# ライセンスと配布条件 / License review

## 選択したライセンス
ライセンス適用日: 2026-10-13。この改訂の独自プロジェクトコードは **GPL-3.0-only** とし、LICENSE.txt に改変していない GPLv3 本文、NOTICE.txt に適用範囲と注意事項を含めます。無料で提供します。1.3.0 の JAR/ヘルパーは、公開しているソースをそのままビルドしたもので、難読化していません（1.1.0〜1.2.1 は ProGuard で難読化していました）。GPLv3 は商用利用・有償再配布も許容するため、独自の非商用限定や転売禁止条件は追加しません。

配布物には対応するソース、Gradle wrapper とビルド設定、テスト、ドキュメントを同梱します。バイナリを再配布する場合は対応ソース提供など GPLv3 の条件を確認してください。ソースと配布ファイルについては [配布情報](../PUBLICATION-NOTES.md) を参照してください。

## 実際の依存関係
依存関係と座標の正本は THIRD-PARTY-NOTICES.md です。

- Spigot API: `org.spigotmc:spigot-api:26.2-R0.1-20260915.151451-13`（配布 JAR のコンパイル対象）と `org.spigotmc:spigot-api:26.3-R0.1-20261026.080323-10`（追加のコンパイルとリンク検査）。公式ライセンスは GPLv3。compileOnly として使用し、JAR へ同梱・シェーディングしません。
- Paper API: `io.papermc.paper:paper-api:26.2.build.135-stable`（Paper 用の実装 2 クラスのコンパイル対象）と `io.papermc.paper:paper-api:26.3.build.253-stable`（追加のコンパイルとリンク検査）。Paper の公式の通知は、Spigot・Bukkit・CraftBukkit から継承した GPLv3 を示しています。一部の貢献者が自身の貢献部分を MIT で許諾していますが、API 全体が MIT という意味ではありません。compileOnly として使用し、JAR へ同梱・シェーディングしません。
- Adventure（`net.kyori:adventure-api`）と Brigadier（`com.mojang:brigadier`）: Paper API が公開している型として、Paper 用の実装から参照します。どちらも MIT License です。同梱・シェーディングせず、Paper サーバーが提供します。
- Gradle wrapper: Apache-2.0。元の LICENSE/NOTICE を third-party に保存します。
- Java と Minecraft/Paper/Purpur/Spigot サーバーは別途必要です。テスト用サーバー本体、API JAR、依存キャッシュ、ワールドやバックアップをこのプロジェクトの配布物へ含めません。
- 1.3.0 のビルドに、難読化・縮小・バイトコード後処理のツールは含まれません。1.1.0〜1.2.1 は、ビルド専用の ProGuard 7.10.0 でシンボルを難読化していました。公開済みの各版は変更しません。

API を同梱しないことだけで結合の法的問題がすべて解消する、とは扱いません。GPLv3 を選び対応ソースを用意する方針ですが、他者の権利や地域ごとの法的判断まで保証するものではありません。

## 公式資料
- [Spigot API の公式ライセンス](https://hub.spigotmc.org/stash/projects/SPIGOT/repos/bukkit/browse/LICENCE.txt?at=a176036de14dbd873fcfed8f3111723cdb34e12c)
- [GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)
- [Gradle 9.8.1 LICENSE](https://github.com/gradle/gradle/blob/v9.8.1/LICENSE)
- [Spigot BuildTools](https://www.spigotmc.org/wiki/buildtools/)
- [Minecraft EULA](https://www.minecraft.net/en-us/eula) / [Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)
- Paper、Adventure、Brigadier の通知とライセンスの所在は THIRD-PARTY-NOTICES.md に記載しています。

無料化や Spigot 互換性の追加だけで、Minecraft 関連の利用条件や公開先規約が免除されるわけではありません。公開する場合は対象サイトの最新条件と対応ソースの入手経路を整えてください。PaperMC の公式プラグイン配布サイト Hangar は難読化したプラグインを認めていないため、1.3.0 で難読化を廃止しました（[OBFUSCATION.md](OBFUSCATION.md)）。公開は、各マーケットでの審査合格や法的認証を意味しません。
