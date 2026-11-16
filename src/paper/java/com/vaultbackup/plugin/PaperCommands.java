// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Paper command front-end: a Brigadier command tree registered through the lifecycle event
 * manager, which Paper recommends over Bukkit's CommandExecutor (marked obsolete in 26.3).
 * The handler runs again whenever the server rebuilds its commands, for example on /reload.
 *
 * plugin.yml still declares the command for Spigot. Paper turns that declaration into a Bukkit
 * command first; registering the same labels here replaces it, so players get one command.
 */
final class PaperCommands {
    /** Every label declared in plugin.yml (name and aliases) must be registered here as well. */
    static final List<String> LABELS = List.of("backup", "vaultbackup");

    private final VaultBackupPlugin plugin;
    private final CommandActions actions;

    PaperCommands(VaultBackupPlugin plugin, CommandActions actions) {
        this.plugin = plugin;
        this.actions = actions;
    }

    static void register(VaultBackupPlugin plugin, CommandActions actions) {
        PaperCommands commands = new PaperCommands(plugin, actions);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            String description = plugin.text("command.description");
            for (String label : LABELS) event.registrar().register(commands.tree(label).build(), description);
        });
    }

    private static Predicate<CommandSourceStack> permitted(String action) {
        return source -> CommandActions.may(source.getSender(), action);
    }

    /** Runs an action for the command's sender, unless the plugin was disabled after registration. */
    private Command<CommandSourceStack> run(Consumer<CommandSender> action) {
        return context -> {
            if (plugin.isEnabled()) action.accept(context.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        };
    }

    private Command<CommandSourceStack> run(BiConsumer<CommandSender, CommandContext<CommandSourceStack>> action) {
        return context -> {
            if (plugin.isEnabled()) action.accept(context.getSource().getSender(), context);
            return Command.SINGLE_SUCCESS;
        };
    }

    private SuggestionProvider<CommandSourceStack> backupIds(String action, boolean withCancel) {
        return (context, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            CommandSender sender = context.getSource().getSender();
            if (plugin.isEnabled()) for (String id : actions.backupIds(sender, action)) if (id.toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(id);
            if (withCancel && "cancel".startsWith(typed) && CommandActions.may(sender, action)) builder.suggest("cancel");
            return builder.buildFuture();
        };
    }

    LiteralArgumentBuilder<CommandSourceStack> tree(String label) {
        SuggestionProvider<CommandSourceStack> intervals = (context, builder) -> {
            for (long minutes : CommandActions.INTERVAL_CHOICES) if (Long.toString(minutes).startsWith(builder.getRemaining())) builder.suggest(Long.toString(minutes));
            return builder.buildFuture();
        };
        return Commands.literal(label)
                // Senders without any VaultBackup permission do not see the command at all.
                .requires(source -> CommandActions.mayUseAny(source.getSender()))
                .executes(run(actions::open))
                .then(Commands.literal("help").executes(run(actions::help)))
                .then(Commands.literal("create").requires(permitted("create"))
                        .executes(run(sender -> actions.create(sender, null)))
                        .then(Commands.argument("label", StringArgumentType.word())
                                .executes(run((sender, context) -> actions.create(sender, StringArgumentType.getString(context, "label"))))))
                .then(Commands.literal("list").requires(permitted("list"))
                        .executes(run(sender -> actions.list(sender, 1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(run((sender, context) -> actions.list(sender, IntegerArgumentType.getInteger(context, "page"))))))
                .then(Commands.literal("verify").requires(permitted("list")).executes(run(actions::verify)))
                .then(Commands.literal("restore").requires(permitted("restore"))
                        // "cancel" is a literal, so it wins over the id argument; backup IDs never spell "cancel".
                        .then(Commands.literal("cancel").executes(run(actions::cancelRestore)))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(backupIds("restore", false))
                                .executes(run((sender, context) -> actions.restore(sender, StringArgumentType.getString(context, "id"))))))
                .then(Commands.literal("delete").requires(permitted("delete"))
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(backupIds("delete", false))
                                .executes(run((sender, context) -> actions.delete(sender, StringArgumentType.getString(context, "id"))))))
                .then(Commands.literal("status").requires(permitted("status")).executes(run(actions::status)))
                .then(Commands.literal("reload").requires(permitted("reload")).executes(run(actions::reload)))
                .then(Commands.literal("auto").requires(permitted("configure"))
                        .then(Commands.literal("on").executes(run(sender -> actions.auto(sender, true))))
                        .then(Commands.literal("off").executes(run(sender -> actions.auto(sender, false)))))
                .then(Commands.literal("interval").requires(permitted("configure"))
                        .then(Commands.argument("minutes", LongArgumentType.longArg(1, CommandActions.MAX_INTERVAL_MINUTES)).suggests(intervals)
                                .executes(run((sender, context) -> actions.interval(sender, LongArgumentType.getLong(context, "minutes"))))))
                .then(Commands.literal("retention").requires(permitted("configure"))
                        .then(Commands.literal("count").then(Commands.argument("number", LongArgumentType.longArg(0, CommandActions.MAX_RETENTION_COUNT))
                                .executes(run((sender, context) -> actions.retentionCount(sender, LongArgumentType.getLong(context, "number"))))))
                        .then(Commands.literal("bytes").then(Commands.argument("number", LongArgumentType.longArg(0))
                                .executes(run((sender, context) -> actions.retentionBytes(sender, LongArgumentType.getLong(context, "number")))))));
    }
}
