package com.dansplugins.factionmap;

import com.dansplugins.factionmap.capture.CaptureScheduler;
import com.dansplugins.factionmap.capture.LoadedChunkReader;
import com.dansplugins.factionmap.factions.FactionSnapshot;
import com.dansplugins.factionmap.factions.MfReader;
import com.dansplugins.factionmap.stats.Stats;
import com.dansplugins.factionmap.surface.Palette;
import com.dansplugins.factionmap.surface.SurfaceColumns;
import com.dansplugins.factionmap.tiles.TileStore;
import com.dansplugins.factionmap.trace.TraceClient;
import com.dansplugins.factionmap.web.StaticWebServer;
import com.google.gson.GsonBuilder;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Faction Map: a read-only 3D map of the loaded world's surface, with Medieval Factions territories,
 * borders, labels and relations drawn over it.
 *
 * <p>Threads: capture runs on the main thread inside a per-tick budget ({@link CaptureScheduler}); meshing,
 * tile writing and the index run on one background thread that owns the {@link TileStore}; the web
 * server has its own two threads and only reads files.
 *
 * <p>Read-only: no world writes, no chunk loading, MF through getters only, and files written only under
 * this plugin's data folder. See {@link LoadedChunkReader} and {@code scripts/check_read_only.py}.
 */
public final class FactionMap extends JavaPlugin {
    private static final String[] WEB_ASSETS = {"web/index.html", "web/app.js", "web/style.css", "web/vendor/three.min.js"};

    private final Stats stats = new Stats();
    private ScheduledExecutorService worker;
    private TileStore store;
    private LoadedChunkReader reader;
    private CaptureScheduler scheduler;
    private StaticWebServer web;
    private MfReader mf;
    private BukkitTask tickTask;
    private TraceClient trace = TraceClient.disabled();
    private UUID worldId;

    private long eventNanos;
    private long lastMfRead;
    private long mfPollNanos;
    private boolean showNames;
    private boolean showHomes;
    private volatile int pendingCaptures;
    private volatile int loadedChunks;
    private String webUrl = "off";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        trace = UsageReporting.start(this);

        String wanted = getConfig().getString("world", "world");
        World world = getServer().getWorld(wanted);
        if (world == null) {
            world = getServer().getWorlds().get(0);
        }
        showNames = getConfig().getBoolean("factions.showNames", true);
        showHomes = getConfig().getBoolean("factions.showHomes", false);
        mfPollNanos = Math.max(1, getConfig().getLong("factions.pollSeconds", 30)) * 1_000_000_000L;

        Path data = getDataFolder().toPath();
        try {
            for (String asset : WEB_ASSETS) {
                extract(asset, data.resolve(asset));
            }
            Palette palette;
            try (InputStream in = getResource("colors.json")) {
                palette = new Palette(in);
            }
            reader = new LoadedChunkReader(world, palette);
            if (!reader.worldName().equals(wanted)) {
                getLogger().warning("World '" + wanted + "' not found; mapping '" + reader.worldName() + "' instead.");
            }
            worldId = reader.worldId();
            store = new TileStore(data, reader.worldName(), palette, stats, getLogger(), System::nanoTime);
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Faction Map could not start; disabling it.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "faction-map-mesh");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker = exec;

        scheduler = new CaptureScheduler(System::nanoTime, this::capture,
                getConfig().getLong("capture.mainThreadBudgetMicros", 500),
                getConfig().getLong("capture.debounceMillis", 1000),
                getConfig().getLong("capture.maxDelayMillis", 5000),
                getConfig().getDouble("capture.sweepChunksPerSecond", 2));

        Plugin mfPlugin = getServer().getPluginManager().getPlugin("MedievalFactions");
        if (mfPlugin != null && mfPlugin.isEnabled()) {
            try {
                mf = new MfReader(mfPlugin);
                getServer().getPluginManager().registerEvents(mf, this);
                getLogger().info("Medieval Factions " + mfPlugin.getDescription().getVersion()
                        + " found: territory, border, label and relation layers are on.");
            } catch (LinkageError | RuntimeException e) {
                mf = null;
                getLogger().log(Level.WARNING, "This Medieval Factions version is not supported; showing terrain only.", e);
            }
        } else {
            getLogger().info("Medieval Factions not found: showing terrain only.");
        }

        getServer().getPluginManager().registerEvents(new ChangeListener(this, reader), this);
        List<Long> loaded = reader.loadedChunkKeys();
        for (long k : loaded) {
            scheduler.chunkLoaded(k);
        }
        warmUp(loaded);
        tickTask = getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        worker.scheduleWithFixedDelay(this::flush, 500, 500, TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(this::writeStats, 5, 5, TimeUnit.SECONDS);

        if (getConfig().getBoolean("web.enabled", true)) {
            String host = getConfig().getString("web.host", "127.0.0.1");
            int port = getConfig().getInt("web.port", 8199);
            try {
                web = new StaticWebServer(host, port, data.resolve("web"), n -> stats.record("http.bytes", n));
                web.start();
                webUrl = "http://" + host + ":" + port + "/";
                getLogger().info("Map viewer at " + webUrl);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "The map's web server could not listen on " + host + ":" + port
                        + "; tiles are still written to " + data.resolve("web"), e);
            }
        }
        trace.report("startup");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (web != null) {
            web.stop();
        }
        if (worker != null) {
            worker.execute(this::flush);
            worker.shutdown();
            try {
                if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().warning("Tile writer did not finish within 10 s; the next start rebuilds what it missed.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        trace.close();
    }

    // ------------------------------------------------------------------ main thread

    /**
     * The first capture after boot pays for class loading and JIT (the spike measured a 212 ms tick).
     * A few captures here, during enable, take that cost before the first tick is timed. Their results
     * are used normally.
     */
    private void warmUp(List<Long> loaded) {
        long t0 = System.nanoTime();
        int n = 0;
        for (long k : loaded) {
            if (n >= 8 || System.nanoTime() - t0 > 250_000_000L) {
                break;
            }
            SurfaceColumns col = reader.capture(k);
            if (col != null) {
                worker.execute(() -> accept(col, 0));
                n++;
            }
        }
        getLogger().fine("Warm-up: " + n + " captures in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }

    CaptureScheduler scheduler() {
        return scheduler;
    }

    /** Time spent in this plugin's event handlers, charged to the current tick. */
    void chargeEvent(long nanos) {
        eventNanos += nanos;
    }

    private void tick() {
        long t0 = System.nanoTime();
        long spent = eventNanos;
        eventNanos = 0;
        if (mf != null && (mf.takeDirty() || t0 - lastMfRead >= mfPollNanos)) {
            lastMfRead = t0;
            readFactions();
        }
        scheduler.tick(spent + (System.nanoTime() - t0));
        long total = spent + (System.nanoTime() - t0);
        stats.record("main.tick.us", total / 1000);
        pendingCaptures = scheduler.pending();
        loadedChunks = scheduler.loadedCount();
    }

    private void readFactions() {
        long m0 = System.nanoTime();
        try {
            FactionSnapshot snapshot = mf.read(worldId, showNames, showHomes);
            boolean homes = showHomes;
            worker.execute(() -> store.setFactions(snapshot, homes));
        } catch (LinkageError | RuntimeException e) {
            getLogger().log(Level.WARNING, "Reading Medieval Factions failed; faction layers are off until restart.", e);
            mf = null;
        }
        stats.record("main.mf_read.us", (System.nanoTime() - m0) / 1000);
    }

    private boolean capture(long chunkKey, long changedAt) {
        long c0 = System.nanoTime();
        SurfaceColumns col = reader.capture(chunkKey);
        if (col == null) {
            return false;
        }
        stats.record("main.capture.us", (System.nanoTime() - c0) / 1000);
        worker.execute(() -> accept(col, changedAt));
        return true;
    }

    /** Captures a chunk outside the tick loop: used for a pending change on a chunk that is unloading. */
    void captureNow(long chunkKey, long changedAt) {
        capture(chunkKey, changedAt);
    }

    // ------------------------------------------------------------------ worker thread

    private void accept(SurfaceColumns col, long changedAt) {
        try {
            store.accept(col, changedAt);
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not mesh chunk " + col.cx + "," + col.cz, e);
        }
    }

    private void flush() {
        try {
            if (store.hasWork()) {
                store.flush();
            }
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.WARNING, "Writing map tiles failed; retrying in 0.5 s.", e);
        }
    }

    private void writeStats() {
        try {
            Map<String, Object> out = status();
            out.put("stats", stats.summary());
            Path p = getDataFolder().toPath().resolve("stats.json");
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.FINE, "stats.json not written", e);
        }
    }

    private Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", reader.worldName());
        m.put("chunksMapped", store.chunkCount());
        m.put("tiles", store.tileCount());
        m.put("chunksLoaded", loadedChunks);
        m.put("pendingCaptures", pendingCaptures);
        m.put("medievalFactions", mf != null);
        m.put("web", webUrl);
        return m;
    }

    private void extract(String resource, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        try (InputStream in = getResource(resource)) {
            if (in == null) {
                throw new IOException("missing resource " + resource);
            }
            Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        trace.report("command", 1.0, Collections.singletonMap("name", "fmap " + sub));
        switch (sub) {
            case "status" -> {
                Map<String, Object> s = status();
                sender.sendMessage("Faction Map: " + s.get("chunksMapped") + " chunks mapped in " + s.get("tiles")
                        + " tiles of '" + s.get("world") + "'; " + s.get("chunksLoaded") + " loaded, "
                        + s.get("pendingCaptures") + " waiting. Medieval Factions: "
                        + (mf != null ? "on" : "off") + ". Viewer: " + s.get("web"));
            }
            case "stats" -> {
                Map<String, Map<String, Object>> sum = stats.summary();
                if (args.length > 1 && args[1].equalsIgnoreCase("json")) {
                    sender.sendMessage(new GsonBuilder().create().toJson(sum));
                    return true;
                }
                for (String key : List.of("main.tick.us", "main.capture.us", "main.mf_read.us",
                        "async.tile_write.us", "latency.block_event_to_tile.ms")) {
                    Map<String, Object> v = sum.get(key);
                    if (v != null) {
                        sender.sendMessage(key + ": n=" + v.get("count") + " mean=" + v.get("mean") + " p50=" + v.get("p50")
                                + " p95=" + v.get("p95") + " p99=" + v.get("p99") + " max=" + v.get("max"));
                    }
                }
            }
            case "reset" -> {
                stats.reset();
                sender.sendMessage("Faction Map: performance figures reset.");
            }
            case "recapture" -> sender.sendMessage("Faction Map: " + scheduler.requeueLoaded() + " loaded chunks queued.");
            default -> {
                return false;
            }
        }
        return true;
    }
}
