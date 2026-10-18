package com.syk.gomoku.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.syk.gomoku.model.PlayerStats;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Default storage: stats.json in the plugin data folder. */
public class JsonStatsStorage implements StatsStorage {

    private final File file;
    private final Gson gson = new GsonBuilder()
            .excludeFieldsWithoutExposeAnnotation()
            .setPrettyPrinting()
            .create();

    public JsonStatsStorage(File file) {
        this.file = file;
    }

    @Override
    public List<PlayerStats> load() throws Exception {
        if (!file.exists()) return new ArrayList<>();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            List<PlayerStats> list = gson.fromJson(r, new TypeToken<List<PlayerStats>>(){}.getType());
            return list != null ? list : new ArrayList<>();
        }
    }

    @Override
    public void saveAll(Collection<PlayerStats> stats) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            gson.toJson(new ArrayList<>(stats), w);
        }
    }
}
