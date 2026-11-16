// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Spigot command front-end: the command declared in plugin.yml, handled by a CommandExecutor.
 * Paper registers a Brigadier command instead (PaperCommands) and never uses this class.
 */
final class BackupCommands implements CommandExecutor, TabCompleter {
    private final CommandActions actions;

    BackupCommands(CommandActions actions) { this.actions = actions; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { actions.open(sender); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "help" -> actions.help(sender);
                case "create" -> {
                    if (args.length > 2) actions.usage(sender, "/backup create [label]");
                    else actions.create(sender, args.length == 2 ? args[1] : null);
                }
                case "list" -> {
                    if (args.length > 2) actions.usage(sender, "/backup list [page >= 1]");
                    else actions.list(sender, args.length == 2 ? Integer.parseInt(args[1]) : 1);
                }
                case "verify" -> { if (exact(sender, args, 1, "/backup verify")) actions.verify(sender); }
                case "restore" -> {
                    if (args.length != 2) actions.usage(sender, "/backup restore <id> | /backup restore cancel");
                    else if (args[1].equalsIgnoreCase("cancel")) actions.cancelRestore(sender);
                    else actions.restore(sender, args[1]);
                }
                case "delete" -> { if (exact(sender, args, 2, "/backup delete <id>")) actions.delete(sender, args[1]); }
                case "status" -> { if (exact(sender, args, 1, "/backup status")) actions.status(sender); }
                case "reload" -> { if (exact(sender, args, 1, "/backup reload")) actions.reload(sender); }
                case "interval" -> { if (exact(sender, args, 2, "/backup interval <minutes>")) actions.interval(sender, Long.parseLong(args[1])); }
                case "auto" -> {
                    if (!exact(sender, args, 2, "/backup auto <on|off>")) break;
                    boolean on = args[1].equalsIgnoreCase("on");
                    if (!on && !args[1].equalsIgnoreCase("off")) actions.plugin().tell(sender, actions.plugin().text("command.onoff"), false);
                    else actions.auto(sender, on);
                }
                case "retention" -> {
                    if (!exact(sender, args, 3, "/backup retention <count|bytes> <number>")) break;
                    long value = Long.parseLong(args[2]);
                    if (args[1].equalsIgnoreCase("count")) actions.retentionCount(sender, value);
                    else if (args[1].equalsIgnoreCase("bytes")) actions.retentionBytes(sender, value);
                    else actions.plugin().tell(sender, actions.plugin().text("command.retention.type"), false);
                }
                default -> actions.help(sender);
            }
        } catch (NumberFormatException notANumber) {
            actions.notANumber(sender);
        }
        return true;
    }

    private boolean exact(CommandSender sender, String[] args, int size, String usage) {
        if (args.length == size) return true;
        actions.usage(sender, usage);
        return false;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            if (CommandActions.mayUseAny(sender)) choices.add("help");
            for (String sub : List.of("create", "list", "restore", "delete", "status", "reload"))
                if (CommandActions.may(sender, sub)) choices.add(sub);
            if (CommandActions.may(sender, "list")) choices.add("verify");
            if (CommandActions.may(sender, "configure")) choices.addAll(List.of("auto", "interval", "retention"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("restore") || sub.equals("delete")) {
                choices.addAll(actions.backupIds(sender, sub));
                if (sub.equals("restore") && CommandActions.may(sender, "restore")) choices.add("cancel");
            }
            if (CommandActions.may(sender, "configure")) {
                if (sub.equals("auto")) choices.addAll(List.of("on", "off"));
                if (sub.equals("interval")) CommandActions.INTERVAL_CHOICES.forEach(minutes -> choices.add(Long.toString(minutes)));
                if (sub.equals("retention")) choices.addAll(List.of("count", "bytes"));
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(100).toList();
    }
}
