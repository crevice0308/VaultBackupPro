// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable, bounded backup policy. Patterns use portable '/'-separated glob syntax. */
public record BackupOptions(Path serverRoot, Path backupDirectory, Path stateDirectory,
                            List<String> includeGlobs, List<String> excludeGlobs,
                            int maxEntries, long maxEntryBytes, long maxTotalBytes,
                            double maxCompressionRatio, int compressionLevel) {
    public BackupOptions {
        Objects.requireNonNull(serverRoot, "serverRoot");
        Objects.requireNonNull(backupDirectory, "backupDirectory");
        Objects.requireNonNull(stateDirectory, "stateDirectory");
        includeGlobs = List.copyOf(Objects.requireNonNull(includeGlobs, "includeGlobs"));
        excludeGlobs = List.copyOf(Objects.requireNonNull(excludeGlobs, "excludeGlobs"));
        if (maxEntries < 1 || maxEntries > 1_000_000) throw new IllegalArgumentException("maxEntries must be 1..1000000");
        if (maxEntryBytes < 1 || maxTotalBytes < maxEntryBytes) throw new IllegalArgumentException("Invalid byte limits");
        if (!Double.isFinite(maxCompressionRatio) || maxCompressionRatio < 1) throw new IllegalArgumentException("Invalid compression ratio");
        if (compressionLevel < 0 || compressionLevel > 9) throw new IllegalArgumentException("compressionLevel must be 0..9");
    }

    public static BackupOptions defaults(Path root, Path backups, Path state) {
        return new BackupOptions(root, backups, state, List.of(), List.of(), 100_000,
                256L * 1024 * 1024 * 1024, 1024L * 1024 * 1024 * 1024, 10_000d, 6);
    }
}
