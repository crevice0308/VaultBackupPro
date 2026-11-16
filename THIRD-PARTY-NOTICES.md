本改訂のプロジェクト本体は GPLv3（GPL-3.0-only）です。配布する JAR は難読化していません。第三者コード・ツールの権利は以下のとおり維持します。

# Third-party notices

Prepared 2026-11-16. This notice records dependencies and packaging; it does not resolve every licensing question or grant rights in third-party software. See [license review](docs/LICENSE-REVIEW.md) before public or commercial distribution.

## VaultBackup Pro runtime artifacts

The build uses original project classes and an original standalone restore helper. Its build configuration does not shade third-party runtime libraries into the plugin or helper, and the JARs are not obfuscated or otherwise post-processed. The plugin is compiled against two server APIs as compile-only dependencies: the shared code and the Spigot implementation against the Spigot API, and the two Paper implementation classes (`PaperPlatform`, `PaperCommands`) against the Paper API. At run time the server provides the API; the classes for the other server are never loaded.

No Minecraft server JAR, Paper/Purpur server JAR, Spigot or Paper API classes, Adventure or Brigadier classes, Gradle distribution, or JDK should be included in the distribution.

## Spigot API: compile-only dependency

- Compiled against (oldest supported): `org.spigotmc:spigot-api:26.2-R0.1-20260915.151451-13`
- Additionally compiled and link-checked against (current): `org.spigotmc:spigot-api:26.3-R0.1-20261026.080323-10`
- Official repository: <https://hub.spigotmc.org/nexus/content/repositories/snapshots/>
- GPLv3 license from the official BuildTools checkout is preserved in `third-party/Spigot-API-LICENCE.txt`.
- Official source: <https://hub.spigotmc.org/stash/projects/SPIGOT/repos/bukkit/browse/LICENCE.txt?at=a176036de14dbd873fcfed8f3111723cdb34e12c>

The API is not bundled or shaded. Compile-only packaging does not by itself resolve the legal obligations of runtime linking; this revision uses GPLv3 for its original source and includes corresponding source/build instructions. BuildTools-generated server binaries are not redistributed.

The official Spigot repository keeps only the most recent timestamped builds of the version line that is still being developed. When the pinned 26.3 build has been removed there, building from source requires pinning a newer build and updating `gradle/verification-metadata.xml`; see BUILDING.md.

## Paper API: compile-only dependency

- Compiled against (oldest supported): `io.papermc.paper:paper-api:26.2.build.135-stable`
- Additionally compiled and link-checked against (current): `io.papermc.paper:paper-api:26.3.build.253-stable`
- Official repository: <https://repo.papermc.io/repository/maven-public/>
- Project: <https://github.com/PaperMC/Paper>
- Applicable repository notice: <https://github.com/PaperMC/Paper/blob/ver/26.2/LICENSE.md>
- GPL text: <https://github.com/PaperMC/Paper/blob/ver/26.2/licenses/GPL.md>

Paper's official notice identifies GPLv3 inherited from Spigot, Bukkit, and CraftBukkit. Selected contributors permit their own contributions under MIT; this is not a blanket MIT license for the entire API. Paper and its upstream contributors retain their rights.

The Paper implementation classes refer to types that the Paper API exposes from two further projects. Neither is bundled or shaded; a Paper server provides both:

- Adventure (text components, `net.kyori:adventure-api`), <https://github.com/PaperMC/adventure>, MIT License as declared in its published POM.
- Brigadier (command tree, `com.mojang:brigadier`), published by Mojang at <https://github.com/Mojang/brigadier> under the MIT License.

Compile-only packaging and the absence of shaded API classes do not by themselves settle obligations arising from runtime linking. This revision supplies its original project code under GPL-3.0-only with corresponding source; preserve the applicable API and project notices when redistributing.

## Gradle Wrapper: source package

The source package contains Gradle Wrapper scripts and wrapper tooling generated using Gradle 9.8.1. Gradle's wrapper source is licensed under Apache License 2.0. Original headers must be preserved.

- Project: <https://github.com/gradle/gradle>
- Version license: <https://github.com/gradle/gradle/blob/v9.8.1/LICENSE>
- Wrapper source: <https://github.com/gradle/gradle/blob/v9.8.1/platforms/core-runtime/wrapper-main/src/main/java/org/gradle/wrapper/GradleWrapperMain.java>
- Full unmodified Gradle distribution license supplied at `third-party/Gradle-LICENSE.txt`
- Full unmodified Gradle distribution notice supplied at `third-party/Gradle-NOTICE.txt`

The license/notice files also describe components of the full Gradle distribution. Their preservation does not mean all those components are included in the plugin JAR. Gradle is a build tool, not a plugin runtime dependency.

## Build verification

- Gradle dependency verification metadata (`gradle/verification-metadata.xml`) records the SHA-256 of every resolved build and API dependency. Repository routing restricts `org.spigotmc` to the official Spigot repository and `io.papermc.paper` to the official Paper repository.
- No obfuscator, shrinker or bytecode post-processor is part of the build. Releases up to 1.2.1 were processed with ProGuard; that tool and its configuration were removed in 1.3.0.

## Minecraft and trademarks

Minecraft is a trademark of Mojang/Microsoft. VaultBackup Pro is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft. Use and distribution remain subject to applicable third-party terms, including the [Minecraft EULA](https://www.minecraft.net/en-us/eula) and [Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines). This notice does not grant a marketplace approval, trademark license, or commercial-distribution clearance.
