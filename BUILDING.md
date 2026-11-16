# ソースからのビルド / Build from source

Java 25 の JDK、Gradle 9.8.1（同梱 wrapper。ビルド定義は Kotlin DSL の build.gradle.kts / settings.gradle.kts）、Python 3 を使います。Gradle は JDK 25 で実行する必要があり、それ以外ではビルドがエラーで止まります。JAVA_HOME は JRE ではなく JDK 25 に設定してください。Python 3 は `check` に必要です（`releaseCheck` タスクが tools/check_release.py を `python3` で実行します）。Maven Central、Spigot の公式リポジトリ、PaperMC の公式リポジトリ、および wrapper が Gradle 本体を取得する配布サイトへの接続が必要です。

Linux: `bash gradlew --no-daemon clean release check`

Windows: `gradlew.bat --no-daemon clean release check`（`python3` という名前で Python 3 を実行できる必要があります。Windows でのビルドは確認していません）

## 生成物
- build/libs/VaultBackupPro-1.3.0.jar — プラグイン。独立ヘルパーを `vaultbackup-restore.jar` として内蔵
- build/libs/vaultbackup-restore-1.3.0.jar — 独立復元ヘルパー。core と restore のパッケージだけを含み、サーバー API に依存しません

どちらもソースをそのままコンパイルして JAR にしたもので、難読化・縮小・最適化などの後処理はしていません。1.2.1 まで使っていた ProGuard と proguard-*.pro は 1.3.0 で削除しました（docs/OBFUSCATION.md）。サーバー API は compileOnly で、サーバー API や Minecraft/Paper/Spigot サーバー本体を JAR に同梱しません。JAR 内の順序・タイムスタンプは再現しやすい設定です。実際の配布物は release/SHA256SUMS.txt の SHA-256 と照合してください。

## 2 つのサーバー API
プラグインは 2 つの API に対してビルドします。固定している座標は build.gradle.kts と THIRD-PARTY-NOTICES.md に記載しています。

- src/main: 両方のサーバーで動く共通部分と、Spigot 用の実装（SpigotPlatform、BackupCommands）。Spigot API に対してコンパイル
- src/paper: Paper 用の実装（PaperPlatform、PaperCommands）。Paper API に対してコンパイル

配布 JAR は、対応する最も古いバージョン（26.2）の API に対してコンパイルします。同じソースを現行（26.3）の API に対してもコンパイルし、どちらの場合も非推奨・削除予定の API の使用はエラーになります。起動時にサーバーを判別し、Paper API がある場合は Paper 用の実装、それ以外では Spigot 用の実装を使います。

## 検証タスク
- coreTest: 使い捨て ZIP・バックアップ・改ざん・保持制限のほか、稼働中のファイル変化、拒否するパスの一括報告、空き容量、細工されたアーカイブの検証
- startupTest: 起動時の古い予約・実ロック競合・取消・復旧状態、停止の判定、元のサーバールートの識別の検証
- restoreTest: 実際の子 JVM とビルドしたヘルパーを使う予約・取消競合・停止・復元・保護の検証
- settingsTest: 設定、日本語/英語、メッセージ、バージョン判定、自動バックアップの予定、plugin.yml との整合の検証
- deleteWorkflowTest: 直接削除・直接予約・権限再確認・取消・連打・予約保護・実行元の制限の検証。Bukkit イベントの模擬で、実クライアントではありません
- spigotPlatformTest: 模擬したサーバーに対する Spigot 用実装（保存コマンドの実行と確認、ChatColor の文字列、plugin.yml のコマンド）の検証
- paperPlatformTest: 模擬したサーバーに対する Paper 用実装（コンポーネント、保存の確認、停止の判定、Brigadier のコマンドツリー）の検証
- compileAgainstCurrentSpigot / compileAgainstCurrentPaper: 同じソースを Spigot API 26.3・Paper API 26.3 に対してコンパイル
- paperDeprecationCheck: Paper 上で実行されるクラスすべてを Paper API 26.3 に対してコンパイルし、Paper が非推奨にした API を使っていないことを検査
- linkageCheck（linkageCheckSpigotMinimum / linkageCheckSpigotCurrent / linkageCheckPaperMinimum / linkageCheckPaperCurrent）: 配布 JAR のクラスが参照するクラス・メソッド・フィールドを 4 つの API に対して解決。共通クラスは 4 つすべて、Paper 用クラスは Paper の 2 つが対象
- releaseCheck: tools/check_release.py で 2 つの配布 JAR を検査（内容物、Java 25 のバイトコード、内蔵ヘルパーと単独ヘルパーのバイト一致、JVM による全クラスの検証と両 API でのロード、起動点）

check は上記の独立 Java assertion harness と検査を実行します。JUnit の test タスクは意図的に無効です。模擬したサーバーを使う試験は Minecraft サーバーを起動しません。検証回数は TEST-RESULTS.md に実測値を記載します。

実サーバー検証には、別途公式サーバー配布物/Spigot BuildTools と Minecraft EULA 同意が必要です。1.3.0 では実サーバー検証を実施していません。tools/runtime-tests のハーネスは過去の版のために書かれたもので、1.3.0 向けには更新していません（tools/runtime-tests/README.md）。配布 ZIP にサーバー本体、テスト用ワールド、実バックアップ、依存キャッシュは含めません。

## 配布準備
`python3 tools/package_release.py [出力先ディレクトリ]`（省略時は dist/）で、ソースとビルド手順を含む完全パッケージ（VaultBackupPro-1.3.0-source.zip）、実行用パッケージ（VaultBackupPro-1.3.0-plugin.zip）、単独 JAR、それらの SHA256SUMS.txt を出力します。先に `release` で JAR をビルドしておく必要があります。JAR の SHA-256 が release/SHA256SUMS.txt の記録と一致しない場合や、release/PUBLIC-FILES.txt が git の追跡ファイルと一致しない場合は中止します。

どちらの ZIP でも、ヘルパーは helpers/vaultbackup-restore.jar（バージョンを含まない名前）として入ります。helpers/restore.sh・restore.cmd は既定で隣のこのファイルを使い、環境変数 VAULTBACKUP_HELPER_JAR で別のパスを指定できます。

GPLv3 と NOTICE.txt の対応ソース提供条件を確認してください。パッケージ生成自体は公開・アップロード・販売を行いません。

## ビルド供給網と依存関係の検証 / Build security

Wrapper は公式 Gradle 9.8.1 の SHA-256 を固定しています。CI は gradle-wrapper.jar 自体の SHA-256 も照合します。依存関係の SHA-256 は gradle/verification-metadata.xml で検証します。`org.spigotmc` は Spigot の公式リポジトリからのみ、`io.papermc.paper`・`com.mojang` と Paper API が必要とする一部の依存は PaperMC の公式リポジトリからのみ、それ以外は Maven Central から解決します。検証失敗を無効化して続けないでください。ビルドに難読化・縮小・バイトコード後処理のツールは含まれません。

CI（.github/workflows/ci.yml）は、チェックサムを固定した JDK で `clean release check` を実行し、できあがった 2 つの JAR が release/SHA256SUMS.txt のハッシュと一致しなければ失敗します。

### Spigot API 26.3 の固定ビルドを取得できなくなった場合
Spigot の公式リポジトリは、開発が続いている系列（現在は 26.3）のタイムスタンプ付きビルドを、最新の数件しか保持しません。build.gradle.kts の `spigotApiCurrent` に固定しているビルド（`org.spigotmc:spigot-api:26.3-R0.1-20261026.080323-10`）が公式リポジトリから削除されると、26.3 の API を使う検査（`check`）は依存関係を取得できなくなります。配布 JAR は 26.2 の API（`spigotApiMinimum`）に対してコンパイルするため、このビルドには依存しません。26.2 の系列は上流で開発が終わっており、固定したビルドは残る想定です。

削除された場合は、次のとおり更新してください。

1. build.gradle.kts の `spigotApiCurrent` を、公式リポジトリにある 26.3 系列の新しいタイムスタンプ付きビルドの座標に変更します。
2. gradle/verification-metadata.xml を更新します。Gradle の `--write-verification-metadata sha256` オプションで新しい成果物の SHA-256 を記録し、差分を確認します。増えるのは通常 `org.spigotmc:spigot-api` の新しいビルドの項目です。それ以外の依存が変わっている場合は、内容を確かめてから採用してください。この作業は、依存キャッシュのない状態（空の Gradle ユーザーホーム）で行ってください。キャッシュが残っていると、クリーンな環境でだけ取得される親 POM などの項目が記録されないことがあります。
3. THIRD-PARTY-NOTICES.md など、座標を記載している文書を合わせます。
4. `bash gradlew --no-daemon clean release check` をやり直します。新しい API で非推奨になったものを使っていると、コンパイルの検査がエラーになります。その場合はソース側の対応が必要です。

検証を無効化したり、公式以外のリポジトリから取得したりしないでください。
