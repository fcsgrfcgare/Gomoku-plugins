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
