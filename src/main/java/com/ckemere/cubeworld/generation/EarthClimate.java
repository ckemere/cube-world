package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.Vec3;

/* imports for params() below are fully-qualified to keep this list short. */

/**
 * Turns real Earth data into vanilla's six climate parameters (roughly
 * [-1, 1]) so {@link VanillaBiomeMapper} can pick the biome vanilla would.
 * Temperature and humidity come from WorldClim, continentalness and erosion
 * from GEBCO elevation and its local ruggedness, weirdness from a smooth
 * seam-safe field of the cube point, and depth from height below the surface
 * (0 at the surface, ~1 deep — where cave biomes live).
 */
public final class EarthClimate {

    private EarthClimate() {
    }

    /**
     * Temperature param, with wetness folded in at the hot end. Vanilla's
     * hottest temperature row is desert regardless of humidity, and jungle
     * lives one row cooler — but real tropical desert and rainforest have
     * nearly the same mean temperature. So for hot places we pull the wet ones
     * down into the warm (jungle) row while the dry ones stay hot (desert);
     * cold-wet places (taiga) are left untouched.
     */
    // Annual-mean temp (C) -> vanilla temperature param. The knots are chosen so
    // each real climate band lands in the vanilla row that hosts its biomes:
    //   < -8C  -> frozen row (T < -0.45): tundra/ice
    //   -8..3  -> cold row  (-0.45..-0.15): taiga
    //   3..18  -> TEMPERATE row (-0.15..0.2): plains/forest  <-- the key band
    //   18..24 -> warm row (0.2..0.45): savanna / warm forest
    //   24+    -> hot row (0.45..1.0): jungle (if wet, folded below) / desert
    // The old curve pushed 12C up to 0.2, i.e. into the subtropical savanna/
    // jungle row, so temperate grassland (the Great Plains) came out jungle.
    private static final double[] TE = {-25, -8, 0, 8, 18, 24, 30, 40};
    private static final double[] TT = {-1.0, -0.45, -0.22, -0.05, 0.18, 0.45, 0.70, 1.0};

    public static double temperature(double tempC, double humidityParam, boolean land) {
        double base = interp(tempC, TE, TT);
        // Land only. Two corrections, because vanilla's hottest row is desert
        // regardless of moisture while jungle lives one row cooler:
        if (land && base > 0.55 && humidityParam >= -0.65) {
            // Hot & wet: drop into the warm row, where high humidity picks
            // jungle (rainforest) instead of the desert the hot row would give.
            base = 0.35;
        } else if (land && humidityParam < -0.6 && tempC > 15) {
            // Warm & arid: real hot deserts (Sahara, Arabia, Egypt) have only a
            // moderate MEAN temperature, so they miss the hot row on temperature
            // alone. Aridity, not mean temp, is what makes them desert — so pin
            // genuinely dry warm land to the desert row explicitly.
            base = Math.max(base, 0.72);
        }
        return clamp(base, -1, 1);
    }

    public static double humidity(double precipMm) {
        return clamp((Math.log10(Math.max(precipMm, 1.0)) - 2.85) / 0.7, -1, 1);
    }

    // elevation (m) -> continentalness: deep ocean very negative, coast near
    // 0, rising inland with altitude.
    private static final double[] CE = {-6000, -1000, -200, 0, 200, 800, 2000, 4000, 8000};
    private static final double[] CC = {-1.0, -0.6, -0.3, -0.08, 0.0, 0.2, 0.45, 0.75, 1.0};

    public static double continentalness(double elevM) {
        return interp(elevM, CE, CC);
    }

    /** ruggedness (m of local relief) -> erosion. Flat land sits in the middle
     * bands (~0.45), rugged land goes negative (mountain/peak biomes). Capped
     * at 0.5 — the 0.55-1.0 band is vanilla's swamp reserve, which we don't
     * want to hit everywhere (real wetlands come from a dedicated layer). */
    public static double erosion(double ruggedMeters) {
        return clamp(0.45 - ruggedMeters / 500.0, -1, 0.5);
    }

    // weirdness is vanilla's peaks-and-valleys selector: |w| picks the terrain
    // "slice" (near 0 = valleys/rivers, ~0.35 mid, ~0.5 high, ~0.65 peaks).
    // We drive |w| from real elevation so mountainous biomes coincide with the
    // mountainous terrain the height field already produces, and keep |w| away
    // from 0 so we don't scatter spurious rivers (real rivers are a later
    // layer). The sign varies smoothly for within-slice variant variety.
    private static final double[] WE = {0, 300, 1500, 3000, 6000};
    private static final double[] WM = {0.20, 0.28, 0.42, 0.58, 0.70};

    public static double weirdness(Vec3 p, double elevMeters) {
        double mag = interp(elevMeters, WE, WM);
        double s = Math.sin(2.1 * p.x() + 1.3) * Math.cos(1.7 * p.z() - 0.4)
                + 0.6 * Math.sin(2.9 * p.y() + 0.8);
        return mag * (s >= 0 ? 1.0 : -1.0);
    }

    public static double depth(double surfaceY, int y) {
        // Cave biomes live at depth 0.2-0.9; cap there so the deep underground
        // sits firmly in that band (surface biome fades out ~14 blocks down).
        return clamp((surfaceY - y) / 70.0, -0.1, 0.9);
    }

    /**
     * The full 6-parameter vanilla climate at a world position, plus the raw
     * elev/temp/precip for debugging. Returns null off the net. Layout:
     * [temperature, humidity, continentalness, erosion, depth, weirdness,
     *  elevM, tempC, precipMm].
     */
    public static double[] params(EarthData earth, MapSampler sampler,
                                  double wx, double wz, int y, long seed) {
        Vec3 p = sampler.cubePointAt(wx, wz);
        if (p == null) {
            return null;
        }
        double[] ll = earth.toLonLat(p);
        double lon = ll[0];
        double lat = ll[1];
        double elev = earth.sample("height", lon, lat);
        double temp = earth.sample("temp", lon, lat);
        double precip = earth.sample("precip", lon, lat);
        boolean land = elev >= 0;
        if (Double.isNaN(temp)) {
            // WorldClim is land-only; oceans use a gentle latitude proxy (water
            // is thermally milder than the air-temp lapse, so a soft slope).
            temp = 27.0 - Math.abs(lat) * 0.45;
        }
        if (Double.isNaN(precip)) {
            precip = 700.0;
        }
        double rugged = ruggedness(earth, lon, lat, elev);
        double surfaceY = sampler.heightAt(wx, wz);
        // --- seed-based local variation (identical to biomegen.py) blended onto
        // the strong Earth constraint: humidity/temp wobble gives e.g. savanna
        // patches in the Sahara, weirdness picks vanilla variants. ---
        int ns = (int) seed;
        double nh = ClimateNoise.fbm(wx, wz, ns + 11, NOISE_WL, NOISE_OCT) * AMP_HUM;
        double nt = ClimateNoise.fbm(wx, wz, ns + 23, NOISE_WL, NOISE_OCT) * AMP_TEMP_C;
        double nc = ClimateNoise.fbm(wx, wz, ns + 41, NOISE_WL, NOISE_OCT) * AMP_CONT;
        double ne = ClimateNoise.fbm(wx, wz, ns + 57, NOISE_WL, NOISE_OCT) * AMP_EROS;
        double raw = ClimateNoise.fbm(wx, wz, ns + 83, NOISE_WL * 1.7, NOISE_OCT);
        double weird = Math.signum(raw) * (0.10 + AMP_WEIRD * Math.abs(raw));

        double tc = temp + nt;
        double h = clamp(humidity(precip) + nh, -1, 1);
        // Boreal correction (cold forests grow on modest rainfall): floor moisture
        // in the cold band so Siberia/Canada come out taiga, not cold steppe.
        double baseTemp = interp(tc, TE, TT);
        if (land && baseTemp >= -0.45 && baseTemp < -0.05) {
            h = Math.max(h, 0.12);
        }
        return new double[] {
                temperature(tc, h, land), h, clamp(continentalness(elev) + nc, -1, 1),
                clamp(erosion(rugged) + ne, -1, 1), depth(surfaceY, y), weird,
                elev, temp, precip};
    }

    // Noise-blend constants — must match biomegen/biomegen.py exactly.
    private static final double NOISE_WL = 340.0;
    private static final int NOISE_OCT = 3;
    private static final double AMP_HUM = 0.34;
    private static final double AMP_TEMP_C = 3.2;
    private static final double AMP_CONT = 0.05;
    private static final double AMP_EROS = 0.16;
    private static final double AMP_WEIRD = 0.62;

    /**
     * River-mask strength at a column (0..1), the SAME value the biome layer and
     * the water carve both key off — so the river biome and the carved water
     * always coincide (no dry river-biome banks). The river layer is a smooth
     * distance ramp (1 on the centreline/lake, falling to 0 over ~3 px), so one
     * bilinear tap already gives a continuous watercourse — no neighbourhood
     * max, which would only widen it. NaN (off-net) reads as 0.
     */
    public static double riverStrength(EarthData earth, MapSampler sampler, double wx, double wz) {
        if (earth == null || !earth.hasLayer("river")) {
            return 0.0;
        }
        Vec3 p = sampler.cubePointAt(wx, wz);
        if (p == null) {
            return 0.0;
        }
        double[] ll = earth.toLonLat(p);
        double r = earth.sample("river", ll[0], ll[1]);
        return Double.isNaN(r) ? 0.0 : r;
    }

    /** The precomputed DOWNHILL water-surface elevation (metres) at a river
     * column, or NaN off-river. Monotonically non-increasing downstream by
     * construction (running-minimum from each river's source), so the carved
     * river never flows uphill regardless of the raster + ridge noise. */
    public static double riverWaterY(EarthData earth, MapSampler sampler, double wx, double wz) {
        if (earth == null || !earth.hasLayer("river_y")) {
            return Double.NaN;
        }
        Vec3 p = sampler.cubePointAt(wx, wz);
        if (p == null) {
            return Double.NaN;
        }
        double[] ll = earth.toLonLat(p);
        return earth.sample("river_y", ll[0], ll[1]);
    }

    // Threshold on the river ramp. The ramp's first ring (one pixel off the
    // centreline) is 0.75; staying just below it keeps diagonal line segments
    // continuous while a higher value would make the river dotty. Raise it for a
    // thinner river (at the cost of continuity), lower it for a wider one.
    public static final double RIVER_THRESHOLD = 0.7;

    /** Local relief in metres, ~0.08 deg (~9 km) around the point. */
    public static double ruggedness(EarthData earth, double lon, double lat, double elev) {
        double d = 0.08;
        double max = 0;
        for (double[] o : new double[][] {{d, 0}, {-d, 0}, {0, d}, {0, -d}}) {
            double hh = earth.sample("height", lon + o[0], lat + o[1]);
            if (!Double.isNaN(hh)) {
                max = Math.max(max, Math.abs(hh - elev));
            }
        }
        return max;
    }

    private static double interp(double x, double[] xs, double[] ys) {
        if (x <= xs[0]) {
            return ys[0];
        }
        for (int i = 0; i < xs.length - 1; i++) {
            if (x < xs[i + 1]) {
                double t = (x - xs[i]) / (xs[i + 1] - xs[i]);
                return ys[i] + (ys[i + 1] - ys[i]) * t;
            }
        }
        return ys[ys.length - 1];
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
