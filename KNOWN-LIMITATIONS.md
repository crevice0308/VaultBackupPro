# Known limitations and production gates

## プレリリース（テスト版）・必須の起動条件

この 1.3.0 はプレリリースのテスト用配布です。1.3.0 の JAR では実サーバーでの試験を実施していません（サーバーの起動に Minecraft EULA への同意が必要なため）。1.3.0 について行ったのは、ビルド時の検査と、サーバーを起動しない隔離試験だけです（結果は TEST-RESULTS.md）。本番環境で使う前に、試験環境でバックアップと復元を必ず確認してください。

ホストの自動再起動を無効にし、復元ヘルパーの完了と終了を確認してから手動起動してください。復元処理中に別のサーバーを起動する運用は未対応です。1.2.0 の実サーバー試験では、Paper がプラグインの停止要求後にもワールドを書き込むことが観測されており（1.3.0 では再試験していません）、プラグインだけではその競合によるデータ破損を防げません。ホスティング側の設定を確認できない環境で本番安全を保証しません。

docs/RUNTIME-TEST-RESULTS.md と docs/test-evidence にある実サーバーの結果は過去の版（難読化ビルド）のもので、1.3.0 を対象としていません。


1. Live backups cannot promise atomic consistency for external databases, async plugin files, remote storage or another writer. Use application exports/filesystem snapshots/maintenance shutdown for those workloads. A file that keeps changing while it is copied is stored as last read and counted; such a file may be internally inconsistent.
2. Offline restore requires local filesystem semantics, Java 25, an external writable state directory and external backup directory. A host must permit launching a Java helper process. The server root must not be a mount point (a volume of its own), and the user running the server must be able to write to the directory that contains it; otherwise a restore is refused when it is reserved. If those conditions fail, the plugin must fail closed rather than overwrite a live root.
3. Disable host/watchdog auto-restart. Starting another uncooperative process during restoration is outside the helper's lock protocol. Do not restart manually until SUCCESS and helper exit are both confirmed, or an explicit recovery has restored a verified safe original tree. RECOVERY_REQUIRED is not permission to restart.
4. Backups are plaintext ZIPs and may contain authentication secrets, player identities and webhook configuration. Restrict operating-system permissions; encrypt backups separately when required. Do not publish or upload real backups with support requests.
5. Custom exclusions remove those paths from the restored root. Originals remain in rollback. Symlink/special-file handling is intentionally conservative: a symlink, special file, unreadable file or directory, or a name that cannot be stored refuses the whole backup until it is excluded or renamed. Remote mounts and symlink-based server layouts need separate evaluation.
6. Rollback directories are retained rather than automatically deleted; provision space for archive, staging and the entire old root. A power loss can leave a journal needing manual recovery. Do not delete rollback or job evidence until checked.
7. A checksum is not a signature or protection against a malicious same-account administrator. Routine post-backup retention, outcome listings, `/backup list` and the GUI catalogue reuse an archive's earlier full verification in the same server run while its file identity, size and timestamps are unchanged. Silent storage corruption that changes none of these is detected at the next startup, by `/backup verify` or the GUI re-verify button, and always for the selected archive before a restore reservation.
8. Windows scripts are supplied, but actual Windows execution is only verified if explicitly listed in TEST-RESULTS.md. Linux testing cannot certify Windows behavior. Where the filesystem reports no file identities (Windows), the helper cannot confirm that a directory is the recorded original server root and relies on the journal and the path alone, as before 1.3.0.
9. GUI interaction with a real Minecraft client, hosting-panel policies and every third-party plugin combination must be separately tested before production use.
10. Minecraft/Paper/Purpur/Spigot future builds may change behavior. Since 1.3.0 only versions older than 26.2 and regionized servers (Folia) are refused; versions newer than 26.3 run with a warning and are untested. Purpur 26.3 was experimental upstream when it was last checked on 2026-10-13.
11. 復元予約は自動停止しません。取消は「取消確認済み」になるまで完了していません。CANCEL_PENDING のまま停止せず、案内に従ってください。復元適用開始後の取消・自動的な取り消しはできません。
12. 通常停止経路の認証マーカーと実 JVM 終了を両方確認します。マーカーは管理者が stop を入力した証明や全保存の完了証明ではありません。通常のシャットダウン経路に入る障害停止も対象になり得ます。SIGKILL 等のマーカーなし終了は自動復元を拒否します。
13. 待機に自動期限はありません。ヘルパー停止・状態不明時は案内を確認し、予約を放置したまま再起動しないでください。古い待機予約が自動整理できても、復元途中やデータ状態不明の起動は保護されます。ホストによる子プロセス終了にも注意してください。
14. 古いバックアップの完全復元では、プラグイン JAR・設定も当時に戻ります。今回の改訂前のバックアップは旧自動停止方式や英語設定を復元する可能性があります。1.2.x 以前のバックアップを復元すると、難読化された旧版の JAR に戻ります。
15. 本改訂は無料・GPL-3.0-only で、公開ソースをそのままビルドした、難読化していない JAR を提供します。マーケットでの審査合格や認証を意味しません。GPL は第三者の商用利用や有償再配布を禁止しません。
16. 1.3.0 は実サーバーで試験していません。次の点は、ビルド時の検査（模擬したサーバーと、サーバーを起動しない隔離試験）で扱っているだけで、実サーバーでは確認していません: `minecraft:save-all flush` の実行と保存の確認、Paper・Purpur での Brigadier コマンドの登録と表示、Adventure コンポーネントによる GUI とメッセージ、Spigot の plugin.yml コマンド、停止の判定、復元の一連の流れ。Spigot の実機、Windows、実クライアントでの GUI 操作も未確認です。
17. Spigot には、サーバーが停止中かどうかを確認する API がありません。そのため Spigot では、プラグインの無効化から 10 分以内に JVM が終了した場合を正常停止とみなします。この結果、プラグインだけを無効化（プラグイン管理ツールによる無効化など）し、その後 10 分以内にサーバーが異常終了した場合にも、予約済みの復元が適用され得ます。Spigot で復元を予約している間は、プラグインの再読み込みや無効化を行わないでください。逆に、プラグインの無効化から JVM の終了までに 10 分を超えた停止では、復元を適用しません。Paper・Purpur は API で確認するため、この扱いはありません。
18. `/reload` やプラグインの再読み込みを行うと、復元予約は取り消されます（サーバーは停止しません）。再読み込み自体は推奨しません。保存先の変更にはサーバーの再起動が必要です。
19. 稼働中のバックアップでは、コピー中に変化し続けたファイルを最後に読み取った内容で保存し、件数を表示します。どのファイルだったかは作成時のサーバーログにしか残りません（最大 20 件）。件数が 0 でも、全ファイルが同じ時点の内容であることは意味しません。
20. バックアップできないパスが 1 件でもあると、バックアップ全体を拒否します。既存の config.yml には新しい既定の除外（`**/*.log`、`lost+found/**`）を追加しないため、ボリューム直下の `lost+found` のような読めないフォルダーがある場合は、自分で除外に追加する必要があります。サーバーログに個別に出力するのは最大 50 件です。
21. POSIX 系の OS でサーバーを UTF-8 以外のロケールで起動している場合、ASCII 以外の名前のファイルがあるとバックアップを拒否します（起動時に警告します）。UTF-8 ロケールで起動してください。
22. ワールドの保存には vanilla の `minecraft:save-all flush` コマンドを使います。このコマンドを無効化・遮断しているサーバーでは、バックアップを開始できません。
23. `/backup` はプレイヤー、コンソール、RCON からしか実行できません。コマンドブロックなどからの定期実行はできないため、定期実行には自動バックアップを使用してください。
24. コンテナー型のホスティングなど、サーバールートが独立したボリュームとしてマウントされている環境では、復元を予約できません。また、バックアップと状態の保存先はサーバールートの外に必要です。

See TEST-RESULTS.md for measured results, not assumed coverage.

1.3.0, in English: no real-server test was run for this release. On Spigot, which has no API that tells whether the server is stopping, a JVM exit within 10 minutes after the plugin was disabled counts as a normal stop; a reserved restore can therefore also be applied when only the plugin was disabled and the server then terminated abnormally within those 10 minutes. Do not reload or disable the plugin while a restore is reserved. `/reload` or a plugin reload cancels a reservation. A backup is refused as a whole when any selected path cannot be archived, and, on a POSIX system started without a UTF-8 locale, when a file with a non-ASCII name exists. The world save runs the vanilla `minecraft:save-all flush` command, which must be available. `/backup` cannot be run from command blocks.

Filesystem metadata: ZIP restores preserve file contents and directory structure, not POSIX ownership, ACLs, executable bits, extended attributes or hard-link relationships. Review permissions after restoration; launch Linux scripts through `sh` or restore their executable bit intentionally. Symlinks are refused rather than followed. This tool is not a filesystem-image backup.

Crash durability: archive bytes and metadata are forced to storage, and Linux publication/deletion directory entries are fsynced before retention proceeds. Standard Java on Windows may not support directory fsync; metadata durability across sudden power loss is therefore platform/filesystem dependent and is not certified by these tests. Atomic rename is not a guarantee against every power-loss or storage-controller failure. Keep independent generations outside this server.

Since 1.2.0, manual deletion executes without an extra confirmation, including the final archive. Automatic retention may also leave zero archives. These actions cannot be undone through the plugin. Restore-reserved archives remain protected. Keep independent recovery copies.

Paper の `.paper/console_history` はコンソール操作中に更新されることがあります。1.2.1 までは、バックアップ中にこうしたファイルが変わると、整合性検査がバックアップを中止する場合がありました。1.3.0 では中止せず、読み直しても落ち着かない場合に、変化し続けたファイルとして数えます。新規設定ではこの履歴ファイルを既定除外にしています。既存設定は変更しないため、必要に応じて同じパスを追加してください。除外ファイルは復元対象に入りません。過去の版の実サーバー試験では、同既定除外に加え Spark の一時出力を明示的に除外していました。
