# コマンド / Commands — 1.3.0

管理者権限が必要です。プレイヤーの `/backup` は GUI、コンソールではヘルプを表示します。コマンド名は日本語・英語で共通です。`/vaultbackup` は `/backup` の別名です。

実行できるのはプレイヤー、コンソール、RCON だけです。コマンドブロックなど、それ以外からの実行は権限にかかわらず拒否します。VaultBackup の権限を 1 つも持たない送信者にはコマンド自体を表示せず、ヘルプと補完には使用できる項目だけを表示します（PERMISSIONS.md）。

| コマンド | 動作 |
|---|---|
| `/backup create [label]` | バックアップを作成。label は半角英数字・ピリオド・下線・ハイフンの 1〜64 文字 |
| `/backup list [page]` | 一覧。新規・変更されたアーカイブだけを検証。処理中はキャッシュ済み一覧を表示 |
| `/backup verify` | すべてのアーカイブを読み直して再検証 |
| `/backup status` | 処理状況、予約、保存件数、次の自動実行予定 |
| `/backup delete <id>` | 対象を追加確認なしで削除。取り消せません。検証に失敗したアーカイブも ID 指定で削除できます |
| `/backup restore <id>` | 検証後、次回の通常停止に適用する復元を直接予約 |
| `/backup restore cancel` | 停止前の予約（または検証中の復元）を取消。完了表示まで停止しない |
| `/backup auto on` / `off` | 自動実行の設定を切替 |
| `/backup interval <minutes>` | 自動間隔を設定（1〜525600 分） |
| `/backup retention count <number>` | 保持件数。0 は件数制限なし |
| `/backup retention bytes <number>` | 保持容量。0 は容量制限なし |
| `/backup reload` | 設定再読込 |
| `/backup help` | ヘルプ |

確認用の `confirm` サブコマンドは不要です。古い手順の confirm を追加せず、上記の構文を使用してください。

GUI は一覧から対象の詳細を開き、明示された削除・復元操作ボタンで実行します。一覧の選択だけでは削除・復元しません。操作の説明に不可逆削除と次回停止時の適用を表示しますが、追加の確認画面はありません。一覧と詳細には、コピー中に変化し続けたファイルの件数も表示します。一覧の再検証ボタンは、`/backup verify` と同じくすべてのアーカイブを読み直します。

`/backup list` は、前回の検証以降に追加・変更されたアーカイブだけを検証し、変わっていないものは読み直しません。各行には ID、作成日時、圧縮容量、ラベルを表示し、コピー中に変化し続けたファイルがあればその件数も表示します。検証に失敗したアーカイブ（壊れている、未完成、設定した上限を超えている、付属情報がない）は、検証済みの一覧には含めず、件数・ID・理由を別に表示します。理由は英語の診断文です。これらは復元には使えませんが、`/backup delete <ID>` で削除できます（GUI には件数と ID だけを表示します）。

すべてのアーカイブを最初から読み直すのは、サーバーの起動時、`/backup verify`、GUI の再検証ボタンです。復元を予約するときは、対象のアーカイブを必ず全体検証します。他の処理の実行中に `/backup verify` を実行しても再検証は行われません。処理が終わってから実行してください。

一覧・状況・ヘルプは処理中も利用できます。書込み処理は重複させず、復元予約対象の削除は拒否します。予約中でも自動実行の ON/OFF・間隔や表示言語は変更可能ですが、実際の自動バックアップは予約中は実行しません。保存先など処理の前提を変える設定は予約の取消後に変更してください。

`auto`・`interval`・`retention` は、変更した値を config.yml に書き込みます。config.yml に、まだ反映されていない手編集がある場合は拒否します。先に `/backup reload` で反映（または修正）してください。他の処理の実行中も設定は変更できません。

バックアップの作成、復元の要求・予約・取り消し、削除、設定の変更と再読み込みは、実行者の名前とともにサーバーログへ記録します。GUI からの操作も同じです。

English: delete runs immediately without an extra confirmation. Restore verifies and schedules the selected archive for the next normal shutdown; it does not stop the server. Cancel an unwanted reservation before stopping and wait for acknowledged cancellation. Listing/status/help remain available during work; cached lists are identified. Actual conflicting writes remain serialized. `/backup list` verifies only archives that are new or changed, and `/backup verify` re-reads every archive. Archives that failed verification are listed separately and can be removed with `/backup delete <id>`. Only players, the console and RCON can run `/backup`; command blocks and similar sources are refused, and a sender without any VaultBackup permission does not see the command. Actions are written to the server log with the sender's name. `auto`, `interval` and `retention` are refused while config.yml holds hand edits that have not been applied; run `/backup reload` first.

新規作成は追加確認やクールダウンなしで受付します。開始案内は保存完了の意味ではありません。ワールド保存と ZIP 作成に必要な時間はかかり、進行中は `/backup status` で確認できます。コピー中に変化し続けたファイルがあった場合は、作成後に件数を表示し、対象のパスをサーバーログに出力します（1 回のバックアップにつき最大 20 件）。バックアップできないパスがある場合や保存先の空き容量が足りない場合は、作成せずに理由を表示します。
