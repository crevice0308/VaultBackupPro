# Runtime test results: VaultBackup Pro 1.1.0

**PASS: all six exact server builds completed the final obfuscated-artifact deletion, backup, restore reservation, manual stop, offline restore, and manual restart cycle.** Additional Japanese/English confirmation, cancellation, stale-state, diagnostic, automatic-backup, and retention checks passed.

Executed **2026-10-13, 12:51–12:57 UTC**. Raw console clocks use UTC+09:00. This report covers the final obfuscated 1.1.0 artifacts below. Earlier plain, interrupted, or fixture-debugging trials are not included in the verdict.

## Exact artifacts

- Plugin: **242,619 bytes**, SHA-256 `54b356a6d7a4ccf5a8e2b62b8d556b033c1c76f8cfe7910e19f95c34328e177b`
- Restore helper: **92,617 bytes**, SHA-256 `a2f17d2d2b9b08ae7bea40ec8aec4391fb7f38ddf9c711720c6aa645e5e73a4f`; embedded and standalone bytes match
- **41 plugin classes / 27 helper classes**, all under the project namespace
- Japanese default, both `ja_JP` and `en_US` message bundles, the required plugin entry point, and `api-version: '26.2'` were verified
- The release JAR hashes were rechecked after the run and remained unchanged

[Sanitized artifact inspection](test-evidence/artifact-inspection.json). This curated report contains no raw console/helper logs, absolute fixture paths, user log data, process identifiers, or restore-job identifiers.

## Environment and matrix

Linux amd64, Temurin Java **25.0.4.1+1**, 256–768 MiB JVMs, at most two independent server variants running concurrently, isolated generated worlds, localhost-only offline-mode servers, no players. Existing authorized disposable 1.0.0 baseline trees were copied into completely new directories; no original 1.0.0 artifact, world, backup, or state journal was modified. All six server hashes were checked against the pinned provenance before startup. Minecraft EULA approval was obtained before testing.

| Exact server | Full cycle | Manifest entries verified | Uncompressed bytes verified |
|---|---|---:|---:|
| Paper 26.2 #133 | PASS | 487 | 244,243,257 |
| Paper 26.3 #178 | PASS | 487 | 232,538,404 |
| Purpur 26.2 #2633 | PASS | 503 | 250,217,431 |
| Purpur 26.3 #2648 (experimental) | PASS | 503 | 238,461,167 |
| Spigot 26.2, 4648-Spigot-8db49a2-96ae8cd | PASS | 194 | 188,386,510 |
| Spigot 26.3, 4663-Spigot-bed90fc-c4db4c3 | PASS | 188 | 186,773,232 |

[Server provenance](ENVIRONMENT-VERIFICATION.md) · [Spigot provenance](SPIGOT-COMPATIBILITY.md)

For **each row**, the console harness verified:

1. The exact final 1.1.0 plugin enabled; Japanese help, status, and an initially empty catalogue responded. Paper 26.2 omitted the language key to test the Japanese default.
2. Calling delete confirmation without a deletion preview was refused and preserved the archive.
3. Two generated backups were reduced to one by a normal preview and confirmation. Deleting the remaining good backup required the explicit Japanese warning about leaving zero recovery points and another confirmation.
4. After confirmed last deletion, both status and list truthfully reported zero backups. A new backup could be created immediately afterward.
5. The recreated full backup passed independent ZIP CRC, every manifest file size and SHA-256, world/library payload, marker, and exact embedded-plugin-hash checks.
6. A restore-reserved last backup could not be deleted, including through a deletion confirmation prepared before reservation. Backup creation while reserved was also refused. Expected protections emitted no plugin stack trace.
7. After reservation, the JVM remained running for at least five seconds and world time advanced. No stage, rollback, normal-stop authorization, or success receipt existed before explicit fixture-console `stop`.
8. The explicit manual stop exited **0**. The helper reached `SUCCESS`, restored the original marker, and preserved the changed original in rollback. The server did not automatically restart.
9. A manual restart displayed **green Japanese verified-success text**, consumed the success receipt, retained the exact final plugin hash, and listed the restored backup. The final stop exited **0**.

Spigot's captured console used its supported `-Djansi.passthrough=true` option. Color checks interpret the active ANSI foreground at the success text itself; a terminal or host panel may strip ANSI codes.

## Additional Paper 26.2 checks

**Japanese restore cancellation:** a confirmed reservation was cancelled before stopping. The live marker stayed unchanged and no success receipt was created. Reusing the old restore confirmation was refused, and a fresh reservation in the same JVM successfully completed the normal restore cycle.

**English last-backup deletion and expiry:** help and direct-confirmation refusal were checked. A warned deletion preview expired after the fixture's 15-second confirmation window; confirmation after expiry was refused without deleting the archive. A fresh warned confirmation then deleted the last backup, status/list reported zero, and backup creation worked afterward.

**Changed lastness:** an ordinary non-last deletion preview was created, then its generated sibling archive and metadata were moved outside the test backup store to model another administrator changing the store. The old confirmation did not delete the now-last archive. The console displayed the explicit last-backup warning and requested a fresh confirmation. Only the second explicit confirmation removed it. Expected expiry, stale-state, and protection paths produced no plugin stack trace.

**Unexpected I/O remains diagnosable:** write access to the generated archive directory was temporarily removed. Confirmed deletion preserved the backup, reported failure, and logged the expected `AccessDeniedException` with a stack trace. Permissions were restored immediately afterward. This deliberate diagnostic test is not a product failure.

**Retention and real scheduling:** count retention was set to **1**, with an intentionally impossible **1-byte** archive limit. A newly created valid backup replaced the older one while retaining one recovery point despite exceeding the byte limit. A real one-minute automatic schedule then produced a backup in **66.90 seconds**, with label `automatic`, and again retained exactly one valid archive. Automatic mode was disabled after testing. Count retention was reset to 20 and byte retention disabled.

**English restore cancellation:** the remaining backup was reserved for restore, deletion was refused, and cancellation completed. A stale restore confirmation was refused, no success receipt was created, and status/list continued to show the remaining archive. Final stop exited **0**.

All six remaining archives, including the automatic archive, also passed an independent post-stop ZIP/manifest/hash verification. Logs and Spark temporary payloads were absent. Server/world locks were released. Ports **25581–25587 were all closed** at completion.

## Fixture adjustments and limits

All final fixtures were configured correctly **before startup**, and no configuration correction or source change occurred during the run. The fixture-only settings were a 15-second confirmation window, disabled empty-world pause for deterministic world-ticking assertions, and exclusion of actively written `plugins/spark/tmp/**` files. These are test settings, not a change to production defaults or a relaxation of changed-source verification. Full live backups remain best-effort; transactional data still requires quiescence.

The run covers these exact final artifacts and six exact server builds, not future builds, production load, or unlimited waiting. No real Minecraft player joined. GUI clicks and GUI-only delete cancellation, mixed-actor/player permission flows, Discord delivery, network filesystems, power loss, and a new final-artifact SIGKILL scenario were **not** established by this console suite. Static/unit workflow and safety checks are documented separately in [overall test results](../TEST-RESULTS.md). Ordinary startup authentication/version-check network warnings were present in isolated logs and did not prevent the measured outcomes.

Restore remains an **offline operation after a committed reservation and JVM exit**. There is no live/restartless restore claim. An orderly fault shutdown can also reach the normal shutdown path; disable host automatic restart and cancel an unwanted reservation before stopping.

## Reproduction and public evidence

Use the [1.1.0 confirmed-delete harness and setup instructions](../tools/runtime-tests/CONFIRMED-DELETE-README.md), with separately obtained official server binaries and explicit Minecraft EULA acceptance. Supply the exact expected plugin hash above. The old 1.0.0 manual-stop scripts are historical coverage and are not the source of this 1.1.0 pass verdict.

The public source distribution contains portable harness code and curated summaries. Raw logs, temporary paths, server JARs, generated worlds, backup ZIPs, rollback/staging trees, credentials, and authorization journals remain outside the project and are not included.
