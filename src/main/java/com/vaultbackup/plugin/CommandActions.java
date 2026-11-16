// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupRecord;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The /backup operations. Both command front-ends (plugin.yml commands on Spigot, Brigadier on
 * Paper) only parse and then call these methods, so validation and permission checks cannot differ.
 */
final class CommandActions {
    static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    static final long MAX_INTERVAL_MINUTES = 525600;
    static final long MAX_RETENTION_COUNT = 1_000_000;
    static final List<Long> INTERVAL_CHOICES = List.of(5L, 15L, 30L, 60L, 180L, 360L, 720L, 1440L);
    /** Every permission below vaultbackup.admin that lets a sender use some part of /backup. */
    static final List<String> PERMISSIONS = List.of("gui", "create", "list", "restore", "delete", "status", "configure", "reload");
    private static final int PAGE_SIZE = 10;

    private final VaultBackupPlugin plugin;

    CommandActions(VaultBackupPlugin plugin) { this.plugin = plugin; }

    VaultBackupPlugin plugin() { return plugin; }

    /**
     * /backup is for people and for the console. A command block or a command minecart counts as an
     * operator for permission checks, so it is refused by what it is, whatever its permissions say.
     */
    static boolean acceptable(CommandSender sender) {
        return sender instanceof Player || sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    /** Silent check, for deciding what to show. Actions report a refusal themselves. */
    static boolean may(CommandSender sender, String action) { return acceptable(sender) && sender.hasPermission("vaultbackup." + action); }

    /** True when the sender may use at least one part of the command and may therefore see it. */
    static boolean mayUseAny(CommandSender sender) {
        return PERMISSIONS.stream().anyMatch(action -> may(sender, action));
    }

    private void fail(CommandSender sender, String key, Object... args) { plugin.tell(sender, plugin.text(key, args), false); }

    /** /backup without arguments: the GUI for players, the help for everything else. */
    void open(CommandSender sender) {
        if (sender instanceof Player player) plugin.menus().openMain(player);
        else help(sender);
    }

    void help(CommandSender sender) {
        if (!mayUseAny(sender)) { fail(sender, acceptable(sender) ? "permission.none" : "permission.sender"); return; }
        plugin.tell(sender, plugin.text("command.help.title", plugin.version()), true);
        line(sender, "gui", "gui");
        line(sender, "create", "create");
        line(sender, "list", "list");
        line(sender, "verify", "list");
        line(sender, "restore", "restore");
        line(sender, "cancel", "restore");
        line(sender, "delete", "delete");
        line(sender, "status", "status");
        line(sender, "reload", "reload");
        line(sender, "auto", "configure");
        line(sender, "interval", "configure");
        line(sender, "retention", "configure");
    }

    private void line(CommandSender sender, String helpKey, String permission) {
        if (may(sender, permission)) plugin.tell(sender, plugin.text("command.help." + helpKey), true);
    }

    void usage(CommandSender sender, String usage) { fail(sender, "command.usage", usage); }

    void notANumber(CommandSender sender) { fail(sender, "command.number"); }

    /** @param label null for the default label */
    void create(CommandSender sender, String label) {
        String chosen = label == null ? "manual" : label;
        if (!LABEL.matcher(chosen).matches()) { fail(sender, "command.label"); return; }
        plugin.create(sender, chosen);
    }

    void list(CommandSender sender, int page) {
        if (page < 1) { usage(sender, "/backup list [page >= 1]"); return; }
        plugin.refresh(sender, false, () -> {
            List<BackupRecord> records = plugin.backups();
            int pages = Math.max(1, (records.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page > pages) { fail(sender, "list.page", pages); return; }
            plugin.tell(sender, plugin.text("list.title", page, pages), true);
            if (records.isEmpty()) plugin.tell(sender, plugin.text("list.empty"), true);
            records.stream().skip((page - 1L) * PAGE_SIZE).limit(PAGE_SIZE).forEach(record -> plugin.tell(sender,
                    record.id() + " | " + record.createdAt() + " | " + VaultBackupPlugin.humanBytes(record.compressedBytes())
                            + " | " + record.label() + (record.unstableEntries() > 0 ? " | " + plugin.text("list.unstable", record.unstableEntries()) : "")
                            + (record.vanishedEntries() > 0 ? " | " + plugin.text("list.vanished", record.vanishedEntries()) : ""), true));
            // Archives that are in the store but cannot be trusted: never listed as backups, never hidden either.
            Map<String, String> rejected = plugin.rejectedArchives();
            if (rejected.isEmpty()) return;
            fail(sender, "list.rejected", rejected.size());
            rejected.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(PAGE_SIZE)
                    .forEach(entry -> fail(sender, "list.rejectedentry", entry.getKey(), entry.getValue().replaceAll("\\p{Cntrl}", " ")));
        });
    }

    /** Re-reads every archive completely instead of trusting earlier verification. */
    void verify(CommandSender sender) {
        plugin.refresh(sender, true, () -> plugin.tell(sender, plugin.text("list.verified", plugin.backups().size()), true));
    }

    void restore(CommandSender sender, String id) { plugin.requestRestore(sender, id); }

    void cancelRestore(CommandSender sender) { plugin.cancelRestore(sender); }

    void delete(CommandSender sender, String id) { plugin.delete(sender, id); }

    void status(CommandSender sender) { plugin.status(sender); }

    void reload(CommandSender sender) { plugin.reloadSettings(sender); }

    void auto(CommandSender sender, boolean enabled) { plugin.setting(sender, "automatic.enabled", enabled); }

    void interval(CommandSender sender, long minutes) {
        if (minutes < 1 || minutes > MAX_INTERVAL_MINUTES) { fail(sender, "command.interval"); return; }
        plugin.setting(sender, "automatic.interval-minutes", minutes);
    }

    void retentionCount(CommandSender sender, long value) {
        if (value < 0) { fail(sender, "command.retention.negative"); return; }
        if (value > MAX_RETENTION_COUNT) { fail(sender, "command.retention.maximum"); return; }
        plugin.setting(sender, "retention.max-count", value);
    }

    void retentionBytes(CommandSender sender, long value) {
        if (value < 0) { fail(sender, "command.retention.negative"); return; }
        plugin.setting(sender, "retention.max-bytes", value);
    }

    /** Backup IDs for completion; never disclosed to a sender who may not act on them. */
    List<String> backupIds(CommandSender sender, String action) {
        if (!may(sender, action)) return List.of();
        List<String> ids = new ArrayList<>(plugin.backups().stream().map(BackupRecord::id).limit(100).toList());
        // An archive that failed verification can be deleted by its ID, and by nothing else.
        if (action.equals("delete")) plugin.rejectedArchives().keySet().stream().sorted().limit(100 - ids.size()).forEach(ids::add);
        return ids;
    }
}
