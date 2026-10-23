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
package com.syk.gomoku.model;

import com.google.gson.annotations.Expose;
import java.util.UUID;

public class PlayerStats {
    @Expose private UUID uuid;
    @Expose private String lastName;
    @Expose private int wins, losses, draws, level, totalGames;
    /** Score already spent in the piece shop, so it cannot be spent twice. */
    @Expose private int spent;

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid; this.lastName = name;
        this.level = 1;
    }

    /** Used by the MySQL storage when loading saved rows. */
    public PlayerStats(UUID uuid, String name, int wins, int losses, int draws,
                       int level, int totalGames, int spent) {
        this.uuid = uuid;
        this.lastName = name;
        this.wins = wins;
        this.losses = losses;
        this.draws = draws;
        this.level = level;
        this.totalGames = totalGames;
        this.spent = spent;
    }

    public UUID getUuid() { return uuid; }
    public String getLastName() { return lastName; }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }
    public int getDraws() { return draws; }
    public int getLevel() { return level; }
    public int getTotalGames() { return totalGames; }
    public double getWinRate() { return totalGames == 0 ? 0 : (double) wins / totalGames * 100; }
    /** Lifetime score: wins×3 + draws. */
    public int getScore() { return wins * 3 + draws; }
    public int getSpent() { return spent; }
    /** What is left to spend in the piece shop. */
    public int getAvailableScore() { return Math.max(0, getScore() - spent); }

    public void setLastName(String n) { this.lastName = n; }
    public void setLevel(int l) { this.level = l; }
    public void addWin() { wins++; totalGames++; }
    public void addLoss() { losses++; totalGames++; }
    public void addDraw() { draws++; totalGames++; }
    public void addSpent(int amount) { spent += Math.max(0, amount); }
}
