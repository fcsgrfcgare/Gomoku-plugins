package com.syk.gomoku.command;

import com.syk.gomoku.GomokuPlugin;
import com.syk.gomoku.manager.WorldManager;
import com.syk.gomoku.model.PlayerStats;
import com.syk.gomoku.model.Room;
import com.syk.gomoku.session.GameSession;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class GomokuCommand implements CommandExecutor, TabCompleter {

    /** All /gomoku admin sub-commands, in help order. */
    private static final List<String> ADMIN_SUBS =
            List.of("add","build","dish","fill","check","delete","playerspawn","save","clear","reload","debug");
    /** admin sub-commands that take a room name as the third argument. */
    private static final List<String> ROOM_ARG_SUBS = List.of("add","build","save","clear");
    /** admin sub-commands that edit the board in the world the player stands in. */
    private static final List<String> BOARD_EDIT_SUBS = List.of("dish","fill","check","delete");
    /** Sub-commands that must work before a lobby has been configured. */
    private static final List<String> NO_LOBBY_SUBS = List.of("setlobby","help");
    private final GomokuPlugin plugin;

    public GomokuCommand(GomokuPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            // Console output is English on purpose: the Windows console cannot render
            // the Chinese language strings (they show up as mojibake).
            if (args.length >= 2 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("reload")) {
                sender.sendMessage("[Gomoku] " + reloadAll());
                return true;
            }
            sender.sendMessage("[Gomoku] Console usage: gomoku admin reload");
            sender.sendMessage("[Gomoku] Other commands must be run by a player in game.");
            return true;
        }
        if (args.length == 0) { sendHelp(player); return true; }

        // Every command except setlobby/help requires the lobby to be set
        if (!NO_LOBBY_SUBS.contains(args[0].toLowerCase()) && !plugin.getLobbyManager().isSet()) {
            player.sendMessage(msg("lobby-not-set")); return true;
        }

        return switch (args[0].toLowerCase()) {
            case "help"     -> { sendHelp(player); yield true; }
            case "setlobby" -> handleSetLobby(player);
            case "join"     -> handleJoin(player, args);
            case "spectate" -> handleSpectate(player, args);
            case "leave"    -> handleLeave(player);
            case "stats"    -> handleStats(player, args);
            case "gui"      -> handleGui(player);
            case "pieceshop" -> handlePieceShop(player);
            case "admin"    -> handleAdmin(player, args);
            default         -> { player.sendMessage(msg("unknown-sub")); yield true; }
        };
    }

    private String msg(String k, String... r) { return plugin.getMsg(k, r); }
    private String okFail(boolean ok) { return msg(ok ? "status-ok" : "status-fail"); }

    private boolean needLobby(Player p) {
        if (!plugin.getLobbyManager().isSet()) { p.sendMessage(msg("lobby-not-set")); return false; }
        return true;
    }
    private boolean isInLobby(Player p) {
        return plugin.getLobbyManager().isLobbyWorld(p.getWorld());
    }
    /** /gomoku admin <cmd> <room> - handles the shared permission and argument checks. */
    private boolean withRoom(Player p, String[] args, RoomAction a) {
        if (args.length < 3) { p.sendMessage(msg("admin-usage-short", "{cmd}", args[1])); return true; }
        return a.run(p, args[2]);
    }
    interface RoomAction { boolean run(Player p, String room); }

    /** True when nobody but the command sender is using this room. */
    private boolean roomEmpty(Player sender, Room room) {
        GameSession session = plugin.getGameSessionManager().getSession(room.getName());
        if (session != null && !session.getAllPlayers().isEmpty()) return false;
        World world = room.getWorld();
        if (world != null) {
            for (Player other : world.getPlayers()) {
                if (!other.getUniqueId().equals(sender.getUniqueId())) return false;
            }
        }
        return true;
    }

    /** Blocks admin edits while the room still has a waiting/playing/spectating player. */
    private boolean requireEmptyRoom(Player sender, Room room) {
        if (!roomEmpty(sender, room)) {
            sender.sendMessage(msg("room-busy", "{room}", room.getName()));
            return false;
        }
        // 房间没人, 但会话可能还停在 ENDED 的 3 秒收尾里; 结束它, 免得稍后有人被传回大厅.
        GameSession session = plugin.getGameSessionManager().getSession(room.getName());
        if (session != null && session.getAllPlayers().isEmpty()) {
            plugin.getGameSessionManager().endSession(room.getName());
        }
        return true;
    }

    // -------------------------------------------------------------------------

    private boolean handleSetLobby(Player p) {
        if (!p.hasPermission("gomoku.admin")) { p.sendMessage(msg("no-perm")); return true; }
        plugin.getLobbyManager().setLocation(p.getLocation());
        Location l = p.getLocation();
        p.sendMessage(msg("lobby-set", "{w}", l.getWorld().getName(),
                "{x}", f(l.getX()), "{y}", f(l.getY()), "{z}", f(l.getZ()), "{yaw}", f(l.getYaw())));
        return true;
    }

    private boolean handleAdd(Player p, String name) {
        // Cannot add lobby world
        if (plugin.getLobbyManager().getWorldName() != null
                && plugin.getLobbyManager().getWorldName().equals(name)) {
            p.sendMessage(msg("lobby-build-deny")); return true;
        }
        // If player is in an unsaved room world, require saving first
        String currentWorld = p.getWorld().getName();
        Room currentRoom = plugin.getRoomManager().getRoom(currentWorld);
        if (currentRoom != null && !currentRoom.isEnabled()) {
            p.sendMessage(msg("room-add-must-save", "{room}", currentWorld)); return true;
        }
        if (plugin.getRoomManager().roomExists(name)) {
            p.sendMessage(msg("room-exists", "{room}", name)); return true;
        }
        World w = Bukkit.getWorld(name);
        if (w != null) {
            p.sendMessage(msg("room-world-loaded", "{world}", name));
            plugin.getRoomManager().createRoom(name);
            Room room = plugin.getRoomManager().getRoom(name);
            if (room != null) plugin.getWorldBackupManager().snapshot(room);
            p.sendMessage(msg("room-added", "{room}", name));
            clearInventory(p);                     // enter edit mode with empty hands
            p.getInventory().setItem(0, plugin.createEditAxe());
            p.sendMessage(msg("axe-given"));
            teleportAsync(p, w.getSpawnLocation());
            p.setGameMode(GameMode.CREATIVE);
            return true;
        }
        p.sendMessage(msg("room-add-creating", "{room}", name));
        w = plugin.getWorldManager().createVoidWorld(name);
        if (w == null) { p.sendMessage(msg("room-add-failed")); return true; }
        plugin.getRoomManager().createRoom(name);
        Room room = plugin.getRoomManager().getRoom(name);
        if (room != null) plugin.getWorldBackupManager().snapshot(room);
        p.sendMessage(msg("room-added", "{room}", name));
        Location dest = new Location(w, 0, 70, 5);
        clearInventory(p);                         // enter edit mode with empty hands
        p.getInventory().setItem(0, plugin.createEditAxe());
        p.sendMessage(msg("axe-given"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> teleportAsync(p, dest), 5L);
        p.setGameMode(GameMode.CREATIVE);
        return true;
    }

    private void teleportAsync(Player p, Location loc) {
        if (loc == null) return;
        try { p.teleportAsync(loc); } catch (Exception e) { p.teleport(loc); }
    }

    /**
     * Empties the hotbar and main inventory so edit mode starts clean (no shop chest, no
     * leftover blocks). Armour and the offhand slot are kept.
     */
    private void clearInventory(Player p) {
        for (int i = 0; i < 36; i++) p.getInventory().setItem(i, null);
    }

    private boolean handlePlayerSpawn(Player p, String[] args) {
        if (!p.hasPermission("gomoku.admin")) { p.sendMessage(msg("no-perm")); return true; }
        if (isInLobby(p)) { p.sendMessage(msg("lobby-spawn-deny")); return true; }
        String roomName = p.getWorld().getName();
        Room room = plugin.getRoomManager().getRoom(roomName);
        if (room == null) { p.sendMessage(msg("room-not-found", "{room}", roomName)); return true; }
        if (!requireEmptyRoom(p, room)) return true;
        room.setPlayerSpawn(p.getLocation());
        plugin.getRoomManager().saveRoom(room);
        Location l = p.getLocation();
        p.sendMessage(msg("room-spawn-set", "{room}", roomName,
                "{x}", f(l.getX()), "{y}", f(l.getY()), "{z}", f(l.getZ())));
        return true;
    }

    private boolean handleClear(Player p, String name) {
        // Cannot delete lobby world room
        if (plugin.getLobbyManager().getWorldName() != null
                && plugin.getLobbyManager().getWorldName().equals(name)) {
            p.sendMessage(msg("lobby-clear-deny")); return true;
        }
        Room room = plugin.getRoomManager().getRoom(name);
        if (room == null) { p.sendMessage(msg("room-not-found", "{room}", name)); return true; }
        if (!requireEmptyRoom(p, room)) return true;
        GameSession s = plugin.getGameSessionManager().getSession(name);
        if (s != null) { s.cleanup(); plugin.getGameSessionManager().endSession(name); }
        plugin.getRoomManager().clearRoom(name);
        plugin.getWorldBackupManager().removeBackup(name);
        p.sendMessage(msg("room-cleared", "{room}", name));
        return true;
    }

    private boolean handleSave(Player p, String name) {
        Room room = plugin.getRoomManager().getRoom(name);
        if (room == null) { p.sendMessage(msg("room-not-found", "{room}", name)); return true; }
        // Must be in the room's own world
        if (!p.getWorld().getName().equals(room.getWorldName())) {
            p.sendMessage(msg("room-save-wrong-world", "{room}", name)); return true;
        }
        if (!room.isBoardSet()) { p.sendMessage(msg("room-save-no-board")); return true; }
        if (!room.isSpawnSet()) { p.sendMessage(msg("room-save-no-spawn")); return true; }
        if (!requireEmptyRoom(p, room)) return true;
        room.setEnabled(true);
        plugin.getRoomManager().applyDefaultPieceNames(room);
        plugin.getRoomManager().saveRoom(room);
        // A backup failure must not abort the save - the room is already persisted
        try {
            plugin.getWorldBackupManager().snapshot(room);
        } catch (Exception e) {
            plugin.getLogger().warning("Board backup failed for room '" + name + "': " + e.getMessage());
        }
        // The board cache is temporary: the final board is the room's, earlier attempts are forgotten
        int flushed = plugin.getWorldManager().clearBoardHistory(room.getWorld(), true);
        if (flushed > 0) {
            p.sendMessage(msg("board-save-flushed", "{count}", String.valueOf(flushed)));
        }
        plugin.getBoardPreviewManager().cancelPreview(p);
        p.sendMessage(msg("room-save-done", "{room}", name, "{size}", room.getBoardSize() + "x" + room.getBoardSize()));
        Location lobby = plugin.getLobbyManager().getLocation();
        if (lobby != null) {
            p.setGameMode(GameMode.ADVENTURE);
            clearInventory(p);                          // edit mode is over - the axe goes too
            p.teleport(lobby);
            plugin.getRoomMenuManager().giveMenuItem(p);
        plugin.getPieceManager().giveMenuItem(p);   // lobby UI back
            p.sendMessage(msg("lobby-returned"));
        }
        return true;
    }

    private boolean handleDebug(Player p) {
        if (!p.hasPermission("gomoku.admin")) { p.sendMessage(msg("no-perm")); return true; }
        p.sendMessage(plugin.getRawMsg("debug-header"));
        p.sendMessage(plugin.getRawMsg("debug-locale", "{locale}", plugin.getLanguageManager().getCurrentLocale()));
        p.sendMessage(plugin.getRawMsg("debug-exit-item", "{value}", plugin.getRawMsg("exit-item-name")));
        p.sendMessage(plugin.getRawMsg("debug-exit-confirm", "{value}", plugin.getRawMsg("exit-confirm")));
        p.sendMessage(plugin.getRawMsg("debug-ai-name", "{value}", plugin.getRawMsg("ai-name")));

        Room room = plugin.getRoomManager().getRoom(p.getWorld().getName());
        if (room == null) {
            p.sendMessage(plugin.getRawMsg("debug-no-room"));
        } else {
            p.sendMessage(plugin.getRawMsg("debug-room",
                    "{room}", room.getName(),
                    "{enabled}", String.valueOf(room.isEnabled()),
                    "{boardSet}", String.valueOf(room.isBoardSet())));
            p.sendMessage(plugin.getRawMsg("debug-board",
                    "{size}", String.valueOf(room.getBoardSize()),
                    "{interval}", String.valueOf(room.getInterval()),
                    "{y}", String.valueOf(room.getBoardY()),
                    "{x}", String.valueOf(room.getBoardCenterX()),
                    "{z}", String.valueOf(room.getBoardCenterZ())));
            p.sendMessage(plugin.getRawMsg("debug-blocks",
                    "{line}", String.valueOf(room.getLineBlock()),
                    "{fill}", String.valueOf(room.getFillBlock()),
                    "{periphery}", String.valueOf(room.getPeripheryBlock())));
            double dist = Math.hypot(p.getLocation().getX() - room.getBoardCenterX(),
                    p.getLocation().getZ() - room.getBoardCenterZ());
            p.sendMessage(plugin.getRawMsg("debug-distance",
                    "{distance}", String.format(java.util.Locale.ROOT, "%.1f", dist)));
            int[] cell = room.worldToBoard(p.getLocation());
            p.sendMessage(plugin.getRawMsg("debug-cell",
                    "{cell}", cell == null ? plugin.getRawMsg("debug-cell-none") : cell[0] + "," + cell[1]));
        }

        GameSession s = plugin.getGameSessionManager().getGame(p);
        p.sendMessage(plugin.getRawMsg("debug-session",
                "{inGame}", String.valueOf(plugin.getGameSessionManager().isInGame(p)),
                "{session}", s == null ? plugin.getRawMsg("debug-none") : s.getState().name(),
                "{ai}", s == null ? "-" : String.valueOf(s.hasAi())));
        p.sendMessage(plugin.getRawMsg("debug-footer"));
        return true;
    }
    private String reloadAll() {
        List<String> errors = new ArrayList<>();
        List<String> ok = new ArrayList<>();

        try { plugin.getLanguageManager().reload(); ok.add("Language"); }
        catch (Exception e) { errors.add("Language: " + e.getMessage()); }

        try { ok.add(plugin.getRoomManager().reload()); }
        catch (Exception e) { errors.add("Rooms: " + e.getMessage()); }

        try { plugin.getLobbyManager().reload(); ok.add("Lobby"); }
        catch (Exception e) { errors.add("Lobby: " + e.getMessage()); }

        try { ok.add(plugin.getStatsManager().reload()); }
        catch (Exception e) { errors.add("Stats: " + e.getMessage()); }

        try { plugin.getScoreboardManager().reload(); ok.add("Scoreboard"); }
        catch (Exception e) { errors.add("Scoreboard: " + e.getMessage()); }

        try { plugin.getRoomMenuManager().load(); ok.add("Hotbar"); }
        catch (Exception e) { errors.add("Hotbar: " + e.getMessage()); }

        try { plugin.getPieceManager().reload(); ok.add("Pieces"); }
        catch (Exception e) { errors.add("Pieces: " + e.getMessage()); }

        // Re-render hotbar items in place so a locale switch updates their names immediately
        try {
            for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                plugin.getRoomMenuManager().refreshMenuItem(online);
                plugin.getPieceManager().refreshMenuItem(online);
            }
            ok.add("Hotbar refresh");
        } catch (Exception e) { errors.add("Hotbar refresh: " + e.getMessage()); }

        try {
            GomokuPlugin.ConfigCheckResult cfg = plugin.checkAndFixConfig();
            if (!cfg.fixed.isEmpty()) ok.add("Config (" + cfg.fixed.size() + " fixed)");
            else ok.add("Config");
            if (!cfg.errors.isEmpty()) errors.add("Config: " + String.join(", ", cfg.errors));
        } catch (Exception e) { errors.add("Config: " + e.getMessage()); }

        try { if (plugin.localizeConfigComments()) ok.add("Config comments"); }
        catch (Exception e) { errors.add("Config comments: " + e.getMessage()); }

        try { plugin.loadOptions(); ok.add("Options"); }
        catch (Exception e) { errors.add("Options: " + e.getMessage()); }

        if (!errors.isEmpty()) return "Reload finished with errors: " + String.join(", ", errors);
        return "Reloaded: " + String.join(", ", ok);
    }

    private boolean handleReload(Player p) {
        if (!p.hasPermission("gomoku.admin")) { p.sendMessage(msg("no-perm")); return true; }
        String result = reloadAll();
        if (result.startsWith("Reload finished with errors: ")) {
            p.sendMessage(msg("reload-errors"));
            p.sendMessage(msg("reload-errors-detail", "{errors}",
                    result.substring("Reload finished with errors: ".length())));
            return true;
        }
        p.sendMessage(msg("reload-done", "{items}", result.substring("Reloaded: ".length())));
        p.sendMessage(msg("reload-info",
                "{rooms}", String.valueOf(plugin.getRoomManager().getAllRooms().size()),
                "{lobby}", okFail(plugin.getLobbyManager().isSet())));
        return true;
    }

    private boolean handleAdmin(Player p, String[] args) {
        if (!p.hasPermission("gomoku.admin")) { p.sendMessage(msg("no-perm")); return true; }
        if (args.length < 2) { p.sendMessage(msg("admin-usage")); return true; }
        String sub = args[1].toLowerCase();
        // Board editing commands act on the room world the player stands in
        if (BOARD_EDIT_SUBS.contains(sub) && isInLobby(p)) {
            p.sendMessage(msg("lobby-admin-deny")); return true;
        }
        return switch (sub) {
            case "add"         -> withRoom(p, args, this::handleAdd);
            case "clear"       -> withRoom(p, args, this::handleClear);
            case "save"        -> withRoom(p, args, this::handleSave);
            case "playerspawn" -> handlePlayerSpawn(p, args);
            case "debug" -> handleDebug(p);
            case "reload"      -> handleReload(p);
            case "build"       -> handleAdminBuild(p, args);
            case "dish"        -> handleAdminDish(p, args);
            case "fill"        -> handleAdminFill(p, args);
            case "check"       -> handleAdminCheck(p, args);
            case "delete"      -> handleAdminDelete(p, args);
            default            -> { p.sendMessage(msg("admin-usage")); yield true; }
        };
    }

    private boolean handleAdminBuild(Player p, String[] args) {
        if (args.length < 3) { p.sendMessage(msg("admin-build-usage")); return true; }
        String name = args[2];

        // The lobby world itself cannot be built as a room
        if (plugin.getLobbyManager().getWorldName() != null
                && plugin.getLobbyManager().getWorldName().equals(name)) {
            p.sendMessage(msg("lobby-build-deny")); return true;
        }

        Room room = plugin.getRoomManager().getRoom(name);
        if (room == null) { p.sendMessage(msg("room-not-found", "{room}", name)); return true; }
        if (!requireEmptyRoom(p, room)) return true;
        World w = room.getWorld();
        if (w == null) { plugin.getWorldManager().createVoidWorld(name); w = room.getWorld(); }
        if (w == null) return true;
        plugin.getWorldBackupManager().snapshot(room);

        int cellSpan = (room.getInterval() > 0 ? room.getInterval() : 1) + 1;
        double halfSpan = room.isBoardSet() ? room.getHalfSpan() : room.getBoardSize() * cellSpan / 2.0;

        // 先关闭房间再传送管理员: 避免确认无人后正好有人加入, 也避免残留会话把人传回大厅
        if (room.isEnabled()) {
            GameSession s = plugin.getGameSessionManager().getSession(name);
            if (s != null) {
                if (!s.getAllPlayers().isEmpty()) {
                    // 极小概率竞态: 确认无人后有人挤进来, 先把他们送回大厅
                    s.broadcast(msg("room-cheat-close"));
                    s.returnToLobby();
                } else {
                    s.cleanup();
                    plugin.getGameSessionManager().endSession(name);
                }
            }
            room.setEnabled(false);
            plugin.getRoomManager().saveRoom(room);
        }

        // 已有棋盘的房间要回到原棋盘中心, 否则 dish/fill 会建到世界原点, 旧棋盘还留在原处
        Location editSpawn = room.isBoardSet()
                ? new Location(w, room.getBoardCenterX(), room.getBoardY(), room.getBoardCenterZ())
                : new Location(w, 0, 70, halfSpan + 5);

        clearInventory(p);                         // edit mode starts with empty hands
        p.getInventory().setItem(0, plugin.createEditAxe());
        p.sendMessage(msg("axe-given"));           // usage in chat on every entry
        teleportAsync(p, editSpawn);
        p.setGameMode(GameMode.CREATIVE);

        // Status report: what this room still needs before it can be saved
        p.sendMessage(msg("room-build-header", "{room}", name));
        p.sendMessage(msg("room-build-lobby", "{status}", okFail(plugin.getLobbyManager().isSet())));
        p.sendMessage(msg("room-build-spawn", "{status}", okFail(room.isSpawnSet())));
        p.sendMessage(msg("room-build-board", "{status}",
                room.isBoardSet()
                        ? msg("status-ok") + " (" + room.getBoardSize() + "x" + room.getBoardSize()
                          + " int=" + room.getInterval() + ")"
                        : msg("status-fail")));

        if (!room.isSpawnSet()) p.sendMessage(msg("room-build-spawn-missing", "{room}", name));
        else if (!room.isBoardSet()) {
            p.sendMessage(msg("room-build-hint", "{room}", name));
            p.sendMessage(msg("room-dish-blocks"));
        } else p.sendMessage(msg("room-build-ready", "{room}", name));
        return true;
    }

    private boolean handleAdminDish(Player p, String[] args) {
        if (args.length < 3) { p.sendMessage(msg("admin-dish-usage")); return true; }
        int size;
        try { size = Integer.parseInt(args[2]); } catch (NumberFormatException e) {
            p.sendMessage(msg("admin-bad-number", "{input}", args[2])); return true;
        }
        if (size < 15 || size > 64) { p.sendMessage(msg("admin-bad-size")); return true; }
        Room room = plugin.getRoomManager().getRoom(p.getWorld().getName());
        if (room != null && !requireEmptyRoom(p, room)) return true;
        int gap = 3;
        plugin.getBoardPreviewManager().startPreview(p, size, gap);
        p.sendMessage(msg("admin-dish-start", "{size}", String.valueOf(size), "{gap}", String.valueOf(gap)));
        p.sendMessage(msg("admin-fill-usage"));
        return true;
    }

    private boolean handleAdminFill(Player p, String[] args) {
        if (args.length < 5) { p.sendMessage(msg("admin-fill-usage")); return true; }
        Room room = plugin.getRoomManager().getRoom(p.getWorld().getName());
        if (room == null) { p.sendMessage(msg("admin-fill-room-only")); return true; }
        if (!requireEmptyRoom(p, room)) return true;
        var session = plugin.getBoardPreviewManager().getSession(p);
        if (session == null) { p.sendMessage(msg("admin-no-preview")); return true; }

        String lineBlock = args[2].toUpperCase();
        String fillBlock = args[3].toUpperCase();
        String peripheryBlock = args[4].toUpperCase();
        int size = session.size;
        int gap = 3;

        if (lineBlock.equalsIgnoreCase(fillBlock)) {
            p.sendMessage(msg("admin-fill-same")); return true;
        }
        Material lm = WorldManager.parseMaterial(lineBlock);
        Material fm = WorldManager.parseMaterial(fillBlock);
        Material pm = WorldManager.parseMaterial(peripheryBlock);
        if (WorldManager.isBlocked(lm) || WorldManager.isBlocked(fm) || WorldManager.isBlocked(pm)) {
            p.sendMessage(msg("admin-fill-blocked")); return true;
        }
        if (lm == Material.AIR || fm == Material.AIR || pm == Material.AIR) {
            p.sendMessage(msg("admin-fill-air")); return true;
        }

        String chessType = "WOOL";
        World w = p.getWorld();
        // 优先严格照 dish 预览的位置建 (预览在哪, 棋盘就在哪)。
        // 只有在没有有效预览时才退回管理员脚下, 并且把来源打印出来, 便于核对。
        int[] center = plugin.getBoardPreviewManager().getBuildCenter(p);
        int cx, cz, boardY;
        String source;
        if (center != null) {
            cx = center[0]; cz = center[1]; boardY = center[2];
            source = "preview";
        } else {
            // 没有预览就不许建: 严格保证"预览在哪, 棋盘就在哪"
            p.sendMessage(msg("admin-fill-need-preview"));
            return true;
        }
        p.sendMessage(msg("admin-fill-warn",
                "{size}", String.valueOf(size), "{gap}", String.valueOf(gap),
                "{line}", lineBlock, "{fill}", fillBlock, "{periphery}", peripheryBlock,
                "{cx}", cx + " [" + source + "]", "{cz}", String.valueOf(cz)));

        String roomName = w.getName();
        // 长耗时的异步铺板期间不允许玩家加入, 保存房间时再重新启用
        boolean wasEnabled = room.isEnabled();
        if (wasEnabled) { room.setEnabled(false); plugin.getRoomManager().saveRoom(room); }
        if (!requireEmptyRoom(p, room)) {
            if (wasEnabled) { room.setEnabled(true); plugin.getRoomManager().saveRoom(room); }
            return true;
        }
        // 同一世界重复 dish+fill 会堆出多块棋盘, 旧的不清掉就会"房间指着一块、人站在另一块"。
        // 建新板前把这个世界里记录过的棋盘方块全部删掉。
        int removedOld = plugin.getWorldManager().clearBoardHistory(w, false);
        if (removedOld > 0) {
            p.sendMessage(msg("board-fill-replaced", "{count}", String.valueOf(removedOld)));
        }
        if (room != null) {
            room.setDishParams(size, gap, lineBlock, fillBlock, chessType, cx, cz, boardY);
            room.setPeripheryBlock(WorldManager.parseMaterial(peripheryBlock).name());
            plugin.getRoomManager().saveRoom(room);
        }


        plugin.getBoardPreviewManager().cancelPreview(p);

        plugin.getWorldManager().generateBoardAsync(w, size, gap, lineBlock, fillBlock, cx, cz, boardY,
                peripheryBlock, () -> {
                    p.sendMessage(msg("admin-fill-done", "{size}", String.valueOf(size), "{gap}", String.valueOf(gap),
                            "{line}", lineBlock, "{fill}", fillBlock));
                    var recs = plugin.getWorldManager().boardHistory(w);
                    if (!recs.isEmpty()) {
                        p.sendMessage(msg("board-cached-hint",
                                "{no}", String.valueOf(recs.get(recs.size() - 1).no)));
                    }
                    if (room != null) p.sendMessage(msg("admin-next-spawn", "{room}", roomName));
                });
        return true;
    }

    private boolean handleAdminCheck(Player p, String[] args) {
        if (args.length < 4) {
            p.sendMessage(msg("admin-chess-usage"));
            return true;
        }
        String roomName = p.getWorld().getName();
        Room room = plugin.getRoomManager().getRoom(roomName);
        if (room == null) { p.sendMessage(msg("room-not-found", "{room}", roomName)); return true; }
        if (!requireEmptyRoom(p, room)) return true;

        String blackName = args[2];
        String whiteName = args[3];

        org.bukkit.inventory.ItemStack slot0 = p.getInventory().getItem(0);
        org.bukkit.inventory.ItemStack slot1 = p.getInventory().getItem(1);
        Material blackMat = (slot0 != null && slot0.getType() != Material.AIR) ? slot0.getType() : Material.BLACK_WOOL;
        Material whiteMat = (slot1 != null && slot1.getType() != Material.AIR) ? slot1.getType() : Material.WHITE_WOOL;

        room.setBlackPieceName(blackName);
        room.setWhitePieceName(whiteName);
        room.setBlackPieceMaterial(blackMat.name());
        room.setWhitePieceMaterial(whiteMat.name());
        room.setPiecesSet(true);   // pinned by the admin -> the piece shop no longer applies here
        plugin.getRoomManager().saveRoom(room);

        p.sendMessage(msg("admin-chess-set", "{black}", blackName, "{white}", whiteName));
        if (room.isBoardSet() && room.isSpawnSet()) p.sendMessage(msg("admin-next-save", "{room}", roomName));
        return true;
    }

    /**
     * /gomoku admin delete            - remove the most recently built board
     * /gomoku admin delete &lt;no&gt;  - remove the board with that creation number
     * /gomoku admin delete list       - show the cached boards of this world
     */
    private boolean handleAdminDelete(Player p, String[] args) {
        World w = p.getWorld();

        if (args.length >= 3 && args[2].equalsIgnoreCase("list")) {
            List<WorldManager.BoardRecord> recs = plugin.getWorldManager().boardHistory(w);
            if (recs.isEmpty()) { p.sendMessage(msg("board-list-empty")); return true; }
            p.sendMessage(msg("board-list-header"));
            for (WorldManager.BoardRecord r : recs) {
                p.sendMessage(msg("board-list-line",
                        "{no}", String.valueOf(r.no),
                        "{x}", String.valueOf(r.centerX),
                        "{y}", String.valueOf(r.boardY),
                        "{z}", String.valueOf(r.centerZ),
                        "{size}", String.valueOf(r.size)));
            }
            return true;
        }

        Room room = plugin.getRoomManager().getRoom(w.getName());
        if (room != null && !requireEmptyRoom(p, room)) return true;

        WorldManager.BoardRecord rec;
        if (args.length >= 3) {
            int no;
            try { no = Integer.parseInt(args[2]); }
            catch (NumberFormatException e) {
                p.sendMessage(msg("admin-bad-number", "{input}", args[2]));
                return true;
            }
            rec = plugin.getWorldManager().deleteBoard(w, no);
            if (rec == null) {
                p.sendMessage(msg("board-delete-notfound", "{no}", String.valueOf(no)));
                return true;
            }
        } else {
            rec = plugin.getWorldManager().deleteLastBoard(w);
            if (rec == null) { p.sendMessage(msg("board-delete-none")); return true; }
        }

        // If the room is pointing at the board we just removed, it no longer has a board
        if (room != null && room.isBoardSet()
                && room.getBoardCenterX() == rec.centerX && room.getBoardCenterZ() == rec.centerZ) {
            room.setBoardSet(false);
            room.setEnabled(false);
            plugin.getRoomManager().saveRoom(room);
            p.sendMessage(msg("board-delete-current", "{room}", room.getName()));
        }

        p.sendMessage(msg("board-delete-done",
                "{no}", String.valueOf(rec.no),
                "{x}", String.valueOf(rec.centerX),
                "{y}", String.valueOf(rec.boardY),
                "{z}", String.valueOf(rec.centerZ),
                "{count}", String.valueOf(rec.blockCount())));
        return true;
    }

    // -------------------------------------------------------------------------

    private boolean handleGui(Player p) {
        if (plugin.getGameSessionManager().isInGame(p)) {
            p.sendMessage(msg("gui-in-game"));
            return true;
        }
        plugin.getRoomMenuManager().open(p);
        return true;
    }

    private boolean handlePieceShop(Player p) {
        plugin.getPieceManager().openMenu(p);
        return true;
    }

    private boolean handleJoin(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage(msg("join-usage")); return true; }
        if (!needLobby(p)) return true;
        if (plugin.getGameSessionManager().isInGame(p)) { p.sendMessage(msg("join-already")); return true; }

        if (args[1].equalsIgnoreCase("auto")) return joinAuto(p);

        Room room = plugin.getRoomManager().getRoom(args[1]);
        if (room == null || !room.isEnabled()) { p.sendMessage(msg("join-not-found", "{room}", args[1])); return true; }
        if (room.getWorld() == null) {
            plugin.getWorldManager().createVoidWorld(room.getWorldName());
            if (room.getWorld() == null) {
                p.sendMessage(msg("join-no-world", "{world}", room.getWorldName()));
                return true;
            }
        }
        GameSession s = plugin.getGameSessionManager().getSession(args[1]);
        if (s == null) s = plugin.getGameSessionManager().createSession(room);
        if (s.getState() == GameSession.State.ENDED) { p.sendMessage(msg("join-ended")); return true; }
        s.addPlayer(p);
        return true;
    }

    private boolean joinAuto(Player p) {
        for (Room room : plugin.getRoomManager().getAllRooms()) {
            if (!room.isEnabled()) continue;
            GameSession s = plugin.getGameSessionManager().getSession(room.getName());
            if (s != null && s.getState() == GameSession.State.WAITING) {
                if (room.getWorld() == null) plugin.getWorldManager().createVoidWorld(room.getWorldName());
                if (room.getWorld() == null) continue;
                s.addPlayer(p, true);
                return true;
            }
        }
        for (Room room : plugin.getRoomManager().getAllRooms()) {
            if (!room.isEnabled()) continue;
            GameSession s = plugin.getGameSessionManager().getSession(room.getName());
            if (s != null && s.getState() == GameSession.State.ENDED) continue;
            if (room.getWorld() == null) plugin.getWorldManager().createVoidWorld(room.getWorldName());
            if (room.getWorld() == null) continue;
            if (s == null) s = plugin.getGameSessionManager().createSession(room);
            s.addPlayer(p, true);
            return true;
        }
        p.sendMessage(msg("join-not-found", "{room}", "auto"));
        return true;
    }

    private boolean handleSpectate(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage(msg("spectate-usage")); return true; }
        Room room = plugin.getRoomManager().getRoom(args[1]);
        if (room == null) { p.sendMessage(msg("room-not-found", "{room}", args[1])); return true; }
        if (plugin.getGameSessionManager().isInGame(p)) { p.sendMessage(msg("join-already")); return true; }

        // Only a running match can be watched. Same definition the room counters use
        // (GameSessionManager#isRoomPlaying), so the placeholder and this check never disagree.
        if (!plugin.getGameSessionManager().isRoomPlaying(room.getName())) {
            p.sendMessage(msg("spectate-not-started", "{room}", room.getName()));
            return true;
        }
        GameSession s = plugin.getGameSessionManager().getSession(room.getName());
        if (room.getWorld() == null) {
            plugin.getWorldManager().createVoidWorld(room.getWorldName());
            if (room.getWorld() == null) {
                p.sendMessage(msg("join-no-world", "{world}", room.getWorldName()));
                return true;
            }
        }
        if (s != null) s.addSpectator(p);
        return true;
    }

    private boolean handleLeave(Player p) {
        GameSession s = plugin.getGameSessionManager().getGame(p);
        if (s == null) { p.sendMessage(msg("leave-not-in-game")); return true; }
        boolean participant = s.isParticipant(p);
        s.removePlayer(p);
        plugin.getGameSessionManager().unregisterPlayer(p.getUniqueId());
        Location lobby = plugin.getLobbyManager().getLocation();
        if (lobby != null) p.teleport(lobby);
        // Leaving a match must not leave the player in CREATIVE or SPECTATOR
        p.setGameMode(GameMode.ADVENTURE);
        // Match pieces were handed out by setupPlayer(); a spectator's own items must survive
        if (participant) p.getInventory().clear();
        else p.getInventory().remove(Material.REDSTONE);
        plugin.getRoomMenuManager().giveMenuItem(p);
        plugin.getPieceManager().giveMenuItem(p);
        p.sendMessage(msg("leave-success"));
        return true;
    }

    private boolean handleStats(Player p, String[] args) {
        String name = args.length >= 2 ? args[1] : p.getName();
        Player t = Bukkit.getPlayer(name);
        if (t == null) { p.sendMessage(msg("stats-offline")); return true; }
        PlayerStats s = plugin.getStatsManager().get(t.getUniqueId());
        if (s == null) { p.sendMessage(msg("stats-no-data", "{player}", name)); return true; }
        p.sendMessage(msg("stats-header", "{player}", s.getLastName()));
        p.sendMessage(msg("stats-detail",
                "{level}", String.valueOf(s.getLevel()),
                "{wins}", String.valueOf(s.getWins()),
                "{losses}", String.valueOf(s.getLosses()),
                "{draws}", String.valueOf(s.getDraws())));
        p.sendMessage(msg("stats-winrate",
                "{winrate}", String.format("%.1f%%", s.getWinRate()),
                "{score}", String.valueOf(s.getScore())));
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage(msg("help-header"));
        if (p.hasPermission("gomoku.admin")) p.sendMessage(msg("help-admin"));
        p.sendMessage(msg("help-player"));
        p.sendMessage(msg("help-rules"));
    }

    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String l, String[] args) {
        if (args.length == 1) {
            List<String> o = new ArrayList<>(List.of("help","join","spectate","leave","stats","gui","pieceshop"));
            if (s.hasPermission("gomoku.admin")) { o.add("setlobby"); o.add("admin"); }
            return filter(o, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("admin") && s.hasPermission("gomoku.admin"))
                return filter(ADMIN_SUBS, args[1]);
            if (sub.equals("join")) {
                List<String> names = new ArrayList<>(plugin.getRoomManager().getRoomNames());
                names.add("auto");
                return filter(names, args[1]);
            }
            if (sub.equals("spectate"))
                return filter(plugin.getRoomManager().getAllRooms().stream()
                        .map(Room::getName)
                        .filter(name -> plugin.getGameSessionManager().isRoomPlaying(name))
                        .sorted().toList(), args[1]);
            if (sub.equals("stats"))
                return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            return List.of();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && s.hasPermission("gomoku.admin")
                && ROOM_ARG_SUBS.contains(args[1].toLowerCase())) {
            return filter(plugin.getRoomManager().getRoomNames(), args[2]);
        }
        return List.of();
    }
    private List<String> filter(List<String> o, String in) {
        String low = in.toLowerCase();
        return o.stream().filter(x -> x.toLowerCase().startsWith(low)).sorted().toList();
    }
    private String f(double d) { return String.format("%.1f", d); }
}
