package com.dansplugins.factionmap.stats;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
