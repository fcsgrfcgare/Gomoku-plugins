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
package com.syk.gomoku;

import com.syk.gomoku.command.GomokuCommand;
import com.syk.gomoku.listener.GameListener;
import com.syk.gomoku.manager.*;
import com.syk.gomoku.session.GameSession;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GomokuPlugin extends JavaPlugin {

    private LobbyManager lobbyManager;
    private RoomManager roomManager;
    private StatsManager statsManager;
    private GameSessionManager gameSessionManager;
    private BoardManager boardManager;
    private LanguageManager languageManager;
    private WorldManager worldManager;
    private WorldBackupManager worldBackupManager;
    private WinCelebrationManager winCelebrationManager;
    private BoardPreviewManager boardPreviewManager;
    private ScoreboardManager scoreboardManager;
    private PieceManager pieceManager;

    // Options from config.yml (loaded on enable and /gomoku admin reload)
    private boolean joinMessageLobby = true, joinMessageRoom = false;
    private boolean quitMessageLobby = true, quitMessageRoom = false;
    private boolean preventDeath = true, blockPlayerAttacks = true, preventPlayerDamage = true;
    private boolean lobbyProtectEnabled = true, lobbyRequireAdmin = true, lobbyRequireCreative = true;
    private int statsSaveDelaySeconds = 10;
    private int gameReturnDelaySeconds = 10;
    /** Marks the edit-mode board-scanner axe. */
    private org.bukkit.NamespacedKey editAxeKey;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        this.languageManager = new LanguageManager(getDataFolder(), getLogger());
        this.lobbyManager = new LobbyManager(getDataFolder(), getLogger());
        this.roomManager = new RoomManager(this);
        this.statsManager = new StatsManager(getDataFolder(), getLogger());
        this.statsManager.setPlugin(this);
        this.boardManager = new BoardManager(this);
        this.gameSessionManager = new GameSessionManager(this);
        this.worldManager = new WorldManager(this);
        this.worldBackupManager = new WorldBackupManager(this);
        this.winCelebrationManager = new WinCelebrationManager(this);
        this.boardPreviewManager = new BoardPreviewManager(this);
        this.scoreboardManager = new ScoreboardManager(this);
        this.pieceManager = new PieceManager(this);
        this.editAxeKey = new org.bukkit.NamespacedKey(this, "edit_axe");
        ConfigCheckResult cfgCheck = checkAndFixConfig();
        if (cfgCheck.isClean()) {
            getLogger().info("Config check: config.yml is complete.");
        } else {
            if (!cfgCheck.fixed.isEmpty()) {
                getLogger().warning("Config check: fixed " + cfgCheck.fixed.size()
                        + " invalid/missing key(s): " + String.join(", ", cfgCheck.fixed));
            }
            if (!cfgCheck.errors.isEmpty()) {
                getLogger().warning("Config check: could not fix: " + String.join(", ", cfgCheck.errors));
            }
        }
        try { localizeConfigComments(); }
        catch (Exception e) { getLogger().warning("Config comments: " + e.getMessage()); }
        this.loadOptions();

        GomokuCommand cmd = new GomokuCommand(this);
        PluginCommand pluginCmd = getCommand("gomoku");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd);
            pluginCmd.setTabCompleter(cmd);
        }

        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);

        // Register PlaceholderAPI expansion (if PAPI is installed)
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new com.syk.gomoku.placeholder.GomokuExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        getLogger().info("Gomoku enabled! Rooms: " + roomManager.getAllRooms().size()
                + " | Lobby: " + (lobbyManager.isSet() ? "set" : "not set"));

        // Sidebar scoreboard (reads config.yml, then starts its refresh task)
        this.scoreboardManager.load();
        this.scoreboardManager.start();
    }

    @Override
    public void onDisable() {
        // 关服前把棋子层和红羊毛高亮全部清掉, 避免重启后残留
        try {
            for (com.syk.gomoku.model.Room room : roomManager.getAllRooms()) {
                if (!room.isBoardSet() || room.getWorld() == null) continue;
                int y = room.getBoardY();
                int size = room.getBoardSize();
                int[][] offs = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int row = 0; row < size; row++) {
                    for (int col = 0; col < size; col++) {
                        org.bukkit.Location c = new org.bukkit.Location(room.getWorld(), room.centerX(row, col), y, room.centerZ(row, col));
                        for (int[] o : offs) c.clone().add(o[0], 0, o[1]).getBlock().setType(org.bukkit.Material.AIR);
                    }
                }
                getLogger().info("Cleared board blocks of room '" + room.getName() + "'");
            }
        } catch (Exception e) {
            getLogger().warning("Failed to clear board blocks: " + e.getMessage());
        }
        if (aiEngine != null) { aiEngine.close(); aiEngine = null; }   // 别留孤儿 pbrain-rapfi 进程
        if (scoreboardManager != null) scoreboardManager.stop();
        if (winCelebrationManager != null) winCelebrationManager.stopAll();
        for (GameSession session : gameSessionManager.getAllSessions()) session.cleanup();
        if (statsManager != null) statsManager.save();
        getLogger().info("Gomoku disabled.");
    }

    public LobbyManager getLobbyManager() { return lobbyManager; }
    public RoomManager getRoomManager() { return roomManager; }
    public StatsManager getStatsManager() { return statsManager; }
    public GameSessionManager getGameSessionManager() { return gameSessionManager; }
    public BoardManager getBoardManager() { return boardManager; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public WorldManager getWorldManager() { return worldManager; }
    public WorldBackupManager getWorldBackupManager() { return worldBackupManager; }
    public WinCelebrationManager getWinCelebrationManager() { return winCelebrationManager; }
    public BoardPreviewManager getBoardPreviewManager() { return boardPreviewManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
    public PieceManager getPieceManager() { return pieceManager; }

    /** Reads join/protection/stats options from config.yml. */
    public void loadOptions() {
        java.io.File f = new java.io.File(getDataFolder(), "config.yml");
        org.bukkit.configuration.file.YamlConfiguration cfg = f.exists()
                ? org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f)
                : new org.bukkit.configuration.file.YamlConfiguration();
        this.joinMessageLobby = cfg.getBoolean("join-message.lobby", true);
        this.joinMessageRoom = cfg.getBoolean("join-message.room", false);
        this.quitMessageLobby = cfg.getBoolean("quit-message.lobby", true);
        this.quitMessageRoom = cfg.getBoolean("quit-message.room", false);
        this.preventDeath = cfg.getBoolean("protect.prevent-death", true);
        this.blockPlayerAttacks = cfg.getBoolean("protect.block-player-attacks", true);
        this.preventPlayerDamage = cfg.getBoolean("protect.prevent-player-damage", true);
        this.lobbyProtectEnabled = cfg.getBoolean("lobby-protect.enabled", true);
        this.lobbyRequireAdmin = cfg.getBoolean("lobby-protect.require-admin", true);
        this.lobbyRequireCreative = cfg.getBoolean("lobby-protect.require-creative", true);
        this.statsSaveDelaySeconds = Math.max(0, cfg.getInt("stats.save-delay-seconds", 10));
        this.gameReturnDelaySeconds = Math.max(0, cfg.getInt("game.return-delay-seconds", 10));
        if (statsManager != null) statsManager.setSaveDelayTicks(statsSaveDelaySeconds * 20);
        if (winCelebrationManager != null) winCelebrationManager.load();
    }

    public boolean joinMessageLobby() { return joinMessageLobby; }
    public boolean joinMessageRoom() { return joinMessageRoom; }
    public boolean quitMessageLobby() { return quitMessageLobby; }
    public boolean quitMessageRoom() { return quitMessageRoom; }
    public boolean preventDeath() { return preventDeath; }
    public boolean blockPlayerAttacks() { return blockPlayerAttacks; }
    public boolean preventPlayerDamage() { return preventPlayerDamage; }
    public boolean lobbyProtectEnabled() { return lobbyProtectEnabled; }

    /** Result of validating/fixing config.yml against the bundled defaults. */
    public static final class ConfigCheckResult {
        public final List<String> fixed = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public boolean isClean() { return fixed.isEmpty() && errors.isEmpty(); }
    }

    /**
     * Compares config.yml with the bundled defaults. Missing keys and keys whose
     * type does not match the default are reset to the default, then the file is
     * saved. Called on startup and on every reload.
     */
    public ConfigCheckResult checkAndFixConfig() {
        ConfigCheckResult result = new ConfigCheckResult();
        File file = new File(getDataFolder(), "config.yml");
        // Pick the template language from the current config; this only changes which
        // bundled reference is used, never the locale or the user's values.
        String template = "config.yml";
        if (file.exists() && "en".equalsIgnoreCase(
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
                        .getString("locale", "zh_cn"))) {
            template = "config_en.yml";
        }
        try (InputStream in = getResource(template)) {
            if (in == null) {
                result.errors.add("bundled " + template + " not found");
                return result;
            }
            org.bukkit.configuration.file.YamlConfiguration def =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                            new InputStreamReader(in, StandardCharsets.UTF_8));
            org.bukkit.configuration.file.YamlConfiguration cur =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            for (String key : def.getKeys(true)) {
                if (def.isConfigurationSection(key)) continue;
                Object expected = def.get(key);
                if (!cur.contains(key)) {
                    cur.set(key, expected);
                    result.fixed.add(key + " (missing)");
                    continue;
                }
                Object actual = cur.get(key);
                if (!sameType(expected, actual)) {
                    cur.set(key, expected);
                    result.fixed.add(key + " (invalid format)");
                }
            }
            if (!result.fixed.isEmpty()) cur.save(file);
        } catch (Exception e) {
            result.errors.add(e.getMessage());
        }
        return result;
    }

    private static boolean sameType(Object expected, Object actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected instanceof Number && actual instanceof Number) return true;
        if (expected instanceof org.bukkit.configuration.ConfigurationSection
                && actual instanceof org.bukkit.configuration.ConfigurationSection) return true;
        return expected.getClass() == actual.getClass();
    }

    /** True when this player may break/interact/place inside the lobby world. */
    public boolean canEditLobby(Player p) {
        if (p == null) return false;
        if (lobbyRequireAdmin && !p.hasPermission("gomoku.admin")) return false;
        if (lobbyRequireCreative && p.getGameMode() != org.bukkit.GameMode.CREATIVE) return false;
        return true;
    }

    public int returnDelaySeconds() { return Math.max(0, gameReturnDelaySeconds); }

    /** 结算画面停留 tick 数 (game.return-delay-seconds * 20). */
    public long returnDelayTicks() { return returnDelaySeconds() * 20L; }

    /** config.yml 的 sounds.enabled, 默认开启。 */
    public boolean soundsEnabled() {
        try {
            java.io.File f = new java.io.File(getDataFolder(), "config.yml");
            if (!f.exists()) return true;
            return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f)
                    .getBoolean("sounds.enabled", true);
        } catch (Exception e) {
            return true;
        }
    }

    private org.bukkit.configuration.file.YamlConfiguration soundCfg() {
        java.io.File f = new java.io.File(getDataFolder(), "config.yml");
        return f.exists() ? org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f)
                          : new org.bukkit.configuration.file.YamlConfiguration();
    }

    /** config.yml: sounds.<key>.sound (音符盒/方块音效名), 读不到就用默认值。 */
    public org.bukkit.Sound soundOf(String key, org.bukkit.Sound def) {
        String s = soundCfg().getString("sounds." + key + ".sound", def.name());
        try {
            return org.bukkit.Sound.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return def;
        }
    }

    /** config.yml: sounds.<key>.volume (音量) */
    public float soundVolume(String key) {
        return (float) soundCfg().getDouble("sounds." + key + ".volume", 1.0);
    }

    /** config.yml: sounds.<key>.pitch (声调/速度: 越高越快越尖, 越低越慢越沉) */
    public float soundPitch(String key) {
        return (float) soundCfg().getDouble("sounds." + key + ".pitch", 1.0);
    }
    private GomokuAI aiEngine;

    /** RapFi 引擎单例; ai.engine.enabled=false 或 exe 不存在时返回 null。 */
    public GomokuAI getAiEngine() {
        if (aiEngine != null) return aiEngine;
        java.io.File cfgFile = new java.io.File(getDataFolder(), "config.yml");
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(cfgFile);
        if (!cfg.getBoolean("ai.engine.enabled", false)) return null;
        String rel = cfg.getString("ai.engine.path",
                "plugins/Gomoku/engine/Rapfi-engine/pbrain-rapfi-windows-avx2.exe");
        java.io.File exe = new java.io.File(rel);
        if (!exe.isFile()) {
            getLogger().warning("RapFi engine not found: " + exe.getAbsolutePath());
            return null;
        }
        GomokuAI engine = new GomokuAI(getLogger(), exe.toPath());
        if (!engine.start()) return null;
        engine.newGame(15);
        engine.setTurnTimeout(cfg.getInt("ai.engine.timeout-ms", 3000));
        aiEngine = engine;
        getLogger().info("RapFi engine ready: " + exe.getName());
        return engine;
    }

    private RoomMenuManager roomMenuManager;

    /** Lazily created - it reads config.yml (hotbar.menu.*) on construction. */
    public RoomMenuManager getRoomMenuManager() {
        if (roomMenuManager == null) roomMenuManager = new RoomMenuManager(this);
        return roomMenuManager;
    }

    /** Get message from language.yml */
    public String getMsg(String key, String... replacements) {
        return languageManager.get(key, replacements);
    }

    /** Same, but without the [Gomoku] prefix - for on-screen titles and action bars. */
    public String getRawMsg(String key, String... replacements) {
        return languageManager.getRaw(key, replacements);
    }

    // ── edit-mode board scanner axe ──

    /** Builds the wooden axe handed out when entering edit mode. */
    public org.bukkit.inventory.ItemStack createEditAxe() {
        org.bukkit.inventory.ItemStack item =
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.WOODEN_AXE);
        stampEditAxe(item);
        return item;
    }

    /** True when this item is the edit-mode scanner axe (tagged by {@link #stampEditAxe}). */
    public boolean isEditAxe(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() != org.bukkit.Material.WOODEN_AXE || !item.hasItemMeta()) return false;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(editAxeKey, org.bukkit.persistence.PersistentDataType.STRING);
    }

    /**
     * Gives any wooden axe the usage text and our tag. Called whenever an admin holds one,
     * so axes pulled straight out of the creative menu behave the same way.
     */
    public void stampEditAxe(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() != org.bukkit.Material.WOODEN_AXE) return;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (meta.getPersistentDataContainer().has(editAxeKey, org.bukkit.persistence.PersistentDataType.STRING)) return;

        meta.setDisplayName(getRawMsg("edit-axe-name"));
        meta.setLore(java.util.List.of(
                getRawMsg("edit-axe-lore-1"),
                getRawMsg("edit-axe-lore-2"),
                getRawMsg("edit-axe-lore-3"),
                getRawMsg("edit-axe-lore-4")));
        meta.getPersistentDataContainer().set(editAxeKey,
                org.bukkit.persistence.PersistentDataType.STRING, "1");
        item.setItemMeta(meta);
    }

    // ── config comment localisation ──

    private static final Pattern CONFIG_LINE =
            Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):(.*)$");

    private record TplEntry(int kind, String text, String path, String prefix, String value, String trailing) {
        static final int OTHER = 0, SECTION = 1, LEAF = 2;
    }

    /**
     * Switches config.yml and pieces.yml comments to the current locale's template
     * (Chinese or English) while keeping every configured value unchanged.
     * Unknown custom keys are never dropped: the file is left untouched in that case.
     */
    public boolean localizeConfigComments() {
        boolean changed = localizeYaml(new File(getDataFolder(), "config.yml"),
                "config.yml", "config_en.yml",
                Set.of("hotbar.menu.name", "hotbar.menu.lore", "scoreboard", "room-edit"),
                Set.of("scoreboard.", "room-edit."));
        boolean pieces = localizeYaml(new File(getDataFolder(), "pieces.yml"),
                "pieces.yml", "pieces_en.yml",
                Set.of("menu.name", "menu.lore", "menu.title"), Set.of());
        return changed || pieces;
    }

    private boolean localizeYaml(File file, String zhTemplate, String enTemplate,
                                 Set<String> deprecatedExact, Set<String> deprecatedPrefixes) {
        if (!file.exists()) return false;
        org.bukkit.configuration.file.YamlConfiguration cur =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        File configFile = new File(getDataFolder(), "config.yml");
        String locale = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile)
                .getString("locale", "zh_cn");
        String templateName = "en".equalsIgnoreCase(locale) ? enTemplate : zhTemplate;
        List<String> tplLines = readTemplate(templateName);
        if (tplLines == null) return false;

        List<TplEntry> entries = parseTemplate(tplLines);
        Set<String> leaves = new LinkedHashSet<>();
        for (TplEntry e : entries) if (e.kind() == TplEntry.LEAF) leaves.add(e.path());

        List<String> extras = new ArrayList<>();
        for (String key : cur.getKeys(true)) {
            if (cur.isConfigurationSection(key)) continue;
            if (leaves.contains(key) || deprecatedExact.contains(key)) continue;
            boolean deprecated = false;
            for (String prefix : deprecatedPrefixes) {
                if (key.startsWith(prefix)) { deprecated = true; break; }
            }
            if (!deprecated) extras.add(key);
        }
        if (!extras.isEmpty()) {
            getLogger().warning("Config comments: " + file.getName() + " has custom key(s) "
                    + String.join(", ", extras) + "; comments left unchanged to keep them.");
            return false;
        }

        String rendered = renderTemplate(entries, cur);
        try {
            String existing = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (existing.replace("\r\n", "\n").equals(rendered)) return false;

            File backup = new File(file.getParentFile(), file.getName() + ".bak");
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.write(file.toPath(), rendered.getBytes(StandardCharsets.UTF_8));
            getLogger().info("Config comments: " + file.getName() + " switched to "
                    + ("en".equalsIgnoreCase(locale) ? "English" : "Chinese")
                    + " (values unchanged; backup " + backup.getName() + ").");
            return true;
        } catch (IOException e) {
            getLogger().warning("Config comments: " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private List<String> readTemplate(String name) {
        try (InputStream in = getResource(name)) {
            if (in == null) return null;
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            getLogger().warning("Config comments: cannot read " + name + ": " + e.getMessage());
            return null;
        }
    }

    private List<TplEntry> parseTemplate(List<String> lines) {
        List<TplEntry> out = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.add(new TplEntry(TplEntry.OTHER, line, null, null, null, null));
                continue;
            }
            Matcher m = CONFIG_LINE.matcher(line);
            if (!m.matches()) {
                out.add(new TplEntry(TplEntry.OTHER, line, null, null, null, null));
                continue;
            }
            int indent = m.group(1).length();
            String key = m.group(2);
            String rest = m.group(3);
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                keys.remove(keys.size() - 1);
            }
            String path = keys.isEmpty() ? key : String.join(".", keys) + "." + key;
            boolean section = rest.trim().isEmpty() || rest.trim().startsWith("#");
            if (section) {
                out.add(new TplEntry(TplEntry.SECTION, line, path, null, null, null));
                keys.add(key);
                indents.add(indent);
            } else {
                int ci = rest.indexOf(" #");
                String valuePart = ci >= 0 ? rest.substring(0, ci) : rest;
                String trailing = ci >= 0 ? rest.substring(ci) : "";
                String prefix = line.substring(0, line.indexOf(':') + 1);
                out.add(new TplEntry(TplEntry.LEAF, line, path, prefix, valuePart.trim(), trailing));
            }
        }
        return out;
    }

    private String renderTemplate(List<TplEntry> entries,
                                  org.bukkit.configuration.file.YamlConfiguration cur) {
        StringBuilder sb = new StringBuilder();
        for (TplEntry e : entries) {
            if (e.kind() == TplEntry.OTHER || e.kind() == TplEntry.SECTION) {
                sb.append(e.text()).append('\n');
                continue;
            }
            Object value = cur.contains(e.path()) ? cur.get(e.path()) : null;
            String text = value != null ? yamlValue(value) : e.value();
            sb.append(e.prefix()).append(' ').append(text).append(e.trailing()).append('\n');
        }
        return sb.toString();
    }

    private static String yamlValue(Object value) {
        if (value == null) return "\"\"";
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        String s = String.valueOf(value);
        if (!s.isEmpty()
                && s.matches("[A-Za-z0-9_./:+-]+")
                && !s.matches("(?i)true|false|null|yes|no|on|off")
                && !s.matches("-?\\d+(\\.\\d+)?")) {
            return s;
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
