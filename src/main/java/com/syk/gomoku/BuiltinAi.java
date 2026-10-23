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
package com.syk.gomoku;

import java.util.List;

/**
 * 内置五子棋 AI（纯 Java，无外部进程）。
 *
 * <p>思路：只考虑已有棋子周围 2 格以内的空点，对每个点分别算"我下这里"和"对手下这里"
 * 的棋型分，进攻权重略高于防守，取最高分的点。棋型分按连子数和两端是否被封堵给分：
 * 五连 &gt; 活四 &gt; 冲四 &gt; 活三 &gt; 眠三 &gt; 活二 …</p>
 *
 * <p>返回的坐标就是棋盘的 {@code [row][col]}，和 {@link GomokuGame} 的坐标完全一致，
 * 不需要任何转换。</p>
 */
public final class BuiltinAi {

    private static final int[][] DIRS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    private static final int FIVE      = 10_000_000;
    private static final int OPEN_FOUR = 100_000;
    private static final int FOUR      = 10_000;
    private static final int OPEN_THREE = 5_000;
    private static final int THREE     = 500;
    private static final int OPEN_TWO  = 200;
    private static final int TWO       = 50;

    private BuiltinAi() {}

    /**
     * @param board  棋盘，0=空，1=黑，2=白（即 {@link GomokuGame#getBoard()}）
     * @param aiSide 1 或 2
     * @return {row, col}，棋盘已满时返回 null
     */
    public static int[] bestMove(int[][] board, int aiSide, int size) {
        return bestMove(board, aiSide, size, 3);
    }

    /**
     * @param level 1=简单 2=普通 3=困难 4=最强（低等级会从候选里随机挑，故意留破绽）
     */
    public static int[] bestMove(int[][] board, int aiSide, int size, int level) {
        int humanSide = aiSide == 1 ? 2 : 1;

        if (isEmpty(board, size)) return new int[]{size / 2, size / 2};

        boolean dumb = level <= 1;      // 简单: 会漏防, 常常随手下一手
        boolean sloppy = level == 2;    // 普通: 偶尔走次优
        List<int[]> cands = new java.util.ArrayList<>();

        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (board[row][col] != 0) continue;
                if (!nearStone(board, row, col, size)) continue;

                // 自己一手能赢就直接赢（再傻也知道赢）
                int attack = scoreAt(board, row, col, aiSide, size);
                if (attack >= FIVE) return new int[]{row, col};
                // 堵对手成五: 简单档有 40% 概率故意看不见
                int defend = scoreAt(board, row, col, humanSide, size);
                if (defend >= FIVE && !(dumb && Math.random() < 0.4)) return new int[]{row, col};

                cands.add(new int[]{attack * 2 + defend, row, col});
            }
        }

        if (!cands.isEmpty()) {
            cands.sort((a, b) -> Integer.compare(b[0], a[0]));
            double r = Math.random();
            // 简单: 55% 完全随机挑一个候选点
            if (dumb && r < 0.55) {
                int[] pick = cands.get((int) (Math.random() * cands.size()));
                return new int[]{pick[1], pick[2]};
            }
            // 普通: 25% 从前 5 名里随机
            if (sloppy && r < 0.25 && cands.size() > 1) {
                int bound = Math.min(5, cands.size());
                int[] pick = cands.get((int) (Math.random() * bound));
                return new int[]{pick[1], pick[2]};
            }
            return new int[]{cands.get(0)[1], cands.get(0)[2]};
        }

        // 兜底：随便找一个空点
        for (int row = 0; row < size; row++)
            for (int col = 0; col < size; col++)
                if (board[row][col] == 0) return new int[]{row, col};

        return null;
    }

    private static boolean isEmpty(int[][] board, int size) {
        for (int r = 0; r < size; r++)
            for (int c = 0; c < size; c++)
                if (board[r][c] != 0) return false;
        return true;
    }

    /** 周围 2 格内有棋子才考虑，避免把第一批落点摊到全盘。 */
    private static boolean nearStone(int[][] board, int row, int col, int size) {
        for (int dr = -2; dr <= 2; dr++) {
            for (int dc = -2; dc <= 2; dc++) {
                if (dr == 0 && dc == 0) continue;
                int r = row + dr, c = col + dc;
                if (r < 0 || c < 0 || r >= size || c >= size) continue;
                if (board[r][c] != 0) return true;
            }
        }
        return false;
    }

    /** 假设 {@code side} 落在 (row,col)，累计四个方向的棋型分。 */
    private static int scoreAt(int[][] board, int row, int col, int side, int size) {
        int total = 0;
        for (int[] d : DIRS) {
            int count = 1;
            int openEnds = 0;

            // 正方向
            int r = row + d[0], c = col + d[1];
            while (inBoard(r, c, size) && board[r][c] == side) { count++; r += d[0]; c += d[1]; }
            if (inBoard(r, c, size) && board[r][c] == 0) openEnds++;

            // 反方向
            r = row - d[0]; c = col - d[1];
            while (inBoard(r, c, size) && board[r][c] == side) { count++; r -= d[0]; c -= d[1]; }
            if (inBoard(r, c, size) && board[r][c] == 0) openEnds++;

            total += value(count, openEnds);
        }
        return total;
    }

    private static boolean inBoard(int r, int c, int size) {
        return r >= 0 && c >= 0 && r < size && c < size;
    }

    private static int value(int count, int openEnds) {
        if (count >= 5) return FIVE;
        if (openEnds == 0) return count >= 4 ? FOUR / 10 : 1;   // 两端全堵，基本没用
        return switch (count) {
            case 4 -> openEnds == 2 ? OPEN_FOUR : FOUR;
            case 3 -> openEnds == 2 ? OPEN_THREE : THREE;
            case 2 -> openEnds == 2 ? OPEN_TWO : TWO;
            default -> 10;
        };
    }
}
