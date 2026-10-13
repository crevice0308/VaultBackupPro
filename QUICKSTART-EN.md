# VaultBackup Pro 1.2.0 — Quick start

Install one VaultBackupPro-1.2.0.jar in plugins while the server is stopped. Do not extract the JAR or keep another VaultBackup version beside it. Use Java25 and a tested Paper, Purpur or Spigot26.2/26.3 build. Read TEST-RESULTS.md for exact versions and untested platforms.

Japanese is the default. Set language: en_US in plugins/VaultBackupPro/config.yml and use /backup reload for English. Existing configurations are preserved. A helper for an already-reserved restore keeps the language recorded when that job was created.

Create: /backup create optional-label. List: /backup list. Status: /backup status. The /backup GUI provides the same administrator actions.

Delete: /backup delete <id> immediately deletes the target without another confirmation. This is irreversible, including the final backup. Automatic retention may also remove the final archive to satisfy a positive capacity limit. A retention count/byte value of0 disables that limit; it does not request deletion of everything. Keep independent recovery copies.

Restore: /backup restore <id> verifies the archive and directly reserves it for your next normal server stop. There is no second confirm command. Keep playing until you are ready, then perform a normal stop. Disable host auto-restart. Wait for the helper's SUCCESS, its process exit, and the recorded result before manually starting the server. /backup restore cancel cancels a reservation before application; wait for the acknowledged cancellation message. Do not interpret CANCEL_PENDING as completed cancellation.

The helper log and status file are shown by /backup status. By default they are in ../VaultBackup-state/restore-jobs/<job-id>/helper.log and status.properties relative to the server root. Do not share job.properties; it contains authentication material. A live backup cannot make unrelated plugins or external databases transactionally consistent.

An old waiting reservation may be reconciled while startup continues with a retained root guard. An actual restore conflict or evidence of an incomplete replacement cannot safely permit ordinary world startup. This edition removes redundant interaction steps, not authorization, archive integrity checks, or the requirement to restore offline.

Verification scope: this revision targets Paper/Purpur/Spigot 26.2 and 26.3 on Java 25. Fresh Spigot runtime preparation was blocked; API compilation is not an executed-server result. Windows and real-client GUI interaction remain untested. Purpur 26.3 is experimental. See TEST-RESULTS.md for exact final artifact and runtime evidence.

Prerelease test build: concurrent startup during restoration is unsupported. Paper can write world files after the plugin requests shutdown on a guard conflict. Disable host auto-restart and do not start until the restore helper has completed and exited. This plugin cannot guarantee prevention of corruption caused by a simultaneously started server.
