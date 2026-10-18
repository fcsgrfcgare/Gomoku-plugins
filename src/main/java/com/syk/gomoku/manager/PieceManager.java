package com.syk.gomoku.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.syk.gomoku.GomokuPlugin;
import com.syk.gomoku.model.PlayerStats;
import com.syk.gomoku.model.Room;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 棋子商店: 用积分兑换"一对"黑白棋子外观。
 *
 * <p>配置在 {@code pieces.yml}, 玩家数据在 {@code pieces-data.json}。
 * 房间若用 {@code /gomoku admin check} 指定过棋子, 房间设置优先, 玩家的外观不生效。</p>
 */
public class PieceManager {

    /** One purchasable pair of pieces. */
    public static final class PieceSet {
        public final String id;
        public final int cost;
        public final Material blackMat, whiteMat;
        public final String blackName, whiteName;

        PieceSet(String id, int cost, Material blackMat, String blackName,
                 Material whiteMat, String whiteName) {
            this.id = id; this.cost = cost;
            this.blackMat = blackMat; this.blackName = blackName;
            this.whiteMat = whiteMat; this.whiteName = whiteName;
        }

        public String display() {
            return ChatColor.translateAlternateColorCodes('&', blackName)
                    + " &7/ " + ChatColor.translateAlternateColorCodes('&', whiteName);
        }
    }

    /** Marks our own GUI so click/drag handlers can recognise it. */
    public static final class MenuHolder implements InventoryHolder {
        private final Map<Integer, String> slots = new HashMap<>();
        private Inventory inventory;

        @Override public Inventory getInventory() { return inventory; }

        String idAt(int slot) { return slots.get(slot); }
    }

    /** Persisted per-player state. */
    private static final class PlayerData {
        private String selected;
        private Set<String> owned = new HashSet<>();
    }

    private final GomokuPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, PieceSet> sets = new LinkedHashMap<>();
    private final Map<UUID, PlayerData> players = new HashMap<>();
    /** PDC tag that identifies the shop item in a player's inventory. */
    private final NamespacedKey menuKey;

    private File configFile;
    private File dataFile;

    // menu settings
    private int menuSlot = 7;
    private Material menuMaterial = Material.CHEST;
    private String menuName = "";
    private List<String> menuLore = new ArrayList<>();
    private int menuSize = 27;
    private String menuTitle = "";

    public PieceManager(GomokuPlugin plugin) {
        this.plugin = plugin;
        this.menuKey = new NamespacedKey(plugin, "piece_menu");
        load();
    }

    // ── config / data ──

    public void load() {
        this.configFile = new File(plugin.getDataFolder(), "pieces.yml");
        this.dataFile = new File(plugin.getDataFolder(), "pieces-data.json");
        loadConfig();
        loadData();
    }

    public void reload() {
        load();
    }

    private void loadConfig() {
        sets.clear();
        if (!configFile.exists()) {
            // Fresh install: use the English template when config.yml selects locale "en".
            String resource = "pieces.yml";
            File serverCfg = new File(plugin.getDataFolder(), "config.yml");
            if (serverCfg.exists() && "en".equalsIgnoreCase(
                    YamlConfiguration.loadConfiguration(serverCfg).getString("locale", "zh_cn"))) {
                resource = "pieces_en.yml";
            }
            try (InputStream in = plugin.getResource(resource)) {
                if (in != null) {
                    if (configFile.getParentFile() != null) configFile.getParentFile().mkdirs();
                    Files.copy(in, configFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not extract " + resource + ": " + e.getMessage());
            }
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

        this.menuSlot = Math.max(0, Math.min(8, cfg.getInt("menu.slot", 7)));
        // config.yml 覆盖箱子在快捷栏的位置 / 是否启用
        File serverCfg = new File(plugin.getDataFolder(), "config.yml");
        if (serverCfg.exists()) {
            YamlConfiguration sc = YamlConfiguration.loadConfiguration(serverCfg);
            if (sc.contains("hotbar.shop")) {
                this.menuSlot = sc.getBoolean("hotbar.shop.enabled", true)
                        ? Math.max(0, Math.min(8, sc.getInt("hotbar.shop.slot", this.menuSlot)))
                        : -1;
            }
        }
        Material mat = Material.matchMaterial(cfg.getString("menu.material", "CHEST"));
        this.menuMaterial = mat != null ? mat : Material.CHEST;
        // Shop item/menu text lives in the language files so locale switches cleanly.
        this.menuName = plugin.getRawMsg("piece-menu-name");
        this.menuLore = plugin.getLanguageManager().getRawList("piece-menu-lore");
        int size = cfg.getInt("menu.size", 27);
        this.menuSize = Math.max(9, Math.min(54, size / 9 * 9));
        this.menuTitle = plugin.getRawMsg("piece-menu-title");

        ConfigurationSection root = cfg.getConfigurationSection("pieces");
        if (root == null) {
            plugin.getLogger().warning("pieces.yml has no 'pieces' section.");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Material bm = Material.matchMaterial(s.getString("black.material", "BLACK_WOOL"));
            Material wm = Material.matchMaterial(s.getString("white.material", "WHITE_WOOL"));
            if (bm == null || wm == null || bm == Material.AIR || wm == Material.AIR) {
                plugin.getLogger().warning("pieces.yml: '" + id + "' has an invalid material, skipped.");
                continue;
            }
            if (bm == wm) {
                plugin.getLogger().warning("pieces.yml: '" + id + "' uses the same block for black and white, skipped.");
                continue;
            }
            sets.put(id, new PieceSet(id,
                    Math.max(0, s.getInt("cost", 0)),
                    bm, s.getString("black.name", plugin.getRawMsg("piece-default-black")),
                    wm, s.getString("white.name", plugin.getRawMsg("piece-default-white"))));
        }
        plugin.getLogger().info("Loaded " + sets.size() + " piece set(s).");
    }

    private void loadData() {
        players.clear();
        if (!dataFile.exists()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8)) {
            Map<String, PlayerData> raw = gson.fromJson(r, new TypeToken<Map<String, PlayerData>>(){}.getType());
            if (raw != null) raw.forEach((k, v) -> {
                try { players.put(UUID.fromString(k), v); } catch (IllegalArgumentException ignored) {}
            });
        } catch (Exception e) {
            plugin.getLogger().warning("pieces-data.json load failed: " + e.getMessage());
        }
    }

    private void saveData() {
        try {
            if (dataFile.getParentFile() != null) dataFile.getParentFile().mkdirs();
            Map<String, PlayerData> out = new HashMap<>();
            players.forEach((k, v) -> out.put(k.toString(), v));
            try (Writer w = new OutputStreamWriter(new FileOutputStream(dataFile), StandardCharsets.UTF_8)) {
                gson.toJson(out, w);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("pieces-data.json save failed: " + e.getMessage());
        }
    }

    private PlayerData data(Player p) {
        return players.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    // ── selection / resolution ──

    public Set<PieceSet> allSets() { return new LinkedHashSet<>(sets.values()); }

    public int getMenuSlot() { return menuSlot; }

    public String selectedId(Player p) {
        PlayerData d = players.get(p.getUniqueId());
        return d != null ? d.selected : null;
    }

    /** The set this player would use right now, or null for the defaults. */
    public PieceSet selectedSet(Player p) {
        String id = selectedId(p);
        return id != null ? sets.get(id) : null;
    }

    /** Points available to spend (earned score minus what was already spent). */
    public int points(Player p) {
        PlayerStats s = plugin.getStatsManager().get(p.getUniqueId());
        return s != null ? s.getAvailableScore() : 0;
    }

    /**
     * Material to use for this player's side. A room that pinned its own pieces always wins,
     * otherwise the player's selection applies, otherwise the room defaults.
     */
    public Material materialFor(Player p, boolean black, Room room) {
        if (room.isPiecesSet()) {
            return black ? room.getBlackPieceMaterial() : room.getWhitePieceMaterial();
        }
        PieceSet set = p != null ? selectedSet(p) : null;
        if (set != null) return black ? set.blackMat : set.whiteMat;
        return black ? room.getBlackPieceMaterial() : room.getWhitePieceMaterial();
    }

    /** Display name to use for this player's side; same priority as {@link #materialFor}. */
    public String nameFor(Player p, boolean black, Room room) {
        if (room.isPiecesSet()) {
            return black ? room.getBlackPieceName() : room.getWhitePieceName();
        }
        PieceSet set = p != null ? selectedSet(p) : null;
        if (set != null) return black ? set.blackName : set.whiteName;
        return black ? room.getBlackPieceName() : room.getWhitePieceName();
    }

    // ── menu item ──

    public ItemStack menuItem(Player p) {
        ItemStack item = new ItemStack(menuMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(fill(menuName, p)));
            List<String> lore = new ArrayList<>();
            for (String line : menuLore) lore.add(color(fill(line, p)));
            meta.setLore(lore);
            // Tagged so the item can be recognised without guessing at its display name
            meta.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING, "1");
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isMenuItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(menuKey, PersistentDataType.STRING);
    }

    /** Puts the shop item into the configured hotbar slot, never destroying what is there. */
    public void giveMenuItem(Player p) {
        if (menuSlot < 0) { removeMenuItem(p); return; }   // hotbar.shop.enabled: false
        var inv = p.getInventory();
        // Update an existing tagged item in place, wherever it currently is
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && isMenuItem(it)) {
                inv.setItem(i, menuItem(p));
                return;
            }
        }
        ItemStack existing = inv.getItem(menuSlot);
        if (existing == null) {
            inv.setItem(menuSlot, menuItem(p));
            return;
        }
        // Slot already taken by something else - use the first free slot instead
        int free = inv.firstEmpty();
        if (free != -1) inv.setItem(free, menuItem(p));
    }

    /** Re-renders an existing shop item after a language/config reload. */
    public void refreshMenuItem(Player p) {
        if (menuSlot < 0) { removeMenuItem(p); return; }
        var inv = p.getInventory();
        boolean found = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && isMenuItem(it)) {
                inv.setItem(i, menuItem(p));
                found = true;
            }
        }
        if (!found && plugin.getLobbyManager().isLobbyWorld(p.getWorld())
                && !plugin.getGameSessionManager().isInGame(p)) {
            giveMenuItem(p);
        }
    }

    public void removeMenuItem(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isMenuItem(inv.getItem(i))) inv.setItem(i, null);
        }
    }

    // ── GUI ──

    public void openMenu(Player p) {
        if (plugin.getGameSessionManager().isInGame(p)) {
            p.sendMessage(plugin.getMsg("piece-in-game"));
            return;
        }
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, menuSize, color(fill(menuTitle, p)));
        holder.inventory = inv;

        int slot = 0;
        for (PieceSet set : sets.values()) {
            if (slot >= menuSize) break;
            inv.setItem(slot, entryItem(p, set));
            holder.slots.put(slot, set.id);
            slot++;
        }
        p.openInventory(inv);
    }

    private ItemStack entryItem(Player p, PieceSet set) {
        ItemStack item = new ItemStack(set.blackMat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        PlayerData d = data(p);
        boolean owned = set.cost <= 0 || d.owned.contains(set.id);
        // No explicit pick yet -> the first free set counts as equipped
        String effective = d.selected;
        if (effective == null && firstFree() != null) effective = firstFree().id;
        boolean selected = set.id.equals(effective);
        int points = points(p);

        // GUI text is prefix-free (getRawMsg) - the [Gomoku] prefix would look wrong in a menu
        meta.setDisplayName(plugin.getRawMsg("piece-entry-name",
                "{black}", set.blackName, "{white}", set.whiteName));

        List<String> lore = new ArrayList<>();
        lore.add(plugin.getRawMsg("piece-lore-black", "{name}", set.blackName));
        lore.add(plugin.getRawMsg("piece-lore-white", "{name}", set.whiteName));
        lore.add(plugin.getRawMsg("piece-lore-cost", "{cost}", String.valueOf(set.cost)));
        lore.add("");
        if (selected) lore.add(plugin.getRawMsg("piece-lore-selected"));
        else if (owned) lore.add(plugin.getRawMsg("piece-lore-owned"));
        else if (points >= set.cost) lore.add(plugin.getRawMsg("piece-lore-buy"));
        else lore.add(plugin.getRawMsg("piece-lore-poor"));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    /** The first cost-0 set, used to show "equipped" for players who never picked one. */
    private PieceSet firstFree() {
        for (PieceSet s : sets.values()) if (s.cost <= 0) return s;
        return null;
    }

    /** Handles a click inside our GUI. */
    public void handleClick(Player p, MenuHolder holder, int slot) {
        String id = holder.idAt(slot);
        if (id == null) return;
        PieceSet set = sets.get(id);
        if (set == null) return;

        PlayerData d = data(p);
        boolean owned = set.cost <= 0 || d.owned.contains(id);

        if (owned) {
            d.selected = id;
            saveData();
            p.sendMessage(plugin.getMsg("piece-equip-success",
                    "{black}", set.blackName, "{white}", set.whiteName));
            refresh(p);
            return;
        }

        int points = points(p);
        if (points < set.cost) {
            p.sendMessage(plugin.getMsg("piece-no-points",
                    "{cost}", String.valueOf(set.cost),
                    "{points}", String.valueOf(points)));
            return;
        }

        plugin.getStatsManager().spend(p.getUniqueId(), p.getName(), set.cost);
        d.owned.add(id);
        d.selected = id;
        saveData();
        p.sendMessage(plugin.getMsg("piece-buy-success",
                "{black}", set.blackName, "{white}", set.whiteName,
                "{cost}", String.valueOf(set.cost)));
        refresh(p);
    }

    /** Re-opens the menu on the next tick - reopening inside the click event is unsafe. */
    private void refresh(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> openMenu(p));
    }

    private String fill(String text, Player p) {
        return text == null ? "" : text.replace("%player%", p.getName())
                .replace("{points}", String.valueOf(points(p)));
    }

    private static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
