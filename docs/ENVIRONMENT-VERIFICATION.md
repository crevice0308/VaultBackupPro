# Environment and dependency verification

Checked against publisher documentation, live Maven metadata, and publisher download APIs on **2026-10-13 UTC**. Availability verification is not a runtime compatibility test. Exact builds below were current when checked; dynamic documentation and Javadocs may lag the download APIs.

## Minecraft, Java, and target builds

- Minecraft Java **26.2** was released on June 16, 2026: [Mojang release announcement](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2).
- Minecraft Java **26.3** was released on September 15, 2026: [Mojang release announcement](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3).
- Use **JDK 25** to build and run these targets. Paper documents Java 25 for 26.1 and newer: [runtime requirements](https://docs.papermc.io/paper/getting-started/), [project setup](https://docs.papermc.io/paper/dev/project-setup/).
- Paper 26.2 build **133**, stable; Paper 26.3 build **178**, stable.
- Purpur 26.2 build **2633**, stable; Purpur 26.3 build **2648**, **experimental**. Do not describe the latter as a stable Purpur release. The official API records `metadata.type=experimental`; the [26.3 download page](https://purpurmc.org/download/purpur/26.3) warns about experimental builds.

### Verified API coordinates

Paper repository: `https://repo.papermc.io/repository/maven-public/`

- Baseline: `io.papermc.paper:paper-api:26.2.build.133-stable`
- Additional compatibility compile target: `io.papermc.paper:paper-api:26.3.build.178-stable`

Both coordinates appear in [live Paper Maven metadata](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml); their individual POM URLs returned HTTP 200. Use Gradle `compileOnly` or Maven `provided`. Do not use the old `-R0.1-SNAPSHOT` suffix for Paper 26.x. Paper explains the new version scheme in its [26.1 announcement](https://papermc.io/news/26-1/).

Purpur repository: `https://repo.purpurmc.org/snapshots/`

- `org.purpurmc.purpur:purpur-api:26.2.build.2633-stable`
- `org.purpurmc.purpur:purpur-api:26.3.build.2648-experimental`

Both appear in [live Purpur Maven metadata](https://repo.purpurmc.org/snapshots/org/purpurmc/purpur/purpur-api/maven-metadata.xml). A Paper-based plugin does not need a Purpur-specific dependency unless it uses Purpur-only API.

Set `api-version: '26.2'` in plugin.yml for the minimum supported API. That field rejects older servers but does not enforce an upper bound, so a separate explicit runtime version check is needed for exclusive 26.2/26.3 support. [Paper plugin.yml documentation](https://docs.papermc.io/paper/dev/plugin-yml/).

## Official server downloads and publisher hashes

All four JARs were downloaded and verified against publisher checksums at 2026-10-13 09:20 UTC. Subsequently, all four unmodified servers reached their ready state, saved with `save-all flush`, stopped through the console, and exited with code 0 in isolated loopback-only tests. No plugin was installed during initialization. The [Minecraft EULA](https://www.minecraft.net/en-us/eula) was accepted before these isolated tests; the separate final plugin runtime results are in [RUNTIME-TEST-RESULTS.md](RUNTIME-TEST-RESULTS.md). Downloading a JAR alone is not evidence of successful server startup or plugin compatibility.

### Paper 26.2, build 133

- [Publisher build metadata](https://fill.papermc.io/v3/projects/paper/versions/26.2/builds)
- [Official JAR](https://fill-data.papermc.io/v1/objects/bf1dcf627364f8a631ab15250d240eb5a8e89faaac523b391e1c61367fce9ff1/paper-26.2-133.jar)
- Publisher SHA-256, verified: `bf1dcf627364f8a631ab15250d240eb5a8e89faaac523b391e1c61367fce9ff1`
- Publisher size: 64,525,509 bytes

### Paper 26.3, build 178

- [Publisher build metadata](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds)
- [Official JAR](https://fill-data.papermc.io/v1/objects/5dd0c8fb20a96eb7c98f3c4b1c890e99ef6db56fd689a2cf06975a3471fda757/paper-26.3-178.jar)
- Publisher SHA-256, verified: `5dd0c8fb20a96eb7c98f3c4b1c890e99ef6db56fd689a2cf06975a3471fda757`
- Publisher size: 55,143,992 bytes

### Purpur 26.2, build 2633

- [Publisher build metadata](https://api.purpurmc.org/v2/purpur/26.2/2633)
- [Official JAR](https://api.purpurmc.org/v2/purpur/26.2/2633/download)
- Publisher MD5, verified: `3f3c933f2d518af98571c7461fa42278`
- Local SHA-256: `41f7c0535c1cec47ede48d25c8d9bd7f053d4dc44f87293e5a007314dbcfc32c`
- Downloaded size: 67,413,040 bytes

### Purpur 26.3, build 2648, experimental

- [Publisher build metadata](https://api.purpurmc.org/v2/purpur/26.3/2648)
- [Official JAR](https://api.purpurmc.org/v2/purpur/26.3/2648/download)
- Publisher MD5, verified: `839af7c4f2d55d4184768c34b09210f1`
- Local SHA-256: `7e36f3b09d394307ae9a6a233f38a0564e8429c4c1141f468ac743220fb01a41`
- Downloaded size: 58,014,461 bytes

Purpur download URLs returned HTTP 200 and the expected attachment filenames. MD5 is the publisher's available checksum, not a modern authenticity guarantee. These files were downloaded over HTTPS, validated against publisher hashes, and additionally hashed with local SHA-256. Artifact names match the download filenames listed above.

## Original Paper baseline: storage and save semantics (historical)

The save API discussion in this section records the original Paper-only implementation. This release uses the shared ServerLifecycle adapter described below and in SPIGOT-COMPATIBILITY.md; it does not call Paper-only World.save(boolean).

The [Paper 26.1 storage migration](https://papermc.io/news/26-1/) applies to these targets. Dimension files live below `world/dimensions/<namespace>/<dimension>/`; shared player files are below `world/players/`, and level.dat, datapacks, and shared data belong to the world root. Backing up only the dimension path omits shared state. Do not assume the old `world_nether`, `world_the_end`, or `playerdata` layout.

Use `World#getWorldPath()` for the actual dimension location. The implementation resolves the dimension path through the server storage source: [official CraftWorld source](https://github.com/PaperMC/Paper/blob/ver/26.2/paper-server/src/main/java/org/bukkit/craftbukkit/CraftWorld.java).

**Call `world.save(true)`, not plain `world.save()`, before reading world files for a backup.** The [official API source](https://github.com/PaperMC/Paper/blob/ver/26.2/paper-api/src/main/java/org/bukkit/World.java) implements the no-argument method as `save(false)`. The boolean overload explicitly controls waiting for the chunk writer: [save(boolean) API contract](https://jd.papermc.io/paper/26.2/org/bukkit/World.html#save(boolean)). CraftWorld enforces main-thread saving and forwards `flush` to the world save.

This flush is not a filesystem snapshot and does not guarantee an atomic cross-file or cross-plugin backup while the server continues to change. `setAutoSave(false)` disables periodic saving, not every possible write; its [API note](https://jd.papermc.io/paper/26.2/org/bukkit/World.html#setAutoSave(boolean)) explicitly says shutdown still saves. Save/flush on the main thread, preserve and restore prior autosave state, and document live-backup limitations. Offline restoration and a stopped-server backup remain the safest general recovery workflow. Do not promise zero-pause point-in-time consistency from this API alone.

## 公開版の API・ライセンス

本改訂のコンパイル対象は Spigot API 26.2 の固定 timestamp artifact です。上記 Paper/Purpur 座標は最初の調査記録であり、現在の build.gradle の依存関係ではありません。[Spigot 対応調査](SPIGOT-COMPATIBILITY.md) に公式 BuildTools と API の来歴を記載しています。

Paper 専用の World.save(true) から、実対象で検証する CraftServer → MinecraftServer.saveEverything(true,true,true) に移行しました。未知の反射シグネチャや失敗は安全側に拒否します。停止判定は別に hasStopped を使用しますが、保存完了の証明としては使いません。

本公開版は GPL-3.0-only で無料配布します。1.1.0 は ProGuard 7.10.0 で難読化した JAR が標準です。Spigot/Paper/Minecraft のクラスやサーバー JAR をプラグインへ同梱しません。[ライセンス記録](LICENSE-REVIEW.md) と THIRD-PARTY-NOTICES.md を参照してください。公開ファイル名とソースは [配布情報](../PUBLICATION-NOTES.md) を参照してください。

## Initialization results

On 2026-10-13 all four official server artifacts initialized with JDK 25.0.4.1 and a 768 MiB maximum heap, sequentially. Each bound only to 127.0.0.1 on its distinct port (25581 through 25584), with offline authentication, maximum two players, view/simulation distance 2, and flat test worlds. Each reached `Done`, handled a save/flush and clean console stop, and exited 0. The expected new `world/dimensions/minecraft/` and `world/players/` layout was observed.

Retained logs include Minecraft authentication/discovery DNS errors in this isolated offline test environment and a flat-generator defaults warning; they did not prevent initialization. This is baseline server verification, not plugin acceptance testing, player-login testing, or proof of backup/restore correctness.

## プラグインの最終検証

旧版の 4 サーバー難読化 JAR 検証は本改訂の証拠に置き換えません。本公開版の正確な最終 JAR、6 サーバー結果、追加取消・異常終了検証は [実行結果](RUNTIME-TEST-RESULTS.md) と TEST-RESULTS.md を参照してください。

Purpur 26.3 は上流で experimental のままです。GUI の実クライアント操作、Windows 改訂版、実 webhook、実運用サイズ、突然の電源断は未検証です。
