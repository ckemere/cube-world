package com.ckemere.cubeworld.teleport;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Two independent rings (Hamiltonian cycles) over all stations. Every station
 * always offers its successor in each ring, so the network is fully connected by
 * hopping and the two rings give alternate routes. A new station splices in at a
 * random index in each ring.
 *
 * <p>Pure logic (operates on station keys) — unit-tested directly.
 */
public final class RingNetwork {

    private final List<String> a = new ArrayList<>();
    private final List<String> b = new ArrayList<>();

    /** Insert a key at explicit indices (testable). No-op if already present. */
    public void insert(String key, int idxA, int idxB) {
        if (!a.contains(key)) {
            a.add(Math.max(0, Math.min(idxA, a.size())), key);
        }
        if (!b.contains(key)) {
            b.add(Math.max(0, Math.min(idxB, b.size())), key);
        }
    }

    /** Insert at a random, independent index in each ring. */
    public void insertRandom(String key, Random r) {
        insert(key, r.nextInt(a.size() + 1), r.nextInt(b.size() + 1));
    }

    /**
     * Up to two <em>distinct</em> next-hops a station always offers: its ring-A
     * successor, and its ring-B successor — or, if that coincides with ring-A's,
     * the next distinct station walking forward along ring B. With three or more
     * stations this yields two distinct destinations.
     */
    public List<String> twoDestinations(String key) {
        List<String> out = new ArrayList<>();
        String sa = successorA(key);
        if (sa != null) {
            out.add(sa);
        }
        String sb = successorB(key);
        if (sb != null && !sb.equals(sa)) {
            out.add(sb);
            return out;
        }
        int i = b.indexOf(key);                  // ring-B coincided (or null) — walk on
        if (i >= 0) {
            for (int step = 1; step < b.size(); step++) {
                String cand = b.get((i + step) % b.size());
                if (!cand.equals(key) && !cand.equals(sa)) {
                    out.add(cand);
                    break;
                }
            }
        }
        return out;
    }

    public void remove(String key) {
        a.remove(key);
        b.remove(key);
    }

    public boolean contains(String key) {
        return a.contains(key);
    }

    public int size() {
        return a.size();
    }

    /** The station after {@code key} in ring A (cyclic), or null if none. */
    public String successorA(String key) {
        return successor(a, key);
    }

    public String successorB(String key) {
        return successor(b, key);
    }

    private static String successor(List<String> ring, String key) {
        int i = ring.indexOf(key);
        if (i < 0 || ring.size() < 2) {
            return null;
        }
        return ring.get((i + 1) % ring.size());
    }

    // persistence: the service saves/loads these ordered key lists
    public List<String> ringA() {
        return a;
    }

    public List<String> ringB() {
        return b;
    }

    public void load(List<String> savedA, List<String> savedB) {
        a.clear();
        a.addAll(savedA);
        b.clear();
        b.addAll(savedB);
    }
}
