package com.dansplugins.factionmap.factions;

import java.util.List;

/**
 * An immutable copy of what the map shows from Medieval Factions, taken on the main thread.
 * It deliberately holds no player data: no member names, UUIDs, roles or positions.
 *
 * @param factions  factions with at least one claim in the mapped world, most claims first
 * @param claims    {chunkX, chunkZ, index into factions}, sorted by x then z
 * @param relations faction links; indices into factions
 */
public record FactionSnapshot(List<Faction> factions, List<int[]> claims, List<Relation> relations) {

    /** @param home block position {x, y, z} of the faction home, or null when hidden or elsewhere */
    public record Faction(String id, String label, String colour, int[] home) {
    }

    /** {@code type} is ally, war or vassal; for vassal, {@code a} is the vassal of {@code b}. */
    public record Relation(int a, int b, String type) {
    }

    public static final FactionSnapshot EMPTY = new FactionSnapshot(List.of(), List.of(), List.of());

    /** Content equality, so an unchanged poll does not rewrite the index. */
    public boolean sameAs(FactionSnapshot o) {
        if (o == null || o.factions.size() != factions.size() || o.claims.size() != claims.size()
                || !o.relations.equals(relations)) {
            return false;
        }
        for (int i = 0; i < factions.size(); i++) {
            Faction a = factions.get(i);
            Faction b = o.factions.get(i);
            if (!a.id().equals(b.id()) || !a.label().equals(b.label()) || !a.colour().equals(b.colour())
                    || !java.util.Arrays.equals(a.home(), b.home())) {
                return false;
            }
        }
        for (int i = 0; i < claims.size(); i++) {
            if (!java.util.Arrays.equals(claims.get(i), o.claims.get(i))) {
                return false;
            }
        }
        return true;
    }
}
