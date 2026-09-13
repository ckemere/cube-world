package com.ckemere.cubeworld.lifesteal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The provenance ledger behind a player's hearts: how many are innate and,
 * for every heart gained by eating, which kind it was and the epitaph it
 * carried. Losses pop the MOST RECENTLY gained heart first — your innate
 * hearts are the last to go; you die your own death last.
 *
 * <p>Stored as JSON in player PDC. The heart COUNT (HeartService) stays the
 * authority for max health; the ledger reconciles to it on read (drift is
 * absorbed into the innate line, so books never block gameplay).
 */
public final class HeartLedger {

    /** One gained heart: its kind and the death it came from. */
    public record Entry(HeartService.HeartKind kind, String epitaph) {
    }

    private final NamespacedKey key;
    private final HeartService hearts;

    public HeartLedger(Plugin plugin, HeartService hearts) {
        this.key = new NamespacedKey(plugin, "heart_ledger");
        this.hearts = hearts;
    }

    // -------------------------------------------------------------- mutation
    public void recordGain(Player p, HeartService.HeartKind kind, String epitaph) {
        State s = load(p);
        s.gained.add(new Entry(kind, epitaph == null ? "" : epitaph));
        save(p, s);
    }

    /** A heart was lost: pop the newest gained one, else spend an innate. */
    public void recordLoss(Player p) {
        State s = load(p);
        if (!s.gained.isEmpty()) {
            s.gained.remove(s.gained.size() - 1);
        } else if (s.innate > 0) {
            s.innate--;
        }
        save(p, s);
    }

    // --------------------------------------------------------------- reading
    /** {@code "9 innate · 2 Wanderer · 1 Traveler"} — count-reconciled. */
    public String summary(Player p) {
        State s = load(p);
        Map<HeartService.HeartKind, Integer> byKind = new LinkedHashMap<>();
        for (Entry e : s.gained) {
            byKind.merge(e.kind(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder(s.innate + " innate");
        for (Map.Entry<HeartService.HeartKind, Integer> e : byKind.entrySet()) {
            sb.append(" · ").append(e.getValue()).append(' ')
                    .append(kindName(e.getKey()));
        }
        return sb.toString();
    }

    /** The gained entries, oldest first (each with its epitaph). */
    public List<Entry> entries(Player p) {
        return List.copyOf(load(p).gained);
    }

    private static String kindName(HeartService.HeartKind k) {
        return switch (k) {
            case TRAVELER -> "Traveler";
            case EXPLORER -> "Explorer";
            case WANDERER -> "Wanderer";
            case FORGED -> "Artificer";
        };
    }

    // -------------------------------------------------------------- storage
    private static final class State {
        int innate;
        final List<Entry> gained = new ArrayList<>();
    }

    private State load(Player p) {
        State s = new State();
        String raw = p.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw != null) {
            try {
                JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
                s.innate = o.get("innate").getAsInt();
                for (var el : o.getAsJsonArray("gained")) {
                    JsonObject g = el.getAsJsonObject();
                    s.gained.add(new Entry(
                            HeartService.HeartKind.valueOf(g.get("k").getAsString()),
                            g.get("e").getAsString()));
                }
            } catch (Exception ignored) {
                s.innate = 0;
                s.gained.clear();
            }
        }
        // Reconcile to the authoritative count: drift (pre-ledger hearts,
        // admin edits) is absorbed into the innate line.
        int diff = hearts.hearts(p) - (s.innate + s.gained.size());
        if (diff != 0) {
            s.innate = Math.max(0, s.innate + diff);
        }
        return s;
    }

    private void save(Player p, State s) {
        JsonObject o = new JsonObject();
        o.addProperty("innate", s.innate);
        JsonArray arr = new JsonArray();
        for (Entry e : s.gained) {
            JsonObject g = new JsonObject();
            g.addProperty("k", e.kind().name());
            g.addProperty("e", e.epitaph());
            arr.add(g);
        }
        o.add("gained", arr);
        p.getPersistentDataContainer().set(key, PersistentDataType.STRING, o.toString());
    }
}
