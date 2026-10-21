package com.dansplugins.factionmap.tiles;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkKeysTest {
    @Test
    void keyRoundTripsEverySignAndTheIntExtremes() {
        int[] values = {0, 1, -1, 7, -8, 1875000, -1875000, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : values) {
            for (int z : values) {
                long k = ChunkKeys.key(x, z);
                assertEquals(x, ChunkKeys.x(k), "x of (" + x + "," + z + ")");
                assertEquals(z, ChunkKeys.z(k), "z of (" + x + "," + z + ")");
            }
        }
    }

    @Test
    void aNegativeZDoesNotBleedIntoX() {
        assertEquals(0, ChunkKeys.x(ChunkKeys.key(0, -1)));
        assertEquals(5, ChunkKeys.x(ChunkKeys.key(5, Integer.MIN_VALUE)));
    }

    @Test
    void distinctPairsGiveDistinctKeys() {
        Set<Long> keys = new HashSet<>();
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                keys.add(ChunkKeys.key(x, z));
            }
        }
        assertEquals(41 * 41, keys.size());
    }

    @Test
    void aTileCoversEightByEightChunksOf128Blocks() {
        assertEquals(ChunkKeys.TILE_CHUNKS, 1 << ChunkKeys.TILE_SHIFT);
        assertEquals(128, ChunkKeys.TILE_BLOCKS);
    }

    @Test
    void tileOfFloorsSoNegativeChunksFallInNegativeTiles() {
        assertEquals(ChunkKeys.key(0, 0), ChunkKeys.tileOf(ChunkKeys.key(0, 0)));
        assertEquals(ChunkKeys.key(0, 0), ChunkKeys.tileOf(ChunkKeys.key(7, 7)));
        assertEquals(ChunkKeys.key(1, 0), ChunkKeys.tileOf(ChunkKeys.key(8, 0)));
        assertEquals(ChunkKeys.key(-1, -1), ChunkKeys.tileOf(ChunkKeys.key(-1, -1)));
        assertEquals(ChunkKeys.key(-1, 0), ChunkKeys.tileOf(ChunkKeys.key(-8, 3)));
        assertEquals(ChunkKeys.key(-2, 2), ChunkKeys.tileOf(ChunkKeys.key(-9, 16)));
    }

    @Test
    void everyChunkOfATileMapsBackToIt() {
        long tile = ChunkKeys.key(-3, 4);
        for (int dz = 0; dz < ChunkKeys.TILE_CHUNKS; dz++) {
            for (int dx = 0; dx < ChunkKeys.TILE_CHUNKS; dx++) {
                long chunk = ChunkKeys.key(-3 * ChunkKeys.TILE_CHUNKS + dx, 4 * ChunkKeys.TILE_CHUNKS + dz);
                assertEquals(tile, ChunkKeys.tileOf(chunk));
            }
        }
    }

    @Test
    void tileNameIsTheTXZFormTheViewerFetches() {
        assertEquals("t.0.0", ChunkKeys.tileName(ChunkKeys.key(0, 0)));
        assertEquals("t.-2.15", ChunkKeys.tileName(ChunkKeys.key(-2, 15)));
        assertEquals("t.3.-1", ChunkKeys.tileName(ChunkKeys.key(3, -1)));
    }
}
