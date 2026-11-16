// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupDeletionRefusedException;
import com.vaultbackup.core.BackupEngine;
import com.vaultbackup.core.BackupOptions;
import com.vaultbackup.core.BackupRecord;
import com.vaultbackup.restore.RestoreCoordinator;
import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.*;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Disposable real archives and actual command/plugin methods; GUI bindings are counted separately. */
public final class DeleteWorkflowTest {
    private static int checks, sourceChecks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static void sourceCheck(boolean value, String name) {
        if (!value) throw new AssertionError("source-only: " + name);
        sourceChecks++;
    }
    private static void field(Object object, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object,value);
    }
    private static final class QueuedIO extends AbstractExecutorService {
        final Deque<Runnable> queue = new ArrayDeque<>();
        boolean shutdown;
        public void execute(Runnable task) { queue.add(task); }
        public void shutdown() { shutdown=true; }
        public List<Runnable> shutdownNow() { shutdown=true; var rest=List.copyOf(queue);queue.clear();return rest; }
        public boolean isShutdown() { return shutdown; }
        public boolean isTerminated() { return shutdown && queue.isEmpty(); }
        public boolean awaitTermination(long value,TimeUnit unit) { return isTerminated(); }
        void runOne() { queue.removeFirst().run(); }
    }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte)0;
        if (type == short.class) return (short)0;
        if (type == char.class) return '\0';
        return null;
    }
    private static void drainMain(Deque<Runnable> tasks) { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
    private static boolean contains(List<String> messages, String text) { return messages.stream().anyMatch(m->m.contains(text)); }

    private static Inventory inventory(InventoryHolder holder, int size) {
        Map<Integer,ItemStack> items=new HashMap<>();
        return (Inventory)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{Inventory.class},(p,m,a)->switch(m.getName()) {
            case "getHolder" -> holder;
            case "getSize" -> size;
            case "setItem" -> {items.put((Integer)a[0],(ItemStack)a[1]);yield null;}
            case "getItem" -> items.get((Integer)a[0]);
            case "getType" -> InventoryType.CHEST;
            default -> defaultValue(m.getReturnType());
        });
    }
    private static ItemMeta metadata() {
        Map<String,Object> values=new HashMap<>();
        return (ItemMeta)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{ItemMeta.class},(p,m,a)->switch(m.getName()) {
            case "setDisplayName" -> {values.put("name",a[0]);yield null;}
            case "getDisplayName" -> values.get("name");
            case "setLore" -> {values.put("lore",a[0]);yield null;}
            case "getLore" -> values.get("lore");
            case "clone" -> p;
            default -> defaultValue(m.getReturnType());
        });
    }
    private static ItemFactory itemFactory() {
        return (ItemFactory)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{ItemFactory.class},(p,m,a)->switch(m.getName()) {
            case "getItemMeta" -> metadata();
            case "isApplicable" -> true;
            case "asMetaFor" -> a[0];
            default -> defaultValue(m.getReturnType());
        });
    }
    private static InventoryView view(Player player, Inventory top) {
        Inventory bottom=inventory(null,36);
        return (InventoryView)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{InventoryView.class},(p,m,a)->switch(m.getName()) {
            case "getTopInventory" -> top;
            case "getBottomInventory" -> bottom;
            case "getPlayer" -> player;
            case "getType" -> InventoryType.CHEST;
            case "getTitle", "getOriginalTitle" -> "Simulated menu";
            case "convertSlot" -> (Integer)a[0];
            case "getInventory" -> (Integer)a[0]<top.getSize()?top:bottom;
            case "getItem" -> top.getItem((Integer)a[0]);
            default -> defaultValue(m.getReturnType());
        });
    }
    private static InventoryClickEvent click(BackupMenus menus, InventoryView view, int slot, ClickType type) {
        InventoryClickEvent click=new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,slot,type,InventoryAction.PICKUP_ALL);
        menus.click(click);return click;
    }

    public static void main(String[] args) throws Exception {
        Path fixture=Files.createTempDirectory("vaultbackup-direct-actions-");
        try {
            Path root=Files.createDirectory(fixture.resolve("server")), backup=fixture.resolve("backups"), state=fixture.resolve("state");
            Files.writeString(root.resolve("world.dat"),"disposable fixture data");
            BackupEngine engine=new BackupEngine(BackupOptions.defaults(root,backup,state));
            Path helper=state.resolve("helper.jar");Files.createDirectories(state);Files.writeString(helper,"not executed by this harness");
            RestoreCoordinator coordinator=new RestoreCoordinator(root,backup,state,helper,"en");
            PluginSettings settings=new PluginSettings(root,backup,state,helper,List.of("**"),List.of(),false,60,20,
                    Long.MAX_VALUE,6,100000,34359738368L,1099511627776L,2000,60,false,null,"en_US");
            Deque<Runnable> main=new ArrayDeque<>(); QueuedIO io=new QueuedIO();
            // What the fixture server models of a real one. A command issued while another command is
            // being executed is queued and runs only after the outer command has returned.
            boolean[] primary={false},inCommand={false};
            List<org.bukkit.World> worlds=new ArrayList<>();Deque<Runnable> nested=new ArrayDeque<>();List<String> dispatched=new ArrayList<>();
            BukkitPlatform[] saving={null};
            BukkitScheduler scheduler=(BukkitScheduler)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),
                    new Class<?>[]{BukkitScheduler.class},(p,m,a)->{
                        if(m.getName().equals("runTask") && a[1] instanceof Runnable task)main.add(task);
                        return defaultValue(m.getReturnType());
                    });
            Server server=(Server)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{Server.class},
                    (p,m,a)->switch(m.getName()) {
                        case "getScheduler" -> scheduler;
                        case "isPrimaryThread" -> primary[0];
                        case "getWorlds" -> worlds.isEmpty()?null:List.copyOf(worlds);
                        case "getWorld" -> worlds.stream().filter(w->w.getUID().equals(a[0])).findFirst().orElse(null);
                        case "dispatchCommand" -> {
                            String issued=(String)a[1];dispatched.add((inCommand[0]?"nested:":"direct:")+issued);
                            Runnable run=()->{if(issued.equals("minecraft:save-all flush"))for(org.bukkit.World w:worlds)saving[0].worldSaved(new org.bukkit.event.world.WorldSaveEvent(w));};
                            if(inCommand[0])nested.add(run);else run.run();
                            yield true;
                        }
                        case "getLogger" -> java.util.logging.Logger.getLogger("VaultBackupFixture");
                        case "getName" -> "Spigot";
                        case "getVersion", "getBukkitVersion" -> "26.2-R0.1-SNAPSHOT";
                        case "getItemFactory" -> itemFactory();
                        case "createInventory" -> inventory((InventoryHolder)a[0],(Integer)a[1]);
                        case "getPluginManager" -> Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),
                                new Class<?>[]{org.bukkit.plugin.PluginManager.class},(q,n,b)->defaultValue(n.getReturnType()));
                        default -> defaultValue(m.getReturnType());
                    });
            // JavaPlugin's real constructor is server-loader-only. Allocate a fixture instance and
            // supply explicit test state; no server startup/lifecycle or real user data is involved.
            Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field uf=unsafeType.getDeclaredField("theUnsafe");uf.setAccessible(true);
            VaultBackupPlugin plugin=(VaultBackupPlugin)unsafeType.getMethod("allocateInstance",Class.class).invoke(uf.get(null),VaultBackupPlugin.class);
            field(plugin,JavaPlugin.class,"server",server);field(plugin,JavaPlugin.class,"isEnabled",true);
            field(plugin,JavaPlugin.class,"description",new org.bukkit.plugin.PluginDescriptionFile("VaultBackupFixture","9.9.9-fixture",VaultBackupPlugin.class.getName()));
            org.bukkit.plugin.PluginLogger logger=new org.bukkit.plugin.PluginLogger(plugin);logger.setUseParentHandlers(false);
            field(plugin,JavaPlugin.class,"logger",logger);
            field(plugin,VaultBackupPlugin.class,"settings",settings);field(plugin,VaultBackupPlugin.class,"engine",engine);
            field(plugin,VaultBackupPlugin.class,"restores",coordinator);field(plugin,VaultBackupPlugin.class,"io",io);
            AtomicBoolean busy=new AtomicBoolean(),cancelled=new AtomicBoolean();
            field(plugin,VaultBackupPlugin.class,"busy",busy);field(plugin,VaultBackupPlugin.class,"cancelled",cancelled);
            field(plugin,VaultBackupPlugin.class,"operation","operation.idle");field(plugin,VaultBackupPlugin.class,"progress","Ready");
            // This object is allocated without running field initialisers.
            field(plugin,VaultBackupPlugin.class,"rejected",Map.of());field(plugin,VaultBackupPlugin.class,"catalogueLoaded",true);
            field(plugin,VaultBackupPlugin.class,"autosave",new java.util.LinkedHashSet<>());
            Set<String> permissions=new HashSet<>(List.of("vaultbackup.delete","vaultbackup.restore","vaultbackup.list","vaultbackup.status"));
            List<String> messages=new ArrayList<>();
            CommandSender sender=(CommandSender)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{org.bukkit.command.ConsoleCommandSender.class},(p,m,a)->{
                if(m.getName().equals("hasPermission"))return permissions.contains(a[0]);
                if(m.getName().equals("getName"))return "fixture-console";
                if(m.getName().equals("sendMessage")) { for(Object v:a)if(v instanceof String text)messages.add(text);return null; }
                return defaultValue(m.getReturnType());
            });
            Bukkit.setServer(server);
            // The Spigot implementation is the one that links against this harness's API class path.
            field(plugin,VaultBackupPlugin.class,"platform",new SpigotPlatform(plugin));
            BackupCommands commands=new BackupCommands(new CommandActions(plugin));
            BackupRecord first=engine.createBackup("first"),second=engine.createBackup("second");
            field(plugin,VaultBackupPlugin.class,"catalogue",engine.listBackups());
            // A command block is an operator as far as permissions go. It is refused for what it is.
            List<String> blockMessages=new ArrayList<>();
            CommandSender block=(CommandSender)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{org.bukkit.command.BlockCommandSender.class},(p,m,a)->{
                if(m.getName().equals("hasPermission") || m.getName().equals("isOp"))return true;
                if(m.getName().equals("getName"))return "@";
                if(m.getName().equals("sendMessage")) { for(Object v:a)if(v instanceof String text)blockMessages.add(text);return null; }
                return defaultValue(m.getReturnType());
            });
            for(String[] attempt:new String[][]{{"delete",first.id()},{"create"},{"restore",first.id()},{"reload"},{"auto","off"},{}})commands.onCommand(block,null,"backup",attempt);
            check(io.queue.isEmpty() && !busy.get() && Files.exists(first.zipPath()) && blockMessages.size()==6 && blockMessages.stream().allMatch(line->line.contains("player, the console or RCON")),
                    "a command block with every permission cannot run any part of /backup");
            check(commands.onTabComplete(block,null,"backup",new String[]{""}).isEmpty() && commands.onTabComplete(block,null,"backup",new String[]{"delete",""}).isEmpty(),"and learns no subcommand or backup ID from completion");
            plugin.delete(block,first.id());plugin.create(block,"manual");plugin.requestRestore(block,first.id());
            check(io.queue.isEmpty() && !busy.get(),"the actions themselves refuse such a sender as well");
            commands.onCommand(sender,null,"backup",new String[]{"delete",first.id(),"confirm"});
            check(io.queue.isEmpty() && Files.exists(first.zipPath()),"legacy confirm syntax performs no deletion");
            check(contains(messages,"Usage: /backup delete <id>"),"legacy confirm syntax reports direct usage");
            permissions.remove("vaultbackup.delete");commands.onCommand(sender,null,"backup",new String[]{"delete",first.id()});
            check(io.queue.isEmpty() && Files.exists(first.zipPath()),"permission denied before direct delete");
            permissions.add("vaultbackup.delete");messages.clear();
            commands.onCommand(sender,null,"backup",new String[]{"delete",first.id()});
            check(io.queue.size()==1 && busy.get(),"one explicit command immediately queues deletion");
            commands.onCommand(sender,null,"backup",new String[]{"delete",first.id()});
            check(io.queue.size()==1 && contains(messages,"Another operation"),"repeated target action cannot queue a second mutation");
            commands.onCommand(sender,null,"backup",new String[]{"list"});
            check(io.queue.size()==1 && contains(messages,"last verified catalogue") && contains(messages,first.id()),"busy list uses cached catalogue");
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"verify"});
            check(io.queue.size()==1 && contains(messages,"Another operation") && !contains(messages,"re-verified") && !contains(messages,"Re-verified"),"a re-verification that cannot run is never reported as done");
            commands.onCommand(sender,null,"backup",new String[]{"status"});
            commands.onCommand(sender,null,"backup",new String[]{"help"});
            check(contains(messages,"Deleting backup") && contains(messages,"VaultBackup Pro 9.9.9-fixture commands"),"status and help stay readable while busy");
            io.runOne();drainMain(main);
            check(!busy.get() && !Files.exists(first.zipPath()) && !Files.exists(first.metadataPath()),"direct delete removes exact archive and metadata");
            check(Files.exists(second.zipPath()) && plugin.backups().size()==1,"other target remains intact");
            commands.onCommand(sender,null,"backup",new String[]{"delete",second.id()});io.runOne();drainMain(main);
            check(plugin.backups().isEmpty(),"last verified archive deletes directly to zero");
            commands.onCommand(sender,null,"backup",new String[]{"delete",second.id()});
            check(io.queue.isEmpty() && contains(messages,"Unknown backup ID"),"repeated completed delete is rejected without mutation");
            BackupRecord recreated=engine.createBackup("recreated");field(plugin,VaultBackupPlugin.class,"catalogue",engine.listBackups());
            check(Files.exists(recreated.zipPath()),"backup creation still works after zero archives");
            RestoreCoordinator.Reservation reserved=coordinator.reserve(recreated.id(),recreated.zipPath(),recreated.sha256(),java.time.Duration.ofMinutes(1));
            AtomicInteger expectedCount=new AtomicInteger(),diagnosticCount=new AtomicInteger();
            try {engine.delete(recreated.id(),coordinator.protectedBackupIds());throw new AssertionError("protected archive deleted");}
            catch(BackupDeletionRefusedException expected) { DeleteFeedback.report(expected,reason->{
                check(reason==BackupDeletionRefusedException.Reason.RESTORE_PROTECTED,"typed restore protection");expectedCount.incrementAndGet();
            },error->diagnosticCount.incrementAndGet()); }
            check(expectedCount.get()==1 && diagnosticCount.get()==0 && Files.exists(recreated.zipPath()),"restore protection never deletes target");
            coordinator.cancel(reserved.token());
            for(Exception error:List.of(new IOException("Backup is missing or corrupt"),new IllegalStateException("unexpected"))) {
                DeleteFeedback.report(error,reason->{throw new AssertionError("untyped error accepted");},actual->{check(actual==error,"original failure preserved");diagnosticCount.incrementAndGet();});
            }
            check(diagnosticCount.get()==2,"unexpected failures use diagnostic path");
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"restore",recreated.id(),"confirm"});
            check(io.queue.isEmpty() && contains(messages,"Usage:"),"legacy restore confirm never launches");
            // Without the bundled helper there is no hash to pin: no restore is offered, whatever file sits at the helper path.
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"restore",recreated.id()});
            check(io.queue.isEmpty() && !busy.get() && contains(messages,"restore helper is not available"),"a plugin without its bundled helper accepts no restore");
            field(plugin,VaultBackupPlugin.class,"helperAvailable",true);
            commands.onCommand(sender,null,"backup",new String[]{"restore",recreated.id()});
            check(io.queue.size()==1 && busy.get() && contains(messages,"Verifying"),"direct restore starts verification immediately");
            commands.onCommand(sender,null,"backup",new String[]{"restore",recreated.id()});
            check(io.queue.size()==1,"repeated restore does not queue another job");
            io.runOne();check(!coordinator.protectedBackupIds().isEmpty(),"verified target is internally pinned before main-thread authorization recheck");
            permissions.remove("vaultbackup.restore");drainMain(main);
            check(!busy.get() && io.queue.isEmpty() && coordinator.protectedBackupIds().isEmpty(),"revoked permission after verification cancels token before commit");
            check(!Files.exists(state.resolve("active-restore.properties")),"revoked permission never publishes a restore");
            permissions.add("vaultbackup.restore");commands.onCommand(sender,null,"backup",new String[]{"restore",recreated.id()});
            commands.onCommand(sender,null,"backup",new String[]{"restore","cancel"});
            check(cancelled.get() && contains(messages,"verification cancellation requested"),"cancel while verifying is accepted");
            io.runOne();drainMain(main);
            check(!busy.get() && coordinator.protectedBackupIds().isEmpty() && !Files.exists(state.resolve("active-restore.properties")),"cancelled verification cannot commit");
            field(plugin,VaultBackupPlugin.class,"restoreStatus",new RestoreCoordinator.Status("fixture-job",recreated.id(),"WAITING_FOR_MANUAL_STOP","",state.resolve("restore-jobs/fixture-job/job.properties"),true));
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"delete",recreated.id()});
            check(io.queue.isEmpty() && Files.exists(recreated.zipPath()) && contains(messages,"protected for restore"),"plugin direct delete blocks reserved target");
            commands.onCommand(sender,null,"backup",new String[]{"restore",recreated.id()});
            check(io.queue.isEmpty() && contains(messages,"reservation already exists"),"plugin refuses a second reserved restore");
            commands.onCommand(sender,null,"backup",new String[]{"status"});commands.onCommand(sender,null,"backup",new String[]{"help"});
            check(contains(messages,"fixture-job") && contains(messages,"VaultBackup Pro 9.9.9-fixture commands"),"reserved status and help remain accessible");
            field(plugin,VaultBackupPlugin.class,"restoreStatus",null);
            check(commands.onTabComplete(sender,null,"backup",new String[]{"delete",recreated.id(),""}).isEmpty(),"no delete confirmation completion");
            check(commands.onTabComplete(sender,null,"backup",new String[]{"restore",recreated.id(),""}).isEmpty(),"no restore confirmation completion");
            for(String language:List.of("ja_JP","en_US")) {
                check(!Messages.text(language,"command.help.restore").contains("confirm"),"direct restore help "+language);
                check(!Messages.text(language,"command.help.delete").contains("confirm"),"direct delete help "+language);
                check(!Messages.text(language,"menu.delete.permanent").isBlank(),"irreversible action lore "+language);
                check(!Messages.text(language,"menu.restore.direct").isBlank(),"direct restore action lore "+language);
                check(Messages.text(language,"backup.retentioncount",0).contains("0"),"zero-retention count is explicit "+language);
            }
            Path source=Path.of(args.length==0?"src/main/java/com/vaultbackup/plugin":args[0]);
            String menus=Files.readString(source.resolve("BackupMenus.java")), pluginSource=Files.readString(source.resolve("VaultBackupPlugin.java"));
            sourceCheck(menus.contains("who -> openInfo(who,record.id(),current)"),"list selection only opens details");
            sourceCheck(menus.contains("who.closeInventory(); plugin.requestRestore(who,id);"),"explicit restore action closes old menu and reserves directly");
            sourceCheck(menus.contains("who.closeInventory(); plugin.delete(who,id,true);"),"explicit delete action closes old menu and deletes directly");
            sourceCheck(!menus.contains("Warning(") && !menus.contains("Final(") && !menus.contains("confirmtwice"),"no warning/final modal routes remain");
            sourceCheck(menus.contains("open.get(player.getUniqueId()) != m") && menus.contains("!plugin.allowed(player,action.permission)"),"queued clicks recheck identity and permission");
            sourceCheck(menus.contains("!event.isLeftClick() || event.isShiftClick()") && menus.contains("ClickType.LEFT"),"non-explicit clicks cannot trigger actions");
            sourceCheck(pluginSource.contains("engine.delete(id, restores.protectedBackupIds())"),"backend restore pin checked again at mutation");
            sourceCheck(!pluginSource.contains("pendingDeletes") && !pluginSource.contains("pendingRestores"),"no actor confirmation maps remain");
            sourceCheck(pluginSource.contains("if (busy() || restoreReserved())"),"automatic backups still defer during restore");
            sourceCheck(pluginSource.contains("restores.requireHelperSha256(HexFormat.of().formatHex(bundled))"),"installed helper digest is pinned before any launch");
            sourceCheck(pluginSource.indexOf("prepareStateDirectory();") >= 0 && pluginSource.indexOf("prepareStateDirectory();") < pluginSource.indexOf("restoreGuardStartupFailure = true;"),
                    "state directory creation failure is a plugin error, not a server shutdown");
            sourceCheck(pluginSource.contains("BackupRecord record = engine.verify(id, this::isCancelled"),"restore reserves the record it just verified");
            sourceCheck(!menus.contains("{ player.closeInventory(); return; }"),"click handler never closes an inventory inside InventoryClickEvent");
            // Simulated Bukkit inventory events exercise actual handlers and queued callbacks.
            // This is not a Minecraft client rendering or real-server inventory test.
            BackupMenus menusObject=new BackupMenus(plugin);field(plugin,VaultBackupPlugin.class,"menus",menusObject);
            permissions.addAll(List.of("vaultbackup.admin","vaultbackup.gui"));
            InventoryView[] openView={null};UUID playerId=UUID.randomUUID();
            // What a player has open when no container is: their own inventory.
            Inventory ownTop=inventory(null,5);
            InventoryView ownView=(InventoryView)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{InventoryView.class},(p,m,a)->switch(m.getName()) {
                case "getTopInventory" -> ownTop;
                default -> defaultValue(m.getReturnType());
            });
            Player player=(Player)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->switch(m.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "fixture-player";
                case "isOnline" -> true;
                case "hasPermission" -> permissions.contains(a[0]);
                case "getOpenInventory" -> openView[0]!=null?openView[0]:ownView;
                case "openInventory" -> {
                    if(openView[0]!=null)menusObject.close(new InventoryCloseEvent(openView[0]));
                    openView[0]=view((Player)p,(Inventory)a[0]);yield openView[0];
                }
                case "closeInventory" -> {
                    if(openView[0]!=null)menusObject.close(new InventoryCloseEvent(openView[0]));
                    openView[0]=null;yield null;
                }
                case "sendMessage" -> {for(Object v:a)if(v instanceof String text)messages.add(text);yield null;}
                default -> defaultValue(m.getReturnType());
            });
            BackupRecord guiTarget=engine.createBackup("gui-target");
            field(plugin,VaultBackupPlugin.class,"catalogue",List.of(guiTarget,recreated));
            menusObject.openList(player,0);
            InventoryView listView=openView[0];
            check(click(menusObject,listView,0,ClickType.LEFT).isCancelled(),"GUI blocks moving its inventory items");
            drainMain(main);
            check(openView[0]!=listView && io.queue.isEmpty() && Files.exists(guiTarget.zipPath()),"GUI list selection only navigates to target details");
            InventoryView detailView=openView[0];
            List<String> deleteLore=detailView.getTopInventory().getItem(15).getItemMeta().getLore();
            check(deleteLore.stream().anyMatch(line->line.contains(guiTarget.id())) && deleteLore.stream().anyMatch(line->line.contains("permanent")),"GUI explicit delete shows exact target and irreversible consequence");
            click(menusObject,detailView,15,ClickType.RIGHT);click(menusObject,detailView,15,ClickType.SHIFT_LEFT);
            drainMain(main);check(io.queue.isEmpty(),"GUI right/shift click cannot execute delete");
            click(menusObject,detailView,15,ClickType.LEFT);permissions.remove("vaultbackup.delete");drainMain(main);
            check(io.queue.isEmpty() && Files.exists(guiTarget.zipPath()),"GUI queued action rechecks revoked delete permission");
            permissions.add("vaultbackup.delete");
            click(menusObject,detailView,15,ClickType.LEFT);click(menusObject,detailView,15,ClickType.LEFT);drainMain(main);
            check(io.queue.size()==1 && busy.get() && openView[0]==null,"GUI double-click dispatches one direct delete and closes stale menu");
            io.runOne();drainMain(main);
            check(!Files.exists(guiTarget.zipPath()) && Files.exists(recreated.zipPath()),"GUI deletes only its server-owned selected target");
            check(openView[0]!=null && openView[0]!=detailView,"a delete started from the menu returns to the list");
            // The same delete typed as a command never opens a menu on the player's screen.
            BackupRecord typed=engine.createBackup("typed");field(plugin,VaultBackupPlugin.class,"catalogue",engine.listBackups());
            player.closeInventory();plugin.delete(player,typed.id());io.runOne();drainMain(main);
            check(!Files.exists(typed.zipPath()) && openView[0]==null,"a delete typed as a command opens no menu");
            // Started from the menu, but the player opened a chest before it finished: nothing is put over it.
            BackupRecord chest=engine.createBackup("chest");field(plugin,VaultBackupPlugin.class,"catalogue",engine.listBackups());
            plugin.delete(player,chest.id(),true);
            menusObject.opened(new org.bukkit.event.inventory.InventoryOpenEvent(view(player,inventory(null,27))));
            io.runOne();drainMain(main);
            check(!Files.exists(chest.zipPath()) && openView[0]==null,"the list is not put over something the player opened meanwhile");
            click(menusObject,detailView,15,ClickType.LEFT);drainMain(main);
            check(io.queue.isEmpty(),"stale GUI view cannot replay an action");
            menusObject.openList(player,0);click(menusObject,openView[0],0,ClickType.LEFT);drainMain(main);
            detailView=openView[0];
            click(menusObject,detailView,11,ClickType.LEFT);permissions.remove("vaultbackup.restore");drainMain(main);
            check(io.queue.isEmpty(),"GUI queued restore rechecks permission before verification");
            permissions.add("vaultbackup.restore");click(menusObject,detailView,11,ClickType.LEFT);click(menusObject,detailView,11,ClickType.LEFT);drainMain(main);
            check(io.queue.size()==1 && openView[0]==null,"GUI explicit restore immediately verifies once without a confirmation modal");
            plugin.cancelRestore(player);io.runOne();drainMain(main);
            check(!busy.get() && !Files.exists(state.resolve("active-restore.properties")),"GUI-started verification remains cancellable");
            var outcomeMethod=VaultBackupPlugin.class.getDeclaredMethod("reportBackupOutcome",CommandSender.class,BackupRecord.class,PluginSettings.class);
            outcomeMethod.setAccessible(true);
            messages.clear();outcomeMethod.invoke(plugin,sender,recreated,settings);drainMain(main);
            check(contains(messages,"Backup created:") && contains(messages,"after retention: 1"),"retained outcome requires current verification");
            // Model an interrupted deletion that withdrew metadata before a directory fsync failed.
            Files.delete(recreated.metadataPath());messages.clear();outcomeMethod.invoke(plugin,sender,recreated,settings);drainMain(main);
            check(!contains(messages,"Backup created:") && contains(messages,"not a verified recovery point"),"partial deletion never claims retained success");
            check(contains(messages,"after retention: 0") && contains(messages,"No recovery point"),"partial deletion reports zero verified recovery points");
            Files.delete(recreated.zipPath());messages.clear();outcomeMethod.invoke(plugin,sender,recreated,settings);drainMain(main);
            check(!contains(messages,"Backup created:") && contains(messages,"removed by retention"),"removed archive outcome is truthful after deletion errors");
            Path hidden=fixture.resolve("unavailable-backups");Files.move(backup,hidden);
            messages.clear();outcomeMethod.invoke(plugin,sender,recreated,settings);drainMain(main);
            check(!contains(messages,"Backup created:") && contains(messages,"could not be verified"),"unreadable store reports availability unknown");
            check(!contains(messages,"Verified backups after retention:"),"unreadable store never invents a current count");
            Files.move(hidden,backup);
            permissions.add("vaultbackup.create");
            field(plugin,VaultBackupPlugin.class,"autosave",new java.util.LinkedHashSet<>());
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"create","immediate"});
            check(contains(messages,"Starting backup. Saving worlds"),"create immediately acknowledges before mandatory save");
            check(!contains(messages,"World save executed") && !contains(messages,"Backup created:"),"acceptance does not falsely claim saving or backup completion");
            drainMain(main);
            check(!busy.get() && io.queue.isEmpty(),"failed fixture save cannot enqueue compression or leave busy state");
            sourceCheck(pluginSource.indexOf("RestoreCoordinator.start(") < pluginSource.indexOf("engine = new BackupEngine(settings.options())")
                    && !pluginSource.contains("createPrivateDirectories(settings.backupDirectory())"),"root guard precedes archive directory setup");
            // A command changes exactly one value of config.yml. What was edited by hand needs an explicit reload.
            List<String> logged=new ArrayList<>();
            logger.addHandler(new java.util.logging.Handler() {
                @Override public void publish(java.util.logging.LogRecord record) { logged.add(record.getMessage()); }
                @Override public void flush() {}
                @Override public void close() {}
            });
            Path data=Files.createDirectory(fixture.resolve("plugin-data")),realConfig=data.resolve("real-config.yml"),config=data.resolve("config.yml");
            field(plugin,JavaPlugin.class,"dataFolder",data.toFile());
            org.bukkit.configuration.file.YamlConfiguration yaml=new org.bukkit.configuration.file.YamlConfiguration();
            yaml.set("language","en_US");yaml.set("storage.server-root",root.toString());yaml.set("storage.backup-directory",backup.toString());
            yaml.set("storage.state-directory",state.toString());yaml.set("restore.helper-jar",helper.toString());
            yaml.set("backup.include",List.of("**"));yaml.set("backup.exclude",List.of("logs/**"));yaml.set("automatic.enabled",false);
            Files.writeString(realConfig,yaml.saveToString());
            boolean links=true;
            try {Files.createSymbolicLink(config,realConfig.getFileName());} catch(UnsupportedOperationException | IOException noLinks) {links=false;Files.move(realConfig,config);}
            boolean posix=Files.getFileStore(data).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class);
            if(posix)Files.setPosixFilePermissions(links?realConfig:config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
            field(plugin,VaultBackupPlugin.class,"settings",PluginSettings.read(config));
            BackupEngine before=engine;
            permissions.addAll(List.of("vaultbackup.configure","vaultbackup.reload"));
            messages.clear();logged.clear();commands.onCommand(sender,null,"backup",new String[]{"interval","30"});
            check(contains(messages,"Setting updated: automatic.interval-minutes") && PluginSettings.read(config).intervalMinutes()==30 && plugin.settings().intervalMinutes()==30,"a setting command changes its one value in the file and in memory");
            check(contains(logged,"[audit] fixture-console: changed a setting: automatic.interval-minutes = 30"),"who changed which setting is logged");
            var engineField=VaultBackupPlugin.class.getDeclaredField("engine");engineField.setAccessible(true);
            check(engineField.get(plugin)==before,"the engine, with what it has verified, is kept when nothing it uses changed");
            if(links)check(Files.isSymbolicLink(config) && Files.readString(realConfig).contains("interval-minutes: 30"),"a linked config.yml stays a link to the same file");
            if(posix)check(Files.getPosixFilePermissions(links?realConfig:config).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----")),"config.yml keeps its permissions");
            // An edit made by hand: a different retention count, not yet applied.
            org.bukkit.configuration.file.YamlConfiguration edited=new org.bukkit.configuration.file.YamlConfiguration();edited.load(config.toFile());
            edited.set("retention.max-count",3);Files.writeString(links?realConfig:config,edited.saveToString());
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"interval","15"});
            check(contains(messages,"have not been applied yet") && plugin.settings().intervalMinutes()==30 && plugin.settings().retentionCount()==20
                    && PluginSettings.read(config).intervalMinutes()==30,"a command never applies hand edits as a side effect, and changes nothing");
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"reload"});
            check(contains(messages,"Configuration reloaded") && plugin.settings().retentionCount()==3,"an explicit reload applies them");
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"interval","15"});
            check(contains(messages,"Setting updated") && plugin.settings().intervalMinutes()==15 && plugin.settings().retentionCount()==3,"and commands work again afterwards");
            Files.writeString(links?realConfig:config,"language: [not, a, language]\n");
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"reload"});
            check(contains(messages,"Configuration rejected") && plugin.settings().intervalMinutes()==15,"an invalid file is rejected and the active settings stay");
            // /backup create typed as a command, against a server model with one world.
            boolean[] autosave={true};UUID worldId=UUID.randomUUID();
            org.bukkit.World world=(org.bukkit.World)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{org.bukkit.World.class},(p,m,a)->switch(m.getName()) {
                case "getUID" -> worldId;
                case "getName" -> "world";
                case "getWorldFolder" -> root.toFile();
                case "isAutoSave" -> autosave[0];
                case "setAutoSave" -> {autosave[0]=(Boolean)a[0];yield null;}
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p==a[0];
                case "toString" -> "fixture world";
                default -> defaultValue(m.getReturnType());
            });
            SpigotPlatform live=new SpigotPlatform(plugin);field(plugin,VaultBackupPlugin.class,"platform",live);saving[0]=live;
            worlds.add(world);primary[0]=true;
            // The model: a save issued while a command is being executed has not run when the handler looks for it.
            inCommand[0]=true;
            try {live.saveAll();throw new AssertionError("a save that is still queued behind the running command was reported as done");}
            catch(IOException unconfirmed) {checks++;}
            finally {inCommand[0]=false;nested.clear();}
            for(CommandSender issuer:List.of(sender,player)) {
                dispatched.clear();messages.clear();logged.clear();
                int stored=engine.listBackups().size();
                inCommand[0]=true;commands.onCommand(issuer,null,"backup",new String[]{"create","typed"});inCommand[0]=false;
                while(!nested.isEmpty())nested.removeFirst().run();
                check(dispatched.isEmpty() && busy.get() && autosave[0] && contains(messages,"Starting backup"),"the command handler itself issues no save and reports none");
                drainMain(main);
                check(dispatched.equals(List.of("direct:minecraft:save-all flush")) && contains(messages,"World save executed") && io.queue.size()==1 && !autosave[0],
                        "the save runs in a task of its own, is confirmed there, and world saving is suspended for the copy");
                io.runOne();
                check(!autosave[0] && busy.get(),"the worker changes no world setting itself");
                main.removeFirst().run();
                check(autosave[0] && busy.get(),"world saving is switched back on as soon as the server files have been read, before the archive is verified and reported");
                drainMain(main);
                check(!busy.get() && autosave[0] && contains(messages,"Backup created:") && engine.listBackups().stream().anyMatch(r->r.label().equals("typed")),"the backup typed as a command completes");
                check(contains(logged,"[audit] "+issuer.getName()+": started a backup (label: typed)"),"who started the backup is logged");
                // The console sees its own messages in the log; what a player was told is logged for them.
                check(contains(logged,"Backup created:")==(issuer==player),"the result a player was told is in the server log as well");
                check(stored>=0,"fixture");
            }
            // A restore reservation stays cancellable while a long catalogue job runs: a server stopped in the meantime would apply it.
            field(plugin,VaultBackupPlugin.class,"armedJob","00000000-0000-4000-8000-000000000000");
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"verify"});
            check(io.queue.size()==1 && busy.get(),"a complete re-verification is queued");
            commands.onCommand(sender,null,"backup",new String[]{"restore","cancel"});
            check(contains(messages,"Stopping the running catalogue verification") && cancelled.get() && io.queue.size()==1,"the cancellation is accepted and the catalogue job is told to stop");
            io.runOne();drainMain(main);
            check(!contains(messages,"re-verified") && !contains(messages,"failed") && contains(messages,"was stopped so that a restore reservation could be cancelled") && io.queue.size()==1 && busy.get(),
                    "the stopped catalogue job reports neither success nor failure but says why it stopped, and the cancellation follows by itself");
            io.runOne();drainMain(main);
            check(!busy.get() && !plugin.restoreReserved() && io.queue.isEmpty() && contains(messages,"Restore reservation cancelled"),"the reservation is cancelled without a second command");
            System.out.println("PASS DeleteWorkflowTest ("+checks+" executed checks; "+sourceChecks+" source-only GUI wiring checks; includes simulated Bukkit events, no real client or helper launch)");
        } finally {
            try(var paths=Files.walk(fixture)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path); }
        }
    }
}
