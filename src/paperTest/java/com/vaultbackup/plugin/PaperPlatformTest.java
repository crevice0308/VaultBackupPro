// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.vaultbackup.core.BackupRecord;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The Paper implementation against a faked server: components instead of legacy text, the save
 * confirmed from the command's own feedback, the server's stop indicator, and the Brigadier
 * command tree run through a real Brigadier dispatcher. Runs against the Paper API on the class
 * path; no Minecraft server is started.
 */
public final class PaperPlatformTest {
    private static int checks;
    /** Changed by the test to model another server brand. */
    static volatile String brand = "paper";

    /** Stands in for the implementation a real Paper server provides through ServiceLoader. */
    public static final class FixtureBuildInfo implements ServerBuildInfo {
        @Override public Key brandId() { return Key.key("papermc", brand); }
        @Override public boolean isBrandCompatible(Key other) { return brandId().equals(other); }
        @Override public String brandName() { return brand.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + brand.substring(1); }
        @Override public String minecraftVersionId() { return "26.3"; }
        @Override public String minecraftVersionName() { return "26.3"; }
        @Override public OptionalInt buildNumber() { return OptionalInt.of(253); }
        @Override public Instant buildTime() { return Instant.EPOCH; }
        @Override public Optional<String> gitBranch() { return Optional.empty(); }
        @Override public Optional<String> gitCommit() { return Optional.empty(); }
        @Override public String asString(StringRepresentation representation) { return "fixture"; }
    }

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
    private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> methods) {
        return (T) Proxy.newProxyInstance(PaperPlatformTest.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            Function<Object[], Object> handler = methods.get(method.getName());
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

    private static final class Dispatch {
        boolean primaryThread = true, known = true, throwing, stopping;
        List<World> worlds = new ArrayList<>(), announce = new ArrayList<>();
        /** Feedback the faked server gives the command's sender. */
        List<Component> feedback = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<CommandSender> senders = new ArrayList<>();
        PaperPlatform platform;
    }

    /** A sender whose messages (components on Paper) and permissions the test controls. */
    private static CommandSender sender(Set<String> permissions, List<Component> received) {
        return fake(CommandSender.class, Map.of(
                "hasPermission", a -> permissions.contains(String.valueOf(a[0])),
                "getName", a -> "fixture-console",
                "sendMessage", a -> { for (Object value : a) if (value instanceof Component c) received.add(c); return null; }));
    }

    private static String plain(Component component) {
        StringBuilder text = new StringBuilder();
        if (component instanceof TextComponent t) text.append(t.content());
        for (Component child : component.children()) text.append(plain(child));
        return text.toString();
    }

    private static boolean said(List<Component> received, String fragment) {
        return received.stream().anyMatch(component -> plain(component).contains(fragment));
    }

    public static void main(String[] args) throws Exception {
        Dispatch dispatch = new Dispatch();
        List<Object[]> registered = new ArrayList<>();
        PluginManager pluginManager = fake(PluginManager.class, Map.of("registerEvents", a -> { registered.add(a); return null; }));
        List<Object[]> inventories = new ArrayList<>();
        List<Consumer<? super Component>> receivers = new ArrayList<>();
        Server server = fake(Server.class, Map.ofEntries(
                Map.entry("getName", a -> "Paper"),
                Map.entry("getBukkitVersion", a -> "26.3-R0.1-SNAPSHOT"),
                Map.entry("getMinecraftVersion", a -> "26.3"),
                Map.entry("getLogger", a -> Logger.getLogger("PaperPlatformTest")),
                Map.entry("isPrimaryThread", a -> dispatch.primaryThread),
                Map.entry("isStopping", a -> dispatch.stopping),
                Map.entry("getWorlds", a -> List.copyOf(dispatch.worlds)),
                Map.entry("getPluginManager", a -> pluginManager),
                Map.entry("createInventory", a -> { inventories.add(a); return fake(Inventory.class, Map.of("getHolder", b -> {
                    // Paper's snapshot-free lookup is the one-argument-boolean overload.
                    check(b != null && b.length == 1 && Boolean.FALSE.equals(b[0]), "holder looked up without a block-state snapshot");
                    return a[0];
                })); }),
                Map.entry("createCommandSender", a -> {
                    @SuppressWarnings("unchecked") Consumer<? super Component> receiver = (Consumer<? super Component>) a[0];
                    receivers.add(receiver);
                    return fake(CommandSender.class, Map.of("getName", b -> "feedback-receiver"));
                }),
                Map.entry("dispatchCommand", a -> {
                    dispatch.senders.add((CommandSender) a[0]);
                    dispatch.commands.add((String) a[1]);
                    if (dispatch.throwing) throw new org.bukkit.command.CommandException("fixture failure");
                    for (World saved : dispatch.announce) dispatch.platform.worldSaved(new WorldSaveEvent(saved));
                    for (Component message : dispatch.feedback) receivers.get(receivers.size() - 1).accept(message);
                    return dispatch.known;
                })));
        Bukkit.setServer(server);

        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        VaultBackupPlugin plugin = (VaultBackupPlugin) unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafeField.get(null), VaultBackupPlugin.class);
        field(plugin, JavaPlugin.class, "server", server);
        field(plugin, JavaPlugin.class, "isEnabled", true);
        field(plugin, JavaPlugin.class, "pluginMeta", new org.bukkit.plugin.PluginDescriptionFile("VaultBackupPro", "8.8.8-fixture", VaultBackupPlugin.class.getName()));
        Logger logger = Logger.getLogger("PaperPlatformTest.plugin");
        logger.setUseParentHandlers(false);
        field(plugin, JavaPlugin.class, "logger", logger);
        // Field initialisers do not run for an instance allocated without its constructor.
        field(plugin, VaultBackupPlugin.class, "busy", new java.util.concurrent.atomic.AtomicBoolean());
        field(plugin, VaultBackupPlugin.class, "cancelled", new java.util.concurrent.atomic.AtomicBoolean());
        field(plugin, VaultBackupPlugin.class, "operation", "operation.idle");
        field(plugin, VaultBackupPlugin.class, "progress", "Ready");

        check(ServerPlatform.paperApiAvailable(), "the Paper API is recognised");
        ServerPlatform detected = ServerPlatform.detect(plugin);
        check(detected.getClass().getName().equals("com.vaultbackup.plugin.PaperPlatform"), "Paper implementation selected on the Paper API");
        PaperPlatform platform = (PaperPlatform) detected;
        dispatch.platform = platform;
        field(plugin, VaultBackupPlugin.class, "platform", platform);
        check(registered.size() == 1 && registered.get(0)[0] == platform, "platform listens for world save announcements");

        check(platform.serverName().equals("Paper") && platform.minecraftVersion().equals("26.3"), "brand and version from the Paper API");
        check(platform.pluginVersion().equals("8.8.8-fixture"), "plugin version from the plugin meta");
        check(!platform.regionized(), "Paper is not regionized");
        brand = "folia";
        check(platform.regionized(), "Folia is recognised by Paper's documented brand check");
        brand = "purpur";
        check(!platform.regionized() && platform.serverName().equals("Purpur"), "a Paper fork keeps the Paper implementation");
        brand = "paper";

        // The server itself says whether it is stopping.
        check(platform.stopEvidence() == ServerPlatform.StopEvidence.NOT_STOPPING, "running server reports not stopping");
        dispatch.stopping = true;
        check(platform.stopEvidence() == ServerPlatform.StopEvidence.STOPPING, "stopping server reports stopping");
        dispatch.stopping = false;

        World overworld = world("world"), nether = world("world_nether");
        dispatch.worlds = new ArrayList<>(List.of(overworld, nether));
        Component saving = Component.translatable("commands.save.saving"), success = Component.translatable("commands.save.success");

        // 1. Announced and confirmed by the command's own success message.
        dispatch.announce = new ArrayList<>(dispatch.worlds);
        dispatch.feedback = new ArrayList<>(List.of(saving, success));
        platform.saveAll();
        check(dispatch.commands.equals(List.of("minecraft:save-all flush")), "exactly the namespaced vanilla flush command is dispatched");
        check(receivers.size() == 1 && dispatch.senders.get(0) != null && !(dispatch.senders.get(0) instanceof org.bukkit.command.ConsoleCommandSender),
                "a feedback receiver is the sender on Paper, not the console");

        // 2. The command reports failure (or nothing): not a save, even though every world was announced.
        dispatch.feedback = new ArrayList<>(List.of(saving, Component.translatable("commands.save.failed")));
        rejects(platform::saveAll, "vanilla failure message");
        dispatch.feedback = new ArrayList<>(List.of(saving));
        rejects(platform::saveAll, "missing success message");
        dispatch.feedback = new ArrayList<>(List.of(Component.text("commands.save.success")));
        rejects(platform::saveAll, "plain text that merely spells the key");
        dispatch.feedback = new ArrayList<>(List.of(Component.text("Server: ").append(success)));
        platform.saveAll(); // a wrapped success message still counts

        // 3. Success message but a world never announced: unconfirmed.
        dispatch.feedback = new ArrayList<>(List.of(saving, success));
        dispatch.announce = new ArrayList<>(List.of(overworld));
        IOException partial = rejects(platform::saveAll, "partially announced save");
        check(partial.getMessage().contains("world_nether"), "unconfirmed world is named");

        // 4. Unknown command, exception, wrong thread.
        dispatch.announce = new ArrayList<>(dispatch.worlds);
        dispatch.known = false;
        rejects(platform::saveAll, "unknown command");
        dispatch.known = true;
        dispatch.throwing = true;
        check(rejects(platform::saveAll, "throwing command").getCause() instanceof org.bukkit.command.CommandException, "original failure is kept as the cause");
        dispatch.throwing = false;
        dispatch.primaryThread = false;
        int before = dispatch.commands.size();
        rejects(platform::saveAll, "off-thread save");
        check(dispatch.commands.size() == before, "no dispatch off the primary thread");
        dispatch.primaryThread = true;
        check(dispatch.commands.stream().allMatch("minecraft:save-all flush"::equals), "no other command is ever dispatched");

        // Messages are components: a gold prefix and a coloured line, no legacy section signs.
        List<Component> received = new ArrayList<>();
        Set<String> permissions = new HashSet<>();
        CommandSender recipient = sender(permissions, received);
        platform.send(recipient, true, "saved §c<red>");
        platform.send(recipient, false, "failed");
        check(received.size() == 2 && plain(received.get(0)).equals("[VaultBackup] saved §c<red>"), "text is passed through literally, never parsed as formatting");
        check(received.get(0).children().get(0).color() == NamedTextColor.GOLD && received.get(0).children().get(1).color() == NamedTextColor.GREEN, "success colours");
        check(received.get(1).children().get(1).color() == NamedTextColor.RED, "failure colour");
        InventoryHolder holder = fake(InventoryHolder.class, Map.of());
        Inventory menu = platform.createMenu(holder, 54, "Title");
        check(inventories.size() == 1 && inventories.get(0)[0] == holder && inventories.get(0)[1].equals(54)
                && inventories.get(0)[2] instanceof Component title && plain(title).equals("Title") && title.color() == NamedTextColor.DARK_AQUA,
                "menu created with a component title");
        check(platform.holderOf(menu) == holder, "menu holder lookup");

        // The Brigadier tree, executed by Brigadier itself.
        field(plugin, VaultBackupPlugin.class, "settings", null);
        BackupRecord record = new BackupRecord("20261116T000000000Z-0123456789ab", Instant.EPOCH, "fixture", Path.of("a.zip"), Path.of("a.properties"), 1, 1, 1, "0".repeat(64), 0);
        field(plugin, VaultBackupPlugin.class, "catalogue", List.of(record));
        CommandActions actions = new CommandActions(plugin);
        PaperCommands commands = new PaperCommands(plugin, actions);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        for (String label : PaperCommands.LABELS) dispatcher.getRoot().addChild(commands.tree(label).build());
        CommandSourceStack source = fake(CommandSourceStack.class, Map.of("getSender", a -> recipient));

        received.clear();
        try { dispatcher.execute("backup help", source); throw new AssertionError("command visible without any permission"); }
        catch (CommandSyntaxException hidden) { checks++; }
        check(received.isEmpty(), "a sender without permissions gets nothing from the plugin");

        permissions.addAll(List.of("vaultbackup.delete", "vaultbackup.restore"));
        check(dispatcher.execute("backup help", source) == 1, "help runs once any permission is held");
        check(said(received, "8.8.8-fixture") && said(received, "/backup delete") && said(received, "/backup restore"), "help lists what the sender may use");
        check(!said(received, "/backup create") && !said(received, "/backup reload"), "help omits what the sender may not use");
        check(dispatcher.execute("vaultbackup help", source) == 1, "the plugin.yml alias is a full command too");

        received.clear();
        dispatcher.execute("backup delete 20261116T999999999Z-ffffffffffff", source);
        check(said(received, "/backup list"), "an unknown id reaches the plugin and is refused there");
        received.clear();
        dispatcher.execute("backup restore cancel", source);
        check(!received.isEmpty() && !said(received, "20261116"), "restore cancel is the literal, not an id");
        for (String forbidden : List.of("backup create", "backup create label", "backup status", "backup reload", "backup verify", "backup list", "backup auto on", "backup interval 5", "backup retention count 3")) {
            try { dispatcher.execute(forbidden, source); throw new AssertionError("ran without permission: " + forbidden); }
            catch (CommandSyntaxException refused) { checks++; }
        }
        permissions.add("vaultbackup.configure");
        for (String malformed : List.of("backup interval 0", "backup interval 525601", "backup interval abc", "backup retention count -1", "backup retention count 1000001", "backup retention bytes -5", "backup auto maybe", "backup delete", "backup delete a b")) {
            try { dispatcher.execute(malformed, source); throw new AssertionError("accepted malformed input: " + malformed); }
            catch (CommandSyntaxException refused) { checks++; }
        }

        ParseResults<CommandSourceStack> parsed = dispatcher.parse("backup delete ", source);
        List<String> suggested = dispatcher.getCompletionSuggestions(parsed).get().getList().stream().map(Suggestion::getText).toList();
        check(suggested.equals(List.of(record.id())), "backup ids are suggested to a sender who may delete");
        suggested = dispatcher.getCompletionSuggestions(dispatcher.parse("backup restore ", source)).get().getList().stream().map(Suggestion::getText).toList();
        check(suggested.contains(record.id()) && suggested.contains("cancel"), "restore suggests ids and cancel");
        permissions.remove("vaultbackup.delete");
        suggested = dispatcher.getCompletionSuggestions(dispatcher.parse("backup delete ", source)).get().getList().stream().map(Suggestion::getText).toList();
        check(suggested.isEmpty(), "backup ids are not disclosed without the permission");
        // The server sends a client only the nodes it may use; that filter is each node's requirement.
        var root = dispatcher.getRoot().getChild("backup");
        check(root.canUse(source) && root.getChild("restore").canUse(source) && root.getChild("interval").canUse(source), "permitted sub-commands are usable");
        check(!root.getChild("delete").canUse(source) && !root.getChild("create").canUse(source) && !root.getChild("reload").canUse(source), "other sub-commands are hidden");
        permissions.clear();
        check(!root.canUse(source) && !dispatcher.getRoot().getChild("vaultbackup").canUse(source), "without permissions the command itself is hidden");
        permissions.addAll(List.of("vaultbackup.restore", "vaultbackup.configure"));

        // A command node can outlive the plugin until the server rebuilds its commands.
        permissions.add("vaultbackup.delete");
        field(plugin, JavaPlugin.class, "isEnabled", false);
        received.clear();
        dispatcher.execute("backup delete " + record.id(), source);
        check(received.isEmpty(), "a disabled plugin performs nothing from a stale command node");

        System.out.println("PASS PaperPlatformTest (" + checks + " checks against a faked Paper server and a real Brigadier dispatcher; no Minecraft server started)");
    }
}
