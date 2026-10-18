package com.syk.gomoku.model;

import com.google.gson.annotations.Expose;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

public class Room {

    @Expose private String name;
    @Expose private String worldName;
    @Expose private boolean enabled;
    @Expose private int boardSize, interval;
    @Expose private double cellSpan, halfSpan;
    @Expose private boolean boardSet, spawnSet;
    @Expose private String lineBlock, fillBlock, chessType;
    @Expose private String blackPieceName = "黑子"; // 黑子
    @Expose private String whitePieceName = "白子"; // 白子
    @Expose private String blackPieceMat = "BLACK_WOOL";
    @Expose private String whitePieceMat = "WHITE_WOOL";
    /** True once an admin pinned the pieces via /gomoku admin check -> overrides the shop. */
    @Expose private boolean piecesSet;
    /** Relative whole-world backup folder under plugins/Gomoku, e.g. backups/1. */
    @Expose private String backupFolder;
    @Expose private int boardCenterX, boardCenterZ, boardY = 64; // board world offset + surface Y

    // Player spawn
    @Expose private double spawnX, spawnY, spawnZ;
    @Expose private float spawnYaw, spawnPitch;

    // Pre-computed cell centers [row][col] = [x, z]
    private transient double[][] centersX, centersZ;
    private transient boolean centersComputed;

    public Room(String name) {
        this.name = name; this.worldName = name;
        this.enabled = false; this.boardSize = 15;
    }

    public String getName() { return name; }
    public String getWorldName() { return worldName; }
    public boolean isEnabled() { return enabled; }
    public int getBoardSize() { return boardSize; }
    public boolean isBoardSet() { return boardSet; }
    public boolean isSpawnSet() { return spawnSet; }
    public int getInterval() { return interval; }
    public double getCellSpan() { return cellSpan; }
    public double getHalfSpan() { return halfSpan; }
    public String getLineBlock() { return lineBlock; }
    public String getFillBlock() { return fillBlock; }
    public String getChessType() { return chessType; }
    public String getBlackPieceName() { return blackPieceName != null ? blackPieceName : "黑子"; }
    public String getWhitePieceName() { return whitePieceName != null ? whitePieceName : "白子"; }
    public void setBlackPieceName(String n) { this.blackPieceName = n; }
    public void setWhitePieceName(String n) { this.whitePieceName = n; }
    public Material getBlackPieceMaterial() { return Material.getMaterial(blackPieceMat != null ? blackPieceMat : "BLACK_WOOL"); }
    public Material getWhitePieceMaterial() { return Material.getMaterial(whitePieceMat != null ? whitePieceMat : "WHITE_WOOL"); }
    public void setBlackPieceMaterial(String m) { this.blackPieceMat = m; }
    public void setWhitePieceMaterial(String m) { this.whitePieceMat = m; }
    public boolean isChessSet() { return blackPieceName != null && whitePieceName != null; }

    /**
     * True when the room pins its own piece look, which overrides the shop. Rooms configured
     * before the flag existed are detected by their non-default materials/names.
     */
    public boolean isPiecesSet() {
        return piecesSet
                || !"BLACK_WOOL".equals(blackPieceMat) || !"WHITE_WOOL".equals(whitePieceMat)
                || !isDefaultPieceName(blackPieceName) || !isDefaultPieceName(whitePieceName);
    }

    /** True only when an admin explicitly pinned the pieces via /gomoku admin check. */
    public boolean isPiecesPinned() { return piecesSet; }

    /** Known language defaults; used to refresh unpinned rooms when the locale changes. */
    public static boolean isDefaultPieceName(String name) {
        String n = plainPieceName(name);
        return n.isEmpty()
                || n.equals("黑子") || n.equals("白子")
                || n.equals("Black Stone") || n.equals("White Stone")
                || n.equals("Black") || n.equals("White");
    }

    /** Removes &/§ colour codes so saved names can be compared with the language defaults. */
    public static String plainPieceName(String name) {
        if (name == null) return "";
        return name.replaceAll("(?i)[§&][0-9A-FK-OR]", "").trim();
    }

    public void setPiecesSet(boolean v) { this.piecesSet = v; }
    public World getWorld() { return Bukkit.getWorld(worldName); }
    public String getBackupFolder() { return backupFolder; }
    public void setBackupFolder(String f) { this.backupFolder = f; }

    public void setName(String n) { name = n; }
    public void setWorldName(String n) { worldName = n; }
    public void setEnabled(boolean v) { enabled = v; }
    public void setBoardSet(boolean v) { boardSet = v; }
    public void setSpawnSet(boolean v) { spawnSet = v; }

    public void setDishParams(int size, int interval, String lineBlock, String fillBlock, String chessType) {
        setDishParams(size, interval, lineBlock, fillBlock, chessType, 0, 0, 64);
    }
    public void setDishParams(int size, int interval, String lineBlock, String fillBlock, String chessType,
                               int centerX, int centerZ, int boardY) {
        this.boardSize = size; this.interval = interval;
        this.cellSpan = interval + 1; this.halfSpan = size * cellSpan / 2.0;
        this.lineBlock = lineBlock; this.fillBlock = fillBlock; this.chessType = chessType;
        this.boardCenterX = centerX; this.boardCenterZ = centerZ; this.boardY = boardY;
        this.boardSet = true;
        computeCenters();
    }

    /** 外围方块 (admin fill 第三个参数): 它和它外面都不能落子。 */
    @Expose private String peripheryBlock;

    public String getPeripheryBlock() { return peripheryBlock; }
    public void setPeripheryBlock(String m) { this.peripheryBlock = m; }

    public int getBoardY() { return boardY; }
    public int getBoardCenterX() { return boardCenterX; }
    public int getBoardCenterZ() { return boardCenterZ; }

    private void computeCenters() {
        centersX = new double[boardSize][boardSize];
        centersZ = new double[boardSize][boardSize];
        double off = 1.5 + (interval - 1) / 2.0;
        for (int r = 0; r < boardSize; r++)
            for (int c = 0; c < boardSize; c++) {
                centersX[r][c] = boardCenterX + c * cellSpan - halfSpan + off;
                centersZ[r][c] = boardCenterZ + r * cellSpan - halfSpan + off;
            }
        centersComputed = true;
    }

    private void ensureCenters() { if (centersX == null && boardSet) computeCenters(); }
    public double centerX(int row, int col) { ensureCenters(); return centersX[row][col]; }
    public double centerZ(int row, int col) { ensureCenters(); return centersZ[row][col]; }

    public void setPlayerSpawn(Location loc) {
        this.spawnX = loc.getX(); this.spawnY = loc.getY(); this.spawnZ = loc.getZ();
        this.spawnYaw = loc.getYaw(); this.spawnPitch = loc.getPitch();
        this.spawnSet = true;
    }

    public Location getPlayerSpawn() {
        World w = getWorld();
        return (w != null && spawnSet) ? new Location(w, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch) : null;
    }

    /** Stone placement at cell center (pre-computed cache, O(1)). */
    public Location boardToWorld(int row, int col) {
        if (!boardSet) return null;
        World w = getWorld(); if (w == null) return null;
        return new Location(w, centerX(row, col), boardY, centerZ(row, col));
    }

    /** Click -> board cell (fast formula, O(1)). Accounts for board center offset. */
    public int[] worldToBoard(Location loc) {
        if (!boardSet) return null;
        if (loc.getWorld() == null || !loc.getWorld().getName().equals(worldName)) return null;

        // 只认棋盘附近的高度。用容差而不是死等 boardY/boardY-1:
        // fill 记录的高度和实际棋盘层差 1 格时, 死等会让所有落子被拒 (站在棋盘上却下不了)
        int by = loc.getBlockY();
        if (Math.abs(by - boardY) > 2) return null;

        // 最外围 X/Z 边界: 棋盘 = halfSpan .. halfSpan + size*cellSpan。
        // 现在这组参数在开局时由 calibrateBoard() 从世界扫描得到(与世界一致),
        // 所以这道判定是安全的, 且能挡住"点在棋盘外围一格".
        int span = boardSize * (int) Math.round(cellSpan);
        int minBX = (int) Math.floor(boardCenterX - halfSpan);
        int maxBX = minBX + span;
        int minBZ = (int) Math.floor(boardCenterZ - halfSpan);
        int maxBZ = minBZ + span;
        if (loc.getBlockX() < minBX || loc.getBlockX() > maxBX
                || loc.getBlockZ() < minBZ || loc.getBlockZ() > maxBZ) return null;

        // 关键: 不信配置里的坐标, 以世界里的真实方块为准。
        // 算出的格子中心下面一层必须是棋盘方块(井字线或填充), 否则说明这块格子根本不在棋盘上
        // -> 直接拒绝, 杜绝"下到棋盘外面"。
        double cellOff = 1.5 + (interval - 1) / 2.0;
        int probeCol = (int) Math.round((loc.getX() - boardCenterX + halfSpan - cellOff) / cellSpan);
        int probeRow = (int) Math.round((loc.getZ() - boardCenterZ + halfSpan - cellOff) / cellSpan);
        if (probeCol >= 0 && probeRow >= 0 && probeCol < boardSize && probeRow < boardSize) {
            int fx = (int) Math.floor(boardCenterX + probeCol * cellSpan - halfSpan + cellOff);
            int fz = (int) Math.floor(boardCenterZ + probeRow * cellSpan - halfSpan + cellOff);
            org.bukkit.Material under = loc.getWorld().getBlockAt(fx, boardY - 1, fz).getType();
            boolean isBoard = (lineBlock != null && under.name().equals(lineBlock))
                    || (fillBlock != null && under.name().equals(fillBlock));
            if (!isBoard) return null;
            // 外围方块就是边界: 落在它上面或它外面的方块都不许落子
            String periph = peripheryBlock;
            if (periph != null && !periph.isEmpty()) {
                if (under.name().equals(periph)) return null;
                org.bukkit.Material at = loc.getBlock().getType();
                if (at.name().equals(periph)) return null;
            }
        } else {
            return null;
        }

        double off = 1.5 + (interval - 1) / 2.0;
        int col = (int) Math.round((loc.getX() - boardCenterX + halfSpan - off) / cellSpan);
        int row = (int) Math.round((loc.getZ() - boardCenterZ + halfSpan - off) / cellSpan);
        if (col < 0 || col >= boardSize || row < 0 || row >= boardSize) return null;
        // Verify distance is within half cell
        double dx = loc.getX() - centerX(row, col);
        double dz = loc.getZ() - centerZ(row, col);
        double maxD = cellSpan / 2.0;
        return (Math.abs(dx) <= maxD && Math.abs(dz) <= maxD) ? new int[]{row, col} : null;
    }
}
