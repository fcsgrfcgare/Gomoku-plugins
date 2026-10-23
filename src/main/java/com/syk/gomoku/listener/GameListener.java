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
package com.syk.gomoku.listener;

import com.syk.gomoku.GomokuPlugin;
import com.syk.gomoku.manager.BoardScanner;
import com.syk.gomoku.manager.PieceManager;
import com.syk.gomoku.manager.RoomMenuManager;
import com.syk.gomoku.model.Room;
import com.syk.gomoku.session.GameSession;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class GameListener implements Listener {

    private final GomokuPlugin plugin;

    public GameListener(GomokuPlugin plugin) { this.plugin = plugin; }

    // Sponsor message comes from the language file (op-join-message)

    // Teleport new players to lobby
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.setJoinMessage(null);   // 由插件按 join-message 配置广播, 避免和原版重复
        Location lobby = plugin.getLobbyManager().getLocation();
        if (lobby != null) {
            p.teleportAsync(lobby);
            // 只有配置了大厅才发大厅物品, 避免普通服/未设大厅时把物品塞给玩家
            plugin.getRoomMenuManager().giveMenuItem(p);
            plugin.getPieceManager().giveMenuItem(p);
        }
        // Sponsor message for OPs, from the language file
        if (p.isOp()) {
            p.sendMessage(plugin.getMsg("op-join-message"));
        }

        // 大厅/对局中的加入提示
        String joinMsg = plugin.getMsg("join-broadcast", "{player}", p.getName());
        if (plugin.joinMessageLobby() && lobby != null) {
            for (Player other : lobby.getWorld().getPlayers()) {
                if (!other.getUniqueId().equals(p.getUniqueId())) other.sendMessage(joinMsg);
            }
        }
        if (plugin.joinMessageRoom()) {
            for (GameSession session : plugin.getGameSessionManager().getAllSessions()) {
                if (session.isActive() && session.getRoom().isEnabled()) session.broadcast(joinMsg);
            }
        }
    }

    // -- Piece shop: open the GUI from the hotbar item --
    @EventHandler
    public void onShopOpen(PlayerInteractEvent e) {
        // Lobby-only: never open the menus while playing or spectating
        if (plugin.getGameSessionManager().isInGame(e.getPlayer())) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_AIR) return;
        if (plugin.getRoomMenuManager().isMenuItem(e.getItem())) {
            e.setCancelled(true);
            plugin.getRoomMenuManager().open(e.getPlayer());
            return;
        }
        if (!plugin.getPieceManager().isMenuItem(e.getItem())) return;
        e.setCancelled(true);
        plugin.getPieceManager().openMenu(e.getPlayer());
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof RoomMenuManager.LevelHolder levelHolder) {
            e.setCancelled(true);
            if (e.getClickedInventory() != top) return;
            plugin.getRoomMenuManager().handleLevelClick(p, levelHolder, e.getRawSlot());
            return;
        }
        if (top.getHolder() instanceof RoomMenuManager.Holder menu) {
            e.setCancelled(true);
            if (e.getClickedInventory() != top) return;
            plugin.getRoomMenuManager().handleClick(p, menu, e.getRawSlot(), e.getClick().isRightClick());
            return;
        }
        if (!(top.getHolder() instanceof PieceManager.MenuHolder holder)) return;
        e.setCancelled(true);                       // never let items be taken out of the GUI
        if (e.getClickedInventory() != top) return; // clicks inside the player's own inventory
        plugin.getPieceManager().handleClick(p, holder, e.getRawSlot());
    }

    @EventHandler
    public void onShopDrag(InventoryDragEvent e) {
        Object holder = e.getView().getTopInventory().getHolder();
        if (holder instanceof PieceManager.MenuHolder || holder instanceof RoomMenuManager.Holder) {
            e.setCancelled(true);
            return;
        }
        // Dragging a UI item around inside the player's own inventory is blocked too
        for (ItemStack it : e.getNewItems().values()) {
            if (isUiItem(it)) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onShopDrop(PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();
        if (plugin.getPieceManager().isMenuItem(item) || plugin.getRoomMenuManager().isMenuItem(item))
            e.setCancelled(true);
    }

    /**
     * 准心瞄准棋盘落子：只允许左键，用射线找到玩家看的那一格。
     */
    @EventHandler
    public void onAimClick(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action a = e.getAction();
        if (!a.isLeftClick()) return;
        if (!plugin.getGameSessionManager().isInGame(p)) return;
        if (isExitItem(p.getInventory().getItemInMainHand())) return;   // 手持退出物品时不走射线
        GameSession s = plugin.getGameSessionManager().getGame(p);
        if (s == null) return;
        // 用精确射线拿到"命中点"，而不是只拿到方块
        org.bukkit.util.RayTraceResult hit =
                p.rayTraceBlocks(520, org.bukkit.FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || hit.getHitPosition() == null) return;
        org.bukkit.block.Block target = hit.getHitBlock();
        int[] cell = s.getRoom().worldToBoard(target.getLocation());
        if (cell == null) return;

        // 命中点必须落在格子内部：离井字线至少 0.3 格才算数
        // 格子自身半宽 = interval / 2（interval=3 -> 1.5, interval=1 -> 0.5）
        double half = s.getRoom().getInterval() / 2.0 - 0.3;
        if (half < 0.05) half = 0.05;
        org.bukkit.util.Vector hp = hit.getHitPosition();
        double cx = s.getRoom().centerX(cell[0], cell[1]);
        double cz = s.getRoom().centerZ(cell[0], cell[1]);
        if (Math.abs(hp.getX() - cx) > half || Math.abs(hp.getZ() - cz) > half) return;

        e.setCancelled(true);
        if (s.makeMove(p, cell[0], cell[1])) {
            plugin.getServer().getScheduler().runTask(plugin, () -> s.placeStone(cell[0], cell[1]));
        }
    }

    /** 把玩家身上的"退出对局"物品全部清掉。 */
    private void removeExitItem(Player p) {
        for (int i = 0; i < 36; i++) {
            if (isExitItem(p.getInventory().getItem(i))) p.getInventory().setItem(i, null);
        }
    }
    /** 判断是否是"退出对局"那个物品 (靠 PDC 标签, 不看材质)。 */
    private boolean isExitItem(org.bukkit.inventory.ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        return m != null && m.getPersistentDataContainer()
                .has(new org.bukkit.NamespacedKey(plugin, "exit_item"),
                        org.bukkit.persistence.PersistentDataType.STRING);
    }

    /** 房间/大厅里死亡不掉落, 避免对局/大厅物品丢失。 */
    @EventHandler
    public void onRoomDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Player p = e.getEntity();
        boolean roomWorld = plugin.getRoomManager().getRoom(p.getWorld().getName()) != null;
        boolean lobbyWorld = plugin.getLobbyManager().isLobbyWorld(p.getWorld());
        if (!roomWorld && !lobbyWorld) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
    }
    /** 对局中把退出红石放到物品栏第 8 格 (最右边)。 */
    private void ensureExitItem(Player p) {
        GameSession s = plugin.getGameSessionManager().getGame(p);
        if (s == null || s.getState() != GameSession.State.PLAYING) return;   // 非正式对局不发, 也不删
        org.bukkit.inventory.ItemStack cur = p.getInventory().getItem(8);
        if (isExitItem(cur)) return;
        org.bukkit.inventory.ItemStack exitItem = new org.bukkit.inventory.ItemStack(Material.REDSTONE);
        org.bukkit.inventory.meta.ItemMeta em = exitItem.getItemMeta();
        if (em != null) {
            em.setDisplayName(plugin.getRawMsg("exit-item-name"));
            em.setLore(java.util.List.of(plugin.getRawMsg("exit-item-lore")));
            em.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "exit_item"),
                    org.bukkit.persistence.PersistentDataType.STRING, "1");
            exitItem.setItemMeta(em);
        }
        p.getInventory().setItem(8, exitItem);
    }
    private final java.util.Map<java.util.UUID, Long> exitPending = new java.util.HashMap<>();

    private boolean exitWatchdogStarted = false;

    /** 每 20 tick 检查: 等待匹配中的玩家必须持有那颗红石, 丢了就补回来。 */
    private void startExitWatchdog() {
        if (exitWatchdogStarted) return;
        exitWatchdogStarted = true;
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                GameSession s = plugin.getGameSessionManager().getGame(p);
                if (s == null) continue;
                GameSession.State st = s.getState();
                if (st != GameSession.State.WAITING && st != GameSession.State.PLAYING) continue;
                if (isExitItem(p.getInventory().getItem(8))) continue;
                p.getInventory().setItem(8, makeExitItem());
            }
        }, 20L, 20L);
    }

    /** 生成一颗带 gomoku:exit_item 标签的红石。 */
    private org.bukkit.inventory.ItemStack makeExitItem() {
        org.bukkit.inventory.ItemStack rs = new org.bukkit.inventory.ItemStack(Material.REDSTONE);
        org.bukkit.inventory.meta.ItemMeta m = rs.getItemMeta();
        if (m != null) {
            m.setDisplayName(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    plugin.getRawMsg("exit-item-name")));
            m.setLore(java.util.List.of(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    plugin.getRawMsg("exit-item-lore"))));
            m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "exit_item"),
                    org.bukkit.persistence.PersistentDataType.STRING, "1");
            rs.setItemMeta(m);
        }
        return rs;
    }
    /** 进大厅: 强制清空背包与物品栏, 再发下界之星和箱子。 */
    @EventHandler
    public void onLobbyJoinClean(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            // 没配大厅(普通服)时绝不能清玩家背包
            if (!plugin.getLobbyManager().isSet()) return;
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.setGameMode(org.bukkit.GameMode.ADVENTURE);
            plugin.getRoomMenuManager().giveMenuItem(p);
            plugin.getPieceManager().giveMenuItem(p);
        }, 2L);
    }

    /** 对局中: 第一次提示确认, 5 秒后可再点退出; 结算倒计时中: 只把自己送回大厅, 其他人继续等待。 */
    @EventHandler
    public void onRedstoneExit(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action a = e.getAction();
        if (!a.isLeftClick() && !a.isRightClick()) return;
        if (!isExitItem(p.getInventory().getItemInMainHand())) return;
        GameSession exitSession = plugin.getGameSessionManager().getGame(p);
        if (exitSession == null) return;
        // 结算倒计时中(session ENDED): 只传送点击红石的本人, 其他人留在房间继续倒计时/自行点红石
        if (exitSession.getState() == GameSession.State.ENDED) {
            plugin.getServer().dispatchCommand(p, "gomoku leave");
            e.setCancelled(true);
            return;
        }
        // 只认正式对局 (PLAYING); 等待匹配阶段右键不发确认提示
        if (exitSession.getState() != GameSession.State.PLAYING) return;
        e.setCancelled(true);                        // 手持红石不能放置方块
        if (!a.isRightClick()) return;

        long now = System.currentTimeMillis();
        Long first = exitPending.get(p.getUniqueId());
        if (first == null || now - first > 10_000L) {   // 第一次, 或已超过 10 秒 -> 重新计时
            exitPending.put(p.getUniqueId(), now);
            p.sendMessage(plugin.getMsg("exit-confirm"));
            return;
        }
        if (now - first < 5_000L) {                     // 5 秒内重复点击 -> 无效
            p.sendMessage(plugin.getMsg("exit-too-fast"));
            return;
        }
        exitPending.remove(p.getUniqueId());
        plugin.getServer().dispatchCommand(p, "gomoku leave");
    }
    // ── Edit-mode board scanner (wooden axe) ──

    /** True when a room admin holds the scanner axe outside a match. */
    private boolean isEditAxe(Player p) {
        if (!p.hasPermission("gomoku.admin")) return false;
        if (plugin.getGameSessionManager().isInGame(p)) return false;
        if (plugin.getRoomManager().getRoom(p.getWorld().getName()) == null) return false;
        ItemStack held = p.getInventory().getItemInMainHand();
        return held != null && held.getType() == Material.WOODEN_AXE;
    }

    /** Any wooden axe an admin holds gets the usage text automatically (creative menu included). */
    @EventHandler
    public void onAxeHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        // Only inside a room world - outside edit mode the axe must stay a plain axe
        if (plugin.getRoomManager().getRoom(p.getWorld().getName()) == null) return;
        ItemStack item = p.getInventory().getItem(e.getNewSlot());
        if (item == null || item.getType() != Material.WOODEN_AXE) return;
        plugin.stampEditAxe(item);
        if (isEditAxe(p)) p.sendActionBar(plugin.getRawMsg("axe-hint"));
    }

    /** While the scanner is held, breaking blocks is disabled. */
    @EventHandler
    public void onAxeBreak(BlockBreakEvent e) {
        if (isEditAxe(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onAxeUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isEditAxe(p)) return;
        Action a = e.getAction();
        if (a == Action.LEFT_CLICK_BLOCK) {
            e.setCancelled(true);               // select, never break
            Block b = e.getClickedBlock();
            if (b != null) scanBoard(p, b);
        } else if (a == Action.RIGHT_CLICK_BLOCK || a == Action.RIGHT_CLICK_AIR) {
            e.setCancelled(true);               // no block interaction while scanning
            plugin.getBoardPreviewManager().cancelPreview(p);
            p.sendMessage(plugin.getMsg("axe-cancel"));
        }
    }

    private void scanBoard(Player p, Block b) {
        Room room = plugin.getRoomManager().getRoom(p.getWorld().getName());
        if (room == null) { p.sendMessage(plugin.getMsg("axe-no-room")); return; }

        BoardScanner.Result r = BoardScanner.scan(b.getWorld(), b.getX(), b.getY(), b.getZ());
        if (!r.ok()) { p.sendMessage(plugin.getMsg(r.error)); return; }

        room.setDishParams(r.size, r.interval, r.lineBlock, r.fillBlock, "WOOL",
                r.centerX, r.centerZ, r.boardY);
        plugin.getRoomManager().saveRoom(room);

        p.sendMessage(plugin.getMsg("axe-scan-ok",
                "{size}", String.valueOf(r.size),
                "{interval}", String.valueOf(r.interval),
                "{line}", r.lineBlock,
                "{fill}", r.fillBlock,
                "{x}", String.valueOf(r.centerX),
                "{z}", String.valueOf(r.centerZ),
                "{y}", String.valueOf(r.boardY),
                "{room}", room.getName()));
    }

    // -- Redstone right-click to leave matchmaking --
    @EventHandler
    public void onRedstoneInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        // 只认"手里拿着的"那颗带标签的红石, 别的交互(比如右键下界之星开界面)完全不影响它
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isExitItem(hand)) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.getGameSessionManager().isInGame(p)) return;
        GameSession s = plugin.getGameSessionManager().getGame(p);
        if (s == null || s.getState() != GameSession.State.WAITING) return;
        // 等待匹配中: 手持红石右键 -> 取消匹配
        s.removePlayer(p);
        plugin.getGameSessionManager().unregisterPlayer(p.getUniqueId());
        removeExitItem(p);
        Location lobby = plugin.getLobbyManager().getLocation();
        if (lobby != null) p.teleport(lobby);
        p.sendMessage(plugin.getMsg("leave-success"));
        e.setCancelled(true);
    }

    // -- 对局中只允许左键瞄准落子, 右键放置方块一律取消 --
    @EventHandler
    public void onGameBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (plugin.getLobbyManager().isLobbyWorld(p.getWorld())) {
            if (plugin.lobbyProtectEnabled() && !plugin.canEditLobby(p)) e.setCancelled(true);
            return;
        }
        if (!plugin.getGameSessionManager().isInGame(p)) return;
        if (isExitItem(p.getInventory().getItemInMainHand())) return;   // 手持退出物品时不拦截
        e.setCancelled(true);
    }

    /** 对局中禁用右键方块/空气交互(箱子、按钮、门、物品使用), 只保留左键瞄准落子; 退出红石单独处理. */
    @EventHandler
    public void onGameInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.RIGHT_CLICK_AIR) return;
        Player p = e.getPlayer();
        if (plugin.getLobbyManager().isLobbyWorld(p.getWorld())) {
            if (plugin.lobbyProtectEnabled() && !plugin.canEditLobby(p)) e.setCancelled(true);
            return;
        }
        boolean inGame = plugin.getGameSessionManager().isInGame(p);
        if (!inGame) {
            Room room = plugin.getRoomManager().getRoom(p.getWorld().getName());
            if (room == null || p.hasPermission("gomoku.admin")) return;
        }
        if (isExitItem(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
    }

    // -- Prevent dropping redstone --
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (e.getItemDrop().getItemStack().getType() == Material.REDSTONE
                && plugin.getGameSessionManager().isInGame(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    // Prevent moving redstone out of inventory
    /** Lobby UI items (room menu star / shop chest) must never leave their slot. */
    private boolean isUiItem(ItemStack it) {
        return it != null && (plugin.getPieceManager().isMenuItem(it)
                || plugin.getRoomMenuManager().isMenuItem(it));
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        // Pickup, shift-click, drop-key, hotkey swap and offhand swap are all blocked
        if (isUiItem(e.getCurrentItem()) || isUiItem(e.getCursor())
                || isUiItem(p.getInventory().getItemInOffHand())) {
            e.setCancelled(true);
            return;
        }
        if (e.getClick() == ClickType.NUMBER_KEY) {
            int hotbar = e.getHotbarButton();
            if (hotbar >= 0 && isUiItem(p.getInventory().getItem(hotbar))) {
                e.setCancelled(true);
                return;
            }
        }
        if (!plugin.getGameSessionManager().isInGame(p)) return;
        ItemStack cur = e.getCurrentItem();
        if (cur != null && cur.getType() == Material.REDSTONE) e.setCancelled(true);
    }

    // -- Death / quit / kick --
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (plugin.getGameSessionManager().isInGame(p)) {
            GameSession s = plugin.getGameSessionManager().getGame(p);
            if (s != null) s.removePlayer(p);
            plugin.getGameSessionManager().unregisterPlayer(p.getUniqueId());
            p.getInventory().remove(Material.REDSTONE);
            p.sendMessage(plugin.getMsg("lobby-death"));
        }
        // NOTE: teleporting a dead player does not decide where they respawn - see onRespawn.
    }

    /**
     * Vanilla respawns a player at their bed, or at the overworld's shared spawn point - which is
     * not necessarily the lobby. A player who dies inside a room world (playing a match or building
     * one) or inside the lobby world is therefore respawned at the configured lobby location.
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Location lobby = plugin.getLobbyManager().getLocation();
        if (lobby == null) return;

        boolean inRoomWorld = plugin.getRoomManager().getRoom(p.getWorld().getName()) != null;
        boolean inLobbyWorld = plugin.getLobbyManager().isLobbyWorld(p.getWorld());
        boolean wasInGame = plugin.getGameSessionManager().isInGame(p);
        if (!inRoomWorld && !inLobbyWorld && !wasInGame) return;

        e.setRespawnLocation(lobby);

        // Leaving a match also resets the landing state, same as GameSession#returnPlayer.
        // Ordinary deaths in the lobby world keep the player's own gamemode.
        if (inRoomWorld || wasInGame) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                p.getInventory().remove(Material.REDSTONE);
                p.setGameMode(GameMode.ADVENTURE);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        e.setQuitMessage(null);   // 由插件按 quit-message 配置广播
        leave(p);
        String quitMsg = plugin.getMsg("quit-broadcast", "{player}", p.getName());
        Location lobby = plugin.getLobbyManager().getLocation();
        if (plugin.quitMessageLobby() && lobby != null) {
            for (Player other : lobby.getWorld().getPlayers()) {
                if (!other.getUniqueId().equals(p.getUniqueId())) other.sendMessage(quitMsg);
            }
        }
        if (plugin.quitMessageRoom()) {
            for (GameSession session : plugin.getGameSessionManager().getAllSessions()) {
                if (session.isActive() && session.getRoom().isEnabled()) session.broadcast(quitMsg);
            }
        }
    }
    @EventHandler
    public void onKick(PlayerKickEvent e) { leave(e.getPlayer()); }
    private void leave(Player p) {
        if (plugin.getGameSessionManager().isInGame(p)) {
            GameSession s = plugin.getGameSessionManager().getGame(p);
            if (s != null) s.removePlayer(p);
            plugin.getGameSessionManager().unregisterPlayer(p.getUniqueId());
            p.getInventory().remove(Material.REDSTONE);
        }
    }

    // ?? Protection ??
    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (plugin.getLobbyManager().isLobbyWorld(p.getWorld())) {
            if (plugin.lobbyProtectEnabled() && !plugin.canEditLobby(p)) e.setCancelled(true);
            return;
        }
        if (plugin.getGameSessionManager().isInGame(p)) { e.setCancelled(true); return; }
        Room room = plugin.getRoomManager().getRoom(p.getWorld().getName());
        if (room != null && !p.hasPermission("gomoku.admin")) e.setCancelled(true);
    }
    @EventHandler
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!isPluginWorld(p)) return;
        if (p.hasPermission("gomoku.admin")) return;
        if (plugin.blockPlayerAttacks()) e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isPluginWorld(p)) return;
        if (plugin.preventPlayerDamage()) { e.setCancelled(true); return; }
        if (plugin.preventDeath() && e.getFinalDamage() >= p.getHealth() + p.getAbsorptionAmount()) {
            e.setCancelled(true);
        }
    }

    /** 大厅世界和房间世界受保护; 普通世界不受插件影响. */
    private boolean isPluginWorld(Player p) {
        return plugin.getLobbyManager().isLobbyWorld(p.getWorld())
                || plugin.getRoomManager().getRoom(p.getWorld().getName()) != null;
    }

    @EventHandler
    public void onCelebrationExplode(EntityExplodeEvent e) {
        if (!plugin.getWinCelebrationManager().isCelebrationTnt(e.getEntity())) return;
        if (!plugin.getWinCelebrationManager().breakBlocks()) e.blockList().clear();
    }

    @EventHandler
    public void onCelebrationDamage(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && e.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        Entity direct = e.getDamageSource().getDirectEntity();
        if (direct != null && plugin.getWinCelebrationManager().isCelebrationTnt(direct)
                && !plugin.getWinCelebrationManager().damageEntities()) {
            e.setCancelled(true);
        }
    }

    // Cancel board preview on world change
    private void removeEditAxes(Player p) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && plugin.isEditAxe(it)) p.getInventory().setItem(i, null);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        plugin.getBoardPreviewManager().cancelPreview(p);
        // The scanner axe only exists inside a room world - drop it when leaving
        if (plugin.getRoomManager().getRoom(p.getWorld().getName()) == null) removeEditAxes(p);
    }
}
