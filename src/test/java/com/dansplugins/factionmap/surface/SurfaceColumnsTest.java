package com.dansplugins.factionmap.surface;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceColumnsTest {
    /** Each column's height encodes its position, so an edge read from the wrong side is visible. */
    static SurfaceColumns numbered(int cx, int cz) {
        SurfaceColumns c = new SurfaceColumns(cx, cz);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                c.h[SurfaceColumns.index(x, z)] = (short) (x * 100 + z);
            }
        }
        return c;
    }

    @Test
    void columnIndexIsZMajor() {
        assertEquals(0, SurfaceColumns.index(0, 0));
        assertEquals(15, SurfaceColumns.index(15, 0));
        assertEquals(16, SurfaceColumns.index(0, 1));
        assertEquals(255, SurfaceColumns.index(15, 15));
    }

    @Test
    void edgesAreReadAlongTheNamedSideInIncreasingCoordinateOrder() {
        SurfaceColumns c = numbered(0, 0);
        short[] west = new short[16];
        short[] east = new short[16];
        short[] north = new short[16];
        short[] south = new short[16];
        for (int k = 0; k < 16; k++) {
            west[k] = (short) k;               // x = 0, z = k
            east[k] = (short) (1500 + k);      // x = 15, z = k
            north[k] = (short) (k * 100);      // x = k, z = 0
            south[k] = (short) (k * 100 + 15); // x = k, z = 15
        }
        assertArrayEquals(west, c.edge(0));
        assertArrayEquals(east, c.edge(1));
        assertArrayEquals(north, c.edge(2));
        assertArrayEquals(south, c.edge(3));
    }

    @Test
    void anEdgeIsACopyNotAView() {
        SurfaceColumns c = numbered(0, 0);
        c.edge(0)[0] = 999;
        assertEquals(0, c.h[0]);
    }

    @Test
    void anUnknownSideIsRejected() {
        SurfaceColumns c = new SurfaceColumns(0, 0);
        assertThrows(IllegalArgumentException.class, () -> c.edge(4));
        assertThrows(IllegalArgumentException.class, () -> c.edge(-1));
    }

    @Test
    void sameAsComparesPositionAndAllThreeLayers() {
        SurfaceColumns a = numbered(2, -3);
        assertTrue(a.sameAs(numbered(2, -3)));
        assertFalse(a.sameAs(null));
        assertFalse(a.sameAs(numbered(3, -3)));
        assertFalse(a.sameAs(numbered(2, 3)));

        SurfaceColumns height = numbered(2, -3);
        height.h[200] = 1;
        assertFalse(a.sameAs(height));

        SurfaceColumns colour = numbered(2, -3);
        colour.c[7] = 4;
        assertFalse(a.sameAs(colour));

        SurfaceColumns beneath = numbered(2, -3);
        beneath.s[255] = 4;
        assertFalse(a.sameAs(beneath));
    }

    @Test
    void aChunkIsEmptyOnlyWhenEveryColumnHasNoHeight() {
        SurfaceColumns c = new SurfaceColumns(0, 0);
        assertFalse(c.isEmpty(), "a fresh chunk has height 0 everywhere, which is a real height");
        Arrays.fill(c.h, SurfaceColumns.NONE);
        assertTrue(c.isEmpty());
        c.h[SurfaceColumns.index(9, 9)] = -64;
        assertFalse(c.isEmpty());
    }
}
