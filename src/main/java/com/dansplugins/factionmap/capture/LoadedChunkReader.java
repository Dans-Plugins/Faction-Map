package com.dansplugins.factionmap.capture;

import com.dansplugins.factionmap.surface.Palette;
import com.dansplugins.factionmap.surface.SurfaceColumns;
import com.dansplugins.factionmap.surface.SurfaceFilter;
import com.dansplugins.factionmap.tiles.ChunkKeys;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The only class in the plugin that touches {@link World}, {@link Chunk}, {@link Block} or {@link BlockState}.
 * The CI scan of the jar's bytecode enforces that, and that every call made here is a read.
 *
 * <p>Read-only by construction: it reads chunks that are already loaded and checks
 * {@link World#isChunkLoaded(int, int)} before every capture, so it never loads or generates a chunk.
 * It reads the {@code WORLD_SURFACE} heightmap plus the one to three blocks per column the map needs.
 * Main thread only.
 */
public final class LoadedChunkReader {
    /** Returned by the key methods for anything outside the mapped world. */
    public static final long NOT_MAPPED = Long.MIN_VALUE;
    /** Deepest the reader looks below the heightmap for a mappable block, so one column has a bounded cost. */
    static final int MAX_DESCENT = 64;
    private static final int SKIP = -1;

    private final World world;
    private final int minY;
    private final Palette palette;
    private final Map<Material, Integer> codes = new HashMap<>();

    public LoadedChunkReader(World world, Palette palette) {
        this.world = world;
        this.minY = world.getMinHeight();
        this.palette = palette;
    }

    public String worldName() {
        return world.getName();
    }

    public UUID worldId() {
        return world.getUID();
    }

    public long keyOf(Chunk chunk) {
        return chunk.getWorld() == world ? ChunkKeys.key(chunk.getX(), chunk.getZ()) : NOT_MAPPED;
    }

    public long keyOf(Block block) {
        return block.getWorld() == world ? ChunkKeys.key(block.getX() >> 4, block.getZ() >> 4) : NOT_MAPPED;
    }

    public long keyOf(BlockState state) {
        return state.getWorld() == world ? ChunkKeys.key(state.getX() >> 4, state.getZ() >> 4) : NOT_MAPPED;
    }

    public List<Long> loadedChunkKeys() {
        Chunk[] chunks = world.getLoadedChunks();
        List<Long> out = new ArrayList<>(chunks.length);
        for (Chunk c : chunks) {
            out.add(ChunkKeys.key(c.getX(), c.getZ()));
        }
        return out;
    }

    /** @return the chunk's surface, or null when the chunk is not loaded (it is never loaded for this) */
    public SurfaceColumns capture(long chunkKey) {
        int cx = ChunkKeys.x(chunkKey);
        int cz = ChunkKeys.z(chunkKey);
        if (!world.isChunkLoaded(cx, cz)) {
            return null;
        }
        SurfaceColumns col = new SurfaceColumns(cx, cz);
        int bx = cx << 4;
        int bz = cz << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = SurfaceColumns.index(x, z);
                int top = world.getHighestBlockYAt(bx + x, bz + z, HeightMap.WORLD_SURFACE);
                int floor = Math.max(minY, top - MAX_DESCENT);
                int y = top;
                int code = SKIP;
                while (y >= floor) {
                    code = code(world.getType(bx + x, y, bz + z));
                    if (code != SKIP) {
                        break;
                    }
                    y--;
                }
                if (code == SKIP) {
                    col.h[i] = SurfaceColumns.NONE;
                    continue;
                }
                col.h[i] = (short) (y + 1);
                col.c[i] = (short) code;
                int below = y - 1 >= minY ? code(world.getType(bx + x, y - 1, bz + z)) : SKIP;
                col.s[i] = (short) (below == SKIP ? code : below);
            }
        }
        return col;
    }

    private int code(Material m) {
        Integer c = codes.get(m);
        if (c == null) {
            // The namespaced id is derived from the enum name (exact for every vanilla block) rather than read
            // with Material.getKey(), which newer APIs deprecate.
            String name = m.name();
            c = SurfaceFilter.skip(name, m.isAir(), m.isSolid()) ? SKIP
                    : palette.indexOf("minecraft:" + name.toLowerCase(Locale.ROOT), name);
            codes.put(m, c);
        }
        return c;
    }
}
