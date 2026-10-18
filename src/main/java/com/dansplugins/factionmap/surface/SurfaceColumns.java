package com.dansplugins.factionmap.surface;

import java.util.Arrays;

/**
 * The top surface of one chunk: for each of its 256 block columns, the height of the top face,
 * the palette index of the top block, and the palette index of the block beneath it.
 * 256 x 3 shorts, 1.5 KB per chunk. Column index is {@code z * 16 + x}.
 */
public final class SurfaceColumns {
    /** Height of a column with no mappable block at all (void, or nothing but decorations). */
    public static final short NONE = Short.MIN_VALUE;

    public final int cx;
    public final int cz;
    public final short[] h = new short[256];
    public final short[] c = new short[256];
    public final short[] s = new short[256];

    public SurfaceColumns(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    public static int index(int x, int z) {
        return z * 16 + x;
    }

    public boolean sameAs(SurfaceColumns o) {
        return o != null && o.cx == cx && o.cz == cz
                && Arrays.equals(h, o.h) && Arrays.equals(c, o.c) && Arrays.equals(s, o.s);
    }

    /** Heights along one edge: 0 = west (x=0), 1 = east (x=15), 2 = north (z=0), 3 = south (z=15). */
    public short[] edge(int side) {
        short[] e = new short[16];
        for (int k = 0; k < 16; k++) {
            int i = switch (side) {
                case 0 -> k * 16;
                case 1 -> k * 16 + 15;
                case 2 -> k;
                case 3 -> 15 * 16 + k;
                default -> throw new IllegalArgumentException("side " + side);
            };
            e[k] = h[i];
        }
        return e;
    }

    public boolean isEmpty() {
        for (short v : h) {
            if (v != NONE) {
                return false;
            }
        }
        return true;
    }
}
