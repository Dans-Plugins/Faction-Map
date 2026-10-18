package com.dansplugins.factionmap.mesh;

import com.dansplugins.factionmap.surface.SurfaceColumns;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MesherTest {
    static SurfaceColumns flat(int cx, int cz, int height, int colour) {
        SurfaceColumns c = new SurfaceColumns(cx, cz);
        Arrays.fill(c.h, (short) height);
        Arrays.fill(c.c, (short) colour);
        Arrays.fill(c.s, (short) colour);
        return c;
    }

    static short[] flatEdge(int height) {
        short[] e = new short[16];
        Arrays.fill(e, (short) height);
        return e;
    }

    static int count(short[] mesh, int dir) {
        int n = 0;
        for (int q = 0; q < mesh.length; q += Mesher.STRIDE) {
            if (mesh[q] == dir) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aFlatChunkWithKnownFlatNeighboursIsOneQuad() {
        short[][] edges = {flatEdge(64), flatEdge(64), flatEdge(64), flatEdge(64)};
        short[] m = Mesher.mesh(flat(0, 0, 64, 3), edges);
        assertEquals(Mesher.STRIDE, m.length);
        assertEquals(0, m[0]);
        assertEquals(16, m[4], "du");
        assertEquals(16, m[5], "dv");
        assertEquals(64, m[3], "y");
        assertEquals(3, m[6], "colour");
    }

    @Test
    void unknownNeighboursGetSkirtsOnAllFourSides() {
        short[] m = Mesher.mesh(flat(0, 0, 64, 3), new short[4][]);
        for (int dir = 1; dir <= 4; dir++) {
            assertTrue(count(m, dir) >= 1, "side " + dir);
        }
    }

    @Test
    void onlyTheHigherColumnEmitsTheStepFace() {
        SurfaceColumns c = flat(0, 0, 64, 1);
        for (int z = 0; z < 16; z++) {
            for (int x = 8; x < 16; x++) {
                c.h[SurfaceColumns.index(x, z)] = 70; // east half raised by 6
            }
        }
        short[][] edges = {flatEdge(64), flatEdge(70), flatEdge(64), flatEdge(64)};
        short[] m = Mesher.mesh(c, edges);
        assertEquals(2, count(m, 0), "two top rectangles");
        // the step faces west (-x, dir 2), owned by the x=8 columns, in two bands merged along z
        assertEquals(2, count(m, 2));
        assertEquals(0, count(m, 1), "no +x faces: the east neighbour is level with the raised half");
        int bandHeights = 0;
        for (int q = 0; q < m.length; q += Mesher.STRIDE) {
            if (m[q] == 2) {
                assertEquals(8, m[q + 1], "plane x = 8");
                assertEquals(16, m[q + 4], "merged along the whole edge");
                bandHeights += m[q + 5];
            }
        }
        assertEquals(6, bandHeights, "the bands cover the whole step");
    }

    @Test
    void quadsNeverLeaveTheChunk() {
        SurfaceColumns c = flat(0, 0, 64, 1);
        java.util.Random r = new java.util.Random(42);
        for (int i = 0; i < 256; i++) {
            c.h[i] = (short) (60 + r.nextInt(12));
            c.c[i] = (short) r.nextInt(4);
        }
        short[] m = Mesher.mesh(c, new short[4][]);
        for (int q = 0; q < m.length; q += Mesher.STRIDE) {
            int dir = m[q], x = m[q + 1], z = m[q + 2], du = m[q + 4], dv = m[q + 5];
            if (dir == 0) {
                assertTrue(x >= 0 && z >= 0 && x + du <= 16 && z + dv <= 16);
            } else if (dir <= 2) {
                assertTrue(x >= 0 && x <= 16 && z >= 0 && z + du <= 16);
            } else {
                assertTrue(z >= 0 && z <= 16 && x >= 0 && x + du <= 16);
            }
        }
    }

    @Test
    void anEmptyChunkHasNoQuads() {
        SurfaceColumns c = new SurfaceColumns(0, 0);
        Arrays.fill(c.h, SurfaceColumns.NONE);
        assertEquals(0, Mesher.mesh(c, new short[4][]).length);
    }
}
