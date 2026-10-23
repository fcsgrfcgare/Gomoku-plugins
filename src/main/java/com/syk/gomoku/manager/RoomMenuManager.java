/*
 * Gomoku - Paper 五子棋插件
 * Copyright (C) 2026 syk
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.syk.gomoku.manager;

import com.syk.gomoku.GomokuPlugin;
import com.syk.gomoku.model.Room;
import com.syk.gomoku.session.GameSession;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 大厅快捷栏的下界之星 —— 打开一个 54 格的房间菜单。
 *
 * <ul>
 *   <li>空闲房间显示为工作台 ({@code WORKBENCH})，点击加入对局</li>
 *   <li>对局中的房间显示为红石块 ({@code REDSTONE_BLOCK})，点击进入观战</li>
 *   <li>最下方中间是铁砧 ({@code ANVIL})，点击自动匹配</li>
 * </ul>
 *
 * <p>物品位置/是否启用写在 config.yml 的 {@code hotbar.menu.*}。材质名兼容 1.8 的旧名
 * (WORKBENCH / NETHER_STAR / REDSTONE_BLOCK / ANVIL)。</p>
 */
public class RoomMenuManager {

    /** AI 等级选择界面（一行 9 格: 空 绿 空 黄 空 橙 空 红 空）。 */
    public static final class LevelHolder implements InventoryHolder {
        private final String room;
        private Inventory inventory;

        LevelHolder(String room) { this.room = room; }

        @Override public Inventory getInventory() { return inventory; }
    }

    public static final class Holder implements InventoryHolder {
        private final Map<Integer, String> actions = new HashMap<>();
        private Inventory inventory;

        @Override public Inventory getInventory() { return inventory; }

        String actionAt(int slot) { return actions.get(slot); }
        void bind(int slot, String action) { actions.put(slot, action); }
    }

    private static final int SIZE = 54;
    private static final int AUTO_SLOT = 49;   // 第 6 行正中间
    private static final int ROOM_LIMIT = 45;  // 前 5 行放房间

    private final GomokuPlugin plugin;
    private final NamespacedKey key;
    /** 左键匹配后 5 分钟没等到真人 -> 自动转 AI 的定时任务（按房间名索引）。 */
    private final Map<String, org.bukkit.scheduler.BukkitTask> aiFallbacks = new HashMap<>();

    private boolean enabled = true;
    private int slot = 0;
    private Material material = Material.NETHER_STAR;
    private String name = "";
    private List<String> lore = List.of();

    public RoomMenuManager(GomokuPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "room_menu");
        load();
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration cfg = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        this.enabled = cfg.getBoolean("hotbar.menu.enabled", true);
        this.slot = Math.max(0, Math.min(8, cfg.getInt("hotbar.menu.slot", 0)));
        this.material = legacyMaterial(cfg.getString("hotbar.menu.material", "NETHER_STAR"), Material.NETHER_STAR);
        // Hotbar item text lives in the language files so locale switches cleanly
        this.name = plugin.getRawMsg("room-menu-item-name");
        this.lore = plugin.getLanguageManager().getRawList("room-menu-item-lore");
    }

    public int getSlot() { return slot; }
    public boolean isEnabled() { return enabled; }

    // ── hotbar item ──

    public ItemStack menuItem() {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> out = new ArrayList<>();
            for (String l : lore) out.add(color(l));
            meta.setLore(out);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "1");
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isMenuItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    public void giveMenuItem(Player p) {
        if (!enabled) { removeMenuItem(p); return; }
        var inv = p.getInventory();
        // Update an existing tagged item in place, wherever it currently is
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && isMenuItem(it)) {
                inv.setItem(i, menuItem());
                return;
            }
        }
        ItemStack existing = inv.getItem(slot);
        if (existing == null) { inv.setItem(slot, menuItem()); return; }
        int free = inv.firstEmpty();
        if (free != -1) inv.setItem(free, menuItem());
    }

    /** Re-renders an existing room-menu item after a language/config reload. */
    public void refreshMenuItem(Player p) {
        if (!enabled) { removeMenuItem(p); return; }
        var inv = p.getInventory();
        boolean found = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && isMenuItem(it)) {
                inv.setItem(i, menuItem());
                found = true;
            }
        }
        // Only hand out a missing item to lobby players; never insert one mid-game
        if (!found && plugin.getLobbyManager().isLobbyWorld(p.getWorld())
                && !plugin.getGameSessionManager().isInGame(p)) {
            giveMenuItem(p);
        }
    }

    public void removeMenuItem(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (isMenuItem(inv.getItem(i))) inv.setItem(i, null);
    }

    // ── GUI ──

    public void open(Player p) {
        Holder holder = new Holder();
        List<Room> rooms = new ArrayList<>(plugin.getRoomManager().getAllRooms());
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                color(plugin.getRawMsg("menu-title", "{rooms}", String.valueOf(rooms.size()))));
        holder.inventory = inv;

        int index = 0;
        for (Room room : rooms) {
            if (index >= ROOM_LIMIT) break;
            boolean playing = plugin.getGameSessionManager().isRoomPlaying(room.getName());
            inv.setItem(index, roomItem(room, playing));
            holder.bind(index, room.getName());
            index++;
        }
        if (index == 0) inv.setItem(22, emptyItem());
        inv.setItem(AUTO_SLOT, autoItem());
        holder.bind(AUTO_SLOT, "auto");

        p.openInventory(inv);
    }

    private ItemStack roomItem(Room room, boolean playing) {
        Material m = playing ? legacyMaterial("REDSTONE_BLOCK", Material.REDSTONE_BLOCK)
                             : legacyMaterial("WORKBENCH", Material.CRAFTING_TABLE);
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&f") + room.getName());
            List<String> out = new ArrayList<>();
            out.add(plugin.getRawMsg(playing ? "menu-room-playing-lore" : "menu-room-free-lore"));
            out.add(plugin.getRawMsg("menu-room-size-lore", "{size}", String.valueOf(room.getBoardSize())));
            GameSession sess = plugin.getGameSessionManager().getSession(room.getName());
            int waiting = sess != null ? sess.getAllPlayers().size() : 0;
            out.add(plugin.getRawMsg("menu-room-players-lore", "{count}", String.valueOf(waiting)));
            meta.setLore(out);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack autoItem() {
        ItemStack item = new ItemStack(legacyMaterial("ANVIL", Material.ANVIL));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.getRawMsg("menu-auto-name"));
            meta.setLore(List.of(plugin.getRawMsg("menu-auto-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack emptyItem() {
        ItemStack item = new ItemStack(legacyMaterial("WORKBENCH", Material.CRAFTING_TABLE));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.getRawMsg("menu-empty-name"));
            meta.setLore(List.of(plugin.getRawMsg("menu-empty-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    public void handleClick(Player p, Holder holder, int rawSlot, boolean ai) {
        String action = holder.actionAt(rawSlot);
        if (action == null) return;
        p.closeInventory();

        if (action.equals("auto")) { autoMatch(p); return; }

        Room room = plugin.getRoomManager().getRoom(action);
        if (room == null) {
            p.sendMessage(plugin.getMsg("room-not-found", "{room}", action));
            return;
        }
        if (plugin.getGameSessionManager().isInGame(p)) {
            p.sendMessage(plugin.getMsg("join-already"));
            return;
        }
        if (ai) { openAiLevels(p, room); return; }
        if (plugin.getGameSessionManager().isRoomPlaying(room.getName())) spectate(p, room);
        else join(p, room);
    }

    private void join(Player p, Room room) { join(p, room, false); }

    private void join(Player p, Room room, boolean autoMatch) {
        if (!room.isEnabled()) {
            p.sendMessage(plugin.getMsg("join-not-found", "{room}", room.getName()));
            return;
        }
        if (room.getWorld() == null) {
            plugin.getWorldManager().createVoidWorld(room.getWorldName());
            if (room.getWorld() == null) {
                p.sendMessage(plugin.getMsg("join-no-world", "{world}", room.getWorldName()));
                return;
            }
        }
        GameSession s = plugin.getGameSessionManager().getSession(room.getName());
        if (s == null) s = plugin.getGameSessionManager().createSession(room);
        if (s.getState() == GameSession.State.ENDED) {
            p.sendMessage(plugin.getMsg("join-ended"));
            return;
        }
        s.addPlayer(p, autoMatch);
        scheduleAiFallback(room, s, p);
    }

    private void spectate(Player p, Room room) {
        GameSession s = plugin.getGameSessionManager().getSession(room.getName());
        if (s == null) {
            p.sendMessage(plugin.getMsg("spectate-not-started", "{room}", room.getName()));
            return;
        }
        if (room.getWorld() == null) plugin.getWorldManager().createVoidWorld(room.getWorldName());
        s.addSpectator(p);
    }

    /**
     * 左键匹配专用：5 分钟还没等到真人对手，就自动转成 AI 对局。
     * 同一房间重复匹配会重置计时；期间匹配到真人或玩家离线则放弃。
     */
    private void scheduleAiFallback(Room room, GameSession session, Player p) {
        org.bukkit.scheduler.BukkitTask old = aiFallbacks.remove(room.getName());
        if (old != null) old.cancel();
        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            aiFallbacks.remove(room.getName());
            if (!p.isOnline()) return;
            if (session.getState() != GameSession.State.WAITING) return;   // 已经有人进来了
            p.sendMessage(plugin.getMsg("join-ai-timeout"));
            session.startWithAi(p);
        }, 20L * 300);   // 5 分钟
        aiFallbacks.put(room.getName(), task);
    }

    /** 右键房间 -> 一行 9 格的 AI 等级选择界面。 */
    public void openAiLevels(Player p, Room room) {
        LevelHolder holder = new LevelHolder(room.getName());
        Inventory inv = Bukkit.createInventory(holder, 9, color(plugin.getRawMsg("ai-level-title")));
        holder.inventory = inv;
        int[] slots = {1, 3, 5, 7};
        Material[] mats = {Material.GREEN_WOOL, Material.YELLOW_WOOL,
                Material.ORANGE_WOOL, Material.RED_WOOL};
        boolean engineReady = plugin.getAiEngine() != null;
        for (int i = 0; i < 4; i++) {
            boolean off = (i == 3 && !engineReady);        // 红色档: 引擎没接上就灰掉
            ItemStack it = new ItemStack(off ? Material.GRAY_WOOL : mats[i]);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(plugin.getRawMsg(off ? "ai-level-4-off" : "ai-level-" + (i + 1)));
                if (off) meta.setLore(List.of(plugin.getRawMsg("ai-level-4-off-lore")));
                it.setItemMeta(meta);
            }
            inv.setItem(slots[i], it);
        }
        p.openInventory(inv);
    }

    public void handleLevelClick(Player p, LevelHolder holder, int rawSlot) {
        int level = switch (rawSlot) {
            case 1 -> 1; case 3 -> 2; case 5 -> 3; case 7 -> 4; default -> 0;
        };
        if (level == 0) return;
        p.closeInventory();
        Room room = plugin.getRoomManager().getRoom(holder.room);
        if (room == null) { p.sendMessage(plugin.getMsg("room-not-found", "{room}", holder.room)); return; }
        startAi(p, room, level);
    }

    /** 房间真正开局（匹配到真人）或等待者退出时，取消 5 分钟的 AI 兜底计时。 */
    public void cancelAiFallback(String roomName) {
        org.bukkit.scheduler.BukkitTask task = aiFallbacks.remove(roomName);
        if (task != null) task.cancel();
    }

    /** 直接和指定等级的 AI 开局。 */
    private void startAi(Player p, Room room, int level) {
        if (!room.isEnabled()) { p.sendMessage(plugin.getMsg("join-not-found", "{room}", room.getName())); return; }
        if (room.getWorld() == null) {
            plugin.getWorldManager().createVoidWorld(room.getWorldName());
            if (room.getWorld() == null) return;
        }
        GameSession s = plugin.getGameSessionManager().getSession(room.getName());
        if (s == null) s = plugin.getGameSessionManager().createSession(room);
        if (s.getState() != GameSession.State.WAITING) {
            p.sendMessage(plugin.getMsg("room-busy", "{room}", room.getName()));
            return;
        }
        if (s.getBlackPlayer() != null && !s.getBlackPlayer().getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(plugin.getMsg("room-busy", "{room}", room.getName()));
            return;
        }
        if (level == 4 && plugin.getAiEngine() == null) p.sendMessage(plugin.getMsg("ai-level-fallback"));
        s.startWithAi(p, level);
    }

    private void autoMatch(Player p) {
        // 1) 优先加入已经在等人的房间
        for (Room r : plugin.getRoomManager().getAllRooms()) {
            if (!r.isEnabled() || plugin.getGameSessionManager().isRoomPlaying(r.getName())) continue;
            GameSession s = plugin.getGameSessionManager().getSession(r.getName());
            if (s != null && s.getState() == GameSession.State.WAITING) { join(p, r, true); return; }
        }
        // 2) 否则开一个空闲房间
        for (Room r : plugin.getRoomManager().getAllRooms()) {
            if (!r.isEnabled() || plugin.getGameSessionManager().isRoomPlaying(r.getName())) continue;
            join(p, r, true);
            return;
        }
        p.sendMessage(plugin.getMsg("join-not-found", "{room}", "auto"));
    }

    // ── helpers ──

    /** 1.8 旧材质名 → 现代 Material。 */
    public static Material legacyMaterial(String raw, Material def) {
        if (raw == null || raw.isEmpty()) return def;
        switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "WORKBENCH": case "CRAFTING_TABLE": return Material.CRAFTING_TABLE;
            case "NETHER_STAR": return Material.NETHER_STAR;
            case "REDSTONE_BLOCK": return Material.REDSTONE_BLOCK;
            case "ANVIL": return Material.ANVIL;
            case "CHEST": return Material.CHEST;
            case "REDSTONE": return Material.REDSTONE;
            default: {
                Material m = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
                return m != null ? m : def;
            }
        }
    }

    private static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
