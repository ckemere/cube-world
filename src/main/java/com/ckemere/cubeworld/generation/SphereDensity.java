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

    private final MapSampler sampler;
    private final double radius;
    private final boolean earthHeight;
    private final EarthData earth;
    private final PeakField peaks;

    private SphereDensity(MapSampler sampler, int faceSize, boolean earthHeight, EarthData earth) {
        this.sampler = sampler;
        this.radius = RADIUS_FACTOR * faceSize;
        this.earthHeight = earthHeight;
        this.earth = earth;
        this.peaks = (earthHeight && earth != null) ? PeakField.get() : null;
    }

    public static SphereDensity forSampler(MapSampler sampler, int faceSize) {
        return new SphereDensity(sampler, faceSize, false, null);
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

    // Per-thread cache of the last column's unit direction: [wx, wz, dx, dy, dz,
    // present]. Every noise node folds through sphereFrom with the same context,
    // and columns repeat across Y, so this collapses ~20 cube-point resolves per
    // density sample (and per Y) down to one.
    private final ThreadLocal<double[]> unitCache =
            ThreadLocal.withInitial(() -> new double[] {Double.NaN, Double.NaN, 0, 0, 0, 0});

    private SphereContext sphereFrom(DensityFunction.FunctionContext c) {
        if (c instanceof SphereContext s) {
            return s;
        }
        if (c instanceof DualContext d) {
            return new SphereContext(d.sx, d.sy, d.sz);
        }
        // Any other context (vanilla NoiseChunk cells, nested probes): fold it
        // from the real coordinates it carries, reusing the cached direction.
        double qx = c.blockX() + 0.5;
        double qz = c.blockZ() + 0.5;
        double[] u = unitCache.get();
        if (u[0] != qx || u[1] != qz) {
            Vec3 dir = unit(qx, qz);
            u[0] = qx;
            u[1] = qz;
            if (dir != null) {
                u[2] = dir.x();
                u[3] = dir.y();
                u[4] = dir.z();
                u[5] = 1;
            } else {
                u[5] = 0;
            }
        }
        if (u[5] == 0) {
            return new SphereContext(c.blockX(), c.blockY(), c.blockZ());
        }
        double r = radius + c.blockY();
        return new SphereContext(
                (int) Math.round(u[2] * r),
                (int) Math.round(u[3] * r),
                (int) Math.round(u[4] * r));
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
                default -> {
                }
            }
            if (earthHeight && isOffsetToDepth(node)) {
                return new EarthDepth();
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
                h = surfaceHeight(c.blockX() + 0.5, c.blockZ() + 0.5);
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
