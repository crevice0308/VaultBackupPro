// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupEngine;
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
import java.util.concurrent.atomic.AtomicReference;
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
    private volatile boolean snapshotActive;
    private volatile String armedJob;
    private boolean restoreGuardStartupFailure;
    /** Rejected backup IDs already logged, so each verification failure is reported once. */
    private final Set<String> reportedRejected = ConcurrentHashMap.newKeySet();
    private volatile Map<String, String> rejectedBackups = Map.of();
    /** A restore cancellation requested while a read-only catalogue refresh owned the worker. */
    private final AtomicReference<CommandSender> pendingCancel = new AtomicReference<>();

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            settings = PluginSettings.read(getDataFolder().toPath().resolve("config.yml"));
            if (!supported()) { getServer().getPluginManager().disablePlugin(this); return; }
            ServerLifecycle.configureLanguage(language());
            // An absent state directory cannot contain a restore journal or guard, so failing to
            // create it is a configuration problem: disable the plugin, do not stop the server.
            prepareStateDirectory();
            // Acquire the lifetime root guard before opening the engine or requiring the root.
            restoreGuardStartupFailure = true;
            RestoreCoordinator.StartupResult startup = RestoreCoordinator.start(settings.serverRoot(),
                    settings.backupDirectory(), settings.stateDirectory(), settings.helperJar(), helperLanguage(),
                    message -> getLogger().info(text("restore.helpermessage",message)));
            restores = startup.coordinator();
            if (startup.disposition() == RestoreCoordinator.StartupDisposition.HARD_CONFLICT)
                throw new IOException(startup.message());
            if (startup.disposition() == RestoreCoordinator.StartupDisposition.CONFIG_CONFLICT) {
                // Another server's directory: a configuration error, never a swap of this root.
                restoreGuardStartupFailure = false;
                getLogger().severe(text("startup.storeconflict", startup.message()));
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            if (startup.disposition() == RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED) {
                restoreGuardStartupFailure = false;
                getLogger().warning(text("startup.degraded"));
                getLogger().warning(text("job.diagnostic",startup.message()));
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            restores.startupFailure().ifPresent(message -> {
                tell(Bukkit.getConsoleSender(), text("startup.restorefailed"), false);
                getLogger().warning(text("job.diagnostic", message));
            });
            restores.consumeVerifiedReceipt().ifPresent(receipt -> tell(Bukkit.getConsoleSender(),
                    "\n========================================\n" + text("startup.success", receipt.backupId(), receipt.jobId(),
                            receipt.completedAt(), receipt.rollbackDirectory()) + "\n========================================", true));
            restoreGuardStartupFailure = false;
            ServerLifecycle.verifyCompatibility();
            try { engine = new BackupEngine(settings.options()); }
            catch (IOException error) {
                if (error.getMessage() != null && error.getMessage().contains("belongs to another server root")) {
                    getLogger().severe(text("startup.storeconflict", error.getMessage()));
                    getServer().getPluginManager().disablePlugin(this);
                    return;
                }
                throw error;
            }
            warnAboutEnvironment();
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

    private void prepareStateDirectory() throws IOException {
        Path state = settings.stateDirectory();
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            // A regular file can hold no restore journal or guard: a configuration error only.
            // Symlinks and unreadable directories still go to the restore guard below.
            if (!Files.isSymbolicLink(state) && !Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS))
                throw new IOException(text("startup.statenotdir", state));
            return;
        }
        try { createPrivateDirectories(state); }
        catch (IOException error) { throw new IOException(text("startup.statedir", state), error); }
    }

    private void installHelper() throws Exception {
        try (InputStream input = getResource("vaultbackup-restore.jar")) {
            if (input == null) { getLogger().warning(text("helper.missing")); return; }
            byte[] data = input.readAllBytes();
            byte[] bundled = MessageDigest.getInstance("SHA-256").digest(data);
            Path target = settings.helperJar();
            if (Files.isSymbolicLink(target)) throw new IOException(text("helper.symlink"));
            boolean current = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && MessageDigest.isEqual(bundled,
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)));
            if (!current) {
                // Do not replace a helper used by an unfinished restore job; coordinator validates journals before use.
                if (Files.exists(settings.stateDirectory().resolve("active-restore.properties")))
                    throw new IOException(text("helper.pending"));
                createPrivateDirectories(target.getParent());
                durableReplace(target, data);
            }
            // Reservations launch a private copy of exactly these bytes; a different helper is refused.
            restores.requireHelperSha256(HexFormat.of().formatHex(bundled));
        }
    }

    /** Writes a sibling temp file, forces it, atomically replaces the target, then syncs the directory. */
    private static void durableReplace(Path target, byte[] data) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + "-", ".tmp");
        try {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(target.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            catch (IOException | UnsupportedOperationException unsupported) {
                // Windows cannot open directories for fsync; the forced file and atomic rename remain.
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) throw unsupported;
            }
        } finally { Files.deleteIfExists(temp); }
    }

    /** Startup warnings for environments that silently weaken backups or the restore helper. */
    private void warnAboutEnvironment() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String encoding = System.getProperty("sun.jnu.encoding", "");
        if (!windows && !encoding.equalsIgnoreCase("UTF-8") && !encoding.equalsIgnoreCase("UTF8"))
            getLogger().warning(text("startup.encoding", encoding));
        for (Path directory : List.of(settings.stateDirectory(), settings.backupDirectory(), settings.helperJar().getParent())) {
            try {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                Set<java.nio.file.attribute.PosixFilePermission> permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
                if (permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
                    getLogger().warning(text("startup.permissions", directory));
            } catch (IOException | UnsupportedOperationException notPosix) { /* Windows keeps its ACL policy. */ }
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
        catch (Error fatal) { cancelled.set(true); throw fatal; } // Never publish a backup of an unsaved world.
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
        return restoreStatus != null && restoreStatus.backupId().equals(id);
    }
    long freeSpace() { return freeSpace; }
    Instant nextAutomatic() { return nextAutomatic; }
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
        // Only template line feeds start a new prefixed line; Unicode line separators inside
        // interpolated data never do, so stored text cannot forge extra plugin lines.
        for (String line : message.split("\\r?\\n", -1)) {
            sender.sendMessage(ChatColor.GOLD + "[VaultBackup] " + (success ? ChatColor.GREEN : ChatColor.RED)
                    + line.replace('\u2028', ' ').replace('\u2029', ' ').replace('\u0085', ' ').replace('\r', ' '));
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
        String printable = Messages.safe(message);
        return printable.substring(0, Math.min(printable.length(), 220));
    }
    private void submit(Runnable work) {
        try { io.execute(work); } catch (RejectedExecutionException error) { restoreAutosave(); finish(); }
    }

    void create(CommandSender sender, String label) {
        if (sender != null && !allowed(sender, "create")) return;
        if (restoreReserved()) { tell(sender, text("backup.blocked"), false); return; }
        if (!begin(sender, "operation.create")) return;
        tell(sender, text("backup.starting"), true);
        boolean saved = false;
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
            saved = true;
        } catch (Exception error) { reportFailure(sender,"job.worldsave",error); }
        finally {
            // Also after an Error (for example OutOfMemoryError while saving): never leave autosave
            // off or the plugin busy. The Error itself still propagates.
            if (!saved) { restoreAutosave(); finish(); }
        }
        if (!saved) return;
        tell(sender, text("backup.saved"), true);
        PluginSettings jobSettings = settings;
        submit(() -> {
            try {
                BackupRecord record = engine.createBackup(label, this::isCancelled, p -> progress = p);
                main(this::restoreAutosave);
                try {
                    engine.retain(jobSettings.retentionCount() == 0 ? Integer.MAX_VALUE : jobSettings.retentionCount(), jobSettings.retentionBytes() == 0 ? Long.MAX_VALUE : jobSettings.retentionBytes(), restores.protectedBackupIds(), record.id(), this::isCancelled, p -> progress = p);
                } catch (Exception error) { reportFailure(sender, "job.retention", error); }
                // A failed delete/fsync may already have withdrawn the archive's commit marker.
                // Reconcile the resulting store even after retention failure; never equate the
                // earlier successful creation with a presently usable recovery point.
                reportBackupOutcome(sender, record, jobSettings);
            } catch (Exception error) { reportFailure(sender, "job.backup", error); }
            finally { main(() -> { restoreAutosave(); finish(); }); }
        });
    }

    private void reportBackupOutcome(CommandSender sender, BackupRecord created, PluginSettings jobSettings) {
        List<BackupRecord> remaining;
        try {
            remaining = List.copyOf(engine.listBackups());
            catalogue = remaining;
            noteRejected(sender);
        } catch (Exception error) {
            String outcome = text("backup.outcomeunknown",created.id());
            main(() -> tell(sender,outcome,false));
            reportFailure(sender,"job.retention",error);
            if (notifier != null) notifier.send(jobSettings,outcome);
            return;
        }
        boolean retained = remaining.stream().anyMatch(record -> record.id().equals(created.id()));
        boolean removed = Files.notExists(created.zipPath(),LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(created.metadataPath(),LinkOption.NOFOLLOW_LINKS);
        String outcome = retained ? text("backup.created",created.id(),humanBytes(created.compressedBytes()))
                : text(removed ? "backup.retentionremoved" : "backup.unavailable",created.id());
        String summary = remaining.isEmpty() ? text("backup.retentionempty") : text("backup.retentioncount",remaining.size());
        main(() -> { tell(sender,outcome,retained); tell(sender,summary,!remaining.isEmpty()); });
        if (notifier != null) notifier.send(jobSettings,outcome + " " + summary);
        try { freeSpace = Files.getFileStore(settings.backupDirectory()).getUsableSpace(); }
        catch (IOException error) { reportFailure(sender,"job.list",error); }
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
    /**
     * revalidate re-reads every archive (startup and administrator refresh/list). Internal
     * follow-ups reuse this engine's earlier full verification of files that are provably unchanged.
     */
    private void updateCatalogue(boolean revalidate) throws IOException {
        catalogue = List.copyOf(revalidate ? engine.revalidateBackups(this::isCancelled, p -> progress = p) : engine.listBackups());
        freeSpace = Files.getFileStore(settings.backupDirectory()).getUsableSpace();
        noteRejected(null);
    }
    /**
     * Archives that fail verification drop out of the list, restore and retention. Log each one
     * once (and notify), so silent storage corruption is visible instead of a shorter list.
     */
    private void noteRejected(CommandSender sender) {
        Map<String, String> current = engine.rejectedBackups();
        rejectedBackups = current;
        reportedRejected.retainAll(current.keySet());
        for (Map.Entry<String, String> entry : current.entrySet()) {
            if (!reportedRejected.add(entry.getKey())) continue;
            getLogger().warning(text("backup.rejected", entry.getKey(), Messages.safe(entry.getValue())));
            // Archive paths in the reason stay in the server log; the webhook gets only the ID.
            if (notifier != null) notifier.send(settings, text("backup.rejectednotice", entry.getKey()));
        }
    }
    void reportRejected(CommandSender sender) {
        if (!rejectedBackups.isEmpty()) tell(sender, text("list.rejected", rejectedBackups.size()), false);
    }
    void refresh(CommandSender sender, Runnable after) {
        if (sender != null && !allowed(sender, "list")) return;
        if (busy() || restoreReserved()) {
            // Read-only navigation remains available while the I/O worker owns a mutation. While a
            // restore is reserved, never start a full re-hash: it would hold the worker that
            // cancelling the reservation needs.
            if (sender != null) tell(sender,text(busy() ? "list.cached" : "list.reserved"),true);
            if (after != null) after.run();
            return;
        }
        if (!begin(sender, "operation.catalogue")) return;
        submit(() -> { try { updateCatalogue(true); main(() -> { if (after != null && pendingCancel.get() == null) after.run(); }); }
            catch (Exception error) { if (pendingCancel.get() == null) reportFailure(sender, "job.list", error); }
            finally { main(() -> { finish(); CommandSender canceller = pendingCancel.getAndSet(null); if (canceller != null) cancelRestore(canceller); }); } });
    }
    Optional<BackupRecord> find(String id) { return catalogue.stream().filter(record -> record.id().equals(id)).findFirst(); }

    /** One explicit administrator action verifies and reserves; it never stops the server. */
    void requestRestore(CommandSender sender, String id) {
        if (!allowed(sender,"restore")) return;
        if (busy()) { tell(sender,text("job.busy",operation()),false); return; }
        if (restoreReserved()) { tell(sender,text("restore.pending"),false); return; }
        if (find(id).isEmpty()) { tell(sender,text("list.unknown"),false); return; }
        if (!Files.isRegularFile(settings.helperJar())) { tell(sender,text("restore.helpermissing"),false); return; }
        if (!begin(sender,"operation.verify")) return;
        tell(sender,text("restore.verifying",id),true);
        submit(() -> {
            String preparedToken = null;
            boolean handedOff = false;
            try {
                // Reserve exactly what was just verified, never the possibly stale catalogue entry.
                BackupRecord record = engine.verify(id, this::isCancelled, p -> progress = p);
                if (isCancelled()) return;
                restores.setLanguage(helperLanguage());
                RestoreCoordinator.Reservation reservation = restores.reserve(record.id(), record.zipPath(),
                        record.sha256(), Duration.ofSeconds(settings.confirmationSeconds()));
                preparedToken = reservation.token();
                String token = preparedToken;
                main(() -> {
                    // Verification can be slow. Recheck permission and connection on the server
                    // thread immediately before committing this explicit action.
                    if (isCancelled() || sender instanceof Player p && !p.isOnline()) {
                        restores.cancel(token); finish(); return;
                    }
                    if (!allowed(sender,"restore")) { restores.cancel(token); finish(); return; }
                    operation = "operation.reserve";
                    submit(() -> commitVerifiedRestore(sender, record.id(), token));
                });
                handedOff = true;
            } catch (Exception error) {
                if (!isCancelled()) reportFailure(sender,"job.restoreprepare",error);
            } finally {
                // Also after an Error: an unconsumed token is dropped and busy is released.
                if (!handedOff) {
                    if (preparedToken != null) restores.cancel(preparedToken);
                    main(this::finish);
                }
            }
        });
    }

    private void commitVerifiedRestore(CommandSender sender, String id, String token) {
        try {
            if (isCancelled()) { restores.cancel(token); return; }
            RestoreCoordinator.LaunchResult result;
            try { result = restores.confirm(token); }
            catch (Exception error) {
                restores.cancel(token);
                restoreStatusUncertain = true;
                try { refreshRestoreState(); } catch (IOException ignored) { /* keep unsafe state visible */ }
                reportFailure(sender,"job.restorelaunch",error);
                return;
            }
            // The reason (e.g. helper could not start) is otherwise only in the job directory, if anywhere.
            if (!result.helperReady()) getLogger().warning(text("job.diagnostic", result.message()));
            armedJob = result.jobId();
            try { refreshRestoreState(); }
            catch (Exception error) {
                // confirm() returned, so the reservation is committed and possibly armed: never
                // report that as a failed reservation, or the administrator may stop the server.
                restoreStatusUncertain = true;
                getLogger().log(Level.WARNING, text("restore.pollfailed"), error);
                main(() -> {
                    tell(sender,text("restore.committeduncertain"),false);
                    tell(sender,text("restore.cancelhint"),true);
                    showRestorePaths(sender, result.jobFile().getParent());
                });
                return;
            }
            main(() -> {
                tell(sender,text(result.helperReady() ? "restore.reserved" : restoreReserved() ? "restore.notready" : "restore.notready.cleared", id),result.helperReady());
                if (result.helperReady()) {
                    tell(sender,text("restore.cancelhint"),true);
                    tell(sender,text("restore.shutdowncaveat"),false);
                }
                showRestorePaths(sender, result.jobFile().getParent());
                if (result.helperReady()) tell(sender,text("restore.restart"),true);
            });
        } finally { main(this::finish); }
    }

    void cancelRestore(CommandSender sender) {
        if (!allowed(sender,"restore")) return;
        if (busy() && operation.equals("operation.verify")) {
            cancelled.set(true);
            tell(sender,text("restore.verificationcancelled"),true);
            return;
        }
        if (busy() && operation.equals("operation.catalogue") && restoreReserved()) {
            // A read-only re-hash must never delay disarming a reservation: stop it, then cancel.
            pendingCancel.set(sender);
            cancelled.set(true);
            tell(sender,text("restore.cancelqueued"),true);
            return;
        }
        if (busy()) { tell(sender,text("job.busy",operation()),false); return; }
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

    /** Explicit target action. The engine rechecks archive integrity and restore protection under lock. */
    void delete(CommandSender sender, String id) { delete(sender, id, false); }
    /** reopenGui: only a deletion started from the GUI returns to the backup list afterwards. */
    void delete(CommandSender sender, String id, boolean reopenGui) {
        if (!allowed(sender,"delete")) return;
        if (busy()) { tell(sender,text("job.busy",operation()),false); return; }
        if (find(id).isEmpty()) { tell(sender,text("list.unknown"),false); return; }
        if (protectedBackup(id)) { tell(sender,text("delete.protected"),false); return; }
        if (!begin(sender,"operation.delete")) return;
        submit(() -> {
            try {
                if (isCancelled()) return;
                engine.delete(id, restores.protectedBackupIds());
                updateCatalogue(false);
                main(() -> {
                    tell(sender,text("delete.done", id),true);
                    if (reopenGui && sender instanceof Player p && p.isOnline() && p.hasPermission("vaultbackup.admin")
                            && p.hasPermission("vaultbackup.gui") && p.hasPermission("vaultbackup.list")) menus.openList(p,0);
                });
            } catch (Exception error) {
                DeleteFeedback.report(error, reason -> main(() -> tell(sender,text(DeleteFeedback.messageKey(reason)),false)),
                        unexpected -> reportFailure(sender,"job.delete",unexpected));
            } finally { main(this::finish); }
        });
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
        if (busy()) { tell(sender,text("settings.blocked"),false); return; }
        Path file = getDataFolder().toPath().resolve("config.yml");
        try {
            YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
            if (key != null) yaml.set(key,value);
            PluginSettings candidate = PluginSettings.from(yaml);
            if (!candidate.serverRoot().equals(settings.serverRoot()) || !candidate.backupDirectory().equals(settings.backupDirectory())
                    || !candidate.stateDirectory().equals(settings.stateDirectory()) || !candidate.helperJar().equals(settings.helperJar()))
                throw new IllegalArgumentException(text("settings.restart"));
            boolean reserved = restoreReserved();
            if (reserved && !safeReservedSettings(settings, candidate))
                throw new IllegalArgumentException(text("settings.reserved"));
            BackupEngine candidateEngine = reserved ? engine : new BackupEngine(candidate.options());
            // Forced before the atomic rename: a power loss must never leave an empty config.yml.
            if (key != null) durableReplace(file, yaml.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // An already committed helper retains the language stored in its immutable job.
            if (!reserved) restores.setLanguage(candidate.language().equals("en_US") ? "en" : "ja");
            settings = candidate; engine = candidateEngine; ServerLifecycle.configureLanguage(language()); reschedule();
            tell(sender,key == null ? text("settings.reloaded") : text("settings.updated", key),true);
        } catch (Exception error) { tell(sender,text("settings.rejected", safeError(error)),false); }
    }
    static boolean safeReservedSettings(PluginSettings current, PluginSettings candidate) {
        return candidate.equals(new PluginSettings(current.serverRoot(), current.backupDirectory(),
                current.stateDirectory(), current.helperJar(), current.includes(), current.excludes(),
                candidate.automatic(), candidate.intervalMinutes(), current.retentionCount(),
                current.retentionBytes(), current.compressionLevel(), current.maxEntries(), current.maxEntryBytes(),
                current.maxTotalBytes(), current.maxCompressionRatio(), current.confirmationSeconds(),
                current.discordEnabled(), current.webhook(), candidate.language()));
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
}
