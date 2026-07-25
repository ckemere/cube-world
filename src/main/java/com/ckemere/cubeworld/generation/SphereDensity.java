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
    /** Off switch for A/B: {@code -Dcubeworld.earthShape=false} restores vanilla's
     * own (geography-blind) factor/jaggedness. */
    private static final boolean EARTH_SHAPE =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.earthShape", "true"));

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

    private SphereDensity(MapSampler sampler, int faceSize, boolean earthHeight, EarthData earth) {
        this(sampler, faceSize, earthHeight, earth, false);
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
    public NoiseRouter fold(NoiseRouter router) {
        return router.mapAll(new SphereVisitor());
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
                    return new Remap(node);
                }
                case "BlendedNoise" -> {
                    if (foldBlendedNoise) {
                        return new Remap(node);
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
            if (earthHeight && EARTH_SHAPE && earth != null) {
                if (isFactorSpline(node)) {
                    return new EarthFactor();
                }
                if (isJaggednessSpline(node)) {
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
    private final ThreadLocal<double[]> reliefCache =
            ThreadLocal.withInitial(() -> new double[] {Double.NaN, Double.NaN, 0.0});

    private double reliefAt(int bx, int bz) {
        double[] c = reliefCache.get();
        if (c[0] == bx && c[1] == bz) {
            return c[2];
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
        return r;
    }

    /** 0 (flat) .. 1 (very rugged), from metres of local relief over ~9 km. */
    private double reliefNorm(int bx, int bz) {
        return Math.clamp(reliefAt(bx, bz) / RELIEF_FULL, 0.0, 1.0);
    }

    /**
     * Replaces vanilla's {@code factor} spline. factor multiplies depth in
     * {@code 4 * (depth * factor).quarterNegative()}, so it sets how sharply
     * density crosses zero — i.e. how much vertical room the 3D noise gets to
     * carve cliffs and overhangs. dy ~ 32/factor blocks, so a LOW factor gives
     * dramatic mountain terrain and a HIGH factor a tight surface that hugs the
     * Earth elevation. Range matches the vanilla spline it replaces.
     */
    private final class EarthFactor implements DensityFunction {
        @Override
        public double compute(FunctionContext c) {
            double n = reliefNorm(c.blockX(), c.blockZ());
            return FACTOR_FLAT + (FACTOR_RUGGED - FACTOR_FLAT) * n;
        }

        @Override
        public void fillArray(double[] out, ContextProvider p) {
            for (int i = 0; i < out.length; i++) {
                out[i] = compute(p.forIndex(i));
            }
        }

        @Override public DensityFunction mapChildren(Visitor v) { return this; }
        @Override public double minValue() { return Math.min(FACTOR_RUGGED, FACTOR_FLAT); }
        @Override public double maxValue() { return Math.max(FACTOR_RUGGED, FACTOR_FLAT); }
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
