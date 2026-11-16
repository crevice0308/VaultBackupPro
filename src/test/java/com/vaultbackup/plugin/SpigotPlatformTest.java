// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The Spigot implementation against a faked server: how the full save is requested and when it
 * counts as confirmed. Runs against the Spigot API on the class path; no Minecraft server is started.
 */
public final class SpigotPlatformTest {
    private static int checks;

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    private interface Checked { void run() throws Exception; }

    private static IOException rejects(Checked action, String name) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return expected; }
        throw new AssertionError("expected refusal: " + name);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, java.util.function.Function<Object[], Object>> methods) {
        return (T) Proxy.newProxyInstance(SpigotPlatformTest.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            java.util.function.Function<Object[], Object> handler = methods.get(method.getName());
            if (handler != null) return handler.apply(arguments);
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> type.getSimpleName() + "@fixture";
                default -> defaultValue(method.getReturnType());
            };
        });
    }

    private static World world(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return fake(World.class, Map.of("getName", a -> name, "getUID", a -> id));
    }

    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** What the faked server does when a command is dispatched. */
    private static final class Dispatch {
        boolean primaryThread = true, known = true, throwing;
        List<World> worlds = new ArrayList<>(), announce = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<CommandSender> senders = new ArrayList<>();
        SpigotPlatform platform;
    }

    public static void main(String[] args) throws Exception {
        Dispatch dispatch = new Dispatch();
        ConsoleCommandSender console = fake(ConsoleCommandSender.class, Map.of("getName", a -> "CONSOLE"));
        List<Object[]> registered = new ArrayList<>();
        PluginManager pluginManager = fake(PluginManager.class, Map.of("registerEvents", a -> { registered.add(a); return null; }));
        Map<String, Object> lastMeta = new HashMap<>();
        ItemFactory itemFactory = fake(ItemFactory.class, Map.of(
                "getItemMeta", a -> fake(ItemMeta.class, Map.of(
                        "setDisplayName", b -> { lastMeta.put("name", b[0]); return null; },
                        "setLore", b -> { lastMeta.put("lore", b[0]); return null; },
                        "clone", b -> null)),
                "isApplicable", a -> true,
                "asMetaFor", a -> a[0]));
        List<Object[]> inventories = new ArrayList<>();
        Server server = fake(Server.class, Map.ofEntries(
                Map.entry("getName", a -> "Spigot"),
                Map.entry("getVersion", a -> "4664-Spigot (MC: 26.3)"),
                Map.entry("getBukkitVersion", a -> "26.3-R0.1-SNAPSHOT"),
                Map.entry("getLogger", a -> Logger.getLogger("SpigotPlatformTest")),
                Map.entry("isPrimaryThread", a -> dispatch.primaryThread),
                Map.entry("getWorlds", a -> List.copyOf(dispatch.worlds)),
                Map.entry("getConsoleSender", a -> console),
                Map.entry("getPluginManager", a -> pluginManager),
                Map.entry("getItemFactory", a -> itemFactory),
                Map.entry("createInventory", a -> { inventories.add(a); return fake(Inventory.class, Map.of("getHolder", b -> a[0])); }),
                Map.entry("dispatchCommand", a -> {
                    dispatch.senders.add((CommandSender) a[0]);
                    dispatch.commands.add((String) a[1]);
                    if (dispatch.throwing) throw new org.bukkit.command.CommandException("fixture failure");
                    // The real server announces every world it saves while the command runs.
                    for (World saved : dispatch.announce) dispatch.platform.worldSaved(new WorldSaveEvent(saved));
                    return dispatch.known;
                })));
        Bukkit.setServer(server);

        // JavaPlugin's constructor only works inside a server's plugin class loader; allocate a bare fixture instead.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        VaultBackupPlugin plugin = (VaultBackupPlugin) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafeField.get(null), VaultBackupPlugin.class);
        field(plugin, JavaPlugin.class, "server", server);
        field(plugin, JavaPlugin.class, "isEnabled", true);
        field(plugin, JavaPlugin.class, "description", new org.bukkit.plugin.PluginDescriptionFile("VaultBackupPro", "7.7.7-fixture", VaultBackupPlugin.class.getName()));
        org.bukkit.plugin.PluginLogger logger = new org.bukkit.plugin.PluginLogger(plugin);
        logger.setUseParentHandlers(false);
        field(plugin, JavaPlugin.class, "logger", logger);
        field(plugin, VaultBackupPlugin.class, "settings", null); // language() falls back to the default bundle

        check(!ServerPlatform.paperApiAvailable(), "the Spigot API is not mistaken for the Paper API");
        ServerPlatform detected = ServerPlatform.detect(plugin);
        check(detected instanceof SpigotPlatform, "Spigot implementation selected on the Spigot API");
        SpigotPlatform platform = (SpigotPlatform) detected;
        dispatch.platform = platform;
        check(registered.size() == 1 && registered.get(0)[0] == platform && registered.get(0)[1] == plugin, "platform listens for world save announcements");

        check(platform.serverName().equals("Spigot") && platform.minecraftVersion().equals("26.3"), "server name and Minecraft version from the Bukkit API");
        check(platform.pluginVersion().equals("7.7.7-fixture"), "plugin version from plugin.yml metadata");
        check(!platform.regionized(), "Spigot is never regionized");
        check(platform.stopEvidence() == ServerPlatform.StopEvidence.UNKNOWN, "Spigot has no stop indicator and never claims one");

        World overworld = world("world"), nether = world("world_nether"), custom = world("lobby");
        dispatch.worlds = new ArrayList<>(List.of(overworld, nether, custom));

        // 1. Everything saved and announced.
        dispatch.announce = new ArrayList<>(dispatch.worlds);
        platform.saveAll();
        check(dispatch.commands.equals(List.of("minecraft:save-all flush")), "exactly the namespaced vanilla flush command is dispatched");
        check(dispatch.senders.size() == 1 && dispatch.senders.get(0) == console, "the console is the sender on Spigot");

        // 2. Off the primary thread nothing is dispatched.
        dispatch.primaryThread = false;
        rejects(platform::saveAll, "off-thread save");
        check(dispatch.commands.size() == 1, "no dispatch off the primary thread");
        dispatch.primaryThread = true;

        // 3. The server does not know the command (removed or blocked): refused, never treated as saved.
        dispatch.known = false;
        IOException unknown = rejects(platform::saveAll, "unknown command");
        check(unknown.getMessage().contains("minecraft:save-all flush"), "refusal names the command");
        dispatch.known = true;

        // 4. The command ran but a world was never announced: unconfirmed, naming the world.
        dispatch.announce = new ArrayList<>(List.of(overworld, custom));
        IOException partial = rejects(platform::saveAll, "partially announced save");
        check(partial.getMessage().contains("world_nether"), "unconfirmed world is named");
        dispatch.announce = new ArrayList<>();
        IOException silent = rejects(platform::saveAll, "command that saved nothing");
        check(silent.getMessage().contains("world"), "a command that announces nothing is not a save");

        // 5. The command throws: reported as a failed save, not propagated as an unchecked exception.
        dispatch.announce = new ArrayList<>(dispatch.worlds);
        dispatch.throwing = true;
        IOException thrown = rejects(platform::saveAll, "throwing command");
        check(thrown.getCause() instanceof org.bukkit.command.CommandException, "original failure is kept as the cause");
        dispatch.throwing = false;

        // 6. Announcements outside a save are not remembered for the next one.
        for (World saved : dispatch.worlds) platform.worldSaved(new WorldSaveEvent(saved));
        dispatch.announce = new ArrayList<>();
        rejects(platform::saveAll, "stale announcements");
        dispatch.announce = new ArrayList<>(dispatch.worlds);
        platform.saveAll();

        // 7. A server without loaded worlds: the command alone decides.
        dispatch.worlds = new ArrayList<>();
        dispatch.announce = new ArrayList<>();
        platform.saveAll();
        check(dispatch.commands.stream().allMatch("minecraft:save-all flush"::equals), "no other command is ever dispatched");

        // Text and menus use the Spigot String API.
        List<String> sent = new ArrayList<>();
        CommandSender recipient = fake(CommandSender.class, Map.of("sendMessage", a -> { for (Object value : a) if (value instanceof String s) sent.add(s); return null; }));
        platform.send(recipient, true, "saved");
        platform.send(recipient, false, "failed");
        check(sent.get(0).equals(ChatColor.GOLD + "[VaultBackup] " + ChatColor.GREEN + "saved"), "success line format");
        check(sent.get(1).equals(ChatColor.GOLD + "[VaultBackup] " + ChatColor.RED + "failed"), "failure line format");
        InventoryHolder holder = fake(InventoryHolder.class, Map.of());
        Inventory menu = platform.createMenu(holder, 27, "Title");
        check(inventories.size() == 1 && inventories.get(0)[0] == holder && inventories.get(0)[1].equals(27)
                && inventories.get(0)[2].equals(ChatColor.DARK_AQUA + "Title"), "menu created with holder, size and coloured title");
        check(platform.holderOf(menu) == holder, "menu holder lookup");
        ItemStack icon = platform.icon(Material.CHEST, "Name", List.of("first", "second"));
        check(icon.getType() == Material.CHEST && lastMeta.get("name").equals(ChatColor.RESET.toString() + ChatColor.GOLD + "Name"), "icon name");
        check(lastMeta.get("lore").equals(List.of(ChatColor.RESET.toString() + ChatColor.GRAY + "first", ChatColor.RESET.toString() + ChatColor.GRAY + "second")), "icon lore");

        // The plugin.yml command gets the Bukkit executor and completer.
        Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        constructor.setAccessible(true);
        PluginCommand command = constructor.newInstance("backup", plugin);
        Server withCommand = fake(Server.class, Map.of("getPluginCommand", a -> a[0].equals("backup") ? command : null,
                "getLogger", a -> Logger.getLogger("SpigotPlatformTest")));
        field(plugin, JavaPlugin.class, "server", withCommand);
        platform.registerCommands(new CommandActions(plugin));
        check(command.getExecutor() instanceof BackupCommands && command.getTabCompleter() == command.getExecutor(), "plugin.yml command wired to the Bukkit executor");

        System.out.println("PASS SpigotPlatformTest (" + checks + " checks against a faked Spigot server; no Minecraft server started)");
    }
}
