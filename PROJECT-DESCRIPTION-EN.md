# VaultBackup Pro 1.3.0 — Free, GPLv3 server backups

VaultBackup Pro is provided without charge with its complete project source under GNU GPL version 3. This release provides Japanese/English interface support and a manually controlled restore reservation workflow. It uses only the public server APIs, and its JARs are built directly from the published source without obfuscation. 1.3.0 is a prerelease that has not been tested on a real server.

## What it does
- Full eligible server-root ZIP backups, including worlds, player files, plugins and settings
- Whole-archive and per-entry SHA-256 verification, configurable retention and scheduled backups; `/backup verify` re-reads every archive
- Backups of a running server continue when files change: a file that keeps changing while it is copied is stored as last read, and the number of such files is reported
- Paths that cannot be archived are reported together, and free space is checked before anything is written
- Administrator inventory menus, console/player commands and granular permissions
- Submit a restore reservation directly while the server keeps running; cancel before shutdown or stop the server normally when ready
- Independent helper waits for the recorded JVM to exit, verifies staged data, preserves the old root and records the result
- Manual startup after restoration; no automatic restart
- Japanese default with an English option; optional Discord notifications disabled by default

## Supported targets
Paper, Purpur and Spigot for Minecraft Java 26.2 and newer, Java 25. Folia and other regionized servers are not supported. On Paper and Purpur the plugin uses Brigadier commands, Adventure components and the server's own stop indicator; on Spigot it uses plugin.yml commands with a CommandExecutor and ChatColor. The build compiles and link-checks the plugin against the Spigot API 26.2 and 26.3 and the Paper API 26.2 and 26.3; versions newer than 26.3 run with a warning. PaperMC ended support for 26.2 on 2026-11-03, so 26.3 is recommended. Server binaries are not distributed here.

No real-server test was run for 1.3.0, because starting a server requires accepting the Minecraft EULA. The real-server results in docs/RUNTIME-TEST-RESULTS.md and docs/test-evidence belong to earlier, obfuscated builds and do not cover this release. Consult TEST-RESULTS.md for the build and isolated test results.

## Boundaries
Deletion executes without a second confirmation and may remove the final backup. Automatic retention may also leave zero archives. Deleted backups cannot be recovered through the plugin.
A live backup is not an atomic snapshot of independent plugin databases or external writers. An accepted reservation applies after the next eligible server shutdown path and actual JVM exit; a fault that enters the server shutdown path can also qualify. Abrupt termination without the required marker fails closed. Disable automated stops/restarts and wait for helper completion before restarting.

Spigot has no API that tells whether the server is stopping. There, a JVM exit within 10 minutes after the plugin was disabled counts as a normal stop, so a reserved restore can also be applied when only the plugin was disabled and the server then terminated abnormally within those 10 minutes. `/reload` or a plugin reload cancels a reservation on every server. A restore cannot be reserved when the server root is a mount point or its parent folder is not writable.

GUI client interaction, Spigot at run time, Windows execution and real external webhook delivery are not verified; they are not implied by headless Linux tests either. Keep independent backups and run your own restore rehearsal.

## Source and license
The source archive includes build scripts, tests, documentation and license notices. The plugin and helper JARs are built directly from that source and are not obfuscated; releases 1.1.0 to 1.2.1 were obfuscated, and docs/OBFUSCATION.md explains why that was dropped. GPLv3 requires applicable corresponding source and license notices when distributing covered binaries/modified versions; it allows commercial use and paid redistribution. There is no custom DRM, activation server or payment requirement.

See [publication notes](PUBLICATION-NOTES.md) for the source repository and release filenames. Minecraft and third-party platform terms still apply; passing tests is not marketplace approval or a guarantee against data loss.
