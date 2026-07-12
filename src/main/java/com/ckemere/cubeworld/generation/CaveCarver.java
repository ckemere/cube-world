package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.Vec3;

/**
 * Seam-consistent cave carving: spaghetti tunnels and cheese rooms from
 * smooth pseudo-noise over (cube-surface point, depth). Vanilla carvers are
 * chunk-seeded and do not engage with custom generators (and could never
 * line up across stitched seams); these caves are a continuous function of
 * the folded cube — parameterized by the world seed via {@link WorldSeeds}
 * phases 4–12 — so tunnels flow across every boundary and margins mirror
 * their sources' caves exactly.
 */
public final class CaveCarver {

    /** Keep at least this many solid blocks between a cave and the surface. */
    public static final int ROOF = 8;
    /** Carved space below this Y floods with lava, vanilla-style. */
    public static final int LAVA_LEVEL = -54;

    private final WorldSeeds seeds;

    public CaveCarver(WorldSeeds seeds) {
        this.seeds = seeds;
    }

    /** Should the block at (cube point p, block y) be carved out? */
    public boolean carved(Vec3 p, int y, double surfaceHeight) {
        if (y > surfaceHeight - ROOF) {
            return false;
        }
        // Spaghetti tunnels: near-zero crossings of two independent fields.
        double n1 = Math.sin(31.0 * p.x() + seeds.phase(4)) + Math.cos(27.0 * p.z() + seeds.phase(5))
                + Math.sin(0.11 * y + 13.0 * p.y() + seeds.phase(6));
        double n2 = Math.cos(29.0 * p.x() + seeds.phase(7)) + Math.sin(33.0 * p.y() + seeds.phase(8))
                + Math.cos(0.13 * y - 11.0 * p.z() + seeds.phase(9));
        if (Math.abs(n1) < 0.55 && Math.abs(n2) < 0.55) {
            return true;
        }
        // Cheese rooms, lower down.
        if (y < 30) {
            double n3 = Math.sin(17.0 * p.x() + 5.0 * p.y() + seeds.phase(10))
                    + Math.cos(19.0 * p.z() - 7.0 * p.y() + seeds.phase(11))
                    + Math.sin(0.07 * y + seeds.phase(12));
            return n3 > 2.05;
        }
        return false;
    }
}
