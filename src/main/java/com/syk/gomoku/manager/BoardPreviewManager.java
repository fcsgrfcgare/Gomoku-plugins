package com.syk.gomoku.manager;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Board border preview using wool blocks.
 *
 * Strategy: preload ALL required chunks asynchronously first, then batch-place
 * wool on the main thread once every chunk is guaranteed loaded.  This avoids
 * the Paper 1.20.4 deadlock where getBlockAt().getType() triggers synchronous
 * chunk generation on the server thread.
 */
public class BoardPreviewManager {

    private final Plugin plugin;
    private final Map<UUID, PreviewSession> sessions = new HashMap<>();
    private static final int BATCH_SIZE = 20;

    public BoardPreviewManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void startPreview(Player player, int size, int gap) {
        cancelPreview(player);

        int cellSpan = gap + 1;
        double halfSpan = size * cellSpan / 2.0;
        double borderMin = -halfSpan - 1;
        double borderMax = halfSpan + 1;

        World w = player.getWorld();
        int cx = player.getLocation().getBlockX();
        int cz = player.getLocation().getBlockZ();
        int y = player.getLocation().getBlockY() - 1;

        // 1. Collect every block position on the border
        List<int[]> positions = new ArrayList<>();
        for (double b = borderMin; b <= borderMax + 0.1; b++) {
            positions.add(new int[]{cx + (int)b, y, cz + (int)borderMin});
            positions.add(new int[]{cx + (int)b, y, cz + (int)borderMax});
            positions.add(new int[]{cx + (int)borderMin, y, cz + (int)b});
            positions.add(new int[]{cx + (int)borderMax, y, cz + (int)b});
        }

        List<int[]> placed = new ArrayList<>();
        int boardY = player.getLocation().getBlockY();
        PreviewSession session = new PreviewSession(size, gap, cx, cz, boardY, placed);
        sessions.put(player.getUniqueId(), session);

        // 2. Work out which chunks need to exist
        Set<Long> neededChunks = new HashSet<>();
        for (int[] p : positions) {
            int chunkX = p[0] >> 4, chunkZ = p[2] >> 4;
            neededChunks.add(((long)chunkX << 32) | (chunkZ & 0xffffffffL));
        }

        // 3. Preload every chunk asynchronously — no main-thread blocking
        @SuppressWarnings("unchecked")
        CompletableFuture<Chunk>[] futures = neededChunks.stream()
                .map(key -> w.getChunkAtAsync((int)(key >> 32), (int)(long)key))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).thenRun(() -> {
            // 4. All chunks are loaded — schedule batch placement on the main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                int[] idx = {0};
            BukkitRunnable task = new BukkitRunnable() {
                @Override
                public void run() {
                    int end = Math.min(idx[0] + BATCH_SIZE, positions.size());
                    for (int i = idx[0]; i < end; i++) {
                        int[] p = positions.get(i);
                        // getType() is now instant — chunks are already loaded
                        if (w.getBlockAt(p[0], p[1], p[2]).getType() == Material.AIR) {
                            w.getBlockAt(p[0], p[1], p[2]).setType(Material.LIME_WOOL, false);
                            placed.add(p);
                        }
                    }
                    idx[0] = end;
                    if (idx[0] >= positions.size()) {
                        session.task = null;
                        cancel();
                    }
                }
            };
            session.task = task;
            task.runTaskTimer(plugin, 0L, 1L);
            });
        });
    }

    /** Remove placed wool — chunks are already loaded from placement, safe to batch. */
    private void removeWoolBatch(World w, List<int[]> placed) {
        if (placed.isEmpty()) return;
        int[] idx = {0};
        new BukkitRunnable() {
            @Override
            public void run() {
                int end = Math.min(idx[0] + BATCH_SIZE, placed.size());
                for (int i = idx[0]; i < end; i++) {
                    int[] p = placed.get(i);
                    if (w.getBlockAt(p[0], p[1], p[2]).getType() == Material.LIME_WOOL) {
                        w.getBlockAt(p[0], p[1], p[2]).setType(Material.AIR, false);
                    }
                }
                idx[0] = end;
                if (idx[0] >= placed.size()) cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Returns {cx, cz, boardY} from preview session, or null if no session. */
    public int[] getBuildCenter(Player player) {
        PreviewSession s = sessions.get(player.getUniqueId());
        if (s != null) return new int[]{s.cx, s.cz, s.boardY};
        return null;
    }

    public PreviewSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void cancelPreview(Player player) {
        PreviewSession s = sessions.remove(player.getUniqueId());
        if (s == null) return;
        if (s.task != null) {
            s.task.cancel();
        }
        if (s.placed != null && !s.placed.isEmpty()) {
            removeWoolBatch(player.getWorld(), new ArrayList<>(s.placed));
        }
    }

    public static class PreviewSession {
        public final int size, gap, cx, cz, boardY;
        public final List<int[]> placed;
        BukkitRunnable task;

        PreviewSession(int size, int gap, int cx, int cz, int boardY, List<int[]> placed) {
            this.size = size; this.gap = gap; this.cx = cx; this.cz = cz; this.boardY = boardY;
            this.placed = placed;
        }
    }
}
