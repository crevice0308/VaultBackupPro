# Distribution obfuscation / 配布 JAR の難読化

VaultBackup Pro 1.1.0 is free software under GPL-3.0-only. The complete, readable
corresponding source, build scripts and these ProGuard rules belong in the public
source release. Both executable distribution JARs use conservative name
obfuscation. This is a packaging choice, not DRM, encryption, license enforcement,
a secrecy guarantee, or a restriction on the freedoms granted by the license.
There is no activation server or runtime ProGuard dependency.

配布用のプラグインと復元ヘルパーは名前を難読化します。読みやすい完全なソースと
ビルド設定は GPLv3 の対応ソースとして公開します。難読化は暗号化や DRM ではなく、
秘密情報を保護する機能でもありません。

## Build design

Use the JDK 25 toolchain and the pinned Gradle wrapper described in
[BUILDING.md](../BUILDING.md). ProGuard is pinned to the official
`com.guardsquare:proguard-base:7.10.0` build tool. Its dependencies are build-time
only. Check `build.gradle` for the complete pinned build dependency configuration.

1. Compile the readable source with Java 25 (`--release 25`).
2. Build a plain, internal helper input from `core` and `restore` classes only.
3. Run `proguard-restore.pro` and normalize its archive into the final standalone
   `build/libs/vaultbackup-restore-1.1.0.jar`.
4. Build a plain, internal plugin input with resources, without an embedded helper.
5. Run `proguard-plugin.pro`, then assemble
   `build/libs/VaultBackupPro-1.1.0.jar` with the exact final standalone helper bytes
   stored at `vaultbackup-restore.jar`.

Processing the two programs separately avoids duplicate-class handling or
accidental recursive processing of the embedded JAR. Their symbol maps need not
match: they communicate through explicitly named files/fields and a child-JVM
command line, not by exchanging Java object instances.

Mappings are generated only below `build/obfuscated/`:

- `plugin-mapping.txt`
- `restore-mapping.txt`

They are local build diagnostics and are not embedded in either executable or
copied into the public release packages. Do not add them, dependency caches, JDK
library images, or unprocessed intermediate JARs to the publication allowlist.
The public source and public rules let recipients build their own executable and
produce their own mappings. Keeping a maintainer's mapping out of a distribution
does not make the implementation secret.

## Conservative rules and compatibility boundaries

`proguard-common.pro` disables shrinking and optimization. It retains package
boundaries, normal Java verification, runtime annotations, generic/nesting
metadata, record metadata, and diagnostic line numbers. It preserves record
component field and accessor names together while permitting record class names
to change. Enum lookup methods and declared serialization hooks are retained.
Source filename attributes are normalized to `SourceFile`.

The rules preserve these externally discovered entrypoints and callbacks:

- `com.vaultbackup.plugin.VaultBackupPlugin`, its public no-argument constructor,
  and JavaPlugin lifecycle methods. `plugin.yml` keeps the same `main` value.
- Bukkit `@EventHandler` methods, including annotation metadata.
- Command execution, tab completion and inventory-holder interface methods.
- `com.vaultbackup.restore.RestoreMain.main(String[])`, matching the standalone
  helper's manifest `Main-Class`.

Other implementation symbols remain eligible for renaming. No global warning
suppression, string rewriting, aggressive method overloading, access expansion,
or verification disabling is enabled. The optional Folia class-presence probe
has a narrowly scoped informational-note suppression only.

The source audit found these dynamic/persistence boundaries:

- `ServerLifecycle` reflectively resolves external `getServer`, `saveEverything`
  and `hasStopped` server methods. Their string literals are not rewritten, and
  no server implementation classes are program inputs.
- The optional `io.papermc.paper.threadedregions.RegionizedServer` lookup is a
  runtime rejection check; Folia is not made a build dependency.
- Message files use absolute `/messages/ja_JP.properties` and
  `/messages/en_US.properties` resource paths. These paths and contents stay fixed.
- Backup metadata, archive manifests, restore jobs, journals and authentication
  fields are explicitly encoded as textual fields. They do not use reflective
  object-to-JSON mapping or Java object serialization. No application-owned
  `ServiceLoader` provider registrations exist in this version.
- Job authentication secrets remain runtime secrets in protected job files;
  obfuscation does not encrypt or conceal them.

Adding reflection, serializers, native methods, a stable third-party Java API,
or service providers requires a fresh rule review and post-obfuscation tests.
Keeping generic serialization hooks alone does not guarantee compatibility of
persisted Java-serialized objects across builds.

## Library inputs

Never process the server API, Java runtime or ProGuard's own dependencies as
program input. Libraries describe inheritance and method signatures; they are
not copied into the output.

Preferred inputs for a full JDK installation are:

- Plugin: the exact resolved Gradle `compileClasspath`, including the pinned
  Spigot API and all its transitive compile dependencies, plus the selected
  Java 25 toolchain's runtime libraries.
- Helper: only that Java 25 runtime. It must have no server API dependency.

For JDK 25 distributions with `jmods/`, supply each matching `*.jmod` as a separate
library entry with `(!**.jar;!module-info.class)`, for example:

```
-libraryjars /path/to/jdk-25/jmods/java.base.jmod(!**.jar;!module-info.class)
-libraryjars /path/to/jdk-25/jmods/java.logging.jmod(!**.jar;!module-info.class)
-libraryjars /path/to/jdk-25/jmods/java.net.http.jmod(!**.jar;!module-info.class)
```

These three illustrate direct application dependencies; use the complete module
set rather than guessing the transitive closure of the Spigot API. Do not use
Java 21 libraries to process Java 25 classes.

Some JDK distributions contain `lib/modules` and `bin/jimage`, but no `jmods/`.
For that layout, the build may extract the matching toolchain's runtime image
with its `jimage` tool and construct a temporary library-only JAR from the module
contents, excluding every module descriptor. It must stay in local build/tool
storage, never a release archive. It describes the same toolchain runtime; it is
not a replacement JDK shipped to users. See the build script for its actual
module-input selection.

## Verify the final distribution bytes

`tools/check_obfuscated_release.py` checks the actual final JARs rather than the
unprocessed classes. Give it the two build-local mappings, the JDK path, and the
resolved Spigot compile classpath:

```
python3 tools/check_obfuscated_release.py \
  --java-home "$JAVA_HOME" \
  --compile-classpath "<resolved compileClasspath, platform-separated>" \
  --plugin-mapping build/obfuscated/plugin-mapping.txt \
  --helper-mapping build/obfuscated/restore-mapping.txt
```

It checks Java 25 classfile versions, real symbol renaming, expected resources,
notices, entrypoint names, duplicate ZIP entries, absence of shaded dependencies
and mapping/source files, and byte identity between the embedded and standalone
helper. Java runs with `-Xverify:all` to load and reflect over distribution classes,
check record component/accessor correspondence, exercise record bootstrap
`equals`/`hashCode`/`toString` and enum lookup, inspect Bukkit callback annotations,
and load both message languages. The standalone helper must reject missing
arguments with exit code 2. The report defaults to
`build/reports/obfuscation-verification.json`.

These are structural and targeted execution checks. They do not establish live
server compatibility, backup correctness or restore safety on their own. Run the
normal assertion harnesses, the restore harness against the final helper, and
real-server acceptance tests against the exact final plugin JAR. Rerun affected
tests after source, rule, dependency or packaging changes. Consult
[TEST-RESULTS.md](../TEST-RESULTS.md) for the release's recorded results and limits.

## Tool documentation

- [Official ProGuard release history](https://www.guardsquare.com/manual/releasenotes)
- [Library inputs and keep options](https://www.guardsquare.com/manual/configuration/usage)
- [Class-file attributes](https://www.guardsquare.com/manual/configuration/attributes)
- [Entrypoints, callbacks and enum examples](https://www.guardsquare.com/manual/configuration/examples)
