# Real-server tests

Two generations of scripts live here.

- **`smoke_1_3.py`** is the acceptance run for 1.3.x. It was run against the 1.3.0 release JAR; the results are in `TEST-RESULTS.md` and `docs/test-evidence/runtime-smoke-1.3.0.json`.
- Everything else (`direct_harness.py`, `test_direct_*.py`, `manualstop_*`, `confirmed_delete_*`, …) is the harness of the 1.0.0 to 1.2.0 releases. It is kept as the record of how those results were produced and cannot be used against a 1.3.0 JAR; see "The 1.2.0 harness" below.

Running a Minecraft server requires accepting the Minecraft EULA (https://www.minecraft.net/en-us/eula). No script here accepts it for you: each writes `eula=true` only when `VAULTBACKUP_TEST_EULA_ACCEPTED=true` is set, which you set after reading and accepting the agreement. No script downloads a server.

## The 1.3.x acceptance run (`smoke_1_3.py`)

A single self-contained script. It starts real servers in new, disposable fixtures (127.0.0.1 only, offline mode, at most two players, a small flat world, RCON with a random password) and drives them through the console and RCON. No Minecraft client is involved.

    VAULTBACKUP_TEST_BASE=/absolute/path/that/does/not/exist/yet
    VAULTBACKUP_TEST_JAVA=/absolute/path/to/jdk-25/bin/java
    VAULTBACKUP_TEST_PLUGIN=/absolute/path/to/VaultBackupPro-1.3.0.jar
    VAULTBACKUP_TEST_PAPER=/absolute/path/to/a/directory/with/paper-<version>-<build>.jar
    VAULTBACKUP_TEST_SPIGOT=/absolute/path/to/spigot-<version>.jar
    VAULTBACKUP_TEST_EULA_ACCEPTED=true
    python3 smoke_1_3.py paper spigot

- `VAULTBACKUP_TEST_PAPER` names a directory that holds exactly one `paper-*.jar`. If `cache/`, `libraries/` and `versions/` are beside it (they appear after the launcher has run once), they are copied into the fixture so that the test itself downloads nothing.
- `VAULTBACKUP_TEST_SPIGOT` is the server JAR built by the official BuildTools (`java -jar BuildTools.jar --rev <version>`).
- Give only one of the two variables, or name the servers on the command line, to run one server.
- `VAULTBACKUP_TEST_PORT` (default 25610) is the first port; each server uses ten ports from there, and its RCON listens 100 above its game port.
- Linux only: the script reads `/proc`, sends POSIX signals and, for one scenario, uses `tmux` (skipped when it is not installed).
- A complete run takes about ten minutes per server and needs about 2 GiB of disk space per server. The fixture directories, the console logs (`logs/`) and `summary.json` stay in `VAULTBACKUP_TEST_BASE`; the script never deletes anything.

What it checks, on each server:

1. First start in the shipped configuration: the plugin enables without a warning or error, `config.yml` is written unchanged, the backup and state directories are created beside the server (with the server's folder name in them) and record the server as their owner, the bundled restore helper is installed.
2. `/backup help`, `status`, `create`, `list`, `verify`, `delete` from the console; `status` and `create` over RCON; the contents of an archive (the world, `server.properties`, the plugin configuration; no `session.lock`, no log).
3. `/backup reload` after an edit by hand, and the setting commands (`interval`, `retention`, `auto`); the comments in `config.yml` survive.
4. An automatic backup, which runs after the empty server has paused (`pause-when-empty-seconds`).
5. A complete restore: reserve, stop from the console, the helper applies the backup after the server process has exited, the previous data is kept beside the server, the next start reports the verified restore.
6. A cancelled reservation is not applied when the server stops.
7. A plugin reload (`/bukkit:reload confirm`; on Paper the bare `/reload` is the vanilla data-pack command): the server keeps running, the reservation is withdrawn, backups still work.
8. The server is killed (SIGKILL) while a restore is reserved: nothing is applied, and the next start says so and clears the reservation.
9. The helper is ended together with the server (what a container stop or `systemctl stop` does): nothing is applied; `helpers/restore.sh` then applies the reserved restore by hand, and the next start reports it.

On Paper only:

10. A second server that names the first server's directories: its plugin is disabled, the server keeps running, and nothing in the directories changes.
11. The server runs as the only program of a `tmux` session, which therefore ends, and hangs up everything in it, when the server exits: the helper survives and applies the restore.

What it does not check, because it needs a Minecraft client or other infrastructure: the menus, commands issued by a player, permissions of players who are not operators, tab completion, the `/help` listing, Discord notifications, Windows, hosting panels, containers and systemd themselves (scenario 9 imitates only what they do to the processes), large worlds, long runs, power loss.

Every check that passed is listed in `summary.json`, together with the SHA-256 of the plugin JAR and of the server JARs that were used. A failed check ends the scenario it belongs to; the summary then names it.

## The 1.2.0 harness

> **Not updated for 1.3.0 and not run against it.** The scripts require the frozen obfuscated `VaultBackupPro-1.2.0.jar` and install it under that file name, accept only run tags beginning with `direct-120`, pin the SHA-256 of particular server builds and the JDK version string, and expect 1.2.0 console output. The older scripts (`manualstop_*`, `confirmed_delete_*`) pin 1.0.0 and 1.1.0 in the same way. Everything below describes the 1.2.0 procedure.

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
