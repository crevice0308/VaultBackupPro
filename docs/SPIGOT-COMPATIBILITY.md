# Spigot compatibility and build provenance

Java 25 is the release baseline.

## 1.3.0 verification scope

No Spigot server, and no other real server, was started for 1.3.0, because running a Minecraft server requires accepting the Minecraft EULA. What exists for 1.3.0 is build-time evidence only:

- The shared code and the Spigot implementation compile against the pinned Spigot API 26.2 and, as a check, against the pinned Spigot API 26.3, with every deprecation treated as an error.
- Every class, method and field that the shipped classes reference is resolved against the Spigot API 26.2 and 26.3 and the Paper API 26.2 and 26.3 (`linkageCheck`).
- `spigotPlatformTest` runs the Spigot implementation against a faked server through the Spigot API interfaces. It starts no Minecraft server.

Results are recorded in [TEST-RESULTS.md](../TEST-RESULTS.md). None of this is a Spigot runtime result. Spigot at run time remains unverified, as it was for 1.2.0 and 1.2.1.

## Earlier verification scope (1.2.0 and 1.2.1)

1.2.1 compiled against the same pinned Spigot 26.2 API and passed its isolated build/test suite. Spigot runtime tests were not run for 1.2.1.

The 1.2.0 build compiled against the pinned Spigot 26.2 API. Fresh Spigot 26.2/26.3 runtime preparation did not complete in that environment: official BuildTools bootstrap encountered network failures and its subsequent retry was cancelled. No 1.2.0 Spigot server lifecycle run is claimed. The locally built server provenance and earlier source checks below describe the previous 1.1.0 validation only. Paper/Purpur 1.2.0 runtime results are reported separately in [RUNTIME-TEST-RESULTS.md](RUNTIME-TEST-RESULTS.md); they were measured with an obfuscated build and do not cover 1.3.0.

## Common plugin API

Since 1.3.0 the plugin is built against two server APIs. The code that runs on both servers and the Spigot implementation (`SpigotPlatform`, `BackupCommands`) compile against the Spigot API. The Paper implementation (`PaperPlatform`, `PaperCommands`) compiles against the Paper API and is loaded by name only where that API exists. On Spigot those two classes are never loaded.

- Repository: `https://hub.spigotmc.org/nexus/content/repositories/snapshots/`
- Compiled against (oldest supported): `org.spigotmc:spigot-api:26.2-R0.1-20260915.151451-13`
- Additionally compiled and link-checked against (current): `org.spigotmc:spigot-api:26.3-R0.1-20261026.080323-10`
- The SHA-256 of each artifact is fixed in `gradle/verification-metadata.xml`. The Paper API coordinates are listed in [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).

The dependencies are compile-only; neither the Spigot API, the Paper API nor a server distribution is bundled inside the plugin. The official Spigot repository keeps only the newest few timestamped builds of the version line that is still being developed. When the pinned 26.3 build has been removed there, the coordinate in build.gradle.kts and `gradle/verification-metadata.xml` have to be updated; see [BUILDING.md](../BUILDING.md). The release JARs are compiled against the 26.2 API and do not depend on that build.

What the Spigot implementation uses:

- Commands: the `backup` command declared in plugin.yml, handled by a `CommandExecutor` and `TabCompleter`. The command's `permission` entry lists the sub-command permissions, so a sender without any of them does not see the command.
- Text: inventory titles, item names/lore and messages use Bukkit's String methods with `ChatColor`.
- Version detection: Bukkit's version string. `api-version: '26.2'` in plugin.yml makes older servers refuse the plugin. Versions newer than 26.3 run with a warning that they are untested; there is no upper bound any more.
- No reflection into server internals (NMS/CraftBukkit) and no Adventure, Brigadier or other Paper-only calls. Paper and Purpur do not run through this implementation: there the Paper implementation is selected (Brigadier commands, Adventure components, `Bukkit.isStopping()`).

Folia and other regionized servers remain unsupported. A server that reports another implementation name gets a warning and runs through whichever of the two APIs is detected.

## Saving and shutdown safety

Up to 1.2.1 an isolated `ServerLifecycle` adapter resolved implementation methods of the server (`saveEverything`, `hasStopped`) by reflection. 1.3.0 removed that adapter. Both operations now go through public API.

Saving:

- Saving runs on the primary server thread and executes the vanilla command `minecraft:save-all flush` through Bukkit's command dispatch, with the console as sender. The command saves players, global data such as level.dat and the scoreboard, and every loaded world, and waits for the data to be written. The namespaced label cannot be shadowed by a commands.yml alias or another plugin's command.
- On Spigot the dispatch result only says that the command exists. Success is therefore established from the per-world save events: a loaded world for which no save was announced fails the save.
- A rejected command, an exception from the command, an unannounced world, or a call off the primary thread aborts backup creation.
- World autosave is switched off for the duration of the backup and switched on again afterwards, only for the worlds this plugin switched off.
- A live backup remains best-effort. Saving cannot make arbitrary third-party plugin writes, external databases, or changing files into a transactionally consistent filesystem snapshot. Files that keep changing while they are copied are stored as last read and counted.

Shutdown:

- The Spigot API has no method that tells whether the server is stopping. When the plugin is disabled while a restore is reserved, the Spigot implementation can therefore only record that the plugin was disabled, not why.
- The restore helper treats that record as a normal stop only if it observes the server JVM exiting within 10 minutes of it. A record older than that is refused, and nothing is changed.
- Consequence: on Spigot a reserved restore can also be applied when only the plugin was disabled, for example by a plugin manager, and the server then terminated abnormally within those 10 minutes. Do not reload or disable the plugin while a restore is reserved on Spigot.
- A stop that takes longer than 10 minutes between the plugin being disabled and the JVM exiting is not applied. Reserve again after starting the server, or use the stopped-server fallback in [OFFLINE-RESTORE.md](OFFLINE-RESTORE.md).
- `/reload` or a plugin reload does not stop the server. The reloaded plugin cancels the reservation.
- Paper and Purpur are not affected: there the plugin asks the server whether it is stopping and writes no stop record for a mere reload or unload.
- The offline helper must still wait for the original JVM to exit and satisfy the restore safety checks before touching the server tree. The record does not prove a particular stop cause or that all later shutdown work succeeded.

These paths are covered by `spigotPlatformTest`, `restoreTest` and `startupTest` against faked servers and disposable JVMs only. The behaviour of a real Spigot server during save and shutdown has not been observed with 1.3.0.

## Historical 1.1.0 official server build provenance

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

These server builds were used for the 1.1.0 validation. No Spigot runtime result exists for 1.2.0, 1.2.1 or 1.3.0.

## Licenses and distribution boundaries

The BuildTools checkouts preserve their original licenses. The generated Spigot-API trees inherit Bukkit's `LICENCE.txt`, GNU GPL version 3, whose SHA-256 is `8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903` in both builds. See the [official Bukkit license source](https://hub.spigotmc.org/stash/projects/SPIGOT/repos/bukkit/browse/LICENCE.txt?at=a176036de14dbd873fcfed8f3111723cdb34e12c) and [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).

The locally built server distributions are disposable test fixtures and are not included in the public source or release packages. Server owners should obtain their own server through [official BuildTools](https://www.spigotmc.org/wiki/buildtools/) and accept the applicable Minecraft EULA before running it.

Official release announcements: [Spigot 26.2](https://www.spigotmc.org/threads/spigot-bungeecord-26-2.721331/) and [Spigot 26.3](https://www.spigotmc.org/threads/spigot-bungeecord-26-3.724398/).
