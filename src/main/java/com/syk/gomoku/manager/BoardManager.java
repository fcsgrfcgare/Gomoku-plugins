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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.logging.Logger;

/**
 * Stone placement only - grid is computed from dish params, no armor stands.
 */
public class BoardManager {

    private final Logger logger;

    public BoardManager(GomokuPlugin plugin) {
        this.logger = plugin.getLogger();
    }

    public void placeStone(Room room, int row, int col, boolean isBlack) {
        World w = room.getWorld(); if (w == null) return;
        Location loc = new Location(w, room.centerX(row, col), 64, room.centerZ(row, col));
        String chess = room.getChessType() != null ? room.getChessType() : "WOOL";
        loc.getBlock().setType(WorldManager.getChessPiece(chess, isBlack));
    }

    public void clearStones(Room room) {
        World w = room.getWorld(); if (w == null) return;
        for (int r = 0; r < room.getBoardSize(); r++)
            for (int c = 0; c < room.getBoardSize(); c++)
                new Location(w, room.centerX(r, c), 64, room.centerZ(r, c)).getBlock().setType(Material.AIR);
    }
}
