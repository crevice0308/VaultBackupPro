# コマンド / Commands — 1.2.0

管理者権限が必要です。プレイヤーの `/backup` は GUI、コンソールではヘルプを表示します。コマンド名は日本語・英語で共通です。

| コマンド | 動作 |
|---|---|
| `/backup create [label]` | バックアップを作成 |
| `/backup list [page]` | 一覧。処理中はキャッシュ済み一覧を表示 |
| `/backup status` | 処理状況、予約、保存件数 |
| `/backup delete <id>` | 対象を追加確認なしで削除。取り消せません |
| `/backup restore <id>` | 検証後、次回の通常停止に適用する復元を直接予約 |
| `/backup restore cancel` | 停止前の予約を取消。完了表示まで停止しない |
| `/backup auto on` / `off` | 自動実行の設定を切替 |
| `/backup interval <minutes>` | 自動間隔を設定 |
| `/backup retention count <number>` | 保持件数。0 は件数制限なし |
| `/backup retention bytes <number>` | 保持容量。0 は容量制限なし |
| `/backup reload` | 設定再読込 |
| `/backup help` | ヘルプ |

確認用の `confirm` サブコマンドは不要です。古い手順の confirm を追加せず、上記の構文を使用してください。

GUI は一覧から対象の詳細を開き、明示された削除・復元操作ボタンで実行します。一覧の選択だけでは削除・復元しません。操作の説明に不可逆削除と次回停止時の適用を表示しますが、追加の確認画面はありません。

一覧・状況・ヘルプは処理中も利用できます。書込み処理は重複させず、復元予約対象の削除は拒否します。予約中でも自動実行の ON/OFF・間隔や表示言語は変更可能ですが、実際の自動バックアップは予約中は延期されます。保存先など処理の前提を変える設定は予約の取消後に変更してください。

English: delete runs immediately without an extra confirmation. Restore verifies and schedules the selected archive for the next normal shutdown; it does not stop the server. Cancel an unwanted reservation before stopping and wait for acknowledged cancellation. Listing/status/help remain available during work; cached lists are identified. Actual conflicting writes remain serialized.

新規作成は追加確認やクールダウンなしで受付します。開始案内は保存完了の意味ではありません。ワールド保存と ZIP 作成に必要な時間はかかり、進行中は `/backup status` で確認できます。
