# Spigot compatibility and build provenance

Verified on 2026-10-13. Java 25 is the release baseline.

## Common plugin API

The plugin compiles against the lowest supported common API:

- Repository: `https://hub.spigotmc.org/nexus/content/repositories/snapshots/`
- Dependency: `org.spigotmc:spigot-api:26.2-R0.1-20260915.151451-13`
- API JAR SHA-256: `8c7bd2071d8fb85afa1331b3aa240a8507a73b5f661fa7084edcd06a2d9725f8`
- For comparison, the verified 26.3 API is `org.spigotmc:spigot-api:26.3-R0.1-20261004.024323-9`, SHA-256 `41f378819cdcac9cdb773d5573e6cac5f2ad4db93bdd14a4ba9f4575ac0cd4d5`.

Gradle resolved the timestamp-pinned 26.2 coordinate and its transitive dependencies successfully. The dependency is compile-only; neither the Spigot API nor a server distribution is bundled inside the plugin.

Inventory titles, item names/lore, and messages use Bukkit's String/ChatColor APIs. Version detection uses Bukkit's version string. Adventure-only calls and Paper's `World.save(boolean)` are not required. Paper and Purpur remain supported through their Spigot-compatible APIs. Folia and untested versions remain unsupported.

## Saving and shutdown safety

The isolated `ServerLifecycle` adapter resolves the public implementation methods used by the supported 26.2/26.3 server builds. Startup checks that these methods exist with the expected signatures. An unknown or changed implementation fails closed instead of silently falling back to an unverified save.

- Saving runs on the primary server thread and calls `saveEverything(true, true, true)`. This is the operation underlying vanilla `save-all flush`: include player data, request a synchronous flush, and force saving while world autosave is temporarily disabled.
- A false return, unavailable method, invalid signature, or invocation exception aborts backup creation. A successful Bukkit command-dispatch result alone is not accepted as proof of saving.
- `hasStopped()` identifies entry into the server's shutdown path. It is set before plugins are disabled; ordinary plugin disable/reload does not set it.
- Shutdown-path entry does not prove a particular stop cause or that all later shutdown work succeeded. The offline helper must still wait for the original JVM to exit and satisfy the restore safety checks before touching the server tree.
- A live backup remains best-effort. This adapter cannot make arbitrary third-party plugin writes, external databases, or changing files into a transactionally consistent filesystem snapshot.

The public method signatures and vanilla save-command call were checked against both Spigot source builds and the four Paper/Purpur test distributions. A focused adapter probe passed 19 assertions covering save arguments, non-main-thread rejection, false/error handling, missing/malformed implementations, stop-state checks, and Japanese/English diagnostics. Full end-to-end runtime results are recorded in [TEST-RESULTS.md](../TEST-RESULTS.md).

## Official server build provenance

Both test distributions were produced locally by official Spigot BuildTools. No third-party server-download site was used.

BuildTools:

- Version: `git-BuildTools-5fc1010-201` (#201)
- Download: [official BuildTools #201 artifact](https://hub.spigotmc.org/jenkins/job/BuildTools/201/artifact/target/BuildTools.jar)
- SHA-256: `81ed06f5ce5fb705b558bf9b3cc2c5c53cdd9742406c5338c452faa13a1e1c24`
- Commands: `java -jar BuildTools.jar --nogui --rev 26.2` and `java -jar BuildTools.jar --nogui --rev 26.3`

### Spigot 26.2

- Manifest implementation version: `4648-Spigot-8db49a2-96ae8cd`
- BuildData: `41bd187bd86ec1444d2dc9e0188e53559cf870b3`
- Bukkit: `a176036de14dbd873fcfed8f3111723cdb34e12c`
- CraftBukkit: `96ae8cd2f4f8bfcee57193455d0714f2bd4fcd1f`
- Spigot: `8db49a2b72ef138a615856934957da2978f72f08`
- Server JAR SHA-256: `675c766c5e4d13184a10316fbc89224de975b0c83d404a01462618a0742d406a`
- Hash exactly matches [official 26.2 build metadata](https://hub.spigotmc.org/versions/26.2.json).

### Spigot 26.3

- Manifest implementation version: `4663-Spigot-bed90fc-c4db4c3`
- BuildData: `39e62795554dcf6b62e922bc061705b3dc228f88`
- Bukkit: `29025a7ed5789807e11158a2263316b13ed50c3b`
- CraftBukkit: `c4db4c38d647c099a7c4736143043fa1440f648f`
- Spigot: `bed90fca391bc35f0dbbcdc7ce5d7bb28e533681`
- Server JAR SHA-256: `59f4a101e03012e5182e43c77f06679cdcb15d779c7c4ab6899200c567d79537`
- Hash exactly matches [official 26.3 build metadata](https://hub.spigotmc.org/versions/26.3.json).

Both official build metadata files permit Java class-file versions 69–70; the actual builds and release tests use Java 25 (class-file version 69).

## Licenses and distribution boundaries

The BuildTools checkouts preserve their original licenses. The generated Spigot-API trees inherit Bukkit's `LICENCE.txt`, GNU GPL version 3, whose SHA-256 is `8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903` in both builds. See the [official Bukkit license source](https://hub.spigotmc.org/stash/projects/SPIGOT/repos/bukkit/browse/LICENCE.txt?at=a176036de14dbd873fcfed8f3111723cdb34e12c) and [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).

The locally built server distributions are disposable test fixtures and are not included in the public source or release packages. Server owners should obtain their own server through [official BuildTools](https://www.spigotmc.org/wiki/buildtools/) and accept the applicable Minecraft EULA before running it.

Official release announcements: [Spigot 26.2](https://www.spigotmc.org/threads/spigot-bungeecord-26-2.721331/) and [Spigot 26.3](https://www.spigotmc.org/threads/spigot-bungeecord-26-3.724398/).
