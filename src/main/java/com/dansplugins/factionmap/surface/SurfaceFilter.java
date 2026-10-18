package com.dansplugins.factionmap.surface;

import java.util.Set;

/**
 * Decides which blocks the map treats as the surface. Small non-full blocks (grass, flowers,
 * torches, carpets, rails...) would speckle the terrain, so they are looked through; water and
 * lava are kept. Works on block names and two flags so it can be tested without a server.
 */
public final class SurfaceFilter {
    private SurfaceFilter() {
    }

    // Names are compared as strings, never as Material constants: some of these only exist on newer
    // Minecraft versions, and the plugin must load on every version it declares.
    private static final Set<String> DECORATIONS = Set.of(
            "SHORT_GRASS", "GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT",
            "BUSH", "FIREFLY_BUSH", "LEAF_LITTER", "DEAD_BUSH", "SNOW", "VINE", "GLOW_LICHEN", "SWEET_BERRY_BUSH",
            "WILDFLOWERS", "PINK_PETALS", "LILY_PAD", "COBWEB", "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "SUGAR_CANE",
            "TORCH", "WALL_TORCH", "REDSTONE_WIRE", "RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL",
            "TRIPWIRE", "HANGING_ROOTS", "SPORE_BLOSSOM", "BIG_DRIPLEAF", "SMALL_DRIPLEAF", "MOSS_CARPET", "LEVER");

    /**
     * @param name  the block's enum-style name, e.g. {@code OAK_LEAVES}
     * @param air   whether the block is air of any kind
     * @param solid whether the block is solid (Bukkit's {@code Material.isSolid})
     * @return true when the map looks through this block to the one below
     */
    public static boolean skip(String name, boolean air, boolean solid) {
        if (air) {
            return true;
        }
        if (name.equals("WATER") || name.equals("LAVA")) {
            return false;
        }
        if (DECORATIONS.contains(name) || name.endsWith("_SAPLING") || name.endsWith("_BUTTON")
                || name.endsWith("_PRESSURE_PLATE") || name.endsWith("_CARPET") || name.endsWith("_WALL_TORCH")) {
            return true;
        }
        return !solid; // flowers, signs, ladders, banners...
    }
}
