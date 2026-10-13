# VaultBackup Pro 1.1.0 — Publication notes

Public files are VaultBackupPro-1.1.0.jar, VaultBackupPro-1.1.0-plugin.zip and VaultBackupPro-1.1.0-source.zip. Install one plugin JAR only. The source archive includes corresponding source, Gradle build, test harnesses and GPLv3 notices. Exact hashes are in TEST-RESULTS.md and release/SHA256SUMS.txt.

Version 1.1.0 includes independently obfuscated plugin/helper builds, public GPLv3 source and ProGuard settings, hardened build dependencies, and a rollback fix for status-journal failures. It also changes manual deletion: the final verified good backup can be deleted after an explicit zero-backups warning and confirmation. Automatic retention continues to protect at least one good recovery point. Restore-reserved archives remain protected. Full restoration still waits for actual server JVM exit and requires manual restart.

The existing 1.0.0 tag, release and assets remain unchanged. Restoring an older full backup may restore its older plugin/configuration; check the version before starting.

The public packages omit user-provided logs, addresses/identifiers, raw local execution logs, temporary fixture paths, generated worlds, server binaries, backup archives and journals. The published evidence is a curated test summary and sanitized artifact metadata. Windows execution and real-client GUI interaction are not automatically established by Linux console tests; consult the final report. Purpur 26.3 remains experimental upstream.

This is free GPL-3.0-only software without warranty. GPLv3 permits commercial use and paid redistribution subject to its terms. Preserve the corresponding-source and notice obligations when redistributing.
