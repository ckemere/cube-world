package com.ckemere.cubeworld.generation;

/**
 * Deterministic derivation of noise parameters from the world seed.
 *
 * <p>Seam rule: randomness may only parameterize <em>global fields</em> —
 * functions of the cube-surface point — never be keyed on chunk coordinates.
 * The seed therefore selects phase offsets for the sine fields used by
 * terrain height, cave carving, and cave-biome selection; the fields remain
 * single continuous functions of the folded cube, so every seed produces a
 * different but still seam-consistent world.
 *
 * <p>Phases are drawn from a SplitMix64 stream over disjoint index ranges:
 * 0–3 terrain height, 4–12 cave carver, 13–16 cave biomes. Add new consumers
 * at the end; never reorder, or existing seeds change meaning.
 */
public final class WorldSeeds {

    private static final int PHASE_COUNT = 24;

    private final long seed;
    private final double[] phases;

    private WorldSeeds(long seed, double[] phases) {
        this.seed = seed;
        this.phases = phases;
    }

    public static WorldSeeds from(long seed) {
        double[] phases = new double[PHASE_COUNT];
        long state = seed;
        for (int i = 0; i < PHASE_COUNT; i++) {
            state = splitMix64Advance(state);
            phases[i] = phase(splitMix64Output(state));
        }
        return new WorldSeeds(seed, phases);
    }

    public long seed() {
        return seed;
    }

    /** Seeded phase offset in [0, 2π). */
    public double phase(int index) {
        return phases[index];
    }

    private static long splitMix64Advance(long state) {
        return state + 0x9E3779B97F4A7C15L;
    }

    private static long splitMix64Output(long state) {
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double phase(long bits) {
        return (bits >>> 11) * (2.0 * Math.PI / (1L << 53));
    }
}
