# SPDX-License-Identifier: GPL-3.0-only
-include proguard-common.pro

# Exact name and public zero-argument constructor used by Bukkit plugin.yml.
-keep public class com.vaultbackup.plugin.VaultBackupPlugin {
    public <init>();
    public void onEnable();
    public void onDisable();
}

# Bukkit discovers listeners by runtime annotation and invokes their methods.
# Listener implementation classes may still be renamed.
-keepclassmembers class com.vaultbackup.** implements org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler <methods>;
}

# These names are normally retained through the complete library hierarchy;
# explicit rules document the server callbacks that must remain callable.
-keepclassmembers class com.vaultbackup.** implements org.bukkit.command.CommandExecutor {
    public boolean onCommand(org.bukkit.command.CommandSender,org.bukkit.command.Command,java.lang.String,java.lang.String[]);
}
-keepclassmembers class com.vaultbackup.** implements org.bukkit.command.TabCompleter {
    public java.util.List onTabComplete(org.bukkit.command.CommandSender,org.bukkit.command.Command,java.lang.String,java.lang.String[]);
}
-keepclassmembers class com.vaultbackup.** implements org.bukkit.inventory.InventoryHolder {
    public org.bukkit.inventory.Inventory getInventory();
}

# The outer plugin currently also contains restore classes. Preserve the helper
# entrypoint there for predictable inspection; the embedded helper is built and
# obfuscated separately and is inserted AFTER processing this plugin input.
-keep public class com.vaultbackup.restore.RestoreMain {
    public static void main(java.lang.String[]);
}

# Folia rejection is an optional runtime class-presence probe, not a dependency.
# Its exact literal is intentionally retained. Do not suppress other warnings.
-dontnote io.papermc.paper.threadedregions.RegionizedServer
