# Runtime test results: VaultBackup Pro 1.0.0

**PASS: all six exact servers completed the final-artifact backup → reservation → manual stop → offline restore → manual restart cycle.** Japanese/English commands, confirmed cancellation/retry, real scheduling, count retention, and forced-crash refusal passed.

Executed **2026-10-13, 11:16–11:30 UTC**. Console clocks use UTC+09:00. Only this final non-obfuscated GPL release is covered; historical/observer-interrupted runs are not substitutes.

## Exact artifacts

- Plugin: **274,018 bytes**, SHA-256 `1abf09736a5ea4bf1b363259a744c9ff050b7d73548db353e7066f357855c7ab`
- Helper: **105,276 bytes**, SHA-256 `93ff6574b7af2b8bf3f72a48b4e1a0f1ab9628bbcd78a01e194f1eb416a520a9`; embedded and standalone bytes match
- **37 plugin classes / 25 helper classes**, all in the project namespace; both JARs include LICENSE, NOTICE, and third-party notices
- Japanese default and `ja_JP`/`en_US` bundles verified; descriptor retains `api-version: '26.2'`, `load: STARTUP`, and the required main class

[Sanitized artifact inspection](test-evidence/artifact-inspection.json). This public report retains measured outcomes; raw console logs, temporary filesystem paths, process IDs, and restore-job identifiers are excluded.

## Environment and matrix

Linux amd64, Temurin Java 25.0.4.1+1, sequential 256–768 MiB JVMs, generated flat-world fixtures, localhost ports 25581–25587, offline authentication, view/simulation distance 2. No player joined. Minecraft EULA approval was obtained before startup. Existing Paper/Purpur baselines were copied; Spigot fixtures were newly initialized from official BuildTools #201 output. Every server hash was rechecked. [Provenance](ENVIRONMENT-VERIFICATION.md)

| Exact server | Cycle | Manifest entries | Verified uncompressed bytes |
|---|---|---:|---:|
| Paper 26.2 #133 | PASS | 491 | 244,886,303 |
| Paper 26.3 #178 | PASS | 491 | 233,183,613 |
| Purpur 26.2 #2633 | PASS | 507 | 250,862,644 |
| Purpur 26.3 #2648 (experimental) | PASS | 507 | 239,106,384 |
| Spigot 26.2, 4648-Spigot-8db49a2-96ae8cd | PASS | 194 | 188,417,850 |
| Spigot 26.3, 4663-Spigot-bed90fc-c4db4c3 | PASS | 188 | 186,804,591 |

For **each row**, the harness verified:

1. Final plugin enabled; Japanese help/status/list responded. Paper 26.2 omitted the language key to check the Japanese default.
2. A full backup passed independent ZIP CRC, every manifest file size/SHA-256, marker/world/library coverage, and exact backed-up plugin-hash checks.
3. After changing the marker, preview and confirmation reached `WAITING_FOR_MANUAL_STOP`. The JVM stayed alive at least **5 seconds**, and overworld time advanced **101 ticks**. Japanese status said manual stop was pending. No stage, rollback, receipt, or normal-stop marker existed yet.
4. Reserved-archive deletion preview and a new backup were refused; current data remained unchanged.
5. An explicit fixture-console `stop` returned **0**. The normal-stop authorization existed; the helper completed `SUCCESS`, restored the original marker, and preserved the modified original in rollback.
6. The server remained stopped until manual restart, then reached `Done`, showed **actual green Japanese success text**, consumed the receipt, retained the final plugin hash, and listed the backup. Final stop returned **0**.

The table and assertions above summarize the six measured cycles. Spigot's pipe console used its supported `-Djansi.passthrough=true`; without ANSI-capable output a host console may strip colors. The observer checks active color at the text itself. Purpur's multiline reset issue was fixed, and all six cycles reran on the final hash.

Journals use format 2, `MANUAL_NORMAL_STOP`, and no automatic deadline. Five seconds verifies waiting behavior, not indefinite-wait endurance. A normal shutdown path does not establish human intent: another exit that runs that path can also authorize restore after reservation. Automatic restart must be disabled; cancel an unwanted reservation before stopping.

## Additional final-artifact checks

**Confirmed cancellation, Paper 26.2:** two confirmed reservations were cancelled; same-JVM re-reservation worked. After normal stop, both remained cancelled, without restore/rollback/receipt and with the modified marker intact. Retrying an old cancelled helper while stopped refused with exit **3**. Restart did not falsely claim success; a fresh reservation subsequently restored successfully.

**English/scheduling/retention:** `en_US` help, status, list, validation, preview, confirmed reservation, waiting status, cancellation, and stale-confirmation refusal passed. A real one-minute schedule produced an independently verified archive in **66.99 seconds**. Retention count **2** kept exactly two generated ZIPs after each of three manual backups and removed **2** prior generated archives. Automatic mode was disabled, retention reset to 20, fixture language restored to Japanese, and stop returned **0**.

The first extended attempt safely refused a concurrently changing Spark/JFR temporary file. That incomplete attempt is a separate negative test outcome, not the complete extended pass. Only the subsequent extended/crash fixtures excluded `plugins/spark/tmp/**`; no production default or source-change check changed. The six main cycles did not add this exclusion.

**Crash refusal:** SIGKILL of a separately generated reserved Paper fixture returned **−9** without a normal-stop marker. The helper reached `FAILED_BEFORE_SWAP`: no stage, rollback, receipt, or marker rollback. Restart displayed a **red Japanese failure**, never success, and final stop returned **0**.

All seven localhost ports were verified closed after the tests. Interrupted and superseded attempts are excluded from the final pass verdict.

## Limits and reproduction

These real headless tests cover the exact builds/artifact, not future versions or production load. No client GUI/click/permission test, external webhook, transactional database, network filesystem, power-loss, long-wait, or byte-retention runtime test is claimed. Live backup is best-effort; the Spark refusal illustrates concurrent-writer limits. Logs retain isolated-network authentication/version-query failures, upstream/JDK warnings, and Paper logger advice; these did not block the final results. See [overall tests and limits](../TEST-RESULTS.md).

[Reproduction scripts and EULA/setup requirements](../tools/runtime-tests/README.md). The baseline-seeding utility was syntax-checked; this run used initialized Paper/Purpur baselines and fresh Spigot fixtures. The public distribution contains this summary and sanitized artifact metadata, with no raw console/helper logs, temporary paths, job identifiers, server JARs, worlds, backup ZIPs, credentials, or authorization journals. Disposable baselines, backups, and rollback trees are excluded.
