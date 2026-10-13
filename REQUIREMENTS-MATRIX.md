# 要件と実装の対応（VaultBackup Pro 1.0.0）

| 要件 | 実装・検証 |
|---|---|
| Paper/Purpur/Spigot 26.2/26.3、Java 25 | 明示的バージョン判定。公式取得・BuildTools 由来の正確なビルドと最終 JAR 実行結果は TEST-RESULTS.md |
| 管理者 GUI、権限、コマンド、補完 | BackupMenus / BackupCommands / plugin.yml。GUI 所有者識別と確認画面。実 Minecraft クライアント操作は未検証 |
| 日本語・英語 | ja_JP 標準、en_US 切替。メッセージキー・置換パラメーターの一致検証。旧設定には安全な既定値 |
| サーバールート全体の ZIP | BackupEngine、外部出力・制御ディレクトリ、除外、世代・容量管理、少なくとも正常 1 世代維持 |
| 完全性 | 全体 SHA-256、エントリーごとの manifest、メタデータ最終公開、不完全 ZIP 不採用 |
| 保存と非同期処理 | メインスレッドで ServerLifecycle の検証済み saveEverything(true,true,true)、プレイヤー含む。単一非同期処理、autosave 復帰。外部 DB の原子性は保証しない |
| 復元予約・手動停止 | 二段階確認後もプレイ継続。状態に「手動停止待ち」。通常 stop 後、実 PID/start identity の終了を待つ |
| 停止前の予約取消 | 永続 tombstone、ジョブ排他ゲート、ヘルパー応答確認。取消確認済みジョブは二度と復元しない。適用後取消は不可 |
| 安全なオフライン復元 | 外部 Java ヘルパー、ルート lifetime guard、停止マーカー、セッションロック、検証ステージ、原本 rollback、認証済み receipt |
| 再起動と完了表示 | 自動再起動なし。ヘルパー状態・ログで終了確認、手動再起動時に成功/失敗通知。別進捗ウィンドウなし |
| Windows/Linux | 両方のラッパー提供。本改訂の Linux 実行検証と Windows 未実行を区別 |
| 自動バックアップ | GUI 5 分～24 時間、カスタム分指定、設定保持。元版の実時間テストと本改訂の結果を区別 |
| パス・ZIP 攻撃 | traversal、大小文字/Unicode 衝突、symlink、容量上限、再帰保存先防止、失敗時 rollback の fixture |
| Discord | 明示設定時のみ HTTPS Discord エンドポイント。実ユーザーデータ・実 webhook の送信なし |
| 無料・オープンソース | GPL-3.0-only 全文、第三者通知、通常の非難読化 JAR、対応ソース・Gradle・試験・文書を同梱。公開ファイル名は PUBLICATION-NOTES.md を参照 |

数値カスタム入力は文書記載のコマンドを使います。保存先変更は設定編集後に再起動します。
