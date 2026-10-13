// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupEngine;
import com.vaultbackup.core.BackupDeletionRefusedException;
import com.vaultbackup.core.BackupRecord;
import com.vaultbackup.restore.RestoreCoordinator;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Bukkit is used only on the primary server thread; archive/helper I/O uses one bounded worker. */
public final class VaultBackupPlugin extends JavaPlugin implements Listener {
    private volatile PluginSettings settings;
    private BackupEngine engine;
    private RestoreCoordinator restores;
    private BackupMenus menus;
    private DiscordNotifier notifier;
    private ExecutorService io;
    private BukkitTask automaticTask;
    private BukkitTask restorePollTask;
    private final AtomicBoolean restorePolling = new AtomicBoolean();
    private volatile RestoreCoordinator.Status restoreStatus;
    private volatile boolean restoreStatusUncertain;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile String operation = "operation.idle";
    private volatile String progress = "Ready";
    private volatile List<BackupRecord> catalogue = List.of();
    private volatile long freeSpace;
    private Instant nextAutomatic;
    private final Map<UUID, Boolean> autosave = new LinkedHashMap<>();
    private final Map<String, PendingRestore> pendingRestores = new HashMap<>();
    private final Map<String, DeleteConfirmation> pendingDeletes = new HashMap<>();
    private volatile boolean snapshotActive;
    private volatile String armedJob;
    private boolean restoreGuardStartupFailure;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            settings = PluginSettings.read(getDataFolder().toPath().resolve("config.yml"));
            if (!supported()) { getServer().getPluginManager().disablePlugin(this); return; }
            ServerLifecycle.configureLanguage(language());
            createPrivateDirectories(settings.backupDirectory());
            createPrivateDirectories(settings.stateDirectory());
            engine = new BackupEngine(settings.options());
            // Journal and authenticated receipt validation are one startup safety boundary.
            // Any failure here requests shutdown rather than enabling world startup with unknown restore state.
            restoreGuardStartupFailure = true;
            restores = new RestoreCoordinator(settings.serverRoot(), settings.backupDirectory(), settings.stateDirectory(), settings.helperJar(), helperLanguage());
            restores.startupFailure().ifPresent(message -> {
                tell(Bukkit.getConsoleSender(), text("startup.restorefailed"), false);
                getLogger().warning(text("job.diagnostic", message));
            });
            restores.consumeVerifiedReceipt().ifPresent(receipt -> tell(Bukkit.getConsoleSender(),
                    "\n========================================\n" + text("startup.success", receipt.backupId(), receipt.jobId(),
                            receipt.completedAt(), receipt.rollbackDirectory()) + "\n========================================", true));
            ServerLifecycle.verifyCompatibility();
            restoreGuardStartupFailure = false;
            installHelper();
            io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "VaultBackup-IO"); t.setDaemon(true); return t; });
            notifier = new DiscordNotifier(getLogger());
            menus = new BackupMenus(this);
            BackupCommands commands = new BackupCommands(this);
            Objects.requireNonNull(getCommand("backup")).setExecutor(commands);
            Objects.requireNonNull(getCommand("backup")).setTabCompleter(commands);
            getServer().getPluginManager().registerEvents(menus, this);
            getServer().getPluginManager().registerEvents(this, this);
            refresh(null, null);
            reschedule();
            restorePollTask = getServer().getScheduler().runTaskTimer(this, this::pollRestoreStatus, 40L, 40L);
            getLogger().info(text("startup.enabled"));
        } catch (Exception error) {
            tell(Bukkit.getConsoleSender(), text("startup.failed"), false);
            getLogger().log(Level.SEVERE, text("startup.failed"), error);
            if (restoreGuardStartupFailure) {
                getLogger().severe(text("startup.guard"));
                Bukkit.shutdown();
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private boolean supported() {
        String version = ServerVersions.minecraftVersion(Bukkit.getBukkitVersion());
        if (!(version.equals("26.2") || version.equals("26.3"))) {
            getLogger().severe(text("startup.minecraft", Bukkit.getBukkitVersion())); return false;
        }
        if (Runtime.version().feature() < 25) { getLogger().severe(text("startup.java")); return false; }
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            getLogger().severe(text("startup.folia")); return false;
        } catch (ClassNotFoundException expected) { /* Paper's region scheduler API alone is not a Folia test. */ }
        String name = Bukkit.getName().toLowerCase(Locale.ROOT);
        if (!(name.equals("paper") || name.equals("purpur") || name.equals("spigot"))) {
            getLogger().severe(text("startup.server", Bukkit.getName())); return false;
        }
        return true;
    }

    /** Creation attributes apply only to new directories; never chmod existing user-owned paths. */
    private static void createPrivateDirectories(Path path) throws IOException {
        Path ancestor = path;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (Files.getFileStore(ancestor).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) {
            Files.createDirectories(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        } else {
            // Windows/non-POSIX filesystems retain the administrator's inherited ACL policy.
            Files.createDirectories(path);
        }
    }

    private void installHelper() throws Exception {
        try (InputStream input = getResource("vaultbackup-restore.jar")) {
            if (input == null) { getLogger().warning(text("helper.missing")); return; }
            byte[] data = input.readAllBytes();
            Path target = settings.helperJar();
            if (Files.isSymbolicLink(target)) throw new IOException(text("helper.symlink"));
            if (Files.isRegularFile(target) && MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(data),
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)))) return;
            // Do not replace a helper used by an unfinished restore job; coordinator validates journals before use.
            if (Files.exists(settings.stateDirectory().resolve("active-restore.properties")))
                throw new IOException(text("helper.pending"));
            createPrivateDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".helper-", ".tmp");
            try { Files.write(temp, data); Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        }
    }

    @Override public void onDisable() {
        stopping = true;
        // Record entry into the server shutdown path, not proof that every save has finished.
        // Plugin disable/reload does not write this marker; the helper also requires actual JVM exit.
        if (restores != null && ServerLifecycle.isStopping()) {
            try { restores.noteNormalShutdown(); }
            catch (IOException error) { getLogger().log(Level.SEVERE, text("restore.shutdownfailed"), error); }
        }
        cancelled.set(true);
        if (automaticTask != null) automaticTask.cancel();
        if (restorePollTask != null) restorePollTask.cancel();
        if (menus != null) menus.closeAll();
        restoreAutosave();
        if (io != null) io.shutdownNow();
        // The reservation survives normal shutdown; the helper still waits for actual JVM exit.
        // Never release the lifetime guard, extract archives, or swap server directories here.
    }

    @EventHandler public void worldLoaded(WorldLoadEvent event) {
        if (!snapshotActive) return;
        World world = event.getWorld();
        try {
            if (!world.getWorldFolder().toPath().toRealPath().startsWith(settings.serverRoot())) {
                cancelled.set(true); getLogger().warning(text("world.outside", world.getName())); return;
            }
        } catch (IOException error) { cancelled.set(true); return; }
        autosave.putIfAbsent(world.getUID(), world.isAutoSave());
        world.setAutoSave(false);
        try { ServerLifecycle.flushAll(); }
        catch (Exception error) { cancelled.set(true); getLogger().log(Level.WARNING, text("job.failed", text("job.worldsave")), error); }
    }

    PluginSettings settings() { return settings; }
    BackupMenus menus() { return menus; }
    List<BackupRecord> backups() { return catalogue; }
    boolean busy() { return busy.get(); }
    String language() { return settings == null ? Messages.DEFAULT_LANGUAGE : settings.language(); }
    String helperLanguage() { return language().equals("en_US") ? "en" : "ja"; }
    String text(String key, Object... args) { return Messages.text(language(), key, args); }
    String operation() {
        if (!busy() && restoreReserved()) return restoreStatusUncertain ? text("operation.blocked") : restoreState();
        return text(operation);
    }
    String progress() { return !busy() && restoreReserved() ? text("restore.cancelhint") : Messages.phase(language(), progress); }
    boolean restoreReserved() { return armedJob != null || restoreStatus != null || restoreStatusUncertain; }
    String restoreState() {
        String state = restoreStatus == null ? "WAITING_FOR_MANUAL_STOP" : restoreStatus.state();
        String key = "restore.state." + state;
        return Messages.keys(language()).contains(key) ? text(key) : text("restore.state.UNKNOWN");
    }
    Path restoreLog() { return restoreJobDirectory().resolve("helper.log"); }
    Path restoreStatusFile() { return restoreJobDirectory().resolve("status.properties"); }
    private Path restoreJobDirectory() {
        if (restoreStatus != null) return restoreStatus.jobFile().getParent();
        return settings.stateDirectory().resolve("restore-jobs").resolve(armedJob == null ? "" : armedJob);
    }
    private boolean protectedBackup(String id) {
        return (restoreStatus != null && restoreStatus.backupId().equals(id))
                || pendingRestores.values().stream().anyMatch(p -> p.id.equals(id));
    }
    long freeSpace() { return freeSpace; }
    Instant nextAutomatic() { return nextAutomatic; }
    static String actor(CommandSender sender) { return sender instanceof Player p ? p.getUniqueId().toString() : "console:" + sender.getName(); }
    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB","MiB","GiB","TiB","PiB"};
        double value = bytes; int u = -1;
        do { value /= 1024; u++; } while (value >= 1024 && u < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[u]);
    }
    boolean allowed(CommandSender sender, String action) {
        if (sender.hasPermission("vaultbackup." + action)) return true;
        tell(sender, text("permission.denied", action), false); return false;
    }
    void tell(CommandSender sender, String message, boolean success) {
        if (sender == null) { getLogger().info(message); return; }
        // Some server console implementations split multiline text and reset formatting.
        // Color every separately delivered line so verified success/failure stays visible.
        for (String line : message.split("\\R", -1)) {
            sender.sendMessage(ChatColor.GOLD + "[VaultBackup] " + (success ? ChatColor.GREEN : ChatColor.RED) + line);
        }
    }
    private void main(Runnable runnable) {
        if (stopping || !isEnabled()) return;
        try { getServer().getScheduler().runTask(this, () -> { if (!stopping) runnable.run(); }); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* disable race */ }
    }
    private boolean begin(CommandSender sender, String name) {
        if (stopping || !busy.compareAndSet(false, true)) { tell(sender, text("job.busy", operation()), false); return false; }
        operation = name; progress = "Starting"; cancelled.set(false); return true;
    }
    private boolean isCancelled() { return cancelled.get() || stopping || Thread.currentThread().isInterrupted(); }
    private void finish() { busy.set(false); operation = "operation.idle"; progress = "Ready"; }
    private void reportFailure(CommandSender sender, String context, Exception error) {
        String message = text("job.failed", text(context));
        getLogger().log(Level.WARNING, message, error);
        main(() -> tell(sender, message, false));
        if (notifier != null) notifier.send(settings, message);
    }
    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.replaceAll("[\\r\\n]", " ").substring(0, Math.min(message.length(), 220));
    }
    private void submit(Runnable work) {
        try { io.execute(work); } catch (RejectedExecutionException error) { restoreAutosave(); finish(); }
    }

    void create(CommandSender sender, String label) {
        if (sender != null && !allowed(sender, "create")) return;
        expirePending();
        if (!pendingRestores.isEmpty() || restoreReserved()) { tell(sender, text("backup.blocked"), false); return; }
        if (!begin(sender, "operation.create")) return;
        try {
            snapshotActive = true;
            for (World world : Bukkit.getWorlds()) {
                if (!world.getWorldFolder().toPath().toRealPath().startsWith(settings.serverRoot()))
                    throw new IOException(text("world.outside", world.getName()));
                autosave.put(world.getUID(), world.isAutoSave());
                world.setAutoSave(false);
            }
            Bukkit.savePlayers();
            ServerLifecycle.flushAll();
        } catch (Exception error) { restoreAutosave(); finish(); reportFailure(sender,"job.worldsave",error); return; }
        tell(sender, text("backup.saved"), true);
        PluginSettings jobSettings = settings;
        submit(() -> {
            try {
                BackupRecord record = engine.createBackup(label, this::isCancelled, p -> progress = p);
                main(this::restoreAutosave);
                main(() -> tell(sender, text("backup.created", record.id(), humanBytes(record.compressedBytes())), true));
                notifier.send(jobSettings, text("backup.created.notify", record.id()));
                try {
                    engine.retain(jobSettings.retentionCount() == 0 ? Integer.MAX_VALUE : jobSettings.retentionCount(), jobSettings.retentionBytes() == 0 ? Long.MAX_VALUE : jobSettings.retentionBytes(), restores.protectedBackupIds(), this::isCancelled, p -> progress = p);
                    updateCatalogue();
                } catch (Exception error) { reportFailure(sender, "job.retention", error); }
            } catch (Exception error) { reportFailure(sender, "job.backup", error); }
            finally { main(() -> { restoreAutosave(); finish(); }); }
        });
    }

    private void restoreAutosave() {
        snapshotActive = false;
        for (Map.Entry<UUID, Boolean> entry : autosave.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world != null) {
                try { world.setAutoSave(entry.getValue()); }
                catch (Exception error) { getLogger().log(Level.SEVERE,text("world.autosave"),error); }
            }
        }
        autosave.clear();
    }
    private void updateCatalogue() throws IOException {
        catalogue = List.copyOf(engine.listBackups());
        freeSpace = Files.getFileStore(settings.backupDirectory()).getUsableSpace();
    }
    void refresh(CommandSender sender, Runnable after) {
        if (sender != null && !allowed(sender, "list")) return;
        if (!begin(sender, "operation.catalogue")) return;
        submit(() -> { try { updateCatalogue(); main(() -> { if (after != null) after.run(); }); }
            catch (Exception error) { reportFailure(sender, "job.list", error); }
            finally { main(this::finish); } });
    }
    Optional<BackupRecord> find(String id) { return catalogue.stream().filter(record -> record.id().equals(id)).findFirst(); }

    void requestRestore(CommandSender sender, String id, Consumer<String> reserved) {
        if (!allowed(sender,"restore")) return;
        if (busy()) { tell(sender,text("job.busy",operation()),false); return; }
        expirePending();
        if (!pendingRestores.isEmpty() || restoreReserved()) { tell(sender,text("restore.pending"),false); return; }
        Optional<BackupRecord> selected = find(id);
        if (selected.isEmpty()) { tell(sender,text("list.unknown"),false); return; }
        if (!Files.isRegularFile(settings.helperJar())) { tell(sender,text("restore.helpermissing"),false); return; }
        if (!begin(sender,"operation.verify")) return;
        BackupRecord record = selected.get();
        submit(() -> { try {
                engine.verify(id, this::isCancelled, p -> progress = p);
                RestoreCoordinator.Reservation reservation = restores.reserve(id, record.zipPath(), record.sha256(), Duration.ofSeconds(settings.confirmationSeconds()));
                main(() -> {
                    pendingRestores.put(actor(sender), new PendingRestore(id, reservation.token(), reservation.expiresAt()));
                    tell(sender,text("restore.preview", id, settings.confirmationSeconds()),false);
                    if (reserved != null) reserved.accept(reservation.token());
                });
            } catch (Exception error) { reportFailure(sender,"job.restoreprepare",error); }
            finally { main(this::finish); }
        });
    }

    void confirmRestore(CommandSender sender, String id) {
        if (!allowed(sender,"restore")) return;
        expirePending();
        PendingRestore pending = pendingRestores.get(actor(sender));
        if (pending == null || !pending.id.equals(id)) { tell(sender,text("restore.noconfirmation"),false); return; }
        if (!begin(sender,"operation.reserve")) return;
        pendingRestores.remove(actor(sender));
        submit(() -> {
            try {
                RestoreCoordinator.LaunchResult result = restores.confirm(pending.token);
                armedJob = result.jobId();
                refreshRestoreState();
                main(() -> {
                    tell(sender,text(result.helperReady() ? "restore.reserved" : restoreReserved() ? "restore.notready" : "restore.notready.cleared", id),result.helperReady());
                    if (result.helperReady()) {
                        tell(sender,text("restore.cancelhint"),true);
                        tell(sender,text("restore.shutdowncaveat"),false);
                    }
                    showRestorePaths(sender, result.jobFile().getParent());
                    if (result.helperReady()) tell(sender,text("restore.restart"),true);
                });
            } catch (Exception error) {
                restoreStatusUncertain = true;
                try { refreshRestoreState(); } catch (IOException ignored) { /* keep unsafe state visible */ }
                reportFailure(sender,"job.restorelaunch",error);
            } finally { main(this::finish); }
        });
    }

    void cancelRestore(CommandSender sender) {
        if (!allowed(sender,"restore")) return;
        if (busy()) { tell(sender,text("job.busy",operation()),false); return; }
        PendingRestore pending = pendingRestores.get(actor(sender));
        if (pending != null) {
            restores.cancel(pending.token);
            pendingRestores.remove(actor(sender));
            tell(sender,text("restore.cancelled.pending"),true);
            return;
        }
        if (!restoreReserved()) { tell(sender,text("restore.nocancel"),false); return; }
        if (!begin(sender,"operation.cancel")) return;
        submit(() -> {
            try {
                RestoreCoordinator.CancelResult result = restores.cancelCommitted();
                refreshRestoreState();
                main(() -> {
                    tell(sender,text(result.cancelled() ? "restore.cancelled" : "restore.canceluncertain"),result.cancelled());
                    if (!result.cancelled() && restoreReserved()) showRestorePaths(sender, restoreJobDirectory());
                });
            } catch (Exception error) { reportFailure(sender,"job.restorecancel",error); }
            finally { main(this::finish); }
        });
    }

    /** Poll external journal state off-thread; keep command and inventory callbacks non-blocking. */
    private void pollRestoreStatus() {
        if (!restoreReserved() || busy() || stopping || !restorePolling.compareAndSet(false, true)) return;
        try { io.execute(() -> {
            try { refreshRestoreState(); }
            catch (IOException error) {
                if (!restoreStatusUncertain) getLogger().log(Level.WARNING, text("restore.pollfailed"), error);
                restoreStatusUncertain = true;
            } finally { restorePolling.set(false); }
        }); } catch (RejectedExecutionException error) { restorePolling.set(false); }
    }
    private void refreshRestoreState() throws IOException {
        Optional<RestoreCoordinator.Status> current = restores.status();
        RestoreCoordinator.Status previous = restoreStatus;
        RestoreCoordinator.Status updated = current.orElse(null);
        if (updated != null && (updated.state().equals("HELPER_EXITED") || updated.state().equals("HELPER_UNCERTAIN"))
                && (previous == null || !previous.state().equals(updated.state())))
            getLogger().warning(text("restore.state." + updated.state()));
        restoreStatus = updated;
        armedJob = current.map(RestoreCoordinator.Status::jobId).orElse(null);
        restoreStatusUncertain = false;
    }
    private void showRestorePaths(CommandSender sender, Path directory) {
        tell(sender,text("restore.log", directory.resolve("helper.log")),true);
        tell(sender,text("restore.statusfile", directory.resolve("status.properties")),true);
    }

    private void expirePending() {
        Instant now = Instant.now();
        pendingRestores.entrySet().removeIf(entry -> {
            if (entry.getValue().expires.isAfter(now)) return false;
            try { restores.cancel(entry.getValue().token); } catch (Exception e) { getLogger().warning(text("restore.expired")); }
            return true;
        });
        pendingDeletes.entrySet().removeIf(entry -> !entry.getValue().expires().isAfter(now));
    }
    boolean requestDelete(CommandSender sender, String id) {
        if (!allowed(sender,"delete")) return false;
        pendingDeletes.remove(actor(sender));
        if (busy()) { tell(sender,text("job.busy",operation()),false); return false; }
        if (find(id).isEmpty()) { tell(sender,text("list.unknown"),false); return false; }
        if (protectedBackup(id)) { tell(sender,text("delete.protected"),false); return false; }
        boolean warnLast = DeleteConfirmation.needsLastBackupWarning(catalogue, id);
        if (warnLast) tell(sender,text("delete.last.warning"),false);
        pendingDeletes.put(actor(sender), new DeleteConfirmation(id,
                Instant.now().plusSeconds(settings.confirmationSeconds()), warnLast));
        tell(sender,text("delete.preview", id, settings.confirmationSeconds()),false);
        return true;
    }
    boolean lastDeleteWarning(CommandSender sender, String id) {
        DeleteConfirmation pending = pendingDeletes.get(actor(sender));
        return pending != null && pending.matches(id, Instant.now()) && pending.lastBackupWarning();
    }
    void cancelDelete(CommandSender sender, String id) {
        DeleteConfirmation pending = pendingDeletes.get(actor(sender));
        if (pending != null && pending.id().equals(id)) pendingDeletes.remove(actor(sender));
        tell(sender,text("delete.cancelled"),true);
    }
    void delete(CommandSender sender, String id) {
        if (!allowed(sender,"delete")) return;
        expirePending();
        DeleteConfirmation pending = pendingDeletes.get(actor(sender));
        if (pending == null || !pending.matches(id, Instant.now())) { tell(sender,text("delete.request", id),false); return; }
        if (protectedBackup(id)) { tell(sender,text("delete.protected"),false); return; }
        if (!begin(sender,"operation.delete")) return;
        pendingDeletes.remove(actor(sender));
        submit(() -> { try { engine.delete(id, restores.protectedBackupIds(), pending.lastBackupWarning()); updateCatalogue();
                boolean noneRemain = catalogue.isEmpty();
                main(() -> { tell(sender,text("delete.done", id),true);
                    if (noneRemain) tell(sender,text("delete.noneleft"),false);
                    if (sender instanceof Player p) menus.openList(p,0); });
            } catch (Exception error) {
                DeleteFeedback.report(error, reason -> main(() -> {
                    // A disconnected player cannot receive this new warning. Do not leave
                    // a warned token that could be consumed after reconnecting.
                    if (sender instanceof Player p && !p.isOnline()) return;
                    tell(sender,text(DeleteFeedback.messageKey(reason)),false);
                    if (reason == BackupDeletionRefusedException.Reason.LAST_GOOD_CONFIRMATION_REQUIRED) {
                        // An ordinary preview became stale. No deletion occurred. Record a NEW
                        // warned confirmation; never retry deletion automatically with consent.
                        tell(sender,text("delete.last.warning"),false);
                        pendingDeletes.put(actor(sender), new DeleteConfirmation(id,
                                Instant.now().plusSeconds(settings.confirmationSeconds()), true));
                        tell(sender,text("delete.preview", id, settings.confirmationSeconds()),false);
                        if (sender instanceof Player p) menus.deleteFinal(p,id,0);
                    }
                }), unexpected -> reportFailure(sender,"job.delete",unexpected));
            } finally { main(this::finish); } });
    }

    void reloadSettings(CommandSender sender) {
        if (!allowed(sender,"reload")) return;
        changeSettings(sender, null, null);
    }
    void setting(CommandSender sender, String key, Object value) {
        if (!allowed(sender,"configure")) return;
        changeSettings(sender,key,value);
    }
    private void changeSettings(CommandSender sender, String key, Object value) {
        expirePending();
        if (busy() || !pendingRestores.isEmpty() || restoreReserved()) { tell(sender,text("settings.blocked"),false); return; }
        Path file = getDataFolder().toPath().resolve("config.yml");
        try {
            YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
            if (key != null) yaml.set(key,value);
            PluginSettings candidate = PluginSettings.from(yaml);
            if (!candidate.serverRoot().equals(settings.serverRoot()) || !candidate.backupDirectory().equals(settings.backupDirectory())
                    || !candidate.stateDirectory().equals(settings.stateDirectory()) || !candidate.helperJar().equals(settings.helperJar()))
                throw new IllegalArgumentException(text("settings.restart"));
            BackupEngine candidateEngine = new BackupEngine(candidate.options());
            if (key != null) {
                Path temp = Files.createTempFile(file.getParent(), ".config-", ".tmp");
                try { Files.writeString(temp,yaml.saveToString()); Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                finally { Files.deleteIfExists(temp); }
            }
            restores.setLanguage(candidate.language().equals("en_US") ? "en" : "ja");
            settings = candidate; engine = candidateEngine; ServerLifecycle.configureLanguage(language()); reschedule();
            tell(sender,key == null ? text("settings.reloaded") : text("settings.updated", key),true);
        } catch (Exception error) { tell(sender,text("settings.rejected", safeError(error)),false); }
    }
    private void reschedule() {
        if (automaticTask != null) automaticTask.cancel();
        nextAutomatic = null;
        if (!settings.automatic()) return;
        long ticks = Math.multiplyExact(settings.intervalMinutes(),1200L);
        nextAutomatic = Instant.now().plusSeconds(settings.intervalMinutes()*60);
        automaticTask = getServer().getScheduler().runTaskTimer(this, () -> {
            nextAutomatic = Instant.now().plusSeconds(settings.intervalMinutes()*60);
            if (busy() || restoreReserved()) { getLogger().info(text("automatic.skipped", operation())); return; }
            create(null,"automatic");
        },ticks,ticks);
    }
    void status(CommandSender sender) {
        if (!allowed(sender,"status")) return;
        tell(sender,text("status.summary",operation(),progress(),catalogue.size(),humanBytes(freeSpace),
                settings.automatic() ? text("common.minutes",settings.intervalMinutes()) : text("common.off")),true);
        if (nextAutomatic != null) tell(sender,text("common.next",nextAutomatic),true);
        RestoreCoordinator.Status current = restoreStatus;
        if (current != null) tell(sender,text("restore.status",current.jobId(),current.backupId(),restoreState()),!restoreStatusUncertain);
        if (restoreReserved()) {
            if (restoreStatusUncertain) tell(sender,text("restore.pollfailed"),false);
            showRestorePaths(sender,restoreJobDirectory());
            tell(sender,text("restore.cancelhint"),true);
            tell(sender,text("restore.restart"),true);
        }
    }
    private record PendingRestore(String id, String token, Instant expires) {}
}
