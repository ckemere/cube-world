package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.Vec3;

/**
 * Subsurface biome selection: smooth pseudo-noise over (cube-surface point,
 * depth), so cave biome patches — like heights — agree across every seam by
 * construction. Pure math, unit-tested for cross-seam consistency.
 */
public final class CaveBiomes {

    /** Cave biomes give way to the surface theme this close to the surface. */
    public static final int SURFACE_BUFFER = 12;
    /** No cave biomes above this Y. */
    public static final int CAVE_BIOME_MAX_Y = 45;

    public enum CaveBiome {
        NONE, LUSH, DRIPSTONE, DEEP_DARK
    }

    private CaveBiomes() {
    }

    /**
     * Cave biome at a cube-surface point and block Y, ignoring the surface
     * buffer (callers check depth against the local surface height).
     */
    public static CaveBiome at(Vec3 p, int y) {
        if (y > CAVE_BIOME_MAX_Y) {
            return CaveBiome.NONE;
        }
        double n = Math.sin(7.3 * p.x() + 1.7) * Math.cos(6.1 * p.z() - 0.4)
                + Math.sin(5.7 * p.y() + 0.9)
                + Math.sin(0.21 * y + 3.0 * p.x() - 2.0 * p.z());
        if (y < -25 && n < -1.7) {
            return CaveBiome.DEEP_DARK;
        }
        if (n > 1.55) {
            return CaveBiome.LUSH;
        }
        if (n < -1.55 && y >= -25) {
            return CaveBiome.DRIPSTONE;
        }
        return CaveBiome.NONE;
    }
}
