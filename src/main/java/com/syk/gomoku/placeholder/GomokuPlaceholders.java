package com.syk.gomoku.placeholder;

import com.syk.gomoku.GomokuPlugin;
import com.syk.gomoku.model.PlayerStats;
import com.syk.gomoku.model.Room;
import com.syk.gomoku.session.GameSession;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlaceholderAPI-independent resolver for every {@code %gomoku_xxx%} placeholder.
 *
 * <p>Used by {@link GomokuExpansion} when PlaceholderAPI is installed, and by the
 * scoreboard renderer, which has to work even without PlaceholderAPI.</p>
 */
public final class GomokuPlaceholders {

    /** Matches {@code %gomoku_<identifier>%}. */
    private static final Pattern TOKEN =
            Pattern.compile("%gomoku_([a-z0-9_]+)%", Pattern.CASE_INSENSITIVE);

    private static final String NONE = "—";

    private GomokuPlaceholders() {}

    /**
     * Resolves a single identifier (the part after {@code gomoku_}).
     *
     * @return the replacement text, or {@code null} when the identifier is unknown
     */
    public static String resolve(GomokuPlugin plugin, OfflinePlayer offlinePlayer, String identifier) {
        // ── Server-wide placeholders (no player needed) ──
        // "in a match" counts, not "configured/enabled" rooms.
        if (identifier.startsWith("playing_room_count") || identifier.startsWith("enabled_room_count")) {
            return String.valueOf(plugin.getGameSessionManager().countPlayingRooms());
        }
        if (identifier.startsWith("room_count")) {
            return String.valueOf(plugin.getRoomManager().getAllRooms().size());
        }
        if (identifier.startsWith("player_count")) {
            return String.valueOf(plugin.getGameSessionManager().getAllSessions().stream()
                    .mapToInt(s -> s.getAllPlayers().size()).sum());
        }

        // ── Player placeholders ──
        if (offlinePlayer == null || !offlinePlayer.isOnline()) return "";
        Player player = offlinePlayer.getPlayer();
        if (player == null) return "";

        GameSession session = plugin.getGameSessionManager().getGame(player);
        PlayerStats stats = plugin.getStatsManager().get(player.getUniqueId());

        // Spendable score (piece shop currency) - available even when not in game
        if (identifier.equals("points")) {
            return String.valueOf(stats != null ? stats.getAvailableScore() : 0);
        }

        // Stats (available even when not in game)
        if (stats != null) {
            switch (identifier) {
                case "wins":         return String.valueOf(stats.getWins());
                case "losses":       return String.valueOf(stats.getLosses());
                case "draws":        return String.valueOf(stats.getDraws());
                case "total_games":  return String.valueOf(stats.getTotalGames());
                case "winrate":      return String.format("%.1f%%", stats.getWinRate());
                case "score":        return String.valueOf(stats.getScore());
                case "level":        return String.valueOf(stats.getLevel());
            }
        } else {
            switch (identifier) {
                case "wins": case "losses": case "draws": case "total_games": return "0";
                case "winrate": return "0.0%";
                case "score":   return "0";
                case "level":   return "1";
            }
        }

        // Game context (only when in game)
        if (session == null) {
            switch (identifier) {
                case "room": case "state": case "opponent":
                case "color": case "turn": case "turn_piece":
                case "black_player": case "white_player":
                case "timer": case "countdown":
                case "board_size": case "move_count":
                case "black_score": case "white_score": return NONE;
                case "spectator": return "false";
                case "spectator_count": return "0";
                default: return null;
            }
        }

        Room room = session.getRoom();
        switch (identifier) {
            case "room":         return room != null ? room.getName() : NONE;
            case "state":        return session.getState().name();
            case "opponent":     return getOpponent(player, session);
            case "color":        return getColor(plugin, player, session);
            case "black_player": return nameOf(session.getBlackPlayer());
            case "white_player": return nameOf(session.getWhitePlayer());
            case "turn":         return getTurn(session);
            case "turn_piece":   return getTurnPiece(session);
            case "timer":        return getRemaining(session);
            case "countdown":    return getCountdown(plugin, session);
            case "board_size":   return room != null ? String.valueOf(room.getBoardSize()) : NONE;
            case "move_count":   return session.isActive() ? String.valueOf(session.getGame().getMoveCount()) : NONE;
            case "black_score":  return scoreOf(plugin, session.getBlackPlayer());
            case "white_score":  return scoreOf(plugin, session.getWhitePlayer());
            case "spectator":    return String.valueOf(session.isSpectator(player));
            case "spectator_count": return String.valueOf(session.getSpectatorCount());
        }

        return null; // unknown placeholder
    }

    /** Replaces every {@code %gomoku_xxx%} token in {@code text}; unknown tokens are left alone. */
    public static String replaceAll(GomokuPlugin plugin, Player player, String text) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = resolve(plugin, player, m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group(0) : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ── helpers ──

    private static String nameOf(Player p) {
        return p != null ? p.getName() : NONE;
    }

    /** Lifetime score of the black/white side; "0" when that side has no stats yet. */
    private static String scoreOf(GomokuPlugin plugin, Player p) {
        if (p == null) return NONE;
        PlayerStats stats = plugin.getStatsManager().get(p.getUniqueId());
        return stats != null ? String.valueOf(stats.getScore()) : "0";
    }

    /** A spectator has no opponent - they watch both sides. */
    private static String getOpponent(Player player, GameSession s) {
        if (s.isSpectator(player)) return NONE;
        if (s.getBlackPlayer() != null && !s.getBlackPlayer().getUniqueId().equals(player.getUniqueId()))
            return s.getBlackPlayer().getName();
        if (s.getWhitePlayer() != null && !s.getWhitePlayer().getUniqueId().equals(player.getUniqueId()))
            return s.getWhitePlayer().getName();
        return NONE;
    }

    private static String getColor(GomokuPlugin plugin, Player player, GameSession s) {
        if (s.getBlackPlayer() != null && s.getBlackPlayer().getUniqueId().equals(player.getUniqueId()))
            return plugin.getRawMsg("color-black");
        if (s.getWhitePlayer() != null && s.getWhitePlayer().getUniqueId().equals(player.getUniqueId()))
            return plugin.getRawMsg("color-white");
        if (s.isSpectator(player)) return plugin.getRawMsg("color-spectator");
        return NONE;
    }

    private static String getTurn(GameSession s) {
        if (s.getState() != GameSession.State.PLAYING) return NONE;
        Player cur = s.getCurrentTurnPlayer();
        return cur != null ? cur.getName() : NONE;
    }

    /** Piece name of the side on move, e.g. the room's configured "黑子". */
    private static String getTurnPiece(GameSession s) {
        if (s.getState() != GameSession.State.PLAYING) return NONE;
        String piece = s.getCurrentPieceName();
        return piece != null && !piece.isEmpty() ? piece : NONE;
    }

    /** Seconds left in the current turn; "—" outside a running match. */
    private static String getRemaining(GameSession s) {
        if (s.getState() != GameSession.State.PLAYING) return NONE;
        return String.valueOf(Math.max(0, s.getRemainingSeconds()));
    }

    /** One-line summary: which piece, which player, how many seconds are left. */
    private static String getCountdown(GomokuPlugin plugin, GameSession s) {
        if (s.getState() != GameSession.State.PLAYING) return NONE;
        Player cur = s.getCurrentTurnPlayer();
        return plugin.getRawMsg("papi-countdown",
                "{piece}", getTurnPiece(s),
                "{player}", cur != null ? cur.getName() : NONE,
                "{seconds}", getRemaining(s));
    }
}
