// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldSaveEvent;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The part of saving that is identical on both APIs.
 *
 * A complete, flushed save is the vanilla {@code save-all flush} command: players, global data
 * (level.dat, scoreboard and the like), every world, and a wait for the chunk writers. Neither
 * API offers that as a method. World#save does not flush on Spigot, and on Paper it omits the
 * global data. The command is therefore run through the server's public command dispatch.
 * Its effect is confirmed instead of assumed: the server announces every world it saves with a
 * WorldSaveEvent, and a world that was not announced fails the save.
 */
abstract class BukkitPlatform implements ServerPlatform, Listener {
    /** The namespaced label cannot be shadowed by a commands.yml alias or another plugin's command. */
    static final String SAVE_COMMAND = "minecraft:save-all flush";

    protected final VaultBackupPlugin plugin;
    /** Primary thread only. Non-null exactly while saveAll() is collecting announcements. */
    private Set<UUID> announced;

    BukkitPlatform(VaultBackupPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void worldSaved(WorldSaveEvent event) {
        if (announced != null) announced.add(event.getWorld().getUID());
    }

    @Override public final void saveAll() throws IOException {
        if (!Bukkit.isPrimaryThread()) throw new IOException(plugin.text("lifecycle.main-thread"));
        List<World> worlds = List.copyOf(Bukkit.getWorlds());
        Set<UUID> seen = new HashSet<>();
        announced = seen;
        try {
            dispatchSave();
        } catch (RuntimeException | LinkageError failure) {
            // Command dispatch reports failures of the command itself as unchecked exceptions.
            throw new IOException(plugin.text("lifecycle.save-failed"), failure);
        } finally {
            announced = null;
        }
        for (World world : worlds) {
            if (!seen.contains(world.getUID())) throw new IOException(plugin.text("lifecycle.save-unconfirmed", world.getName()));
        }
    }

    /** Runs {@link #SAVE_COMMAND}; throws when the platform can tell that it did not succeed. */
    protected abstract void dispatchSave() throws IOException;

    private static final java.util.regex.Pattern BUKKIT_VERSION =
            java.util.regex.Pattern.compile("^([0-9]{1,4}(?:\\.[0-9]{1,4}){1,2})(?:[-.].*)?$");

    /**
     * Minecraft version at the start of Bukkit's version string, for example "26.3" from
     * "26.3-R0.1-SNAPSHOT" or from "26.3.build.253-stable". Empty when the format is unknown.
     */
    static String minecraftVersion(String bukkitVersion) {
        if (bukkitVersion == null) return "";
        java.util.regex.Matcher match = BUKKIT_VERSION.matcher(bukkitVersion);
        return match.matches() ? match.group(1) : "";
    }

    /**
     * Compares dotted versions numerically: negative when {@code version} is older than {@code other}.
     * Missing components count as zero, so 26.3 equals 26.3.0.
     */
    static int compareVersions(String version, String other) {
        String[] a = version.split("\\."), b = other.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0, y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
