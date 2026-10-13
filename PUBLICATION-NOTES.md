# VaultBackup Pro 1.0.0 — Publication notes

## Source and public filenames

The corresponding source, build instructions, tests, and license notices are provided in this repository and the source archive. Public artifact names are:

- `VaultBackupPro-1.0.0.jar`: the plugin, including its independent restore helper
- `VaultBackupPro-1.0.0-plugin.zip`: the plugin, helper, operating guides, and notices
- `VaultBackupPro-1.0.0-source.zip`: corresponding project source, Gradle wrapper/build configuration, test harnesses, and documentation

Install only `VaultBackupPro-1.0.0.jar` in the server's plugins directory. Product and plugin version remain **1.0.0**. Exact artifact identity is established by the SHA-256 values in [TEST-RESULTS.md](TEST-RESULTS.md), rather than the version number alone.

## Preserved build-time notices

The tested JAR is published byte-for-byte without changing its classes, resources, or embedded helper. Its build-time revision label is `ManualStop-JA`. That historical label remains inside `src/main/resources/revision.txt`, `NOTICE.txt`, and `THIRD-PARTY-NOTICES.md`; it is not an extra suffix for the public filenames above.

The original embedded notice describes the prepublication build and names an earlier `VaultBackupPro-1.0.0-ManualStop-JA-source.zip` package. The corresponding public source archive is named `VaultBackupPro-1.0.0-source.zip`, and this repository also supplies that source. The statement that no public repository was created “by this build” records the build-time state; it does not describe the later publication operation.

`LICENSE.txt`, `NOTICE.txt`, and `THIRD-PARTY-NOTICES.md` are preserved unchanged because the Gradle build embeds them into both JARs. Source code, production resources, and build configuration are likewise preserved. Public-facing documentation, portable test-harness path setup, and packaging can be updated without rebuilding or modifying the tested release JAR.

## Public test evidence and limits

The public repository contains the measured [test summary](TEST-RESULTS.md), [runtime results](docs/RUNTIME-TEST-RESULTS.md), and a [sanitized artifact summary](docs/test-evidence/artifact-inspection.json). It excludes raw logs, generated worlds, server distributions, backup archives, restore journals, temporary paths, job identifiers, and local build caches. Run the supplied harnesses only against new disposable fixtures after completing their documented prerequisites.

The exact final JAR passed the documented six-server Linux matrix. Real Minecraft client GUI/permission interaction, this release's Windows execution, real Discord webhook delivery, and hardware power loss remain untested. Purpur 26.3 is experimental upstream. Test results are not a certification, marketplace approval, or guarantee against data loss.

## ライセンスと配布情報

本公開版は無料の GPL-3.0-only ソフトウェアです。公開ファイル名には内部の改訂ラベルを付けず、上記の名前を使用します。検証済み JAR とビルド入力は変更せず、内蔵 NOTICE の公開前の記述・旧ソース ZIP 名は、この文書で公開用の名前に対応付けています。

生ログ・一時パス・ジョブ識別情報・テスト用ワールド・バックアップは配布しません。対応ソースとビルド手順、検証要約、ライセンス通知を含みます。Windows と実クライアント GUI は未検証、Purpur 26.3 は experimental です。
