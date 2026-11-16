# VaultBackup Pro 1.3.0 — 配布情報

ローカル納品用の VaultBackupPro-1.3.0.jar、VaultBackupPro-1.3.0-plugin.zip、VaultBackupPro-1.3.0-source.zip です。GitHub ではプレリリースとして配布します。公開済み 1.0.0、1.1.0、1.2.0、1.2.1 は変更しません。ソースのリポジトリは https://github.com/crevice0308/VaultBackupPro です。

1.3.0 の JAR では実サーバーでの試験を実施していません。docs/RUNTIME-TEST-RESULTS.md と docs/test-evidence にある実サーバーの結果は過去の版（難読化ビルド）のもので、1.3.0 を対象としていません。

無料 GPL-3.0-only。プラグインと独立ヘルパーは、公開しているソースをそのままビルドしたもので、難読化していません。実行用 ZIP（-plugin.zip）にはプラグイン、独立ヘルパー、ヘルパー用スクリプト、文書、ライセンスと通知を、ソース ZIP（-source.zip）にはそれに加えて対応ソース、ビルド設定、検証用コードを含めます。生のユーザーログ、認証情報、実サーバー、ワールド、バックアップ、作業キャッシュは含めません。

配布 ZIP の中のヘルパーは helpers/vaultbackup-restore.jar（バージョンを含まない名前）です。helpers/restore.sh・restore.cmd は既定で隣のこのファイルを使い、環境変数 VAULTBACKUP_HELPER_JAR で別のパスを指定できます。ビルドした直後の成果物は build/libs/VaultBackupPro-1.3.0.jar と build/libs/vaultbackup-restore-1.3.0.jar です。

利用上の注意、最終ハッシュ、確認した環境と未確認事項は README.md、TEST-RESULTS.md、release/SHA256SUMS.txt、KNOWN-LIMITATIONS.md を参照してください。変更点は CHANGELOG.md の 1.3.0 の節に記載しています。1.2.x のバックアップと状態ディレクトリはそのまま使え、config.yml の変更は不要です。

## プレリリース（テスト版）・必須の起動条件

この 1.3.0 はプレリリースのテスト用配布です。1.3.0 の JAR では実サーバーでの試験を実施していません（サーバーの起動に Minecraft EULA への同意が必要なため）。1.3.0 について行ったのは、ビルド時の検査と、サーバーを起動しない隔離試験だけです（結果は TEST-RESULTS.md）。本番環境で使う前に、試験環境でバックアップと復元を必ず確認してください。

ホストの自動再起動を無効にし、復元ヘルパーの完了と終了を確認してから手動起動してください。復元処理中に別のサーバーを起動する運用は未対応です。1.2.0 の実サーバー試験では、Paper がプラグインの停止要求後にもワールドを書き込むことが観測されており（1.3.0 では再試験していません）、プラグインだけではその競合によるデータ破損を防げません。ホスティング側の設定を確認できない環境で本番安全を保証しません。
