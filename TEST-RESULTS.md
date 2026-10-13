# VaultBackup Pro 1.0.0 — 検証結果

検証日: 2026-10-13 UTC。試験はすべて隔離した生成データで実施しました。実ユーザーのサーバー・ワールド・認証情報・Discord 送信先は使用していません。これは無損失保証やセキュリティ認証ではありません。

## 最終成果物

- 通常の非難読化プラグイン、274,018 bytes、SHA-256: `1abf09736a5ea4bf1b363259a744c9ff050b7d73548db353e7066f357855c7ab`
- 独立ヘルパー、105,276 bytes、SHA-256: `93ff6574b7af2b8bf3f72a48b4e1a0f1ab9628bbcd78a01e194f1eb416a520a9`
- 埋込ヘルパーと別配布ヘルパーのバイト一致を確認。プラグイン 37・ヘルパー 25 クラスはすべてプロジェクト名前空間。第三者 API クラスなし。両 JAR の CRC と LICENSE/NOTICE 同梱を検査。
- 内部バージョンは 1.0.0。GPL-3.0-only、ソース同梱。JAR 内の改訂識別と公開ファイル名の関係は PUBLICATION-NOTES.md を参照。

## ビルドと実行した fixture

Linux x86_64、Eclipse Temurin 25.0.4.1+1-LTS、Gradle 9.1.0、固定 Spigot API `26.2-R0.1-20260915.151451-13`。Purpur の複数行コンソール表示で後続行の色が失われる問題を修正し、各行へ個別に色を付ける最終コードで `clean release check` を実行し成功。最終ハッシュは上記です。

**520 assertions 合格:**

- BackupEngineTest **62**: 全体 ZIP・manifest/hash、空ディレクトリ・バイナリ、保持・最後の正常世代・予約対象保護、書込み変化・取消時の不完全物、パス traversal/大小文字/Unicode/ancestor 衝突、symlink、容量・数・圧縮上限、壊れたアーカイブ、排他。
- RestoreSafetyTest **116**: 実親/ヘルパー JVM を使用。親存命時に変更しない、手動停止待機、取消と再予約・取消競合、取消済みジョブの再実行拒否、状態不明時の拒否、停止マーカーとプロセス同一性、ロック、全体置換、原本退避、改ざん拒否、認証 receipt の一度消費、適用後失敗の rollback、クラッシュジャーナル回復。
- PluginSettingsTest **323**: 設定型・範囲・保存先・除外・保持・interval・URL、旧設定の日本語既定値、ja_JP/en_US の全 252 キーとパラメーター整合。実 webhook 送信なし。
- ServerLifecycleProbe **19**: main-thread 条件、検証対象 saveEverything(true,true,true) の成功/失敗/未知 API 拒否、停止経路判定。

Linux ラッパーは shell 構文と空白を含むパスでの通常・回復・入力拒否・引数不足の 4 シナリオを生成した Java 代役で検査しました（引数転送試験であり、それ自体は復元実行試験ではありません）。

通常の Gradle `test` は JUnit 未使用のため意図的にスキップします。上記は `check` に接続した独立 Java assertion harness です。構成検査の公開要約は [artifact-inspection.json](docs/test-evidence/artifact-inspection.json)。生の実行ログや一時環境の識別情報は配布しません。

## 対応ソースの再ビルド

配布用ソース ZIP を別の作業ディレクトリへ展開し、JDK 25 と Gradle 9.1.0 で `clean release` を実行しました。生成したプラグインとヘルパーの SHA-256 は両方とも上記最終成果物と完全一致しました。同一環境での再現結果であり、全 OS/JDK の再現性保証ではありません。公開用にはビルド入力を維持し、配布文書・補助テストツール・パッケージ整理を行っています。

## 最終 JAR のサーバー実行試験

**6 対象すべて合格:** Paper 26.2 build 133、Paper 26.3 build 178、Purpur 26.2 build 2633、Purpur 26.3 build 2648（experimental）、公式 BuildTools 由来の Spigot 26.2 / 26.3。

各対象で最終 JAR の読込み、日本語コマンド、全 ZIP manifest の独立 SHA-256/サイズ照合、予約後も JVM 存命・ワールド時間進行、予約アーカイブ保護、手動 stop、実 JVM 終了後の復元、変更前 marker の復元・変更後原本の rollback 保存、自動再起動しないこと、手動起動時の実際の緑色日本語成功文・receipt 消費を確認しました。Spigot の非対話コンソールでは標準が色を除去するため、公式実装が提供する `-Djansi.passthrough=true` を試験時に使用しました。

Paper 26.2 では確定済み取消を 2 回、同 JVM 再予約、取消後 stop でも復元しないこと、古い取消済みヘルパーの手動実行拒否を確認。英語へ切り替えた予約・状態・取消も合格しました。実 1 分スケジューラーは **66.99 秒**でバックアップを完成し、保持試験では生成アーカイブが正確に 2 件残りました。

別の隔離 fixture で予約後に SIGKILL を行い、通常停止マーカーなしでは staging/rollback/receipt を作らず、変更データを保持し、次回起動で赤い日本語失敗通知を出すことを確認しました。これはマーカーなし強制終了の検証であり、あらゆる障害停止を拒否する保証ではありません。

追加試験中、Paper の Spark 一時 JFR ファイルが変わった回は `Source changed during backup` で正しく拒否されました。履歴を保存し、その生成 fixture の `plugins/spark/tmp/**` だけを明示除外して英語/スケジューラー/保持試験を再実行しました。製品の既定除外・安全検証は変更していません。

詳しい記録は `docs/RUNTIME-TEST-RESULTS.md`。以前のビルドの試験結果は本公開版の合格数へ流用していません。

## 静的確認と未実施項目

- 実 Minecraft クライアントでの GUI クリック・見た目・ページ操作・非 OP の権限・再接続は未実行。所有者/InventoryHolder、権限再確認、click/drag 抑止、二段階確認をコードレビュー。コンソール試験は GUI 合格の代用ではありません。
- 実プレイヤーのインベントリー/NBT 復元やプレイ継続は未検証。ファイル hash とサーバー再読込は、より狭い証拠です。
- 本改訂の Windows/CMD/NTFS 動作は未実行。以前のビルドの動作報告は、この公開版の実行試験の代わりになりません。
- 実 Discord webhook、停電・コントローラー故障、全書込み箇所の disk-full、超大規模ワールド、任意の外部 DB、共有/ネットワーク FS、全ホスト監視機構は未検証。
- 自動予約待機は無期限です。通常停止経路マーカーは保存完了の証明ではなく、同じ終了経路を通る障害停止も復元を進め得ます。取消は確認済み表示まで完了扱いにしません。
- マーケットの審査合格、販売認証、法的認証を示すものではありません。

運用前に disposable な復元リハーサル、実クライアント GUI/権限試験と対象 OS での確認を行ってください。詳細は KNOWN-LIMITATIONS.md、docs/OFFLINE-RESTORE.md、docs/SECURITY-REVIEW.md。
