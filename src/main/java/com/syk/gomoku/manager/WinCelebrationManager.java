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
import com.syk.gomoku.model.Room;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Win celebration effects.
 *
 * <p>Only called for a real player win (not AI, draw or timeout). The TNT switch
 * ({@code win-celebration.tnt}) decides between the TNT branch and the "other"
 * entity/block branch; the two branches never run together.</p>
 */
public class WinCelebrationManager {

    private final GomokuPlugin plugin;
    private final NamespacedKey tntKey;
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    private boolean enabled = true;
    private boolean useTnt = true;
    private double offsetY = -1;
    private double range = 0;
    /** TNT / other effect interval in ticks (1 = every tick, 20 = once per second). */
    private int mainIntervalTicks = 1;
    /** Corner firework interval in ticks. */
    private int fireworkIntervalTicks = 1;

    private boolean tntIgnited = true;
    private int fuseTicks = 20;
    private boolean breakBlocks = false;
    private boolean damageEntities = false;

    private String otherEntity = "FIREWORK";
    private double entityRange = 0;
    private double entityOffsetY = 1;

    private String otherBlock = "TNT";
    private double blockRange = 0;
    private double blockOffsetY = -1;

    private boolean cornerFireworks = true;

    public WinCelebrationManager(GomokuPlugin plugin) {
        this.plugin = plugin;
        this.tntKey = new NamespacedKey(plugin, "celebration_tnt");
    }

    /** Reads config.yml and logs the active mode in English (info only, never an error). */
    public void load() {
        File f = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();

        enabled = cfg.getBoolean("win-celebration.enabled", true);
        useTnt = cfg.getBoolean("win-celebration.tnt", true);
        offsetY = cfg.getDouble("win-celebration.offset-y", -1);
        range = Math.max(0, cfg.getDouble("win-celebration.range", 0));
        mainIntervalTicks = Math.max(1, cfg.getInt("win-celebration.interval-ticks", 1));
        fireworkIntervalTicks = Math.max(1, cfg.getInt("win-celebration.corner-fireworks.interval-ticks", 1));

        tntIgnited = cfg.getBoolean("win-celebration.tnt-options.ignited", true);
        fuseTicks = Math.max(0, cfg.getInt("win-celebration.tnt-options.fuse-ticks", 20));
        breakBlocks = cfg.getBoolean("win-celebration.tnt-options.break-blocks", false);
        damageEntities = cfg.getBoolean("win-celebration.tnt-options.damage-entities", false);

        otherEntity = cfg.getString("win-celebration.other.entity", "FIREWORK");
        entityRange = Math.max(0, cfg.getDouble("win-celebration.other.entity-range", 0));
        entityOffsetY = cfg.getDouble("win-celebration.other.entity-offset-y", 1);

        otherBlock = cfg.getString("win-celebration.other.block", "TNT");
        blockRange = Math.max(0, cfg.getDouble("win-celebration.other.block-range", 0));
        blockOffsetY = cfg.getDouble("win-celebration.other.block-offset-y", -1);

        cornerFireworks = cfg.getBoolean("win-celebration.corner-fireworks.enabled", true);

        if (!enabled) {
            plugin.getLogger().info("Win celebration is disabled.");
            return;
        }
        if (useTnt) {
            plugin.getLogger().info("Win celebration: TNT enabled (ignited=" + tntIgnited
                    + ", break-blocks=" + breakBlocks + ", damage-entities=" + damageEntities
                    + "); other generation is ignored.");
        } else if (hasText(otherEntity) || hasText(otherBlock)) {
            if (hasText(otherEntity) && !otherEntity.equalsIgnoreCase("FIREWORK")
                    && !otherEntity.equalsIgnoreCase("FIREWORK_ROCKET")) {
                try {
                    EntityType.valueOf(otherEntity.trim().toUpperCase(Locale.ROOT));
                } catch (Exception e) {
                    plugin.getLogger().info("Win celebration: unknown entity '" + otherEntity + "'; skipped.");
                }
            }
            if (hasText(otherBlock)) {
                Material m = Material.matchMaterial(otherBlock.trim().toUpperCase(Locale.ROOT));
                if (m == null || !m.isBlock()) {
                    plugin.getLogger().info("Win celebration: unknown block '" + otherBlock + "'; skipped.");
                }
            }
            plugin.getLogger().info("Win celebration: TNT disabled; using other generation (entity="
                    + safe(otherEntity) + ", block=" + safe(otherBlock) + ").");
        } else {
            plugin.getLogger().info("Win celebration: TNT disabled and no other entity/block configured; nothing will spawn.");
        }
        plugin.getLogger().info("Win celebration: corner fireworks " + (cornerFireworks ? "enabled" : "disabled") + ".");
        plugin.getLogger().info("Win celebration intervals: TNT/other every " + mainIntervalTicks
                + " tick(s); corner fireworks every " + fireworkIntervalTicks + " tick(s).");
    }

    /**
     * Starts a celebration that spawns the configured effect every tick until
     * {@code game.return-delay-seconds} is over or the session ends.
     */
    public void celebrate(Player winner, Room room) {
        stop(room);
        if (!enabled || winner == null || room == null) return;
        int duration = (int) Math.max(1, plugin.returnDelayTicks());
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int ticks;
            @Override public void run() {
                if (!winner.isOnline() || ticks++ >= duration) { stop(room); return; }
                int current = ticks;
                if (current % mainIntervalTicks == 0) {
                    if (useTnt) spawnTnt(winner);
                    else spawnOther(winner);
                }
                if (cornerFireworks && current % fireworkIntervalTicks == 0) spawnCornerFireworks(room);
            }
        }, 0L, 1L);
        tasks.put(room.getName().toLowerCase(Locale.ROOT), task);
        plugin.getLogger().info("Win celebration started for " + winner.getName()
                + " (TNT/other every " + mainIntervalTicks + " tick(s), fireworks every "
                + fireworkIntervalTicks + " tick(s), " + (duration / 20) + "s).");
    }

    public void stop(Room room) {
        if (room == null) return;
        BukkitTask task = tasks.remove(room.getName().toLowerCase(Locale.ROOT));
        if (task != null) task.cancel();
    }

    public void stopAll() {
        for (BukkitTask task : tasks.values()) task.cancel();
        tasks.clear();
    }

    private void spawnTnt(Player winner) {
        Location loc = offset(winner.getLocation(), range, offsetY);
        if (loc == null || loc.getWorld() == null) return;
        if (!tntIgnited) {
            loc.getBlock().setType(Material.TNT);
            return;
        }
        TNTPrimed tnt = loc.getWorld().spawn(loc, TNTPrimed.class);
        tnt.setFuseTicks(fuseTicks);
        tnt.setIsIncendiary(false);
        tnt.getPersistentDataContainer().set(tntKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void spawnOther(Player winner) {
        if (hasText(otherEntity)) spawnOtherEntity(winner);
        if (hasText(otherBlock)) spawnOtherBlock(winner);
    }

    private void spawnOtherEntity(Player winner) {
        Location loc = offset(winner.getLocation(), entityRange, entityOffsetY);
        if (loc == null || loc.getWorld() == null) return;
        String name = otherEntity.trim().toUpperCase(Locale.ROOT);
        if (name.equals("FIREWORK") || name.equals("FIREWORK_ROCKET")) {
            spawnFirework(loc);
            return;
        }
        try {
            EntityType type = EntityType.valueOf(name);
            loc.getWorld().spawnEntity(loc, type);
        } catch (Exception ignored) {
        }
    }

    private void spawnOtherBlock(Player winner) {
        Location loc = offset(winner.getLocation(), blockRange, blockOffsetY);
        if (loc == null || loc.getWorld() == null) return;
        Material mat = Material.matchMaterial(otherBlock.trim().toUpperCase(Locale.ROOT));
        if (mat == null || !mat.isBlock()) return;
        loc.getBlock().setType(mat);
    }

    private void spawnCornerFireworks(Room room) {
        World w = room.getWorld();
        if (w == null || !room.isBoardSet()) return;
        int size = room.getBoardSize();
        int y = room.getBoardY() + 1;
        int[][] corners = {{0, 0}, {0, size - 1}, {size - 1, 0}, {size - 1, size - 1}};
        for (int[] c : corners) {
            spawnFirework(new Location(w, room.centerX(c[0], c[1]), y, room.centerZ(c[0], c[1])));
        }
    }

    private void spawnFirework(Location loc) {
        if (loc.getWorld() == null) return;
        Firework fw = loc.getWorld().spawn(loc, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.RED, Color.YELLOW, Color.AQUA, Color.LIME)
                .with(FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .flicker(true)
                .build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
        fw.detonate();
    }

    private Location offset(Location base, double radius, double y) {
        if (base == null) return null;
        double dx = 0;
        double dz = 0;
        if (radius > 0) {
            dx = ThreadLocalRandom.current().nextDouble(-radius, radius);
            dz = ThreadLocalRandom.current().nextDouble(-radius, radius);
        }
        return base.clone().add(dx, y, dz);
    }

    public boolean isCelebrationTnt(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(tntKey, PersistentDataType.BYTE);
    }

    public boolean breakBlocks() { return breakBlocks; }
    public boolean damageEntities() { return damageEntities; }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }
    private static String safe(String s) { return hasText(s) ? s.trim() : "-"; }
}
