# ソースからのビルド / Build from source

Java 25 の JDK と Gradle 9.1.0（同梱 wrapper）を使います。JAVA_HOME は JRE ではなく JDK に設定してください。Maven Central と公式 Spigot Maven リポジトリへの接続が必要です。

Linux: `./gradlew clean release check`

Windows: `gradlew.bat clean release check`

## 生成物
- build/libs/VaultBackupPro-1.0.0.jar — 難読化なしのプラグイン。独立ヘルパーを内蔵
- build/libs/vaultbackup-restore-1.0.0.jar — 難読化なしの独立復元ヘルパー

この改訂では ProGuard を使用しません。API は compileOnly で、サーバー API や Minecraft/Spigot サーバー本体を JAR に同梱しません。JAR 内の順序・タイムスタンプは再現しやすい設定です。実際の配布物はテスト結果の SHA-256 と照合してください。

## 検証タスク
- coreTest: 使い捨て ZIP・バックアップ・改ざん・保持制限の検証
- restoreTest: 実際の子 JVM と配布ヘルパーを使う予約・取消競合・停止・復元・保護の検証
- settingsTest: 設定、日本語/英語、メッセージ、バージョン判定の検証
- lifecycleTest: 保存/停止アダプターの安全側失敗と呼出し条件の検証

check は上記の独立 Java assertion harness を実行します。JUnit の test タスクは意図的に無効です。検証回数は TEST-RESULTS.md に実測値を記載します。

実サーバー検証には、別途公式サーバー配布物/Spigot BuildTools と Minecraft EULA 同意が必要です。配布 ZIP にサーバー本体、テスト用ワールド、実バックアップ、依存キャッシュは含めません。

## 配布準備
`python3 tools/package_release.py <output-directory>` で、ソースとビルド手順を含む完全パッケージ、実行用パッケージ、単独 JAR を整理します。GPLv3 と NOTICE.txt の対応ソース提供条件を確認してください。パッケージ生成自体は公開・アップロード・販売を行いません。
