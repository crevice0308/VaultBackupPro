// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
public final class PluginSettingsTest {
 static int checks=0;
 static String template;
 static Path base,root;
 static YamlConfiguration base() throws Exception {
  YamlConfiguration y=new YamlConfiguration(); y.loadFromString(template);
  y.set("storage.server-root",root.toString());
  y.set("storage.backup-directory",base.resolve("backups").toString());
  y.set("storage.state-directory",base.resolve("state").toString());
  y.set("restore.helper-jar",base.resolve("state/helper/vaultbackup-restore.jar").toString()); return y;
 }
 static void reject(String key,Object value) throws Exception {
  var y=base();y.set(key,value);boolean rejected=false;
  try{PluginSettings.from(y).options();}catch(IllegalArgumentException|java.io.IOException expected){rejected=true;}
  if(!rejected)throw new AssertionError("Accepted invalid "+key+"="+value);checks++;
 }
 static void rejectAll(Map<String,Object> values) throws Exception {
  var y=base();for(var entry:values.entrySet())y.set(entry.getKey(),entry.getValue());boolean rejected=false;
  try{PluginSettings.from(y).options();}catch(IllegalArgumentException|java.io.IOException expected){rejected=true;}
  if(!rejected)throw new AssertionError("Accepted invalid "+values);checks++;
 }
 public static void main(String[] args)throws Exception {
  template=Files.readString(Path.of(args[0]));base=Files.createTempDirectory("vaultbackup-config-test-");root=Files.createDirectory(base.resolve("server"));
  var s=PluginSettings.from(base());s.options();if(s.maxEntries()!=100000||s.intervalMinutes()!=60||s.discordEnabled()||!s.language().equals("ja_JP"))throw new AssertionError("defaults");checks++;
  if(!s.excludes().contains(".paper/console_history"))throw new AssertionError("default console history exclusion");checks++;
  Files.createDirectories(root.resolve(".paper"));
  Files.writeString(root.resolve(".paper/console_history"),"initial console history");
  Files.writeString(root.resolve(".paper/keep.dat"),"keep neighbouring data");
  var engine=new com.vaultbackup.core.BackupEngine(s.options());
  var record=engine.createBackup("default history exclusion",()->false,message->{
   try { Files.writeString(root.resolve(".paper/console_history"),"console history changed during backup "+message); }
   catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
  });
  try(var zip=new java.util.zip.ZipFile(record.zipPath().toFile())) {
   if(zip.getEntry(".paper/console_history")!=null)throw new AssertionError("history leaked");checks++;
   if(zip.getEntry(".paper/keep.dat")==null)throw new AssertionError("neighbour was excluded");checks++;
  }
  var explicit=base();explicit.set("backup.exclude",List.of("logs/**"));
  if(!PluginSettings.from(explicit).excludes().equals(List.of("logs/**")))throw new AssertionError("existing config exclusions changed");checks++;
  // The version gate is a minimum, not a list of exact builds: the plugin uses only public API.
  for(var e:Map.of("26.2","26.2","26.3-R0.1-SNAPSHOT","26.3","26.2.build.133-stable","26.2","26.3.build.253-stable","26.3",
    "26.3.1-R0.1-SNAPSHOT","26.3.1","26.4-R0.1-SNAPSHOT","26.4","27.1","27.1","1.21.11-R0.1-SNAPSHOT","1.21.11","26.3-rc-3.build.1-alpha","26.3").entrySet()) {
   if(!BukkitPlatform.minecraftVersion(e.getKey()).equals(e.getValue()))throw new AssertionError("version of "+e.getKey()+" read as "+BukkitPlatform.minecraftVersion(e.getKey()));checks++;
  }
  for(String v:Arrays.asList("","garbage","v26.3","26","26.x-R0.1","26.33333",null)) {
   if(!BukkitPlatform.minecraftVersion(v).isEmpty())throw new AssertionError("unrecognised version string accepted "+v);checks++;
  }
  String minimum=VaultBackupPlugin.MINIMUM_MINECRAFT,verified=VaultBackupPlugin.VERIFIED_MINECRAFT;
  for(String older:List.of("1.21.11","26.1","26.1.2")){if(BukkitPlatform.compareVersions(older,minimum)>=0)throw new AssertionError(older+" must be below the minimum");checks++;}
  for(String ok:List.of("26.2","26.2.0","26.2.1","26.3","26.4","27.1")){if(BukkitPlatform.compareVersions(ok,minimum)<0)throw new AssertionError(ok+" must satisfy the minimum");checks++;}
  if(BukkitPlatform.compareVersions("26.3.0",verified)!=0||BukkitPlatform.compareVersions("26.4",verified)<=0||BukkitPlatform.compareVersions("26.10","26.9")<=0)throw new AssertionError("numeric version order");checks++;
  // The shipped storage paths, untouched: two servers side by side in one folder get directories of their own.
  // (Up to 1.2.1 both got ../VaultBackup-backups and deleted each other's backups through retention.)
  Path network=Files.createDirectory(base.resolve("network")),lobby=Files.createDirectory(network.resolve("lobby")),survival=Files.createDirectory(network.resolve("survival"));
  YamlConfiguration shipped=new YamlConfiguration();shipped.loadFromString(template);
  shipped.set("storage.server-root",lobby.toString());var lobbySettings=PluginSettings.from(shipped);
  shipped.set("storage.server-root",survival.toString());var survivalSettings=PluginSettings.from(shipped);
  if(!lobbySettings.backupDirectory().equals(network.toRealPath().resolve("VaultBackup-lobby-backups"))||!lobbySettings.stateDirectory().equals(network.toRealPath().resolve("VaultBackup-lobby-state"))
    ||!lobbySettings.helperJar().equals(network.toRealPath().resolve("VaultBackup-lobby-state/helper/vaultbackup-restore.jar")))throw new AssertionError("shipped storage paths carry the server's folder name: "+lobbySettings);checks++;
  if(lobbySettings.backupDirectory().equals(survivalSettings.backupDirectory())||lobbySettings.stateDirectory().equals(survivalSettings.stateDirectory())||lobbySettings.helperJar().equals(survivalSettings.helperJar()))
   throw new AssertionError("two servers in one folder share storage with the shipped configuration");checks++;
  // A configuration written by an earlier version keeps exactly the directories it names.
  shipped.set("storage.backup-directory","../VaultBackup-backups");shipped.set("storage.state-directory","../VaultBackup-state");shipped.set("restore.helper-jar","../VaultBackup-state/helper/vaultbackup-restore.jar");
  var earlier=PluginSettings.from(shipped);
  if(!earlier.backupDirectory().equals(network.toRealPath().resolve("VaultBackup-backups"))||!earlier.stateDirectory().equals(network.toRealPath().resolve("VaultBackup-state")))throw new AssertionError("an existing configuration keeps its directories");checks++;
  // The placeholder can be used anywhere in a path, and a missing storage key falls back to the per-server name.
  shipped.set("storage.backup-directory",base.resolve("all-backups/{server}/archives").toString());shipped.set("storage.state-directory",null);shipped.set("restore.helper-jar",null);
  var placed=PluginSettings.from(shipped);
  if(!placed.backupDirectory().equals(base.toRealPath().resolve("all-backups/survival/archives"))||!placed.stateDirectory().equals(network.toRealPath().resolve("VaultBackup-survival-state")))throw new AssertionError("{server} placeholder: "+placed);checks++;
  // The automatic interval counts from the newest backup, also across restarts.
  java.time.Instant now=java.time.Instant.parse("2026-11-16T04:00:00Z");java.time.Duration settle=VaultBackupPlugin.AUTOMATIC_SETTLE;
  if(!VaultBackupPlugin.automaticDue(now,null,1440).equals(now.plus(settle)))throw new AssertionError("no backup yet: due once the server has settled");checks++;
  if(!VaultBackupPlugin.automaticDue(now,now.minus(java.time.Duration.ofHours(23)),1440).equals(now.plus(java.time.Duration.ofHours(1))))throw new AssertionError("due one interval after the newest backup");checks++;
  if(!VaultBackupPlugin.automaticDue(now,now.minus(java.time.Duration.ofHours(25)),1440).equals(now.plus(settle)))throw new AssertionError("an overdue backup waits only for the settle time");checks++;
  if(!VaultBackupPlugin.automaticDue(now,now.minus(java.time.Duration.ofMinutes(1439)),1440).equals(now.plus(settle)))throw new AssertionError("never during startup itself");checks++;
  if(!VaultBackupPlugin.automaticDue(now,now.plus(java.time.Duration.ofDays(3)),60).equals(now.plus(java.time.Duration.ofHours(1))))throw new AssertionError("a backup dated in the future postpones by one interval at most");checks++;
  // A server restarted every day with a daily interval used to never make an automatic backup.
  java.time.Instant newest=null;int made=0;
  for(int day=0;day<5;day++){java.time.Instant boot=now.plus(java.time.Duration.ofDays(day));java.time.Instant due=VaultBackupPlugin.automaticDue(boot,newest,1440);if(due.isBefore(boot.plus(java.time.Duration.ofHours(23)))){newest=due;made++;}}
  if(made!=5)throw new AssertionError("daily restarts with a daily interval made "+made+" backups in 5 days");checks++;
  if(BukkitPlatform.compareVersions(verified,minimum)<0)throw new AssertionError("verified version below minimum");checks++;
  // plugin.yml must agree with the code: same minimum version, and the command the code registers.
  var pluginYml=new YamlConfiguration();pluginYml.loadFromString(Files.readString(Path.of(args[1])));
  if(!minimum.equals(pluginYml.getString("api-version")))throw new AssertionError("api-version differs from MINIMUM_MINECRAFT");checks++;
  if(!"${version}".equals(pluginYml.getString("version")))throw new AssertionError("plugin.yml version must come from the build");checks++;
  if(!VaultBackupPlugin.class.getName().equals(pluginYml.getString("main")))throw new AssertionError("plugin.yml main");checks++;
  if(!"STARTUP".equals(pluginYml.getString("load")))throw new AssertionError("restore guard must start before worlds");checks++;
  if(pluginYml.contains("folia-supported"))throw new AssertionError("Folia must stay unsupported");checks++;
  var commandPermission=Arrays.asList(String.valueOf(pluginYml.getString("commands.backup.permission")).split(";"));
  for(String action:CommandActions.PERMISSIONS){
   if(!commandPermission.contains("vaultbackup."+action))throw new AssertionError("command permission lacks "+action);checks++;
   if(!pluginYml.contains("permissions.vaultbackup."+action))throw new AssertionError("undeclared permission "+action);checks++;
   if(!pluginYml.getBoolean("permissions.vaultbackup.admin.children.vaultbackup."+action))throw new AssertionError("admin lacks child "+action);checks++;
  }
  if(commandPermission.size()!=CommandActions.PERMISSIONS.size())throw new AssertionError("unexpected command permission entries");checks++;
  var legacy=base();legacy.set("language",null);if(!PluginSettings.from(legacy).language().equals("ja_JP"))throw new AssertionError("legacy locale");checks++;
  var english=base();english.set("language","en_US");if(!PluginSettings.from(english).language().equals("en_US"))throw new AssertionError("English locale");checks++;
  reject("language","fr_FR");reject("language",false);reject("language","");reject("language","ja");
  if(!Messages.keys("ja_JP").equals(Messages.keys("en_US")))throw new AssertionError("bundle parity");checks++;
  for(String key:Messages.keys("ja_JP")) {
   String ja=Messages.text("ja_JP",key),en=Messages.text("en_US",key);
   if(ja.isBlank()||en.isBlank())throw new AssertionError("empty message "+key);
   var pattern=java.util.regex.Pattern.compile("\\{[0-9]+}");
   Set<String> jpTokens=new TreeSet<>(),enTokens=new TreeSet<>();
   var jm=pattern.matcher(ja);while(jm.find())jpTokens.add(jm.group());
   var em=pattern.matcher(en);while(em.find())enTokens.add(em.group());
   if(!jpTokens.equals(enTokens))throw new AssertionError("placeholder mismatch "+key);checks++;
  }
  if(!Messages.text(null,"operation.idle").equals("待機中"))throw new AssertionError("fallback locale");checks++;
  if(!Messages.text("en_US","operation.idle").equals("Idle"))throw new AssertionError("English idle");checks++;
  if(!Messages.text("ja_JP","restore.state.WAITING_FOR_MANUAL_STOP").contains("手動停止待ち"))throw new AssertionError("manual stop state");checks++;
  if(!Messages.text("ja_JP","common.label","{0} $1 \\ data").equals("ラベル: {0} $1 \\ data"))throw new AssertionError("literal argument");checks++;
  if(!Messages.phase("ja_JP","Backing up world/region/r.0.0.mca").equals("バックアップ中: world/region/r.0.0.mca"))throw new AssertionError("phase path");checks++;
  if(!Messages.phase("en_US","Verifying new archive").equals("Verifying new archive"))throw new AssertionError("English phase");checks++;
  if(Messages.phase("ja_JP","Unrecognized upstream phase").equals("Unrecognized upstream phase"))throw new AssertionError("unknown phase localization");checks++;
  var badEnglish=base();badEnglish.set("language","en_US");badEnglish.set("automatic.interval-minutes",0);
  try{PluginSettings.from(badEnglish);throw new AssertionError("invalid English configuration accepted");}catch(IllegalArgumentException expected){if(!expected.getMessage().contains("must be between"))throw new AssertionError("English validation",expected);}checks++;
  var badJapanese=base();badJapanese.set("automatic.interval-minutes",0);
  try{PluginSettings.from(badJapanese);throw new AssertionError("invalid Japanese configuration accepted");}catch(IllegalArgumentException expected){if(!expected.getMessage().contains("指定してください"))throw new AssertionError("Japanese validation",expected);}checks++;
  reject("automatic.enabled","true");reject("automatic.interval-minutes",0);reject("automatic.interval-minutes",525601);
  reject("automatic.interval-minutes",1.5);reject("backup.compression-level",10);reject("limits.max-entries",1000001);
  reject("limits.max-entry-bytes",274877906945L);reject("limits.max-total-bytes",1099511627777L);
  reject("limits.max-compression-ratio",10000.01);
  var hardLimits=base();hardLimits.set("limits.max-entries",1000000);hardLimits.set("limits.max-entry-bytes",274877906944L);
  hardLimits.set("limits.max-total-bytes",1099511627776L);hardLimits.set("limits.max-compression-ratio",10000.0);
  PluginSettings.from(hardLimits).options();checks++;
  reject("limits.max-compression-ratio","bad");reject("limits.max-compression-ratio",Double.NaN);
  reject("limits.max-compression-ratio",0.9);reject("limits.max-total-bytes",1);reject("retention.max-count",-1);
  reject("retention.max-bytes",new java.math.BigInteger("18446744073709551616"));
  reject("backup.include",List.of());reject("backup.include",List.of(7));reject("backup.exclude",List.of(""));
  reject("storage.backup-directory",root.resolve("backups").toString());reject("storage.state-directory",base.resolve("backups/nested").toString());
  reject("restore.helper-jar",base.resolve("outside.jar").toString());
  for(Object legacyTtl:List.of(1,-1,"obsolete",true)) {
   var legacySettings=base();legacySettings.set("restore.confirmation-seconds",legacyTtl);
   if(PluginSettings.from(legacySettings).confirmationSeconds()!=60)throw new AssertionError("legacy TTL affects direct operation");checks++;
  }
  var missingRoot=base();Path absent=base.resolve("not-created-server");missingRoot.set("storage.server-root",absent.toString());
  if(!PluginSettings.from(missingRoot).serverRoot().equals(absent)||Files.exists(absent))throw new AssertionError("missing root not deferred safely");checks++;
  var changed=base();changed.set("automatic.enabled",false);changed.set("automatic.interval-minutes",5);changed.set("language","en_US");
  if(!VaultBackupPlugin.safeReservedSettings(s,PluginSettings.from(changed)))throw new AssertionError("safe reserved settings rejected");checks++;
  for(var entry:Map.<String,Object>of("retention.max-count",21,"retention.max-bytes",1L,"backup.compression-level",3,
   "backup.exclude",List.of("other/**"),"limits.max-entries",99999).entrySet()) {
   var structural=base();structural.set(entry.getKey(),entry.getValue());
   if(VaultBackupPlugin.safeReservedSettings(s,PluginSettings.from(structural)))throw new AssertionError("structural reserved change accepted "+entry.getKey());checks++;
  }
  reject("notifications.discord.enabled",true);
  var malformedWebhook=base();malformedWebhook.set("notifications.discord.webhook-url","https://discord.com/api/webhooks/123/secret bad");
  try{PluginSettings.from(malformedWebhook);throw new AssertionError("malformed credential accepted");}catch(IllegalArgumentException expected){if(expected.getMessage().contains("secret"))throw new AssertionError("credential leaked",expected);}checks++;
  reject("notifications.discord.webhook-url","http://discord.com/api/webhooks/123/token");
  reject("notifications.discord.webhook-url","https://evil.test/api/webhooks/123/token");
  reject("notifications.discord.webhook-url","https://discord.com/api/webhooks/123/token?q=1");
  reject("notifications.discord.webhook-url","https://discord.com:444/api/webhooks/123/token");
  var y=base();y.set("retention.max-count",0);y.set("retention.max-bytes",0L);var unlimited=PluginSettings.from(y);if(unlimited.retentionCount()!=0||unlimited.retentionBytes()!=0)throw new AssertionError();checks++;
  y=base();y.set("notifications.discord.enabled",true);y.set("notifications.discord.webhook-url","https://discord.com/api/webhooks/123/fake_unit_test_token");PluginSettings.from(y);checks++;
  Path link=base.resolve("linked");Files.createSymbolicLink(link,root);reject("storage.backup-directory",link.resolve("nested").toString());
  // Storage that contains server-root is a configuration error. Before 1.2.1 it passed parsing and
  // then failed the restore guard as a startup conflict, which stops the whole server on every start.
  Path elsewhere=Files.createTempDirectory("vaultbackup-config-elsewhere-");
  rejectAll(Map.<String,Object>of("storage.backup-directory",base.toString(),"storage.state-directory",elsewhere.resolve("state").toString(),
   "restore.helper-jar",elsewhere.resolve("state/helper/vaultbackup-restore.jar").toString()));
  rejectAll(Map.<String,Object>of("storage.state-directory",base.toString(),"storage.backup-directory",elsewhere.resolve("backups").toString(),
   "restore.helper-jar",base.resolve("helper/vaultbackup-restore.jar").toString()));
  var filesystemRoot=base();filesystemRoot.set("storage.server-root",root.getRoot().toString());
  try{PluginSettings.from(filesystemRoot);throw new AssertionError("filesystem root accepted as server-root");}
  catch(IllegalArgumentException expected){if(!expected.getMessage().equals(Messages.text("ja_JP","settings.rootparent")))throw new AssertionError("filesystem root diagnostic",expected);}checks++;
  // The helper path is overwritten on install: never the state directory, a journal, or a non-JAR file.
  reject("restore.helper-jar",base.resolve("state").toString());
  reject("restore.helper-jar",base.resolve("state/restore-jobs/vaultbackup-restore.jar").toString());
  reject("restore.helper-jar",base.resolve("state/active-restore.properties").toString());
  var upperJar=base();upperJar.set("restore.helper-jar",base.resolve("state/tools/VaultBackup-Restore.JAR").toString());PluginSettings.from(upperJar);checks++;
  // Discord payloads must stay valid JSON for any text and within Discord's content limit.
  String json=DiscordNotifier.payload("tab\tquote\" back\\ nl\n cr\r nul\u0000 del\u007f ls\u2028 日本語");
  if(!json.equals("{\"content\":\"VaultBackup Pro: tab\\tquote\\\" back\\\\ nl\\n cr\\r nul\\u0000 del\\u007f ls\\u2028 日本語\",\"allowed_mentions\":{\"parse\":[]}}"))
   throw new AssertionError("Discord JSON encoding: "+json);checks++;
  if(json.chars().anyMatch(c->c<0x20))throw new AssertionError("raw control character in Discord JSON");checks++;
  String longJson=DiscordNotifier.payload("x".repeat(5000));
  if(longJson.length()>2000||!longJson.contains("x…\""))throw new AssertionError("Discord content limit");checks++;
  String emoji=DiscordNotifier.payload("y".repeat(DiscordNotifier.MAX_CONTENT-"VaultBackup Pro: ".length()-1)+"😀😀");
  if(emoji.chars().anyMatch(c->Character.isHighSurrogate((char)c)))throw new AssertionError("truncation split a surrogate pair");checks++;
  System.out.println("PASS "+checks+" configuration validations (no webhook requests sent)");
 }
}
