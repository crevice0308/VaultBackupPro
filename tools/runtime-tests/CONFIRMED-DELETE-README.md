# VaultBackup Pro 1.1.0 confirmed-last-delete runtime regression

These tests use newly copied, isolated, localhost-only server fixtures. They deliberately delete **only generated test backups**, move one generated sibling archive to a quarantine directory to simulate stale state, and temporarily deny writes to the test archive directory to verify unexpected-I/O diagnostics. Do not use a production directory or personal world.

Read the existing `README.md` for official server provenance, Java 25, EULA, proxy, and fixture preparation requirements. Explicit Minecraft EULA acceptance is required before setting `VAULTBACKUP_TEST_EULA_ACCEPTED=true`. No Minecraft server binaries or worlds are included in the distribution.

## Run

```sh
export VAULTBACKUP_TEST_BASE=/absolute/path/to/disposable-tests
export VAULTBACKUP_TEST_BASELINES=/absolute/path/to/previous-isolated-baselines
export VAULTBACKUP_TEST_TOOLS=/absolute/path/to/test-tools
export VAULTBACKUP_TEST_PROJECT=/absolute/path/to/vaultbackup-pro-1.1.0
export VAULTBACKUP_TEST_PLUGIN="$VAULTBACKUP_TEST_PROJECT/build/libs/VaultBackupPro-1.1.0.jar"
export VAULTBACKUP_TEST_JAVA=/absolute/path/to/java25/bin/java
export VAULTBACKUP_TEST_RUN=last-delete-110-local1
export VAULTBACKUP_TEST_EULA_ACCEPTED=true
python test_confirmed_last_deletion.py EXPECTED_PLUGIN_SHA256
```

`VAULTBACKUP_TEST_BASELINES` must contain `paper-26.2`, `paper-26.3`, `purpur-26.2`, `purpur-26.3`, `spigot-26.2`, and `spigot-26.3` isolated baseline directories. None are modified. Only the new copies replace the previous plugin JAR with the pinned 1.1.0 JAR. For freshly prepared uninitialized baselines, normal official upstream access may be needed at startup. The old preparation helper can be used to seed Paper/Purpur baselines; Spigot can be initialized from the separately verified BuildTools binaries if no baseline directory exists.

`VAULTBACKUP_TEST_BASE` and `VAULTBACKUP_TEST_TOOLS` are required and have no machine-specific defaults.

The run tag must begin `last-delete-110` and may have an alphanumeric/hyphen/underscore suffix. Use a new tag for a new attempt. Existing fixtures and logs are not replaced. Two independent variants may run concurrently on their distinct localhost ports. Allow at least 8 GiB free space for fixtures, backups, and preserved rollback trees.

## Coverage

All six server variants:

- Exact final 1.1.0 plugin hash and pinned server hashes
- Japanese help, status, and empty catalogue
- Direct deletion confirmation without a preview is refused
- Confirmed manual deletion from two backups to one, then explicit last-good warning and confirmation from one to zero
- Zero-count status and empty catalogue are truthful; a new backup succeeds afterward
- Full ZIP CRC, every manifest size/SHA-256, world/library payload, and embedded plugin hash
- Restore-reserved last backup cannot be deleted, including an earlier deletion confirmation; new backup creation is also blocked
- Expected protections do not emit plugin stack traces
- Restore confirmation leaves the JVM running and world ticking; no staging, swap, or receipt before explicit manual stop
- Offline restoration, preserved rollback, no automatic restart, manual restart with green Japanese verified-success receipt, receipt consumption, and exact restored plugin hash

Additional Paper 26.2 English cases:

- Last-good warning, confirmation, zero state, and recreation
- Confirmation expiry
- An ordinary preview whose target later becomes the last good archive requires a new warning and second explicit confirmation
- Expected refusals have no plugin stack trace, while injected unexpected I/O still has a diagnostic and stack trace
- Retention count 1 and an impossible 1-byte limit preserve one verified archive
- A real one-minute automatic backup replaces the old generation while still retaining one

The fixture uses a 15-second confirmation window. Spark's actively written `plugins/spark/tmp/**` output is excluded only in the disposable fixtures; this is not a production default or a relaxation of changed-source verification. Empty-world pause is disabled in these fixtures to make the world-ticking assertion deterministic.

Japanese confirmed-restore cancellation and same-JVM re-reservation are also checked; the English pass checks reservation protection, cancellation, and refusal of stale restore confirmation.

No interactive Minecraft client is used. Inventory click paths, GUI cancellation, permissions through a real player session, and mixed-actor confirmations are not established by this console suite. Those need separate tests. There is no live or restartless restore feature in this test.

Raw console logs and detailed fixture results remain under `$VAULTBACKUP_TEST_BASE/$VAULTBACKUP_TEST_RUN-logs`; generated server trees, archives, state/authorization journals, and rollback trees must never be added to a source ZIP. A sanitized summary can be published separately after reviewing it for private paths and user data.
