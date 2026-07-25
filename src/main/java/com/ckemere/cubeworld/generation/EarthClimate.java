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

    /** distance-to-coast (km) -> continentalness, matching vanilla's inland bands:
     * coast -0.19..-0.11, near-inland -0.11..0.03, mid-inland 0.03..0.30,
     * far-inland 0.30..1.0. Fitted to the measured distribution of the coast
     * raster (land p50 594 km, p90 1687 km, max 2896 km). */
    private static final double[] DK = {0, 15, 60, 200, 600, 1400, 2600};
    private static final double[] DC = {-0.11, -0.02, 0.06, 0.22, 0.42, 0.70, 1.00};

    /**
     * Continentalness for LAND from true distance to the ocean, which is what
     * vanilla's parameter actually means. The old elevation proxy pinned land
     * near 0 (most land is 0-800 m), so coast/near-inland swallowed nearly every
     * column and vanilla's mid/far-inland bands were never used. Elevation still
     * contributes a small lift so high plateaus read as more interior.
     */
    public static double continentalnessFromCoast(double coastKm, double elevM) {
        double c = interp(coastKm, DK, DC);
        double lift = clamp(elevM / 6000.0, 0.0, 1.0) * 0.25;
        c += lift;
        // Vanilla's "coast" band (C <= -0.11) is where beach lives, and beaches are
        // a few blocks wide. Our coast raster is ~18 km/px, so distance alone cannot
        // resolve that and would paint every shoreline region as beach. Only
        // genuinely low ground is allowed into the band; anything with real
        // elevation is floored into near-inland.
        if (elevM > 25.0) {
            c = Math.max(c, -0.05);
        }
        return clamp(c, -1, 1);
    }

    /** Metres of relief that map to one unit of erosion depression. Fitted to the
     * measured global distribution (tools/compact/relief_stats.py): land relief is
     * p50 48 m, p90 382 m, p95 578 m, p99 1053 m. The old divisor of 500 saturated
     * at 725 m, i.e. at about p95, which clamped 3% of ALL land to the -1.00 floor
     * and made K2 indistinguishable from ordinary hill country. 750 puts
     * saturation near p99 so mountains actually differentiate. */
    private static final double RELIEF_PER_EROSION = 750.0;

    /** Blocks above sea level at which terrain counts as fully mountainous. */
    private static final double MOUNTAIN_FULL_BLOCKS = 45.0;

    /**
     * ruggedness (m of local relief) + in-game altitude -> erosion. Flat land sits
     * in the middle bands (~0.45); rugged HIGH land goes negative (mountain/peak
     * biomes). Capped at 0.5 — the 0.55-1.0 band is vanilla's swamp reserve, which
     * we don't want to hit everywhere (real wetlands come from a dedicated layer).
     *
     * <p>The altitude gate matters because vanilla COUPLES "jagged" to "tall": the
     * same splines that lower erosion also raise {@code offset}, so vanilla only
     * reaches its snowy slope/peak biome families on terrain it has actually built
     * tall. We broke that link — erosion comes from real relief while altitude
     * comes from real elevation compressed ~35x vertically — and relief is
     * {@code max|dh|} to neighbours ~9 km out, so a low coastal cell beside a
     * mountain inherits mountain-grade relief. That produced snowy {@code grove}
     * on a 13 C Mediterranean hillside at y=65. Gating the depression by the
     * column's own height above sea level restores the coupling: only genuinely
     * high AND rugged ground reads as mountain.
     */
    /** Relief (m) -> erosion, as a percentile curve fitted to the MEASURED global
     * distribution (relief_stats.py: land p50 48 m, p75 153, p90 382, p95 578,
     * p99 1053) and mapped onto vanilla's own erosion band edges
     * (-0.78 / -0.375 / -0.2225 / 0.05 / 0.45).
     *
     * <p>The previous linear form {@code 0.45 - rugged/750} put 70% of all land
     * into a 0.074-wide sliver against its own 0.45 ceiling, so vanilla's seven
     * erosion bands collapsed to about one. That is what made
     * {@code windswept_savanna} — whose box is exactly E [0.45, 0.55] — 17% of all
     * land, and left swamps to appear only as overspill from the +-0.16 noise. */
    private static final double[] RM = {0, 48, 153, 382, 578, 1053, 2000};
    private static final double[] RE = {0.44, 0.30, 0.05, -0.2225, -0.375, -0.78, -1.0};

    /** Erosion for genuinely low ground, before any relief is credited. Kept below
     * vanilla's windswept band (0.45) so flat land does not pile into it. */
    private static final double LOWLAND_EROSION = 0.40;

    public static double erosion(double ruggedMeters, double blocksAboveSea) {
        double fromRelief = interp(ruggedMeters, RM, RE);
        // Altitude still gates how much the relief is believed: vanilla couples
        // "jagged" to "tall", and relief is max|dh| to neighbours ~9 km out, so a
        // low coastal cell beside a mountain would otherwise read as mountain.
        double gate = clamp(blocksAboveSea / MOUNTAIN_FULL_BLOCKS, 0.0, 1.0);
        return clamp(LOWLAND_EROSION * (1.0 - gate) + fromRelief * gate, -1, 1);
    }

    /** Ungated form, for callers with no altitude to hand (map previews). */
    public static double erosion(double ruggedMeters) {
        return erosion(ruggedMeters, MOUNTAIN_FULL_BLOCKS);
    }

    /**
     * Wetland score 0..1 — flat, low, wet ground: deltas, floodplains and coastal
     * marshes. Vanilla puts BOTH swamp and mangrove_swamp at erosion >= 0.55 and
     * splits them purely on temperature (below +0.20 swamp, above mangrove), so
     * pushing erosion into that band here is all that is needed; the
     * temperate/tropical split then happens for free.
     *
     * <p>Measured coverage of this rule: ~2-4% of land, against roughly 5-8% for
     * real Earth wetlands. Deliberately conservative — the erosion band is shared
     * with nothing else, so over-firing would carpet the world in swamp.
     */
    public static double wetland(double elevM, double ruggedMeters, double precipMm,
                                double tempC) {
        if (elevM < 0) {
            return 0.0;
        }
        // Loosened from rug<60/elev<80/precip>700 (which qualified only 3.0% of
        // land and yielded 1.62% swamp) to roughly double the footprint. Kept
        // deliberately short of a "flat + low + wet" rule that would also capture
        // the Amazon basin, which is all three yet is rainforest, not swamp -- the
        // elevation term is the main guard there.
        double flat = clamp(1.0 - ruggedMeters / 100.0, 0.0, 1.0);
        double low = clamp(1.0 - elevM / 150.0, 0.0, 1.0);
        // Waterlogging is precipitation against EVAPORATION, not raw rainfall.
        // A raw precip>600 rule caught every tropical delta but excluded the boreal
        // peatlands -- Siberia, Canada, Scandinavia get 300-500 mm yet are soaked
        // because nothing evaporates -- which left mangrove outnumbering swamp 3:1,
        // backwards from Earth. PET is proxied linearly from mean temperature
        // (~300 mm/yr in the Arctic, ~1650 at 30 C).
        // Floor the PET itself, not the temperature: 300 + 45*(-5) would be 75 mm,
        // which makes even a desert-dry Arctic read as waterlogged.
        double pet = Math.max(250.0, 300.0 + 45.0 * tempC);
        double wet = clamp((precipMm / pet - 0.70) / 0.60, 0.0, 1.0);
        // The raw product of three [0,1] terms peaks near 0.48 at p99, so lerping
        // erosion toward 0.68 with it never entered vanilla's swamp band. Smoothstep
        // it so genuine wetlands saturate: measured ~3% of land above 0.15.
        double s = flat * low * wet;
        double t = clamp((s - 0.10) / (0.35 - 0.10), 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    /** Erosion inside a wetland, i.e. within vanilla's swamp band [0.55, 1.0]. */
    private static final double WETLAND_EROSION = 0.68;

    /** Continentalness from the coast sidecar when it is loaded, else the old
     * elevation proxy. */
    public static double continentalnessAt(EarthData earth, double lon, double lat,
                                           double elevM) {
        if (elevM >= 0 && earth.hasLayer("coast")) {
            double km = earth.sample("coast", lon, lat);
            if (!Double.isNaN(km)) {
                return continentalnessFromCoast(km, elevM);
            }
        }
        return continentalness(elevM);
    }

    /** Erosion, lifted into vanilla's swamp band over wetlands. */
    public static double erosionAt(EarthData earth, double lon, double lat, double elevM,
                                   double ruggedMeters, double precipMm, double tempC,
                                   double blocksAboveSea) {
        double e = erosion(ruggedMeters, blocksAboveSea);
        double w = wetland(elevM, ruggedMeters, precipMm, tempC);
        return w > 0 ? e * (1.0 - w) + WETLAND_EROSION * w : e;
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

    /** Deep-underground plateau: the value {@link #depth} saturates at. Vanilla's
     * depth axis runs to ~1.5 at the world bottom, and its BOTTOM biomes (only
     * deep dark) are registered at exactly {@code point(1.1)} while every SURFACE
     * biome is registered twice, at {@code point(0.0)} AND {@code point(1.0)}.
     * The old cap of 0.9 therefore made the whole deep underground nearest to the
     * surface-biome-at-1.0 entry, so the surface biome just extended downward and
     * deep dark was unreachable everywhere, at any Y, forever. 1.25 sits just
     * past 1.1 so the deep zone is closest to deep dark (0.15 away vs 0.25 to the
     * surface entry) and erosion decides, exactly as in vanilla.
     *
     * <p>Deliberately not larger: the ramp is surface-relative, so under an
     * 8000 m peak an uncapped depth would reach ~3.8 — far outside anything
     * vanilla registers, where nearest-neighbour results get arbitrary. */
    public static final double DEEP_PLATEAU = 1.25;

    public static double depth(double surfaceY, int y) {
        // Cave biomes live at depth 0.2-0.9 (vanilla addUndergroundBiome), and
        // bottom biomes at 1.1; run past that to DEEP_PLATEAU so both bands are
        // reachable. 70 blocks per unit => caves 14-63 blocks down, deep dark
        // from ~77 down. Ocean is naturally excluded: the deepest sea floor sits
        // at y~2, so even at bedrock depth only reaches (2+64)/70 = 0.94.
        return clamp((surfaceY - y) / 70.0, -0.1, DEEP_PLATEAU);
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
        // Restore named summits the same way TERRAIN does (SphereDensity), so the
        // climate the biome layer sees matches the mountain the generator builds.
        double raster = earth.sample("height", lon, lat);
        // Only LIFT to a summit cone; never let cone==0 clamp a negative (ocean)
        // elevation up to 0. Math.max(-3757, 0) made every ocean column read as
        // sea-level land, so c[6] < 0 never fired and oceanBiome() never ran.
        double cone = peakCone(lon, lat);
        double elev = cone > 0 ? Math.max(raster, cone) : raster;
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
        // Widened from 0.10 + 0.62*|raw| (which topped out at |W| = 0.66) so the
        // extreme bands are reachable: sulfur_caves needs W <= -0.85, and vanilla's
        // outermost variant bands start at |W| = 0.78. A small deadband remains so
        // W never sits at 0, where vanilla selects valley/river variants that would
        // fight our dedicated river layer.
        double weird = Math.signum(raw) * (0.05 + 0.98 * Math.abs(raw));

        double tc = temp + nt;
        double h = clamp(humidity(precip) + nh, -1, 1);
        // Boreal correction (cold forests grow on modest rainfall): floor moisture
        // in the cold band so Siberia/Canada come out taiga, not cold steppe.
        double baseTemp = interp(tc, TE, TT);
        if (land && baseTemp >= -0.45 && baseTemp < -0.05) {
            h = Math.max(h, 0.12);
        }
        return new double[] {
                temperature(tc, h, land), h, clamp(continentalnessAt(earth, lon, lat, elev) + nc, -1, 1),
                clamp(erosionAt(earth, lon, lat, elev, rugged, precip, temp,
                        surfaceY - EarthMapSpec.SEA_LEVEL) + ne, -1, 1),
                depth(surfaceY, y), weird,
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
    /**
     * Named-summit cone elevation (m) at a lon/lat — {@link PeakField}'s restored
     * peak, or 0 away from the 6000 m summits. The GEBCO raster averages summits
     * down (Everest reads ~5000 m, not 8849), and {@code SphereDensity} restores
     * them for TERRAIN; the climate path must see them too or a named peak reads
     * as gentle mid-altitude ground — no jagged-peak biome, and erosion stays
     * positive so no deep dark can form beneath it. Cheap away from peaks: the
     * bins for the 9 surrounding whole degrees are almost always empty.
     */
    public static double peakCone(double lon, double lat) {
        return PeakField.get().coneElevation(lon, lat);
    }

    /**
     * Local relief in metres: the largest elevation difference to four neighbours
     * ~0.08 deg (~9 km) away. Summit cones are folded in on both sides, so a
     * named peak becomes a relief spike — Everest's cone falls ~2800 m over that
     * 9 km baseline, which drives {@link #erosion} to its -1.0 floor and makes
     * the summit a centre of jagged, least-eroded terrain (and deep-dark-capable
     * rock below it), matching the terrain the height field actually builds.
     */
    public static double ruggedness(EarthData earth, double lon, double lat, double elev) {
        double d = 0.08;
        double max = 0;
        for (double[] o : new double[][] {{d, 0}, {-d, 0}, {0, d}, {0, -d}}) {
            double hh = earth.sample("height", lon + o[0], lat + o[1]);
            if (!Double.isNaN(hh)) {
                double nc = peakCone(lon + o[0], lat + o[1]);
                if (nc > 0) {
                    hh = Math.max(hh, nc);       // lift only; see params() above
                }
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
