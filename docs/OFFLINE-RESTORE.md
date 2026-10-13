# Offline restore operations and safety boundary

## 日本語: 予約してから手動で停止する

1. ホスティング管理画面、監視ツール、コンテナーなどの自動再起動・自動停止を無効にしてください。復元中は別のサーバー JVM を起動しないでください。
2. 復元対象をプレビューし、GUI の確認ボタンまたは確認コマンドで予約します。予約時にサーバーを停止することはありません。予約済みのバックアップは削除・保持数整理から保護されます。
3. `WAITING_FOR_MANUAL_STOP` は停止待ちです。待機時間に上限はありません。プレイを続け、準備ができたら管理者が通常の `stop` コマンドや通常の終了操作でサーバーを停止してください。
4. 停止前に予約を取り消せます。「取消を確認しました」が表示されるまでサーバーを停止しないでください。要求を送っただけの `CANCEL_PENDING` は取消完了ではありません。確認済みの取消ジョブは、後から停止しても、手動ヘルパーで実行しても復元しません。
5. ヘルパーは、サーバーの停止処理への移行記録と、対象 JVM の実際の終了を確認した後でのみオフライン復元を開始します。`onDisable` に到達しただけではファイルを復元しません。強制終了などで移行記録がない場合は復元せず停止します。
6. 外部の状態ディレクトリー内の `restore-jobs/JOB-ID/status.properties` と `helper.log` を確認してください。`SUCCESS` と完了ログを確認し、ヘルパーが終了してから管理者がサーバーを起動します。自動再起動は行いません。

重要: 停止処理への移行記録は、「管理者が手動で停止したこと」や「全保存処理が正常終了したこと」の証明ではありません。障害時でも通常の停止処理に入った場合、予約済みの復元が実行されることがあります。自動停止・再起動を無効にし、予約後は次の停止で復元されるものとして運用してください。

バックアップは保存済みの正確なファイル構成に置き換えます。バックアップから除外されたファイルや後から追加したファイルは、元データのロールバック用フォルダーに残ります。古いプラグインを含むバックアップではプラグイン自身も古い版に戻るため、必要に応じて起動前に最新の JAR を配置し直してください。

手動実行・復旧の前には必ずサーバーを完全停止し、以下の英語セクションの手順を確認してください。Linux のラッパーは日英併記です。Windows の `.cmd` はコンソールの文字コード依存を避けるため ASCII で案内し、この文書を参照します。`STOPPED` と入力するまで実行しません。ヘルパーの状態と完了・失敗概要は `language: ja_JP` の予約では日本語で表示されます。詳細な OS/Java エラーは英語の場合があります。

## Before confirming

- Disable hosting-panel automatic restart, watchdog restart, scheduled stop/restart tasks, systemd `Restart=always`, container restart policies, and third-party launch scripts for the entire operation. Do not launch any server JVM while the helper is running.
- Set both `backup-directory` and `state-directory` to real directories outside the configured server root. They cannot be ancestors of the root. Symlink path components are refused. The plugin enforces external storage for backups and restoration.
- Run Java 25 and the packaged helper as the same OS user that owns/runs the server. The parent directory must allow creating/renaming sibling directories. Root and staging must be on the same filesystem; atomic directory renames are required. Mount-point roots or filesystems without supported atomic rename are refused.
- Have enough space for the extracted archive plus the existing root. Original data is never intentionally deleted. The original root remains in a sibling rollback directory after success; the backup archive is untouched.
- Review the archive scope. Restore installs the exact backed-up tree, replacing the root. Files excluded from the backup or created afterward are absent from the installed tree and remain in the preserved original rollback. A filtered backup is not an entire-server snapshot.
- File contents, paths, and empty directories are restored. Original ownership, executable bits, ACLs, extended attributes, and exact timestamps are not reproduced. On POSIX the new root is owner-only (0700). Review permissions before intentional sharing or launching executable scripts. On Windows the new directory uses inherited ACLs.

## Reservation and manual-stop path

1. Preview fully verifies archive SHA-256, manifest, and every payload hash. Its confirmation token is short-lived and bound to that archive and root. This preview expiry is separate from the confirmed reservation.
2. Confirmation rechecks the archive digest, writes a durable external protocol-v2 job, and starts the standalone helper while the server keeps running. The startup handshake is bounded to 15 seconds. Confirmation does not request shutdown or restart. Repeated confirmation of the same token refers to the same job and cannot launch an additional helper or revive a cancelled job.
3. `WAITING_FOR_MANUAL_STOP` means the helper is waiting without a time limit. Keep playing for as long as needed, then use the server's usual manual stop operation. There is no ten-minute reservation expiry. The archive stays protected from deletion and retention while the reservation is unresolved.
4. During the platform's server-shutdown path, the plugin writes only a job-bound authenticated shutdown-path marker. Plugin disable/reload alone does not authorize this marker. The helper separately requires the recorded parent PID and process start instant to cease running. PID reuse, missing identity, a held root guard, or an active world session lock refuses restoration. A lifetime file lock stays held until actual JVM exit; reaching `onDisable` never authorizes world writes.
5. If the JVM exits abruptly without the shutdown-path marker, the waiting helper fails before staging. The marker proves that the server shutdown path was entered, not that shutdown was manually requested or every save completed successfully. A graceful fault shutdown may enter the same path and trigger the already-confirmed reservation. Disable other stop/restart automation and treat the next eligible shutdown as the restore trigger.
6. After all exit checks pass, the helper extracts to an owner-only sibling directory, verifies all staged data, and forces file/directory writes before swapping. It renames the original root to rollback, then stage to root.
7. The helper verifies every installed file and the exact tree before writing an authenticated success receipt. Status progresses through `STAGING`, `STAGED`, `MOVING_ORIGINAL`, `INSTALLING`, `VERIFYING_INSTALLED`, and `SUCCESS`. The helper logs completion and never restarts the server.
8. Check the job's `status.properties` and `helper.log`, then wait for the helper process to exit. Manually start the server only after successful completion or reviewed recovery. At startup the plugin verifies the success receipt before accepting a successful journal and consumes the notification once. The receipt describes the verified offline installation time; normal startup may already have changed live files.

A failed or uncertain startup handshake is disarmed using a durable cancellation tombstone. If cancellation cannot be confirmed, the coordinator attempts to terminate only its own newly launched helper. It reports uncertainty explicitly and keeps the journal and archive protected. Never interpret a launch failure or a cancellation request as restore completion.

## Cancelling a confirmed reservation

Cancel through the GUI or restore-cancel command before stopping the server. A preview cancellation and a confirmed-reservation cancellation are different operations; the latter requires acknowledgment.

- `CANCEL_PENDING` means a durable request exists but acknowledgment has not been verified. Keep the server running. The archive remains protected and the active marker is retained.
- A successful cancellation requires an authenticated, job-bound permanent tombstone plus the helper's acknowledgment or confirmed death of the exact helper process. A request file alone is insufficient.
- Cancellation and application share an interprocess job-state lock. The helper never holds that lock while waiting for the server to stop. Once application owns the lock, cancellation cannot falsely claim success.
- The active marker and archive pin are cleared only after safe acknowledgment. Repeated cancellation is safe. A new reservation can then be made in the same server JVM.
- The tombstone is retained permanently. A cancelled job cannot run automatically, with `--manual`, or with `--recover`, and cannot affect a later active job.
- Malformed, symlinked, mismatched, or unauthenticated cancellation/acknowledgment files fail closed. Do not delete or edit them to force a retry.
- `HELPER_EXITED` or `HELPER_UNCERTAIN` in the plugin means a waiting helper is unavailable or cannot be reliably identified. The reservation remains unresolved and protected. Cancel or investigate it before stopping; the plugin does not claim it is ready to restore.

## Explicit stopped-server fallback

Use this only for an active, uncancelled job with the server fully stopped and restart automation disabled. It is useful when a shutdown-path marker is missing or a stopped-server recovery was reviewed. A safely cancelled launch or reservation cannot be resumed; create a new reservation instead.

Copy the packaged helper beside its wrapper, or set `VAULTBACKUP_HELPER_JAR` to its absolute path. `JAVA_HOME` is supported, including paths with spaces.

Linux:

```sh
./restore.sh /absolute/state/restore-jobs/JOB-ID/job.properties
```

Windows:

```bat
restore.cmd C:\absolute\state\restore-jobs\JOB-ID\job.properties
```

The wrapper requires typing `STOPPED`. Direct invocation requires both explicit flags:

```sh
java -jar vaultbackup-restore-1.0.0.jar --job /absolute/job.properties --manual --i-confirm-server-stopped
```

Manual confirmation never bypasses a living recorded JVM, uncertain/reused PID identity, the root lock, active world session locks, or a cancellation tombstone. It cannot silently resume an interrupted swap. Older protocol-v1 jobs require explicit stopped-server review/manual execution; the new automatic waiting path refuses them. Existing correctly authenticated success receipts remain readable.

## Failure and crash recovery

Review `state/restore-jobs/JOB-ID/status.properties` and `helper.log`. Do not delete state, stage, rollback, or failed-tree siblings to force a retry.

- Before the first rename, failure leaves the original root untouched and retains any stage.
- After the first rename, a normal failure attempts to put the original root back. Any installed/failed tree is moved to a separate sibling instead of deleted. No success receipt is emitted.
- A crash or ambiguous rename journal never automatically resumes installation. Keep the server stopped and inspect the retained paths.
- `--recover --i-confirm-server-stopped`, or the wrapper's optional `--recover` argument, restores a present preserved original rollback for an active, uncancelled job. An existing current root is first retained in the failed-tree sibling. If the original location is not provable or a preservation target already exists, recovery refuses to guess or overwrite.
- Recovery does not automatically reverse a completed successful restore. The preserved rollback is available for a separate reviewed recovery operation.
- A confirmed-dead helper with a safely terminal pre-swap/rolled-back/recovered journal can be durably disarmed before startup cleanup. Unresolved jobs, unauthenticated success receipts, or ambiguous cancellation block safe startup rather than treating a status word as proof.

Backups, original data, stage, and failure evidence are not automatically removed by the restore helper. Cleanup requires a separate administrator decision after verification.

## Operational limits

The helper enforces at most 1,000,000 entries, 256 GiB per file, 1 TiB uncompressed total, compression ratio 10,000, 128 MiB manifest, 8,192-character manifest records, and 4,096-character relative paths. It rejects path traversal, duplicates, case/Unicode aliases including implicit ancestors, reserved Windows names, unsafe cross-platform names, unmanifested payloads, symlink roots, and file/directory collisions.

The guard coordinates this plugin and its helper. It cannot prevent an unrelated operator, host watchdog, or uncooperative JVM from accessing the root before its plugin loads. Disabling stop/restart automation and not starting another process during restore are required operating conditions. Same-OS-account malicious tampering is outside the receipt authentication boundary.

Restoring an archive also restores its plugin JARs and configuration. An older archive can therefore reinstall an earlier VaultBackupPro implementation, even if both show internal version 1.0.0. Keep the current release outside the server root and reapply it before startup when necessary to retain the manual-stop reservation behavior.
