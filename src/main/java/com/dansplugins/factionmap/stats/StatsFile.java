package com.dansplugins.factionmap.stats;

import com.dansplugins.factionmap.tiles.AtomicFiles;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes {@code stats.json} into the plugin's data folder. Scripts and the smoke gate read that file
 * while it is being replaced, so it goes through {@link AtomicFiles} and they never see part of it.
 */
public final class StatsFile {
    public static final String NAME = "stats.json";

    private StatsFile() {
    }

    public static void write(Path dataFolder, Map<String, Object> content) throws IOException {
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(content);
        AtomicFiles.write(dataFolder.resolve(NAME), json.getBytes(StandardCharsets.UTF_8));
    }
}
