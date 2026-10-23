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
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class GameSessionManager {
    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, GameSession> playerSession = new ConcurrentHashMap<>();
    private final GomokuPlugin plugin;
    private final Logger logger;

    public GameSessionManager(GomokuPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public GameSession createSession(Room room) {
        GameSession s = new GameSession(room, plugin);
        sessions.put(room.getName().toLowerCase(), s);
        return s;
    }

    public GameSession getSession(String name) { return sessions.get(name.toLowerCase()); }
    public GameSession getGame(Player p) { return playerSession.get(p.getUniqueId()); }
    public void registerPlayer(UUID uid, GameSession s) { playerSession.put(uid, s); }
    public void unregisterPlayer(UUID uid) { playerSession.remove(uid); }
    public boolean isInGame(Player p) { return playerSession.containsKey(p.getUniqueId()); }

    /**
     * Single definition of "this room is in a match": a session exists and is PLAYING.
     * Used both by /gomoku spectate and by the room-count placeholders so they can never drift.
     */
    public boolean isRoomPlaying(String roomName) {
        GameSession s = getSession(roomName);
        return s != null && s.getState() == GameSession.State.PLAYING;
    }

    /** Number of rooms currently in a match (not merely enabled/configured). */
    public long countPlayingRooms() {
        return sessions.values().stream()
                .filter(s -> s.getState() == GameSession.State.PLAYING)
                .count();
    }

    public void endSession(String name) {
        GameSession s = sessions.remove(name.toLowerCase());
        if (s != null) {
            for (UUID uid : s.getAllPlayers()) playerSession.remove(uid);
            s.cleanup(false);
        }
    }

    public Collection<GameSession> getAllSessions() { return Collections.unmodifiableCollection(sessions.values()); }
}
