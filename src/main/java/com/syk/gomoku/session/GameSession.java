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
package com.syk.gomoku.session;

import com.syk.gomoku.GomokuGame;
import com.syk.gomoku.BuiltinAi;
import com.syk.gomoku.GomokuAI;
import com.syk.gomoku.GomokuPlugin;
import com.syk.gomoku.manager.WorldManager;
import com.syk.gomoku.model.Room;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;

public class GameSession {

    public enum State { WAITING, PLAYING, ENDED }

    private final Room room;
    private final GomokuPlugin plugin;
    private State state = State.WAITING;
    private GomokuGame game;
    private Player blackPlayer, whitePlayer;
    private final Set<UUID> spectators = new HashSet<>();
    private boolean blackTurn = true;
    private int totalMoves = 0;

    // Piece look, resolved once when the match starts. A room that pinned its pieces
    // with /gomoku admin check wins over whatever the player picked in the shop.
    private String blackPieceName = "黑子", whitePieceName = "白子";
    private Material blackPieceMat = Material.BLACK_WOOL, whitePieceMat = Material.WHITE_WOOL;

    // Timer
    private BukkitTask timerTask;
    /** Delayed return-to-lobby; cancelled when a room is emptied/edited or a new game starts. */
    private BukkitTask returnTask;
    private BukkitTask returnCountdownTask;
    private int turnSeconds;
    /** Seconds left in the current turn - read by the countdown placeholder. */
    private int remainingSeconds;
    /** Player currently shown the countdown, so it can be wiped when the turn ends. */
    private Player countdownOwner;
    /** AI 是否占着白方席位（玩家执黑先手）。 */
    private boolean aiWhite = false;
    /** AI 等级 1-4（4 = 走 RapFi，未安装时回退内置最强档）。 */
    private int aiLevel = 3;
    /** 上一步棋子四周的高亮方块，换手或结算时清掉。 */
    private final List<Location> highlightBlocks = new java.util.ArrayList<>();

    public GameSession(Room room, GomokuPlugin plugin) {
        this.room = room; this.plugin = plugin;
        this.game = new GomokuGame(room.getBoardSize());
    }

    public Room getRoom() { return room; }
    public State getState() { return state; }
    public boolean isActive() { return state != State.ENDED; }
    public GomokuGame getGame() { return game; }
    public Player getBlackPlayer() { return blackPlayer; }
    public Player getWhitePlayer() { return whitePlayer; }
    public boolean isBlackTurn() { return blackTurn; }
    /** Time budget of a single turn, in seconds. */
    public int getTurnSeconds() { return turnSeconds; }
    /** Seconds left in the current turn (0 once the countdown has run out). */
    public int getRemainingSeconds() { return remainingSeconds; }
    /** Player whose turn it is right now. */
    public Player getCurrentTurnPlayer() { return blackTurn ? blackPlayer : whitePlayer; }
    /** Piece name configured for the side that is on move. */
    public String getCurrentPieceName() {
        return blackTurn ? blackPieceName : whitePieceName;
    }
    public String getBlackPieceName() { return blackPieceName; }
    public String getWhitePieceName() { return whitePieceName; }
    public Material getBlackPieceMaterial() { return blackPieceMat; }
    public Material getWhitePieceMaterial() { return whitePieceMat; }
    public Set<UUID> getAllPlayers() {
        Set<UUID> all = new HashSet<>(spectators);
        if (blackPlayer != null) all.add(blackPlayer.getUniqueId());
        if (whitePlayer != null) all.add(whitePlayer.getUniqueId());
        return all;
    }
    public boolean hasPlayer(Player p) {
        UUID uid = p.getUniqueId();
        return uid.equals(blackPlayer != null ? blackPlayer.getUniqueId() : null)
            || uid.equals(whitePlayer != null ? whitePlayer.getUniqueId() : null)
            || spectators.contains(uid);
    }

    // --- join ---

    public String addPlayer(Player player) { return addPlayer(player, false); }

    /**
     * @param autoMatch true = 自动匹配 (显示"正在匹配"), false = 点击具体房间 (显示"等待玩家")
     */
    public String addPlayer(Player player, boolean autoMatch) {
        var gsm = plugin.getGameSessionManager();
        if (blackPlayer == null) {
            blackPlayer = player; gsm.registerPlayer(player.getUniqueId(), this);
            player.sendMessage(plugin.getMsg(autoMatch ? "join-matching" : "join-success-black",
                    "{room}", room.getName()));
            giveRedstone(player);
            broadcastLobby(plugin.getMsg("lobby-broadcast-black", "{player}", player.getName(), "{room}", room.getName()));
            return "BLACK";
        }
        if (whitePlayer == null) {
            whitePlayer = player; gsm.registerPlayer(player.getUniqueId(), this);
            player.sendMessage(plugin.getMsg("join-success-white", "{room}", room.getName()));
            broadcastLobby(plugin.getMsg("lobby-broadcast-white", "{player}", player.getName(), "{room}", room.getName()));
            startGame();
            return "WHITE";
        }
        spectators.add(player.getUniqueId()); gsm.registerPlayer(player.getUniqueId(), this);
        player.sendMessage(plugin.getMsg("join-success-spectator", "{room}", room.getName()));
        Location sp = room.getPlayerSpawn();
        if (sp == null) sp = new Location(room.getWorld(), 0, 65, room.getHalfSpan() + 3);
        player.teleport(sp); player.setGameMode(GameMode.SPECTATOR);
        return "SPECTATOR";
    }

    private void broadcastLobby(String msg) {
        Location lobby = plugin.getLobbyManager().getLocation();
        if (lobby == null || lobby.getWorld() == null) return;
        String c = ChatColor.translateAlternateColorCodes('&', msg);
        for (Player p : lobby.getWorld().getPlayers())
            if (!hasPlayer(p)) p.sendMessage(c);
    }

    /** 给玩家第 8 格放一颗带 gomoku:exit_item 标签的退出红石。 */
    public void giveExitItem(Player p) {
        if (p == null || !p.isOnline()) return;
        ItemStack rs = new ItemStack(Material.REDSTONE);
        ItemMeta m = rs.getItemMeta();
        if (m != null) {
            m.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    plugin.getRawMsg("exit-item-name")));
            m.setLore(List.of(ChatColor.translateAlternateColorCodes('&',
                    plugin.getRawMsg("exit-item-lore"))));
            m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "exit_item"),
                    org.bukkit.persistence.PersistentDataType.STRING, "1");
            rs.setItemMeta(m);
        }
        p.getInventory().setItem(8, rs);
    }
    private void giveRedstone(Player p) {
        ItemStack rs = new ItemStack(Material.REDSTONE);
        ItemMeta m = rs.getItemMeta();
        if (m != null) {
            // 走语言文件, 不再硬编码英文; 同时打上 exit_item 标签
            m.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    plugin.getRawMsg("exit-item-name")));
            m.setLore(List.of(ChatColor.translateAlternateColorCodes('&',
                    plugin.getRawMsg("exit-item-lore"))));
            m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "exit_item"),
                    org.bukkit.persistence.PersistentDataType.STRING, "1");
            rs.setItemMeta(m);
        }
        p.getInventory().setItem(8, rs);
    }

    // --- start ---

    private void startGame() {
        cancelReturnTask();
        plugin.getWinCelebrationManager().stop(room);
        // 匹配到真人了 -> 5 分钟的 AI 兜底计时作废
        plugin.getRoomMenuManager().cancelAiFallback(room.getName());
        calibrateBoard();   // 用世界里的真实棋盘覆盖记录值
        clearStoneLayer();   // 退出对局把棋子层清成空气
        state = State.PLAYING;
        blackTurn = true; totalMoves = 0;
        game = new GomokuGame(room.getBoardSize());

        // Piece look: room settings win, otherwise the player's shop choice
        this.blackPieceName = plugin.getPieceManager().nameFor(blackPlayer, true, room);
        this.whitePieceName = whitePlayer != null
                ? plugin.getPieceManager().nameFor(whitePlayer, false, room)
                : plugin.getRawMsg("ai-name");
        this.blackPieceMat = plugin.getPieceManager().materialFor(blackPlayer, true, room);
        this.whitePieceMat = whitePlayer != null
                ? plugin.getPieceManager().materialFor(whitePlayer, false, room)
                : Material.WHITE_WOOL;

        String bName = blackPieceName;
        String wName = whitePieceName;
        Material bm = blackPieceMat;
        Material wm = whitePieceMat;
        Location sp = room.getPlayerSpawn();
        if (sp == null) sp = new Location(room.getWorld(), 0, 65, room.getHalfSpan() + 3);

        setupPlayer(blackPlayer, bm, bName, sp.clone(),
                plugin.getRawMsg("game-start-title-black", "{black}", bName));
        setupPlayer(whitePlayer, wm, wName, sp.clone().add(2, 0, 0),
                plugin.getRawMsg("game-start-title-white", "{white}", wName));

        // 正式对局开始: 双方第 8 格发"退出对局"红石
        giveExitItem(blackPlayer);
        giveExitItem(whitePlayer);
        broadcast(plugin.getMsg("game-start", "{black}", bName, "{white}", wName));
        // 开局音效: 双方都听到开门声 (延迟 10 tick, 等传送落地, 否则声音在旧位置播会听不到)
        if (plugin.soundVolume("start") > 0) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (Player px : new Player[]{blackPlayer, whitePlayer}) {
                    if (px != null && px.isOnline()) {
                        px.playSound(px.getLocation(),
                                plugin.soundOf("start", org.bukkit.Sound.BLOCK_WOODEN_DOOR_OPEN),
                                plugin.soundVolume("start"), plugin.soundPitch("start"));
                    }
                }
            }, 10L);
        }
        startTurnTimer();
    }

    /** 开局时从世界扫出真实棋盘外接框, 覆盖配置里的记录值 (它们可能和实际棋盘不一致)。 */
    private void calibrateBoard() {
        World w = room.getWorld();
        if (w == null || !room.isBoardSet()) return;
        Material lineMat = com.syk.gomoku.manager.WorldManager.parseMaterial(room.getLineBlock());
        if (lineMat == null || lineMat == Material.AIR) return;
        int y = room.getBoardY() - 1;                 // 棋盘方块层
        int cx = room.getBoardCenterX();
        int cz = room.getBoardCenterZ();
        int[] seed = null;
        for (int r = 0; r <= 24 && seed == null; r++) {
            for (int dx = -r; dx <= r && seed == null; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (!w.isChunkLoaded((cx + dx) >> 4, (cz + dz) >> 4)) continue;
                    if (w.getBlockAt(cx + dx, y, cz + dz).getType() == lineMat) {
                        seed = new int[]{cx + dx, cz + dz};
                        break;
                    }
                }
            }
        }
        if (seed == null) {
            plugin.getLogger().warning("Board calibration: no grid line near room '" + room.getName() + "'");
            return;
        }
        com.syk.gomoku.manager.BoardScanner.Result res =
                com.syk.gomoku.manager.BoardScanner.scan(w, seed[0], y, seed[1]);
        if (!res.ok()) {
            plugin.getLogger().warning("Board calibration failed for '" + room.getName() + "': " + res.error);
            return;
        }
        String periph = room.getPeripheryBlock();
        room.setDishParams(res.size, res.interval, res.lineBlock, res.fillBlock, "WOOL",
                res.centerX, res.centerZ, res.boardY);
        room.setPeripheryBlock(periph);
        plugin.getLogger().info("Board calibrated for '" + room.getName() + "': " + res.size + "x" + res.size
                + " int=" + res.interval + " center=" + res.centerX + "," + res.centerZ + " y=" + res.boardY);
    }
    private void setupPlayer(Player p, Material mat, String pieceName, Location sp, String title) {
        if (p == null || !p.isOnline()) return;
        p.getInventory().clear();
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    (mat == Material.BLACK_WOOL || mat.name().startsWith("BLACK_") ? "&0" : "&f") + pieceName));
            item.setItemMeta(meta);
        }
        for (int i = 0; i < 36; i++) p.getInventory().setItem(i, item);
        p.teleport(sp); p.setGameMode(GameMode.CREATIVE);
        p.sendTitle(ChatColor.translateAlternateColorCodes('&', title), "", 10, 60, 10);
    }

    // --- timer ---

    private void startTurnTimer() {
        cancelTimer();

        // Every move gets the same budget (config.yml "turn-seconds", default 60)
        int cfgSeconds = 60;
        try {
            File cfg = new File(plugin.getDataFolder(), "config.yml");
            if (cfg.exists()) {
                cfgSeconds = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(cfg).getInt("turn-seconds", 60);
            }
        } catch (Exception ignored) {}
        turnSeconds = Math.max(1, cfgSeconds);
        remainingSeconds = turnSeconds;
        countdownOwner = blackTurn ? blackPlayer : whitePlayer;

        // Countdown shown in the action bar (right above the hotbar). Running out of time
        // ends the round without awarding points to either player.
        timerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != State.PLAYING) { cancel(); clearTimerDisplay(); return; }
                if (remainingSeconds <= 0) {
                    cancel();
                    Player loser = blackTurn ? blackPlayer : whitePlayer;
                    broadcast(plugin.getMsg("game-timeout",
                            "{player}", loser != null ? loser.getName() : "Player"));
                    endTimeout();
                    return;
                }
                // Always update the same player clearTimerDisplay() will wipe
                Player cur = countdownOwner;
                if (cur != null && cur.isOnline()) {
                    cur.sendActionBar(plugin.getRawMsg("game-turn-actionbar",
                            "{seconds}", String.valueOf(remainingSeconds)));
                }
                remainingSeconds--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void cancelTimer() {
        if (timerTask != null) { timerTask.cancel(); timerTask = null; }
        clearTimerDisplay();
    }

    /**
     * Wipes the countdown off the screen the moment the turn ends. Without this the client
     * keeps the last value frozen (and slowly fades it), because no further update arrives;
     * an empty action bar / title replaces the text immediately.
     */
    private void clearTimerDisplay() {
        if (countdownOwner == null) return;
        if (countdownOwner.isOnline()) {
            // Paper's sendActionBar(String) silently drops empty strings
            // (CraftPlayer: "if (message.isEmpty()) return;"), so it never reaches the client.
            // The Component overload has no such guard and actually clears the bar.
            countdownOwner.sendActionBar(Component.empty());
            countdownOwner.resetTitle();
        }
        countdownOwner = null;
    }

    // --- move ---

    public boolean makeMove(Player player, int row, int col) {
        if (state != State.PLAYING || !game.isEmpty(row, col)) return false;
        boolean isBlack = blackPlayer != null && player.getUniqueId().equals(blackPlayer.getUniqueId());
        boolean isWhite = whitePlayer != null && player.getUniqueId().equals(whitePlayer.getUniqueId());
        if ((blackTurn && !isBlack) || (!blackTurn && !isWhite)) return false;

        int side = blackTurn ? GomokuGame.BLACK : GomokuGame.WHITE;
        game.makeMove(row, col);
        totalMoves++;
        highlightLastMove(row, col);

        // Win detection: skip first 5 total moves (can't have 5-in-a-row yet)
        if (totalMoves >= 5 && game.isWinningMove(row, col, side)) {
            cancelTimer();
            broadcastResult(player, isBlack ? whitePlayer : blackPlayer);
            endGame(player); return true;
        }
        if (game.getMoveCount() == room.getBoardSize() * room.getBoardSize()) {
            cancelTimer();
            broadcast(plugin.getMsg("game-draw")); endGame(null); return true;
        }

        blackTurn = !blackTurn;
        startTurnTimer();
        Player next = blackTurn ? blackPlayer : whitePlayer;
        String colorName = blackTurn ? blackPieceName : whitePieceName;
        if (next != null) {
            broadcast(plugin.getMsg("game-turn",
                    "{player}", next.getName(), "{color}", colorName));
            // Whoever has to move now gets a green on-screen prompt
            if (next.isOnline()) next.sendTitle(plugin.getRawMsg("game-turn-title"), "", 5, 40, 10);
        }
        if (aiWhite) scheduleAi();
        return true;
    }

    // --- AI ---

    public boolean hasAi() { return aiWhite; }

    /** 玩家执黑、AI 执白，直接开局（菜单右键 / 匹配超时用）。 */
    public void startWithAi(Player human) {
        startWithAi(human, 3);
    }

    public void startWithAi(Player human, int level) {
        // 只有真正在等待的房间才能转 AI; 不能顶掉已在等待的玩家, 也不复用已结束的会话
        if (state != State.WAITING || human == null) return;
        if (blackPlayer != null && !blackPlayer.getUniqueId().equals(human.getUniqueId())) return;
        if (whitePlayer != null) return;
        this.aiLevel = level;
        blackPlayer = human;
        plugin.getGameSessionManager().registerPlayer(human.getUniqueId(), this);
        aiWhite = true;
        blackTurn = true;
        human.sendMessage(plugin.getMsg("join-ai-start"));
        startGame();
    }

    private void scheduleAi() {
        if (!aiWhite || state != State.PLAYING) return;

        // 地狱档(4) + 15x15 + 引擎可用 -> 走 RapFi，异步 + 20 秒看门狗
        final GomokuAI engine = (aiLevel >= 4 && room.getBoardSize() == 15) ? plugin.getAiEngine() : null;
        if (engine != null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                int[] mv = null;
                try {
                    engine.sendBoard(game.getBoard(), GomokuGame.WHITE);
                    int[] raw = engine.requestBestMove().get(20, java.util.concurrent.TimeUnit.SECONDS);
                    if (raw != null) mv = new int[]{raw[1], raw[0]};   // 引擎 (x,y)=(列,行) -> (row,col)
                } catch (Exception ex) {
                    plugin.getLogger().warning("RapFi move failed: " + ex.getMessage());
                    engine.stopThinking();
                }
                final int[] move = mv;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (state != State.PLAYING || blackTurn || game == null) return;
                    int[] use = move != null ? move
                            : BuiltinAi.bestMove(game.getBoard(), GomokuGame.WHITE, room.getBoardSize(), 3);
                    if (use != null) makeAiMove(use[0], use[1]);
                });
            });
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (state != State.PLAYING || blackTurn || game == null) return;
            int[] mv = BuiltinAi.bestMove(game.getBoard(), GomokuGame.WHITE, room.getBoardSize(), aiLevel);
            if (mv != null) makeAiMove(mv[0], mv[1]);
        }, 30L);
    }

    /** AI 落子：和 makeMove 同一套流程，但不需要 Player。 */
    private void makeAiMove(int row, int col) {
        if (state != State.PLAYING || game == null || !game.isEmpty(row, col)) return;
        if (blackTurn) return;
        game.makeMove(row, col);
        totalMoves++;
        placeStone(row, col);                          // 把白棋真正放到棋盘上
        highlightLastMove(row, col);
        if (totalMoves >= 5 && game.isWinningMove(row, col, GomokuGame.WHITE)) {
            cancelTimer();
            announceResult(null, blackPlayer);      // AI 赢：玩家收到红色"你输了"
            endAiWin();
            return;
        }
        if (game.getMoveCount() == room.getBoardSize() * room.getBoardSize()) {
            cancelTimer();
            broadcast(plugin.getMsg("game-draw"));
            endGame(null);
            return;
        }
        blackTurn = true;
        startTurnTimer();
        if (blackPlayer != null) {
            broadcast(plugin.getMsg("game-turn", "{player}", blackPlayer.getName(), "{color}", blackPieceName));
            if (blackPlayer.isOnline())
                blackPlayer.sendTitle(plugin.getRawMsg("game-turn-title"), "", 5, 40, 10);
        }
    }

    /** Place the correct chess-piece block at the cell center in the world. */
    public void placeStone(int row, int col) {
        World w = room.getWorld();
        if (w == null) return;
        int side = game.getBoard()[row][col];
        if (side == GomokuGame.EMPTY) return;
        Material mat = (side == GomokuGame.BLACK) ? blackPieceMat : whitePieceMat;
        Location loc = new Location(w, room.centerX(row, col), room.getBoardY(), room.centerZ(row, col));
        loc.getBlock().setType(mat);
        // 落子音效: 直接发给本局每个玩家(含观战者)自己的位置, 不依赖棋盘距离
        // config.yml 的 sounds.place.volume 设为 0 可关闭
        if (plugin.soundVolume("place") > 0) {
            org.bukkit.Sound sound = plugin.soundOf("place", org.bukkit.Sound.BLOCK_STONE_PLACE);
            float volume = plugin.soundVolume("place");
            float pitch = plugin.soundPitch("place");
            for (UUID uid : getAllPlayers()) {
                Player pl = Bukkit.getPlayer(uid);
                if (pl != null && pl.isOnline()) {
                    pl.playSound(pl.getLocation(), sound, volume, pitch);
                }
            }
        }
    }

    /** 用红色羊毛把刚下的棋子围一圈，提示对手下了哪里。 */
    private void highlightLastMove(int row, int col) {
        clearHighlight();
        World w = room.getWorld();
        if (w == null) return;
        // 只有格子宽度 >= 3 时, 棋子四周一格才仍然在同一个格子内
        // (interval=1 的格子只有 1 格宽, 画出去就压到邻居格子了, 直接不画)
        if (room.getInterval() < 3) return;
        Location base = new Location(w, room.centerX(row, col), room.getBoardY(), room.centerZ(row, col));
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : offsets) {
            var b = base.clone().add(o[0], 0, o[1]).getBlock();
            if (!b.getType().isAir()) continue;          // 别盖掉已有棋子
            b.setType(Material.RED_WOOL);
            highlightBlocks.add(b.getLocation());
        }
    }

    private void clearHighlight() {
        for (Location loc : highlightBlocks) {
            if (loc.getWorld() != null) loc.getBlock().setType(Material.AIR);
        }
        highlightBlocks.clear();
    }

    /** 把棋盘上方一层（棋子层 + 红羊毛高亮）整片替换成空气。 */
    public void clearStoneLayer() {
        World w = room.getWorld();
        if (w == null || !room.isBoardSet()) return;
        int y = room.getBoardY();
        int size = room.getBoardSize();
        int half = (int) Math.round(room.getCellSpan()) / 2 + 1;
        int x1 = (int) Math.floor(room.centerX(0, 0)) - half;
        int x2 = (int) Math.floor(room.centerX(0, size - 1)) + half;
        int z1 = (int) Math.floor(room.centerZ(0, 0)) - half;
        int z2 = (int) Math.floor(room.centerZ(size - 1, 0)) + half;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                // 只动已加载区块, 绝不触发区块生成
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                w.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }
        highlightBlocks.clear();
    }

    // --- leave ---

    public void removePlayer(Player p) {
        UUID uid = p.getUniqueId();
        spectators.remove(uid);
        // 退出匹配/对局 -> 5 分钟的 AI 兜底计时也结束
        plugin.getRoomMenuManager().cancelAiFallback(room.getName());
        p.getInventory().remove(Material.REDSTONE);

        boolean isBlack = blackPlayer != null && blackPlayer.getUniqueId().equals(uid);
        boolean isWhite = whitePlayer != null && whitePlayer.getUniqueId().equals(uid);
        // 一个棋都没下就退出: 取消对局, 房间内所有人直接回大厅, 不计胜负
        if (state == State.PLAYING && totalMoves == 0 && (isBlack || isWhite)
                && ((isBlack && whitePlayer != null) || (isWhite && blackPlayer != null))) {
            broadcast(plugin.getMsg("game-cancelled"));
            returnToLobby();
            return;
        }

        // Only a participant leaving stops the clock. A spectator leaving must NOT cancel
        // the turn timer, otherwise the running round would silently lose its countdown.
        boolean participantLeft = false;
        if (blackPlayer != null && blackPlayer.getUniqueId().equals(uid)) {
            if (state == State.PLAYING && whitePlayer != null) {
                broadcast(plugin.getMsg("game-forfeit", "{player}", blackPlayer.getName()));
                announceResult(whitePlayer, blackPlayer);
                endGame(whitePlayer);
            }
            blackPlayer = null;
            participantLeft = true;
        }
        if (whitePlayer != null && whitePlayer.getUniqueId().equals(uid)) {
            if (state == State.PLAYING && blackPlayer != null) {
                broadcast(plugin.getMsg("game-forfeit", "{player}", whitePlayer.getName()));
                announceResult(blackPlayer, whitePlayer);
                endGame(blackPlayer);
            }
            whitePlayer = null;
            participantLeft = true;
        }
        if (participantLeft) cancelTimer();

        if (blackPlayer == null && whitePlayer == null && spectators.isEmpty()) {
            // 房间没人了: 庆祝任务停掉, 棋盘清掉, 会话结束
            plugin.getWinCelebrationManager().stop(room);
            plugin.getLogger().info("Room '" + room.getName() + "' has no players; ending session.");
            clearStoneLayer();
            plugin.getGameSessionManager().endSession(room.getName());
            plugin.getWorldBackupManager().restore(room);
        }
    }

    // --- end ---

    private void broadcastResult(Player winner, Player loser) {
        announceResult(winner, loser);
    }

    /** 胜负只发给当事人：赢的人看到"你赢了"，输的人看到"你输了"，观战者看中立总结。 */
    private void announceResult(Player winner, Player loser) {
        clearHighlight();

        if (winner != null && winner.isOnline()) {
            winner.sendMessage(plugin.getMsg("game-you-win"));
            winner.sendTitle(plugin.getRawMsg("game-you-win-title"), "", 5, 50, 10);
        }
        if (loser != null && loser.isOnline()) {
            loser.sendMessage(plugin.getMsg("game-you-lose"));
            loser.sendTitle(plugin.getRawMsg("game-you-lose-title"), "", 5, 50, 10);
        }
        String summary = plugin.getMsg("game-win",
                "{winner}", winner != null ? winner.getName() : (aiWhite ? plugin.getRawMsg("ai-name") : "?"),
                "{loser}", loser != null ? loser.getName() : (aiWhite ? plugin.getRawMsg("ai-name") : "?"));
        for (UUID uid : spectators) {
            Player sp = Bukkit.getPlayer(uid);
            if (sp != null && sp.isOnline()) sp.sendMessage(summary);
        }
    }
    private void endGame(Player winner) {
        state = State.ENDED; cancelTimer();
        if (winner != null) {
            if (blackPlayer != null && winner.getUniqueId().equals(blackPlayer.getUniqueId())) {
                plugin.getStatsManager().recordWin(blackPlayer.getUniqueId(), blackPlayer.getName());
                if (whitePlayer != null) plugin.getStatsManager().recordLoss(whitePlayer.getUniqueId(), whitePlayer.getName());
            } else if (whitePlayer != null) {
                plugin.getStatsManager().recordWin(whitePlayer.getUniqueId(), whitePlayer.getName());
                if (blackPlayer != null) plugin.getStatsManager().recordLoss(blackPlayer.getUniqueId(), blackPlayer.getName());
            }
        } else {
            if (blackPlayer != null) plugin.getStatsManager().recordDraw(blackPlayer.getUniqueId(), blackPlayer.getName());
            if (whitePlayer != null) plugin.getStatsManager().recordDraw(whitePlayer.getUniqueId(), whitePlayer.getName());
        }
        if (winner != null) plugin.getWinCelebrationManager().celebrate(winner, room);
        scheduleReturnToLobby();
    }

    /** AI 赢: 玩家记一负, AI 不记分; 和真人赢/平局分开处理. */
    private void endAiWin() {
        state = State.ENDED; cancelTimer();
        if (blackPlayer != null) {
            plugin.getStatsManager().recordLoss(blackPlayer.getUniqueId(), blackPlayer.getName());
        }
        scheduleReturnToLobby();
    }

    /**
     * Ends the round without touching any statistics. Used when a player runs out of time:
     * neither side is awarded points (the plain draw path would still add score, because
     * PlayerStats#getScore counts draws).
     */
    private void endTimeout() {
        state = State.ENDED; cancelTimer();
        scheduleReturnToLobby();
    }

    /**
     * 结算后先在聊天栏每秒倒计时, 再按 game.return-delay-seconds 返回大厅。
     * 0 = 不显示倒计时, 立即返回。
     */
    private void scheduleReturnToLobby() {
        cancelReturnTask();
        int seconds = plugin.returnDelaySeconds();
        if (seconds > 0) {
            broadcast(plugin.getMsg("return-countdown", "{seconds}", String.valueOf(seconds)));
            if (seconds > 1) {
                returnCountdownTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                    private int left = seconds - 1;
                    @Override public void run() {
                        if (left <= 0) { cancelReturnCountdown(); return; }
                        broadcast(plugin.getMsg("return-countdown", "{seconds}", String.valueOf(left)));
                        left--;
                    }
                }, 20L, 20L);
            }
        }
        if (plugin.returnDelayTicks() <= 0) returnToLobby();
        else returnTask = Bukkit.getScheduler().runTaskLater(plugin, this::returnToLobby, plugin.returnDelayTicks());
    }

    public void returnToLobby() {
        cancelReturnTask();
        cancelTimer();
        Location lobby = plugin.getLobbyManager().getLocation();
        if (lobby != null) {
            // Players had their inventory swapped for game pieces by setupPlayer(); spectators
            // kept their own items, so only the players' inventories may be cleared here.
            returnPlayer(blackPlayer, lobby, true); returnPlayer(whitePlayer, lobby, true);
            for (UUID uid : spectators) { Player sp = Bukkit.getPlayer(uid); returnPlayer(sp, lobby, false); }
            // Nobody should be left stranded in the room world once the match is over
            World w = room.getWorld();
            if (w != null) for (Player other : w.getPlayers()) {
                if (hasPlayer(other)) continue;
                other.teleport(lobby);
            }
        }
        plugin.getGameSessionManager().endSession(room.getName());
        plugin.getWorldBackupManager().restore(room);
    }

    private void returnPlayer(Player p, Location lobby, boolean clearInventory) {
        if (p == null || !p.isOnline()) return;
        if (clearInventory) p.getInventory().clear();
        p.teleport(lobby);
        p.setGameMode(GameMode.ADVENTURE);
        // Hand the lobby hotbar items back - the match inventory replaced them
        plugin.getRoomMenuManager().giveMenuItem(p);
        plugin.getPieceManager().giveMenuItem(p);
    }

    /**
     * Adds a spectator to a running match: registers them, teleports them into the room
     * and switches them to spectator mode. They are returned to the lobby by
     * {@link #returnToLobby()} when the round ends.
     */
    public void addSpectator(Player p) {
        spectators.add(p.getUniqueId());
        plugin.getGameSessionManager().registerPlayer(p.getUniqueId(), this);

        // Spectators keep their own items, but the lobby UI items must be removed
        for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && (plugin.getRoomMenuManager().isMenuItem(it)
                    || plugin.getPieceManager().isMenuItem(it))) {
                p.getInventory().setItem(i, null);
            }
        }

        World w = room.getWorld();
        Location sp = room.getPlayerSpawn();
        if (sp == null && w != null) sp = new Location(w, 0, 65, room.getHalfSpan() + 3);
        if (sp != null) p.teleport(sp);
        p.setGameMode(GameMode.SPECTATOR);
        p.sendMessage(plugin.getMsg("spectate-joined", "{room}", room.getName()));
    }

    public boolean isSpectator(Player p) { return spectators.contains(p.getUniqueId()); }

    /** Number of spectators currently watching this room. */
    public int getSpectatorCount() { return spectators.size(); }

    /** True for the two players of this match, false for spectators. */
    public boolean isParticipant(Player p) {
        UUID uid = p.getUniqueId();
        return (blackPlayer != null && blackPlayer.getUniqueId().equals(uid))
            || (whitePlayer != null && whitePlayer.getUniqueId().equals(uid));
    }

    public void broadcast(String msg) {
        if (blackPlayer != null && blackPlayer.isOnline()) blackPlayer.sendMessage(msg);
        if (whitePlayer != null && whitePlayer.isOnline()) whitePlayer.sendMessage(msg);
        for (UUID uid : spectators) { Player sp = Bukkit.getPlayer(uid); if (sp != null && sp.isOnline()) sp.sendMessage(msg); }
    }

    public void cleanup() { cleanup(false); }

    /**
     * @param restoreWorld true when the room session really ended and the whole
     *                     world should be reset from its backup; false on plugin
     *                     shutdown or when an admin is about to edit the room.
     */
    public void cleanup(boolean restoreWorld) {
        cancelReturnTask();
        cancelTimer();
        plugin.getWinCelebrationManager().stop(room);
        // 房间无人 / 会话结束时整片清掉棋盘上方一格的棋子层(以及高亮)
        clearStoneLayer();
        if (restoreWorld) plugin.getWorldBackupManager().restore(room);
    }

    /** Cancels the delayed return so a room edit/new game cannot be interrupted by it. */
    private void cancelReturnTask() {
        if (returnTask != null) { returnTask.cancel(); returnTask = null; }
        cancelReturnCountdown();
    }

    private void cancelReturnCountdown() {
        if (returnCountdownTask != null) { returnCountdownTask.cancel(); returnCountdownTask = null; }
    }
}
