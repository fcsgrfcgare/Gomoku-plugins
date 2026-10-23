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

import com.syk.gomoku.model.PlayerStats;
import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Optional MySQL/MariaDB storage. The table is created automatically.
 * If the driver or database is unavailable, StatsManager falls back to JSON.
 */
public class MysqlStatsStorage implements StatsStorage {

    private final String url;
    private final String username;
    private final String password;
    private final String table;

    public MysqlStatsStorage(YamlConfiguration cfg, Logger logger) throws Exception {
        String host = cfg.getString("storage.mysql.host", "127.0.0.1");
        int port = cfg.getInt("storage.mysql.port", 3306);
        String database = cfg.getString("storage.mysql.database", "gomoku");
        this.username = cfg.getString("storage.mysql.username", "root");
        this.password = cfg.getString("storage.mysql.password", "");
        boolean useSsl = cfg.getBoolean("storage.mysql.use-ssl", false);

        String prefix = cfg.getString("storage.mysql.table-prefix", "gomoku_");
        prefix = prefix == null ? "gomoku_" : prefix.replaceAll("[^A-Za-z0-9_]", "");
        if (prefix.isEmpty()) prefix = "gomoku_";
        this.table = prefix + "stats";

        String safeDatabase = database == null ? "gomoku" : database.replaceAll("[^A-Za-z0-9_]", "");
        if (safeDatabase.isEmpty()) safeDatabase = "gomoku";

        boolean createDatabase = cfg.getBoolean("storage.mysql.create-database", true);
        String options = "?useSsl=" + useSsl
                + "&allowPublicKeyRetrieval=true"
                + "&connectTimeout=5000"
                + "&socketTimeout=10000";

        // Paper downloads this class through the plugin.yml libraries section.
        Class.forName("org.mariadb.jdbc.Driver");

        if (createDatabase) {
            try (Connection c = DriverManager.getConnection(
                    "jdbc:mariadb://" + host + ":" + port + "/" + options, username, password);
                 Statement s = c.createStatement()) {
                s.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + safeDatabase
                        + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            logger.info("MySQL database ready: " + safeDatabase + " (created if missing).");
        }

        this.url = "jdbc:mariadb://" + host + ":" + port + "/" + safeDatabase + options;

        try (Connection c = DriverManager.getConnection(url, username, password);
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS `" + table + "` ("
                    + "`uuid` CHAR(36) NOT NULL,"
                    + "`name` VARCHAR(32) NOT NULL,"
                    + "`wins` INT NOT NULL DEFAULT 0,"
                    + "`losses` INT NOT NULL DEFAULT 0,"
                    + "`draws` INT NOT NULL DEFAULT 0,"
                    + "`level` INT NOT NULL DEFAULT 1,"
                    + "`total_games` INT NOT NULL DEFAULT 0,"
                    + "`spent` INT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`uuid`)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }

        logger.info("Player stats storage: MySQL (" + host + ":" + port + "/" + database
                + ", table " + table + ").");
    }

    @Override
    public List<PlayerStats> load() throws Exception {
        List<PlayerStats> list = new ArrayList<>();
        String sql = "SELECT `uuid`,`name`,`wins`,`losses`,`draws`,`level`,`total_games`,`spent` FROM `" + table + "`";
        try (Connection c = DriverManager.getConnection(url, username, password);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(1));
                list.add(new PlayerStats(uuid, rs.getString(2),
                        rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8)));
            }
        }
        return list;
    }

    @Override
    public void saveAll(Collection<PlayerStats> stats) throws Exception {
        if (stats.isEmpty()) return;
        String sql = "INSERT INTO `" + table + "` "
                + "(`uuid`,`name`,`wins`,`losses`,`draws`,`level`,`total_games`,`spent`) "
                + "VALUES (?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE "
                + "`name`=VALUES(`name`),`wins`=VALUES(`wins`),`losses`=VALUES(`losses`),"
                + "`draws`=VALUES(`draws`),`level`=VALUES(`level`),"
                + "`total_games`=VALUES(`total_games`),`spent`=VALUES(`spent`)";

        try (Connection c = DriverManager.getConnection(url, username, password);
             PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            for (PlayerStats st : stats) {
                ps.setString(1, st.getUuid().toString());
                ps.setString(2, st.getLastName());
                ps.setInt(3, st.getWins());
                ps.setInt(4, st.getLosses());
                ps.setInt(5, st.getDraws());
                ps.setInt(6, st.getLevel());
                ps.setInt(7, st.getTotalGames());
                ps.setInt(8, st.getSpent());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        }
    }
}
