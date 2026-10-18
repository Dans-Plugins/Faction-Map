package com.dansplugins.factionmap.surface;

import com.dansplugins.factionmap.tiles.AtomicFiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block id to colour index. Indices are baked into tiles and the column cache, so they must mean the
 * same thing after a restart: the palette is persisted by namespaced id and only ever grows. A block
 * that is renamed or added in a new Minecraft version gets a new entry and leaves old ones valid.
 *
 * <p>Thread-safe: capture (main thread) adds entries, the tile writer (mesh thread) reads them.
 */
public final class Palette {
    private static final Pattern ENTRY = Pattern.compile("\"([A-Z0-9_]+)\"\\s*:\\s*\"(#[0-9a-f]{6})\"");
    private static final Pattern HEX = Pattern.compile("#[0-9a-f]{6}");

    private final Map<String, String> colourByName = new HashMap<>();
    private final Map<String, Integer> indexById = new HashMap<>();
    private final List<String> ids = new ArrayList<>();
    private final List<String> colours = new ArrayList<>();
    private Path file;
    private int saved;

    /** @param colourTable JSON object of {@code "BLOCK_NAME": "#rrggbb"} (the bundled colors.json) */
    public Palette(InputStream colourTable) throws IOException {
        String json = new String(colourTable.readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = ENTRY.matcher(json);
        while (m.find()) {
            colourByName.put(m.group(1), m.group(2));
        }
    }

    /** Loads the persisted palette, if any, and remembers where to save it. */
    public synchronized void bind(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.trim().split(" ");
            if (p.length != 2 || !HEX.matcher(p[1]).matches() || indexById.containsKey(p[0])) {
                continue;
            }
            indexById.put(p[0], ids.size());
            ids.add(p[0]);
            colours.add(p[1]);
        }
        saved = ids.size();
    }

    /**
     * @param id   namespaced block id, e.g. {@code minecraft:oak_leaves} (the persisted key)
     * @param name enum-style name used to look up the colour, e.g. {@code OAK_LEAVES}
     */
    public synchronized int indexOf(String id, String name) {
        Integer i = indexById.get(id);
        if (i != null) {
            return i;
        }
        String hex = colourByName.get(name);
        if (hex == null) {
            hex = fallbackColour(name);
        }
        int idx = ids.size();
        indexById.put(id, idx);
        ids.add(id);
        colours.add(hex);
        return idx;
    }

    public synchronized List<String> colours() {
        return new ArrayList<>(colours);
    }

    public synchronized int size() {
        return ids.size();
    }

    /** Writes the palette when it has grown since the last save. Call before writing anything that uses the new entries. */
    public synchronized void saveIfGrown() throws IOException {
        if (file == null || ids.size() == saved) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            sb.append(ids.get(i)).append(' ').append(colours.get(i)).append('\n');
        }
        AtomicFiles.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        saved = ids.size();
    }

    /** A stable muted colour for a block the colour table does not know. */
    static String fallbackColour(String name) {
        int h = name.toUpperCase(Locale.ROOT).hashCode();
        float hue = ((h >>> 8) & 0xFFFF) / 65535f;
        return Colours.hsb(hue, 0.35f, 0.65f);
    }
}
