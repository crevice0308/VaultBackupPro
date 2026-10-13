# VaultBackup Pro 1.1.0

最後の正常なバックアップも、0 件になる警告を確認すると手動で削除できるようにしました。削除したバックアップからは復元できません。

- GUI・コマンドに日本語/英語の具体的な警告と確認
- 確認待ちの間に最後の 1 件へ変わった場合は再警告・再確認
- 自動の世代保持では最後の正常な 1 件を保護
- 復元予約対象の削除保護は維持
- 想定内の確認要求は理由を表示し、異常 I/O は診断ログを保持
- 復元中の状態記録障害でもロールバックを試行し、元データ・エラーを保全
- Gradle 9.8.1 と依存 SHA-256 検証、ProGuard 7.10.0 による難読化

Paper / Purpur / Spigot 26.2・26.3、Java 25。無料・GPL-3.0-only、難読化 JAR と対応ソースを提供します。公開済み 1.0.0 は変更しません。

634件の実行assertion、難読化後のJVM/resource検証、および6構成すべての実サーバー試験が合格。最終検証結果は TEST-RESULTS.md と docs/RUNTIME-TEST-RESULTS.md に記載しています。Windows の今回版と実 Minecraft クライアント GUI 操作は未検証、Purpur 26.3 は experimental です。無人 pause 自体は今回の不具合原因ではなく、別の隔離比較で自動バックアップの継続を確認しました。

プラグイン JAR の更新は正常停止してから行ってください。フル復元の流れは『予約 → 自分で stop → ヘルパー SUCCESS と終了確認 → 手動起動』のままです。稼働中のワールドを上書きする復元は行いません。ホストの自動再起動を無効にしてください。

Public packages contain no user logs or local fixture data. GPLv3 permits commercial use and paid redistribution under its terms; no warranty is provided. Not an official Minecraft product and not approved by or associated with Mojang or Microsoft.

Plugin SHA-256: `54b356a6d7a4ccf5a8e2b62b8d556b033c1c76f8cfe7910e19f95c34328e177b`
