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

import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从管理员点击的一条"井字线"出发, 自动推算整块手建棋盘的参数。
 *
 * <p>只认和点击方块<b>同一种类型</b>的方块, 所以棋盘外面另外一圈不同材质的外框
 * 不会被算进去 (井字线本身在交叉处相连, 会自然形成一个连通块)。</p>
 *
 * <ol>
 *   <li>点击的方块类型记为 {@code T};</li>
 *   <li>在这一层做 4 连通洪水填充, 只吞 {@code T} 的方块 —— 得到的连通块就是整张井字线;</li>
 *   <li>连通块的外接矩形 = 棋盘网格范围 (第一/最后一条线);</li>
 *   <li>某一列若从头到尾都是 {@code T}, 它就是一条竖线; 横线同理。
 *       竖线数量 = 格子数 {@code size}, 相邻竖线间距 = {@code cellSpan};</li>
 *   <li>中心取外接矩形中点; 棋盘方块在 {@code y}, 棋子层在 {@code y + 1}。</li>
 * </ol>
 */
public final class BoardScanner {

    /** 结果; error 非空时表示识别失败 (值是语言 key)。 */
    public static final class Result {
        public final String error;
        public final int size, interval;
        public final String lineBlock, fillBlock;
        public final int centerX, centerZ, boardY;
        public final int minX, maxX, minZ, maxZ;

        private Result(String error) {
            this.error = error;
            this.size = 0; this.interval = 0;
            this.lineBlock = null; this.fillBlock = null;
            this.centerX = 0; this.centerZ = 0; this.boardY = 0;
            this.minX = 0; this.maxX = 0; this.minZ = 0; this.maxZ = 0;
        }

        private Result(int size, int interval, String lineBlock, String fillBlock,
                       int centerX, int centerZ, int boardY,
                       int minX, int maxX, int minZ, int maxZ) {
            this.error = null;
            this.size = size; this.interval = interval;
            this.lineBlock = lineBlock; this.fillBlock = fillBlock;
            this.centerX = centerX; this.centerZ = centerZ; this.boardY = boardY;
            this.minX = minX; this.maxX = maxX; this.minZ = minZ; this.maxZ = maxZ;
        }

        public boolean ok() { return error == null; }
    }

    /** 连通块最多允许多少方块, 防止误点后扫整个世界。 */
    // 64x64 格、interval=5 时井字线约 4.4 万方块, 上限要留足
    private static final int MAX_BLOCKS = 150000;
    /** 以点击点为中心最多向外扫多少格。防止误点整片基岩/地板时横扫地图。 */
    private static final int MAX_RADIUS = 240;

    private BoardScanner() {}

    public static Result scan(World w, int x, int y, int z) {
        Material line = w.getBlockAt(x, y, z).getType();
        if (line.isAir()) return fail("axe-scan-air");

        // ── 1) 只吞同类型的连通块 (外框材质不同, 天然被排除) ──
        Set<Long> seen = new HashSet<>();
        Deque<int[]> stack = new ArrayDeque<>();
        List<int[]> cells = new ArrayList<>();
        stack.push(new int[]{x, z});
        seen.add(key(x, z));

        int minX = x, maxX = x, minZ = z, maxZ = z;
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            cells.add(p);
            if (cells.size() > MAX_BLOCKS) return fail("axe-scan-too-big");
            if (p[0] < minX) minX = p[0];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] < minZ) minZ = p[1];
            if (p[1] > maxZ) maxZ = p[1];

            pushIfSame(w, y, line, p[0] + 1, p[1], x, z, seen, stack);
            pushIfSame(w, y, line, p[0] - 1, p[1], x, z, seen, stack);
            pushIfSame(w, y, line, p[0], p[1] + 1, x, z, seen, stack);
            pushIfSame(w, y, line, p[0], p[1] - 1, x, z, seen, stack);
        }

        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;
        if (cells.size() < 2) return fail("axe-scan-not-line");

        // ── 1.5) 沿着线补齐缺口：一列/一行大部分是线时, 把中间不完整的方块补成点击的材质 ──
        for (int px = minX; px <= maxX; px++) {
            int cnt = 0;
            for (int pz = minZ; pz <= maxZ; pz++) {
                if (!w.isChunkLoaded(px >> 4, pz >> 4)) continue;
                if (w.getBlockAt(px, y, pz).getType() == line) cnt++;
            }
            if (cnt * 2 < height) continue;                 // 这一列不是线
            for (int pz = minZ; pz <= maxZ; pz++) {
                if (!w.isChunkLoaded(px >> 4, pz >> 4)) continue;
                var b = w.getBlockAt(px, y, pz);
                if (b.getType() != line) {
                    b.setType(line);                        // 补缺口
                    cells.add(new int[]{px, pz});           // 同步到 cells, 后面的 perX/perZ 才能统计到
                }
            }
        }
        for (int pz = minZ; pz <= maxZ; pz++) {
            int cnt = 0;
            for (int px = minX; px <= maxX; px++) {
                if (!w.isChunkLoaded(px >> 4, pz >> 4)) continue;
                if (w.getBlockAt(px, y, pz).getType() == line) cnt++;
            }
            if (cnt * 2 < width) continue;                  // 这一行不是线
            for (int px = minX; px <= maxX; px++) {
                if (!w.isChunkLoaded(px >> 4, pz >> 4)) continue;
                var b = w.getBlockAt(px, y, pz);
                if (b.getType() != line) {
                    b.setType(line);                        // 补缺口
                    cells.add(new int[]{px, pz});
                }
            }
        }

        // ── 2) 统计每个 x / 每个 z 上的方块数 ──
        Map<Integer, Integer> perX = new HashMap<>();
        Map<Integer, Integer> perZ = new HashMap<>();
        for (int[] c : cells) {
            perX.merge(c[0], 1, Integer::sum);
            perZ.merge(c[1], 1, Integer::sum);
        }

        // 一条竖线: 该 x 从 minZ 到 maxZ 全是线; 横线同理
        List<Integer> vs = new ArrayList<>();
        for (int px = minX; px <= maxX; px++) {
            if (perX.getOrDefault(px, 0) == height) vs.add(px);
        }
        List<Integer> hs = new ArrayList<>();
        for (int pz = minZ; pz <= maxZ; pz++) {
            if (perZ.getOrDefault(pz, 0) == width) hs.add(pz);
        }
        if (vs.size() < 2 || vs.size() != hs.size()) return fail("axe-scan-not-line");

        // ── 3) 间距 → interval ──
        int size = vs.size() - 1;      // 线数 - 1 = 格子数(棋盘 size 按格子计)
        int cellSpan = vs.get(1) - vs.get(0);
        if (cellSpan < 2) return fail("axe-scan-not-line");
        for (int i = 1; i < vs.size(); i++) {
            if (vs.get(i) - vs.get(i - 1) != cellSpan) return fail("axe-scan-size");
        }
        for (int i = 1; i < hs.size(); i++) {
            if (hs.get(i) - hs.get(i - 1) != cellSpan) return fail("axe-scan-size");
        }
        if (size < 2 || size > 64) return fail("axe-scan-size");
        if ((vs.get(vs.size() - 1) - vs.get(0)) != size * cellSpan) return fail("axe-scan-size");

        // ── 4) 填充材质 = 网格内出现最多的非线方块 ──
        Material fill = null;
        int best = 0;
        Map<Material, Integer> counts = new HashMap<>();
        for (int px = minX; px <= maxX; px++) {
            for (int pz = minZ; pz <= maxZ; pz++) {
                if (!w.isChunkLoaded(px >> 4, pz >> 4)) continue;
                Material m = w.getBlockAt(px, y, pz).getType();
                if (m.isAir() || m == line) continue;
                int c = counts.merge(m, 1, Integer::sum);
                if (c > best) { best = c; fill = m; }
            }
        }
        if (fill == null) return fail("axe-scan-no-fill");

        return new Result(size, cellSpan - 1, line.name(), fill.name(),
                (minX + maxX) / 2, (minZ + maxZ) / 2, y + 1,
                minX, maxX, minZ, maxZ);
    }

    private static void pushIfSame(World w, int y, Material line, int px, int pz,
                                   int ox, int oz, Set<Long> seen, Deque<int[]> stack) {
        if (Math.abs(px - ox) > MAX_RADIUS || Math.abs(pz - oz) > MAX_RADIUS) return;
        // 关键: 只在已加载的区块里读方块。getBlockAt 会同步加载/生成区块,
        // 在基岩这类整片同材质的方块上会一路蔓延把主线程卡死 (watchdog 崩溃)。
        if (!w.isChunkLoaded(px >> 4, pz >> 4)) return;
        long k = key(px, pz);
        if (!seen.add(k)) return;
        if (w.getBlockAt(px, y, pz).getType() == line) stack.push(new int[]{px, pz});
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static Result fail(String error) { return new Result(error); }
}
