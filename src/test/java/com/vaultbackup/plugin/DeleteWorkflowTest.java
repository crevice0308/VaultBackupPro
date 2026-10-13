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
            BukkitScheduler scheduler=(BukkitScheduler)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),
                    new Class<?>[]{BukkitScheduler.class},(p,m,a)->{
                        if(m.getName().equals("runTask") && a[1] instanceof Runnable task)main.add(task);
                        return defaultValue(m.getReturnType());
                    });
            Server server=(Server)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{Server.class},
                    (p,m,a)->switch(m.getName()) {
                        case "getScheduler" -> scheduler;
                        case "getLogger" -> java.util.logging.Logger.getLogger("VaultBackupFixture");
                        case "getName" -> "Spigot";
                        case "getVersion", "getBukkitVersion" -> "26.2-R0.1-SNAPSHOT";
                        case "getItemFactory" -> itemFactory();
                        case "createInventory" -> inventory((InventoryHolder)a[0],(Integer)a[1]);
                        default -> defaultValue(m.getReturnType());
                    });
            // JavaPlugin's real constructor is server-loader-only. Allocate a fixture instance and
            // supply explicit test state; no server startup/lifecycle or real user data is involved.
            Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field uf=unsafeType.getDeclaredField("theUnsafe");uf.setAccessible(true);
            VaultBackupPlugin plugin=(VaultBackupPlugin)unsafeType.getMethod("allocateInstance",Class.class).invoke(uf.get(null),VaultBackupPlugin.class);
            field(plugin,JavaPlugin.class,"server",server);field(plugin,JavaPlugin.class,"isEnabled",true);
            field(plugin,JavaPlugin.class,"description",new org.bukkit.plugin.PluginDescriptionFile("VaultBackupFixture","1.2.0",VaultBackupPlugin.class.getName()));
            org.bukkit.plugin.PluginLogger logger=new org.bukkit.plugin.PluginLogger(plugin);logger.setUseParentHandlers(false);
            field(plugin,JavaPlugin.class,"logger",logger);
            field(plugin,VaultBackupPlugin.class,"settings",settings);field(plugin,VaultBackupPlugin.class,"engine",engine);
            field(plugin,VaultBackupPlugin.class,"restores",coordinator);field(plugin,VaultBackupPlugin.class,"io",io);
            AtomicBoolean busy=new AtomicBoolean(),cancelled=new AtomicBoolean();
            field(plugin,VaultBackupPlugin.class,"busy",busy);field(plugin,VaultBackupPlugin.class,"cancelled",cancelled);
            field(plugin,VaultBackupPlugin.class,"operation","operation.idle");field(plugin,VaultBackupPlugin.class,"progress","Ready");
            Set<String> permissions=new HashSet<>(List.of("vaultbackup.delete","vaultbackup.restore","vaultbackup.list","vaultbackup.status"));
            List<String> messages=new ArrayList<>();
            CommandSender sender=(CommandSender)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{CommandSender.class},(p,m,a)->{
                if(m.getName().equals("hasPermission"))return permissions.contains(a[0]);
                if(m.getName().equals("getName"))return "fixture-console";
                if(m.getName().equals("sendMessage")) { for(Object v:a)if(v instanceof String text)messages.add(text);return null; }
                return defaultValue(m.getReturnType());
            });
            BackupCommands commands=new BackupCommands(plugin);
            Bukkit.setServer(server);
            BackupRecord first=engine.createBackup("first"),second=engine.createBackup("second");
            field(plugin,VaultBackupPlugin.class,"catalogue",engine.listBackups());
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
            commands.onCommand(sender,null,"backup",new String[]{"status"});
            commands.onCommand(sender,null,"backup",new String[]{"help"});
            check(contains(messages,"Deleting backup") && contains(messages,"VaultBackup Pro 1.2.0 commands"),"status and help stay readable while busy");
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
            check(contains(messages,"fixture-job") && contains(messages,"VaultBackup Pro 1.2.0 commands"),"reserved status and help remain accessible");
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
            sourceCheck(menus.contains("who.closeInventory(); plugin.delete(who,id);"),"explicit delete action closes old menu and deletes directly");
            sourceCheck(!menus.contains("Warning(") && !menus.contains("Final(") && !menus.contains("confirmtwice"),"no warning/final modal routes remain");
            sourceCheck(menus.contains("open.get(player.getUniqueId()) != m") && menus.contains("!plugin.allowed(player,action.permission)"),"queued clicks recheck identity and permission");
            sourceCheck(menus.contains("!event.isLeftClick() || event.isShiftClick()") && menus.contains("ClickType.LEFT"),"non-explicit clicks cannot trigger actions");
            sourceCheck(pluginSource.contains("engine.delete(id, restores.protectedBackupIds())"),"backend restore pin checked again at mutation");
            sourceCheck(!pluginSource.contains("pendingDeletes") && !pluginSource.contains("pendingRestores"),"no actor confirmation maps remain");
            sourceCheck(pluginSource.contains("if (busy() || restoreReserved())"),"automatic backups still defer during restore");
            // Simulated Bukkit inventory events exercise actual handlers and queued callbacks.
            // This is not a Minecraft client rendering or real-server inventory test.
            BackupMenus menusObject=new BackupMenus(plugin);field(plugin,VaultBackupPlugin.class,"menus",menusObject);
            permissions.addAll(List.of("vaultbackup.admin","vaultbackup.gui"));
            InventoryView[] openView={null};UUID playerId=UUID.randomUUID();
            Player player=(Player)Proxy.newProxyInstance(DeleteWorkflowTest.class.getClassLoader(),new Class<?>[]{Player.class},(p,m,a)->switch(m.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "fixture-player";
                case "isOnline" -> true;
                case "hasPermission" -> permissions.contains(a[0]);
                case "getOpenInventory" -> openView[0];
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
            field(plugin,VaultBackupPlugin.class,"autosave",new java.util.LinkedHashMap<>());
            messages.clear();commands.onCommand(sender,null,"backup",new String[]{"create","immediate"});
            check(contains(messages,"Starting backup. Saving worlds"),"create immediately acknowledges before mandatory save");
            check(!contains(messages,"Saving complete") && !contains(messages,"Backup created:"),"acceptance does not falsely claim saving or backup completion");
            drainMain(main);
            check(!busy.get() && io.queue.isEmpty(),"failed fixture save cannot enqueue compression or leave busy state");
            sourceCheck(pluginSource.indexOf("RestoreCoordinator.start(") < pluginSource.indexOf("engine = new BackupEngine(settings.options())")
                    && !pluginSource.contains("createPrivateDirectories(settings.backupDirectory())"),"root guard precedes archive directory setup");
            System.out.println("PASS DeleteWorkflowTest ("+checks+" executed checks; "+sourceChecks+" source-only GUI wiring checks; includes simulated Bukkit events, no real client or helper launch)");
        } finally {
            try(var paths=Files.walk(fixture)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path); }
        }
    }
}
