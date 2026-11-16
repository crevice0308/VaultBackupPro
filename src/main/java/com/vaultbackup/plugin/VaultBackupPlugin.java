// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupEngine;
import com.vaultbackup.core.BackupRecord;
import com.vaultbackup.restore.RestoreCoordinator;
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
import java.util.logging.Level;

/**
 * Bukkit is used only on the primary server thread; archive/helper I/O uses one bounded worker.
 * This class uses only API that Spigot and Paper share unchanged. Whatever the two projects
 * recommend differently (text, commands, items, save and stop handling) is behind ServerPlatform.
 */
public final class VaultBackupPlugin extends JavaPlugin implements Listener {
    /** Oldest Minecraft version; equals api-version in plugin.yml, which makes older servers refuse the plugin. */
    static final String MINIMUM_MINECRAFT = "26.2";
    /** Newest Minecraft version this release was built against and checked with. */
    static final String VERIFIED_MINECRAFT = "26.3";
    /** How many changed-while-copied files are named in the log for one backup. */
    private static final int UNSTABLE_LOG_LIMIT = 20;
    /** How many refused paths a sender is shown; the server log has everything the engine reported. */
    private static final int REFUSED_SHOWN = 5;
    /** An automatic backup that is already due waits this long after enabling, so that it never runs during startup itself. */
    static final Duration AUTOMATIC_SETTLE = Duration.ofMinutes(2);
    private volatile PluginSettings settings;
    private ServerPlatform platform;
    private BackupEngine engine;
    private RestoreCoordinator restores;
    private BackupMenus menus;
    private DiscordNotifier notifier;
    private ExecutorService io;
    private BukkitTask automaticTask;
    private BukkitTask restorePollTask;
    private long automaticIntervalMinutes;
    private final AtomicBoolean restorePolling = new AtomicBoolean();
    private volatile RestoreCoordinator.Status restoreStatus;
    private volatile boolean restoreStatusUncertain;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile String operation = "operation.idle";
    private volatile String progress = "Ready";
    private volatile List<BackupRecord> catalogue = List.of();
    /** Archives in the store that failed verification, by ID, with the reason. */
    private volatile Map<String, String> rejected = Map.of();
    /** False until the store has been read once; an empty list before that means "not known yet". */
    private volatile boolean catalogueLoaded;
    /** True once the bundled helper is installed and pinned by hash. Without it no restore is offered. */
    private volatile boolean helperAvailable;
    private volatile long freeSpace;
    private Instant nextAutomatic;
    /** Worlds whose automatic saving this plugin switched off, and therefore switches on again. */
    private final Set<UUID> autosave = new LinkedHashSet<>();
    /** True once the next automatic backup has been derived from the backups found in the store. */
    private boolean automaticAnchored;
    private volatile boolean snapshotActive;
    private volatile String armedJob;
    private boolean restoreGuardStartupFailure;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            settings = PluginSettings.read(getDataFolder().toPath().resolve("config.yml"));
            platform = ServerPlatform.detect(this);
            if (!supported()) { getServer().getPluginManager().disablePlugin(this); return; }
            // An absent state directory cannot contain a restore journal or guard, so failing to
            // create it is a configuration problem: disable the plugin, do not stop the server.
            prepareStateDirectory();
            // Acquire the lifetime root guard before opening the engine or requiring the root.
            restoreGuardStartupFailure = true;
            RestoreCoordinator.StartupResult startup = RestoreCoordinator.start(settings.serverRoot(),
                    settings.backupDirectory(), settings.stateDirectory(), settings.helperJar(), helperLanguage(),
                    message -> getLogger().info(text("restore.helpermessage",message)));
            restores = startup.coordinator();
            if (startup.disposition() == RestoreCoordinator.StartupDisposition.HARD_CONFLICT) {
                // The server is stopped only when its data may be in use by another process or in
                // the middle of a swap. Paths that merely cannot be used disable the plugin instead.
                restoreGuardStartupFailure = startup.guardHeldElsewhere()
                        || RestoreCoordinator.journalMayExist(settings.stateDirectory());
                throw new IOException(startup.message());
            }
            if (startup.disposition() == RestoreCoordinator.StartupDisposition.GUARDED_DEGRADED) {
                restoreGuardStartupFailure = false;
                getLogger().warning(text("startup.degraded"));
                getLogger().warning(text("job.diagnostic",startup.message()));
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            if (restores.inheritedRootGuard()) getLogger().info(text("startup.reloaded"));
            restores.startupFailure().ifPresent(message -> {
                tell(Bukkit.getConsoleSender(), text(restores.startupFinding() == RestoreCoordinator.StartupFinding.FAILED_RESTORE_ROLLED_BACK
                        ? "startup.rolledback" : "startup.stalecancelled"), false);
                getLogger().warning(text("job.diagnostic", message));
            });
            restores.consumeVerifiedReceipt().ifPresent(receipt -> tell(Bukkit.getConsoleSender(),
                    "\n========================================\n" + text("startup.success", receipt.backupId(), receipt.jobId(),
                            receipt.completedAt(), receipt.rollbackDirectory()) + "\n========================================", true));
            restoreGuardStartupFailure = false;
            engine = new BackupEngine(settings.options());
            warnAboutEnvironment(settings);
            installHelper();
            io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "VaultBackup-IO"); t.setDaemon(true); return t; });
            notifier = new DiscordNotifier(getLogger());
            menus = new BackupMenus(this);
            platform.registerCommands(new CommandActions(this));
            getServer().getPluginManager().registerEvents(menus, this);
            getServer().getPluginManager().registerEvents(this, this);
            reschedule();
            refresh(null, true, null);
            restorePollTask = getServer().getScheduler().runTaskTimer(this, this::pollRestoreStatus, 40L, 40L);
            getLogger().info(text("startup.enabled", version(), platform.serverName(), platform.minecraftVersion()));
        } catch (Exception | LinkageError error) {
            tell(Bukkit.getConsoleSender(), text("startup.failed"), false);
            getLogger().log(Level.SEVERE, text("startup.failed"), error);
            if (restoreGuardStartupFailure) {
                getLogger().severe(text("startup.guard"));
                Bukkit.shutdown();
            } else getLogger().severe(text("startup.disabledonly"));
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** Conditions that do not stop the plugin but make backups fail or select nothing; said once, at startup and on reload. */
    private void warnAboutEnvironment(PluginSettings checked) {
        // POSIX file names are bytes. A JVM started without a UTF-8 locale cannot name non-ASCII files,
        // and the engine then refuses them. Windows uses UTF-16 file names whatever this property says.
        String encoding = System.getProperty("sun.jnu.encoding", "");
        boolean utf8;
        try { utf8 = java.nio.charset.Charset.forName(encoding).equals(java.nio.charset.StandardCharsets.UTF_8); }
        catch (RuntimeException unknown) { utf8 = false; }
        if (!utf8 && !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            getLogger().warning(text("startup.encoding", encoding.isEmpty() ? "?" : encoding));
        List<String> patterns = new ArrayList<>(checked.includes()); patterns.addAll(checked.excludes());
        for (String pattern : BackupEngine.unsupportedGlobSyntax(patterns)) getLogger().warning(text("settings.glob", pattern));
    }

    /**
     * The plugin uses only public server API, so it is not tied to one exact server build.
     * Too-old servers are refused (they already refuse the plugin through api-version), regionized
     * servers are refused, and anything newer or unknown runs with a clear notice that it is untested.
     */
    private boolean supported() {
        if (Runtime.version().feature() < 25) { getLogger().severe(text("startup.java")); return false; }
        if (platform.regionized()) { getLogger().severe(text("startup.folia")); return false; }
        String version = platform.minecraftVersion();
        if (version.isEmpty()) {
            getLogger().warning(text("startup.versionunknown", Bukkit.getBukkitVersion(), VERIFIED_MINECRAFT));
        } else if (BukkitPlatform.compareVersions(version, MINIMUM_MINECRAFT) < 0) {
            getLogger().severe(text("startup.minecraft", MINIMUM_MINECRAFT, version)); return false;
        } else if (BukkitPlatform.compareVersions(version, VERIFIED_MINECRAFT) > 0) {
            getLogger().warning(text("startup.untested", version, VERIFIED_MINECRAFT));
        }
        String name = platform.serverName().toLowerCase(Locale.ROOT);
        if (!Set.of("paper", "purpur", "spigot", "craftbukkit").contains(name))
            getLogger().warning(text("startup.server", platform.serverName()));
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
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) return;
        try { createPrivateDirectories(state); }
        catch (IOException error) { throw new IOException(text("startup.statedir", state), error); }
    }

    private void installHelper() throws Exception {
        try (InputStream input = getResource("vaultbackup-restore.jar")) {
            // Without the bundled helper there is no hash to pin, and an unpinned JAR is never launched.
            if (input == null) { helperAvailable = false; getLogger().warning(text("helper.missing")); return; }
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
                Path temp = Files.createTempFile(target.getParent(), ".helper-", ".tmp");
                try { Files.write(temp, data); Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                finally { Files.deleteIfExists(temp); }
            }
            // Launch only these exact bytes: a helper swapped on disk later is refused, not executed.
            restores.requireHelperSha256(HexFormat.of().formatHex(bundled));
            helperAvailable = true;
        }
    }

    @Override public void onDisable() {
        stopping = true;
        noteShutdown();
        cancelled.set(true);
        if (automaticTask != null) automaticTask.cancel();
        if (restorePollTask != null) restorePollTask.cancel();
        if (menus != null) menus.closeAll();
        restoreAutosave();
        if (io != null) io.shutdownNow();
        if (notifier != null) notifier.close();
        // The reservation survives normal shutdown; the helper still waits for actual JVM exit.
        // Never release the lifetime guard, extract archives, or swap server directories here.
    }

    /**
     * Records authenticated stop intent for a waiting restore reservation. It is never proof that
     * every save has finished; the helper also requires the actual JVM exit.
     * Paper reports whether the server is stopping, so a reload or unload leaves no marker there.
     * Spigot has no such API: the marker then says only that the plugin was disabled, and the
     * helper accepts it solely for a JVM that exits shortly afterwards.
     */
    private void noteShutdown() {
        if (restores == null || platform == null) return;
        try {
            ServerPlatform.StopEvidence evidence = platform.stopEvidence();
            RestoreCoordinator.Status reserved = restoreStatus;
            boolean waiting = reserved != null && reserved.state().equals("WAITING_FOR_MANUAL_STOP");
            if (evidence == ServerPlatform.StopEvidence.NOT_STOPPING) {
                if (waiting) getLogger().warning(text("restore.disablednotice", reserved.backupId()));
                return;
            }
            restores.noteNormalShutdown(evidence == ServerPlatform.StopEvidence.STOPPING
                    ? RestoreCoordinator.ShutdownEvidence.SERVER_STOPPING : RestoreCoordinator.ShutdownEvidence.PLUGIN_DISABLED);
            // The last thing an administrator reads before the process ends: what happens to the data next.
            if (waiting) getLogger().warning(text(evidence == ServerPlatform.StopEvidence.STOPPING
                    ? "restore.stopnotice" : "restore.stopnotice.disabled", reserved.backupId()));
        } catch (IOException | RuntimeException | LinkageError error) {
            getLogger().log(Level.SEVERE, text("restore.shutdownfailed"), error);
        }
    }

    @EventHandler public void worldLoaded(WorldLoadEvent event) {
        if (!snapshotActive) return;
        World world = event.getWorld();
        try {
            if (!world.getWorldFolder().toPath().toRealPath().startsWith(settings.serverRoot())) {
                cancelled.set(true); getLogger().warning(text("world.outside", world.getName())); return;
            }
        } catch (IOException error) { cancelled.set(true); return; }
        // Keep the new world from writing region files while they may be copied. It is not saved
        // now: a save would rewrite files of every world underneath the running copy.
        suspendAutosave(world);
    }
    /** Only a world this plugin switched off is switched on again afterwards; other settings stay the administrator's. */
    private void suspendAutosave(World world) {
        if (!world.isAutoSave()) return;
        world.setAutoSave(false);
        autosave.add(world.getUID());
    }

    PluginSettings settings() { return settings; }
    ServerPlatform platform() { return platform; }
    String version() { return platform == null ? "" : platform.pluginVersion(); }
    BackupMenus menus() { return menus; }
    List<BackupRecord> backups() { return catalogue; }
    Map<String, String> rejectedArchives() { return rejected; }
    boolean catalogueLoaded() { return catalogueLoaded; }
    boolean helperAvailable() { return helperAvailable && Files.isRegularFile(settings.helperJar(), LinkOption.NOFOLLOW_LINKS); }
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
        RestoreCoordinator.Status current = restoreStatus;
        String state = current == null ? "WAITING_FOR_MANUAL_STOP" : current.state();
        String key = "restore.state." + state;
        return Messages.keys(language()).contains(key) ? text(key) : text("restore.state.UNKNOWN");
    }
    Path restoreLog() { return restoreJobDirectory().resolve("helper.log"); }
    Path restoreStatusFile() { return restoreJobDirectory().resolve("status.properties"); }
    private Path restoreJobDirectory() {
        RestoreCoordinator.Status current = restoreStatus;
        if (current != null) return current.jobFile().getParent();
        String job = armedJob;
        return settings.stateDirectory().resolve("restore-jobs").resolve(job == null ? "" : job);
    }
    private boolean protectedBackup(String id) {
        RestoreCoordinator.Status current = restoreStatus;
        return current != null && current.backupId().equals(id);
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
        if (!CommandActions.acceptable(sender)) { tell(sender, text("permission.sender"), false); return false; }
        if (sender.hasPermission("vaultbackup." + action)) return true;
        tell(sender, text("permission.denied", action), false); return false;
    }
    void tell(CommandSender sender, String message, boolean success) {
        if (sender == null || platform == null) {
            if (success) getLogger().info(message); else getLogger().warning(message);
            return;
        }
        // Some server console implementations split multiline text and reset formatting.
        // Color every separately delivered line so verified success/failure stays visible.
        for (String line : message.split("\\R", -1)) platform.send(sender, success, line);
    }
    /**
     * Tells administrators who are online about a problem nobody asked about, such as a failed
     * automatic backup, which would otherwise only reach the server log. Primary thread only.
     */
    private void notifyAdministrators(String message, CommandSender except) {
        for (Player player : Bukkit.getOnlinePlayers())
            if (player != except && player.hasPermission("vaultbackup.notify")) tell(player, message, false);
    }
    /** Who did what. Menu clicks never appear in the server's own command log. */
    private void audit(CommandSender sender, String key, Object... args) {
        if (sender == null) return;
        String name = sender.getName();
        getLogger().info(text("audit.entry", name == null ? "?" : name.replaceAll("\\p{Cntrl}", "?"), text(key, args)));
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
    private void reportFailure(CommandSender sender, String context, Throwable error) {
        String message = text("job.failed", text(context));
        getLogger().log(Level.WARNING, message, error);
        List<String> explanation = explain(error);
        String shown = explanation.isEmpty() ? message : message + "\n" + String.join("\n", explanation);
        // The console already has the log entry above; an unattended job additionally reaches online administrators.
        if (sender == null) main(() -> notifyAdministrators(shown, null));
        else main(() -> tell(sender, shown, false));
        if (notifier != null) notifier.send(settings, explanation.isEmpty() ? message : message + " " + explanation.get(0));
    }
    /** What the administrator can act on, in their language. The server log has the complete exception. */
    private List<String> explain(Throwable error) {
        if (error instanceof BackupEngine.InsufficientSpaceException space)
            return List.of(text("backup.nospace", humanBytes(space.neededBytes()), humanBytes(space.availableBytes())));
        if (error instanceof BackupEngine.SourceRefusedException refused) {
            List<String> lines = new ArrayList<>();
            lines.add(text("backup.refused", refused.total()));
            refused.problems().stream().limit(REFUSED_SHOWN).forEach(problem -> lines.add(" - " + problem.replaceAll("\\p{Cntrl}", "?")));
            if (refused.total() > REFUSED_SHOWN) lines.add(text("backup.refusedmore", refused.total() - REFUSED_SHOWN));
            return lines;
        }
        if (error instanceof RestoreCoordinator.RestoreUnavailableException unavailable)
            return List.of(switch (unavailable.reason()) {
                case ROOT_IS_MOUNT_POINT -> text("restore.unavailable.mount");
                case PARENT_NOT_WRITABLE -> text("restore.unavailable.parent");
                case INSUFFICIENT_SPACE -> text("restore.unavailable.space", humanBytes(unavailable.neededBytes()), humanBytes(unavailable.availableBytes()));
            });
        return List.of();
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
        if (restoreReserved()) { tell(sender, text("backup.blocked"), false); return; }
        if (!begin(sender, "operation.create")) return;
        // Whatever goes wrong below, including an Error from a server this was never tested with,
        // world saving is switched back on and the plugin does not stay "busy" for good.
        boolean handedOff = false;
        try {
            audit(sender, "audit.create", label);
            tell(sender, text("backup.starting"), true);
            snapshotActive = true;
            for (World world : Bukkit.getWorlds()) {
                if (!world.getWorldFolder().toPath().toRealPath().startsWith(settings.serverRoot()))
                    throw new IOException(text("world.outside", world.getName()));
                suspendAutosave(world);
            }
            platform.saveAll();
            tell(sender, text("backup.saved"), true);
            PluginSettings jobSettings = settings;
            BackupEngine jobEngine = engine;
            submit(() -> {
                try {
                    List<String> unstable = new ArrayList<>();
                    BackupRecord record = jobEngine.createBackup(label, this::isCancelled, p -> progress = p,
                            path -> { if (unstable.size() < UNSTABLE_LOG_LIMIT) unstable.add(path); });
                    main(this::restoreAutosave);
                    reportUnstable(sender, record, unstable);
                    try {
                        jobEngine.retain(jobSettings.retentionCount() == 0 ? Integer.MAX_VALUE : jobSettings.retentionCount(), jobSettings.retentionBytes() == 0 ? Long.MAX_VALUE : jobSettings.retentionBytes(), restores.protectedBackupIds(), this::isCancelled, p -> progress = p);
                    } catch (Exception error) { reportFailure(sender, "job.retention", error); }
                    // A failed delete/fsync may already have withdrawn the archive's commit marker.
                    // Reconcile the resulting store even after retention failure; never equate the
                    // earlier successful creation with a presently usable recovery point.
                    reportBackupOutcome(sender, record, jobSettings);
                } catch (Exception | LinkageError error) {
                    // A server that stops while a backup runs interrupts it. That is not a failed backup to alert about.
                    if (stopping) getLogger().info(text("backup.interrupted"));
                    else reportFailure(sender, "job.backup", error);
                }
                finally { main(() -> { restoreAutosave(); finish(); }); }
            });
            handedOff = true;
        } catch (Exception | LinkageError error) { reportFailure(sender, "job.worldsave", error); }
        finally { if (!handedOff) { restoreAutosave(); finish(); } }
    }

    /** Files that kept changing while copied are in the archive as last read; say so instead of implying a clean copy. */
    private void reportUnstable(CommandSender sender, BackupRecord record, List<String> named) {
        if (record.unstableEntries() == 0) return;
        String summary = text("backup.unstable", record.unstableEntries());
        getLogger().warning(summary);
        for (String path : named) getLogger().warning(text("backup.unstablefile", path));
        if (record.unstableEntries() > named.size()) getLogger().warning(text("backup.unstablemore", record.unstableEntries() - named.size()));
        if (sender != null) main(() -> tell(sender, summary, false));
    }

    private void reportBackupOutcome(CommandSender sender, BackupRecord created, PluginSettings jobSettings) {
        List<BackupRecord> remaining;
        try {
            remaining = List.copyOf(engine.listBackups());
            catalogue = remaining; rejected = Map.copyOf(engine.rejectedArchives()); catalogueLoaded = true;
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
        for (UUID id : autosave) {
            World world = Bukkit.getWorld(id);
            if (world != null) {
                try { world.setAutoSave(true); }
                catch (Exception | LinkageError error) { getLogger().log(Level.SEVERE,text("world.autosave"),error); }
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
        rejected = Map.copyOf(engine.rejectedArchives());
        freeSpace = Files.getFileStore(settings.backupDirectory()).getUsableSpace();
        if (!catalogueLoaded) {
            catalogueLoaded = true;
            for (Map.Entry<String, String> entry : rejected.entrySet()) getLogger().warning(text("list.rejectedentry", entry.getKey(), entry.getValue()));
        }
        main(this::anchorAutomatic);
    }
    /**
     * @param revalidate true to re-read every archive (startup, /backup verify, the GUI refresh button);
     *                   false to verify only archives that are new or changed since they were last verified
     */
    void refresh(CommandSender sender, boolean revalidate, Runnable after) {
        if (sender != null && !allowed(sender, "list")) return;
        if (busy()) {
            // Nothing is known before the store has been read once: do not present that as "no backups".
            if (!catalogueLoaded) { if (sender != null) tell(sender,text("list.loading"),false); return; }
            // Read-only navigation remains available while the I/O worker owns a mutation.
            if (sender != null) tell(sender,text("list.cached"),true);
            if (after != null) after.run();
            return;
        }
        if (!begin(sender, "operation.catalogue")) return;
        submit(() -> { try { updateCatalogue(revalidate); main(() -> { if (after != null) after.run(); }); }
            catch (Exception | LinkageError error) { reportFailure(sender, "job.list", error); }
            finally { main(this::finish); } });
    }
    Optional<BackupRecord> find(String id) { return catalogue.stream().filter(record -> record.id().equals(id)).findFirst(); }

    /** One explicit administrator action verifies and reserves; it never stops the server. */
    void requestRestore(CommandSender sender, String id) {
        if (!allowed(sender,"restore")) return;
        if (busy()) { tell(sender,text("job.busy",operation()),false); return; }
        if (restoreReserved()) { tell(sender,text("restore.pending"),false); return; }
        if (find(id).isEmpty()) { tell(sender,text("list.unknown"),false); return; }
        if (!helperAvailable()) { tell(sender,text("restore.helpermissing"),false); return; }
        if (!begin(sender,"operation.verify")) return;
        audit(sender, "audit.restore.request", id);
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
                    boolean committing = false;
                    try {
                        // Verification can be slow. Recheck permission and connection on the server
                        // thread immediately before committing this explicit action.
                        if (isCancelled() || sender instanceof Player p && !p.isOnline() || !allowed(sender,"restore")) {
                            restores.cancel(token); return;
                        }
                        operation = "operation.reserve";
                        submit(() -> commitVerifiedRestore(sender, record.id(), token));
                        committing = true;
                    } finally { if (!committing) finish(); }
                });
                handedOff = true;
            } catch (Exception | LinkageError error) {
                if (preparedToken != null) restores.cancel(preparedToken);
                if (!isCancelled()) reportFailure(sender,"job.restoreprepare",error);
            } finally { if (!handedOff) main(this::finish); }
        });
    }

    private void commitVerifiedRestore(CommandSender sender, String id, String token) {
        try {
            if (isCancelled()) { restores.cancel(token); return; }
            RestoreCoordinator.LaunchResult result = restores.confirm(token);
            // The reason (e.g. helper could not start) is otherwise only in the job directory, if anywhere.
            if (!result.helperReady()) getLogger().warning(text("job.diagnostic", result.message()));
            armedJob = result.jobId();
            refreshRestoreState();
            if (result.helperReady()) audit(sender, "audit.restore.reserved", id, result.jobId());
            main(() -> {
                // A reservation changes what the next server stop does. Other administrators must not learn that from the result.
                if (result.helperReady()) notifyAdministrators(text("restore.reservednotice", sender.getName(), id), sender);
                tell(sender,text(result.helperReady() ? "restore.reserved" : restoreReserved() ? "restore.notready" : "restore.notready.cleared", id),result.helperReady());
                if (result.helperReady()) {
                    tell(sender,text("restore.cancelhint"),true);
                    tell(sender,text("restore.shutdowncaveat"),false);
                }
                showRestorePaths(sender, result.jobFile().getParent());
                if (result.helperReady()) tell(sender,text("restore.restart"),true);
            });
        } catch (Exception | LinkageError error) {
            restores.cancel(token);
            restoreStatusUncertain = true;
            try { refreshRestoreState(); } catch (IOException ignored) { /* keep unsafe state visible */ }
            reportFailure(sender,"job.restorelaunch",error);
        } finally { main(this::finish); }
    }

    void cancelRestore(CommandSender sender) {
        if (!allowed(sender,"restore")) return;
        if (busy() && operation.equals("operation.verify")) {
            cancelled.set(true);
            tell(sender,text("restore.verificationcancelled"),true);
            return;
        }
        if (busy()) { tell(sender,text("job.busy",operation()),false); return; }
        if (!restoreReserved()) { tell(sender,text("restore.nocancel"),false); return; }
        if (!begin(sender,"operation.cancel")) return;
        audit(sender, "audit.restore.cancel");
        submit(() -> {
            try {
                RestoreCoordinator.CancelResult result = restores.cancelCommitted();
                refreshRestoreState();
                main(() -> {
                    tell(sender,text(result.cancelled() ? "restore.cancelled" : "restore.canceluncertain"),result.cancelled());
                    if (!result.cancelled() && restoreReserved()) showRestorePaths(sender, restoreJobDirectory());
                });
            } catch (Exception | LinkageError error) { reportFailure(sender,"job.restorecancel",error); }
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

    /** Explicit target action. The engine rechecks restore protection under lock. */
    void delete(CommandSender sender, String id) { delete(sender, id, false); }
    /**
     * @param fromMenu true when a menu button started this; only then is the list shown again afterwards.
     *                 An archive that failed verification can be deleted by its ID as well.
     */
    void delete(CommandSender sender, String id, boolean fromMenu) {
        if (!allowed(sender,"delete")) return;
        if (busy()) { tell(sender,text("job.busy",operation()),false); return; }
        if (find(id).isEmpty() && !rejected.containsKey(id)) { tell(sender,text("list.unknown"),false); return; }
        if (protectedBackup(id)) { tell(sender,text("delete.protected"),false); return; }
        if (!begin(sender,"operation.delete")) return;
        audit(sender, "audit.delete", id);
        Player viewer = fromMenu && sender instanceof Player p ? p : null;
        if (viewer != null) menus.expectReturn(viewer);
        submit(() -> {
            try {
                if (isCancelled()) return;
                engine.delete(id, restores.protectedBackupIds());
                updateCatalogue(false);
                main(() -> {
                    tell(sender,text("delete.done", id),true);
                    if (viewer != null) menus.returnToList(viewer);
                });
            } catch (Exception | LinkageError error) {
                if (error instanceof Exception exception)
                    DeleteFeedback.report(exception, reason -> main(() -> tell(sender,text(DeleteFeedback.messageKey(reason)),false)),
                            unexpected -> reportFailure(sender,"job.delete",unexpected));
                else reportFailure(sender,"job.delete",error);
            } finally { main(() -> { if (viewer != null) menus.forgetReturn(viewer); finish(); }); }
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
            if (key != null) {
                // A command changes one value. Whatever was edited in the file by hand takes effect
                // only through an explicit reload, never as a side effect of an unrelated command.
                if (!PluginSettings.from(yaml).equals(settings)) throw new IllegalArgumentException(text("settings.unapplied"));
                yaml.set(key,value);
            }
            PluginSettings candidate = PluginSettings.from(yaml);
            if (!candidate.serverRoot().equals(settings.serverRoot()) || !candidate.backupDirectory().equals(settings.backupDirectory())
                    || !candidate.stateDirectory().equals(settings.stateDirectory()) || !candidate.helperJar().equals(settings.helperJar()))
                throw new IllegalArgumentException(text("settings.restart"));
            boolean reserved = restoreReserved();
            if (reserved && !safeReservedSettings(settings, candidate))
                throw new IllegalArgumentException(text("settings.reserved"));
            // The engine remembers which archives it has verified. Keep it unless what it backs up or accepts changed.
            boolean sameEngine = reserved || candidate.options().equals(settings.options());
            BackupEngine candidateEngine = sameEngine ? engine : new BackupEngine(candidate.options());
            if (key != null) replaceConfiguration(file, yaml.saveToString());
            // An already committed helper retains the language stored in its immutable job.
            if (!reserved) restores.setLanguage(candidate.language().equals("en_US") ? "en" : "ja");
            settings = candidate; engine = candidateEngine; reschedule();
            if (key == null) { audit(sender, "audit.reload"); warnAboutEnvironment(candidate); }
            else audit(sender, "audit.setting", key, value);
            tell(sender,key == null ? text("settings.reloaded") : text("settings.updated", key),true);
            // A different selection or limit can accept or reject other archives than before.
            if (!sameEngine) refresh(null, false, null);
        } catch (Exception error) { tell(sender,text("settings.rejected", safeError(error)),false); }
    }
    /**
     * Replaces config.yml in one step. A link the administrator put there keeps pointing at the same
     * file, and the file keeps its permissions (it may hold the Discord webhook URL).
     */
    private static void replaceConfiguration(Path file, String content) throws IOException {
        Path target = file.toRealPath();
        Path temp = Files.createTempFile(target.getParent(), ".config-", ".tmp");
        try {
            Files.writeString(temp, content);
            try { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target)); }
            catch (UnsupportedOperationException notPosix) { /* The directory's inherited ACL applies. */ }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static boolean safeReservedSettings(PluginSettings current, PluginSettings candidate) {
        return candidate.equals(new PluginSettings(current.serverRoot(), current.backupDirectory(),
                current.stateDirectory(), current.helperJar(), current.includes(), current.excludes(),
                candidate.automatic(), candidate.intervalMinutes(), current.retentionCount(),
                current.retentionBytes(), current.compressionLevel(), current.maxEntries(), current.maxEntryBytes(),
                current.maxTotalBytes(), current.maxCompressionRatio(), current.confirmationSeconds(),
                current.discordEnabled(), current.webhook(), candidate.language()));
    }

    /**
     * The interval is wall-clock time. Scheduler ticks stretch when the server lags, so the task
     * only looks at the clock once a second instead of counting ticks to the next backup.
     */
    private void reschedule() {
        if (!settings.automatic()) {
            if (automaticTask != null) automaticTask.cancel();
            automaticTask = null; nextAutomatic = null; automaticIntervalMinutes = 0;
            return;
        }
        // Reloading unrelated settings must not push the next automatic backup further away.
        if (automaticTask == null || automaticIntervalMinutes != settings.intervalMinutes() || nextAutomatic == null)
            nextAutomatic = catalogueLoaded ? automaticDue(Instant.now(), newestBackup(), settings.intervalMinutes())
                    : Instant.now().plusSeconds(Math.multiplyExact(settings.intervalMinutes(), 60L));
        automaticIntervalMinutes = settings.intervalMinutes();
        if (automaticTask != null) return;
        automaticTask = getServer().getScheduler().runTaskTimer(this, () -> {
            Instant due = nextAutomatic;
            if (due == null || Instant.now().isBefore(due)) return;
            nextAutomatic = Instant.now().plusSeconds(Math.multiplyExact(settings.intervalMinutes(), 60L));
            if (busy() || restoreReserved()) { getLogger().info(text("automatic.skipped", operation())); return; }
            create(null,"automatic");
        },20L,20L);
    }
    /**
     * Once the store has been read for the first time: the interval counts from the newest backup,
     * not from the moment the plugin was enabled. A server restarted more often than the interval
     * would otherwise never make an automatic backup at all.
     */
    private void anchorAutomatic() {
        if (automaticAnchored || !catalogueLoaded) return;
        automaticAnchored = true;
        if (settings.automatic() && nextAutomatic != null)
            nextAutomatic = automaticDue(Instant.now(), newestBackup(), settings.intervalMinutes());
    }
    private Instant newestBackup() {
        return catalogue.stream().map(BackupRecord::createdAt).max(Comparator.naturalOrder()).orElse(null);
    }
    /**
     * @param newest when the newest verified backup was made, or null without one
     * @return when the next automatic backup is due: one interval after the newest backup, but not
     *         before the server has had {@link #AUTOMATIC_SETTLE} to finish starting
     */
    static Instant automaticDue(Instant now, Instant newest, long intervalMinutes) {
        Instant earliest = now.plus(AUTOMATIC_SETTLE);
        if (newest == null) return earliest;
        long seconds = Math.multiplyExact(intervalMinutes, 60L);
        // A backup dated in the future means the clock was changed; fall back to a full interval from now.
        if (newest.isAfter(now)) return now.plusSeconds(seconds);
        Instant due = newest.plusSeconds(seconds);
        return due.isBefore(earliest) ? earliest : due;
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
