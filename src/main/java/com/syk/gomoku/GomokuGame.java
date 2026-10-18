package com.syk.gomoku;

import java.util.ArrayList;
import java.util.List;

/**
 * Gomoku board state - 0=empty, 1=BLACK, 2=WHITE.
 * Coordinates: [row][col], 0-indexed from top-left.
 */
public class GomokuGame {

    public static final int EMPTY = 0, BLACK = 1, WHITE = 2;
    public static final int RULE_FREESTYLE = 0, RULE_STANDARD = 1, RULE_RENJU = 4;
    public static final int GAME_ONGOING = 0, GAME_BLACK_WIN = 1, GAME_WHITE_WIN = 2, GAME_DRAW = 3;

    private static final int[][] DIRS = {{1,0},{0,1},{1,1},{1,-1}};

    private final int size;
    private final int[][] board;
    private final List<int[]> history = new ArrayList<>();
    private int rule;

    public GomokuGame(int size) { this(size, RULE_FREESTYLE); }

    public GomokuGame(int size, int rule) {
        this.size = size; this.rule = rule; this.board = new int[size][size];
    }

    public int getSize() { return size; }
    public int getRule() { return rule; }
    public int[][] getBoard() { return board; }
    public int getMoveCount() { return history.size(); }
    public boolean isEmpty() { return history.isEmpty(); }

    public int sideToMove() { return history.size() % 2 == 0 ? BLACK : WHITE; }

    public boolean isEmpty(int r, int c) {
        return r >= 0 && r < size && c >= 0 && c < size && board[r][c] == EMPTY;
    }

    public boolean makeMove(int r, int c) {
        if (!isEmpty(r, c)) return false;
        if (getResult() != GAME_ONGOING) return false;
        board[r][c] = sideToMove();
        history.add(new int[]{r, c});
        return true;
    }

    public int[] undo() {
        if (history.isEmpty()) return null;
        int[] last = history.remove(history.size() - 1);
        board[last[0]][last[1]] = EMPTY;
        return last;
    }

    public boolean isWinningMove(int r, int c, int side) {
        for (int[] d : DIRS) {
            int cnt = 1;
            for (int i = 1; i < 5; i++) {
                int nr = r + d[0]*i, nc = c + d[1]*i;
                if (nr >= 0 && nr < size && nc >= 0 && nc < size && board[nr][nc] == side) cnt++;
                else break;
            }
            for (int i = 1; i < 5; i++) {
                int nr = r - d[0]*i, nc = c - d[1]*i;
                if (nr >= 0 && nr < size && nc >= 0 && nc < size && board[nr][nc] == side) cnt++;
                else break;
            }
            if (cnt >= 5) return true;
        }
        return false;
    }

    public int getResult() {
        if (history.isEmpty()) return GAME_ONGOING;
        int[] last = history.get(history.size() - 1);
        int side = board[last[0]][last[1]];
        if (isWinningMove(last[0], last[1], side))
            return side == BLACK ? GAME_BLACK_WIN : GAME_WHITE_WIN;
        if (history.size() == size * size) return GAME_DRAW;
        return GAME_ONGOING;
    }

    public List<int[]> getWinLine() {
        if (history.isEmpty()) return List.of();
        int[] last = history.get(history.size() - 1);
        int side = board[last[0]][last[1]];
        for (int[] d : DIRS) {
            List<int[]> line = new ArrayList<>();
            line.add(new int[]{last[0], last[1]});
            for (int i = 1; i < 5; i++) {
                int nr = last[0]+d[0]*i, nc = last[1]+d[1]*i;
                if (nr >= 0 && nr < size && nc >= 0 && nc < size && board[nr][nc] == side) line.add(new int[]{nr,nc});
                else break;
            }
            for (int i = 1; i < 5; i++) {
                int nr = last[0]-d[0]*i, nc = last[1]-d[1]*i;
                if (nr >= 0 && nr < size && nc >= 0 && nc < size && board[nr][nc] == side) line.add(new int[]{nr,nc});
                else break;
            }
            if (line.size() >= 5) return line;
        }
        return List.of();
    }

    public void setRule(int rule) { this.rule = rule; }

    public GomokuGame copy() {
        GomokuGame g = new GomokuGame(size, rule);
        for (int[] m : history) g.makeMove(m[0], m[1]);
        return g;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++)
                sb.append(board[r][c]==BLACK?"[B]":board[r][c]==WHITE?"[W]":".").append(' ');
            sb.append('\n');
        }
        return sb.toString();
    }
}
