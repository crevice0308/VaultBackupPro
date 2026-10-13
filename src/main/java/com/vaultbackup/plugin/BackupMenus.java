// SPDX-License-Identifier: GPL-3.0-only
package com.vaultbackup.plugin;

import com.vaultbackup.core.BackupRecord;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/** Server-owned holders and action maps; no authorization or target IDs come from item display text. */
final class BackupMenus implements Listener {
    private final VaultBackupPlugin plugin;
    private final Map<UUID, Menu> open = new HashMap<>();
    BackupMenus(VaultBackupPlugin plugin) { this.plugin = plugin; }
    private String t(String key, Object... args) { return plugin.text(key,args); }
    private String yes(boolean value) { return t(value ? "common.yes" : "common.no"); }
    private String on(boolean value) { return t(value ? "common.on" : "common.off"); }
    private boolean access(Player p) {
        if (!p.hasPermission("vaultbackup.admin") || !p.hasPermission("vaultbackup.gui")) {
            plugin.tell(p,t("permission.gui"),false); return false;
        }
        return true;
    }
    private Menu make(Player p, String title, int size) { return new Menu(p.getUniqueId(),title,size); }
    private void show(Player p, Menu menu) {
        if (!access(p) || !p.isOnline()) return;
        p.openInventory(menu.inventory); open.put(p.getUniqueId(),menu);
    }
    void openMain(Player p) {
        if (!access(p)) return;
        Menu m = make(p,t("menu.main"),27);
        m.put(10,Material.EMERALD_BLOCK,t("menu.create"),List.of(t("menu.create.save"),t("menu.create.async")),"create",who -> { who.closeInventory(); plugin.create(who,"manual"); });
        m.put(12,Material.CHEST,t("menu.catalogue"),List.of(t("menu.catalogue.count",plugin.backups().size()),t("menu.catalogue.click")),"list",who -> plugin.refresh(who,() -> openList(who,0)));
        m.put(14,Material.CLOCK,t("menu.auto"),List.of(t("common.automatic",on(plugin.settings().automatic())),t("common.interval",plugin.settings().intervalMinutes())),"configure",this::openAutomatic);
        m.put(16,Material.COMPARATOR,t("menu.settings"),List.of(t("menu.settings.details"),t("menu.settings.validate")),"configure",this::openSettings);
        m.put(22,Material.PAPER,t("menu.status"),List.of(plugin.operation(),plugin.progress(),t("common.free",VaultBackupPlugin.humanBytes(plugin.freeSpace()))),"status",who -> { plugin.status(who); openStorage(who); });
        if (plugin.restoreReserved()) m.put(24,Material.BARRIER,t("menu.restore.cancel"),
                List.of(plugin.restoreState(),t("menu.restore.cancelhint")),"restore",who -> { who.closeInventory(); plugin.cancelRestore(who); });
        show(p,m);
    }
    void openList(Player p, int page) {
        if (!access(p) || !plugin.allowed(p,"list")) return;
        List<BackupRecord> records = plugin.backups();
        int pages = Math.max(1,(records.size()+44)/45);
        int current = Math.max(0,Math.min(page,pages-1));
        Menu m = make(p,t("menu.list",current+1,pages),54);
        int start = current*45;
        for (int i=start; i<Math.min(start+45,records.size()); i++) {
            BackupRecord record = records.get(i);
            m.put(i-start,Material.ENDER_CHEST,record.id(),List.of(t("common.created",record.createdAt()),t("common.label",record.label()),
                    t("common.archive",VaultBackupPlugin.humanBytes(record.compressedBytes())),t("common.entries",record.entryCount()),t("menu.details")),"list",who -> openInfo(who,record.id(),current));
        }
        if (records.isEmpty()) m.display(22,Material.BARRIER,t("menu.empty"),List.of(t("menu.empty.hint")));
        if (current>0) m.put(45,Material.ARROW,t("menu.previous"),List.of(),"list",who -> openList(who,current-1));
        m.put(48,Material.COMPASS,t("common.dashboard"),List.of(),"gui",this::openMain);
        m.put(49,Material.SPYGLASS,t("menu.refresh"),List.of(t("menu.refresh.hint")),"list",who -> plugin.refresh(who,() -> openList(who,current)));
        if (current+1<pages) m.put(53,Material.ARROW,t("menu.next"),List.of(),"list",who -> openList(who,current+1));
        show(p,m);
    }
    private void openInfo(Player p, String id, int page) {
        BackupRecord record = plugin.find(id).orElse(null);
        if (record == null) { plugin.tell(p,t("list.missing"),false); openList(p,page); return; }
        Menu m = make(p,t("menu.info"),27);
        m.display(4,Material.WRITABLE_BOOK,id,List.of(t("common.created",record.createdAt()),t("common.label",record.label()),
                t("common.archive",VaultBackupPlugin.humanBytes(record.compressedBytes())),t("common.original",VaultBackupPlugin.humanBytes(record.totalBytes())),
                t("common.entries",record.entryCount()),"SHA-256: "+record.sha256().substring(0,16)+"…"));
        m.put(11,Material.LIME_CONCRETE,t("menu.restore"),List.of(t("menu.confirmtwice"),t("menu.restore.manual"),t("menu.restore.rollback")),"restore",who -> restoreWarning(who,id,page));
        m.put(15,Material.RED_CONCRETE,t("menu.delete"),List.of(t("menu.delete.files"),t("menu.confirmtwice"),t("menu.delete.last"),t("menu.delete.zero"),t("menu.delete.norecovery"),t("menu.delete.pinned")),"delete",who -> deleteWarning(who,id,page));
        m.put(22,Material.ARROW,t("menu.back"),List.of(),"list",who -> openList(who,page));
        show(p,m);
    }
    private void restoreWarning(Player p, String id, int page) {
        Menu m = make(p,t("menu.restore.warning"),27);
        m.display(4,Material.TNT,t("menu.restore.warningtitle"),List.of(t("menu.selected",id),t("menu.noautorestart"),t("menu.excluded"),t("menu.norestart")));
        m.put(11,Material.ORANGE_CONCRETE,t("menu.verify"),List.of(t("menu.verify.hint")),"restore",who -> {
            who.closeInventory(); plugin.requestRestore(who,id,token -> restoreFinal(who,id,page));
        });
        m.put(15,Material.BARRIER,t("common.cancel"),List.of(),"gui",who -> openInfo(who,id,page));
        show(p,m);
    }
    private void restoreFinal(Player p, String id, int page) {
        Menu m = make(p,t("menu.restore.final"),27);
        m.display(4,Material.REDSTONE_BLOCK,t("menu.restore.question",id),List.of(t("menu.restore.keepsrunning"),t("menu.restore.restart"),t("menu.expires",plugin.settings().confirmationSeconds())));
        m.put(11,Material.LIME_CONCRETE,t("menu.restore.confirm"),List.of(t("menu.finalhint")),"restore",who -> { who.closeInventory(); plugin.confirmRestore(who,id); });
        m.put(15,Material.BARRIER,t("menu.restore.cancel"),List.of(),"restore",who -> { plugin.cancelRestore(who); openInfo(who,id,page); });
        show(p,m);
    }
    private void deleteWarning(Player p, String id, int page) {
        Menu m = make(p,t("menu.delete.warning"),27);
        m.display(4,Material.TNT,t("menu.delete.question",id),List.of(t("menu.delete.files"),t("menu.delete.permanent"),t("menu.delete.last"),t("menu.delete.zero"),t("menu.delete.norecovery"),t("menu.delete.pinned")));
        m.put(11,Material.ORANGE_CONCRETE,t("menu.delete.continue"),List.of(),"delete",who -> { if (plugin.requestDelete(who,id)) deleteFinal(who,id,page); });
        m.put(15,Material.BARRIER,t("common.cancel"),List.of(),"gui",who -> openInfo(who,id,page));
        show(p,m);
    }
    void deleteFinal(Player p, String id, int page) {
        Menu m = make(p,t("menu.delete.final"),27);
        boolean warnLast = plugin.lastDeleteWarning(p,id);
        m.display(4,Material.REDSTONE_BLOCK,id,List.of(t("menu.delete.noundo"),t("menu.delete.last"),t("menu.delete.zero"),t("menu.delete.norecovery"),t("menu.delete.pinned")));
        m.put(11,Material.RED_CONCRETE,t(warnLast ? "menu.delete.confirm.last" : "menu.delete.confirm"),
                warnLast ? List.of(t("menu.delete.zero"),t("menu.delete.norecovery")) : List.of(),
                "delete",who -> { who.closeInventory(); plugin.delete(who,id); });
        m.put(15,Material.BARRIER,t("common.cancel"),List.of(),"gui",who -> { plugin.cancelDelete(who,id); openInfo(who,id,page); });
        show(p,m);
    }
    private void openAutomatic(Player p) {
        Menu m = make(p,t("menu.auto"),27);
        m.put(4,plugin.settings().automatic()?Material.LIME_DYE:Material.GRAY_DYE,t("common.automatic",on(plugin.settings().automatic())),
                List.of(t("menu.toggle"),t("common.interval",plugin.settings().intervalMinutes())),"configure",who -> {
                    plugin.setting(who,"automatic.enabled",!plugin.settings().automatic()); openAutomatic(who);
                });
        long[] minutes = {5,15,30,60,180,360,720,1440};
        for (int i=0;i<minutes.length;i++) {
            long value=minutes[i]; String text=value<60?t("common.minutes",value):t("common.hours",value/60);
            m.put(10+i,Material.CLOCK,text,List.of(t("menu.interval.set",value)),"configure",who -> { plugin.setting(who,"automatic.interval-minutes",value); openAutomatic(who); });
        }
        m.put(21,Material.OAK_SIGN,t("menu.interval.custom"),List.of(t("menu.interval.use"),t("menu.interval.range")),"configure",who -> { who.closeInventory(); plugin.tell(who,t("menu.interval.message"),true); });
        m.put(23,Material.ARROW,t("common.dashboard"),List.of(),"gui",this::openMain);
        show(p,m);
    }
    private void openSettings(Player p) {
        PluginSettings s = plugin.settings();
        Menu m = make(p,t("menu.settings"),27);
        m.put(10,Material.BOOK,t("menu.retention.count"),List.of(t("common.current",s.retentionCount()==0?t("common.unlimited"):s.retentionCount()),t("menu.retention.countuse"),t("menu.retention.countzero")),"configure",who -> { who.closeInventory(); plugin.tell(who,t("menu.retention.countmessage"),true); });
        m.put(12,Material.HOPPER,t("menu.retention.bytes"),List.of(t("common.current",s.retentionBytes()==0?t("common.unlimited"):VaultBackupPlugin.humanBytes(s.retentionBytes())),t("menu.retention.bytesuse"),t("menu.retention.after")),"configure",who -> { who.closeInventory(); plugin.tell(who,t("menu.retention.bytesmessage"),true); });
        m.put(14,Material.BARREL,t("menu.storage"),List.of(t("common.free",VaultBackupPlugin.humanBytes(plugin.freeSpace())),t("menu.storage.restart")),"status",this::openStorage);
        m.put(16,Material.REPEATER,t("menu.reload"),List.of(t("menu.compression",s.compressionLevel()),t("menu.reload.validate"),t("menu.reload.invalid")),"reload",who -> { plugin.reloadSettings(who); openSettings(who); });
        m.put(22,Material.ARROW,t("common.dashboard"),List.of(),"gui",this::openMain); show(p,m);
    }
    private void openStorage(Player p) {
        PluginSettings s=plugin.settings();
        Menu m=make(p,t("menu.storage.title"),27);
        long used=plugin.backups().stream().mapToLong(BackupRecord::compressedBytes).sum();
        m.display(10,Material.CHEST,t("menu.storage.backups"),List.of(s.backupDirectory().toString(),t("menu.storage.used",VaultBackupPlugin.humanBytes(used)),t("common.free",VaultBackupPlugin.humanBytes(plugin.freeSpace())),t("menu.catalogue.count",plugin.backups().size())));
        m.display(12,Material.COMPARATOR,t("menu.status"),List.of(plugin.operation(),plugin.progress(),t("common.next",plugin.nextAutomatic()==null?t("common.off"):plugin.nextAutomatic())));
        m.display(14,Material.IRON_DOOR,t("menu.storage.helper"),List.of(s.helperJar().toString(),t("common.available",yes(Files.isRegularFile(s.helperJar()))),t("common.state",s.stateDirectory()),t("menu.storage.offline")));
        m.put(16,Material.WRITABLE_BOOK,t("menu.storage.settings"),List.of(t("menu.storage.edit"),t("menu.storage.restart"),t("menu.storage.external")),"configure",who -> plugin.tell(who,t("menu.storage.message"),true));
        if (plugin.restoreReserved()) {
            m.display(20,Material.PAPER,t("operation.reserved"),List.of(plugin.restoreState(),
                    t("restore.log",plugin.restoreLog()),t("restore.statusfile",plugin.restoreStatusFile()),t("menu.restore.restart")));
            m.put(24,Material.BARRIER,t("menu.restore.cancel"),List.of(t("menu.restore.cancelhint")),"restore",who -> { who.closeInventory(); plugin.cancelRestore(who); });
        }
        m.put(22,Material.ARROW,t("common.dashboard"),List.of(),"gui",this::openMain); show(p,m);
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        event.setCancelled(true); // Covers bottom inventory, shift, number-key, swap, drop and collect-to-cursor.
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!m.owner.equals(player.getUniqueId()) || open.get(player.getUniqueId()) != m || !access(player)) { player.closeInventory(); return; }
        if (!event.isLeftClick() || event.isShiftClick() || event.getClick() != org.bukkit.event.inventory.ClickType.LEFT) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= m.inventory.getSize()) return;
        Action action = m.actions.get(slot);
        if (action == null || !plugin.allowed(player,action.permission)) return;
        // Run next tick to avoid inventory mutation from inside InventoryClickEvent.
        Bukkit.getScheduler().runTask(plugin,() -> {
            if (!player.isOnline() || open.get(player.getUniqueId()) != m || !access(player) || !plugin.allowed(player,action.permission)) return;
            action.callback.accept(player);
        });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu m) open.remove(event.getPlayer().getUniqueId(),m);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { open.remove(event.getPlayer().getUniqueId()); }
    void closeAll() {
        for (Player p:Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
        open.clear();
    }
    private record Action(String permission, Consumer<Player> callback) {}
    private static final class Menu implements InventoryHolder {
        final UUID owner;
        final Inventory inventory;
        final Map<Integer,Action> actions=new HashMap<>();
        Menu(UUID owner,String title,int size) {
            this.owner=owner; inventory=Bukkit.createInventory(this,size,ChatColor.DARK_AQUA+title);
            ItemStack filler=item(Material.GRAY_STAINED_GLASS_PANE," ",List.of());
            for (int slot=0;slot<size;slot++) inventory.setItem(slot,filler);
        }
        @Override public Inventory getInventory() { return inventory; }
        void put(int slot,Material type,String title,List<String> lore,String permission,Consumer<Player> callback) {
            display(slot,type,title,lore); actions.put(slot,new Action(permission,callback));
        }
        void display(int slot,Material type,String title,List<String> lore) { inventory.setItem(slot,item(type,title,lore)); }
        private static ItemStack item(Material type,String title,List<String> lore) {
            ItemStack stack=new ItemStack(type);
            ItemMeta meta=stack.getItemMeta();
            meta.setDisplayName(ChatColor.RESET.toString()+ChatColor.GOLD+title);
            meta.setLore(lore.stream().map(line -> ChatColor.RESET.toString()+ChatColor.GRAY+line).toList());
            stack.setItemMeta(meta); return stack;
        }
    }
}
