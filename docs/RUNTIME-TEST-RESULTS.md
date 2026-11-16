# VaultBackup Pro 1.2.0 runtime results

> **These results do not cover 1.3.0.** They belong to the 1.2.0 final JAR listed below, which was an obfuscated build. They were not re-run for 1.2.1, and no real-server test at all was run for 1.3.0, because starting a Minecraft server requires accepting the Minecraft EULA.
>
> 1.3.0 differs from the tested build in ways these results cannot stand in for: the JARs are no longer obfuscated; the plugin uses only public server API, with Brigadier commands and Adventure components on Paper and Purpur and plugin.yml commands on Spigot; worlds are saved by running `minecraft:save-all flush`; a file that changes while it is copied no longer aborts the backup; and the way a server stop is recognised changed (an API check on Paper and Purpur, a ten-minute rule on Spigot). The harness in tools/runtime-tests that produced these results was not updated for 1.3.0 either. See TEST-RESULTS.md for the build and isolated test results of the current release.

Prerelease test distribution. Tested on 2026-10-13 UTC with Temurin JDK 25.0.4.1+1.

- Final plugin SHA-256: `598d17446beb71b56b58386edeaf3f51d9d943a71b0ac76c72dcc54d77550996`
- Embedded/standalone helper SHA-256: `308511e9c2c7c7950c8e16181033182cbab342e834b37d1de686dc4257c096e2`

## Completed acceptance tests

- Paper 26.2 build 133 (stable): 32 checks passed.
- Paper 26.3 build 179 (stable): 32 checks passed.
- Purpur 26.2 build 2633 (stable): 32 checks passed.
- Purpur 26.3 build 2648 (experimental): 32 checks passed.

Total: 6 workflow scenarios, 153 checks. Startup-conflict observation below is excluded from this pass count.

Each primary target verified direct deletion to zero, retention to zero and recreation, immediate acceptance → save completion → final-result ordering, cached list/status while busy, reservation/cancellation without stopping play, manual stop and actual JVM exit, offline restoration, actual helper exit before manual restart, green verified receipt, rollback preservation, and unchanged restored plugin hash. Fresh plugin-generated defaults matched the frozen resource; console history was excluded while its neighbouring version history was retained.

- English direct actions, reserved settings and real scheduled retention: 16 checks passed.
- Authenticated stale waiting job with dead helper: 9 checks passed.

## Important observed limitation

On Paper 26.3 build 179, an independently held restore root guard made the plugin request shutdown. Paper still loaded/saved worlds and displayed Done before exiting 0. The world files `level.dat` and `level.dat_old` changed. This is an observed limitation, not successful write prevention. Concurrent startup during restore is unsupported.

Disable automatic restart. Reserve restoration, stop manually, wait for helper SUCCESS and actual helper exit, then restart manually.

## Boundaries and earlier attempts

- Spigot 26.2 and 26.3 final runtime tests were unavailable. Official BuildTools #201 source preparation was blocked by proxy/approval-review failures; no unofficial binary was used.
- Purpur 26.3 build 2648 remains experimental.
- Earlier hashes and interrupted executions are retained separately and are not counted as final passes. Interrupted operations were reconciled and retried once on fresh fixtures. The extras retry completed English checks, then was cancelled again before deliberate SIGKILL; that crash-refusal scenario remains untested on the final JAR.
- Test worlds were loopback-only and disposable. The sole added exclusion was `plugins/spark/tmp/**`; existing runtime cache/libraries were deliberately retained. No real users, worlds or webhooks were involved.
- Actual-player GUI interactions, runtime player permission revocation, Windows, real webhooks, production scale and power loss were not tested. Source/unit tests are separate evidence.

The accompanying JSON (docs/test-evidence/runtime-summary.json) contains exact server hashes and check lists without raw logs, machine paths, PIDs or job identifiers. It records this same 1.2.0 run and, like this document, does not cover 1.3.0.
