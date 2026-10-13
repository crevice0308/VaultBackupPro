// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupRecord;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BackupCommands implements CommandExecutor, TabCompleter {
    private final VaultBackupPlugin plugin;
    BackupCommands(VaultBackupPlugin plugin) { this.plugin = plugin; }
    private String t(String key, Object... args) { return plugin.text(key, args); }
    private IllegalArgumentException invalid(String key, Object... args) { return new IllegalArgumentException(t(key,args)); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) plugin.menus().openMain(player);
            else help(sender);
            return true;
        }
        try {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "help" -> help(sender);
                case "create" -> {
                    if (args.length > 2) throw invalid("command.usage", "/backup create [label]");
                    String backupLabel = args.length == 2 ? args[1] : "manual";
                    if (!backupLabel.matches("[A-Za-z0-9._-]{1,64}")) throw invalid("command.label");
                    plugin.create(sender,backupLabel);
                }
                case "list" -> {
                    int page = args.length == 2 ? Integer.parseInt(args[1]) : 1;
                    if (args.length > 2 || page < 1) throw invalid("command.usage", "/backup list [page >= 1]");
                    plugin.refresh(sender, () -> {
                        List<BackupRecord> records = plugin.backups();
                        int pages = Math.max(1,(records.size()+9)/10);
                        if (page > pages) { plugin.tell(sender,t("list.page",pages),false); return; }
                        plugin.tell(sender,t("list.title",page,pages),true);
                        if (records.isEmpty()) plugin.tell(sender,t("list.empty"),true);
                        records.stream().skip((page-1L)*10).limit(10).forEach(r -> plugin.tell(sender,r.id() + " | " + r.createdAt() + " | " + VaultBackupPlugin.humanBytes(r.compressedBytes()) + " | " + r.label(),true));
                    });
                }
                case "restore" -> {
                    if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) plugin.cancelRestore(sender);
                    else if (args.length == 2) plugin.requestRestore(sender,args[1]);
                    else throw invalid("command.usage", "/backup restore <id> | /backup restore cancel");
                }
                case "delete" -> {
                    if (args.length == 2) plugin.delete(sender,args[1]);
                    else throw invalid("command.usage", "/backup delete <id>");
                }
                case "status" -> { exact(args,1,"/backup status"); plugin.status(sender); }
                case "reload" -> { exact(args,1,"/backup reload"); plugin.reloadSettings(sender); }
                case "interval" -> {
                    exact(args,2,"/backup interval <minutes>");
                    long minutes = Long.parseLong(args[1]);
                    if (minutes < 1 || minutes > 525600) throw invalid("command.interval");
                    plugin.setting(sender,"automatic.interval-minutes",minutes);
                }
                case "auto" -> {
                    exact(args,2,"/backup auto <on|off>");
                    if (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off")) throw invalid("command.onoff");
                    plugin.setting(sender,"automatic.enabled",args[1].equalsIgnoreCase("on"));
                }
                case "retention" -> {
                    exact(args,3,"/backup retention <count|bytes> <number>");
                    long value = Long.parseLong(args[2]);
                    if (value < 0) throw invalid("command.retention.negative");
                    if (args[1].equalsIgnoreCase("count")) {
                        if (value > 1000000) throw invalid("command.retention.maximum");
                        plugin.setting(sender,"retention.max-count",value);
                    } else if (args[1].equalsIgnoreCase("bytes")) plugin.setting(sender,"retention.max-bytes",value);
                    else throw invalid("command.retention.type");
                }
                default -> help(sender);
            }
        } catch (NumberFormatException error) { plugin.tell(sender,t("command.number"),false); }
        catch (IllegalArgumentException error) { plugin.tell(sender,error.getMessage(),false); }
        return true;
    }
    private void exact(String[] args, int size, String usage) {
        if (args.length != size) throw invalid("command.usage", usage);
    }
    private void help(CommandSender sender) {
        plugin.tell(sender,t("command.help.title"),true);
        for (String key : List.of("gui","create","list","restore","cancel","delete","status","reload","auto","interval","retention"))
            plugin.tell(sender,t("command.help."+key),true);
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            choices.add("help");
            for (String sub : List.of("create","list","restore","delete","status","reload"))
                if (sender.hasPermission("vaultbackup."+sub)) choices.add(sub);
            if (sender.hasPermission("vaultbackup.configure")) choices.addAll(List.of("auto","interval","retention"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ((sub.equals("restore") || sub.equals("delete")) && sender.hasPermission("vaultbackup."+sub)) {
                choices.addAll(plugin.backups().stream().map(BackupRecord::id).toList());
                if (sub.equals("restore")) choices.add("cancel");
            }
            if (sender.hasPermission("vaultbackup.configure")) {
                if (sub.equals("auto")) choices.addAll(List.of("on","off"));
                if (sub.equals("interval")) choices.addAll(List.of("5","15","30","60","180","360","720","1440"));
                if (sub.equals("retention")) choices.addAll(List.of("count","bytes"));
            }
        }
        String prefix = args[args.length-1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(100).toList();
    }
}
