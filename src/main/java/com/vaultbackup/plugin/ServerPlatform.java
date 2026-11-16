// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.List;

/**
 * Everything that differs between the two supported server APIs. Each implementation is compiled
 * against the API of its own platform and uses only what that project documents for plugins:
 * the Paper API on Paper and its forks, the Spigot API elsewhere. Neither touches server internals.
 */
interface ServerPlatform {
    /** What the server could tell the plugin about a shutdown while the plugin is being disabled. */
    enum StopEvidence {
        /** The server API reports that its shutdown path has been entered. */
        STOPPING,
        /** The server API reports that it keeps running (plugin reload or unload). */
        NOT_STOPPING,
        /** This server API has no shutdown indicator. */
        UNKNOWN
    }

    /** Server implementation name for messages, for example "Paper" or "Spigot". */
    String serverName();

    /** Minecraft version such as "26.3", or an empty string when it cannot be determined. */
    String minecraftVersion();

    /** This plugin's version as declared in plugin.yml. */
    String pluginVersion();

    /** True on Folia-style regionized servers, which this plugin does not support. */
    boolean regionized();

    StopEvidence stopEvidence();

    /**
     * Saves players, global data and every loaded world and waits until the data is written
     * (the vanilla {@code save-all flush} operation). Primary thread only.
     *
     * @throws IOException when the save did not run or could not be confirmed; the backup is then not started
     */
    void saveAll() throws IOException;

    /** Sends one prefixed, coloured line. */
    void send(CommandSender recipient, boolean success, String line);

    Inventory createMenu(InventoryHolder holder, int size, String title);

    ItemStack icon(Material type, String name, List<String> lore);

    /** The holder of a menu inventory without creating block-state snapshots where the API allows it. */
    InventoryHolder holderOf(Inventory inventory);

    /** Registers /backup with the command system this platform recommends. */
    void registerCommands(CommandActions actions);

    /** True when the Paper API (not merely the Bukkit API) is available at run time. */
    static boolean paperApiAvailable() {
        try {
            // Presence only: the classes are not initialised here, the Paper implementation does that when it runs.
            ClassLoader loader = ServerPlatform.class.getClassLoader();
            Class.forName("io.papermc.paper.ServerBuildInfo", false, loader);
            Class.forName("io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents", false, loader);
            Class.forName("net.kyori.adventure.text.Component", false, loader);
            org.bukkit.Server.class.getMethod("isStopping");
            org.bukkit.Server.class.getMethod("createCommandSender", java.util.function.Consumer.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError missing) {
            return false;
        }
    }

    /**
     * Selects the implementation for the running server. The Paper implementation lives in a class
     * that only links where the Paper API exists, so it is loaded by name and never referenced on Spigot.
     */
    static ServerPlatform detect(VaultBackupPlugin plugin) {
        if (paperApiAvailable()) {
            try {
                return (ServerPlatform) Class.forName("com.vaultbackup.plugin.PaperPlatform")
                        .getDeclaredConstructor(VaultBackupPlugin.class).newInstance(plugin);
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Paper API support could not be loaded; using the Spigot-compatible API instead.", incompatible);
            }
        }
        return new SpigotPlatform(plugin);
    }
}
