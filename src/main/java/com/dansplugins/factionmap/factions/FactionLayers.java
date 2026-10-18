package com.dansplugins.factionmap.factions;

import com.dansplugins.factionmap.surface.Colours;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the faction layers of {@code index.json} from a {@link FactionSnapshot}: per faction its label,
 * the colour it is drawn in, and whether that colour had to be shifted because it was too close to the
 * colour of a faction listed before it (factions are ordered most claims first, so the bigger faction
 * keeps its own colour).
 */
public final class FactionLayers {
    /** RGB distance below which two faction colours are treated as indistinguishable on the map. */
    static final double CLASH_DISTANCE = 60;
    private static final float SHIFT_DEGREES = 47;

    private FactionLayers() {
    }

    public static List<Map<String, Object>> factions(FactionSnapshot snapshot, boolean showHomes) {
        int[] counts = new int[snapshot.factions().size()];
        for (int[] c : snapshot.claims()) {
            counts[c[2]]++;
        }
        List<String[]> seen = new ArrayList<>(); // {colour drawn, label}
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < snapshot.factions().size(); i++) {
            FactionSnapshot.Faction f = snapshot.factions().get(i);
            String original = f.colour();
            String colour = original;
            String clash = null;
            for (int guard = 0; guard < 8; guard++) {
                String hit = null;
                for (String[] s : seen) {
                    if (Colours.distance(colour, s[0]) < CLASH_DISTANCE) {
                        hit = s[1];
                        break;
                    }
                }
                if (hit == null) {
                    break;
                }
                if (clash == null) {
                    clash = hit;
                }
                colour = Colours.hueShift(colour, SHIFT_DEGREES);
            }
            seen.add(new String[]{colour, f.label()});
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", f.label());
            m.put("color", colour);
            m.put("colorAdjusted", clash != null);
            m.put("originalColor", original);
            m.put("colorClashesWith", clash);
            m.put("chunks", counts[i]);
            if (showHomes && f.home() != null) {
                m.put("home", f.home());
            }
            out.add(m);
        }
        return out;
    }

    public static List<Object[]> relations(FactionSnapshot snapshot) {
        List<Object[]> out = new ArrayList<>();
        for (FactionSnapshot.Relation r : snapshot.relations()) {
            out.add(new Object[]{r.a(), r.b(), r.type()});
        }
        return out;
    }

    /** "A", "B", ... "Z", "AA", "AB"...: what a faction is called when names are hidden. */
    public static String letterLabel(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return "Faction " + sb;
    }
}
