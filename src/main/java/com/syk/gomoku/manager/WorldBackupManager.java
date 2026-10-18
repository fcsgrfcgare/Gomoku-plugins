package com.syk.gomoku.manager;

import com.syk.gomoku.GomokuPlugin;
import com.syk.gomoku.model.Room;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Whole-world backup/restore for a room.
 *
 * <p>{@code snapshot()} copies the room world folder to
 * {@code plugins/Gomoku/backups/<room>}. {@code restore()} unloads the (empty)
 * world, replaces its folder from the backup and loads it again. This removes
 * every generated block/entity (including win-celebration TNT and blocks).</p>
 */
public class WorldBackupManager {

    private final GomokuPlugin plugin;
    private final Logger logger;
    private final Set<String> saving = new HashSet<>();

    public WorldBackupManager(GomokuPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** Copies the complete room world folder to the room's backup folder. */
    public void snapshot(Room room) { snapshot(room, true); }

    /**
     * @param saveWorld true when the caller wants the world saved first; false when
     *                  this is called from WorldSaveEvent (the world is already saving).
     */
    public void snapshot(Room room, boolean saveWorld) {
        World w = room.getWorld();
        if (w == null) {
            logger.info("World backup skipped: world '" + room.getWorldName() + "' is not loaded.");
            return;
        }
        String key = room.getName().toLowerCase(Locale.ROOT);
        if (saveWorld) {
            saving.add(key);
            try {
                w.save();
            } finally {
                saving.remove(key);
            }
        }
        File backup = new File(plugin.getDataFolder(), "backups/" + room.getName());
        try {
            delete(backup);
            copy(w.getWorldFolder(), backup);
            room.setBackupFolder("backups/" + room.getName());
            plugin.getRoomManager().saveRoom(room);
            logger.info("Backed up entire world of room '" + room.getName()
                    + "' (" + w.getWorldFolder().getName() + ") to the plugin backups folder.");
        } catch (IOException e) {
            logger.warning("World backup failed for room '" + room.getName() + "': " + e.getMessage());
        }
    }

    public boolean isSaving(Room room) {
        return saving.contains(room.getName().toLowerCase(Locale.ROOT));
    }

    /**
     * Restores the whole room world from its backup. Only runs when nobody is
     * inside the world; unloads it, replaces the folder and loads it again.
     */
    public boolean restore(Room room) {
        File backup = backupDir(room);
        if (!backup.isDirectory()) {
            logger.info("No world backup for room '" + room.getName() + "'; skipping world reset.");
            return false;
        }
        World w = room.getWorld();
        if (w != null) {
            if (!w.getPlayers().isEmpty()) {
                // The session players are already in the lobby at this point; any
                // remaining players are editors/spectators. Move them out instead of
                // skipping the reset, so every game end really resets the room.
                Location lobby = plugin.getLobbyManager().getLocation();
                if (lobby != null) {
                    for (Player p : new ArrayList<>(w.getPlayers())) p.teleport(lobby);
                }
                if (!w.getPlayers().isEmpty()) {
                    logger.info("World reset skipped for room '" + room.getName() + "': players are still inside.");
                    return false;
                }
            }
            if (!Bukkit.unloadWorld(w, false)) {
                logger.warning("World reset failed for room '" + room.getName() + "': could not unload world.");
                return false;
            }
        }
        File worldDir = new File(Bukkit.getWorldContainer(), room.getWorldName());
        try {
            delete(worldDir);
            copy(backup, worldDir);
        } catch (IOException e) {
            logger.warning("World reset failed for room '" + room.getName() + "': " + e.getMessage());
            return false;
        }
        World newWorld = plugin.getWorldManager().createVoidWorld(room.getWorldName());
        if (newWorld == null) {
            logger.warning("World reset failed for room '" + room.getName() + "': could not load world.");
            return false;
        }
        logger.info("Reset entire world of room '" + room.getName() + "' from backup.");
        return true;
    }

    public void removeBackup(String roomName) {
        try {
            delete(new File(plugin.getDataFolder(), "backups/" + roomName));
        } catch (IOException ignored) {
        }
    }

    private File backupDir(Room room) {
        String rel = room.getBackupFolder();
        if (rel == null || rel.isBlank()) rel = "backups/" + room.getName();
        return new File(plugin.getDataFolder(), rel);
    }

    private static void copy(File src, File dst) throws IOException {
        // The running server holds session.lock open; it must never be copied.
        if ("session.lock".equals(src.getName())) return;
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("Cannot create " + dst);
            File[] files = src.listFiles();
            if (files != null) for (File f : files) copy(f, new File(dst, f.getName()));
        } else {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void delete(File f) throws IOException {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) delete(c);
        }
        if (!f.delete() && f.exists()) throw new IOException("Cannot delete " + f);
    }
}
