package com.syk.gomoku.manager;

import com.syk.gomoku.model.PlayerStats;

import java.util.Collection;
import java.util.List;

/** Storage backend for player stats: JSON file or MySQL database. */
public interface StatsStorage {

    List<PlayerStats> load() throws Exception;

    void saveAll(Collection<PlayerStats> stats) throws Exception;

    default void close() {}
}
