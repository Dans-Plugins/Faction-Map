package com.dansplugins.factionmap.surface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceFilterTest {
    @Test
    void airIsLookedThrough() {
        assertTrue(SurfaceFilter.skip("AIR", true, false));
        assertTrue(SurfaceFilter.skip("CAVE_AIR", true, false));
    }

    @Test
    void waterAndLavaAreSurfacesEvenThoughNotSolid() {
        assertFalse(SurfaceFilter.skip("WATER", false, false));
        assertFalse(SurfaceFilter.skip("LAVA", false, false));
    }

    @Test
    void decorationsAreLookedThrough() {
        for (String n : new String[]{"SHORT_GRASS", "TORCH", "OAK_SAPLING", "STONE_BUTTON", "OAK_PRESSURE_PLATE",
                "RED_CARPET", "SOUL_WALL_TORCH", "SNOW", "RAIL"}) {
            assertTrue(SurfaceFilter.skip(n, false, true), n);
        }
    }

    @Test
    void nonSolidBlocksAreLookedThroughAndSolidOnesKept() {
        assertTrue(SurfaceFilter.skip("POPPY", false, false));
        assertTrue(SurfaceFilter.skip("OAK_SIGN", false, false));
        assertFalse(SurfaceFilter.skip("STONE", false, true));
        assertFalse(SurfaceFilter.skip("OAK_LEAVES", false, true));
    }
}
