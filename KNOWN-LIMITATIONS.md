# Known limitations and production gates

## プレリリース（テスト版）・必須の起動条件

この 1.2.2 はプレリリースのテスト用配布です。ホストの自動再起動を無効にし、復元ヘルパーの完了と終了を確認してから手動起動してください。復元処理中に別のサーバーを起動する運用は未対応です。Paper はプラグインの停止要求後にもワールドを書き込むことが実測されており、プラグインだけではその競合によるデータ破損を防げません。ホスティング側の設定を確認できない環境で本番安全を保証しません。


1. Live backups cannot promise atomic consistency for external databases, async plugin files, remote storage or another writer. Use application exports/filesystem snapshots/maintenance shutdown for those workloads.
2. Offline restore requires local filesystem semantics, Java 25, an external writable state directory and external backup directory. A host must permit launching a Java helper process. If those conditions fail, the plugin must fail closed rather than overwrite a live root.
3. Disable host/watchdog auto-restart. Starting another uncooperative process during restoration is outside the helper's lock protocol. Do not restart manually until SUCCESS and helper exit are both confirmed, or an explicit recovery has restored a verified safe original tree. RECOVERY_REQUIRED is not permission to restart.
4. Backups are plaintext ZIPs and may contain authentication secrets, player identities and webhook configuration. Restrict operating-system permissions; encrypt backups separately when required. Do not publish or upload real backups with support requests.
5. Custom exclusions remove those paths from the restored root. Originals remain in rollback. Symlink/special-file handling is intentionally conservative. Remote mounts and symlink-based server layouts need separate evaluation. Since 1.2.2 a symlinked or differently spelled *ancestor* of `storage.server-root` is resolved during configuration; the server root itself must still be a real directory.
6. Rollback directories are retained rather than automatically deleted; provision space for archive, staging and the entire old root. Since 1.2.2 the helper refuses to stage when the free space beside the server root is smaller than the expanded backup (plus a margin), and removes a partial stage it created when a failure happens before any rename. A power loss can leave a journal needing manual recovery. Do not delete rollback or job evidence until checked; after SUCCESS has been checked, removing the rollback no longer blocks the next startup.
7. A checksum is not a signature or protection against a malicious same-account administrator. Routine post-backup retention and outcome listings reuse an archive's earlier full verification in the same server run while its file identity, size and timestamps are unchanged. Silent storage corruption that changes none of these is detected at the next startup, `/backup list` or GUI catalogue refresh, and always before a restore reservation.
8. Windows scripts are supplied, but actual Windows execution is only verified if explicitly listed in TEST-RESULTS.md. Linux testing cannot certify Windows behavior. On Windows, close every console, Explorer window or program whose current folder is inside the server folder before stopping: Windows cannot rename a folder that is in use (the helper retries for up to 30 seconds). macOS folder names with non-ASCII characters are handled by a normalization-aware comparison that has not been tested on a Mac.
9. GUI interaction with a real Minecraft client, hosting-panel policies and every third-party plugin combination must be separately tested before production use.
10. Purpur 26.3 is experimental. Minecraft/Paper/Purpur/Spigot future builds may change behavior; supported version gating intentionally rejects unrelated versions.
11. 復元予約は自動停止しません。取消は「取消確認済み」になるまで完了していません。CANCEL_PENDING のまま停止せず、案内に従ってください。復元適用開始後の取消・自動的な取り消しはできません。
12. 通常停止経路の認証マーカーと実 JVM 終了を両方確認します。マーカーは管理者が stop を入力した証明や全保存の完了証明ではありません。通常のシャットダウン経路に入る障害停止も対象になり得ます。SIGKILL 等のマーカーなし終了は自動復元を拒否します。
13. 待機に自動期限はありません。ヘルパー停止・状態不明時は案内を確認し、予約を放置したまま再起動しないでください。古い待機予約が自動整理できても、復元途中やデータ状態不明の起動は保護されます。ホストによる子プロセス終了にも注意してください。Linux ではヘルパーを別セッション（setsid）で起動するため、端末の Ctrl+C や SSH 切断ではヘルパーは終了しません。一方、systemd の既定（KillMode=control-group / mixed）、Docker・Pterodactyl などのコンテナ停止、macOS launchd は停止時にヘルパーも終了させます。これらの環境ではユニットに KillMode=process を設定するか、自動適用を使わず停止後に restore.sh で手動復元してください。ヘルパーが終了した予約は次回起動時に安全に取り消されます。
14. 古いバックアップの完全復元では、プラグイン JAR・設定も当時に戻ります。今回の改訂前のバックアップは旧自動停止方式や英語設定を復元する可能性があります。
15. 本改訂は無料・GPL-3.0-only で、公開ソースに対応する難読化 JAR を提供します。マーケットでの審査合格や認証を意味しません。GPL は第三者の商用利用や有償再配布を禁止しません。

See TEST-RESULTS.md for measured results, not assumed coverage.

Filesystem metadata: ZIP restores preserve file contents and directory structure, not POSIX ownership, ACLs, executable bits, extended attributes or hard-link relationships. Review permissions after restoration; launch Linux scripts through `sh` or restore their executable bit intentionally. Symlinks are refused rather than followed. This tool is not a filesystem-image backup.

Crash durability: archive bytes and metadata are forced to storage, and Linux publication/deletion directory entries are fsynced before retention proceeds. Standard Java on Windows may not support directory fsync; metadata durability across sudden power loss is therefore platform/filesystem dependent and is not certified by these tests. Atomic rename is not a guarantee against every power-loss or storage-controller failure. Keep independent generations outside this server.

Since 1.2.0, manual deletion executes without an extra confirmation, including the final archive. Automatic retention may also leave zero archives. These actions cannot be undone through the plugin. Restore-reserved archives remain protected. Keep independent recovery copies.

Paper の `.paper/console_history` はコンソール操作中に更新されることがあります。バックアップ中の一覧・状況コマンドでもこの外部ファイルが変わると、整合性検査がバックアップを中止する場合があります。新規設定ではこの履歴ファイルだけを既定除外にしています。既存設定は変更しないため、必要に応じて同じパスを追加してください。除外ファイルは復元対象に入りません。試験では同既定除外に加え Spark の一時出力を明示的に除外します。ファイル変更検知は維持しています。

## 1.2.2 で追加・変更された動作

- バックアップ保存先と状態保存先は 1 台のサーバー専用です。初回起動時に `.vaultbackup-owner.properties` でサーバーのルートを記録し、別のサーバーが同じ保存先を使うと、そのサーバーの VaultBackup だけを無効にします（サーバーは停止しません）。同じ親フォルダーに複数サーバーを並べる場合は、サーバーごとに `storage.backup-directory`・`storage.state-directory`・`restore.helper-jar` を指定してください。サーバーを移動した場合のみ、ほかのサーバーが使っていないことを確認してからマーカーを削除してください。
- `backup.include` はディレクトリにも適用されます。`world` のようにディレクトリへ一致するパターンは、その配下すべてを含めます（除外と同じ規則）。それ以外のディレクトリは、含めるファイルの親としてのみ保存します。
- Windows 固有の名前規則（CON・NUL・COM1・LPT1 などの予約名、`<>:"|?*`、末尾のドットや空白）は Windows 上でのみ適用します。Linux/macOS では `con.yml` などを含んでもバックアップは成功し、その名前を含むバックアップは Windows 上の検証で拒否されます（大文字小文字の違いだけの名前の衝突は全環境で拒否します）。
- サーバーのファイル名文字コードが UTF-8 でないなど、名前を変えずに保存できないファイル（U+FFFD を含む名前）があるとバックアップを拒否し、理由を表示します。UTF-8 ロケール（例: `LANG=C.UTF-8`）で起動するか、そのファイルを除外してください。
- 検証に失敗したバックアップは一覧・復元・保存制限の対象外となり、そのことをサーバーログ（初回のみ）・`/backup list`・Discord 通知（有効時）で知らせます。ファイルは削除しません。
- 付属情報のない `ID.zip`（削除や作成の中断で残ったもの）は、保存制限と削除の処理中に自動で片付けます。

