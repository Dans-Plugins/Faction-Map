package com.dansplugins.factionmap.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsFileTest {
    @TempDir
    Path dir;

    private static Map<String, Object> content(String world) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", world);
        m.put("chunksMapped", 12);
        return m;
    }

    @Test
    void writesTheContentAsPrettyPrintedJson() throws IOException {
        StatsFile.write(dir, content("overworld"));
        String text = Files.readString(dir.resolve("stats.json"));
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        assertEquals("overworld", json.get("world").getAsString());
        assertEquals(12, json.get("chunksMapped").getAsInt());
        assertTrue(text.contains("\n"), "one key per line, for people reading the file");
    }

    @Test
    void writesSeriesFromAStatsSummary() throws IOException {
        Stats stats = new Stats();
        stats.record("main.tick.us", 40);
        Map<String, Object> out = content("overworld");
        out.put("stats", stats.summary());
        StatsFile.write(dir, out);
        JsonObject json = JsonParser.parseString(Files.readString(dir.resolve("stats.json"))).getAsJsonObject();
        assertEquals(40, json.getAsJsonObject("stats").getAsJsonObject("main.tick.us").get("max").getAsInt());
    }

    @Test
    void replacesTheFileRatherThanRewritingItInPlace() throws IOException {
        StatsFile.write(dir, content("before"));
        // A hard link keeps the old file's bytes only if the name is moved onto a new file. Rewriting in
        // place, which is what lets a reader see a partial file, would change what the link shows too.
        Path heldByAReader = Files.createLink(dir.resolve("held"), dir.resolve("stats.json"));
        String before = Files.readString(heldByAReader);

        StatsFile.write(dir, content("after"));

        assertEquals(before, Files.readString(heldByAReader));
        assertTrue(Files.readString(dir.resolve("stats.json")).contains("after"));
    }

    @Test
    void leavesOnlyStatsJsonBehind() throws IOException {
        StatsFile.write(dir, content("a"));
        StatsFile.write(dir, content("b"));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("stats.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }
}
