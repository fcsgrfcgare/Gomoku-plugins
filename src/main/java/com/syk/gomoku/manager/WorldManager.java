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
import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

public class WorldManager {

    private final Logger logger;
    private final Plugin plugin;

    public WorldManager(GomokuPlugin plugin) {
        this.logger = plugin.getLogger();
        this.plugin = plugin;
    }

    public World createVoidWorld(String worldName) {
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) return existing;
        // NORMAL environment + VoidGenerator that disables all generation phases.
        // Paper 1.20.4 requires shouldGenerate* = false to avoid chunk-pipeline hangs.
        World world = new org.bukkit.WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .generator(new VoidGenerator())
                .createWorld();
        if (world != null) {
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            world.setGameRule(GameRule.DO_FIRE_TICK, false);
            world.getWorldBorder().setCenter(0, 0);
            world.getWorldBorder().setSize(1000);
            world.setTime(6000);
            world.setDifficulty(Difficulty.PEACEFUL);
        }
        return world;
    }

    /** Build at world origin (0,0) -- synchronous. */
    public void generateBoard(World world, int size, int interval, String lineBlock, String fillBlock) {
        generateBoardSync(world, size, interval, lineBlock, fillBlock, 0, 0, 64);
    }

    /** Build synchronously at center (for admin save/load). */
    public void generateBoardSync(World world, int size, int interval, String lineBlock, String fillBlock,
                                    int centerX, int centerZ, int boardY) {
        Material lineMat = parse(lineBlock);
        Material fillMat = parse(fillBlock);
        int cellSpan = interval + 1;
        int y = boardY;
        int halfSpan = size * cellSpan / 2;
        int total = size * cellSpan;

        for (int ix = 0; ix <= total; ix++)
            for (int iz = 0; iz <= total; iz++)
                if (ix % cellSpan != 0 || iz % cellSpan != 0)
                    world.getBlockAt(centerX + ix - halfSpan, y - 1, centerZ + iz - halfSpan).setType(fillMat);

        for (int i = 0; i <= size; i++) {
            for (int j = 0; j <= total; j++) {
                int p = i * cellSpan - halfSpan;
                world.getBlockAt(centerX + j - halfSpan, y - 1, centerZ + p).setType(lineMat);
                world.getBlockAt(centerX + p, y - 1, centerZ + j - halfSpan).setType(lineMat);
            }
        }

        double bio = halfSpan + 1;
        for (double b = -bio; b <= bio; b++) {
            world.getBlockAt(centerX + (int)b, y - 1, centerZ + (int)(-bio)).setType(Material.DARK_OAK_PLANKS);
            world.getBlockAt(centerX + (int)b, y - 1, centerZ + (int)bio).setType(Material.DARK_OAK_PLANKS);
            world.getBlockAt(centerX + (int)(-bio), y - 1, centerZ + (int)b).setType(Material.DARK_OAK_PLANKS);
            world.getBlockAt(centerX + (int)bio, y - 1, centerZ + (int)b).setType(Material.DARK_OAK_PLANKS);
        }

        world.setSpawnLocation(new Location(world, centerX, y + 1, centerZ + halfSpan + 4));
        logger.info("Board: " + size + "x" + size + " int=" + interval + " line=" + lineBlock + " fill=" + fillBlock
                + " at " + centerX + "," + centerZ);
    }

    // ── board build history (temporary, per world) ──

    /**
     * One cached board build. {@code no} is its creation order in that world and stays stable
     * even after earlier boards are deleted, so "/gomoku admin delete 2" keeps meaning
     * "the second board that was created here".
     */
    public static final class BoardRecord {
        public final int no;
        public final String worldName;
        public final int centerX, centerZ, boardY, size, interval;
        public final String lineBlock, fillBlock, peripheryBlock;
        final List<Integer> xs, zs;

        BoardRecord(int no, String worldName, int centerX, int centerZ, int boardY,
                    int size, int interval, String lineBlock, String fillBlock, String peripheryBlock,
                    List<Integer> xs, List<Integer> zs) {
            this.no = no; this.worldName = worldName;
            this.centerX = centerX; this.centerZ = centerZ; this.boardY = boardY;
            this.size = size; this.interval = interval;
            this.lineBlock = lineBlock; this.fillBlock = fillBlock; this.peripheryBlock = peripheryBlock;
            this.xs = xs; this.zs = zs;
        }

        public int blockCount() { return xs.size(); }
    }

    private final java.util.Map<String, List<BoardRecord>> boardHistory = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> boardCounter = new java.util.HashMap<>();

    /** Cached boards of this world, oldest first. */
    public List<BoardRecord> boardHistory(World world) {
        if (world == null) return List.of();
        List<BoardRecord> list = boardHistory.get(world.getName());
        return list != null ? list : List.of();
    }

    /** Deletes board #{@code no} of this world and clears its blocks. Null when not found. */
    public BoardRecord deleteBoard(World world, int no) {
        if (world == null) return null;
        List<BoardRecord> list = boardHistory.get(world.getName());
        if (list == null) return null;
        BoardRecord found = null;
        for (BoardRecord r : list) if (r.no == no) { found = r; break; }
        if (found == null) return null;
        list.remove(found);
        clearBoardBlocks(world, found);
        logger.info("Deleted board #" + no + " at " + found.centerX + "," + found.centerZ
                + " (" + found.blockCount() + " blocks)");
        return found;
    }

    /** Deletes the most recently created board of this world. */
    public BoardRecord deleteLastBoard(World world) {
        List<BoardRecord> list = boardHistory(world);
        if (list.isEmpty()) return null;
        return deleteBoard(world, list.get(list.size() - 1).no);
    }

    private void clearBoardBlocks(World world, BoardRecord r) {
        for (int i = 0; i < r.xs.size(); i++) {
            world.getBlockAt(r.xs.get(i), r.boardY - 1, r.zs.get(i)).setType(Material.AIR, false);
        }
    }

    /**
     * Drops cached records. With {@code keepLast} only the newest board stays, which is what
     * saving a room does: the final board is the room's, the earlier attempts are forgotten.
     *
     * @return how many records were dropped
     */
    public int clearBoardHistory(World world, boolean keepLast) {
        if (world == null) return 0;
        List<BoardRecord> list = boardHistory.get(world.getName());
        if (list == null || list.isEmpty()) return 0;
        if (keepLast && list.size() <= 1) return 0;
        int removed = keepLast ? list.size() - 1 : list.size();
        if (keepLast) {
            BoardRecord last = list.get(list.size() - 1);
            for (BoardRecord r : list) {
                if (r != last) clearBoardBlocks(world, r);
            }
            list.clear();
            list.add(last);
        } else {
            for (BoardRecord r : list) clearBoardBlocks(world, r);
            list.clear();
        }
        return removed;
    }

    /** Build asynchronously in batches (safe for Paper 1.20.4, won't block main thread). */
    public void generateBoardAsync(World world, int size, int interval, String lineBlock, String fillBlock,
                                    int centerX, int centerZ, int boardY, String peripheryBlock, Runnable onDone) {
        Material lineMat = parse(lineBlock);
        Material fillMat = parse(fillBlock);
        Material periMat = parse(peripheryBlock);
        int cellSpan = interval + 1;
        int y = boardY;
        int halfSpan = size * cellSpan / 2;
        int total = size * cellSpan;
        double bio = halfSpan + 1;

        // Flatten all work into parallel lists: xs, zs, mats
        List<Integer> xs = new ArrayList<>();
        List<Integer> zs = new ArrayList<>();
        List<Material> mats = new ArrayList<>();

        // Fill blocks
        for (int ix = 0; ix <= total; ix++)
            for (int iz = 0; iz <= total; iz++)
                if (ix % cellSpan != 0 || iz % cellSpan != 0) {
                    xs.add(centerX + ix - halfSpan); zs.add(centerZ + iz - halfSpan); mats.add(fillMat);
                }

        // Line blocks
        for (int i = 0; i <= size; i++) {
            for (int j = 0; j <= total; j++) {
                int p = i * cellSpan - halfSpan;
                xs.add(centerX + j - halfSpan); zs.add(centerZ + p); mats.add(lineMat);
                xs.add(centerX + p); zs.add(centerZ + j - halfSpan); mats.add(lineMat);
            }
        }

        // Border
        for (double b = -bio; b <= bio; b++) {
            xs.add(centerX + (int)b); zs.add(centerZ + (int)(-bio)); mats.add(periMat);
            xs.add(centerX + (int)b); zs.add(centerZ + (int)bio);  mats.add(periMat);
            xs.add(centerX + (int)(-bio)); zs.add(centerZ + (int)b); mats.add(periMat);
            xs.add(centerX + (int)bio);  zs.add(centerZ + (int)b);  mats.add(periMat);
        }

        // Cache this build so an admin can still remove it by index (temporary, cleared on save)
        int no = boardCounter.merge(world.getName(), 1, Integer::sum);
        boardHistory.computeIfAbsent(world.getName(), k -> new ArrayList<>())
                .add(new BoardRecord(no, world.getName(), centerX, centerZ, y, size, interval,
                        lineBlock, fillBlock, peripheryBlock, xs, zs));

        int BATCH = 50;
        int[] idx = {0};

        new BukkitRunnable() {
            @Override
            public void run() {
                int end = Math.min(idx[0] + BATCH, xs.size());
                for (int i = idx[0]; i < end; i++) {
                    world.getBlockAt(xs.get(i), y - 1, zs.get(i)).setType(mats.get(i), false);
                }
                idx[0] = end;
                if (idx[0] >= xs.size()) {
                    world.setSpawnLocation(new Location(world, centerX, y + 1, centerZ + halfSpan + 4));
                    logger.info("Board async: " + size + "x" + size + " int=" + interval
                            + " line=" + lineBlock + " fill=" + fillBlock + " at " + centerX + "," + centerZ);
                    if (onDone != null) onDone.run();
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public static Material getChessPiece(String chessType, boolean isBlack) {
        return isBlack
            ? parse(chessType.equals("SKULL") ? "BLACK_WOOL" : "BLACK_" + chessType)
            : parse(chessType.equals("SKULL") ? "WHITE_WOOL" : "WHITE_" + chessType);
    }

    /** Blocks that cannot be used for board building (physics, redstone, etc). */
    private static final Set<Material> BLOCKED = Set.of(
        Material.TNT, Material.REDSTONE, Material.REDSTONE_BLOCK, Material.REDSTONE_TORCH,
        Material.REDSTONE_WALL_TORCH, Material.SAND, Material.RED_SAND, Material.GRAVEL,
        Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
        Material.SCAFFOLDING, Material.POWDER_SNOW,
        Material.REPEATER, Material.COMPARATOR, Material.OBSERVER,
        Material.PISTON, Material.STICKY_PISTON, Material.DISPENSER, Material.DROPPER,
        Material.HOPPER, Material.DAYLIGHT_DETECTOR, Material.TRIPWIRE,
        Material.TRIPWIRE_HOOK, Material.LEVER, Material.STONE_BUTTON,
        Material.OAK_BUTTON, Material.SPRUCE_BUTTON, Material.BIRCH_BUTTON,
        Material.JUNGLE_BUTTON, Material.ACACIA_BUTTON, Material.DARK_OAK_BUTTON,
        Material.CRIMSON_BUTTON, Material.WARPED_BUTTON, Material.MANGROVE_BUTTON,
        Material.BAMBOO_BUTTON, Material.CHERRY_BUTTON, Material.STONE_PRESSURE_PLATE,
        Material.OAK_PRESSURE_PLATE, Material.SPRUCE_PRESSURE_PLATE,
        Material.BIRCH_PRESSURE_PLATE, Material.JUNGLE_PRESSURE_PLATE,
        Material.ACACIA_PRESSURE_PLATE, Material.DARK_OAK_PRESSURE_PLATE,
        Material.CRIMSON_PRESSURE_PLATE, Material.WARPED_PRESSURE_PLATE,
        Material.MANGROVE_PRESSURE_PLATE, Material.BAMBOO_PRESSURE_PLATE,
        Material.CHERRY_PRESSURE_PLATE, Material.LIGHT_WEIGHTED_PRESSURE_PLATE,
        Material.HEAVY_WEIGHTED_PRESSURE_PLATE, Material.POLISHED_BLACKSTONE_PRESSURE_PLATE,
        Material.OAK_FENCE_GATE, Material.SPRUCE_FENCE_GATE, Material.BIRCH_FENCE_GATE,
        Material.JUNGLE_FENCE_GATE, Material.ACACIA_FENCE_GATE, Material.DARK_OAK_FENCE_GATE,
        Material.CRIMSON_FENCE_GATE, Material.WARPED_FENCE_GATE, Material.MANGROVE_FENCE_GATE,
        Material.BAMBOO_FENCE_GATE, Material.CHERRY_FENCE_GATE
    );

    public static boolean isBlocked(Material mat) { return BLOCKED.contains(mat); }

    public static Material parseMaterial(String s) {
        return parse(s);
    }
    private static Material parse(String s) {
        try { return Material.valueOf(s.toUpperCase()); }
        catch (Exception e) { return Material.OAK_PLANKS; }
    }

    /**
     * Void world with a bedrock layer at y=0.
     * Paper 1.20.4's light engine deadlocks on fully-empty chunks because
     * skylight propagates through all 384 layers with no termination surface.
     * A single bedrock layer at y=0 breaks the light column and prevents deadlocks.
     */
    public static class VoidGenerator extends ChunkGenerator {

        @Override
        public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
            ChunkData data = createChunkData(world);
            // Bedrock at y=0 stops skylight propagation, prevents lighting deadlock
            for (int bx = 0; bx < 16; bx++)
                for (int bz = 0; bz < 16; bz++)
                    data.setBlock(bx, 0, bz, Material.BEDROCK);
            return data;
        }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0, 70, 0);
        }

        @Override public boolean shouldGenerateNoise()       { return false; }
        @Override public boolean shouldGenerateSurface()     { return false; }
        @Override public boolean shouldGenerateCaves()        { return false; }
        @Override public boolean shouldGenerateDecorations()  { return false; }
        @Override public boolean shouldGenerateMobs()         { return false; }
        @Override public boolean shouldGenerateStructures()   { return false; }
    }
}