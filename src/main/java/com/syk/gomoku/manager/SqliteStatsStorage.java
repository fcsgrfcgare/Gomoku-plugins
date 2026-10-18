package com.syk.gomoku.manager;

import com.syk.gomoku.model.PlayerStats;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Local SQL DB (SQLite) storage. The database file and table are created automatically,
 * so no external database server is needed.
 */
public class SqliteStatsStorage implements StatsStorage {

    private static final String TABLE = "gomoku_stats";

    private final String url;

    public SqliteStatsStorage(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        this.url = "jdbc:sqlite:" + file.getAbsolutePath();

        Class.forName("org.sqlite.JDBC");

        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS `" + TABLE + "` ("
                    + "`uuid` TEXT NOT NULL PRIMARY KEY,"
                    + "`name` TEXT NOT NULL,"
                    + "`wins` INTEGER NOT NULL DEFAULT 0,"
                    + "`losses` INTEGER NOT NULL DEFAULT 0,"
                    + "`draws` INTEGER NOT NULL DEFAULT 0,"
                    + "`level` INTEGER NOT NULL DEFAULT 1,"
                    + "`total_games` INTEGER NOT NULL DEFAULT 0,"
                    + "`spent` INTEGER NOT NULL DEFAULT 0"
                    + ")");
        }
    }

    @Override
    public List<PlayerStats> load() throws Exception {
        List<PlayerStats> list = new ArrayList<>();
        String sql = "SELECT `uuid`,`name`,`wins`,`losses`,`draws`,`level`,`total_games`,`spent` FROM `" + TABLE + "`";
        try (Connection c = DriverManager.getConnection(url);
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
        String sql = "INSERT INTO `" + TABLE + "` "
                + "(`uuid`,`name`,`wins`,`losses`,`draws`,`level`,`total_games`,`spent`) "
                + "VALUES (?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(`uuid`) DO UPDATE SET "
                + "`name`=excluded.`name`,`wins`=excluded.`wins`,`losses`=excluded.`losses`,"
                + "`draws`=excluded.`draws`,`level`=excluded.`level`,"
                + "`total_games`=excluded.`total_games`,`spent`=excluded.`spent`";

        try (Connection c = DriverManager.getConnection(url);
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
