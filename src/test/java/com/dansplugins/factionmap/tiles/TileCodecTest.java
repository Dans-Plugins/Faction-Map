package com.dansplugins.factionmap.tiles;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TileCodecTest {
    @Test
    void layoutIsHeaderThenDirectionsThenColumnsWithChunkOffsetsApplied() {
        short[] a = {0, 1, 2, 64, 3, 4, 7};      // one top quad at (1,2) in chunk (0,0)
        short[] b = {2, 5, 6, 60, 1, 2, 9};      // one -x side quad at (5,6) in chunk (2,1)
        byte[] raw = TileCodec.encode(-1, 3, List.of(new TileCodec.Part(0, 0, a), new TileCodec.Part(2, 1, b)));
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals('K', bb.get(0));
        assertEquals('1', bb.get(3));
        assertEquals(2, bb.getInt(4));
        assertEquals(-1, bb.getInt(8));
        assertEquals(3, bb.getInt(12));
        assertEquals(0, raw[16]);
        assertEquals(2, raw[17]);
        int off = 18; // two dirs, even count: no pad
        short[] xs = {bb.getShort(off), bb.getShort(off + 2)};
        short[] zs = {bb.getShort(off + 4), bb.getShort(off + 6)};
        assertArrayEquals(new short[]{1, 5 + 32}, xs);
        assertArrayEquals(new short[]{2, 6 + 16}, zs);
        assertEquals(16 + 2 + 12 * 2, raw.length);
    }

    @Test
    void anOddQuadCountIsPaddedToAlignTheShorts() {
        byte[] raw = TileCodec.encode(0, 0, List.of(new TileCodec.Part(0, 0, new short[]{0, 0, 0, 1, 1, 1, 1})));
        assertEquals(16 + 1 + 1 + 12, raw.length);
    }

    @Test
    void gzipIsDeterministic() {
        byte[] raw = TileCodec.encode(0, 0, List.of(new TileCodec.Part(0, 0, new short[]{0, 0, 0, 1, 1, 1, 1})));
        assertArrayEquals(TileCodec.gzip(raw), TileCodec.gzip(raw));
    }

    @Test
    void chunkKeysPackSignedCoordinatesAndTilesAreEightChunksWide() {
        long k = ChunkKeys.key(-5, 7);
        assertEquals(-5, ChunkKeys.x(k));
        assertEquals(7, ChunkKeys.z(k));
        assertEquals(ChunkKeys.key(-1, 0), ChunkKeys.tileOf(k));
        assertEquals(ChunkKeys.key(0, 0), ChunkKeys.tileOf(ChunkKeys.key(7, 7)));
        assertEquals(ChunkKeys.key(1, 0), ChunkKeys.tileOf(ChunkKeys.key(8, 0)));
        assertEquals("t.-1.0", ChunkKeys.tileName(ChunkKeys.key(-1, 0)));
    }
}
