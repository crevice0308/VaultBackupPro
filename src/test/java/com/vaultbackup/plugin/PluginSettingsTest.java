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
  for(String v:List.of("26.2","26.3","26.2-R0.1-SNAPSHOT","26.3-R0.1-SNAPSHOT","26.2.build.133-stable","26.3.build.28-stable","26.2.build.1")) {
   if(ServerVersions.minecraftVersion(v).isEmpty())throw new AssertionError("supported version rejected "+v);checks++;
  }
  for(String v:List.of("26.2.1","26.3.1-R0.1-SNAPSHOT","26.4-R0.1-SNAPSHOT","26.2.build.x-stable","26.2-malicious","1.21.11-R0.1-SNAPSHOT","")) {
   if(!ServerVersions.minecraftVersion(v).isEmpty())throw new AssertionError("unsupported version accepted "+v);checks++;
  }
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
  System.out.println("PASS "+checks+" configuration validations (no webhook requests sent)");
 }
}
