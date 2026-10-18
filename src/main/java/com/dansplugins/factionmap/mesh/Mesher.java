package com.dansplugins.factionmap.mesh;

import com.dansplugins.factionmap.surface.SurfaceColumns;

/**
 * Greedy mesher for one chunk's top surface, producing quads in the KFE-map tile format
 * (dir, x, z, y0, du, dv, colour) with x/z local to the chunk. Quads never cross the chunk
 * border, so a region tile is just the concatenation of its chunks' quads (chunk-aligned).
 *
 * dir: 0 = top, 1 = +x, 2 = -x, 3 = +z, 4 = -z. Side faces are emitted by the HIGHER column only,
 * so each chunk owns the faces on its own side of a border. Reused from the RFC 0002 spike. An unknown neighbour edge is treated
 * as a floor (a skirt); it is re-meshed when that neighbour arrives.
 */
public final class Mesher {
    /** Shorts per quad: dir, x, z, y0, du, dv, colour. */
    public static final int STRIDE = 7;

    private Mesher() {}

    /** @param edges neighbour heights: [0]=west neighbour's x=15 column, [1]=east's x=0, [2]=north's z=15, [3]=south's z=0; null = unknown. */
    public static short[] mesh(SurfaceColumns col, short[][] edges) {
        short[] h = col.h, c = col.c, s = col.s;
        int floor = Integer.MAX_VALUE;
        for (short v : h) if (v != SurfaceColumns.NONE) floor = Math.min(floor, v);
        if (floor == Integer.MAX_VALUE) return new short[0];
        floor -= 3;
        QuadBuf out = new QuadBuf();

        // ---- top faces: greedy rectangles of equal (height, colour)
        boolean[] used = new boolean[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                if (used[i] || h[i] == SurfaceColumns.NONE) continue;
                int w = 1;
                while (x + w < 16 && !used[i + w] && h[i + w] == h[i] && c[i + w] == c[i]) w++;
                int d = 1;
                outer:
                while (z + d < 16) {
                    for (int k = 0; k < w; k++) {
                        int j = (z + d) * 16 + x + k;
                        if (used[j] || h[j] != h[i] || c[j] != c[i]) break outer;
                    }
                    d++;
                }
                for (int dz = 0; dz < d; dz++) for (int k = 0; k < w; k++) used[(z + dz) * 16 + x + k] = true;
                out.add(0, x, z, h[i], w, d, c[i]);
            }
        }

        // ---- side faces, two bands (1-block top band in surface colour, body in sub-surface colour),
        //      merged along the plane.
        for (int dir = 1; dir <= 4; dir++) {
            for (int plane = 0; plane < 16; plane++) {   // column index across the plane normal
                int runStart = -1, rY0 = 0, rY1 = 0, rCol = 0;
                for (int band = 0; band < 2; band++) {
                    runStart = -1;
                    for (int along = 0; along <= 16; along++) {
                        int y0 = 0, y1 = 0, colour = 0;
                        boolean has = false;
                        if (along < 16) {
                            int x = (dir <= 2) ? plane : along;
                            int z = (dir <= 2) ? along : plane;
                            int i = z * 16 + x;
                            int hi = h[i] == SurfaceColumns.NONE ? floor : h[i];
                            int lo = neighbour(h, edges, floor, x, z, dir);
                            if (hi > lo) {
                                if (band == 0) { y0 = Math.max(lo, hi - 1); y1 = hi; colour = c[i]; }
                                else { y0 = lo; y1 = hi - 1; colour = s[i]; }
                                has = y1 > y0 && h[i] != SurfaceColumns.NONE;
                            }
                        }
                        if (runStart >= 0 && (!has || y0 != rY0 || y1 != rY1 || colour != rCol)) {
                            emitSide(out, dir, plane, runStart, along - runStart, rY0, rY1, rCol);
                            runStart = -1;
                        }
                        if (has && runStart < 0) { runStart = along; rY0 = y0; rY1 = y1; rCol = colour; }
                    }
                }
            }
        }
        return out.toArray();
    }

    private static void emitSide(QuadBuf out, int dir, int plane, int start, int len, int y0, int y1, int colour) {
        switch (dir) {
            case 1 -> out.add(1, plane + 1, start, y0, len, y1 - y0, colour);  // plane x = column+1, spans z
            case 2 -> out.add(2, plane, start, y0, len, y1 - y0, colour);      // plane x = column
            case 3 -> out.add(3, start, plane + 1, y0, len, y1 - y0, colour);  // plane z = row+1, spans x
            default -> out.add(4, start, plane, y0, len, y1 - y0, colour);
        }
    }

    private static int neighbour(short[] h, short[][] edges, int floor, int x, int z, int dir) {
        int nx = x + (dir == 1 ? 1 : dir == 2 ? -1 : 0);
        int nz = z + (dir == 3 ? 1 : dir == 4 ? -1 : 0);
        short v;
        if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) v = h[nz * 16 + nx];
        else {
            short[] e = switch (dir) { case 1 -> edges[1]; case 2 -> edges[0]; case 3 -> edges[3]; default -> edges[2]; };
            if (e == null) return floor;
            v = e[dir <= 2 ? z : x];
        }
        return v == SurfaceColumns.NONE ? floor : v;
    }

    private static final class QuadBuf {
        short[] a = new short[STRIDE * 64];
        int n;
        void add(int d, int x, int z, int y0, int du, int dv, int colour) {
            if (n + STRIDE > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = (short) d; a[n++] = (short) x; a[n++] = (short) z; a[n++] = (short) y0;
            a[n++] = (short) du; a[n++] = (short) dv; a[n++] = (short) colour;
        }
        short[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
