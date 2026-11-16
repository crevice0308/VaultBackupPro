// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.core;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A completed archive. Obtain through BackupEngine verification before trusting it.
 *
 * @param unstableEntries files that kept changing while they were copied from the running server.
 *                        They are archived as last read and may be internally inconsistent
 *                        (typically databases or logs another plugin was writing).
 * @param vanishedEntries files that existed when the server tree was listed and were gone when
 *                        they were to be copied. They are not in the archive. A few are normal
 *                        for a running server; many mean that part of the tree was being
 *                        deleted, moved or unmounted while the backup ran.
 */
public record BackupRecord(String id, Instant createdAt, String label, Path zipPath,
                           Path metadataPath, long compressedBytes, long totalBytes,
                           int entryCount, String sha256, int unstableEntries, int vanishedEntries) {
    /** For records of archives made before the count of vanished files was kept. */
    public BackupRecord(String id, Instant createdAt, String label, Path zipPath, Path metadataPath,
                        long compressedBytes, long totalBytes, int entryCount, String sha256, int unstableEntries) {
        this(id, createdAt, label, zipPath, metadataPath, compressedBytes, totalBytes, entryCount, sha256, unstableEntries, 0);
    }
}
