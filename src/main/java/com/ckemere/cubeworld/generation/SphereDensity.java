package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.Vec3;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * Folds vanilla's terrain {@link NoiseRouter} onto the cube's sphere embedding.
 * Every horizontal-noise node (Noise/Shift/ShiftA/ShiftB/ShiftedNoise) is
 * replaced so it samples at the sphere-embedded cube point instead of the raw
 * plane coordinate; {@code YClampedGradient} and everything else pass through
 * untouched and keep reading the real block Y (the vertical depth gradient).
 *
 * <p>Because the sampled position is a continuous function of (cube direction,
 * y), and two blocks on opposite sides of a stitched seam fold to the same cube
 * point, vanilla's own generator produces terrain, caves, aquifers and ore
 * veins that line up across every seam automatically — the sphere fold does for
 * the real density graph what {@link CaveCarver} did by hand
 * ([[hook-real-code-not-bukkit]]). Swap the folded router into the world's
 * {@code RandomState} and vanilla places every block.
 *
 * <p>The remap is idempotent via a {@link SphereContext} marker, so a shift
 * nested under a shifted noise is folded once, not twice.
 */
public final class SphereDensity {

    /**
     * Sphere radius in blocks. 2*pi*R ~= 4*faceSize (the cube perimeter), so
     * noise features come out the same size they would in a vanilla world.
     */
    private static final double RADIUS_FACTOR = 0.64;

    /** Blocks of y per unit of vanilla "depth" (yClampedGradient -64..320, 1.5..-1.5). */
    private static final double DEPTH_SLOPE = 128.0;

    // ---- Earth-driven terrain SHAPE (vanilla factor/jaggedness from real relief).
    /** Metres of local relief (over the ~9 km ruggedness baseline) that count as
     * fully rugged. Himalayan/Andean flanks exceed this; plains are near 0. */
    private static final double RELIEF_FULL = 700.0;
    /** factor for flat ground: tight surface, hugs the Earth elevation (~5 blocks
     * of noise freedom). Deliberately ABOVE vanilla's spline ceiling of 6.3:
     * vanilla never needs a tighter surface because its offset is synthesised,
     * whereas ours is a real elevation raster that flat land should hug. */
    private static final double FACTOR_FLAT = 9.0;
    /** factor for rugged ground: shallow gradient, so the 3D noise gets ~25
     * blocks to carve cliffs and overhangs. */
    private static final double FACTOR_RUGGED = 1.25;
    /** Peak ridge-noise amplitude on the most rugged terrain (vanilla's jaggedness
     * spline tops out at 0.63). */
    private static final double JAGGED_MAX = 0.60;

    /**
     * Couples the noise amplitude to the freeboard -- how far above sea level the
     * elevation data wants a column to be.
     *
     * <p>Surface displacement from unit noise is {@code 32 / factor} blocks. The
     * first cut set this to 64, aiming the wobble at half the freeboard on that
     * arithmetic, and measured 27% of land still drowning with the ground landing
     * about 2 blocks low -- BASE_3D_NOISE plainly swings wider than the +/-1 the
     * estimate assumed. 128 halves the wobble again to absorb it. Without this the two are independent, and they are on
     * incompatible scales near the coast: the low-elevation curve grants 300 m of
     * real terrain 1.8 blocks while FACTOR_FLAT hands the noise 3.6 blocks to play
     * with, so below roughly 800 m the noise -- not the Earth data -- decides
     * whether a column is land. Measured on a transect through the Antioch shore
     * before this: 7 of 11 genuinely-land points generated as open sea, including
     * one at 502 m.
     */
    private static final double FREEBOARD_TIGHTNESS =
            Double.parseDouble(System.getProperty("cubeworld.freeboardTightness", "128.0"));

    /**
     * Tightest the surface may be pinned, i.e. at least 0.5 blocks of wobble.
     *
     * <p>DO NOT raise this to pin the shoreline harder. Tried and measured:
     * 256 (with LAND_FREEBOARD_MIN dropped to 1) took drowning from 2.4% to
     * 21.9% of intended-land columns -- confirmed in real generated chunks, not
     * just the emulator.
     *
     * <p>The reason is a coupling that is easy to miss. {@code factor} does not
     * only set surface tightness; it also decides WHERE vanilla switches from
     * near-surface to full-cave treatment, because that switch is a threshold on
     * {@code sloped_cheese} ({@code SURFACE_DENSITY_THRESHOLD = 1.5625}) and
     * {@code sloped_cheese ~ 4 * depth * factor}. The surface-regime depth is
     * therefore {@code 70 * 1.5625 / (4 * factor)} blocks:
     *
     * <pre>
     *   factor   9  ->  3.04 blocks of near-surface zone
     *   factor  98  ->  0.28
     *   factor 256  ->  0.11
     * </pre>
     *
     * <p>Past about factor 60 the near-surface zone is thinner than one block,
     * so the full cave subtraction (cheese, spaghetti, entrances) applies
     * immediately under the surface and carves voids straight through it. The
     * measured drops were 3 to 44 blocks -- far too large for surface noise, and
     * unmistakably caves.
     */
    private static final double FACTOR_PINNED =
            Double.parseDouble(System.getProperty("cubeworld.factorPinned", "64.0"));

    /**
     * Water depth (blocks) at which the seabed may carry its full relief-driven
     * character. Below this it is damped toward flat, because the failure the
     * old landGate was written for is real: relief is max|dh| over a ~9 km
     * baseline, so a shoal beside a deep drop reads as maximally rugged and
     * punches rock spires through the sea surface.
     */
    private static final double SEABED_RELIEF_FULL =
            Double.parseDouble(System.getProperty("cubeworld.seabedReliefFull", "28.0"));

    /** Fraction of the overlying water a seabed feature may rise through. */
    private static final double SEABED_JAG_HEADROOM =
            Double.parseDouble(System.getProperty("cubeworld.seabedHeadroom", "0.35"));

    /**
     * Drive the aquifer's floodedness from real hydrology
     * ({@code -Dcubeworld.hydroAquifers=}).
     *
     * <p>Vanilla decides whether an underground pocket is dry, partially or
     * fully flooded from a plain noise field. We have the data to do better:
     * caves should be wet under rivers and in soaked climates and dry under
     * deserts, which is a thing players can actually read off the landscape.
     */
    private static final boolean HYDRO_AQUIFERS =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.hydroAquifers", "true"));

    /** Undersea ridges and trench walls ({@code -Dcubeworld.seabedJagged=}). */
    private static final boolean SEABED_JAGGED =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.seabedJagged", "true"));

    /** Ceiling for the underwater tightness guard. Lower than the land cap: the
     * seabed does not need pinning as hard, and a high factor is what collapses
     * vanilla's near-surface zone into cave territory. 24 leaves ~1.1 blocks of
     * near-surface zone. */
    private static final double FACTOR_SEABED_MAX = 24.0;
    /** Off switch for A/B: {@code -Dcubeworld.earthShape=false} restores vanilla's
     * own (geography-blind) factor/jaggedness. */
    private static final boolean EARTH_SHAPE =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.earthShape", "true"));

    /**
     * THE LEAF HOOK ({@code -Dcubeworld.leafHook=true}).
     *
     * <p>Two ways to put Earth into vanilla's terrain graph:
     *
     * <p><b>Today (off):</b> mid-graph surgery. We find the {@code factor} and
     * {@code jaggedness} splines by FINGERPRINTING THEIR VALUE RANGES and swap
     * them for our own, and swap {@code depth} too. Vanilla's splines never run.
     * That leaves two independent derivations of the world -- EarthClimate makes
     * the biome axes, SphereDensity makes the terrain -- with nothing forcing
     * them to agree. It is also brittle: any Mojang retune of a spline's range
     * silently un-hooks us.
     *
     * <p><b>Leaf hook (on):</b> replace the LEAVES instead --
     * {@code continentalness}, {@code erosion} and {@code ridge} -- matched by
     * REGISTRY KEY, not by value range. Vanilla's own offset/factor/jaggedness
     * splines are splines over exactly those three leaves, so vanilla then
     * computes the terrain shape itself, from our geography. That is where its
     * axis correlations come from, and it is the thing we have been unable to
     * reproduce by hand.
     *
     * <p>Verified on the live router before building this: a visitor DOES reach
     * inside splines ({@code ridge} is visited 542 times, not once), and every
     * leaf resolves via {@code unwrapKey()}.
     *
     * <p>PROVEN on real chunks (tools/realscore.py, which wipes the region and
     * regenerates under each configuration, because the in-game emulator cannot
     * compare configurations that change the density field's shape). Terrain
     * RMSE against the Earth data 22.6 -> 12.6 blocks overall, driven by Everest
     * 40.4 -> 21.9: vanilla's jaggedness spline is far more restrained than the
     * EarthJagged it replaces, so peaks stop overshooting the raster by 35
     * blocks. Coast and lowland unchanged, drowning 2.7% -> 2.5%, biome
     * accuracy unchanged at 98.7%. Default ON.
     *
     * <p>Height still comes from the raster: {@code depth} and
     * {@code preliminarySurfaceLevel} stay substituted, so vanilla's synthesised
     * {@code offset} is bypassed exactly as before. What changes is that the
     * SHAPE terms are vanilla's, driven by our axes.
     */
    private static final boolean LEAF_HOOK =
            "true".equalsIgnoreCase(System.getProperty("cubeworld.leafHook", "true"));

    /**
     * Decouple vanilla's cave-regime switch from {@code factor}
     * ({@code -Dcubeworld.caveDepthSwitch=true}).
     *
     * <p>Vanilla chooses between "near the surface, cut entrances only" and
     * "deep, apply the full cave subtraction" with
     * {@code rangeChoice(slopedCheese, -1e6, 1.5625, ...)}. Because
     * {@code slopedCheese ~ 4 * depth * factor}, the depth at which that switch
     * happens is {@code 1.5625 * DEPTH_SLOPE / (4 * factor)} -- i.e. it shrinks
     * as the surface is pinned harder:
     *
     * <pre>
     *   factor  9  ->  3.0 blocks of near-surface zone   (open country)
     *   factor 43  ->  0.64                              (coastal, freeboard 3)
     *   factor 64  ->  0.43                              (the pinned cap)
     * </pre>
     *
     * <p>So the freeboard rule, whose entire job is to keep the shoreline dry,
     * simultaneously deletes the protective zone there and lets full cave voids
     * open one block under the beach. That is why the attempt to lower
     * LAND_FREEBOARD_MIN drove drowning from 2.4% to 21.9% -- the drops were
     * caves, not noise. It also means the CURRENT settings already run coasts
     * with a 0.64-block zone.
     *
     * <p>This replaces the switch's INPUT with a plain depth-in-blocks proxy, so
     * the near-surface zone is a fixed thickness everywhere regardless of how
     * hard the surface is pinned. Vanilla's two branches are untouched.
     */
    private static final boolean CAVE_DEPTH_SWITCH =
            "true".equalsIgnoreCase(System.getProperty("cubeworld.caveDepthSwitch", "false"));

    /** Thickness of the near-surface (entrances-only) zone, in blocks. Vanilla's
     * own value varies 3-7 blocks over open country; 6 sits in that range. */
    /**
     * Attenuate the 3D terrain noise near the waterline instead of raising
     * {@code factor} ({@code -Dcubeworld.noiseAttenuation=true}).
     *
     * <p>THE KEY CONSTRAINT, measured the hard way. Vanilla's whole cave system
     * is calibrated on {@code slopedCheese} being O(1) near the surface -- the
     * near-surface branch is {@code min(slopedCheese, 5 * entrances)}, so the two
     * terms are meant to be comparable. Our freeboard rule pushes {@code factor}
     * to 40-250 where vanilla's own spline never exceeds 6.3, which makes
     * {@code slopedCheese} enormous; the {@code min} then ALWAYS picks the
     * entrances term and carves air straight through the ground. Measured drops
     * of 8-22 blocks at 800-975 m Saharan columns, and 31.8% of land drowning.
     *
     * <p>Raising factor is therefore a dead end: it is the one knob that both
     * tightens the surface and breaks the caves. Attenuating the noise achieves
     * the first without the second -- surface displacement is
     * {@code base3d * attenuation * DEPTH_SLOPE / (4 * factor)}, so scaling the
     * noise shrinks the wobble while leaving {@code slopedCheese} in the range
     * vanilla's caves expect.
     */
    private static final boolean NOISE_ATTENUATION =
            "true".equalsIgnoreCase(System.getProperty("cubeworld.noiseAttenuation", "true"));

    /** Freeboard (blocks above sea) at which the 3D noise runs at full strength. */
    private static final double ATTEN_FULL_AT =
            Double.parseDouble(System.getProperty("cubeworld.attenFull", "48.0"));

    /** Floor on the attenuation, so the surface never becomes perfectly flat. */
    private static final double ATTEN_MIN =
            Double.parseDouble(System.getProperty("cubeworld.attenMin", "0.03"));

    private static final double SURFACE_ZONE_BLOCKS =
            Double.parseDouble(System.getProperty("cubeworld.surfaceZone", "6.0"));

    /** Vanilla's cave-regime threshold, from NoiseRouterData. */
    private static final double SURFACE_DENSITY_THRESHOLD = 1.5625;

    /** Vanilla's factor and jaggedness reach the density tree as Spline nodes, and
     * NoiseRouter exposes neither (they live inside finalDensity). RandomState
     * rebuilds every node via mapAll, so registry identity is broken — probed
     * live: 0 identity-shared occurrences. They are, however, cleanly separable by
     * their value RANGE, which is the same behavioural-fingerprint trick
     * isDepthGradient uses. Measured on 26.2: jaggedness [0.000, 0.630],
     * factor [0.625, 6.300], three copies of each in finalDensity. */
    private static boolean isJaggednessSpline(DensityFunction df) {
        return df instanceof DensityFunctions.Spline
                && near(df.minValue(), 0.0, 1.0e-3) && near(df.maxValue(), 0.63, 1.0e-2);
    }

    private static boolean isFactorSpline(DensityFunction df) {
        return df instanceof DensityFunctions.Spline
                && near(df.minValue(), 0.625, 1.0e-2) && near(df.maxValue(), 6.3, 1.0e-2);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    private final MapSampler sampler;
    private final double radius;
    private final boolean earthHeight;
    private final EarthData earth;
    private final PeakField peaks;
    /**
     * When true, the main 3D terrain node ({@code BlendedNoise}) is also folded
     * onto the sphere. The overworld leaves it unfolded (its macro shape comes
     * from folded {@code ShiftedNoise} continents/erosion/depth, so the tiny
     * unfolded 3D detail at a seam is negligible), but the nether has NO macro
     * shape — its entire terrain IS the BlendedNoise ({@code nether/base_3d_noise}
     * inside {@code slide}) — so it must be folded or seams cliff. The fold
     * preserves scale: raising real y by 128 moves the sphere sample radially by
     * 128 blocks, and 2*pi*R ~= face perimeter keeps horizontal features vanilla-sized.
     */
    private final boolean foldBlendedNoise;
    /** World seed, so the leaf axes match EarthClimate's noise blend exactly. */
    private volatile long seed;

    private SphereDensity(MapSampler sampler, int faceSize, boolean earthHeight, EarthData earth) {
        this(sampler, faceSize, earthHeight, earth, false);
    }

    /** Set by the hook; the axes must use the same seed the biome layer does. */
    public SphereDensity seed(long s) {
        this.seed = s;
        return this;
    }

    private SphereDensity(MapSampler sampler, int faceSize, boolean earthHeight, EarthData earth,
                          boolean foldBlendedNoise) {
        this.sampler = sampler;
        this.radius = RADIUS_FACTOR * faceSize;
        this.earthHeight = earthHeight;
        this.earth = earth;
        this.peaks = (earthHeight && earth != null) ? PeakField.get() : null;
        this.foldBlendedNoise = foldBlendedNoise;
    }

    public static SphereDensity forSampler(MapSampler sampler, int faceSize) {
        return new SphereDensity(sampler, faceSize, false, null);
    }

    /**
     * The sphere radius (in blocks) used for the fold at a given face size, so
     * other folded samplers (the nether surface rule) embed on the SAME sphere.
     */
    public static double radiusFor(int faceSize) {
        return RADIUS_FACTOR * faceSize;
    }

    /**
     * Fold for the NETHER: pure vanilla nether density, no Earth height pin, and
     * the main {@code BlendedNoise} terrain node folded too (the nether has no
     * separate macro-shape field to carry seam continuity, so the 3D noise itself
     * must be folded).
     */
    public static SphereDensity forNether(MapSampler sampler, int faceSize) {
        return new SphereDensity(sampler, faceSize, false, null, true);
    }

    /**
     * @param earthHeight when true, the terrain surface follows the sampler's
     *     Earth elevation ({@link MapSampler#heightAt}) instead of vanilla's
     *     noise offset — the Earth "hybrid": real macro-shape, vanilla caves,
     *     aquifers, ores and surface underneath. Real >=6000 m summits
     *     ({@link PeakField}) are restored on top, since the raster averages
     *     them down.
     * @param earth the Earth data (for lon/lat lookup of peaks); may be null.
     */
    public static SphereDensity forSampler(MapSampler sampler, int faceSize,
                                           boolean earthHeight, EarthData earth) {
        return new SphereDensity(sampler, faceSize, earthHeight, earth);
    }

    /** A copy of {@code router} whose noise sampling is folded onto the sphere. */
    /** Last overworld instance, for the terrainprobe debug command. */
    public static volatile SphereDensity LAST;

    /**
     * The surface height the density path is actually AIMING at, i.e. exactly
     * what {@link EarthDepth} centres {@code depth} on. This is NOT
     * {@code sampler.heightAt} -- named summits are restored on top of the cell
     * grid, so at Everest the two differ by ~45 blocks. Scoring against the
     * wrong one manufactures a huge RMSE that is purely an artefact (TODO.md
     * item 7, trap 3: three different surface heights exist and they disagree).
     */
    public double targetSurfaceY(double wx, double wz) {
        double h = sampler.heightAt(wx, wz);
        if (peaks != null && earth != null) {
            Vec3 p = sampler.cubePointAt(wx, wz);
            if (p != null) {
                double[] ll = earth.toLonLat(p);
                double cone = peaks.coneElevation(ll[0], ll[1]);
                if (cone > 0) {
                    double ph = EarthMapSpec.elevationToBlockY(cone);
                    if (ph > h) {
                        h = ph;
                    }
                }
            }
        }
        return h;
    }

    /** Debug dump of the terms that decide the surface at a column. */
    public String probeTerms(double wx, double wz) {
        int bx = (int) Math.floor(wx);
        int bz = (int) Math.floor(wz);
        double natural = sampler.heightAt(wx, wz);
        double rel = reliefAt(bx, bz);
        double lg = landGate(bx, bz);
        double rn = reliefNorm(bx, bz);
        double fRelief = FACTOR_FLAT + (FACTOR_RUGGED - FACTOR_FLAT) * rn;
        // Report the factor ACTUALLY used, not just the relief term -- the two
        // differ by an order of magnitude wherever the freeboard rule bites, and
        // printing the relief term alone once sent an investigation the wrong way.
        double freeboard = natural - EarthMapSpec.SEA_LEVEL;
        double f = freeboard < 0 ? fRelief
                : Math.min(FACTOR_PINNED,
                        Math.max(fRelief, FREEBOARD_TIGHTNESS / Math.max(freeboard, 1.0)));
        return String.format(java.util.Locale.ROOT,
                "natural=%.2f | relief=%.0fm landGate=%.2f reliefNorm=%.2f "
                + "factor=%.1f (relief term %.1f, noise ~%.2f blk) "
                + "surface-regime depth %.2f blk",
                natural, rel, lg, rn, f, fRelief, 32.0 / Math.max(f, 0.01),
                70.0 * 1.5625 / (4.0 * Math.max(f, 0.01)));
    }

    public NoiseRouter fold(NoiseRouter router) {
        if (earthHeight) {
            LAST = this;          // overworld only; the nether instance has no Earth data
        }
        NoiseRouter folded = router.mapAll(new SphereVisitor());
        if (!earthHeight) {
            return folded;
        }
        // preliminarySurfaceLevel is built by NoiseRouterData from `offset` and
        // `factor` directly, NOT from `depth` -- so folding the depth node leaves
        // it behind. We replace `factor` but never `offset`, and `offset` is still
        // vanilla's spline over vanilla continents/erosion noise, uncorrelated with
        // Earth. The result was a chimera: our factor, vanilla's altitude.
        //
        // Its only real consumer is the aquifer system (Aquifer.computeFluid, via
        // NoiseChunk.preliminarySurfaceLevel), which uses it to decide whether an
        // underground cell falls back to the global sea-level fluid or gets a local
        // water table, and how high that table sits. Open ocean is unaffected --
        // that comes from globalFluidPicker -- but perched water underground was
        // being placed against terrain that does not exist here.
        //
        // The record is in hand, so substitute the field outright rather than trying
        // to fingerprint the node.
        return new NoiseRouter(
                folded.barrierNoise(),
                HYDRO_AQUIFERS ? new HydroFloodedness() : folded.fluidLevelFloodednessNoise(),
                folded.fluidLevelSpreadNoise(),
                folded.lavaNoise(),
                folded.temperature(),
                folded.vegetation(),
                folded.continents(),
                folded.erosion(),
                folded.depth(),
                folded.ridges(),
                new EarthSurfaceLevel(),
                folded.finalDensity(),
                folded.veinToggle(),
                folded.veinRidged(),
                folded.veinGap());
    }

    /**
     * The Earth surface height at a column, for the router's
     * {@code preliminarySurfaceLevel} slot. NoiseChunk floors this and caches it
     * per quantized column, so it only needs to be the altitude, not a density.
     */
    /**
     * Aquifer floodedness from Earth hydrology, standing in for
     * {@code fluidLevelFloodednessNoise}.
     *
     * <p>The aquifer compares this against two thresholds (Aquifer
     * .computeSurfaceLevel): above the higher one the pocket fills to sea level,
     * above the lower it gets a randomised local table, below both it is dry. So
     * the output is in the same [-1, 1] range as the noise it replaces, just
     * with the sign carrying meaning -- wet climates and river courses positive,
     * arid interiors negative.
     */
    private final class HydroFloodedness implements DensityFunction {
        @Override
        public double compute(FunctionContext c) {
            double[] h = hydroCache.get();
            int bx = c.blockX();
            int bz = c.blockZ();
            if (h[0] != bx || h[1] != bz) {
                h[0] = bx;
                h[1] = bz;
                h[2] = hydroAt(bx + 0.5, bz + 0.5);
            }
            return h[2];
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapAll(Visitor v) { return v.apply(this); }
        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0).codec();
        }
    }

    private final ThreadLocal<double[]> hydroCache =
            ThreadLocal.withInitial(() -> new double[] {Double.NaN, Double.NaN, 0.0});

    /**
     * Wetness of the ground at a column, in the [-1, 1] the aquifer expects.
     * Precipitation against evaporation (the same PET proxy the wetland rule
     * uses, so a cold dry place still reads as waterlogged), lifted hard along
     * river courses because a river IS a water table intersecting the surface.
     */
    private double hydroAt(double wx, double wz) {
        Vec3 p = sampler.cubePointAt(wx, wz);
        if (p == null || earth == null) {
            return 0.0;
        }
        double[] ll = earth.toLonLat(p);
        double precip = earth.sample("precip", ll[0], ll[1]);
        double tempC = earth.sample("temp", ll[0], ll[1]);
        if (Double.isNaN(precip)) {
            precip = 700.0;
        }
        if (Double.isNaN(tempC)) {
            tempC = 12.0;
        }
        double pet = Math.max(250.0, 300.0 + 45.0 * tempC);
        // ratio ~0.2 in the Sahara, ~2.5 in the Amazon -> about -0.75 .. +0.75
        double wet = Math.clamp((precip / pet - 0.75) / 1.0, -0.75, 0.75);
        double river = EarthClimate.riverStrength(earth, sampler, wx, wz);
        return Math.clamp(wet + 0.6 * river, -1.0, 1.0);
    }

    private final class EarthSurfaceLevel implements DensityFunction {
        @Override
        public double compute(FunctionContext c) {
            fillColumn(c.blockX(), c.blockZ());
            return reliefCache.get()[4];
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return -64.0; }
        @Override public double maxValue() { return 320.0; }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0).codec();      // never serialised
        }
    }

    /**
     * Final terrain density at a world column and block Y (probe/debug):
     * positive = solid, negative = open. Large negative off the net.
     */
    public double probe(NoiseRouter foldedRouter, double worldX, int y, double worldZ) {
        Vec3 dir = unit(worldX, worldZ);
        if (dir == null) {
            return -1.0;
        }
        double r = radius + y;
        DualContext ctx = new DualContext(
                (int) Math.floor(worldX), y, (int) Math.floor(worldZ),
                (int) Math.round(dir.x() * r),
                (int) Math.round(dir.y() * r),
                (int) Math.round(dir.z() * r));
        return foldedRouter.finalDensity().compute(ctx);
    }

    /** Normalized cube-surface direction at a column, or null off the net. */
    private Vec3 unit(double worldX, double worldZ) {
        Vec3 p = sampler.cubePointAt(worldX, worldZ);
        if (p == null) {
            return null;
        }
        double n = Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z());
        if (n < 1e-9) {
            return null;
        }
        return new Vec3(p.x() / n, p.y() / n, p.z() / n);
    }

    // Per-thread direct-mapped cache of column unit directions, 6 doubles per
    // slot: [bx, bz, dx, dy, dz, present]. Every folded noise node resolves the
    // same column, and vanilla samples a cell's ~16 corner columns interleaved
    // across Y, so a small set of slots keyed by (bx, bz) turns almost all of
    // the millions of per-sample resolves into hits.
    private static final int UC_SLOTS = 1024;
    private final ThreadLocal<double[]> unitCache = ThreadLocal.withInitial(() -> {
        double[] a = new double[UC_SLOTS * 6];
        java.util.Arrays.fill(a, Double.NaN);
        return a;
    });

    private SphereContext sphereFrom(DensityFunction.FunctionContext c) {
        if (c instanceof SphereContext s) {
            return s;
        }
        if (c instanceof DualContext d) {
            return new SphereContext(d.sx, d.sy, d.sz);
        }
        int bx = c.blockX();
        int bz = c.blockZ();
        double[] u = unitCache.get();
        int slot = ((bx * 31 + bz) & (UC_SLOTS - 1)) * 6;
        if (u[slot] != bx || u[slot + 1] != bz) {
            long t = System.nanoTime();
            Vec3 dir = unit(bx + 0.5, bz + 0.5);
            GenProfiler.add("cubePointAt(density)", t);
            u[slot] = bx;
            u[slot + 1] = bz;
            if (dir != null) {
                u[slot + 2] = dir.x();
                u[slot + 3] = dir.y();
                u[slot + 4] = dir.z();
                u[slot + 5] = 1;
            } else {
                u[slot + 5] = 0;
            }
        }
        if (u[slot + 5] == 0) {
            return new SphereContext(bx, c.blockY(), bz);
        }
        double r = radius + c.blockY();
        return new SphereContext(
                (int) Math.round(u[slot + 2] * r),
                (int) Math.round(u[slot + 3] * r),
                (int) Math.round(u[slot + 4] * r));
    }

    /** Carries both real coords (for depth gradients) and pre-folded coords. */
    private static final class DualContext implements DensityFunction.FunctionContext {
        final int rx, ry, rz, sx, sy, sz;

        DualContext(int rx, int ry, int rz, int sx, int sy, int sz) {
            this.rx = rx; this.ry = ry; this.rz = rz;
            this.sx = sx; this.sy = sy; this.sz = sz;
        }

        @Override public int blockX() { return rx; }
        @Override public int blockY() { return ry; }
        @Override public int blockZ() { return rz; }
    }

    /** Folded coordinates; recognized by {@link Remap} as already-remapped. */
    private static final class SphereContext implements DensityFunction.FunctionContext {
        final int x, y, z;

        SphereContext(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
        }

        @Override public int blockX() { return x; }
        @Override public int blockY() { return y; }
        @Override public int blockZ() { return z; }
    }

    /** Wraps a horizontal-noise node so it samples at the folded position. */
    private final class Remap implements DensityFunction {
        private final DensityFunction inner;

        Remap(DensityFunction inner) {
            this.inner = inner;
        }

        @Override
        public double compute(FunctionContext c) {
            return inner.compute(sphereFrom(c));
        }

        @Override
        public void fillArray(double[] out, ContextProvider provider) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(provider.forIndex(i));
            }
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(this);
        }

        @Override
        public DensityFunction mapChildren(Visitor visitor) {
            return this;
        }

        @Override public double minValue() { return inner.minValue(); }
        @Override public double maxValue() { return inner.maxValue(); }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return inner.codec();
        }
    }

    private final class SphereVisitor implements DensityFunction.Visitor {
        @Override
        public DensityFunction apply(DensityFunction node) {
            if (node instanceof Remap || node instanceof EarthDepth) {
                return node;
            }
            switch (node.getClass().getSimpleName()) {
                case "Noise", "Shift", "ShiftA", "ShiftB", "ShiftedNoise" -> {
                    if (LEAF_HOOK && earthHeight && earth != null) {
                        String key = RouterProbe.noiseKeyOf(node);
                        if (key != null) {
                            switch (key) {
                                case "minecraft:continentalness" -> {
                                    return new EarthAxis(node, 2);
                                }
                                case "minecraft:erosion" -> {
                                    return new EarthAxis(node, 3);
                                }
                                case "minecraft:ridge" -> {
                                    return new EarthAxis(node, 5);
                                }
                                default -> {
                                }
                            }
                        }
                    }
                    return new Remap(node);
                }
                case "BlendedNoise" -> {
                    if (foldBlendedNoise) {
                        return new Remap(node);
                    }
                    if (NOISE_ATTENUATION && earthHeight && earth != null) {
                        return new AttenuatedNoise(node);
                    }
                }
                default -> {
                }
            }
            if (earthHeight && isOffsetToDepth(node)) {
                return new EarthDepth();
            }
            // Terrain CHARACTER from real relief. Without this the surface sits at
            // the right altitude but its steepness and spikiness come from vanilla
            // Perlin that is uncorrelated with Earth, so a real 8000 m peak can get
            // flat-plains treatment and a real plain can get spikes.
            if (earthHeight && CAVE_DEPTH_SWITCH && isCaveRangeChoice(node)) {
                return rebuildCaveChoice(node);
            }
            if (earthHeight && EARTH_SHAPE && earth != null) {
                if (isFactorSpline(node)) {
                    // Leaf hook: keep VANILLA's factor spline (now reading our
                    // axes) and only apply the freeboard floor on top, so the
                    // waterline stays pinned. Wrapping instead of replacing is
                    // the whole point -- the spline's shape is the part we want.
                    return LEAF_HOOK ? new FreeboardGuard(node) : new EarthFactor();
                }
                if (isJaggednessSpline(node)) {
                    // Leaf hook: vanilla's jaggedness spline over our axes,
                    // plus a seabed term vanilla structurally will not supply.
                    if (LEAF_HOOK) {
                        return SEABED_JAGGED ? new SeabedJagged(node) : node;
                    }
                    return new EarthJagged();
                }
            }
            return node;
        }
    }

    /**
     * True for vanilla's {@code depth} node: {@code add(yClampedGradient(-64,
     * 320, 1.5, -1.5), offset)}. That exact gradient signature is unique to
     * {@code offsetToDepth}, so matching it (and its use in the preliminary
     * surface level) swaps every height reference to Earth's in one pass.
     */
    private static boolean isOffsetToDepth(DensityFunction node) {
        if (!(node instanceof DensityFunctions.TwoArgumentSimpleFunction two)
                || !"ADD".equals(two.type().name())) {
            return false;
        }
        return isDepthGradient(two.argument1()) || isDepthGradient(two.argument2());
    }

    private static boolean isDepthGradient(DensityFunction df) {
        if (!"YClampedGradient".equals(df.getClass().getSimpleName())) {
            return false;
        }
        // The record is package-private (reflective accessors throw), so probe
        // its output instead: offsetToDepth's gradient is -64..320 -> 1.5..-1.5,
        // i.e. 1.5 at y=-64, 0 at y=128, -1.5 at y=320.
        return near(df.compute(atY(-64)), 1.5)
                && near(df.compute(atY(128)), 0.0)
                && near(df.compute(atY(320)), -1.5);
    }

    private static DensityFunction.FunctionContext atY(int y) {
        return new DensityFunction.SinglePointContext(0, y, 0);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-4;
    }

    /**
     * Replacement for vanilla's {@code depth}: 0 at the Earth surface, sloping
     * -1/128 per block of Y (matching vanilla's gradient) so all the downstream
     * factor/jaggedness/cheese/cave/aquifer maths behave identically, just
     * re-centred on the real elevation instead of vanilla's noise offset.
     */
    /**
     * The surface height is independent of Y, but vanilla samples density at
     * many Y per column, so cache it per (x, z) per thread — a big saving since
     * each miss does a cube-point resolve, an elevation lookup and a peak scan.
     */
    /**
     * Local relief in metres at a column, cached per thread. This is the physical
     * quantity that should drive terrain CHARACTER: vanilla derives its
     * {@code factor} and {@code jaggedness} splines from its own
     * continents/erosion/ridges noise, which has nothing to do with real
     * geography, so before this hook an 8849 m Himalayan peak got whatever
     * steepness the Perlin happened to roll there.
     */
    /** Per-thread column cache: [bx, bz, reliefMetres, unused, surfaceY]. These are
     * per-column quantities but are read once per density SAMPLE (many y per
     * column), so caching them together keeps the 30-city distance scan and the
     * raster/peak lookups off the hot path. */
    private final ThreadLocal<double[]> reliefCache =
            ThreadLocal.withInitial(() -> new double[] {Double.NaN, Double.NaN, 0.0, 0.0, 0.0, 0.0});

    private void fillColumn(int bx, int bz) {
        double[] c = reliefCache.get();
        if (c[0] == bx && c[1] == bz) {
            return;
        }
        double r = 0.0;
        Vec3 p = sampler.cubePointAt(bx + 0.5, bz + 0.5);
        if (p != null && earth != null) {
            long t = System.nanoTime();
            double[] ll = earth.toLonLat(p);
            double elev = Math.max(earth.sample("height", ll[0], ll[1]),
                    EarthClimate.peakCone(ll[0], ll[1]));
            if (!Double.isNaN(elev)) {
                r = EarthClimate.ruggedness(earth, ll[0], ll[1], elev);
            }
            GenProfiler.add("earthShape.relief", t);
        }
        c[0] = bx;
        c[1] = bz;
        c[2] = r;
        c[4] = sampler.heightAt(bx + 0.5, bz + 0.5);
        // [5] is the SAME surface EarthDepth centres depth on, i.e. with named
        // summits restored. The two differ by ~45 blocks at Everest, and using
        // the cell grid here would put a whole summit in the deep-cave regime.
        c[5] = targetSurfaceY(bx + 0.5, bz + 0.5);
    }

    private double reliefAt(int bx, int bz) {
        fillColumn(bx, bz);
        return reliefCache.get()[2];
    }


    /** 0 below sea level, ramping to 1 a few blocks above it. Terrain CHARACTER
     * must not be dramatic underwater: relief is max|dh| to neighbours ~9 km out,
     * so a shoal in a bay surrounded by 1000 m deep ocean reads as maximally rugged
     * and gets FACTOR_RUGGED, i.e. ~25 blocks of noise freedom. That punched the
     * seabed straight through the sea surface as rock pillars (measured: intended
     * seabed y=54, generated terrain y=77, a spire reaching y=100 in Antioch's
     * bay). Ocean floor should hug the bathymetry. */
    private double landGate(int bx, int bz) {
        fillColumn(bx, bz);
        double above = reliefCache.get()[4] - EarthMapSpec.SEA_LEVEL;
        return Math.clamp(above / 8.0, 0.0, 1.0);
    }

    /** 0 (flat) .. 1 (very rugged), from metres of local relief over ~9 km. */
    private double reliefNorm(int bx, int bz) {
        return Math.clamp(reliefAt(bx, bz) / RELIEF_FULL, 0.0, 1.0)
                * landGate(bx, bz);                      // the seabed stays calm
    }

    private final class EarthFactor implements DensityFunction {
        @Override
        public double compute(FunctionContext c) {
            double n = reliefNorm(c.blockX(), c.blockZ());   // also fills the column cache
            double relief = FACTOR_FLAT + (FACTOR_RUGGED - FACTOR_FLAT) * n;
            double freeboard = reliefCache.get()[4] - EarthMapSpec.SEA_LEVEL;
            if (freeboard < 0.0) {
                return relief;                                // seabed: nothing to protect
            }
            // MEASURED FIX: the guard used to be `freeboard <= 0`, which sent
            // every column sitting EXACTLY on the waterline down the seabed
            // path and gave it no protection at all -- factor 9, i.e. ~3.6
            // blocks of noise, straddling sea level. That is where essentially
            // all of the measured drowning came from: 2.5% of land columns went
            // under, and they were not marginal coastline -- most were ground
            // the raster puts above 60 m, drowned only because the coarse cell
            // grid had flattened them onto the waterline first.
            //
            // A column at freeboard 0 is the shoreline, not the sea floor, and
            // it is the one place the surface most needs pinning. Flooring the
            // divisor at 1 gives it FACTOR_PINNED (~0.5 blocks of wobble) and a
            // crisp waterline; genuinely submerged columns still take the
            // seabed path above.
            return Math.min(FACTOR_PINNED,
                    Math.max(relief, FREEBOARD_TIGHTNESS / Math.max(freeboard, 1.0)));
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return Math.min(FACTOR_RUGGED, FACTOR_FLAT); }
        @Override public double maxValue() { return FACTOR_PINNED; }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0).codec();      // never serialised
        }
    }

    /** Replaces vanilla's {@code jaggedness} spline: ridge-noise amplitude, so
     * spikes appear on real mountain flanks and nowhere else. */
    private final class EarthJagged implements DensityFunction {
        @Override
        public double compute(FunctionContext c) {
            double n = reliefNorm(c.blockX(), c.blockZ());
            return JAGGED_MAX * n * n;                        // squared: flat land stays flat
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return JAGGED_MAX; }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0).codec();
        }
    }

    /**
     * A climate axis read from {@link EarthClimate}, standing in for one of
     * vanilla's noise leaves. {@code slot} indexes {@code EarthClimate.params}:
     * 2 continentalness, 3 erosion, 5 weirdness.
     *
     * <p>Reports the WRAPPED node's value range rather than its own, because
     * {@code mapAll} maps children before parents, so by the time the visitor
     * sees the factor/jaggedness splines their coordinates are already these --
     * and those splines are still identified by their value range. Changing the
     * reported range would silently un-hook the very splines we are trying to
     * keep.
     */
    private final class EarthAxis implements DensityFunction {
        private final DensityFunction inner;
        private final int slot;

        EarthAxis(DensityFunction inner, int slot) {
            this.inner = inner;
            this.slot = slot;
        }

        @Override
        public double compute(FunctionContext c) {
            double[] a = axisCache.get();
            int bx = c.blockX();
            int bz = c.blockZ();
            if (a[0] != bx || a[1] != bz) {
                double[] p = EarthClimate.params(earth, sampler, bx + 0.5, bz + 0.5,
                        (int) Math.round(sampler.heightAt(bx + 0.5, bz + 0.5)), seed);
                a[0] = bx;
                a[1] = bz;
                if (p == null) {
                    a[2] = 0;
                    a[3] = 0;
                    a[4] = 0;
                } else {
                    a[2] = p[2];
                    a[3] = p[3];
                    a[4] = p[5];
                }
            }
            return switch (slot) {
                case 2 -> a[2];
                case 3 -> a[3];
                default -> a[4];
            };
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapAll(Visitor v) { return v.apply(this); }
        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return inner.minValue(); }
        @Override public double maxValue() { return inner.maxValue(); }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return inner.codec();
        }
    }

    /** Per-thread column cache for the three Earth axes: [bx, bz, C, E, W]. */
    private final ThreadLocal<double[]> axisCache =
            ThreadLocal.withInitial(() -> new double[] {Double.NaN, Double.NaN, 0, 0, 0});

    /**
     * Vanilla's factor spline with our freeboard floor applied on top: the
     * surface may be looser than vanilla wants but never looser than the
     * waterline can survive. Deliberately only ever RAISES factor (tightens),
     * so vanilla's shape is preserved wherever there is room.
     */
    /**
     * Relief normalised for ANY column, land or sea.
     *
     * <p>{@link #reliefNorm} multiplies by {@link #landGate}, which is 0 below
     * sea level -- that is why the seabed has no character anywhere: trenches
     * and mid-ocean ridges exist in the bathymetry (measured 29 and 39 blocks of
     * range) but are rendered as smooth ramps. The constraint was never
     * "underwater", it was "not enough water overhead to hide the relief", so
     * this gates on water depth instead.
     */
    private double reliefNormAny(int bx, int bz) {
        fillColumn(bx, bz);
        double clearance = reliefCache.get()[4] - EarthMapSpec.SEA_LEVEL;
        double gate = clearance >= 0
                ? Math.clamp(clearance / 8.0, 0.0, 1.0)
                : Math.clamp(-clearance / SEABED_RELIEF_FULL, 0.0, 1.0);
        return Math.clamp(reliefAt(bx, bz) / RELIEF_FULL, 0.0, 1.0) * gate;
    }

    /**
     * Vanilla's jaggedness spline, with a seabed term added underneath.
     *
     * <p>Vanilla's spline is identically 0 for continentalness &lt;= -0.11
     * (TerrainProvider.overworldJaggedness), i.e. it structurally refuses to
     * make jagged seafloor -- reasonable for a synthesised world, wrong for one
     * carrying real bathymetry. This only ever ADDS, so land is untouched.
     */
    private final class SeabedJagged implements DensityFunction {
        private final DensityFunction spline;

        SeabedJagged(DensityFunction spline) {
            this.spline = spline;
        }

        @Override
        public double compute(FunctionContext c) {
            double v = spline.compute(c);
            fillColumn(c.blockX(), c.blockZ());
            if (reliefCache.get()[4] - EarthMapSpec.SEA_LEVEL >= 0) {
                return v;
            }
            double n = reliefNormAny(c.blockX(), c.blockZ());
            // Jaggedness is added to DEPTH, so an amplitude j displaces the
            // surface by up to j * DEPTH_SLOPE blocks -- 0.6 is 77 blocks. Left
            // unbounded it lifted the mid-Atlantic ridge clean out of the water
            // (measured: seabed RMSE 2.4 -> 18.0 and 12.5% spurious land).
            // Bound the push to a fraction of the water actually overhead.
            double water = EarthMapSpec.SEA_LEVEL - reliefCache.get()[4];
            double cap = (water * SEABED_JAG_HEADROOM) / DEPTH_SLOPE;
            return Math.max(v, Math.min(JAGGED_MAX * n * n, cap));
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return spline.minValue(); }
        @Override public double maxValue() { return Math.max(spline.maxValue(), JAGGED_MAX); }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return spline.codec();
        }
    }

    private final class FreeboardGuard implements DensityFunction {
        private final DensityFunction spline;

        FreeboardGuard(DensityFunction spline) {
            this.spline = spline;
        }

        @Override
        public double compute(FunctionContext c) {
            double v = spline.compute(c);
            fillColumn(c.blockX(), c.blockZ());
            double freeboard = reliefCache.get()[4] - EarthMapSpec.SEA_LEVEL;
            if (freeboard >= 0.0) {
                return Math.min(FACTOR_PINNED,
                        Math.max(v, FREEBOARD_TIGHTNESS / Math.max(freeboard, 1.0)));
            }
            // SYMMETRIC UNDERWATER CASE. The old landGate simply pinned the whole
            // seabed, which is why there are no undersea ridges or escarpments
            // anywhere. The real constraint was never "underwater" -- it was
            // "not enough water overhead to hide the relief": a shoal beside a
            // deep drop reads as maximally rugged and punches rock spires
            // through the sea surface.
            //
            // So use the same rule with WATER DEPTH as the freeboard. A shoal
            // 4 blocks down gets pinned; the abyssal plain 60 blocks down keeps
            // vanilla's own factor and is free to have character. Capped lower
            // than the land case because a high factor collapses vanilla's
            // near-surface zone and lets caves eat the floor (see FACTOR_PINNED).
            double water = -freeboard;
            return Math.min(FACTOR_SEABED_MAX,
                    Math.max(v, FREEBOARD_TIGHTNESS / Math.max(water, 1.0)));
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return spline.minValue(); }
        @Override public double maxValue() { return FACTOR_PINNED; }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return spline.codec();
        }
    }

    /**
     * Stands in for {@code slopedCheese} in vanilla's cave-regime rangeChoice,
     * scaled so the switch lands at exactly {@link #SURFACE_ZONE_BLOCKS} below
     * the surface no matter what {@code factor} is doing.
     */
    /**
     * The main 3D terrain noise, scaled down where there is little freeboard so
     * the waterline survives without touching {@code factor}. Symmetric below
     * sea level, using water depth, so shoals are damped too.
     */
    private final class AttenuatedNoise implements DensityFunction {
        private final DensityFunction inner;

        AttenuatedNoise(DensityFunction inner) {
            this.inner = inner;
        }

        @Override
        public double compute(FunctionContext c) {
            double v = inner.compute(c);
            fillColumn(c.blockX(), c.blockZ());
            double clearance = Math.abs(reliefCache.get()[4] - EarthMapSpec.SEA_LEVEL);
            double a = ATTEN_MIN
                    + (1.0 - ATTEN_MIN) * Math.clamp(clearance / ATTEN_FULL_AT, 0.0, 1.0);
            return v * a;
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapAll(Visitor v) { return v.apply(this); }
        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return inner.minValue(); }
        @Override public double maxValue() { return inner.maxValue(); }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return inner.codec();
        }
    }

    private final class CaveDepthSwitch implements DensityFunction {
        @Override
        public double compute(FunctionContext c) {
            fillColumn(c.blockX(), c.blockZ());
            double below = reliefCache.get()[5] - c.blockY();
            return SURFACE_DENSITY_THRESHOLD * (below / SURFACE_ZONE_BLOCKS);
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return -1000.0; }
        @Override public double maxValue() { return 1000.0; }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.constant(0).codec();
        }
    }

    /**
     * Vanilla's cave-regime rangeChoice, identified by its exact CONSTANTS
     * (-1e6 .. 1.5625) rather than by a value range -- there is only one node in
     * the router with those bounds, and constants do not drift the way computed
     * ranges do.
     */
    private static boolean isCaveRangeChoice(DensityFunction node) {
        if (!"RangeChoice".equals(node.getClass().getSimpleName())) {
            return false;
        }
        try {
            java.lang.reflect.Method lo = node.getClass().getMethod("minInclusive");
            java.lang.reflect.Method hi = node.getClass().getMethod("maxExclusive");
            lo.setAccessible(true);
            hi.setAccessible(true);
            return near((Double) lo.invoke(node), -1000000.0, 1.0)
                    && near((Double) hi.invoke(node), SURFACE_DENSITY_THRESHOLD, 1.0e-6);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Rebuild that rangeChoice with a depth-based input, keeping both branches. */
    private DensityFunction rebuildCaveChoice(DensityFunction node) {
        try {
            java.lang.reflect.Method in = node.getClass().getMethod("whenInRange");
            java.lang.reflect.Method out = node.getClass().getMethod("whenOutOfRange");
            in.setAccessible(true);
            out.setAccessible(true);
            return DensityFunctions.rangeChoice(new CaveDepthSwitch(),
                    -1000000.0, SURFACE_DENSITY_THRESHOLD,
                    (DensityFunction) in.invoke(node), (DensityFunction) out.invoke(node));
        } catch (Throwable t) {
            return node;
        }
    }

    private final class EarthDepth implements DensityFunction {
        private final ThreadLocal<double[]> cache =
                ThreadLocal.withInitial(() -> new double[] {Double.NaN, Double.NaN, 0.0});

        @Override
        public double compute(FunctionContext c) {
            double[] cc = cache.get();
            double h;
            if (cc[0] == c.blockX() && cc[1] == c.blockZ()) {
                h = cc[2];
            } else {
                long t = System.nanoTime();
                h = surfaceHeight(c.blockX() + 0.5, c.blockZ() + 0.5);
                GenProfiler.add("earthDepth.surface", t);
                cc[0] = c.blockX();
                cc[1] = c.blockZ();
                cc[2] = h;
            }
            return (h - c.blockY()) / DEPTH_SLOPE;
        }

        private double surfaceHeight(double wx, double wz) {
            double h = sampler.heightAt(wx, wz);
            // Restore real summits the coarse raster averaged down: lift the
            // surface to the nearest >=6000 m (or prominent) peak's cone. Only
            // where the cone rises above the base, so ranges keep their shape.
            if (peaks != null) {
                Vec3 p = sampler.cubePointAt(wx, wz);
                if (p != null) {
                    double[] ll = earth.toLonLat(p);
                    double cone = peaks.coneElevation(ll[0], ll[1]);
                    if (cone > 0) {
                        double ph = EarthMapSpec.elevationToBlockY(cone);
                        if (ph > h) {
                            h = ph;
                        }
                    }
                }
            }
            return h;
        }

        @Override
        public void fillArray(double[] out, ContextProvider provider) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(provider.forIndex(i));
            }
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(this);
        }

        @Override
        public DensityFunction mapChildren(Visitor visitor) {
            return this;
        }

        @Override public double minValue() { return -4.0; }
        @Override public double maxValue() { return 4.0; }
        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("EarthDepth is not serializable");
        }
    }
}
