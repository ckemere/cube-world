package com.ckemere.cubeworld.generation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tiny always-on sampling counters for the generation hot paths. Each named
 * stat accumulates total nanos + call count across all worker threads
 * ({@link LongAdder} for low-contention adds). Dump/reset via the
 * {@code /cubeworld genprof} command. Not for production — a diagnostic.
 */
public final class GenProfiler {

    private static final class Stat {
        final LongAdder nanos = new LongAdder();
        final LongAdder count = new LongAdder();
    }

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();

    private GenProfiler() {
    }

    public static void add(String name, long startNanos) {
        Stat s = STATS.computeIfAbsent(name, k -> new Stat());
        s.nanos.add(System.nanoTime() - startNanos);
        s.count.add(1);
    }

    /** Increment a call counter only (no timing). */
    public static void hit(String name) {
        STATS.computeIfAbsent(name, k -> new Stat()).count.add(1);
    }

    public static String dump() {
        if (STATS.isEmpty()) {
            return "genprof: no samples yet";
        }
        StringBuilder sb = new StringBuilder("genprof (name: total_ms, calls, avg_us):\n");
        STATS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().nanos.sum(), a.getValue().nanos.sum()))
                .forEach(e -> {
                    long ns = e.getValue().nanos.sum();
                    long c = e.getValue().count.sum();
                    sb.append(String.format("  %-22s %,10.1f ms  %,12d  %8.2f us%n",
                            e.getKey(), ns / 1e6, c, c == 0 ? 0 : ns / 1000.0 / c));
                });
        return sb.toString();
    }

    public static void reset() {
        STATS.clear();
    }
}
