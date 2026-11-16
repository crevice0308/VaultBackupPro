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
 */
public record BackupRecord(String id, Instant createdAt, String label, Path zipPath,
                           Path metadataPath, long compressedBytes, long totalBytes,
                           int entryCount, String sha256, int unstableEntries) { }
