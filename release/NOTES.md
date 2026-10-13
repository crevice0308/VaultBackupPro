# VaultBackup Pro 1.0.0

無料・GPLv3（GPL-3.0-only）の Minecraft サーバーバックアッププラグインです。

- Paper / Purpur / Spigot 26.2・26.3、Java 25
- 日本語標準、en_US へ切替可能
- 復元を予約してから自分で stop。対象 JVM の完全終了後に復元し、最後は手動起動
- 停止前に確定予約を取消可能。取消確認済みになるまでサーバーを止めないでください
- 全体 ZIP・SHA-256 検証・自動保存・世代保持・元データの rollback 保持

520 件の fixture assertions と上記 6 サーバービルドでバックアップ・手動停止・復元・再起動を確認しています。Windows の今回版と実 Minecraft クライアントの GUI 操作は未検証です。Purpur 26.3 は上流で experimental です。ライブバックアップは外部 DB や任意の同時書込みの原子性を保証しません。稼働中のファイル変化はその回のバックアップを拒否する場合があります。

ホストの自動再起動を止め、ヘルパーの SUCCESS とプロセス終了を確認してから起動してください。通常停止の記録は手入力の stop や全保存完了の証明ではなく、同じ停止経路を通る障害終了も確定予約を適用し得ます。

既存 JAR と二重配置せず、plugins には 1 個だけ置いてください。JAR 自体を展開しないでください。詳細は同梱 INSTALL.md、TEST-RESULTS.md、KNOWN-LIMITATIONS.md。

Public packages omit local execution logs and temporary fixtures. Source and build instructions are included. Embedded pre-publication revision metadata is retained to preserve the exact tested binary; the official release name is VaultBackup Pro 1.0.0.

Plugin SHA-256: `1abf09736a5ea4bf1b363259a744c9ff050b7d73548db353e7066f357855c7ab`

This is free software under GPL-3.0-only, without warranty. GPLv3 permits commercial use and paid redistribution under its terms. Not an official Minecraft product; not approved by or associated with Mojang or Microsoft.
