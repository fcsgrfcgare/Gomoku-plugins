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
import com.syk.gomoku.placeholder.GomokuPlaceholders;
import com.syk.gomoku.session.GameSession;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Sidebar scoreboard: a lobby layout while the player is not in a match, a match layout
 * while they are (players and spectators alike).
 *
 * <p>Configured in {@code config.yml} under {@code scoreboard.*}. Every title and line is
 * run through PlaceholderAPI when it is installed, otherwise through
 * {@link GomokuPlaceholders}, plus the built-in {@code %player%} token.</p>
 */
public class ScoreboardManager {

    /** Legacy limit for a sidebar title. */
    private static final int MAX_TITLE = 32;
    /** Legacy limit for a team prefix. */
    private static final int MAX_LINE = 64;

    /** Layout picked by {@link #modeOf(Player)}. */
    private static final int MODE_LOBBY = 0;
    private static final int MODE_GAME = 1;
    private static final int MODE_SPECTATE = 2;

    private static final String DEFAULT_LOBBY_TITLE = "&6&lGomoku &7- &f大厅";
    private static final List<String> DEFAULT_LOBBY_LINES = List.of(
            "&7玩家: &f%player%",
            "&7等级: &eLv.%gomoku_level%",
            "&a胜:%gomoku_wins% &c负:%gomoku_losses% &7平:%gomoku_draws%",
            "&7胜率: &f%gomoku_winrate%",
            "",
            "&7进行中对局: &f%gomoku_playing_room_count%",
            "",
            "&e/gomoku join auto",
            "&7点击加入对局");

    private static final String DEFAULT_GAME_TITLE = "&6&lGomoku &7- &f%gomoku_room%";
    private static final List<String> DEFAULT_GAME_LINES = List.of(
            "&7房间: &f%gomoku_room%",
            "&7你: %gomoku_color%",
            "&7对手: &f%gomoku_opponent%",
            "",
            "&7当前: &e%gomoku_countdown%",
            "&7已落子: &f%gomoku_move_count%",
            "",
            "&e/gomoku leave",
            "&7退出对局");

    /** Spectators have no opponent - they watch both sides instead. */
    private static final String DEFAULT_SPECTATOR_TITLE = "&6&lGomoku &7- &f观战 &7- &f%gomoku_room%";
    private static final List<String> DEFAULT_SPECTATOR_LINES = List.of(
            "&7房间: &f%gomoku_room%",
            "&0黑方: &f%gomoku_black_player%",
            "&f白方: &f%gomoku_white_player%",
            "",
            "&7当前: &e%gomoku_countdown%",
            "&7已落子: &f%gomoku_move_count%",
            "",
            "&e/gomoku leave",
            "&7退出观战");

    private final GomokuPlugin plugin;
    private final Map<UUID, Board> boards = new HashMap<>();
    private final boolean placeholderApi;

    private BukkitTask task;
    private boolean enabled;
    private int updateInterval;
    private String lobbyTitle, gameTitle, spectatorTitle;
    private List<String> lobbyLines, gameLines, spectatorLines;

    public ScoreboardManager(GomokuPlugin plugin) {
        this.plugin = plugin;
        this.placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    // ── config ──

    /**
     * Reads the layouts from the active language file (so they are bilingual and travel with
     * lang-version refreshes). Older setups that still keep a {@code scoreboard} section in
     * config.yml keep working as a fallback, with a warning.
     */
    public void load() {
        YamlConfiguration lang = plugin.getLanguageManager().getConfig();
        YamlConfiguration cfg = configFile();

        this.enabled = lang.contains("scoreboard.enabled")
                ? lang.getBoolean("scoreboard.enabled", true)
                : cfg.getBoolean("scoreboard.enabled", true);
        int interval = lang.contains("scoreboard.update-interval")
                ? lang.getInt("scoreboard.update-interval", 20)
                : cfg.getInt("scoreboard.update-interval", 20);
        this.updateInterval = Math.max(1, interval);

        this.lobbyTitle = string(lang, cfg, "scoreboard.lobby.title", DEFAULT_LOBBY_TITLE);
        this.gameTitle = string(lang, cfg, "scoreboard.game.title", DEFAULT_GAME_TITLE);
        this.spectatorTitle = string(lang, cfg, "scoreboard.spectator.title", DEFAULT_SPECTATOR_TITLE);

        List<String> lobby = lines(lang, cfg, "scoreboard.lobby.lines");
        this.lobbyLines = lobby.isEmpty() ? DEFAULT_LOBBY_LINES : lobby;
        List<String> game = lines(lang, cfg, "scoreboard.game.lines");
        this.gameLines = game.isEmpty() ? DEFAULT_GAME_LINES : game;
        List<String> spectator = lines(lang, cfg, "scoreboard.spectator.lines");
        this.spectatorLines = spectator.isEmpty() ? DEFAULT_SPECTATOR_LINES : spectator;

        if (cfg.contains("scoreboard")) {
            plugin.getLogger().warning("config.yml still has a 'scoreboard' section - the layouts now live in "
                    + "languages/" + plugin.getLanguageManager().getCurrentLocale() + ".yml and the config.yml copy is ignored.");
        }
    }

    private YamlConfiguration configFile() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        return file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private static String string(YamlConfiguration lang, YamlConfiguration cfg, String path, String def) {
        if (lang.contains(path)) {
            String v = lang.getString(path);
            if (v != null) return v;
        }
        String v = cfg.getString(path);
        return v != null ? v : def;
    }

    private static List<String> lines(YamlConfiguration lang, YamlConfiguration cfg, String path) {
        List<String> l = lang.getStringList(path);
        return l.isEmpty() ? cfg.getStringList(path) : l;
    }

    /** Reloads the config and re-renders everything. */
    public void reload() {
        stop();
        load();
        start();
    }

    // ── lifecycle ──

    public void start() {
        stop();
        if (!enabled) {
            plugin.getLogger().info("Scoreboard disabled in config.yml.");
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, updateInterval);
        plugin.getLogger().info("Scoreboard enabled (" + updateInterval + " tick refresh).");
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        for (UUID uid : new ArrayList<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) reset(p);
        }
        boards.clear();
    }

    private void reset(Player p) {
        var sbm = Bukkit.getScoreboardManager();
        if (sbm != null) p.setScoreboard(sbm.getMainScoreboard());
    }

    // ── rendering ──

    private void tick() {
        if (!enabled) return;
        boards.keySet().removeIf(uid -> Bukkit.getPlayer(uid) == null);
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                update(p);
            } catch (Exception e) {
                plugin.getLogger().warning("Scoreboard update failed for " + p.getName() + ": " + e.getMessage());
            }
        }
    }

    private void update(Player p) {
        int mode = modeOf(p);
        List<String> template = mode == MODE_GAME ? gameLines
                : mode == MODE_SPECTATE ? spectatorLines : lobbyLines;
        String title = clamp(color(resolve(p, mode == MODE_GAME ? gameTitle
                : mode == MODE_SPECTATE ? spectatorTitle : lobbyTitle)), MAX_TITLE);

        Board b = boards.get(p.getUniqueId());
        if (b == null || b.mode != mode || b.entries.size() != template.size()) {
            b = build(p, template.size(), title, mode);
            if (b == null) return;
            boards.put(p.getUniqueId(), b);
        } else if (!title.equals(b.title)) {
            b.objective.setDisplayName(title);
            b.title = title;
        }

        for (int i = 0; i < template.size(); i++) {
            Team t = b.scoreboard.getTeam(teamName(i));
            if (t == null) continue;
            t.setPrefix(clamp(color(resolve(p, template.get(i))), MAX_LINE));
        }
    }

    /** Decides which of the three layouts this player gets. */
    private int modeOf(Player p) {
        GameSession session = plugin.getGameSessionManager().getGame(p);
        if (session == null) return MODE_LOBBY;
        return session.isSpectator(p) ? MODE_SPECTATE : MODE_GAME;
    }

    private Board build(Player p, int lines, String title, int mode) {
        var sbm = Bukkit.getScoreboardManager();
        if (sbm == null) return null;
        Scoreboard sb = sbm.getNewScoreboard();

        Objective objective = sb.registerNewObjective("gomoku", "dummy", title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        Board b = new Board();
        b.scoreboard = sb;
        b.objective = objective;
        b.title = title;
        b.mode = mode;

        for (int i = 0; i < lines; i++) {
            // Invisible, unique entry: the visible text lives in the team prefix
            ChatColor[] colors = ChatColor.values();
            String entry = colors[i % colors.length].toString() + ChatColor.RESET;
            Team team = sb.registerNewTeam(teamName(i));
            team.addEntry(entry);
            objective.getScore(entry).setScore(lines - i);
            b.entries.add(entry);
        }

        p.setScoreboard(sb);
        return b;
    }

    private static String teamName(int index) { return "gomoku-" + index; }

    /** Resolves placeholders for one line. */
    private String resolve(Player p, String text) {
        if (text == null || text.isEmpty()) return "";
        String out = text;
        if (placeholderApi) {
            try {
                out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, out);
            } catch (Throwable ignored) {
                out = GomokuPlaceholders.replaceAll(plugin, p, out);
            }
        } else {
            out = GomokuPlaceholders.replaceAll(plugin, p, out);
        }
        return out.replace("%player%", p.getName());
    }

    private static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String clamp(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Cached per-player scoreboard state. */
    private static final class Board {
        Scoreboard scoreboard;
        Objective objective;
        String title;
        int mode;
        final List<String> entries = new ArrayList<>();
    }
}
