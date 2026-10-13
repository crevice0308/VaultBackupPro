# Review history and 1.1.0 scope

The sections below record the 1.0.0 safety baseline. Version 1.1.0 changes the manually confirmed last-backup deletion policy and repairs rollback diagnostic-failure handling; it also hardens and updates build tooling: explicit last-backup consent may leave zero good backups. Automatic retention and restore reservations remain protected. Final 1.1.0 review findings and regression outcomes are recorded in the new section at the end; do not treat historical assertions as current runtime results.

# VaultBackup Pro security and restore safety review

Review date: 13 October 2026. Scope: source review of version 1.0.0, concentrating on offline restoration, crash recovery, startup and shutdown behavior, archive paths, and administrator inventory actions. This is a focused code review, not a security certification or a guarantee against data loss. Runtime results belong in TEST-RESULTS.md; this review does not turn an untested platform or interaction into a passing test.

## Conclusion

The reviewed restore design stages and verifies a complete replacement tree only after the recorded server JVM has exited. It preserves the original root through directory renames and requires authenticated evidence of completed offline verification before displaying restore success. No ordinary archive traversal or in-game inventory authorization bypass was identified in the reviewed paths. Important failure and confidentiality findings were corrected during review as described below.

Operators must still disable hosting auto-restart and prevent other processes from starting or writing during restore. Advisory locks and a STARTUP plugin cannot stop every uncooperative process or prevent all server-launch writes before the plugin runs. Live backups are best-effort copies and cannot establish atomic consistency for unrelated plugin/database writers.

## Findings and disposition

### Failed helper readiness could leave a restore armed

An IOException during readiness polling could previously return a failed launch after the external helper had already started, without cancelling that helper. A later JVM exit could then initiate restoration despite the reported launch failure.

Corrected in RestoreCoordinator.confirm and failedHandshake. Every uncertain handshake after process creation attempts durable cancellation of its own job. If cancellation fails, it requests termination of that helper and waits for confirmation. Messages distinguish confirmed termination from an unresolved potentially armed helper and explicitly tell the operator not to stop the server in the latter case. abortLaunch uses the job owned by the current coordinator rather than relying on another read of potentially unavailable job data.

Verification in this review: source inspection of the revised control flow and the disposable fixture that starts a helper and deliberately breaks its status response. The fixture asserts durable cancellation and an unchanged original tree. Execution results belong in TEST-RESULTS.md. Failed operating-system process termination was not tested by this review.

### Invalid restore state did not stop server startup

The earlier plugin loaded POSTWORLD and only disabled itself on a guard or journal failure. That did not enforce its instruction to inspect the restore before continuing.

Corrected in plugin.yml and VaultBackupPlugin.onEnable. Loading is now STARTUP, and the guard, journal and authenticated receipt checks share a failure boundary that requests Bukkit shutdown. This reduces the accidental-start exposure. It is not an operating-system launch interlock and does not remove the no-auto-restart requirement.

Verification in this review: source inspection. Exact early-shutdown timing on each supported server build requires runtime evidence.

### Newly created backup and restored directories could expose secrets

Default directory creation could use an ordinary umask such as 0022. Replacing an originally private server root with a newly created 0755 root could expose restored configuration and credentials to other local users before an operator reviewed permissions.

Corrected at new-directory creation points: plugin backup/state/helper directories, the core backup destination, and restore state/job/staging directories request owner-only POSIX permissions using creation attributes. The staged root retains those restricted directory permissions when renamed into place. Existing operator-managed directory permissions are not silently changed. Filesystems without POSIX attributes use inherited/default access controls and require operator review, especially Windows ACLs.

This is protection of the containing directories, not preservation of original filesystem metadata. Ownership, per-file modes, ACLs, executable bits, extended attributes and hard-link relationships are not restored by the ZIP format used here.

Verification in this review: source inspection of creation attributes, fallbacks and fixture permission assertions for newly created state, backup, job and restored root directories. Cross-user access attempts, Windows ACL behavior and non-POSIX filesystems were not tested by this review.

### Cancelling the delete screen retained its confirmation

The final Delete screen previously returned to details without clearing the actor's pending delete confirmation. An explicit delete-confirm command could still consume that pending authorization until expiry.

Corrected in BackupMenus.deleteFinal and VaultBackupPlugin.cancelDelete. Final Cancel now clears only that actor's matching backup confirmation. This was a cancellation-semantics problem, not a cross-user permission bypass.

Verification in this review: source inspection. Real-client click/close timing remains a GUI acceptance-test requirement.

### Backup publication needs directory durability before retention

Source review identified that forcing archive and metadata file contents does not by itself make their renamed directory entries durable. Publishing a new backup and then removing older backups without a directory durability barrier can leave recovery points vulnerable to power-loss ordering on supported filesystems.

Corrected in BackupEngine.createBackup and removeRecord. The backup directory is now forced after publishing both archive and metadata, before creation is marked committed and before the method returns to its retention caller. Deletion forces the directory after withdrawing metadata and again after removing the archive. Non-Windows directory-force errors propagate. Windows directory-force failures are explicitly qualified as platform-dependent durability, rather than certified power-loss safety.

Verification in this review: source inspection of publication, deletion and failure ordering. No physical power-loss test was performed.

## Reviewed safety controls

- Restore reservations are short-lived and bind backup identity, archive digest and manifest scope. Plugin confirmation is bound to the requesting player UUID or console actor and checks permission again.
- No archive extraction or root replacement occurs in onDisable. A strong reference retains the root lock until actual JVM teardown.
- The helper checks the parent PID and process start identity, its own job lock, the external root guard, and world session locks. Explicit manual confirmation does not override a living recorded parent.
- State and backups are external to the server root. Bound sibling staging, rollback and failed-tree paths are checked. Existing recovery paths are not overwritten during normal execution.
- Archive and installed-file digests, entry types and sizes, duplicate paths, path traversal, absolute paths, alternate-stream syntax, selected platform-reserved names and unexpected installed files are checked. Extraction creates regular files; ZIP symlink attributes do not create filesystem symlinks. Filesystem symlinks are refused by path and tree checks.
- The whole root replacement uses two directory renames with journal states. It is not one indivisible transaction. An interrupted swap requires inspection and explicit stopped-server recovery, and original/failed/staged trees are preserved rather than deleted automatically.
- Success requires post-install verification and a job-bound authenticated receipt. Next startup validates that receipt; it describes what was verified offline, not a claim that normal startup has left every byte unchanged.
- The GUI trusts server-owned InventoryHolder objects and action maps rather than item names or lore. Owner and currently open instance are checked. Every click in a managed view is cancelled before dispatch; only a plain left click in the top inventory can invoke an action. Permissions and active-menu identity are checked again on the next tick. Drag events are cancelled. Restore and deletion use warning and final-confirmation screens.
- The GUI custom-interval button directs the administrator to the validated /backup interval command. It does not itself collect a custom number inside the inventory.

## Boundaries and unverified behavior

This review does not establish correctness under hostile same-account filesystem mutation, malicious replacement of both archives and their metadata, remote filesystem semantics, filesystem or hardware corruption, full-disk failures at every write, every power-loss point, concurrent uncooperative server starts, or every third-party plugin writer. The saved receipt secret is not a defense against an administrator who can alter the trusted state directory.

The reviewer inspected source and fixture tests but did not launch a Minecraft server, run a graphical client, execute Windows wrappers, or inject operating-system/power failures. Use the final release JAR test matrix for the exact runtime versions and scenarios actually exercised. Rebuild and rerun applicable checks after source changes; a passing earlier JAR does not validate a later one.

## Manual-stop, Japanese UI and Spigot revision

Reviewed 13 October 2026, product version still **1.0.0**. This section supersedes the baseline reservation/shutdown description above. It covers the revised coordinator, helper, shared job state, plugin commands and inventory callbacks, locale bundles, and narrow server-lifecycle adapter. The final distribution is a plain, non-obfuscated plugin/helper with source. Earlier runtime evidence does not by itself validate this revision; use the final artifact hashes and revision-specific test reports.

### Reservation and shutdown boundary

- Confirmation now creates an external reservation and obtains helper readiness. It does **not** call server shutdown or restart. The only remaining plugin `Bukkit.shutdown()` call is its startup restore-safety refusal.
- The preview confirmation expires, but an accepted reservation has no automatic waiting deadline. Its selected archive remains protected while the reservation is unresolved. Backup creation and automatic backup attempts are blocked while the plugin has a committed or uncertain reservation; deletion still checks the coordinator's protected IDs before acting.
- The automatic helper requires both a job/JVM-bound authenticated shutdown marker and independently verified exit of the recorded PID/start identity. It also requires the root guard and checks world session locks. The marker is written only after the supported server adapter reports entry into its shutdown path. Ordinary plugin disable/reload does not qualify. No archive extraction or directory replacement occurs in `onDisable`, and the root guard remains retained until operating-system JVM teardown.
- A forced exit **without** that marker fails before staging. The marker is a shutdown-intent signal, **not proof of a clean save or proof that an administrator caused the stop**. CraftBukkit sets `hasStopped` before disabling plugins and saving worlds; a fault that enters the same orderly shutdown path can qualify. A forced kill after marker publication can also satisfy the later process-exit check. Do not describe this design as refusing every crash or guaranteeing every shutdown hook completed. User-facing reservation text discloses that an orderly fault shutdown can qualify.
- Explicit stopped-server manual execution can bypass the automatic shutdown-marker requirement, but cannot bypass parent identity/liveness, root/world locks, cancellation, active-job binding, or an uncertain swap journal. A cancelled reservation cannot be revived by manual or recovery flags.

### Cancellation ordering and cleanup

The helper and coordinator serialize cancellation and application with the same external `job.lock`. The helper releases that gate throughout the wait for JVM exit, and retains it throughout staging, replacement, installed-tree verification and any rollback. Therefore a cancellation that loses to application reports failure/pending rather than falsely acknowledging success.

Acknowledged cancellation requires a permanent, job-bound authenticated tombstone plus a matching acknowledgment. The acknowledgment is issued by the helper, after confirmed helper death, or for a job proven never launched by its owning coordinator. An invalid or unreadable cancellation marker blocks execution. The helper checks cancellation before active-job authorization, so retrying an old cancelled helper cannot execute or clear a newer active reservation. Tombstones are not removed when the active slot is freed.

The active slot and archive pin are released only after safe cancellation cleanup. Ambiguous writes, held gates, missing acknowledgments and uncertain helper identity keep the reservation blocked/protected. A successfully acknowledged cancellation permits another reservation in the same running JVM. Repeated cancellation of that same completed cancellation is safe.

### Findings corrected during this revision review

- A dead helper could still be displayed as waiting based only on its journal. Coordinator status now distinguishes an exited or uncertain helper, with matching Japanese/English UI messages; it does not promise that a dead helper will apply the reservation.
- Repeating cancellation by job ID after active-marker cleanup previously failed active-job validation. The revised path recognizes the acknowledged tombstone while preserving the check against clearing a different active job.
- A failed active-marker publication could leave the selected archive unpinned. The pin is now set before publication. Failure before publication, and failure after active-marker deletion but before directory synchronization, remain visible/cancellable instead of silently appearing idle with an unreleasable in-memory pin.
- Lifecycle adapter verification originally ran before the restore startup-safety boundary. It now runs inside that boundary after root-guard/journal/receipt validation, so adapter failure requests safety shutdown rather than silently skipping the restore guard.
- Explicit recovery now recognizes the new waiting state. Reinvoking a helper with a `SUCCESS` journal reports that journal state without independently claiming receipt verification; plugin startup still authenticates the success receipt before its success banner.

### Spigot compatibility and localized controls

The plugin compiles against the common Spigot API. Inventory titles/items and sender messages use common API methods; there is no required Adventure/Paper linkage in those paths. The remaining Folia class-name probe is a rejection check, not a Paper runtime dependency.

`ServerLifecycle` resolves only the expected public CraftBukkit/Minecraft methods and validates their boolean signatures. Its save operation invokes `saveEverything(true, true, true)` on the server thread and requires a true return; it does not mistake command-dispatch handling for a completed save. Missing methods, invocation failures or a false save result abort backup preparation. Unknown shutdown adapters return false. These are supported-build integration checks, not a promise that implementation-level reflection will survive arbitrary future server releases, nor an independent guarantee of physical disk durability or cross-plugin consistency.

Japanese is the default, including configuration files without an explicit language key. Japanese/English bundles were checked for matching keys and placeholders. Backup IDs, file paths and machine state codes remain unchanged; low-level exception diagnostics may retain their original language. Localized display strings do not authorize actions or choose restore targets.

Inventory authorization controls remain intact: server-owned holders, owner UUID, currently open menu identity, top-inventory plain-left-click dispatch, and permission rechecks at execution. Drag and alternate click paths are cancelled. Both the dashboard and status screen expose committed-reservation cancellation; the final preview cancellation clears the actor's pending confirmation. No graphical-client acceptance test is implied by this source review.

### Verification limits

The revised disposable-fixture test source covers confirmed cancellation followed by JVM exit, repeated cancellation, same-JVM re-reservation, old cancelled helper retries, a held job gate, invalid cancellation state, helper death, abrupt parent exit before the marker, authenticated success and rollback/recovery. Consult the final test report for what actually passed against the distributed plain helper and plugin. This reviewer did not launch a Minecraft server or touch production server data. Full-disk/directory-fsync failures at every publication point, physical power loss, Windows behavior, adversarial same-account mutation and concurrent uncooperative launches remain outside this review's demonstrated coverage.


## Version 1.1.0 audit and fixes (13 October 2026)

The confirmed manual-delete path can intentionally reach zero good backups. Permission, actor, selected archive, expiry, exclusive store lock and restore-reservation protection remain authoritative. A stale ordinary confirmation that becomes the final good backup is rejected and renewed only after the extra warning; an offline player is not treated as having received that warning. Automatic retention still retains a verified good backup. Expected confirmation requirements use typed, localized feedback; unexpected I/O retains diagnostics.

Independent fault injection found that a failed status write inside the rollback catch could prevent the actual directory move back. Original data remained in the rollback tree, but the original server root could be absent. The fix isolates best-effort journal writes from physical recovery and preserves the original exception plus suppressed diagnostic/rollback errors. Tests include I/O journal loss, malformed Properties, loss after installed verification with receipt removal, and a conflicting target that must never be overwritten. The expanded restore suite has 166 passing assertions. Independent review reproduced both original failures and verified recovery after the fix. No false SUCCESS receipt is accepted.

Build-only supply-chain changes: Gradle 9.8.1 with distribution SHA-256, ProGuard 7.10.0, JSON-java 20260719 in the isolated ProGuard configuration, exclusive official Spigot repository routing, and checked-in SHA-256 dependency metadata. This addresses the reviewed Gradle advisories (including CVE-2026-22865/CVE-2026-22816 and the additional 9.8.1 fixes) and avoids the affected JSON parser version. JSON parser exploit reachability through ProGuard was not established. These tools are not shaded into the runtime JAR.

The initial metadata generator encountered a Gradle timestamped-snapshot duplicate-key error. Metadata was assembled from the exact resolved official-repository artifacts and required POM parents and then tested in strict verification mode. These checks detect subsequent byte changes; checksums do not themselves establish publisher identity or guarantee safe dependencies. Fresh-cache resolution exposed 13 additional BOM/parent metadata files; their exact official Maven Central SHA-256 values were added. The resulting 53 components / 93 artifact checksums retain strict verification with no trust/ignore exceptions. Reproducibility evidence is recorded separately.

The final plugin and helper are independently symbol-obfuscated without shrinking or optimization. Runtime resource names, Bukkit entrypoints/callbacks, record/enum behavior, helper entrypoint and embedded-helper identity are checked against the actual obfuscated bytes. Corresponding readable GPLv3 source and obfuscation settings remain public.

No unauthenticated game-client RCE, arbitrary-path write or authorization bypass was found in the reviewed paths. This is bounded review and testing, not a statement that the software has no vulnerabilities. Windows execution and real Minecraft client GUI clicks were not exercised for this revision. Runtime acceptance and reproducibility results are tracked in TEST-RESULTS.md; do not infer a pass from this review alone.

Sources: [Gradle 9.8.1 security fixes](https://docs.gradle.org/9.8.1/release-notes.html), [Gradle repository advisory](https://github.com/gradle/gradle/security/advisories/GHSA-mqwm-5m85-gmcv), [Gradle script advisory](https://github.com/gradle/gradle/security/advisories/GHSA-w78c-w6vf-rw82), [JSON-java advisory](https://github.com/stleary/JSON-java/security/advisories/GHSA-ghc9-756g-vrcv).


A fresh-cache regression run also exposed a test synchronization race: a helper can disappear between ProcessHandle lookup and start-time lookup, and the intentionally fail-closed identity check raises IOException. The test previously let that transient exception abort polling immediately. Only the test polling was changed to retry within its existing bounded deadline, retain the last error on timeout, and require an affirmative authenticated death result. Production identity checks were not weakened. The failed fixture retained original data and no false success.
