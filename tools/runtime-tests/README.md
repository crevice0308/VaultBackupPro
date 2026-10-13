# Current 1.1.0 runtime suite

For the current final obfuscated 1.1.0 release, use [CONFIRMED-DELETE-README.md](CONFIRMED-DELETE-README.md), `confirmed_delete_harness.py`, and `test_confirmed_last_deletion.py`. The manual-stop 1.0.0 scripts below are retained as historical regression tooling; their old message patterns and artifact names are not the 1.1.0 release recipe.

# Real-server runtime tests

These Python 3 scripts use **disposable local fixtures only**. Never point them at a production server, personal world, or an existing directory you want modified. The suite starts localhost-only offline-mode servers, creates and restores complete backup ZIPs, keeps rollback directories, tests cancellation, and deletes generated archives under its count-retention test. The separate crash test deliberately sends SIGKILL to its own generated server process. No server JAR, world, backup archive, credential, or authorization journal is distributed here.

## Prerequisites and authorization

- Read and accept the [Minecraft EULA](https://www.minecraft.net/en-us/eula) before starting a server. The scripts require `VAULTBACKUP_TEST_EULA_ACCEPTED=true`; do not set it without that agreement.
- Linux, Python 3 standard library, Java 25, roughly 8 GiB free space for a clean run, and free localhost ports 25581–25587. Retaining multiple runs uses more space.
- Build VaultBackup Pro first. Pass the **exact expected release JAR SHA-256**; a mismatch aborts startup.
- Obtain official server files separately. See [environment and dependency verification](../../docs/ENVIRONMENT-VERIFICATION.md) and [Spigot provenance](../../docs/SPIGOT-COMPATIBILITY.md). No Minecraft server binaries are bundled in the source ZIP.
- Configure any necessary network proxy/trust outside the repository. The harness reads proxy hostname/port from `HTTPS_PROXY` at execution time, does not save its value, and uses `/etc/ssl/certs/java/cacerts` on this Linux fixture host. Adapt that trust-store path for another host; do not disable certificate validation.

## Paths

Set these to paths on your machine. BASE and TOOLS are required; PROJECT defaults to this checkout. Use a new disposable area for BASE:

```sh
export VAULTBACKUP_TEST_BASE=/absolute/path/to/new-disposable-test-area
export VAULTBACKUP_TEST_TOOLS=/absolute/path/to/test-tools
export VAULTBACKUP_TEST_PROJECT=/absolute/path/to/vaultbackup-pro
export VAULTBACKUP_TEST_JAVA=/absolute/path/to/java25/bin/java
export VAULTBACKUP_TEST_PLUGIN="$VAULTBACKUP_TEST_PROJECT/build/libs/VaultBackupPro-1.0.0.jar"
export VAULTBACKUP_TEST_RUN=manualstop-revision-local1
export VAULTBACKUP_TEST_EULA_ACCEPTED=true
```

`VAULTBACKUP_TEST_RUN` must begin `manualstop-revision` and may have a letters/digits/underscore/hyphen suffix. Use a fresh tag for a retry: logs and fixtures are intentionally not overwritten.

The exact official server inputs expected under `VAULTBACKUP_TEST_TOOLS` are:

- `servers/paper-26.2-133.jar`
- `servers/paper-26.3-178.jar`
- `servers/purpur-26.2-2633.jar`
- `servers/purpur-26.3-2648.jar` (experimental upstream)
- `spigot-26.2/spigot-26.2.jar`, built with official BuildTools #201, revision 26.2
- `spigot-26.3/spigot-26.3.jar`, built with official BuildTools #201, revision 26.3

Hashes are pinned in `manualstop_harness.py`. Updating versions requires deliberately updating the expected provenance and hashes, not silently accepting a different binary.

## Run

From this directory:

```sh
python prepare_disposable_baselines.py
python test_manualstop_revision.py EXPECTED_PLUGIN_SHA256
python test_manualstop_crash.py EXPECTED_PLUGIN_SHA256
```

The preparation helper only seeds new baseline directories; it refuses existing ones. The main harness copies these baselines into a new run directory and initializes missing worlds on first startup. A baseline may also be pre-initialized by starting and normally stopping its unmodified server once to warm the official runtime cache. Spigot fixtures are freshly initialized directly from their verified bootstrap JARs. Initial bootstrapping may need normal access to official upstream download services.

An optional list of variants after the hash selects a subset. `paper-26.2` also runs confirmed-cancellation/retry checks, English commands/reservation/cancellation, a real one-minute schedule, and generated-archive retention. The crash test expects the main Paper 26.2 fixture to have completed. For a repeated test invocation, preserve previous logs and use a new run tag rather than replacing evidence.

## What is asserted

- Full ZIP CRC, every manifest file size/SHA-256, world data, runtime libraries and exact embedded plugin JAR hash
- At least five seconds of live JVM operation and advancing world time after restore confirmation
- No stage, rollback, normal-stop authorization, or success receipt before the administrator's explicit fixture `stop`
- Reserved archive protection and refusal to create another backup while reserved
- Verified offline restore, modified-original rollback preservation, manual restart, Japanese success text actually green at the text's ANSI state, receipt consumption, and clean stop
- Confirmed cancellation survives stop/restart; an old cancelled helper cannot restore even when explicitly retried while stopped
- English reserve/status/cancel and validation messages, real scheduler execution, count retention
- Force-kill without normal-stop authorization refuses before staging; next restart shows a red Japanese failure without claiming success

Spigot's captured pipe console uses its supported `-Djansi.passthrough=true` option. A terminal or host panel can still strip colors. Assertions interpret ANSI resets at the success/failure text itself rather than counting a nearby colored prefix.

The long-running English/scheduler/retention fixture excludes only `plugins/spark/tmp/**`, because Paper's actively written Spark/JFR temporary files can correctly trigger the backup engine's changed-source refusal. The six-server main matrix does not add this exclusion. This is test-fixture configuration, not a production default or relaxation of source-change verification. The crash fixture inherits that later configuration.

Results and raw console logs are under `$VAULTBACKUP_TEST_BASE/$VAULTBACKUP_TEST_RUN-logs`. Large server trees, ZIPs, helper journals, and rollback trees remain in the disposable area and must not be added to a source distribution. The baseline-seeding utility was syntax-checked; the reported final runtime run used pre-initialized Paper/Purpur baselines and fresh Spigot fixtures.
