package com.syk.gomoku.manager;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

/**
 * Loads messages from languages/{locale}.yml with color code translation.
 * On first start, extracts built-in language files into the languages/ folder.
 *
 * <p>Each bundled file carries a {@code lang-version}. If the copy on disk is older,
 * it is backed up to {@code languages/backup/} and replaced, so upgraded messages
 * reach servers that already extracted an older revision. To keep local edits,
 * raise {@code lang-version} in your file above the bundled one.</p>
 *
 * <p>Users can edit them directly; /gomoku admin reload picks up changes immediately.</p>
 */
public class LanguageManager {

    /** Bundled language files extracted into languages/. */
    private static final String[] BUILTIN_FILES = {"zh_cn.yml", "en.yml"};

    private final File dataFolder;
    private final File langFolder;
    private final File backupFolder;
    private final Logger logger;
    private YamlConfiguration config;
    private String prefix;
    private String currentLocale;

    public LanguageManager(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.langFolder = new File(dataFolder, "languages");
        this.backupFolder = new File(langFolder, "backup");
        this.logger = logger;
        load();
    }

    private void load() {
        dataFolder.mkdirs();
        langFolder.mkdirs();

        // Ensure config.yml exists (never overwritten - it stores lobby/locale data)
        File cfgFile = new File(dataFolder, "config.yml");
        if (!cfgFile.exists()) {
            copyResource("config.yml", cfgFile);
        }

        // Extract built-in language files, refreshing outdated ones
        for (String name : BUILTIN_FILES) {
            syncLanguageFile(name);
        }

        // Read locale from config.yml
        currentLocale = YamlConfiguration.loadConfiguration(cfgFile).getString("locale", "zh_cn");

        // Load the matching language file
        File langFile = new File(langFolder, currentLocale + ".yml");
        if (!langFile.exists()) {
            logger.warning("Language file not found: " + langFile.getName() + ", falling back to en.yml");
            langFile = new File(langFolder, "en.yml");
        }
        this.config = YamlConfiguration.loadConfiguration(langFile);
        this.prefix = color(config.getString("prefix", "&8[&6Gomoku&8] &r"));
    }

    /**
     * Keeps languages/{name} in sync with the bundled copy.
     * The file on disk is only replaced when its lang-version is lower than the bundled one.
     */
    private void syncLanguageFile(String name) {
        File target = new File(langFolder, name);
        int builtin = resourceVersion(name);

        if (!target.exists()) {
            copyResource(name, target);
            return;
        }
        if (builtin <= 0) return;   // bundled file carries no version -> leave the disk copy alone

        int disk = YamlConfiguration.loadConfiguration(target).getInt("lang-version", 0);
        if (disk >= builtin) return;

        backup(target, disk);
        copyResource(name, target);
        logger.info("Refreshed outdated language file " + name + " (v" + disk + " -> v" + builtin + ")");
    }

    /** Copies the current file into languages/backup/ before it gets replaced. */
    private void backup(File target, int oldVersion) {
        try {
            backupFolder.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            String base = target.getName().replace(".yml", "");
            File dest = new File(backupFolder, base + "-v" + oldVersion + "-" + stamp + ".yml");
            Files.copy(target.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Backed up " + target.getName() + " to languages/backup/" + dest.getName());
        } catch (IOException e) {
            logger.warning("Could not back up " + target.getName() + ": " + e.getMessage());
        }
    }

    /** lang-version of the bundled resource, or -1 when absent/unreadable. */
    private int resourceVersion(String name) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) return -1;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getInt("lang-version", 0);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Copies a bundled resource into the plugin data folder, replacing the target. */
    private void copyResource(String name, File target) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                logger.warning("Bundled resource missing: " + name);
                return;
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("Could not extract " + name + ": " + e.getMessage());
        }
    }

    public void reload() {
        load();
        logger.info("Reloaded (" + config.getKeys(false).size() + " keys, locale=" + currentLocale + ")");
    }

    public String get(String key) {
        String raw = config.getString(key);
        if (raw == null) return prefix + "&cMissing: " + key;
        return prefix + color(raw);
    }

    public String get(String key, String... replacements) {
        String msg = get(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    public String getRaw(String key) {
        String raw = config.getString(key);
        return raw == null ? "Missing: " + key : color(raw);
    }

    /** Prefix-free variant with {placeholder} replacement - for titles and action bars. */
    public String getRaw(String key, String... replacements) {
        String msg = getRaw(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    /** Prefix-free list variant - for GUI lore defined in the language file. */
    public List<String> getRawList(String key) {
        List<String> out = new ArrayList<>();
        for (String s : config.getStringList(key)) out.add(color(s));
        return out;
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public YamlConfiguration getConfig() { return config; }
    public int getKeyCount() { return config.getKeys(false).size(); }
    public String getPrefix() { return prefix; }
    public String getCurrentLocale() { return currentLocale; }
}
