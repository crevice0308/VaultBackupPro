# Obfuscation removed / 難読化の廃止

Since VaultBackup Pro 1.3.0 the distributed plugin and restore helper JARs are built
directly from the published source. They are not obfuscated, shrunk, optimized or
otherwise post-processed. Releases 1.1.0 to 1.2.1 were name-obfuscated with
ProGuard 7.10.0; that tool and the `proguard-*.pro` rule files were removed from
the build in 1.3.0. This document keeps its file name because other documents and
earlier releases refer to it. The real-server results in RUNTIME-TEST-RESULTS.md and
docs/test-evidence were measured with an obfuscated build and do not cover 1.3.0.

1.3.0 以降、配布するプラグインと復元ヘルパーの JAR は、公開しているソースをそのまま
ビルドしたものです。難読化・縮小・最適化などの後処理はしていません。1.1.0〜1.2.1 は
ProGuard 7.10.0 で名前を難読化していましたが、1.3.0 で ProGuard と proguard-*.pro を
ビルドから削除しました。

## Why obfuscation was removed / 廃止の理由

1. Hangar, PaperMC's official plugin distribution site, does not accept obfuscated
   plugins.
2. The plugin uses only the public Paper and Spigot APIs. It no longer reaches into
   server internals, and nothing in it needs to be hidden. The source has always been
   public under GPL-3.0-only, so obfuscation never kept anything secret.
3. Without obfuscation, users can check more easily that a JAR corresponds to the
   source: the class names in the JAR are the ones in the source tree, and a rebuild
   can be compared with the published hashes.

1. PaperMC の公式プラグイン配布サイト Hangar は、難読化したプラグインを認めていません。
2. プラグインは Paper と Spigot の公開 API だけを使い、サーバー内部には触れません。
   隠す対象がありません。
3. 難読化がなければ、利用者がソースと JAR の対応を確認しやすくなります。

## What the build does now

See [BUILDING.md](../BUILDING.md) for the commands and requirements.

- `build/libs/vaultbackup-restore-1.3.0.jar` contains the compiled `core` and
  `restore` packages and the license files. It is the standalone helper.
- `build/libs/VaultBackupPro-1.3.0.jar` contains the compiled classes of `src/main`
  and `src/paper`, the resources, the license files and the helper above, embedded
  unchanged as `vaultbackup-restore.jar`.
- Archive tasks write no file timestamps and use a fixed file order, so that a JAR
  does not vary with the time of the build or the order in which files were found.
- No mapping files, rule files or intermediate JARs exist any more. `revision.txt`
  inside the plugin JAR states `Artifacts: plain (not obfuscated)`.

## How to check that a JAR corresponds to the source / 確認方法

- `release/SHA256SUMS.txt` records the SHA-256 of the two release JARs.
- CI (`.github/workflows/ci.yml`) rebuilds both JARs from a clean checkout with a
  checksum-pinned JDK and fails unless they have exactly the recorded hashes.
- To repeat that locally, build with the same JDK build as CI (see
  `.github/scripts/setup-java25.sh`) and compare:

```
bash gradlew --no-daemon clean release check
(cd build/libs && sha256sum --check ../../release/SHA256SUMS.txt)
```

A different JDK build or platform can produce different bytes. Matching hashes show
that the JAR is the one this source produces; they are not a statement about how the
plugin behaves on a server. `tools/check_release.py`, run by the `releaseCheck` task,
additionally checks the contents of both JARs: only this project's classes, Java 25
bytecode, the license files, no source or build files, and an embedded helper that is
byte-identical to the standalone one.

release/SHA256SUMS.txt に 2 つの配布 JAR の SHA-256 を記録しています。CI は毎回
クリーンな状態からビルドし直し、JAR のハッシュがこの記録と一致しなければ失敗します。
手元で確認する場合は、CI と同じ JDK でビルドしてハッシュを照合してください。

## Earlier releases

Releases 1.1.0, 1.2.0 and 1.2.1 remain as published: obfuscated JARs with the
matching readable source and ProGuard rules in their source archives. The real-server
results in [RUNTIME-TEST-RESULTS.md](RUNTIME-TEST-RESULTS.md) were measured with the
obfuscated 1.2.0 JAR and do not cover 1.3.0. See
[TEST-RESULTS.md](../TEST-RESULTS.md) for the recorded results and limits of the
current release.
