package com.dansplugins.factionmap.factions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactionSnapshotTest {
    static FactionSnapshot.Faction f(String label, String colour, int[] home) {
        return new FactionSnapshot.Faction(label.toLowerCase(), label, colour, home);
    }

    /** A fresh snapshot each call, so equal content never shares arrays. */
    static FactionSnapshot base() {
        return new FactionSnapshot(
                List.of(f("North", "#2e86c1", new int[]{10, 64, -3}), f("South", "#c0392b", null)),
                List.of(new int[]{0, 0, 0}, new int[]{4, -2, 1}),
                List.of(new FactionSnapshot.Relation(0, 1, "war")));
    }

    @Test
    void equalContentInFreshArraysIsTheSame() {
        assertTrue(base().sameAs(base()));
        assertTrue(FactionSnapshot.EMPTY.sameAs(new FactionSnapshot(List.of(), List.of(), List.of())));
    }

    @Test
    void nullAndDifferentSizesAreNotTheSame() {
        assertFalse(base().sameAs(null));
        assertFalse(base().sameAs(FactionSnapshot.EMPTY));
        FactionSnapshot fewerClaims = new FactionSnapshot(base().factions(),
                List.of(new int[]{0, 0, 0}), base().relations());
        assertFalse(base().sameAs(fewerClaims));
    }

    @Test
    void aChangedClaimIsNotTheSame() {
        FactionSnapshot moved = new FactionSnapshot(base().factions(),
                List.of(new int[]{0, 0, 0}, new int[]{4, -1, 1}), base().relations());
        assertFalse(base().sameAs(moved));
        FactionSnapshot transferred = new FactionSnapshot(base().factions(),
                List.of(new int[]{0, 0, 0}, new int[]{4, -2, 0}), base().relations());
        assertFalse(base().sameAs(transferred));
    }

    @Test
    void aChangedRelationIsNotTheSame() {
        FactionSnapshot allied = new FactionSnapshot(base().factions(), base().claims(),
                List.of(new FactionSnapshot.Relation(0, 1, "ally")));
        assertFalse(base().sameAs(allied));
        FactionSnapshot reversed = new FactionSnapshot(base().factions(), base().claims(),
                List.of(new FactionSnapshot.Relation(1, 0, "war")));
        assertFalse(base().sameAs(reversed));
    }

    @Test
    void eachFactionFieldIsCompared() {
        int[] home = {10, 64, -3};
        FactionSnapshot.Faction south = f("South", "#c0392b", null);
        List<FactionSnapshot.Faction> renamedId = List.of(
                new FactionSnapshot.Faction("other", "North", "#2e86c1", home), south);
        List<FactionSnapshot.Faction> relabelled = List.of(f("Nord","#2e86c1", home), south);
        List<FactionSnapshot.Faction> recoloured = List.of(f("North", "#2e86c2", home), south);
        List<FactionSnapshot.Faction> rehomed = List.of(f("North", "#2e86c1", new int[]{10, 65, -3}), south);
        List<FactionSnapshot.Faction> homeHidden = List.of(f("North", "#2e86c1", null), south);
        for (List<FactionSnapshot.Faction> changed : List.of(renamedId, relabelled, recoloured, rehomed, homeHidden)) {
            assertFalse(base().sameAs(new FactionSnapshot(changed, base().claims(), base().relations())),
                    changed.get(0).toString());
        }
    }

    @Test
    void factionOrderMatters() {
        FactionSnapshot swapped = new FactionSnapshot(List.of(base().factions().get(1), base().factions().get(0)),
                base().claims(), base().relations());
        assertFalse(base().sameAs(swapped));
    }
}
