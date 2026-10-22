package com.dansplugins.factionmap.stats;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatsTest {
    @Test
    void percentilesAreNearestRank() {
        Stats s = new Stats();
        for (int i = 1; i <= 100; i++) {
            s.record("x", i);
        }
        Map<String, Object> m = s.summary().get("x");
        assertEquals(100L, m.get("count"));
        assertEquals(50L, m.get("p50"));
        assertEquals(95L, m.get("p95"));
        assertEquals(99L, m.get("p99"));
        assertEquals(100L, m.get("max"));
        assertEquals(50.5, m.get("mean"));
    }

    @Test
    void theWindowKeepsTheMostRecentSamplesButMaxAndCountAreLifetime() {
        Stats s = new Stats();
        s.record("x", 1_000_000);
        for (int i = 0; i < Stats.WINDOW; i++) {
            s.record("x", 1);
        }
        Map<String, Object> m = s.summary().get("x");
        assertEquals(1L, m.get("p99"));
        assertEquals(1_000_000L, m.get("max"));
        assertEquals((long) Stats.WINDOW + 1, m.get("count"));
    }

    @Test
    void resetForgetsEverything() {
        Stats s = new Stats();
        s.record("x", 3);
        s.reset();
        assertEquals(0, s.summary().size());
    }

    @Test
    void summaryListsSeriesInTheOrderTheyWereFirstRecorded() {
        Stats s = new Stats();
        s.record("zeta", 1);
        s.record("alpha", 1);
        s.record("mid", 1);
        s.record("zeta", 2);
        assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(s.summary().keySet()));
    }

    @Test
    void afterResetTheOrderStartsAgainFromWhatIsRecordedNext() {
        Stats s = new Stats();
        s.record("b", 1);
        s.record("a", 1);
        s.reset();
        s.record("a", 1);
        s.record("b", 1);
        assertEquals(List.of("a", "b"), List.copyOf(s.summary().keySet()));
    }

    @Test
    void meanIsRoundedToOneDecimal() {
        Stats s = new Stats();
        s.record("x", 1);
        s.record("x", 2);
        s.record("x", 2);
        assertEquals(1.7, s.summary().get("x").get("mean"));
    }

    @Test
    void samplesPastTheInitialBufferAreAllKeptBelowTheWindow() {
        Stats s = new Stats();
        for (int i = 1; i <= 1000; i++) {
            s.record("x", i);
        }
        Map<String, Object> m = s.summary().get("x");
        assertEquals(1000L, m.get("count"));
        assertEquals(500L, m.get("p50"));
        assertEquals(990L, m.get("p99"));
        assertEquals(1000L, m.get("max"));
    }

    @Test
    void percentileOfNothingIsZeroAndOfOneSampleIsThatSample() {
        assertEquals(0, Stats.percentile(new long[0], 0.5));
        assertEquals(7, Stats.percentile(new long[]{7}, 0.0));
        assertEquals(7, Stats.percentile(new long[]{7}, 0.99));
        assertEquals(7, Stats.percentile(new long[]{7}, 1.0));
    }

    @Test
    void concurrentRecordersLoseNoSamples() throws Exception {
        Stats s = new Stats();
        int threads = 4;
        int each = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<?>[] done = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                done[t] = pool.submit(() -> {
                    for (int i = 0; i < each; i++) {
                        s.record("shared", 1);
                    }
                });
            }
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        Map<String, Object> m = s.summary().get("shared");
        assertEquals((long) threads * each, m.get("count"));
        assertEquals(1.0, m.get("mean"));
        assertEquals(1L, m.get("max"));
    }
}
