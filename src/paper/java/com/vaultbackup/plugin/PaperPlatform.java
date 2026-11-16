// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Paper and its forks: the Paper API as documented for plugins. Text is Adventure components,
 * commands are Brigadier commands registered through the lifecycle event manager, items come
 * from ItemStack.of, and the server itself reports whether it is stopping.
 *
 * This class is compiled against the Paper API and is loaded by name only where that API exists.
 */
final class PaperPlatform extends BukkitPlatform {
    private static final Key FOLIA = Key.key("papermc", "folia");
    private static final String SAVE_SUCCESS = "commands.save.success";

    PaperPlatform(VaultBackupPlugin plugin) { super(plugin); }

    @Override public String serverName() { return ServerBuildInfo.buildInfo().brandName(); }

    @Override public String minecraftVersion() { return minecraftVersion(Bukkit.getMinecraftVersion()); }

    @Override public String pluginVersion() { return plugin.getPluginMeta().getVersion(); }

    /** Paper's documented Folia check, plus the regionized server class for forks that changed their brand. */
    @Override public boolean regionized() {
        if (ServerBuildInfo.buildInfo().isBrandCompatible(FOLIA)) return true;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); return true; }
        catch (ClassNotFoundException | LinkageError absent) { return false; }
    }

    @Override public StopEvidence stopEvidence() {
        return Bukkit.isStopping() ? StopEvidence.STOPPING : StopEvidence.NOT_STOPPING;
    }

    /**
     * Paper can hand a command its own feedback receiver, so the command's result is read directly:
     * only the vanilla success message counts. Nothing is broadcast to operators.
     */
    @Override protected void dispatchSave() throws IOException {
        List<Component> feedback = new ArrayList<>();
        CommandSender receiver = Bukkit.createCommandSender(feedback::add);
        if (!Bukkit.dispatchCommand(receiver, SAVE_COMMAND))
            throw new IOException(plugin.text("lifecycle.save-rejected", SAVE_COMMAND));
        if (feedback.stream().noneMatch(message -> isTranslation(message, SAVE_SUCCESS, 0)))
            throw new IOException(plugin.text("lifecycle.save-failed"));
    }

    private static boolean isTranslation(Component component, String key, int depth) {
        if (component instanceof TranslatableComponent translatable && translatable.key().equals(key)) return true;
        if (depth >= 4) return false;
        for (Component child : component.children()) if (isTranslation(child, key, depth + 1)) return true;
        return false;
    }

    @Override public void send(CommandSender recipient, boolean success, String line) {
        recipient.sendMessage(Component.text()
                .append(Component.text("[VaultBackup] ", NamedTextColor.GOLD))
                .append(Component.text(line, success ? NamedTextColor.GREEN : NamedTextColor.RED))
                .build());
    }

    @Override public Inventory createMenu(InventoryHolder holder, int size, String title) {
        return Bukkit.createInventory(holder, size, Component.text(title, NamedTextColor.DARK_AQUA));
    }

    @Override public ItemStack icon(Material type, String name, List<String> lore) {
        ItemStack stack = ItemStack.of(type);
        stack.editMeta(meta -> {
            // item_name is not italic and cannot be changed in an anvil, unlike a custom name.
            meta.itemName(Component.text(name, NamedTextColor.GOLD));
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(lines);
        });
        return stack;
    }

    @Override public InventoryHolder holderOf(Inventory inventory) { return inventory.getHolder(false); }

    @Override public void registerCommands(CommandActions actions) { PaperCommands.register(plugin, actions); }
}
