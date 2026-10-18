package com.dansplugins.factionmap.capture;

import com.dansplugins.factionmap.tiles.ChunkKeys;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureSchedulerTest {
    static final long MS = 1_000_000L;
    final AtomicLong clock = new AtomicLong(1_000 * MS);
    final List<long[]> captures = new ArrayList<>(); // {key, changedAt}
    final Set<Long> unloaded = new HashSet<>();
    long captureCostNanos = 50_000;

    CaptureScheduler scheduler(long budgetMicros, double sweepPerSecond) {
        return new CaptureScheduler(clock::get, (k, changedAt) -> {
            if (unloaded.contains(k)) {
                return false;
            }
            clock.addAndGet(captureCostNanos);
            captures.add(new long[]{k, changedAt});
            return true;
        }, budgetMicros, 1000, 5000, sweepPerSecond);
    }

    static long k(int x, int z) {
        return ChunkKeys.key(x, z);
    }

    @Test
    void loadedChunksAreCapturedOnceWithinTheBudget() {
        CaptureScheduler s = scheduler(500, 0);
        for (int i = 0; i < 40; i++) {
            s.chunkLoaded(k(i, 0));
        }
        s.tick(0);
        // 50 us each against a 500 us budget, with the estimate settling from 100 us
        assertTrue(captures.size() >= 4 && captures.size() <= 10, "captured " + captures.size());
        while (s.pending() > 0) {
            s.tick(0);
        }
        assertEquals(40, captures.size());
        s.chunkLoaded(k(0, 0)); // reloaded: already captured
        s.tick(0);
        assertEquals(40, captures.size());
    }

    @Test
    void timeAlreadySpentThisTickCountsAgainstTheBudget() {
        CaptureScheduler s = scheduler(500, 0);
        s.chunkLoaded(k(0, 0));
        s.tick(450_000); // 450 us of events + MF read already: no room for a ~100 us capture, and the tick is not quiet
        assertEquals(0, captures.size());
        s.tick(0);
        assertEquals(1, captures.size());
    }

    @Test
    void aCaptureDearerThanTheWholeBudgetStillProgressesOnQuietTicks() {
        captureCostNanos = 2_000_000; // 2 ms per capture on a very slow server
        CaptureScheduler s = scheduler(500, 0);
        s.chunkLoaded(k(0, 0));
        s.chunkLoaded(k(1, 0));
        s.tick(0);
        assertEquals(1, captures.size(), "one per tick, never more");
        s.tick(0);
        assertEquals(2, captures.size());
    }

    @Test
    void blockChangesWaitForQuietThenJumpTheLoadQueue() {
        CaptureScheduler s = scheduler(500, 0);
        for (int i = 0; i < 100; i++) {
            s.chunkLoaded(k(i, 5));
        }
        while (s.pending() > 0) {
            s.tick(0);
        }
        for (int i = 0; i < 100; i++) {
            s.chunkLoaded(k(i, 9)); // a fresh load wave
        }
        long first = clock.get();
        s.blockChanged(k(3, 5));
        clock.addAndGet(500 * MS);
        s.blockChanged(k(3, 5)); // still changing
        captures.clear();
        clock.addAndGet(900 * MS);
        s.tick(0);
        assertTrue(captures.stream().noneMatch(c -> c[0] == k(3, 5)), "not quiet for 1 s yet");
        clock.addAndGet(200 * MS);
        captures.clear();
        s.tick(0);
        assertEquals(k(3, 5), captures.get(0)[0], "the debounced change goes first");
        assertEquals(first, captures.get(0)[1], "and carries the time of its first change");
    }

    @Test
    void aChunkThatKeepsChangingIsCapturedByTheMaxDelay() {
        CaptureScheduler s = scheduler(500, 0);
        long first = clock.get();
        for (int i = 0; i < 12; i++) {
            s.blockChanged(k(0, 0));
            clock.addAndGet(500 * MS);
            s.tick(0);
        }
        assertEquals(1, captures.size());
        assertEquals(first, captures.get(0)[1]);
        assertTrue(clock.get() - first >= 5000 * MS);
    }

    @Test
    void unloadingWithAPendingChangeAsksForAnImmediateCapture() {
        CaptureScheduler s = scheduler(500, 0);
        s.chunkLoaded(k(0, 0));
        s.tick(0);
        long t = clock.get();
        s.blockChanged(k(0, 0));
        assertEquals(t, s.chunkUnloading(k(0, 0)));
        assertEquals(0, s.chunkUnloading(k(1, 1)));
        assertEquals(0, s.pending());
        assertEquals(0, s.loadedCount());
    }

    @Test
    void theSweepRecapturesLoadedChunksRoundRobin() {
        CaptureScheduler s = scheduler(500, 20); // one per tick
        for (int i = 0; i < 3; i++) {
            s.chunkLoaded(k(i, 0));
        }
        while (s.pending() > 0) {
            s.tick(0);
        }
        captures.clear();
        for (int i = 0; i < 6; i++) {
            s.tick(0);
        }
        assertEquals(6, captures.size());
        Set<Long> seen = new HashSet<>();
        captures.forEach(c -> seen.add(c[0]));
        assertEquals(3, seen.size());
    }

    @Test
    void unloadedChunksInTheQueueAreSkippedNotLoaded() {
        CaptureScheduler s = scheduler(500, 0);
        for (int i = 0; i < 1000; i++) {
            s.chunkLoaded(k(i, 0));
            unloaded.add(k(i, 0));
        }
        s.chunkLoaded(k(5000, 0));
        s.tick(0);
        assertEquals(0, captures.size());
        assertEquals(1001 - CaptureScheduler.MAX_SKIPS_PER_TICK, s.pending(), "a stale queue is drained a bounded amount per tick");
    }

    @Test
    void swapRemoveKeepsTheLoadedSetConsistent() {
        CaptureScheduler s = scheduler(500, 0);
        for (int i = 0; i < 5; i++) {
            s.chunkLoaded(k(i, 0));
        }
        s.chunkUnloading(k(1, 0));
        s.chunkUnloading(k(4, 0));
        s.chunkLoaded(k(9, 0));
        assertEquals(4, s.loadedCount());
        assertEquals(4, s.requeueLoaded());
        while (s.pending() > 0) {
            s.tick(0);
        }
        Set<Long> seen = new HashSet<>();
        captures.forEach(c -> seen.add(c[0]));
        assertEquals(Set.of(k(0, 0), k(2, 0), k(3, 0), k(9, 0)), seen);
    }
}
