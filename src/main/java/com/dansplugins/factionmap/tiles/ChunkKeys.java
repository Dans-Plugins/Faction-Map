package com.dansplugins.factionmap.tiles;

/** Packs a pair of ints (chunk or tile coordinates) into one long. */
public final class ChunkKeys {
    /** Chunks per tile side: a tile covers 8 x 8 chunks, 128 x 128 blocks. */
    public static final int TILE_CHUNKS = 8;
    public static final int TILE_SHIFT = 3;
    public static final int TILE_BLOCKS = TILE_CHUNKS * 16;

    private ChunkKeys() {
    }

    public static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }

    /** The key of the tile that holds chunk {@code chunkKey}. */
    public static long tileOf(long chunkKey) {
        return key(x(chunkKey) >> TILE_SHIFT, z(chunkKey) >> TILE_SHIFT);
    }

    public static String tileName(long tileKey) {
        return "t." + x(tileKey) + "." + z(tileKey);
    }
}
