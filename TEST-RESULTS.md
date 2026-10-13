# VaultBackup Pro 1.1.0 — 検証結果

検証日: 2026-10-13 UTC。隔離した生成データだけを使用。実ユーザーのワールド、個人情報、Discord 送信先は使用していません。生ログは公開しません。無損失保証やセキュリティ認証ではありません。

## 最終難読化成果物

- プラグイン 242,619 bytes、SHA-256: `54b356a6d7a4ccf5a8e2b62b8d556b033c1c76f8cfe7910e19f95c34328e177b`
- 独立ヘルパー 92,617 bytes、SHA-256: `a2f17d2d2b9b08ae7bea40ec8aec4391fb7f38ddf9c711720c6aa645e5e73a4f`
- プラグインに埋め込まれたヘルパーは独立配布ヘルパーと完全一致。
- 内部 plugin.yml のバージョンは 1.1.0。GPL-3.0-only の対応ソースと ProGuard 設定を公開。

## ビルドと回帰試験

Linux x86_64、Eclipse Temurin 25.0.4.1+1-LTS、Gradle 9.8.1、ProGuard 7.10.0、固定 Spigot API `26.2-R0.1-20260915.151451-13`。SHA-256 依存検証を有効にした `release check` が成功。独立監査は最終ヘルパーの同じ166項目を再実行しています。

**634 件の実行 assertion が合格:**

- BackupEngineTest **83**: ZIP・manifest/hash、取消・改ざん・安全パス・symlink・排他・保持、明示同意なしの最終削除拒否と同意後のゼロ件化、予約対象保護。
- DeleteWorkflowTest **36**: actor/対象・確認期限・取消・最後の1件の警告・状態変化時の再確認・想定内拒否の表示。別途 **10 件はソース上の GUI 配線チェック**であり、実クライアント試験ではありません。
- RestoreSafetyTest **166**: 実親/ヘルパー JVM の終了待機、手動停止予約、取消競合、ロック、改ざん拒否、全体復元、receipt、rollback・クラッシュ回復。追加5種類の故障注入で journal I/O/形式エラー、復元済み段階、receipt除去、実際のrollback失敗と非上書きを確認。故障注入の一部はテスト側クラスの直接呼出し、子JVMのCLIケースは最終難読化ヘルパーを使用。
- PluginSettingsTest **330**: 設定型・範囲・保存先・除外・保持・interval・URL、旧設定の日本語既定値、日英キー・引数整合。実 webhook 送信なし。
- ServerLifecycleProbe **19**: main-thread 条件、保存アダプター成功/失敗/未知API拒否、停止経路判定。

通常の Gradle `test` は JUnit 未使用のため意図的にスキップ。上記の独立 Java harness は `check` に接続されています。

## 最終 JAR の難読化後検証

`obfuscationTest` は実際の最終 JAR を Java `-Xverify:all` で読み込み、プラグイン41クラス/19record、ヘルパー27クラス/15record、enum、record accessor・equals/hashCode/toString の往復、Bukkit entrypoint/callback/event注釈、日英メッセージ/config、manifest と通知を確認。38/41 と25/27クラスの実際の名前変更、依存混入なし、埋込ヘルパー一致、引数なしヘルパーの終了コード2も合格。詳細は [artifact-inspection.json](docs/test-evidence/artifact-inspection.json)。

## 対応ソースの再ビルド

別ディレクトリのクリーンビルドでプラグイン・ヘルパーとも上記 SHA-256 と完全一致。空の Gradle キャッシュから公式リポジトリへ解決し、53 component / 93 artifact の SHA-256 検証を通過しました。当初不足した13件の BOM/parent メタデータを正確な値で追加し、検証を無効化せずに再確認しました。

そのフル試験中に、終了中のヘルパーの PID 情報が消える瞬間を test polling が即時失敗として扱う競合が1回発生。製品は安全側で拒否して元データを保持。テストだけを期限付き再試行に修正し、不明状態を成功と扱わずタイムアウト時に元例外を残すようにしました。最終の `clean release check --dependency-verification strict` は634件＋難読化後検証を含めて成功し、JARのハッシュは不変です。ビルドのJDK参照用一時展開も、再抽出前に生成物だけをクリアします。

再現結果は同じJDK25.0.4.1/Gradle9.8.1と依存入力を使ったLinuxでの結果です。全OS/JDKベンダーやパッチ版に対する再現性保証ではありません。

## 最終 JAR のサーバー実行試験

**6構成すべて合格:** Paper 26.2 build133、Paper 26.3 build178、Purpur 26.2 build2633、Purpur 26.3 build2648（experimental）、公式 BuildTools 由来の Spigot 26.2/26.3。実行時間 12:51–12:57 UTC。全て同じ最終難読化プラグインとヘルパーを使用。

各構成で日本語案内、確認なしの削除拒否、2→1→警告確認後0件への削除、空一覧/0件status、再作成、全ZIP CRC/manifest/ファイルSHA-256・内蔵プラグイン一致、復元予約対象の削除/作成拒否、予約後JVM存命とworld進行、手動stop前にstage/swap/receiptなし、実JVM終了後復元、変更前内容復帰と変更後原本rollback保存、自動再起動なし、手動起動時の緑色日本語成功文とreceipt消費を確認しました。

Paper26.2では日本語の確定予約取消・同JVM再予約も合格。別の英語fixtureで確認期限切れ、警告・0件化・再作成、確認中に最後の1件へ変化した場合の再警告/再確認、想定内拒否のstacktraceなし、意図的I/O障害の診断stacktrace保持、英語の予約/取消/古い確認拒否を確認。実1分スケジューラーは **66.90秒**でバックアップを完成し、保持件数1・容量1byteの条件でも正常な1件を維持しました。

全fixtureは通常停止し、試験ポートを閉鎖しました。Sparkの変化する一時出力だけを最初からfixture設定で除外し、実行途中に設定を修正して合格扱いにしていません。旧1.0.0/開発plain候補/試験準備不備の結果は含めていません。詳しくは docs/RUNTIME-TEST-RESULTS.md。

## 静的確認と未実施項目

- 実 Minecraft クライアントでの GUI クリック・見た目・ページ操作・非 OP の権限・再接続は未実行。所有者/InventoryHolder、権限再確認、click/drag 抑止、二段階確認をコードレビュー。コンソール試験は GUI 合格の代用ではありません。
- 実プレイヤーのインベントリー/NBT 復元やプレイ継続は未検証。ファイル hash とサーバー再読込は、より狭い証拠です。
- 本改訂の Windows/CMD/NTFS 動作は未実行。以前のビルドの動作報告は、この公開版の実行試験の代わりになりません。
- 実 Discord webhook、停電・コントローラー故障、全書込み箇所の disk-full、超大規模ワールド、任意の外部 DB、共有/ネットワーク FS、全ホスト監視機構は未検証。
- 自動予約待機は無期限です。通常停止経路マーカーは保存完了の証明ではなく、同じ終了経路を通る障害停止も復元を進め得ます。取消は確認済み表示まで完了扱いにしません。
- マーケットの審査合格、販売認証、法的認証を示すものではありません。

運用前に disposable な復元リハーサル、実クライアント GUI/権限試験と対象 OS での確認を行ってください。詳細は KNOWN-LIMITATIONS.md、docs/OFFLINE-RESTORE.md、docs/SECURITY-REVIEW.md。
