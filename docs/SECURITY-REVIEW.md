# 運用上の境界 — VaultBackup Pro 1.2.1

この版は操作手順を簡略化しています。削除は追加確認なしで行い、保持設定によって最後のバックアップもなくなります。独立した復旧用コピーを保管してください。

管理者権限、アーカイブのパス・整合性検証、書込みの排他、実プロセス終了後だけの復元は維持します。予約対象は削除・世代整理から保護します。表示や設定の変更で、予約済みのヘルパーの認証情報や対象アーカイブを変更しません。

起動時の古い待機予約は、元データが未変更と判断できる範囲で取消処理します。取消が確定しない場合は保護ロックを保持したままバックアップ機能を停止/保留する場合があります。実復元中や不完全な置換が疑われる場合は、普通にワールドを起動せず復旧が必要です。状態ファイルや退避フォルダーを手動で削除して回避しないでください。

プラグインはホストや別プロセスの起動を完全に管理できません。復元中の自動再起動を止め、ヘルパー完了前にサーバーを起動しない運用が必要です。稼働中のバックアップは外部DBや任意の同時書込みを原子的に止めるものではありません。

最終 JAR の実行済み試験、難読化後の検証、未確認環境は [TEST-RESULTS.md](../TEST-RESULTS.md) に記載します。コードレビューや試験合格は無欠陥・無損失保証や第三者認証ではありません。Windows と実クライアントの操作は、実行していない場合は明記します。

ビルド用ツールとサーバーAPIはプラグインに同梱せず、固定バージョンとSHA-256で検証します。GPLv3対応ソース、テスト、難読化設定をソースZIPに含めます。

## Additional measured startup boundary

An isolated Paper 26.3 build 179 probe held the root guard in an independent process. The plugin requested shutdown, but Paper subsequently prepared/saved the world and changed level.dat before exiting. Consequently, concurrent server startup during restoration is unsupported; the plugin cannot guarantee prevention of those writes or resulting corruption. Auto-restart must be disabled and manual startup must wait for helper completion and exit. The previous independent source review did not certify this subsequently measured boundary; an additional independent review was unavailable. This distribution is an prerelease test build, not a production-safety certification. The cause of the reported user shutdown incident remains unconfirmed.

## 1.2.1 で修正した問題

- バックアップ ZIP と付属情報が既定の権限（多くの環境で他ユーザーも読める 0644）で作成され、既存の共有ディレクトリでは秘密情報が読める状態でした。POSIX では作成時から 0600 にしました。復元ジョブの記録ファイルも作成時点から 0600 にしました。
- 復元ヘルパー JAR を起動直前に検証していませんでした。プラグイン同梱版の SHA-256 と一致しない場合は起動せず、予約を取り消します。
- Discord 通知の JSON で一部の制御文字がエスケープされず、内容が 2000 文字を超え得ました。完全なエスケープと長さ制限を追加しました。
- GUI のクリックイベント処理中にインベントリを閉じていました（Bukkit が禁止する呼び出し）。次のティックで閉じるようにしました。
- 保存先がサーバールートの親フォルダー、または状態保存先を作成できない場合などの設定上の問題で、起動時の安全停止としてサーバー全体を停止していました。設定エラーとしてプラグインだけを無効にします。
- 自動バックアップのたびに全アーカイブを2回ずつ全体検証していたため、大きなサーバーで長時間ディスクを占有していました。未変更のアーカイブは再読込しません（起動時・一覧更新・復元前は従来どおり全体検証）。
- 強制停止で残った作成途中の一時ファイルが削除されず、容量を消費し続けていました。
- 復元予約に、直前に検証した記録ではなく一覧の記録を使っていました。ヘルパー起動失敗の理由がサーバーログに出ていませんでした。

上記の修正はソースレビューと隔離試験（TEST-RESULTS.md）で確認しました。1.2.1 では実サーバー試験を再実施していません。
