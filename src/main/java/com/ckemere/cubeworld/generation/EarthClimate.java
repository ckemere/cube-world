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
     * A/B switch for the three climate changes made during the calibration work
     * (erosion quantile map, tropical-wetland coast rule, -8 C temperature
     * knot). Set {@code -Dcubeworld.legacyClimate=true} to restore the previous
     * behaviour exactly.
     *
     * <p>It exists so the improvement can be measured like-for-like. Several
     * corrections were also made to the evaluation harness itself, and those
     * move the score without moving the generator; without a way to run the OLD
     * generator under the NEW metric there is no honest way to say how much of
     * the gain was real.
     *
     * <p>Read once at class-init from a system property rather than being
     * runtime-settable, because CubeWorldBiomeProvider memoises biomes per
     * column per thread and a mid-run flip would serve stale answers.
     */
    public static final boolean LEGACY =
            "true".equalsIgnoreCase(System.getProperty("cubeworld.legacyClimate", "false"));

    // ---- pre-calibration values, kept only for the A/B ----
    private static final double[] LEGACY_TT =
            {-1.0, -0.45, -0.22, -0.05, 0.18, 0.45, 0.70, 1.0};
    private static final double[] LEGACY_RM = {0, 48, 153, 382, 578, 1053, 2000};
    private static final double[] LEGACY_RE =
            {0.44, 0.30, 0.05, -0.2225, -0.375, -0.78, -1.0};
    private static final double LEGACY_LOWLAND_EROSION = 0.40;
    private static final double LEGACY_MOUNTAIN_FULL_BLOCKS = 45.0;

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
    // MEASURED FIX: -8 C used to map to exactly -0.45, which is vanilla's edge
    // between the frozen row (snowy_plains / ice_spikes) and the cold row
    // (taiga). Siberian taiga has a mean annual temperature of about -8 C, so it
    // landed a hair INSIDE the frozen row and came out `ice_spikes` across the
    // whole evaluation tile. Worse, the boreal humidity correction below is
    // guarded by `baseTemp >= -0.45`, so it never fired there either -- the one
    // rule meant to make cold forests taiga was switched off precisely where
    // taiga belongs.
    //
    // Moving the knot to -0.38 puts the frozen-row boundary at about -10 C,
    // which is where real tundra actually starts, and lets Siberia fall in the
    // cold row where the boreal correction floors humidity into the taiga column.
    private static final double[] TE = {-25, -8, 0, 8, 18, 24, 30, 40};
    private static final double[] TT = {-1.0, -0.38, -0.22, -0.05, 0.18, 0.45, 0.70, 1.0};

    public static double temperature(double tempC, double humidityParam, boolean land) {
        double base = interp(tempC, TE, LEGACY ? LEGACY_TT : TT);
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
    /**
     * Relief (m) -> erosion, as a QUANTILE MAP: our measured global relief
     * distribution transported onto vanilla's measured erosion distribution, so
     * that by construction our erosion has vanilla's spread while keeping
     * Earth's arrangement.
     *
     * <p>Both sides were measured, not fitted by eye ({@code /cubeworld
     * axisstats vanilla} and {@code ... earth}). Relief percentile q maps to
     * vanilla's erosion percentile 100-q, because high relief means
     * least-eroded means LOW erosion:
     *
     * <pre>
     *   relief p01    2.18 m -> +0.70   (vanilla erosion p99)
     *   relief p25   42.40 m -> +0.15   (vanilla p75)
     *   relief p50  107.84 m -> -0.06   (vanilla p50)
     *   relief p75  251.51 m -> -0.27   (vanilla p25)
     *   relief p95  709.20 m -> -0.56   (vanilla p05)
     *   relief p99 1306.20 m -> -0.76   (vanilla p01)
     * </pre>
     *
     * <p>The previous table put 84% of the world in ONE erosion band and left
     * vanilla's mountain/peak/deep-dark bands (E &lt; -0.2225) holding 1.0%
     * against vanilla's 29.7% -- which is why peak biomes were absent, deep dark
     * was unreachable and windswept_savanna over-fired. The cause was not the
     * curve but the ALTITUDE GATE below, now removed; see the note there.
     */
    private static final double[] RM = {
        0, 2.18, 7.75, 15.07, 42.40, 107.84, 251.51, 491.32, 709.20, 1306.20, 3000};
    private static final double[] RE = {
        1.00, 0.70, 0.45, 0.33, 0.15, -0.06, -0.27, -0.46, -0.56, -0.76, -1.00};

    /**
     * Erosion from local relief alone. The altitude gate this used to apply is
     * GONE, for two measured reasons.
     *
     * <p>First, it was a units bug of the same species as {@code depth}'s
     * divisor: the gate opened over {@code MOUNTAIN_FULL_BLOCKS = 45} BLOCKS
     * above sea, but after the vertical compression in {@link EarthMapSpec} 45
     * blocks is 2500 m of real elevation, so it only opened fully on the tiny
     * fraction of Earth above 2500 m. A 1000 m plateau sits 8.6 blocks above sea
     * and so kept 81% of the flat-lowland value. A constant that means a
     * physical thing was expressed in blocks and silently rescaled with the
     * vertical exaggeration.
     *
     * <p>Second, it pinned the whole ocean -- 70% of the world -- to a single
     * value, because {@code blocksAboveSea} is negative at sea. That made
     * bathymetric relief invisible, so trenches and mid-ocean ridges could not
     * read as least-eroded, and no ocean column could ever qualify for deep
     * dark (which needs E &lt; -0.375). Ocean erosion is safe to vary: vanilla
     * registers every ocean biome with erosion {@code FULL_RANGE}
     * (OverworldBiomeBuilder.addOffCoastBiomes), so it cannot change which
     * ocean biome is chosen -- it only reaches the underground bands.
     *
     * <p>The gate's original purpose -- stopping a low coastal cell beside a
     * mountain from inheriting mountain-grade relief -- is a RELIEF MEASUREMENT
     * problem (max|dh| over a ~9 km baseline is blunt), and belongs there
     * rather than in an altitude fudge.
     */
    public static double erosion(double ruggedMeters, double blocksAboveSea) {
        if (LEGACY) {
            double fromRelief = interp(ruggedMeters, LEGACY_RM, LEGACY_RE);
            double gate = clamp(blocksAboveSea / LEGACY_MOUNTAIN_FULL_BLOCKS, 0.0, 1.0);
            return clamp(LEGACY_LOWLAND_EROSION * (1.0 - gate) + fromRelief * gate, -1, 1);
        }
        return erosion(ruggedMeters);
    }

    /** Erosion from relief. */
    public static double erosion(double ruggedMeters) {
        return clamp(interp(ruggedMeters, RM, RE), -1, 1);
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
        return wetland(elevM, ruggedMeters, precipMm, tempC, Double.NaN);
    }

    /**
     * Wetland score, with the coast distance that separates a tropical delta
     * from a tropical rainforest.
     *
     * <p>MEASURED FAILURE this fixes: the Amazon came out {@code mangrove_swamp}
     * over a whole tile (12.5% correct in the evaluation suite). The basin is
     * flat, low (14-64 m) and very wet (2300 mm), so the flat/low/wet product
     * saturates -- exactly the case the old comment claimed the elevation term
     * guarded against, and it did not, because the lower Amazon really is that
     * low.
     *
     * <p>Elevation cannot separate them, because the Amazon mouth and a real
     * delta sit at the same height. What separates them is DISTANCE TO THE SEA,
     * and the split is physical rather than a fudge: tropical wetlands on Earth
     * are overwhelmingly coastal -- mangrove belts, deltas, tidal marsh -- while
     * flat wet tropical INTERIOR is rainforest. Boreal wetlands are the
     * opposite: Siberian and Canadian peatlands are inland, because what
     * waterlogs them is frozen ground and absent evaporation, not the sea. So
     * the coast requirement is applied only at the warm end.
     */
    public static double wetland(double elevM, double ruggedMeters, double precipMm,
                                double tempC, double coastKm) {
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
        double score = t * t * (3 - 2 * t);
        // Warm + far inland => rainforest, not swamp. Full strength within
        // ~120 km of the sea, fading out by ~400 km; only applied above 10 C so
        // boreal peatlands (which are inland by nature) are untouched.
        if (!LEGACY && score > 0 && tempC > 10.0 && !Double.isNaN(coastKm)) {
            double warm = clamp((tempC - 10.0) / 8.0, 0.0, 1.0);
            double near = 1.0 - clamp((coastKm - 120.0) / 280.0, 0.0, 1.0);
            score *= (1.0 - warm) + warm * near;
        }
        return score;
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
        // Coast distance is already loaded as a sidecar for continentalness;
        // reuse it to keep tropical interiors (the Amazon) out of the swamp band.
        double coastKm = Double.NaN;
        if (elevM >= 0 && earth != null && earth.hasLayer("coast")) {
            coastKm = earth.sample("coast", lon, lat);
        }
        double w = wetland(elevM, ruggedMeters, precipMm, tempC, coastKm);
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

    /**
     * Depth mode ({@code -Dcubeworld.depthMode=} {@code blocks70} |
     * {@code blocks128} | {@code proportional}).
     *
     * <p>Vanilla's depth is {@code (surface - y) / 128} exactly, and the whole
     * biome table is authored in those units: cave biomes at 0.2-0.9, surface
     * biomes re-registered at 1.0, deep dark at 1.1. We used /70, which
     * compresses every one of those bands toward the surface -- the cave window
     * starts 14 blocks down instead of 26 -- and stretches the deep-dark band to
     * ~90 blocks where vanilla's is ~30, which is why deep dark measured 23-41%
     * of deep columns.
     *
     * <p>But /128 alone is not right either, because our terrain is vertically
     * compressed. Vanilla land runs y64-128 and so reaches depth 1.05 (deep
     * dark) comfortably; ours mostly sits at y64-70, where
     * {@code (65+64)/128 = 1.008} never reaches it. Faithful units would delete
     * deep dark from most of the world.
     *
     * <p>{@code proportional} resolves that without a magic constant: measure
     * depth as the FRACTION of the available column consumed, scaled so bedrock
     * is always 1.5 -- the value vanilla's own gradient reaches at its world
     * bottom. Every band is then reachable under any terrain height, and it is
     * scale-invariant, which matters because the whole point of the vertical
     * exaggeration is that it changes with the block scale.
     *
     * <p>MEASURED, on a y103 column and a global census:
     *
     * <pre>
     *              cave window     deep dark      deep dark share
     *                 starts        starts        100 blocks down
     *   blocks70      16 blk        76 blk            24.2%   band ~90 blk, too dominant
     *   blocks128     28 blk       136 blk             0.0%   unreachable under y70 land
     *   proportional  24 blk        90 blk            14.7%   reachable everywhere
     * </pre>
     *
     * <p>{@code proportional} is the default. {@code blocks128} is the
     * vanilla-faithful answer and would be right if the world were not
     * compressed; it is kept so the comparison can be re-run if the vertical
     * scale ever changes.
     */
    private static final String DEPTH_MODE =
            System.getProperty("cubeworld.depthMode", "proportional");

    /** Y of the world floor, for the proportional mode. */
    private static final double WORLD_FLOOR = -64.0;

    public static double depth(double surfaceY, int y) {
        switch (DEPTH_MODE) {
            case "blocks128" -> {
                return clamp((surfaceY - y) / 128.0, -0.1, DEEP_PLATEAU);
            }
            case "proportional" -> {
                double span = Math.max(8.0, surfaceY - WORLD_FLOOR);
                return clamp(1.5 * (surfaceY - y) / span, -0.1, 1.5);
            }
            default -> {
                // Cave biomes live at depth 0.2-0.9 (vanilla addUndergroundBiome),
                // and bottom biomes at 1.1; run past that to DEEP_PLATEAU so both
                // bands are reachable. 70 blocks per unit => caves 14-63 blocks
                // down, deep dark from ~77 down.
                return clamp((surfaceY - y) / 70.0, -0.1, DEEP_PLATEAU);
            }
        }
    }

    /**
     * The full 6-parameter vanilla climate at a world position, plus the raw
     * elev/temp/precip for debugging. Returns null off the net. Layout:
     * [temperature, humidity, continentalness, erosion, depth, weirdness,
     *  elevM, tempC, precipMm, reliefM].
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
        double weird = Math.signum(raw) * weirdMagnitude(Math.abs(raw));

        double tc = temp + nt;
        double h = clamp(humidity(precip) + nh, -1, 1);
        // Boreal correction (cold forests grow on modest rainfall): floor moisture
        // in the cold band so Siberia/Canada come out taiga, not cold steppe.
        double baseTemp = interp(tc, TE, LEGACY ? LEGACY_TT : TT);
        if (land && baseTemp >= -0.45 && baseTemp < -0.05) {
            h = Math.max(h, 0.12);
        }
        return new double[] {
                temperature(tc, h, land), h, clamp(continentalnessAt(earth, lon, lat, elev) + nc, -1, 1),
                clamp(erosionAt(earth, lon, lat, elev, rugged, precip, temp,
                        surfaceY - EarthMapSpec.SEA_LEVEL) + ne, -1, 1),
                depth(surfaceY, y), weird,
                elev, temp, precip,
                // [9] local relief in metres -- the PHYSICAL quantity behind
                // erosion. Exposed so the evaluation harness can build a
                // quantile map from measured relief instead of a fitted guess.
                rugged};
    }

    /**
     * |weirdness| stretched to reach vanilla's outer bands.
     *
     * <p>MEASURED GAP: our weirdness spanned only p01 -0.67 .. p99 +0.64 against
     * vanilla's -0.84 .. +0.86, because the linear form saturated at whatever
     * the fbm happened to reach. Vanilla's extreme bands were therefore dead
     * code for us -- most visibly {@code sulfur_caves}, whose box demands
     * weirdness <= -0.85, so it could never be selected anywhere in the world.
     *
     * <p>Same remedy as erosion: transport our measured magnitudes onto
     * vanilla's, quantile for quantile. The middle is left alone (our p25/p75
     * already match vanilla's) and only the tail is stretched, which keeps the
     * small deadband near 0 -- W must never sit at 0, where vanilla selects the
     * valley/river variants that would fight our dedicated river layer.
     */
    private static final double[] WMAG_IN =  {0.00, 0.05, 0.25, 0.41, 0.50, 0.58, 0.67, 1.00};
    private static final double[] WMAG_OUT = {0.05, 0.10, 0.25, 0.46, 0.59, 0.72, 0.86, 1.00};

    private static double weirdMagnitude(double absRaw) {
        return clamp(interp(absRaw, WMAG_IN, WMAG_OUT), 0.0, 1.0);
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
