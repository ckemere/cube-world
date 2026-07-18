package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.Vec3;
import net.minecraft.world.level.levelgen.DensityFunction;
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

    private final MapSampler sampler;
    private final double radius;

    private SphereDensity(MapSampler sampler, int faceSize) {
        this.sampler = sampler;
        this.radius = RADIUS_FACTOR * faceSize;
    }

    public static SphereDensity forSampler(MapSampler sampler, int faceSize) {
        return new SphereDensity(sampler, faceSize);
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

    private SphereContext sphereFrom(DensityFunction.FunctionContext c) {
        if (c instanceof SphereContext s) {
            return s;
        }
        if (c instanceof DualContext d) {
            return new SphereContext(d.sx, d.sy, d.sz);
        }
        // Any other context (vanilla NoiseChunk cells, nested probes): fold it
        // from the real coordinates it carries.
        Vec3 dir = unit(c.blockX() + 0.5, c.blockZ() + 0.5);
        if (dir == null) {
            return new SphereContext(c.blockX(), c.blockY(), c.blockZ());
        }
        double r = radius + c.blockY();
        return new SphereContext(
                (int) Math.round(dir.x() * r),
                (int) Math.round(dir.y() * r),
                (int) Math.round(dir.z() * r));
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
            if (node instanceof Remap) {
                return node;
            }
            return switch (node.getClass().getSimpleName()) {
                case "Noise", "Shift", "ShiftA", "ShiftB", "ShiftedNoise" -> new Remap(node);
                default -> node;
            };
        }
    }
}
