# 運用上の境界 — VaultBackup Pro 1.2.0

この版は操作手順を簡略化しています。削除は追加確認なしで行い、保持設定によって最後のバックアップもなくなります。独立した復旧用コピーを保管してください。

管理者権限、アーカイブのパス・整合性検証、書込みの排他、実プロセス終了後だけの復元は維持します。予約対象は削除・世代整理から保護します。表示や設定の変更で、予約済みのヘルパーの認証情報や対象アーカイブを変更しません。

起動時の古い待機予約は、元データが未変更と判断できる範囲で取消処理します。取消が確定しない場合は保護ロックを保持したままバックアップ機能を停止/保留する場合があります。実復元中や不完全な置換が疑われる場合は、普通にワールドを起動せず復旧が必要です。状態ファイルや退避フォルダーを手動で削除して回避しないでください。

プラグインはホストや別プロセスの起動を完全に管理できません。復元中の自動再起動を止め、ヘルパー完了前にサーバーを起動しない運用が必要です。稼働中のバックアップは外部DBや任意の同時書込みを原子的に止めるものではありません。

最終 JAR の実行済み試験、難読化後の検証、未確認環境は [TEST-RESULTS.md](../TEST-RESULTS.md) に記載します。コードレビューや試験合格は無欠陥・無損失保証や第三者認証ではありません。Windows と実クライアントの操作は、実行していない場合は明記します。

ビルド用ツールとサーバーAPIはプラグインに同梱せず、固定バージョンとSHA-256で検証します。GPLv3対応ソース、テスト、難読化設定をソースZIPに含めます。

## Additional measured startup boundary

An isolated Paper 26.3 build 179 probe held the root guard in an independent process. The plugin requested shutdown, but Paper subsequently prepared/saved the world and changed level.dat before exiting. Consequently, concurrent server startup during restoration is unsupported; the plugin cannot guarantee prevention of those writes or resulting corruption. Auto-restart must be disabled and manual startup must wait for helper completion and exit. The previous independent source review did not certify this subsequently measured boundary; an additional independent review was unavailable. This distribution is an prerelease test build, not a production-safety certification. The cause of the reported user shutdown incident remains unconfirmed.
