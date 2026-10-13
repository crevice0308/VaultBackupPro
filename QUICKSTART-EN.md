# VaultBackup Pro 1.0.0 — Quick start

Free software under GPL-3.0-only. The default JAR is not obfuscated. The accompanying source ZIP contains the corresponding source, Gradle build, tests and documentation. The plugin filename is VaultBackupPro-1.0.0.jar. Identify this exact release by its SHA-256 in TEST-RESULTS.md; older builds may also report version 1.0.0. See [publication notes](PUBLICATION-NOTES.md) for source and artifact names.

## Install or update

1. Stop your server normally and wait for its Java process to exit. Keep your existing JAR and configuration separately.
2. Copy the packaged VaultBackupPro-1.0.0.jar into plugins. Keep only one VaultBackup Pro JAR there. Do not unzip the JAR itself or create a directory named with the .jar extension.
3. Run Paper, Purpur or Spigot 26.2/26.3 with Java 25. Exact tested builds are listed in the test reports; Purpur 26.3 is experimental upstream. Folia and unrelated versions are unsupported.
4. Backups and restore control state must be outside the server root on a suitable local filesystem. The host must allow the independent Java helper. Configuration mistakes fail closed.

Japanese is the default, including when an older config has no language key. To use English, set `language: en_US` in plugins/VaultBackupPro/config.yml, then run `/backup reload` while idle with no pending/confirmed restore. Set `language: ja_JP` to switch back. Existing configuration is not overwritten. Command names and permissions do not change with language.

## Back up and reserve a restore

- `/backup` opens the administrator inventory GUI; console receives help.
- `/backup create` creates a full-root ZIP after the controlled save step.
- `/backup list` lists verified backups; `/backup status` shows current work and restore state.
- Restore and deletion both require explicit confirmation. Keep independent backups and rehearse on disposable data first.

Restore sequence:

1. Disable host/watchdog automatic restarts and coordinate with other administrators.
2. Run `/backup restore <id>`, read the warning, then `/backup restore <id> confirm` as the same administrator before the preview expires.
3. Confirmation reserves the restore and leaves the server running. Status shows waiting for manual stop. The reservation has no automatic timeout; its archive stays pinned and new backups/settings changes are blocked.
4. To cancel before stopping, run `/backup restore cancel` or use the GUI. Wait for confirmed cancellation. CANCEL_PENDING is not completed cancellation: keep the server running and inspect/retry. Once applying has started, this command cannot undo the restore.
5. When ready, perform a normal `stop`. The helper waits for the recorded JVM to exit and verifies its shutdown-path marker, process identity and locks before changing the server root.
6. With the server stopped, inspect the external state directory's restore-jobs/<job-id>/status.properties and helper.log. No separate progress window opens. Require SUCCESS and helper-process exit before manually starting the server. RECOVERY_REQUIRED is not permission to restart.
7. On manual startup, authenticated offline verification produces a success message. The helper never restarts the server. A reservation or stopped JVM alone is not a successful restore.

A shutdown-path marker is not proof that the administrator typed stop or that every save completed. An orderly fault shutdown can also trigger an already-confirmed reservation. An abrupt exit without the marker refuses automatic application. Never erase job state to force another attempt.

## Recovery and limits

The old root is retained as a rollback sibling, including files excluded from the restored backup. Keep it until you have checked the result. See docs/OFFLINE-RESTORE.md for active, uncancelled manual-helper jobs and explicit recovery. Permanently cancelled jobs cannot be resumed by a wrapper or command-line flag.

A full backup includes plugin JARs and configuration. Restoring an old archive can reinstall the older automatic-stop behavior even though both versions say 1.0.0. Keep this revision outside the server root and check which JAR/config will be started.

Live ZIP backups cannot make external databases or arbitrary plugin writers transactionally consistent. ZIP restores preserve contents and directory layout, not ACLs, ownership or executable bits. Backups can contain secrets: protect them and do not publish them. New Linux directories use owner-only access; Windows inherited ACLs require review.

See TEST-RESULTS.md and KNOWN-LIMITATIONS.md. This revision's real-client GUI/player testing, Windows execution, real Discord delivery and hardware power-loss scenarios are not established by Linux server tests. There is no DRM or activation service. GPLv3 permits commercial use and paid redistribution subject to its terms; our distribution is free of charge.

If a backup reports `Source changed during backup`, that attempt was refused rather than published as a complete snapshot. The runtime tests encountered a changing Spark JFR temporary recording under plugins/spark/tmp. Stop the profiler normally, or explicitly exclude only disposable data you do not need restored (for example `plugins/spark/tmp/**`). Exclusions affect the restored tree; never broadly exclude worlds or databases merely to hide a warning.
