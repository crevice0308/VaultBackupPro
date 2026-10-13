// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupOptions;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Immutable, fully validated configuration. Reload replaces this object only after validation. */
public record PluginSettings(Path serverRoot, Path backupDirectory, Path stateDirectory,
                             Path helperJar, List<String> includes, List<String> excludes,
                             boolean automatic, long intervalMinutes, int retentionCount,
                             long retentionBytes, int compressionLevel, int maxEntries,
                             long maxEntryBytes, long maxTotalBytes, double maxCompressionRatio,
                             long confirmationSeconds, boolean discordEnabled, URI webhook, String language) {
    public static PluginSettings read(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return from(yaml);
    }

    public static PluginSettings from(YamlConfiguration y) throws IOException {
        String language = requiredString(y, "language", Messages.DEFAULT_LANGUAGE);
        if (!Messages.supported(language)) throw invalid(y, "settings.language");
        Path root = Path.of(requiredString(y, "storage.server-root", ".")).toAbsolutePath().normalize();
        // Keep the configured lexical root intact, even when missing, a file or a symlink.
        // The startup coordinator acquires its lifetime guard before classifying those as
        // HARD_CONFLICT. Never create or resolve away an unsafe root during config parsing.
        Path backup = resolve(y, root, requiredString(y, "storage.backup-directory", "../VaultBackup-backups"));
        Path state = resolve(y, root, requiredString(y, "storage.state-directory", "../VaultBackup-state"));
        Path helper = resolve(y, root, requiredString(y, "restore.helper-jar", "../VaultBackup-state/helper/vaultbackup-restore.jar"));
        if (backup.startsWith(root) || state.startsWith(root))
            throw invalid(y, "settings.external");
        if (backup.equals(state) || backup.startsWith(state) || state.startsWith(backup))
            throw invalid(y, "settings.separate");
        if (!helper.startsWith(state)) throw invalid(y, "settings.helper");
        List<String> includes = stringList(y, "backup.include");
        List<String> excludes = stringList(y, "backup.exclude");
        if (includes.isEmpty()) throw invalid(y, "settings.include");
        int compression = integer(y, "backup.compression-level", 6, 0, 9);
        long interval = number(y, "automatic.interval-minutes", 60, 1, 525600);
        int retention = integer(y, "retention.max-count", 20, 0, 1000000);
        long bytes = number(y, "retention.max-bytes", 53687091200L, 0, Long.MAX_VALUE);
        int entries = integer(y, "limits.max-entries", 100000, 1, 1000000);
        long entryBytes = number(y, "limits.max-entry-bytes", 34359738368L, 1, 274877906944L);
        long totalBytes = number(y, "limits.max-total-bytes", 1099511627776L, 1, 1099511627776L);
        if (totalBytes < entryBytes) throw invalid(y, "settings.total");
        Object ratioValue = y.get("limits.max-compression-ratio", 2000.0);
        if (!(ratioValue instanceof Number)) throw invalid(y, "settings.numeric", "limits.max-compression-ratio");
        double ratio = ((Number) ratioValue).doubleValue();
        if (!Double.isFinite(ratio) || ratio < 1 || ratio > 10000)
            throw invalid(y, "settings.range", "limits.max-compression-ratio", 1, 10000);
        // Legacy confirmation-seconds is deliberately ignored. Direct actions consume this
        // short-lived internal token immediately; no administrator countdown is displayed.
        long ttl = 60;
        boolean webhookEnabled = bool(y, "notifications.discord.enabled", false);
        String url = y.getString("notifications.discord.webhook-url", "").trim();
        URI webhook = null;
        if (!url.isEmpty()) {
            try { webhook = URI.create(url); }
            catch (IllegalArgumentException error) { throw invalid(y, "settings.webhook"); }
            if (!"https".equalsIgnoreCase(webhook.getScheme()) || !"discord.com".equalsIgnoreCase(webhook.getHost())
                    || (webhook.getPort() != -1 && webhook.getPort() != 443) || webhook.getUserInfo() != null
                    || webhook.getRawQuery() != null || webhook.getRawFragment() != null
                    || !webhook.getPath().matches("/api/webhooks/[0-9]+/[A-Za-z0-9._-]+"))
                throw invalid(y, "settings.webhook");
        }
        if (webhookEnabled && webhook == null) throw invalid(y, "settings.webhookrequired");
        return new PluginSettings(root, backup, state, helper, includes, excludes, bool(y,"automatic.enabled",true),
                interval, retention, bytes, compression, entries, entryBytes, totalBytes, ratio, ttl, webhookEnabled, webhook, language);
    }

    public BackupOptions options() {
        return new BackupOptions(serverRoot, backupDirectory, stateDirectory, includes, excludes,
                maxEntries, maxEntryBytes, maxTotalBytes, maxCompressionRatio, compressionLevel);
    }

    private static IllegalArgumentException invalid(YamlConfiguration y, String key, Object... arguments) {
        return new IllegalArgumentException(Messages.text(y.getString("language", Messages.DEFAULT_LANGUAGE), key, arguments));
    }

    private static Path resolve(YamlConfiguration y, Path root, String value) throws IOException {
        Path p = root.resolve(value).toAbsolutePath().normalize();
        // Resolve every existing ancestor, preventing a not-yet-created child under a symlink from bypassing containment.
        Path existing = p;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException(Messages.text(y.getString("language", Messages.DEFAULT_LANGUAGE), "settings.parent"));
        return existing.toRealPath().resolve(existing.relativize(p)).normalize();
    }

    private static List<String> stringList(YamlConfiguration y, String key) {
        Object value = y.get(key);
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String text) || text.isBlank()))
            throw invalid(y, "settings.list", key);
        return list.stream().map(String.class::cast).toList();
    }

    private static String requiredString(YamlConfiguration y, String key, String fallback) {
        Object value = y.get(key, fallback);
        if (!(value instanceof String string) || string.isBlank()) throw invalid(y, "settings.string", key);
        return string;
    }

    private static long number(YamlConfiguration y, String key, long fallback, long min, long max) {
        Object value = y.get(key, fallback);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
            throw invalid(y, "settings.integer", key);
        long result = ((Number) value).longValue();
        if (result < min || result > max) throw invalid(y, "settings.range", key, min, max);
        return result;
    }
    private static int integer(YamlConfiguration y, String key, int fallback, int min, int max) {
        return (int) number(y, key, fallback, min, max);
    }
    private static boolean bool(YamlConfiguration y, String key, boolean fallback) {
        Object value = y.get(key, fallback);
        if (!(value instanceof Boolean result)) throw invalid(y, "settings.boolean", key);
        return result;
    }
}
