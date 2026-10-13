// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.core;

import java.nio.file.Path;
import java.time.Instant;

/** A completed archive. Obtain through BackupEngine verification before trusting it. */
public record BackupRecord(String id, Instant createdAt, String label, Path zipPath,
                           Path metadataPath, long compressedBytes, long totalBytes,
                           int entryCount, String sha256) { }
