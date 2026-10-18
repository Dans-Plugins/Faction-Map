package com.dansplugins.factionmap.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Decides, on the main thread, which chunks to capture this tick, and stops before the tick's budget is spent.
 *
 * <ul>
 *   <li>A chunk that loads and has never been captured is queued.</li>
 *   <li>A block change marks its chunk dirty; once the chunk has been quiet for {@code debounce}, or at most
 *       {@code maxDelay} after its first change, it moves to the priority queue, ahead of loads.</li>
 *   <li>A slow sweep re-queues loaded chunks round-robin, which catches changes that fire no event
 *       ({@code /fill}, WorldEdit, some redstone). An unchanged sweep costs one capture and no tile write.</li>
 * </ul>
 *
 * Every per-event method is O(1). Not thread-safe: main thread only.
 */
public final class CaptureScheduler {
    /** Captures one chunk. Returns false when the chunk is not loaded, so nothing was captured. */
    @FunctionalInterface
    public interface Capturer {
        boolean capture(long chunkKey, long changedAt);
    }

    /** Most unloaded chunks skipped in one tick, so a long stale queue cannot stall a tick by itself. */
    static final int MAX_SKIPS_PER_TICK = 256;

    private final LongSupplier clock;
    private final Capturer capturer;
    private final long budgetNanos;
    private final long debounceNanos;
    private final long maxDelayNanos;
    private final double sweepPerTick;

    private final Set<Long> captured = new HashSet<>();
    private final Map<Long, long[]> dirty = new HashMap<>();              // chunk -> {firstChange, lastChange}
    private final LinkedHashMap<Long, Long> queue = new LinkedHashMap<>(); // chunk -> changedAt (0 = plain capture)
    private final LinkedHashMap<Long, Long> prio = new LinkedHashMap<>();  // debounced block changes
    private final List<Long> loaded = new ArrayList<>();
    private final Map<Long, Integer> loadedPos = new HashMap<>();
    private int sweepPos;
    private double sweepCredit;
    private double captureEstimateNanos = 100_000;

    public CaptureScheduler(LongSupplier clock, Capturer capturer, long budgetMicros, long debounceMillis,
                            long maxDelayMillis, double sweepChunksPerSecond) {
        this.clock = clock;
        this.capturer = capturer;
        this.budgetNanos = Math.max(1, budgetMicros) * 1_000;
        this.debounceNanos = Math.max(0, debounceMillis) * 1_000_000;
        this.maxDelayNanos = Math.max(debounceMillis, maxDelayMillis) * 1_000_000;
        this.sweepPerTick = Math.max(0, sweepChunksPerSecond) / 20.0;
    }

    public void chunkLoaded(long k) {
        if (!loadedPos.containsKey(k)) {
            loadedPos.put(k, loaded.size());
            loaded.add(k);
        }
        if (!captured.contains(k)) {
            queue.putIfAbsent(k, 0L);
        }
    }

    /**
     * @return the time of the first pending block change when the chunk has one that has not been captured yet,
     *         so the caller can capture it now while it is still loaded; otherwise 0
     */
    public long chunkUnloading(long k) {
        Integer pos = loadedPos.remove(k);
        if (pos != null) {
            long last = loaded.remove(loaded.size() - 1);
            if (pos < loaded.size()) {
                loaded.set(pos, last);
                loadedPos.put(last, pos);
            }
        }
        queue.remove(k);
        long[] d = dirty.remove(k);
        Long p = prio.remove(k);
        if (d != null) {
            return d[0];
        }
        return p != null ? Math.max(1, p) : 0;
    }

    public void blockChanged(long k) {
        long now = clock.getAsLong();
        long[] v = dirty.get(k);
        if (v == null) {
            dirty.put(k, new long[]{now, now});
        } else {
            v[1] = now;
        }
    }

    /** Re-queues every loaded chunk (the admin's {@code /fmap recapture}). */
    public int requeueLoaded() {
        for (Long k : loaded) {
            queue.putIfAbsent(k, 0L);
        }
        return queue.size();
    }

    /**
     * Runs one tick's worth of capture.
     *
     * @param alreadySpentNanos main-thread time this plugin already used this tick (event handlers, MF reads)
     * @return captures done
     */
    public int tick(long alreadySpentNanos) {
        long t0 = clock.getAsLong();
        promoteDebounced(t0);
        sweep();
        int done = 0;
        int skips = 0;
        while (!prio.isEmpty() || !queue.isEmpty()) {
            long spent = alreadySpentNanos + (clock.getAsLong() - t0);
            boolean fits = spent + (long) captureEstimateNanos <= budgetNanos;
            // On a server where one capture costs more than the whole budget, still make progress:
            // one capture per tick, but only when the tick is otherwise quiet.
            boolean starving = done == 0 && spent < budgetNanos / 2;
            if (!fits && !starving) {
                break;
            }
            Iterator<Map.Entry<Long, Long>> it = (prio.isEmpty() ? queue : prio).entrySet().iterator();
            Map.Entry<Long, Long> e = it.next();
            it.remove();
            long c0 = clock.getAsLong();
            if (!capturer.capture(e.getKey(), e.getValue())) {
                if (++skips >= MAX_SKIPS_PER_TICK) {
                    break;
                }
                continue;
            }
            captured.add(e.getKey());
            captureEstimateNanos = captureEstimateNanos * 0.9 + (clock.getAsLong() - c0) * 0.1;
            done++;
        }
        return done;
    }

    private void promoteDebounced(long now) {
        for (Iterator<Map.Entry<Long, long[]>> it = dirty.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, long[]> e = it.next();
            long[] v = e.getValue();
            if (now - v[1] >= debounceNanos || now - v[0] >= maxDelayNanos) {
                queue.remove(e.getKey());
                prio.merge(e.getKey(), v[0], Math::min);
                it.remove();
            }
        }
    }

    private void sweep() {
        sweepCredit += sweepPerTick;
        while (sweepCredit >= 1) {
            sweepCredit -= 1;
            if (loaded.isEmpty()) {
                continue;
            }
            sweepPos = (sweepPos + 1) % loaded.size();
            queue.putIfAbsent(loaded.get(sweepPos), 0L);
        }
    }

    public int pending() {
        return queue.size() + prio.size() + dirty.size();
    }

    public int loadedCount() {
        return loaded.size();
    }

    long captureEstimateNanos() {
        return (long) captureEstimateNanos;
    }
}
