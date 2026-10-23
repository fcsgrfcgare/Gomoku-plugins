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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.syk.gomoku.GomokuPlugin;
import com.syk.gomoku.model.Room;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class RoomManager {
    private final GomokuPlugin plugin;
    private final File roomsDir;
    private final Gson gson;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Logger logger;

    public RoomManager(GomokuPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.roomsDir = new File(plugin.getDataFolder(), "rooms");
        this.gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();
        loadAll();
    }

    private void loadAll() {
        if (!roomsDir.exists()) { roomsDir.mkdirs(); return; }
        File[] files = roomsDir.listFiles((f, n) -> n.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                Room room = gson.fromJson(r, Room.class);
                if (room != null) {
                    if (applyDefaultPieceNames(room)) saveRoom(room);
                    rooms.put(room.getName().toLowerCase(), room);
                }
            } catch (Exception e) { logger.warning("Room load failed: " + f.getName()); }
        }
        logger.info("Loaded " + rooms.size() + " gomoku room(s)");
    }

    public void saveRoom(Room room) {
        try {
            if (!roomsDir.exists()) roomsDir.mkdirs();
            File f = new File(roomsDir, room.getName().toLowerCase() + ".json");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                gson.toJson(room, w);
            }
        } catch (IOException e) { logger.warning("Room save failed: " + e.getMessage()); }
    }

    public Room createRoom(String name) {
        Room room = new Room(name);
        applyDefaultPieceNames(room);
        rooms.put(name.toLowerCase(), room);
        saveRoom(room);
        return room;
    }

    /**
     * Gives an unpinned room the current language's default piece names.
     * Custom (legacy) names are left alone.
     *
     * @return true when the room changed
     */
    public boolean applyDefaultPieceNames(Room room) {
        if (room == null || room.isPiecesPinned()) return false;
        if (!Room.isDefaultPieceName(room.getBlackPieceName())
                || !Room.isDefaultPieceName(room.getWhitePieceName())) return false;
        String black = plugin.getRawMsg("piece-default-black");
        String white = plugin.getRawMsg("piece-default-white");
        boolean changed = !black.equals(room.getBlackPieceName()) || !white.equals(room.getWhitePieceName());
        room.setBlackPieceName(black);
        room.setWhitePieceName(white);
        return changed;
    }

    public Room getRoom(String name) { return rooms.get(name.toLowerCase()); }
    public Collection<Room> getAllRooms() { return Collections.unmodifiableCollection(rooms.values()); }
    public List<String> getRoomNames() { return rooms.keySet().stream().sorted().toList(); }
    public boolean roomExists(String name) { return rooms.containsKey(name.toLowerCase()); }

    public void clearRoom(String name) {
        rooms.remove(name.toLowerCase());
        new File(roomsDir, name.toLowerCase() + ".json").delete();
    }

    /** Reload all rooms from disk. */
    public String reload() {
        rooms.clear();
        loadAll();
        return rooms.size() + " room(s) reloaded.";
    }
}
