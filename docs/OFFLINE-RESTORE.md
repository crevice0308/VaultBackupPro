# Offline restore operations and safety boundary

> 1.3.0 は実サーバーで試験していません。この文書の手順は、ソースと、サーバーを起動しない隔離試験にもとづいています。docs/RUNTIME-TEST-RESULTS.md と docs/test-evidence にある実サーバーの結果は過去の版（難読化ビルド）のもので、1.3.0 を対象としていません。本番で使う前に、試験環境で復元を確認してください。
>
> No real-server test was run for 1.3.0. The procedure below is based on the source and on isolated tests that start no server. The real-server results in docs/RUNTIME-TEST-RESULTS.md and docs/test-evidence belong to earlier, obfuscated builds and do not cover 1.3.0. Rehearse a restore on a test server before relying on it.

## 日本語: 予約してから手動で停止する

1. ホスティング管理画面、監視ツール、コンテナーなどの自動再起動・自動停止を無効にしてください。復元中は別のサーバー JVM を起動しないでください。
2. 一覧で対象を選び、詳細の復元操作ボタンまたは `/backup restore <id>` で直接予約します。追加確認はありません。予約時にサーバーを停止することはありません。予約済みのバックアップは削除・保持数整理から保護されます。予約の時点で、サーバールートがマウントポイント（独立したボリューム）である、親フォルダーに書き込めない、サーバールートの隣に展開用の空き容量がない、のいずれかを検出した場合は、理由を表示して予約を拒否します。
3. `WAITING_FOR_MANUAL_STOP` は停止待ちです。待機時間に上限はありません。プレイを続け、準備ができたら管理者が通常の `stop` コマンドや通常の終了操作でサーバーを停止してください。
4. 停止前に予約を取り消せます。「取消を確認しました」が表示されるまでサーバーを停止しないでください。要求を送っただけの `CANCEL_PENDING` は取消完了ではありません。確認済みの取消ジョブは、後から停止しても、手動ヘルパーで実行しても復元しません。`/reload` やプラグインの再読み込みを行った場合も、再読み込み後に予約が取り消されます（サーバーは停止しません）。
5. ヘルパーは、サーバーの停止処理への移行記録と、対象 JVM の実際の終了を確認した後でのみオフライン復元を開始します。`onDisable` に到達しただけではファイルを復元しません。強制終了などで移行記録がない場合は復元せず停止します。Paper・Purpur では、サーバーが停止中であることを API で確認して記録します。Spigot にはその API がないため、プラグインの無効化を記録し、そこから 10 分以内に JVM が終了した場合を正常停止とみなします。
6. 外部の状態ディレクトリー内の `restore-jobs/JOB-ID/status.properties` と `helper.log` を確認してください。`SUCCESS` と完了ログを確認し、ヘルパーが終了してから管理者がサーバーを起動します。自動再起動は行いません。

重要: 停止処理への移行記録は、「管理者が手動で停止したこと」や「全保存処理が正常終了したこと」の証明ではありません。障害時でも通常の停止処理に入った場合、予約済みの復元が実行されることがあります。自動停止・再起動を無効にし、予約後は次の停止で復元されるものとして運用してください。

Spigot での注意: 上記の 10 分の扱いのため、Spigot では、プラグインだけを無効化し、その後 10 分以内にサーバーが異常終了した場合にも、復元が適用され得ます。Spigot で復元を予約している間は、プラグインの再読み込みや無効化を行わないでください。プラグインの無効化から JVM の終了までに 10 分を超えた場合は、正常停止として扱わず、何も変更しません。その場合は、サーバーを起動して予約し直すか、停止したまま手動ヘルパーを使ってください。

バックアップは保存済みの正確なファイル構成に置き換えます。バックアップから除外されたファイルや後から追加したファイルは、元データのロールバック用フォルダーに残ります。古いプラグインを含むバックアップではプラグイン自身も古い版に戻るため、必要に応じて起動前に最新の JAR を配置し直してください。

手動実行・復旧の前には必ずサーバーを完全停止し、以下の英語セクションの手順を確認してください。Linux のラッパーは日英併記です。Windows の `.cmd` はコンソールの文字コード依存を避けるため ASCII で案内し、この文書を参照します。ラッパーも追加の入力待ちなしで実行します。実際の停止・ロック検証はヘルパーが行います。ヘルパーの状態と完了・失敗概要は `language: ja_JP` の予約では日本語で表示されます。詳細な OS/Java エラーは英語の場合があります。

配布 ZIP の helpers フォルダーには、ラッパーと並んで `vaultbackup-restore.jar`（バージョンを含まない名前）が入っており、ラッパーは既定でこれを使います。別の場所の JAR を使う場合は、環境変数 `VAULTBACKUP_HELPER_JAR` にそのパスを指定してください。

## Before reserving

- Disable hosting-panel automatic restart, watchdog restart, scheduled stop/restart tasks, systemd `Restart=always`, container restart policies, and third-party launch scripts for the entire operation. Do not launch any server JVM while the helper is running.
- Set both `backup-directory` and `state-directory` to real directories outside the configured server root. They cannot be ancestors of the root. Symlink path components are refused. The plugin enforces external storage for backups and restoration.
- Run Java 25 and the packaged helper as the same OS user that owns/runs the server. The parent directory must allow creating/renaming sibling directories. Root and staging must be on the same filesystem; atomic directory renames are required. Mount-point roots or filesystems without supported atomic rename are refused. Since 1.3.0 a mount-point root and a parent directory that is not writable are detected when the restore is reserved, while the server is still running, instead of after the stop.
- Have enough space for the extracted archive plus the existing root. The reservation requires free space beside the server root for the archive's uncompressed size plus a 64 MiB margin, and the helper checks again before it extracts. Original data is never intentionally deleted. The original root remains in a sibling rollback directory after success; the backup archive is untouched.
- Review the archive scope. Restore installs the exact backed-up tree, replacing the root. Files excluded from the backup or created afterward are absent from the installed tree and remain in the preserved original rollback. A filtered backup is not an entire-server snapshot.
- File contents, paths, and empty directories are restored. Original ownership, executable bits, ACLs, extended attributes, and exact timestamps are not reproduced. On POSIX the new root is owner-only (0700). Review permissions before intentional sharing or launching executable scripts. On Windows the new directory uses inherited ACLs.
- No restore is accepted when the plugin JAR does not contain the restore helper. The installed helper must be byte-identical to the bundled one, or it is not started.

## Reservation and manual-stop path

1. The explicit restore action fully verifies archive SHA-256, manifest, and every payload hash, then immediately submits the reservation. Internal single-use tokens are not a second user confirmation.
2. Reservation writes a durable external protocol-v2 job and starts the standalone helper while the server keeps running. The archive is hashed once more at this point only if its file identity, size or modification time changed since the verification a moment earlier; the helper verifies every byte again before it applies anything. The startup handshake is bounded to 15 seconds. Reservation does not request shutdown or restart. Repeated use of the same internal token refers to the same job and cannot launch an additional helper or revive a cancelled job.
3. `WAITING_FOR_MANUAL_STOP` means the helper is waiting without a time limit. Keep playing for as long as needed, then use the server's usual manual stop operation. The reservation itself never expires. The archive stays protected from deletion and retention while the reservation is unresolved.
4. When the plugin is disabled, it may write a job-bound authenticated stop marker, which also records what was known at that moment:
   - Paper and Purpur report through their API whether the server is stopping. The marker is written only then (evidence `SERVER_STOPPING`). A plugin reload or unload leaves no marker.
   - Spigot has no such API. The marker is written whenever the plugin is disabled (evidence `PLUGIN_DISABLED`), and the helper accepts it only if it observes the JVM exit within 10 minutes of the marker.

   In both cases the helper separately requires the recorded parent PID and process start instant to cease running. A living recorded JVM, a process that cannot be identified, a held root guard, or an active world session lock refuses restoration. A PID that now belongs to a process with a different start time (for example after a host reboot) is treated as the recorded JVM having exited; the root guard remains the decisive check. A lifetime file lock stays held until actual JVM exit; reaching `onDisable` never authorizes world writes.
5. If the JVM exits abruptly without the stop marker, the waiting helper fails before staging. The marker proves that the server shutdown path was entered (on Spigot: that the plugin was disabled shortly before the JVM exited), not that shutdown was manually requested or every save completed successfully. A graceful fault shutdown may enter the same path and trigger the already-accepted reservation. Disable other stop/restart automation and treat the next eligible shutdown as the restore trigger.
6. After all exit checks pass, the helper records the identity of the original root, extracts to an owner-only sibling directory, verifies all staged data, and forces file/directory writes before swapping. It renames the original root to rollback, then stage to root. If the server root was replaced while the backup was being extracted, nothing is moved.
7. The helper verifies every installed file and the exact tree before writing an authenticated success receipt. Status progresses through `PARENT_EXITED`, `STAGING`, `STAGED`, `MOVING_ORIGINAL`, `ORIGINAL_MOVED`, `INSTALLING`, `VERIFYING_INSTALLED`, and `SUCCESS`. The helper logs completion and never restarts the server.
8. Check the job's `status.properties` and `helper.log`, then wait for the helper process to exit. Manually start the server only after successful completion or reviewed recovery. At startup the plugin verifies the success receipt before accepting a successful journal and consumes the notification once. The receipt describes the verified offline installation time; normal startup may already have changed live files. Deleting the preserved rollback directory after a successful restore does not prevent that startup.

Spigot caveat: because of the ten-minute rule, a reserved restore can also be applied on Spigot when only the plugin was disabled (for example by a plugin manager) and the server then terminated abnormally within 10 minutes. Do not reload or disable the plugin while a restore is reserved on Spigot. Conversely, a stop that takes longer than 10 minutes between the plugin being disabled and the JVM exiting is not treated as a normal stop: nothing is changed, and the restore has to be reserved again or run through the stopped-server fallback below.

A failed or uncertain startup handshake is disarmed using a durable cancellation tombstone. If cancellation cannot be confirmed, the coordinator attempts to terminate only its own newly launched helper. It reports uncertainty explicitly and keeps the journal and archive protected. Never interpret a launch failure or a cancellation request as restore completion.

## Cancelling an accepted reservation

Cancel through the GUI or restore-cancel command before stopping the server. Cancelling verification prevents a reservation from being submitted. Cancelling an existing reservation requires authenticated acknowledgment.

- `CANCEL_PENDING` means a durable request exists but acknowledgment has not been verified. Keep the server running. The archive remains protected and the active marker is retained.
- A successful cancellation requires an authenticated, job-bound permanent tombstone plus the helper's acknowledgment or confirmed death of the exact helper process. A request file alone is insufficient.
- Cancellation and application share an interprocess job-state lock. The helper never holds that lock while waiting for the server to stop. Once application owns the lock, cancellation cannot falsely claim success.
- The active marker and archive pin are cleared only after safe acknowledgment. Repeated cancellation is safe. A new reservation can then be made in the same server JVM.
- The tombstone is retained permanently. A cancelled job cannot run automatically, with `--manual`, or with `--recover`, and cannot affect a later active job.
- Malformed, symlinked, mismatched, or unauthenticated cancellation/acknowledgment files fail closed. Do not delete or edit them to force a retry.
- `HELPER_EXITED` or `HELPER_UNCERTAIN` in the plugin means a waiting helper is unavailable or cannot be reliably identified. The reservation remains unresolved and protected. Cancel or investigate it before stopping; the plugin does not claim it is ready to restore.
- `/reload` or a plugin reload: the reloaded plugin reuses the root guard its server process already holds, cancels the waiting reservation and says so on the console. The server keeps running. Reserve again if the restore is still wanted. If the helper's acknowledgment of that cancellation cannot be confirmed within a few seconds, the plugin disables itself with a warning instead and the journal has to be resolved before the next restart. On Paper and Purpur, a plugin that is only disabled while the server keeps running logs that the reserved restore will not be applied.

## Explicit stopped-server fallback

Use this only for an active, uncancelled job with the server fully stopped and restart automation disabled. It is useful when a shutdown-path marker is missing or a stopped-server recovery was reviewed. A safely cancelled launch or reservation cannot be resumed; create a new reservation instead.

The release archives ship the helper as `helpers/vaultbackup-restore.jar`, beside the wrappers and without a version in its name; the wrappers use that file by default. To use another copy, set `VAULTBACKUP_HELPER_JAR` to its absolute path, for example the one the plugin installed at `<state-directory>/helper/vaultbackup-restore.jar`. A helper built from source is `build/libs/vaultbackup-restore-1.3.0.jar`. `JAVA_HOME` is supported, including paths with spaces. `restore.sh` selects the `C.UTF-8` locale when the current locale is not UTF-8 and that locale exists, because archive file names are UTF-8.

Linux:

```sh
./restore.sh /absolute/state/restore-jobs/JOB-ID/job.properties
```

Windows:

```bat
restore.cmd C:\absolute\state\restore-jobs\JOB-ID\job.properties
```

The wrapper does not prompt for input: running it is the operator's explicit confirmation, and it passes `--manual --i-confirm-server-stopped` (or `--recover --i-confirm-server-stopped`) for you. The helper still independently refuses a living server JVM, a held root guard and active world locks. Direct invocation requires both explicit flags:

```sh
java -jar vaultbackup-restore.jar --job /absolute/job.properties --manual --i-confirm-server-stopped
```

Manual invocation never bypasses a living recorded JVM, an unidentifiable recorded process, the root lock, active world session locks, or a cancellation tombstone. It does not require the stop marker, which is why the server must be verifiably stopped first. It cannot silently resume an interrupted swap. Older protocol-v1 jobs require explicit stopped-server review/manual execution; the new automatic waiting path refuses them. Existing correctly authenticated success receipts remain readable.

## Failure and crash recovery

Review `state/restore-jobs/JOB-ID/status.properties` and `helper.log`. Do not delete state, stage, rollback, or failed-tree siblings to force a retry.

- Before the first rename, failure leaves the original root untouched. Since 1.3.0 the helper removes the partially extracted stage in that case and records `FAILED_BEFORE_SWAP`.
- After the first rename, a normal failure attempts to put the original root back. Any installed/failed tree is moved to a separate sibling instead of deleted. No success receipt is emitted. The next startup reports on the console that the restore failed and the original data was put back.
- Only a directory the helper can identify as the original root it recorded is moved back. A directory that merely exists at the rollback path, which another local user able to write the parent directory could have created, is never moved into the server root and is left untouched. Where the filesystem reports no file identities (Windows), this check is not available and the helper relies on the journal and the path alone, as before 1.3.0.
- A crash or ambiguous rename journal never automatically resumes installation. Keep the server stopped and inspect the retained paths.
- `--recover --i-confirm-server-stopped`, or the wrapper's optional `--recover` argument, restores a present preserved original rollback for an active, uncancelled job. An existing current root is first retained in the failed-tree sibling. If the directory at the rollback path is not the recorded original root, if the original location is not provable, or if a preservation target already exists, recovery refuses to guess or overwrite. If the directory in place is identified as the original root, recovery records that and changes no data.
- Recovery does not automatically reverse a completed successful restore. The preserved rollback is available for a separate reviewed recovery operation.
- A safely unchanged pre-swap job can be durably cancelled and reconciled. Completed rollback/recovery requires authenticated completion evidence before cleanup. Cancellation uncertainty retains the root lease and disables conflicting plugin actions; active swaps, unsafe roots, and invalid completion evidence still require stopped-server review. A status word alone is not proof.

Backups, original data, and failure evidence are not automatically removed by the restore helper; the only thing it removes is its own partially extracted stage after a failure before the swap. Cleanup requires a separate administrator decision after verification.

## Operational limits

The helper enforces at most 1,000,000 entries, 256 GiB per file, 1 TiB uncompressed total, compression ratio 10,000, 128 MiB manifest, 8,192-character manifest records, and 4,096-character relative paths. It rejects path traversal, duplicates, case/Unicode aliases including implicit ancestors, reserved Windows names, unsafe cross-platform names, unmanifested payloads, symlink roots, and file/directory collisions. Before an archive is opened, the end of the ZIP file is read and a central directory beyond those limits is refused.

The guard coordinates this plugin and its helper. It cannot prevent an unrelated operator, host watchdog, or uncooperative JVM from accessing the root before its plugin loads. Disabling stop/restart automation and not starting another process during restore are required operating conditions. Same-OS-account malicious tampering is outside the receipt authentication boundary.

Restoring an archive also restores its plugin JARs and configuration. An older archive can therefore reinstall an earlier VaultBackupPro implementation, including older releases or earlier revisions with the same version label. An archive made with 1.2.x or earlier brings back that release's obfuscated JAR. Keep the current release outside the server root and reapply it before startup when necessary to retain the manual-stop reservation behavior.


## 起動時記録の扱い（1.2.0 以降）
古い待機予約は、元データが未変更と判断できる場合に取消処理します。取消の永続記録と停止確認が完了しない間は、サーバーの保護ロックを保持し、復元機能と競合する操作を保留します。実際に復元中、root が欠落、置換途中など元データの状態が不明な場合は、普通にワールドを起動せず復旧が必要です。全ての起動制約がなくなるわけではありません。

1.3.0 では、起動時にサーバーの停止を要求する範囲を限定しました。停止を要求するのは、他のプロセス（作業中の復元ヘルパーや別のサーバー）が保護ロックを保持している場合と、状態保存先に復元記録が存在する（または記録の有無を確かめられない）状態で安全確認に失敗した場合だけです。それ以外の問題では、プラグインだけを無効にし、サーバーは稼働を続けます。`/reload` やプラグインの再読み込みでは、同じサーバープロセスが保持している保護ロックを引き継ぐため、サーバーは停止しません。前回の起動から残っていた予約を取り消した場合と、前回の復元が入れ替えの途中で失敗して元のデータへ戻された場合は、起動時にコンソールへ表示します。
