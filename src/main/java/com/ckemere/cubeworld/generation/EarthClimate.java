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
    // Annual-mean temp (C) -> vanilla temperature param, piecewise so the
    // bands line up with real climate: tundra below ~-8C (frozen row), taiga
    // around -5..0 (cold row), temperate ~5..12 (row 2), warm ~18, hot ~25+.
    // A pure linear map made cold-winter boreal (Siberia) read as frozen.
    private static final double[] TE = {-20, -8, -3, 5, 12, 18, 25, 32};
    private static final double[] TT = {-1.0, -0.5, -0.3, -0.05, 0.2, 0.42, 0.7, 1.0};

    public static double temperature(double tempC, double humidityParam, boolean land) {
        double base = interp(tempC, TE, TT);
        // Land only: vanilla's hottest row is desert regardless of moisture,
        // and jungle/savanna live one row cooler. Real tropics (desert,
        // savanna, jungle) share ~hot mean temps, so keep only genuinely arid
        // tropics in the hot row (desert) and drop the rest to the warm row,
        // where humidity selects jungle (wet) vs savanna (dry). Over ocean we
        // skip this so tropical seas stay warm (coral) rather than lukewarm.
        if (land && base > 0.55 && humidityParam >= -0.65) {
            base = 0.35;
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
                                  double wx, double wz, int y) {
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
        double h = humidity(precip);
        return new double[] {
                temperature(temp, h, land), h, continentalness(elev), erosion(rugged),
                depth(surfaceY, y), weirdness(p, elev), elev, temp, precip};
    }

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
