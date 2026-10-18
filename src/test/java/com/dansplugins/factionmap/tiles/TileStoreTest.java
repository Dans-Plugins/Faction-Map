package com.dansplugins.factionmap.tiles;

import com.dansplugins.factionmap.factions.FactionSnapshot;
import com.dansplugins.factionmap.stats.Stats;
import com.dansplugins.factionmap.surface.Palette;
import com.dansplugins.factionmap.surface.SurfaceColumns;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileStoreTest {
    @TempDir
    Path data;
    final AtomicLong clock = new AtomicLong(1_000_000_000L);
    final Stats stats = new Stats();

    TileStore store() throws IOException {
        Palette palette = new Palette(new ByteArrayInputStream("{\"STONE\": \"#7d7d7d\"}".getBytes(StandardCharsets.UTF_8)));
        TileStore s = new TileStore(data, "world", palette, stats, Logger.getLogger("test"), clock::get);
        assertEquals(0, palette.indexOf("minecraft:stone", "STONE"), "the columns below use palette index 0");
        return s;
    }

    static SurfaceColumns chunk(int cx, int cz, int height) {
        SurfaceColumns c = new SurfaceColumns(cx, cz);
        Arrays.fill(c.h, (short) height);
        return c; // palette index 0 everywhere
    }

    Path tile(String name) {
        return data.resolve("web/tiles/" + name + ".bin.gz");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> index() throws IOException {
        return new Gson().fromJson(Files.readString(data.resolve("web/tiles/index.json")), Map.class);
    }

    static byte[] gunzip(byte[] b) throws IOException {
        return new GZIPInputStream(new ByteArrayInputStream(b)).readAllBytes();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCapturedChunkIsWrittenToItsEightByEightTileWithAnIndex() throws IOException {
        TileStore s = store();
        assertTrue(s.accept(chunk(9, -1, 64), 0));
        assertTrue(s.hasWork());
        s.flush();
        assertFalse(s.hasWork());
        assertTrue(Files.exists(tile("t.1.-1")));
        byte[] raw = gunzip(Files.readAllBytes(tile("t.1.-1")));
        assertEquals("KFT1", new String(raw, 0, 4, StandardCharsets.US_ASCII));
        Map<String, Object> idx = index();
        assertEquals(128.0, idx.get("tileBlocks"));
        Map<String, Map<String, Object>> tiles = (Map<String, Map<String, Object>>) idx.get("tiles");
        assertEquals(1.0, tiles.get("t.1.-1").get("chunks"));
        assertEquals(1, tiles.size());
    }

    @Test
    void anUnchangedRecaptureWritesNothing() throws IOException {
        TileStore s = store();
        s.accept(chunk(0, 0, 64), 0);
        s.flush();
        assertFalse(s.accept(chunk(0, 0, 64), 0));
        assertFalse(s.hasWork());
    }

    @Test
    void aChangeOnATileEdgeRewritesTheNeighbouringTileToo() throws IOException {
        TileStore s = store();
        s.accept(chunk(7, 0, 64), 0); // tile 0,0, east edge
        s.accept(chunk(8, 0, 64), 0); // tile 1,0, west edge
        s.flush();
        String v0 = version("t.0.0");
        String v1 = version("t.1.0");
        s.accept(chunk(8, 0, 60), clock.get()); // lower the east chunk: the west chunk now owns a cliff face on its edge
        clock.addAndGet(2_000_000_000L);
        s.flush();
        assertNotEquals(v1, version("t.1.0"));
        assertNotEquals(v0, version("t.0.0"), "the neighbouring tile was re-meshed from cached columns");
        assertTrue(stats.summary().containsKey("latency.block_event_to_tile.ms"));
        assertEquals(2000L, stats.summary().get("latency.block_event_to_tile.ms").get("max"));
    }

    @SuppressWarnings("unchecked")
    String version(String name) throws IOException {
        return (String) ((Map<String, Map<String, Object>>) index().get("tiles")).get(name).get("version");
    }

    @Test
    void aRestartRebuildsByteIdenticalTilesFromTheColumnCache() throws IOException {
        TileStore s = store();
        for (int cx = 0; cx < 8; cx++) {
            s.accept(chunk(cx, 0, 60 + cx), 0);
        }
        s.flush();
        byte[] before = Files.readAllBytes(tile("t.0.0"));

        // Next session: only one chunk of the tile is loaded, and it changes and changes back.
        TileStore again = store();
        assertFalse(again.accept(chunk(3, 0, 63), 0), "the cache already holds this chunk");
        assertTrue(again.accept(chunk(3, 0, 90), 0));
        again.flush();
        assertTrue(again.accept(chunk(3, 0, 63), 0));
        again.flush();
        assertArrayEquals(before, Files.readAllBytes(tile("t.0.0")), "chunks not loaded this session came from the cache");
    }

    @Test
    void losingThePaletteDiscardsEverythingThatUsedIt() throws IOException {
        TileStore s = store();
        s.accept(chunk(0, 0, 64), 0);
        s.flush();
        Files.delete(data.resolve("cache/palette.txt"));
        TileStore again = store();
        assertFalse(Files.exists(tile("t.0.0")));
        assertFalse(Files.exists(data.resolve("cache/t.0.0.cols.gz")));
        assertEquals(0, again.tileCount());
    }

    @Test
    void aCorruptColumnCacheIsIgnoredNotFatal() throws IOException {
        TileStore s = store();
        s.accept(chunk(0, 0, 64), 0);
        s.flush();
        Files.write(data.resolve("cache/t.0.0.cols.gz"), new byte[]{1, 2, 3});
        TileStore again = store();
        assertTrue(again.accept(chunk(1, 0, 64), 0));
        again.flush();
        assertTrue(Files.exists(tile("t.0.0")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void factionLayersGoIntoTheIndexAndAnUnchangedSnapshotDoesNotRewriteIt() throws IOException {
        TileStore s = store();
        s.flush();
        FactionSnapshot snap = new FactionSnapshot(
                List.of(new FactionSnapshot.Faction("a", "Northreach", "#2f6fb0", null),
                        new FactionSnapshot.Faction("b", "Saltmarsh", "#3a9a5b", new int[]{1, 2, 3})),
                List.of(new int[]{0, 0, 0}, new int[]{1, 0, 1}),
                List.of(new FactionSnapshot.Relation(0, 1, "ally")));
        s.setFactions(snap, false);
        assertTrue(s.hasWork());
        s.flush();
        Map<String, Object> idx = index();
        List<Map<String, Object>> f = (List<Map<String, Object>>) idx.get("factions");
        assertEquals("Northreach", f.get(0).get("label"));
        assertFalse(f.get(1).containsKey("home"), "homes stay out unless enabled");
        assertEquals(List.of(List.of(0.0, 1.0, "ally")), idx.get("relations"));
        s.setFactions(new FactionSnapshot(snap.factions(), List.of(new int[]{0, 0, 0}, new int[]{1, 0, 1}), snap.relations()), false);
        assertFalse(s.hasWork());
    }
}
