# Direct-action 1.2.0 real-server regression harness

Use only newly created disposable test directories. Never point this harness at a production server or real user files. All fixture worlds bind to127.0.0.1, disable authentication, allow at most two players, and use small flat worlds. Do not expose these ports to a network. No actual players are used.

## Prerequisites

- A verified JDK25.0.4.1+1 executable (the recorded test baseline).
- Python3 on Linux. The startup probes use `/proc`, POSIX process signals, and `fcntl` locks; they are not Windows tests.
- The exact final obfuscated VaultBackupPro1.2.0 JAR, plus its independently provided lowercase SHA256. Do not substitute an earlier or rebuilt JAR.
- Independently downloaded official Paper/Purpur server JARs, and Spigot JARs locally produced by official BuildTools#201. Server artifacts are not bundled here.
- Explicit operator acceptance of https://www.minecraft.net/en-us/eula for these isolated tests.

Expected server files under `$VAULTBACKUP_TEST_TOOLS/servers`:

- paper-26.2-133.jar
- paper-26.3-179.jar
- purpur-26.2-2633.jar
- purpur-26.3-2648.jar (experimental)
- spigot-26.2.jar
- spigot-26.3.jar

Publisher/checksum provenance is in the project environment and Spigot documents. Exact expected SHA256 values are pinned in `direct_harness.py`; an unexpected hash aborts before startup. Downloads/builds and their availability must be recorded separately from plugin test results.

Set these environment variables to paths on your own machine:

    VAULTBACKUP_TEST_BASE=/absolute/path/to/new-disposable-evidence-directory
    VAULTBACKUP_TEST_TOOLS=/absolute/path/to/official-test-tools
    VAULTBACKUP_TEST_JAVA=/absolute/path/to/verified-jdk25/bin/java
    VAULTBACKUP_TEST_PLUGIN=/absolute/path/to/frozen/VaultBackupPro-1.2.0.jar
    VAULTBACKUP_TEST_RUN=direct-120-final
    VAULTBACKUP_TEST_EULA_ACCEPTED=true

Export the variables. Set the EULA variable to `true` only after accepting the linked agreement. Create the empty base directory first. A new attempt must use a new run tag matching `direct-120` or `direct-120-<letters_digits_dashes>`. The harness refuses to overwrite existing fixture directories or console logs.

## Commands

From this harness directory:

    python3 baseline_init.py paper-26.2 paper-26.3 purpur-26.2 purpur-26.3 spigot-26.2 spigot-26.3
    python3 test_direct_actions.py EXPECTED_FINAL_SHA256 paper-26.2 paper-26.3 purpur-26.2 purpur-26.3 spigot-26.2 spigot-26.3
    python3 test_direct_extras.py EXPECTED_FINAL_SHA256
    python3 test_startup_boundaries.py EXPECTED_FINAL_SHA256

Baseline initialization contains no product plugin. If an official server is unavailable, select only available variants and explicitly report omitted targets as untested. `test_direct_extras.py` can prepare its own fresh Paper26.3 and Paper26.2 crash fixtures from the initialized baselines; use a distinct run tag when rerunning extras so existing logs are preserved. Startup-boundary tests create separate Paper26.3 fixtures from its baseline. Ports25581–25589 must be free.

Primary tests cover direct deletion2→1→0, rejected legacy `confirm` syntax without mutation, byte retention to zero and truthful feedback, recreation, list/status while busy, one-command restore reservation, repeated-request refusal, reserved archive protection, cancellation/re-reservation, continued world ticking while waiting, manual console stop and real JVM exit, offline restore, rollback preservation, no automatic restart, manual restart green Japanese success receipt, and restored final plugin hash. Archive CRC, manifest file hashes, world payload, and runtime libraries are verified.

Extras cover English retention/deletion and restore messages, permitted auto/interval/language changes during reservation, rejected storage/integrity changes, and a real one-minute automatic backup after cancellation that is removed by byte retention with truthful zero-count feedback, and a deliberately SIGKILLed disposable server without normal-stop authorization. The startup probe holds the external root guard in an independent process, observes the plugin-requested shutdown, and records world-file SHA256 differences plus ordered load/save log events. It does not assume a Bukkit shutdown request prevents all pre-plugin or startup writes. Report this as an observation of an unsupported concurrent-startup boundary, separately from workflow acceptance passes; a completed probe is not a successful write-prevention test. Another fresh fixture terminates only its exact waiting helper, stops its server gracefully, and tests authenticated stale-waiting reconciliation on restart.

Every run records the tested plugin hash and raw console logs outside the source tree. Keep failed attempts; distinguish test failures, unavailable infrastructure, and passing scenarios. These tests do not cover actual-player GUI clicks, mid-verification player permission revocation, Windows, real webhooks, production-scale load, or power loss. Source/unit probes are separate evidence.

## Default configuration and fixture controls

The primary matrix first starts each fresh fixture with no config file, verifies the plugin-generated config is byte-for-byte identical to the frozen JAR resource, and checks the narrow shipped `.paper/console_history` exclusion. It stops normally before applying disposable paths and disabling automatic scheduling. The archive must exclude console history while retaining the adjacent `.paper/version_history.json` on Paper/Purpur.

The harness retains the complete runtime cache/library payload and adds only `plugins/spark/tmp/**` as a recorded fixture-only exclusion. The original failed preliminary attempts are separate evidence: included console history changed when concurrent console commands were deliberately issued. Final tests rely on the shipped default fix, not a hidden test-only console-history exclusion. Existing operator exclusion lists are not modified by the product default change.
