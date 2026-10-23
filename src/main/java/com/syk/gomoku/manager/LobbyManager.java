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

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Reads/writes lobby location from config.yml (unified config file).
 */
public class LobbyManager {

    private final File configFile;
    private final Logger logger;

    private String worldName;
    private double x, y, z;
    private float yaw, pitch;
    private boolean set;

    public LobbyManager(File dataFolder, Logger logger) {
        this.logger = logger;
        this.configFile = new File(dataFolder, "config.yml");
        load();
    }

    private void load() {
        if (!configFile.exists()) { save(); return; }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        this.worldName = cfg.getString("lobby.world", "");
        this.x = cfg.getDouble("lobby.x", 0);
        this.y = cfg.getDouble("lobby.y", 64);
        this.z = cfg.getDouble("lobby.z", 0);
        this.yaw = (float) cfg.getDouble("lobby.yaw", 0);
        this.pitch = (float) cfg.getDouble("lobby.pitch", 0);
        this.set = cfg.getBoolean("lobby.set", false);
        applyWorldSpawn();
    }

    public void save() {
        YamlConfiguration cfg;
        if (configFile.exists()) {
            cfg = YamlConfiguration.loadConfiguration(configFile);
        } else {
            cfg = new YamlConfiguration();
        }
        cfg.set("lobby.world", worldName != null ? worldName : "");
        cfg.set("lobby.x", x);
        cfg.set("lobby.y", y);
        cfg.set("lobby.z", z);
        cfg.set("lobby.yaw", (double) yaw);
        cfg.set("lobby.pitch", (double) pitch);
        cfg.set("lobby.set", set);
        try {
            if (!configFile.getParentFile().exists()) configFile.getParentFile().mkdirs();
            cfg.save(configFile);
        } catch (IOException e) {
            logger.warning("Lobby save failed: " + e.getMessage());
        }
    }

    public boolean isSet() { return set && getLocation() != null; }

    public String getWorldName() { return worldName; }

    public boolean isLobbyWorld(org.bukkit.World world) {
        return world != null && worldName != null && world.getName().equals(worldName);
    }

    public void setLocation(Location loc) {
        this.worldName = loc.getWorld().getName();
        this.x = loc.getX(); this.y = loc.getY(); this.z = loc.getZ();
        this.yaw = loc.getYaw(); this.pitch = loc.getPitch();
        this.set = true;
        save();
        applyWorldSpawn();
    }

    /**
     * Mirrors the lobby into the vanilla spawn point of its world, so that players who
     * respawn without a bed (and first-time joiners) land at the lobby instead of the
     * world's original spawn.
     */
    private void applyWorldSpawn() {
        Location loc = getLocation();
        if (loc == null || loc.getWorld() == null) return;
        World w = loc.getWorld();
        w.setSpawnLocation(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getYaw());
        logger.info("World spawn of '" + w.getName() + "' set to the lobby at "
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
    }

    public Location getLocation() {
        if (!set || worldName == null || worldName.isEmpty()) return null;
        World w = Bukkit.getWorld(worldName);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public void reload() { load(); }
}
