# VaultBackup Pro 1.1.0 — Free, GPLv3 server backups

VaultBackup Pro is provided without charge with its complete project source under GNU GPL version 3. This release provides Japanese/English interface support and a manually controlled restore reservation workflow.

## What it does
- Full eligible server-root ZIP backups, including worlds, player files, plugins and settings
- Whole-archive and per-entry SHA-256 verification, configurable retention and scheduled backups
- Administrator inventory menus, console/player commands and granular permissions
- Confirm a restore reservation while the server keeps running; cancel before shutdown or stop the server normally when ready
- Independent helper waits for the recorded JVM to exit, verifies staged data, preserves the old root and records the result
- Manual startup after restoration; no automatic restart
- Japanese default with an English option; optional Discord notifications disabled by default

## Supported targets
Minecraft Java 26.2 / 26.3 on Paper, Purpur and Spigot, Java 25. Consult the exact-build test report; Purpur 26.3 is experimental upstream. The official BuildTools-generated Spigot server binaries used for tests are not distributed here.

## Boundaries
A live backup is not an atomic snapshot of independent plugin databases or external writers. A confirmed reservation applies after the next eligible server shutdown path and actual JVM exit; a fault that enters the server shutdown path can also qualify. Abrupt termination without the required marker fails closed. Disable automated stops/restarts and wait for helper completion before restarting.

GUI client interaction, Windows revision execution and real external webhook delivery are not implied by headless Linux tests. Keep independent backups and run your own restore rehearsal.

## Source and license
The source archive includes build scripts, tests, documentation and license notices. Default plugin and helper JARs are not obfuscated. GPLv3 requires applicable corresponding source and license notices when distributing covered binaries/modified versions; it allows commercial use and paid redistribution. There is no custom DRM, activation server or payment requirement.

See [publication notes](PUBLICATION-NOTES.md) for the source repository and release filenames. Minecraft and third-party platform terms still apply; passing tests is not marketplace approval or a guarantee against data loss.
