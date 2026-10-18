package com.dansplugins.factionmap.tiles;

import com.dansplugins.factionmap.factions.FactionLayers;
import com.dansplugins.factionmap.factions.FactionSnapshot;
import com.dansplugins.factionmap.mesh.Mesher;
import com.dansplugins.factionmap.stats.Stats;
import com.dansplugins.factionmap.surface.Palette;
import com.dansplugins.factionmap.surface.SurfaceColumns;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Owns all map state: surface columns and meshes per chunk, the tiles on disk, and the index.
 * Confined to the single mesh thread: every method is called from it, so nothing here is locked.
 *
 * <p>A tile covers 8 x 8 chunks. A changed chunk invalidates its own mesh and, when a shared edge
 * changed, its four neighbours' meshes (side faces depend on neighbour heights); every tile touched is
 * rewritten on the next {@link #flush()}. Meshes are rebuilt lazily at write time from cached columns.
 *
 * <p>Writes only below the data folder it is given ({@code web/tiles/}, {@code cache/}), atomically.
 * Everything it writes is derived: deleting it all rebuilds identical tiles from the world.
 */
public final class TileStore {
    private static final int CACHE_MAGIC = 0x464d4331; // "FMC1"
    private static final int CACHE_ENTRY_BYTES = 8 + 3 * 256 * 2;
    private static final int[][] NEIGHBOURS = {{-1, 0, 0}, {1, 0, 1}, {0, -1, 2}, {0, 1, 3}}; // dx, dz, this chunk's edge

    private final Path tilesDir;
    private final Path cacheDir;
    private final Palette palette;
    private final Stats stats;
    private final Logger log;
    private final LongSupplier clock;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private final Map<Long, SurfaceColumns> columns = new HashMap<>();
    private final Map<Long, short[]> meshes = new HashMap<>();
    private final Set<Long> cacheRead = new HashSet<>();
    private final Map<Long, Long> dirtyTiles = new HashMap<>();        // tile -> earliest change (clock)
    private final Map<Long, Long> blockChangeTiles = new HashMap<>();  // tile -> earliest block-event change
    private final Map<String, Map<String, Object>> tileMeta = new TreeMap<>();
    private final String worldName;
    private FactionSnapshot factions = FactionSnapshot.EMPTY;
    private boolean showHomes;
    private boolean indexDirty = true;
    private volatile int chunkCount;
    private volatile int tileCount;

    public TileStore(Path dataDir, String worldName, Palette palette, Stats stats, Logger log, LongSupplier clock) throws IOException {
        this.tilesDir = dataDir.resolve("web").resolve("tiles");
        this.cacheDir = dataDir.resolve("cache");
        this.worldName = worldName;
        this.palette = palette;
        this.stats = stats;
        this.log = log;
        this.clock = clock;
        Files.createDirectories(tilesDir);
        Files.createDirectories(cacheDir);
        Path paletteFile = cacheDir.resolve("palette.txt");
        if (!Files.exists(paletteFile)) {
            // Tiles and the column cache store palette indices. Without the palette they were written
            // with, they would draw in the wrong colours, so they go too. All of it is rebuilt.
            deleteDerived();
        }
        palette.bind(paletteFile);
        readIndex();
        tileCount = tileMeta.size();
    }

    // ------------------------------------------------------------------ input

    /**
     * Takes a fresh capture of one chunk.
     *
     * @param changedAt clock time of the first block change that led to this capture, or 0 for a plain capture
     * @return true when the chunk's surface changed
     */
    public boolean accept(SurfaceColumns col, long changedAt) {
        long t0 = clock.getAsLong();
        long k = ChunkKeys.key(col.cx, col.cz);
        long tile = ChunkKeys.tileOf(k);
        readCache(tile);
        SurfaceColumns old = columns.get(k);
        if (col.sameAs(old)) {
            stats.record("capture.unchanged", 1);
            return false;
        }
        columns.put(k, col);
        meshes.remove(k);
        markTile(tile, changedAt);
        for (int[] n : NEIGHBOURS) {
            if (old != null && java.util.Arrays.equals(old.edge(n[2]), col.edge(n[2]))) {
                continue;
            }
            long nk = ChunkKeys.key(col.cx + n[0], col.cz + n[1]);
            readCache(ChunkKeys.tileOf(nk));
            if (columns.containsKey(nk)) {
                meshes.remove(nk);
                markTile(ChunkKeys.tileOf(nk), changedAt);
            }
        }
        chunkCount = columns.size();
        stats.record("async.accept.us", (clock.getAsLong() - t0) / 1000);
        return true;
    }

    public void setFactions(FactionSnapshot snapshot, boolean showHomes) {
        if (snapshot.sameAs(factions) && showHomes == this.showHomes) {
            return;
        }
        this.factions = snapshot;
        this.showHomes = showHomes;
        indexDirty = true;
    }

    public boolean hasWork() {
        return !dirtyTiles.isEmpty() || indexDirty;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public int tileCount() {
        return tileCount;
    }

    // ------------------------------------------------------------------ output

    /** Writes every dirty tile, then the index that points at them. */
    public void flush() throws IOException {
        long t0 = clock.getAsLong();
        palette.saveIfGrown(); // before anything that stores the new indices
        List<Long> order = new ArrayList<>(dirtyTiles.keySet());
        order.sort(Long::compare);
        for (long tile : order) {
            writeTile(tile);
            long now = clock.getAsLong();
            stats.record("latency.change_to_tile.ms", (now - dirtyTiles.get(tile)) / 1_000_000);
            Long bc = blockChangeTiles.remove(tile);
            if (bc != null) {
                stats.record("latency.block_event_to_tile.ms", (now - bc) / 1_000_000);
            }
        }
        dirtyTiles.clear();
        writeIndex();
        indexDirty = false;
        tileCount = tileMeta.size();
        if (!order.isEmpty()) {
            stats.record("async.flush.us", (clock.getAsLong() - t0) / 1000);
        }
    }

    private void markTile(long tile, long changedAt) {
        long ts = changedAt == 0 ? clock.getAsLong() : changedAt;
        dirtyTiles.merge(tile, ts, Math::min);
        if (changedAt != 0) {
            blockChangeTiles.merge(tile, changedAt, Math::min);
        }
    }

    private short[] mesh(long k) {
        short[] m = meshes.get(k);
        if (m != null) {
            return m;
        }
        SurfaceColumns col = columns.get(k);
        int cx = ChunkKeys.x(k);
        int cz = ChunkKeys.z(k);
        short[][] edges = new short[4][];
        SurfaceColumns w = neighbour(cx - 1, cz);
        SurfaceColumns e = neighbour(cx + 1, cz);
        SurfaceColumns n = neighbour(cx, cz - 1);
        SurfaceColumns s = neighbour(cx, cz + 1);
        edges[0] = w == null ? null : w.edge(1);
        edges[1] = e == null ? null : e.edge(0);
        edges[2] = n == null ? null : n.edge(3);
        edges[3] = s == null ? null : s.edge(2);
        long t0 = clock.getAsLong();
        m = Mesher.mesh(col, edges);
        stats.record("async.mesh.us", (clock.getAsLong() - t0) / 1000);
        meshes.put(k, m);
        return m;
    }

    private SurfaceColumns neighbour(int cx, int cz) {
        long k = ChunkKeys.key(cx, cz);
        readCache(ChunkKeys.tileOf(k));
        return columns.get(k);
    }

    private void writeTile(long tile) throws IOException {
        long t0 = clock.getAsLong();
        int tx = ChunkKeys.x(tile);
        int tz = ChunkKeys.z(tile);
        String name = ChunkKeys.tileName(tile);
        List<TileCodec.Part> parts = new ArrayList<>();
        List<SurfaceColumns> inTile = new ArrayList<>();
        int hmin = Integer.MAX_VALUE;
        int hmax = Integer.MIN_VALUE;
        for (int dz = 0; dz < ChunkKeys.TILE_CHUNKS; dz++) {
            for (int dx = 0; dx < ChunkKeys.TILE_CHUNKS; dx++) {
                long k = ChunkKeys.key(tx * ChunkKeys.TILE_CHUNKS + dx, tz * ChunkKeys.TILE_CHUNKS + dz);
                SurfaceColumns col = columns.get(k);
                if (col == null) {
                    continue;
                }
                inTile.add(col);
                parts.add(new TileCodec.Part(dx, dz, mesh(k)));
                for (short v : col.h) {
                    if (v != SurfaceColumns.NONE) {
                        hmin = Math.min(hmin, v);
                        hmax = Math.max(hmax, v);
                    }
                }
            }
        }
        Path file = tilesDir.resolve(name + ".bin.gz");
        if (inTile.isEmpty()) {
            Files.deleteIfExists(file);
            tileMeta.remove(name);
            return;
        }
        byte[] raw = TileCodec.encode(tx, tz, parts);
        byte[] gz = TileCodec.gzip(raw);
        AtomicFiles.write(file, gz);
        writeCache(tile, inTile);
        int quads = ByteBuffer.wrap(raw, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tx", tx);
        meta.put("tz", tz);
        meta.put("file", name + ".bin.gz");
        meta.put("version", sha1(gz).substring(0, 12));
        meta.put("quads", quads);
        meta.put("bytes", gz.length);
        meta.put("chunks", inTile.size());
        meta.put("hmin", hmin == Integer.MAX_VALUE ? 64 : hmin);
        meta.put("hmax", hmax == Integer.MIN_VALUE ? 64 : hmax);
        tileMeta.put(name, meta);
        stats.record("async.tile_write.us", (clock.getAsLong() - t0) / 1000);
        stats.record("tile.bytes", gz.length);
    }

    private void writeIndex() throws IOException {
        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("format", 1);
        idx.put("world", worldName);
        idx.put("tileBlocks", ChunkKeys.TILE_BLOCKS);
        idx.put("tiles", tileMeta);
        idx.put("palette", palette.colours());
        idx.put("factions", FactionLayers.factions(factions, showHomes));
        idx.put("claims", factions.claims());
        idx.put("relations", FactionLayers.relations(factions));
        idx.put("generated", System.currentTimeMillis() / 1000);
        AtomicFiles.write(tilesDir.resolve("index.json"), gson.toJson(idx).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private void readIndex() {
        Path idx = tilesDir.resolve("index.json");
        if (!Files.exists(idx)) {
            return;
        }
        try {
            Map<String, Object> old = gson.fromJson(Files.readString(idx, StandardCharsets.UTF_8), Map.class);
            Object t = old == null ? null : old.get("tiles");
            if (t instanceof Map<?, ?> tiles) {
                for (Map.Entry<?, ?> e : tiles.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> m) {
                        Map<String, Object> meta = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> f : m.entrySet()) {
                            // Gson reads every number as a double; the viewer wants integers back.
                            Object v = f.getValue();
                            meta.put(String.valueOf(f.getKey()), v instanceof Double d && d == Math.rint(d) ? (Object) d.longValue() : v);
                        }
                        tileMeta.put(String.valueOf(e.getKey()), meta);
                    }
                }
            }
        } catch (IOException | JsonParseException | ClassCastException e) {
            log.warning("Ignoring unreadable " + idx.getFileName() + " (it is rebuilt): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ column cache

    /** Loads a tile's cached columns once, so a tile holding chunks not loaded this session can be rewritten whole. */
    private void readCache(long tile) {
        if (!cacheRead.add(tile)) {
            return;
        }
        Path p = cacheFile(tile);
        if (!Files.exists(p)) {
            return;
        }
        try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(p), 65536)) {
            ByteBuffer in = ByteBuffer.wrap(gz.readAllBytes());
            if (in.getInt() != CACHE_MAGIC) {
                throw new IOException("not a column cache");
            }
            int count = in.getInt();
            if (count < 0 || count > ChunkKeys.TILE_CHUNKS * ChunkKeys.TILE_CHUNKS || in.remaining() != count * CACHE_ENTRY_BYTES) {
                throw new IOException("bad length");
            }
            int paletteSize = palette.size();
            List<SurfaceColumns> read = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                SurfaceColumns c = new SurfaceColumns(in.getInt(), in.getInt());
                if (ChunkKeys.tileOf(ChunkKeys.key(c.cx, c.cz)) != tile) {
                    throw new IOException("chunk " + c.cx + "," + c.cz + " is not in this tile");
                }
                for (int j = 0; j < 256; j++) {
                    c.h[j] = in.getShort();
                }
                for (int j = 0; j < 256; j++) {
                    c.c[j] = checkIndex(in.getShort(), paletteSize);
                }
                for (int j = 0; j < 256; j++) {
                    c.s[j] = checkIndex(in.getShort(), paletteSize);
                }
                read.add(c);
            }
            for (SurfaceColumns c : read) {
                columns.putIfAbsent(ChunkKeys.key(c.cx, c.cz), c);
            }
            chunkCount = columns.size();
        } catch (IOException e) {
            log.warning("Ignoring unreadable column cache " + p.getFileName() + " (it is rebuilt): " + e.getMessage());
        }
    }

    private static short checkIndex(short v, int paletteSize) throws IOException {
        if (v < 0 || v >= paletteSize) {
            throw new IOException("palette index " + v + " out of range");
        }
        return v;
    }

    private void writeCache(long tile, List<SurfaceColumns> list) throws IOException {
        ByteBuffer out = ByteBuffer.allocate(8 + list.size() * CACHE_ENTRY_BYTES);
        out.putInt(CACHE_MAGIC).putInt(list.size());
        for (SurfaceColumns c : list) {
            out.putInt(c.cx).putInt(c.cz);
            for (short v : c.h) {
                out.putShort(v);
            }
            for (short v : c.c) {
                out.putShort(v);
            }
            for (short v : c.s) {
                out.putShort(v);
            }
        }
        // Level 1: the cache is read once per tile per session, so speed beats size here.
        AtomicFiles.write(cacheFile(tile), TileCodec.gzip(out.array(), 1));
    }

    private Path cacheFile(long tile) {
        return cacheDir.resolve(ChunkKeys.tileName(tile) + ".cols.gz");
    }

    private void deleteDerived() throws IOException {
        deleteMatching(tilesDir, "*.bin.gz");
        deleteMatching(tilesDir, "index.json");
        deleteMatching(cacheDir, "*.cols.gz");
    }

    private static void deleteMatching(Path dir, String glob) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String sha1(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
