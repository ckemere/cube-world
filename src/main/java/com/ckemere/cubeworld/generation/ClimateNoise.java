package com.ckemere.cubeworld.generation;

/**
 * A tiny value-noise (integer-hash grid + smoothstep + fbm), bit-identical to
 * the map tool's {@code biomegen/noise.py}, so the seed-based local biome
 * variation matches between the generated world and the map. 32-bit integer math
 * with unsigned shifts; the low bits of the multiplies agree with Python's
 * masked arithmetic by construction.
 */
public final class ClimateNoise {

    private ClimateNoise() {
    }

    static double hash01(int ix, int iz, int seed) {
        int h = ix * 374761393 + iz * 668265263 + seed * 0x9E3779B1;
        h ^= h >>> 13;
        h *= 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 65536.0;
    }

    static double vnoise(double x, double z, int seed, double wavelength) {
        double fx = x / wavelength;
        double fz = z / wavelength;
        int ix = (int) Math.floor(fx);
        int iz = (int) Math.floor(fz);
        double tx = fx - ix;
        double tz = fz - iz;
        double sx = tx * tx * (3 - 2 * tx);
        double sz = tz * tz * (3 - 2 * tz);
        double v00 = hash01(ix, iz, seed);
        double v10 = hash01(ix + 1, iz, seed);
        double v01 = hash01(ix, iz + 1, seed);
        double v11 = hash01(ix + 1, iz + 1, seed);
        return (v00 * (1 - sx) + v10 * sx) * (1 - sz) + (v01 * (1 - sx) + v11 * sx) * sz;
    }

    /** Fractal value noise in ~[-1, 1]. */
    public static double fbm(double x, double z, int seed, double wavelength, int octaves) {
        double total = 0;
        double amp = 1;
        double norm = 0;
        double wl = wavelength;
        for (int o = 0; o < octaves; o++) {
            total += amp * (vnoise(x, z, seed + o * 1013, wl) * 2.0 - 1.0);
            norm += amp;
            amp *= 0.5;
            wl *= 0.5;
        }
        return total / norm;
    }
}
