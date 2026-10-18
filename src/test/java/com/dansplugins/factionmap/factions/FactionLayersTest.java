package com.dansplugins.factionmap.factions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactionLayersTest {
    static FactionSnapshot.Faction f(String name, String colour) {
        return new FactionSnapshot.Faction(name.toLowerCase(), name, colour, new int[]{10, 64, -3});
    }

    @Test
    void theSecondOfTwoNearIdenticalColoursIsShiftedAndSaysWhy() {
        FactionSnapshot s = new FactionSnapshot(List.of(f("Big", "#c0392b"), f("Small", "#c43b2d")),
                List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{5, 5, 1}), List.of());
        List<Map<String, Object>> out = FactionLayers.factions(s, false);
        assertEquals("#c0392b", out.get(0).get("color"));
        assertEquals(false, out.get(0).get("colorAdjusted"));
        assertEquals(2, out.get(0).get("chunks"));
        assertEquals(true, out.get(1).get("colorAdjusted"));
        assertEquals("Big", out.get(1).get("colorClashesWith"));
        assertEquals("#c43b2d", out.get(1).get("originalColor"));
        assertNotEquals("#c43b2d", out.get(1).get("color"));
    }

    @Test
    void distinctColoursAreKept() {
        FactionSnapshot s = new FactionSnapshot(List.of(f("A", "#2f6fb0"), f("B", "#3a9a5b")), List.of(), List.of());
        List<Map<String, Object>> out = FactionLayers.factions(s, false);
        assertEquals("#3a9a5b", out.get(1).get("color"));
        assertNull(out.get(1).get("colorClashesWith"));
    }

    @Test
    void homesAppearOnlyWhenEnabled() {
        FactionSnapshot s = new FactionSnapshot(List.of(f("A", "#2f6fb0")), List.of(), List.of());
        assertFalse(FactionLayers.factions(s, false).get(0).containsKey("home"));
        assertArrayEquals(new int[]{10, 64, -3}, (int[]) FactionLayers.factions(s, true).get(0).get("home"));
    }

    @Test
    void hiddenNamesBecomeLetters() {
        assertEquals("Faction A", FactionLayers.letterLabel(0));
        assertEquals("Faction Z", FactionLayers.letterLabel(25));
        assertEquals("Faction AA", FactionLayers.letterLabel(26));
        assertEquals("Faction AB", FactionLayers.letterLabel(27));
    }

    @Test
    void relationsSerialiseAsTriples() {
        FactionSnapshot s = new FactionSnapshot(List.of(), List.of(),
                List.of(new FactionSnapshot.Relation(0, 1, "war"), new FactionSnapshot.Relation(2, 0, "vassal")));
        List<Object[]> r = FactionLayers.relations(s);
        assertArrayEquals(new Object[]{0, 1, "war"}, r.get(0));
        assertArrayEquals(new Object[]{2, 0, "vassal"}, r.get(1));
    }

    @Test
    void snapshotsCompareByContent() {
        FactionSnapshot a = new FactionSnapshot(List.of(f("A", "#2f6fb0")), List.of(new int[]{1, 2, 0}), List.of());
        FactionSnapshot b = new FactionSnapshot(List.of(f("A", "#2f6fb0")), List.of(new int[]{1, 2, 0}), List.of());
        FactionSnapshot c = new FactionSnapshot(List.of(f("A", "#2f6fb1")), List.of(new int[]{1, 2, 0}), List.of());
        assertTrue(a.sameAs(b));
        assertFalse(a.sameAs(c), "a colour flag change must reach the map");
        assertFalse(a.sameAs(FactionSnapshot.EMPTY));
    }
}
