package com.syk.gomoku.manager;

import com.syk.gomoku.model.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class StatsManager {
    private final File dataFolder;
    private final Logger logger;
    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();
    private StatsStorage storage;
    private Plugin plugin;
    private BukkitTask pendingSave;
    private boolean dirty;
    /** 写盘延迟, 默认 10 秒 (200 tick). */
    private int saveDelayTicks = 200;

    public StatsManager(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.storage = createStorage();
        load();
    }

    private StatsStorage createStorage() {
        File cfgFile = new File(dataFolder, "config.yml");
        YamlConfiguration cfg = cfgFile.exists()
                ? YamlConfiguration.loadConfiguration(cfgFile)
                : new YamlConfiguration();
        String type = cfg.getString("storage.type", "json");
        if (type != null && (type.equalsIgnoreCase("sqlite") || type.equalsIgnoreCase("sqldb"))) {
            try {
                String file = cfg.getString("storage.sqlite.file", "stats.db");
                SqliteStatsStorage sqlite = new SqliteStatsStorage(new File(dataFolder, file));
                if (cfg.getBoolean("storage.auto-migrate", true)) migrateJsonTo(sqlite, "SQLite");
                logger.info("Player stats storage: SQLite (" + file + ").");
                return sqlite;
            } catch (Exception e) {
                logger.warning("SQLite stats storage unavailable: " + e.getMessage() + " - falling back to JSON.");
            }
        }
        if (type != null && type.equalsIgnoreCase("mysql")) {
            try {
                MysqlStatsStorage mysql = new MysqlStatsStorage(cfg, logger);
                if (cfg.getBoolean("storage.auto-migrate", true)) migrateJsonTo(mysql, "MySQL");
                return mysql;
            } catch (Exception e) {
                logger.warning("MySQL stats storage unavailable: " + e.getMessage() + " - falling back to JSON.");
            }
        } else if (type != null && !type.equalsIgnoreCase("json")
                && !type.equalsIgnoreCase("sqlite") && !type.equalsIgnoreCase("sqldb")) {
            logger.info("Unknown storage.type '" + type + "'; using JSON.");
        }
        File json = new File(dataFolder, "stats.json");
        logger.info("Player stats storage: JSON (" + json.getName() + ").");
        return new JsonStatsStorage(json);
    }

    /**
     * When the target SQL table is empty and stats.json exists, copy the JSON data into it.
     * The JSON file is kept as a fallback/backup; it is never deleted.
     */
    private void migrateJsonTo(StatsStorage target, String label) {
        File jsonFile = new File(dataFolder, "stats.json");
        if (!jsonFile.exists()) return;
        try {
            List<PlayerStats> old = new JsonStatsStorage(jsonFile).load();
            if (old.isEmpty()) return;
            if (!target.load().isEmpty()) return;
            target.saveAll(old);
            logger.info("Auto-migrated " + old.size() + " player(s) from stats.json to " + label + ".");
        } catch (Exception e) {
            logger.warning("Auto migration from stats.json to " + label + " failed: " + e.getMessage());
        }
    }

    private void load() {
        stats.clear();
        try {
            for (PlayerStats ps : storage.load()) {
                if (ps != null && ps.getUuid() != null) stats.put(ps.getUuid(), ps);
            }
        } catch (Exception e) {
            logger.warning("Stats load failed: " + e.getMessage());
        }
    }

    public void save() {
        dirty = false;
        try {
            storage.saveAll(new ArrayList<>(stats.values()));
        } catch (Exception e) {
            logger.warning("Stats save failed: " + e.getMessage());
        }
    }

    public void setPlugin(Plugin plugin) { this.plugin = plugin; }
    public void setSaveDelayTicks(int ticks) { this.saveDelayTicks = Math.max(0, ticks); }

    /** 标记数据已改, 合并后在 saveDelayTicks 后写盘 (默认 10 秒). */
    private void scheduleSave() {
        dirty = true;
        if (pendingSave != null) return;
        if (saveDelayTicks <= 0 || plugin == null || !plugin.isEnabled()) { save(); return; }
        pendingSave = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingSave = null;
            if (dirty) save();
        }, saveDelayTicks);
    }

    public PlayerStats getOrCreate(UUID uuid, String name) {
        PlayerStats ps = stats.get(uuid);
        if (ps == null) { ps = new PlayerStats(uuid, name); stats.put(uuid, ps); scheduleSave(); }
        else if (!ps.getLastName().equals(name)) { ps.setLastName(name); scheduleSave(); }
        return ps;
    }

    public PlayerStats get(UUID uuid) { return stats.get(uuid); }

    public void recordWin(UUID u, String n) { getOrCreate(u, n).addWin(); scheduleSave(); }
    public void recordLoss(UUID u, String n) { getOrCreate(u, n).addLoss(); scheduleSave(); }
    public void recordDraw(UUID u, String n) { getOrCreate(u, n).addDraw(); scheduleSave(); }
    /** Deducts spendable score (piece shop). */
    public void spend(UUID u, String n, int amount) { getOrCreate(u, n).addSpent(amount); scheduleSave(); }

    /** Reloads the chosen storage backend and all player data from it. */
    public String reload() {
        stats.clear();
        try { storage.close(); } catch (Exception ignored) {}
        this.storage = createStorage();
        load();
        return stats.size() + " player(s) reloaded.";
    }
}
