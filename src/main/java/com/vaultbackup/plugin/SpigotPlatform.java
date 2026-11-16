// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Spigot and CraftBukkit: the Spigot API as documented for plugins. Commands come from
 * plugin.yml and a CommandExecutor, text uses ChatColor with the String-based methods.
 */
final class SpigotPlatform extends BukkitPlatform {
    SpigotPlatform(VaultBackupPlugin plugin) { super(plugin); }

    @Override public String serverName() { return Bukkit.getName(); }

    @Override public String minecraftVersion() { return minecraftVersion(Bukkit.getBukkitVersion()); }

    @Override public String pluginVersion() { return plugin.getDescription().getVersion(); }

    @Override public boolean regionized() { return false; }

    /** The Spigot API cannot tell a server stop from a plugin reload; the restore helper compensates. */
    @Override public StopEvidence stopEvidence() { return StopEvidence.UNKNOWN; }

    /**
     * The console is the only sender Spigot accepts for a vanilla command from a plugin, and the
     * dispatch result only says that the command exists. Success is established by the caller
     * from the per-world save announcements.
     */
    @Override protected void dispatchSave() throws IOException {
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), SAVE_COMMAND))
            throw new IOException(plugin.text("lifecycle.save-rejected", SAVE_COMMAND));
    }

    @Override public void send(CommandSender recipient, boolean success, String line) {
        recipient.sendMessage(ChatColor.GOLD + "[VaultBackup] " + (success ? ChatColor.GREEN : ChatColor.RED) + line);
    }

    @Override public Inventory createMenu(InventoryHolder holder, int size, String title) {
        return Bukkit.createInventory(holder, size, ChatColor.DARK_AQUA + title);
    }

    @Override public ItemStack icon(Material type, String name, List<String> lore) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RESET.toString() + ChatColor.GOLD + name);
            meta.setLore(lore.stream().map(line -> ChatColor.RESET.toString() + ChatColor.GRAY + line).toList());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    @Override public InventoryHolder holderOf(Inventory inventory) { return inventory.getHolder(); }

    @Override public void registerCommands(CommandActions actions) {
        BackupCommands executor = new BackupCommands(actions);
        PluginCommand command = Objects.requireNonNull(plugin.getCommand("backup"), "plugin.yml declares the backup command");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
