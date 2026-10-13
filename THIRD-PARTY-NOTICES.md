本改訂のプロジェクト本体は GPLv3（GPL-3.0-only）、標準 JAR は ProGuard で難読化しています。第三者コード・ツールの権利は以下のとおり維持します。

# Third-party notices

Prepared 2026-10-13. This notice records dependencies and packaging; it does not resolve every licensing question or grant rights in third-party software. See [license review](docs/LICENSE-REVIEW.md) before public or commercial distribution.

## VaultBackup Pro runtime artifacts

The build uses original project classes and an original standalone restore helper. Its build configuration does not shade third-party runtime libraries into the plugin or helper. The plugin compiles against Spigot API as a compile-only dependency, and the Minecraft/Paper/Purpur/Spigot server supplies the runtime API. Java is a separately installed runtime. Release inspection checks that plugin/helper classes remain under the project namespace and no third-party runtime classes are shaded. The helper matched its separate build byte-for-byte; see [artifact inspection](docs/test-evidence/artifact-inspection.json).

No Minecraft server JAR, Paper/Purpur server JAR, Paper API classes, Gradle distribution, ProGuard implementation, or JDK should be included in the distribution.

## Spigot API: current compile-only dependency

- Pinned coordinate: `org.spigotmc:spigot-api:26.2-R0.1-20260915.151451-13`
- Official repository: <https://hub.spigotmc.org/nexus/content/repositories/snapshots/>
- GPLv3 license from the official BuildTools checkout is preserved in `third-party/Spigot-API-LICENCE.txt`.
- Official source: <https://hub.spigotmc.org/stash/projects/SPIGOT/repos/bukkit/browse/LICENCE.txt?at=a176036de14dbd873fcfed8f3111723cdb34e12c>

The API is not bundled or shaded. Compile-only packaging does not by itself resolve the legal obligations of runtime linking; this revision uses GPLv3 for its original source and includes corresponding source/build instructions. BuildTools-generated server binaries remain local test inputs and are not included in either deliverable.

## Paper API: original-build reference / supported server dependency

- Original-build compile coordinate (superseded by Spigot API in this revision): `io.papermc.paper:paper-api:26.2.build.133-stable`
- Project: <https://github.com/PaperMC/Paper>
- Applicable repository notice: <https://github.com/PaperMC/Paper/blob/ver/26.2/LICENSE.md>
- GPL text: <https://github.com/PaperMC/Paper/blob/ver/26.2/licenses/GPL.md>

Paper's official notice identifies GPLv3 inherited from Spigot, Bukkit, and CraftBukkit. Selected contributors permit their own contributions under MIT; this is not a blanket MIT license for the entire API. Paper and its upstream contributors retain their rights. The project does not claim ownership of those APIs.

Compile-only packaging and the absence of shaded API classes do not by themselves settle obligations arising from runtime linking. This revision supplies its original project code under GPL-3.0-only with corresponding source; preserve the applicable API and project notices.

## Gradle Wrapper: source package

The source package contains Gradle Wrapper scripts and wrapper tooling generated using Gradle 9.8.1. Gradle's wrapper source is licensed under Apache License 2.0. Original headers must be preserved.

- Project: <https://github.com/gradle/gradle>
- Version license: <https://github.com/gradle/gradle/blob/v9.8.1/LICENSE>
- Wrapper source: <https://github.com/gradle/gradle/blob/v9.8.1/platforms/core-runtime/wrapper-main/src/main/java/org/gradle/wrapper/GradleWrapperMain.java>
- Full unmodified Gradle distribution license supplied at `third-party/Gradle-LICENSE.txt`
- Full unmodified Gradle distribution notice supplied at `third-party/Gradle-NOTICE.txt`

The license/notice files also describe components of the full Gradle distribution. Their preservation does not mean all those components are included in the plugin JAR. Gradle is a build tool, not a plugin runtime dependency.

## ProGuard: build-only tool

ProGuard 7.10.0 (`com.guardsquare:proguard-base`) is fetched from Maven Central only for the isolated build-tool configuration. ProGuard is GPLv2 licensed; its official license states that processing an application does not impose ProGuard's license on that application. No ProGuard implementation is embedded in either runtime JAR. Preserve the project GPLv3 license and corresponding source/build settings.

- Official license: <https://github.com/Guardsquare/proguard/blob/v7.10.0/LICENSE>
- Release notes: <https://www.guardsquare.com/manual/releasenotes>
- Build-only JSON-java dependency is constrained to `org.json:json:20260719` for GHSA-ghc9-756g-vrcv. It is not a plugin runtime dependency.
- Gradle dependency verification metadata records the resolved build and API dependencies. Repository routing restricts `org.spigotmc` to its official repository.

## Minecraft and trademarks

Minecraft is a trademark of Mojang/Microsoft. VaultBackup Pro is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft. Use and distribution remain subject to applicable third-party terms, including the [Minecraft EULA](https://www.minecraft.net/en-us/eula) and [Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines). This notice does not grant a marketplace approval, trademark license, or commercial-distribution clearance.
