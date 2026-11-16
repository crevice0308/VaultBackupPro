# VaultBackup Pro 1.2.2 — プレリリース

不具合・脆弱性の修正版です。1.2.1 以前をお使いの場合は更新を推奨します。詳細は docs/SECURITY-REVIEW.md と CHANGELOG.md を参照してください。

- **保存先はサーバーごとに分けてください。** 同じ親フォルダーに複数のサーバーを置き既定の保存先を共有していると、2 台目以降のサーバーでは VaultBackup が無効になります（サーバーは停止しません）。サーバーごとに `storage.backup-directory`・`storage.state-directory`・`restore.helper-jar` を指定してください。
- systemd ではユニットに `KillMode=process` を設定してください。Docker・Pterodactyl などのコンテナーでは、停止後に restore.sh による手動復元を使ってください。いずれも既定では停止時に復元ヘルパーも終了します。
- `backup.include` にディレクトリを書くと、その配下すべてを含めるようになりました。
- 削除は確認画面なしで実行され、取り消せません。保持設定によってバックアップが 0 件になります。
- ホストの自動再起動を無効にし、復元ヘルパーの SUCCESS と終了を確認してから手動起動してください。復元中の同時起動は未対応です。
- 本版は実サーバー試験を再実施していません。Spigot 実行、Windows、macOS、実クライアント GUI も未確認。Purpur 26.3 は experimental です。

無料 GPLv3。難読化 JAR と対応ソースを同梱。Java 25、詳細な使用条件・検証範囲は README と TEST-RESULTS を参照してください。
