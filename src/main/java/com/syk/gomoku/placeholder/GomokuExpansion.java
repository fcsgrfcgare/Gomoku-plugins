package com.syk.gomoku.placeholder;

import com.syk.gomoku.GomokuPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion for Gomoku (五子棋).
 *
 * <p>All resolution lives in {@link GomokuPlaceholders}, so the scoreboard renderer can
 * produce exactly the same values even when PlaceholderAPI is not installed.</p>
 *
 * <p>Player placeholders (require a player):</p>
 * <pre>
 *   %gomoku_room%          — 当前所在房间名 (— if not in game)
 *   %gomoku_state%         — WAITING / PLAYING / ENDED / —
 *   %gomoku_opponent%      — 对手名 (观战者/无对手时为 —)
 *   %gomoku_color%         — 黑子 / 白子 / 观战 / —
 *   %gomoku_black_player%  — 本局黑方玩家名
 *   %gomoku_white_player%  — 本局白方玩家名
 *   %gomoku_black_score%   — 本局黑方玩家积分
 *   %gomoku_white_score%   — 本局白方玩家积分
 *   %gomoku_spectator%     — 是否观战 (true/false)
 *   %gomoku_spectator_count% — 当前观战人数
 *   %gomoku_turn%          — 当前轮到哪个玩家
 *   %gomoku_turn_piece%    — 当前回合的棋子名 (房间里配置的黑子/白子名)
 *   %gomoku_timer%         — 当前回合剩余秒数
 *   %gomoku_countdown%     — 一行摘要: 棋子名 + 玩家名 + 剩余秒数
 *   %gomoku_board_size%    — 棋盘大小 (15-64)
 *   %gomoku_move_count%    — 已落子数
 *   %gomoku_wins%          — 玩家胜场
 *   %gomoku_losses%        — 玩家败场
 *   %gomoku_draws%         — 玩家平局
 *   %gomoku_total_games%   — 玩家总局数
 *   %gomoku_winrate%       — 玩家胜率 (如 75.0%)
 *   %gomoku_score%         — 玩家积分
 *   %gomoku_level%         — 玩家等级
 * </pre>
 *
 * <p>Server placeholders (no player needed):</p>
 * <pre>
 *   %gomoku_room_count%          — 房间总数
 *   %gomoku_playing_room_count%  — 当前正在对战的房间数 (enabled_room_count 为同义旧名)
 *   %gomoku_player_count%        — 当前在游戏中的玩家数
 * </pre>
 */
public class GomokuExpansion extends PlaceholderExpansion {

    private final GomokuPlugin plugin;

    public GomokuExpansion(GomokuPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "gomoku";
    }

    @Override
    public @NotNull String getAuthor() {
        return "syk";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String identifier) {
        return GomokuPlaceholders.resolve(plugin, offlinePlayer, identifier);
    }
}
