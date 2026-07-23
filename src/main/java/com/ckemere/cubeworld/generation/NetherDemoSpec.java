package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeSurface;
import com.ckemere.cubeworld.geometry.Vec3;
import java.util.EnumMap;
import java.util.Map;

/**
 * Support field for the cube nether. <b>It no longer generates terrain.</b> The
 * nether is now real vanilla generation folded onto the cube — netherrack relief,
 * the lava sea, the bedrock roof/floor, caves and MultiNoise biomes all come from
 * vanilla's own generator (see {@link SphereDensity#forNether},
 * {@code SphereRouterHook.installNether} and {@link NetherSurfaceFold}). This spec
 * survives only to give the nether {@link MapSampler} something to construct
 * (the fold uses the sampler's {@code cubePointAt}, which is independent of this
 * spec) plus two peripheral, non-authoritative helpers:
 *
 * <ul>
 *   <li>a flat nominal surface height ({@link #heightAt}) used as a heightmap
 *       hint by {@code getBaseHeight} — the real surface is vanilla noise;
 *   <li>a seam-continuous biome-<i>zone</i> field ({@link #themeAt}) that the
 *       offline biome-raster/census tooling samples as a coarse approximation.
 *       It is NOT how biomes are actually chosen (that is vanilla's MultiNoise
 *       nether source).
 * </ul>
 *
 * The old sine <i>terrain height</i> field (hand-rolled "hellscape ridges") was
 * the rejected demo approach and has been removed.
 */
public final class NetherDemoSpec implements MapSpec {

    /** Lava-sea surface (vanilla nether sea level). */
    public static final int LAVA_LEVEL = 32;

    /** Flat nominal surface, a heightmap hint only (real relief is vanilla noise). */
    private static final double NOMINAL_SURFACE = 64.0;

    // Target nether biome-zone patch size in blocks. The zone field is a function
    // of the UNIT-cube point (so it stays seam-continuous), which is
    // face-size-independent — one face is always 2 cube-units wide whether it is
    // 10240 blocks (overworld) or 1280 (the 1:8-compressed nether). We scale the
    // field frequency by face size so a patch is ~BIOME_BLOCKS regardless of
    // compression.
    private static final double BIOME_BLOCKS = 300.0;

    // Off-axis unit directions for the field. Axis-aligned sines degenerate to a
    // regular plaid on faces where one cube coordinate is ~constant (the poles);
    // off-axis directions + a domain warp keep the zones organic and irregular.
    private static final double[][] DIRS = {
            {0.78, 0.42, 0.46}, {-0.44, 0.80, 0.40}, {0.40, -0.50, 0.77},
    };

    private final int cells;
    private final Map<CubeFace, TerrainTheme[][]> themes = new EnumMap<>(CubeFace.class);

    public NetherDemoSpec(CubeGeometry geometry, WorldSeeds seeds) {
        this.cells = geometry.faceSize() / 16;
        CubeSurface surface = new CubeSurface(geometry);
        // A wavelength of L blocks needs angular frequency pi*faceSize/L on the
        // unit-cube axis (p spans 2 over faceSize blocks), so a patch stays ~L
        // blocks whatever the face compression (overworld 10240 / nether 1280).
        double biomeFreq = Math.PI * geometry.faceSize() / BIOME_BLOCKS;
        for (CubeFace face : CubeFace.values()) {
            TerrainTheme[][] t = new TerrainTheme[cells][cells];
            for (int cx = 0; cx < cells; cx++) {
                for (int cz = 0; cz < cells; cz++) {
                    double wx = geometry.faceMinX(face) + cx * 16 + 8;
                    double wz = geometry.faceMinZ(face) + cz * 16 + 8;
                    Vec3 p = surface.point(face, wx, wz);
                    t[cx][cz] = themeFor(organicField(p, biomeFreq, seeds, 17));
                }
            }
            themes.put(face, t);
        }
    }

    /**
     * Domain-warped, off-axis interference field in roughly [-2.5, 2.5]. It is a
     * function of the cube point (continuous across every stitched seam) but,
     * unlike a sum of axis-aligned sines, organic rather than a separable plaid.
     * {@code base} picks six consecutive seeded phase slots (0..31).
     */
    private static double organicField(Vec3 p, double f, WorldSeeds seeds, int base) {
        double x = p.x();
        double y = p.y();
        double z = p.z();
        // domain warp: bend the coordinates so wavefronts are wavy, not straight
        double wx = x + 0.33 * Math.sin(f * 0.33 * (0.9 * y + 0.4 * z) + seeds.phase(base));
        double wy = y + 0.33 * Math.sin(f * 0.33 * (0.9 * z + 0.4 * x) + seeds.phase(base + 1));
        double wz = z + 0.33 * Math.sin(f * 0.33 * (0.9 * x + 0.4 * y) + seeds.phase(base + 2));
        double a = Math.sin(f * dot(DIRS[0], wx, wy, wz) + seeds.phase(base + 3));
        double b = Math.sin(f * 0.83 * dot(DIRS[1], wx, wy, wz) + seeds.phase(base + 4));
        double c = Math.sin(f * 1.19 * dot(DIRS[2], wx, wy, wz) + seeds.phase(base + 5));
        return a + 0.8 * b + 0.7 * c;
    }

    private static double dot(double[] d, double x, double y, double z) {
        return d[0] * x + d[1] * y + d[2] * z;
    }

    private static TerrainTheme themeFor(double field) {
        if (field < -1.0) {
            return TerrainTheme.SOUL_SAND_VALLEY;
        }
        if (field < -0.1) {
            return TerrainTheme.NETHER_WASTES;
        }
        if (field < 0.7) {
            return TerrainTheme.CRIMSON_FOREST;
        }
        if (field < 1.3) {
            return TerrainTheme.WARPED_FOREST;
        }
        return TerrainTheme.BASALT_DELTAS;
    }

    @Override
    public TerrainTheme fallbackTheme() {
        return TerrainTheme.NETHER_WASTES;
    }

    @Override
    public int cellsPerFace() {
        return cells;
    }

    @Override
    public double heightAt(CubeFace face, int cellX, int cellZ) {
        return NOMINAL_SURFACE;
    }

    @Override
    public TerrainTheme themeAt(CubeFace face, int cellX, int cellZ) {
        return themes.get(face)[cellX][cellZ];
    }
}
