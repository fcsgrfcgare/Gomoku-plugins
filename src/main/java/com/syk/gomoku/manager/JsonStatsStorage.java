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
