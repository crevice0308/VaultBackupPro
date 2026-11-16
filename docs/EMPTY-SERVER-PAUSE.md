# Empty-server pause investigation

The message `Server empty for 60 seconds, pausing` is informational; by itself it is not a backup error. Paper's Bukkit schedulers continue advancing while normal world simulation is paused. Do not move Bukkit world saves to an asynchronous thread merely to address this message.

Status for 1.3.0: this investigation was carried out on 2026-10-13 with the published 1.0.0 JAR and was not repeated. The public releases 1.0.0 to 1.2.1 saved through the server-internal `saveEverything(true,true,true)` call examined below. 1.3.0 no longer uses server internals: it runs the vanilla `minecraft:save-all flush` command and confirms the save from the per-world save events. How that command behaves on a paused, empty server has not been tested with 1.3.0, and no real-server test was run for 1.3.0 at all.

## Sources and scope

- [Paper server properties](https://docs.papermc.io/paper/reference/server-properties/#pause-when-empty-seconds)
- [Paper's explanation of scheduler behavior](https://papermc.io/news/1-21-3/)

Direct inspection of Paper 26.3 build 178 confirmed that its paused tick path continues the Bukkit scheduler heartbeat, and the explicit `saveEverything(true,true,true)` operation used by VaultBackup at the time (releases up to 1.2.1) does not skip saving because the server is paused. Paper's default is -1 (disabled); the corresponding vanilla server defaults to 60 seconds. Existing server.properties values are not evidence of a plugin defect.

## Disposable A/B test, 2026-10-13

Using the unchanged published VaultBackup Pro 1.0.0 JAR, Paper 26.3 build 178, Java 25 and two isolated generated fixtures:

- pause-when-empty-seconds=5 and -1 each completed two automatic backups during 145 command-free wall-clock seconds, then one manually requested backup.
- In the paused fixture, world game time stayed constant while scheduler callbacks and server ticks advanced.
- Console status, list and manual backup remained responsive without resuming world game time.
- All six completed ZIPs passed CRC and embedded plugin-hash checks.
- Both JVMs stopped normally; source baselines and existing backup/state data were unchanged.

Only console logging was used for the probe, and temporary Spark profiler output was excluded in these fixtures to avoid unrelated concurrent-writer refusals. Cleanup interrupted an additional scheduled attempt, which correctly reported cancellation. Player-join resume and the exact 60-second threshold were not tested. These findings do not establish the cause of other reported symptoms and are not a 1.1.0 regression-test substitute.

VaultBackup's default automatic interval is 60 minutes. Up to 1.2.1 the interval was counted from each server start, so no backup ran right after startup, and a server restarted more often than the interval never made an automatic backup. Since 1.3.0 the interval is counted from the newest backup, also across restarts; without any backup, or when one is already overdue at startup, the first automatic backup runs about two minutes after startup. Check `/backup status`, which shows the next scheduled attempt, and the configured interval before diagnosing a missing scheduled run. User-provided logs and identifying information are not included in this public summary.
