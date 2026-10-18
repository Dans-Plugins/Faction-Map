package com.dansplugins.factionmap.factions;

import com.dansplugins.factionsystem.MedievalFactions;
import com.dansplugins.factionsystem.area.MfPosition;
import com.dansplugins.factionsystem.claim.MfClaimedChunk;
import com.dansplugins.factionsystem.event.faction.FactionClaimEvent;
import com.dansplugins.factionsystem.event.faction.FactionCreateEvent;
import com.dansplugins.factionsystem.event.faction.FactionDisbandEvent;
import com.dansplugins.factionsystem.event.faction.FactionRenameEvent;
import com.dansplugins.factionsystem.event.faction.FactionUnclaimEvent;
import com.dansplugins.factionsystem.event.relationship.RelationshipCreateEvent;
import com.dansplugins.factionsystem.event.relationship.RelationshipDeleteEvent;
import com.dansplugins.factionsystem.faction.MfFaction;
import com.dansplugins.factionsystem.faction.MfFactionId;
import com.dansplugins.factionsystem.relationship.MfFactionRelationshipService;
import com.dansplugins.factionsystem.service.Services;
import com.dansplugins.factionmap.surface.Colours;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Reads Medieval Factions through its service getters and events, never its database or REST API.
 * It calls getters only; the CI bytecode scan enforces that no other MF method is called.
 *
 * <p>MF fires its claim events before the change is saved, the events can be cancelled and may be
 * asynchronous, so a listener only sets a dirty flag and the state is re-read on the next main-thread
 * pass. Some changes fire no event at all ({@code /f unclaimall}, colour flag changes), so the plugin
 * also re-reads on a slow poll.
 */
public final class MfReader implements Listener {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private final MedievalFactions mf;
    private final AtomicBoolean dirty = new AtomicBoolean(true);

    public MfReader(Plugin medievalFactions) {
        this.mf = (MedievalFactions) medievalFactions;
    }

    /** True once after any MF change event since the last call. */
    public boolean takeDirty() {
        return dirty.getAndSet(false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaim(FactionClaimEvent e) {
        dirty.set(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnclaim(FactionUnclaimEvent e) {
        dirty.set(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreate(FactionCreateEvent e) {
        dirty.set(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(FactionDisbandEvent e) {
        dirty.set(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRename(FactionRenameEvent e) {
        dirty.set(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRelationshipCreate(RelationshipCreateEvent e) {
        dirty.set(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRelationshipDelete(RelationshipDeleteEvent e) {
        dirty.set(true);
    }

    /**
     * Main thread. Reads factions with claims in {@code worldId}, their claims there, and the relations
     * between them.
     *
     * @param showNames false replaces faction names with letters
     * @param showHomes false leaves homes out entirely
     */
    public FactionSnapshot read(UUID worldId, boolean showNames, boolean showHomes) {
        Services services = mf.getServices();
        List<MfFaction> all = services.getFactionService().getFactions();
        List<MfClaimedChunk> claims = services.getClaimService().getClaims();
        Map<String, Integer> claimCount = new HashMap<>();
        for (MfClaimedChunk c : claims) {
            if (worldId.equals(c.getWorldId())) {
                claimCount.merge(c.getFactionId(), 1, Integer::sum);
            }
        }
        // Most claims first, the order the legend uses; on a colour clash the bigger faction keeps its colour.
        List<MfFaction> shown = new ArrayList<>();
        for (MfFaction f : all) {
            if (claimCount.containsKey(f.getId())) {
                shown.add(f);
            }
        }
        shown.sort((a, b) -> {
            int d = claimCount.get(b.getId()) - claimCount.get(a.getId());
            return d != 0 ? d : a.getId().compareTo(b.getId());
        });
        Map<String, Integer> index = new HashMap<>();
        List<FactionSnapshot.Faction> factions = new ArrayList<>();
        for (MfFaction f : shown) {
            index.put(f.getId(), factions.size());
            Object raw = f.getFlags().get(mf.getFlags().getColor());
            String colour = raw instanceof String s && HEX.matcher(s).matches() ? s.toLowerCase(Locale.ROOT) : fallbackColour(f.getId());
            int[] home = null;
            MfPosition p = showHomes ? f.getHome() : null;
            if (p != null && worldId.equals(p.getWorldId())) {
                home = new int[]{(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
            }
            String label = showNames ? f.getName() : FactionLayers.letterLabel(factions.size());
            factions.add(new FactionSnapshot.Faction(f.getId(), label, colour, home));
        }
        List<int[]> cl = new ArrayList<>();
        for (MfClaimedChunk c : claims) {
            Integer i = worldId.equals(c.getWorldId()) ? index.get(c.getFactionId()) : null;
            if (i != null) {
                cl.add(new int[]{c.getX(), c.getZ(), i});
            }
        }
        cl.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<FactionSnapshot.Relation> rel = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        MfFactionRelationshipService rs = services.getFactionRelationshipService();
        for (MfFaction f : shown) {
            int a = index.get(f.getId());
            for (Object o : rs.getAlliesByFactionId(f.getId())) {
                addSymmetric(rel, seen, index, a, idOf(o), "ally");
            }
            for (Object o : rs.getFactionsAtWarWithByFactionId(f.getId())) {
                addSymmetric(rel, seen, index, a, idOf(o), "war");
            }
            for (Object o : rs.getVassalsByFactionId(f.getId())) { // o is a vassal of f
                Integer v = index.get(idOf(o));
                if (v != null && seen.add("vassal:" + v + ":" + a)) {
                    rel.add(new FactionSnapshot.Relation(v, a, "vassal"));
                }
            }
        }
        rel.sort((x, y) -> x.a() != y.a() ? Integer.compare(x.a(), y.a())
                : x.b() != y.b() ? Integer.compare(x.b(), y.b()) : x.type().compareTo(y.type()));
        return new FactionSnapshot(List.copyOf(factions), List.copyOf(cl), List.copyOf(rel));
    }

    // MF's faction ids are a Kotlin value class; lists of them come back boxed.
    private static String idOf(Object o) {
        return o instanceof MfFactionId id ? id.getValue() : String.valueOf(o);
    }

    private static void addSymmetric(List<FactionSnapshot.Relation> rel, Set<String> seen, Map<String, Integer> index,
                                     int a, String other, String type) {
        Integer b = index.get(other);
        if (b == null || b == a) {
            return;
        }
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        if (seen.add(type + ":" + lo + ":" + hi)) {
            rel.add(new FactionSnapshot.Relation(lo, hi, type));
        }
    }

    private static String fallbackColour(String id) {
        return Colours.hsb((id.hashCode() & 0xFFFF) / 65535f, 0.6f, 0.8f);
    }
}
