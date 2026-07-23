package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeSurface;
import com.ckemere.cubeworld.geometry.Vec3;
import java.util.EnumMap;
import java.util.Map;

/**
 * The cube nether: an open hellscape on the same folded-cube surface as the
 * overworld, with its own height field and a biome field that partitions the
 * surface into the five nether biomes. Both are continuous functions of the
 * cube-surface point, so — exactly like the overworld spec — every stitched
 * edge agrees by construction. Phases 17–20 (height) and 21–23 (theme).
 */
public final class NetherDemoSpec implements MapSpec {

    /** Lava-sea surface. Heights below this flood with lava. */
    public static final int LAVA_LEVEL = 32;

    // Target nether biome patch size in blocks. The biome/terrain fields are
    // functions of the UNIT-cube point (so they stay seam-continuous), which is
    // face-size-independent — one face is always 2 cube-units wide whether it is
    // 10240 blocks (overworld) or 1280 (the 1:8-compressed nether). Without
    // scaling, a sin(2.5*p.x) patch spans the whole face (~1280 blocks) and the
    // nether reads as a few giant biomes. We scale the field frequency by face
    // size so a patch is ~BIOME_BLOCKS regardless of compression.
    private static final double BIOME_BLOCKS = 300.0;    // target nether biome patch size
    private static final double TERRAIN_BLOCKS = 300.0;   // hellscape ridge spacing

    // Off-axis unit directions for the fields. The biome/terrain fields are
    // functions of the cube point (so they're seam-continuous), but AXIS-aligned
    // sines degenerate to a regular plaid on faces where one cube coordinate is
    // ~constant (the poles, and half of each equatorial face) — that's why the
    // nether read as a 4x4 tiled grid. Off-axis directions + a domain warp make
    // the patches organic and irregular, like vanilla's nether.
    private static final double[][] DIRS = {
            {0.78, 0.42, 0.46}, {-0.44, 0.80, 0.40}, {0.40, -0.50, 0.77},
    };

    private final int cells;
    private final Map<CubeFace, double[][]> heights = new EnumMap<>(CubeFace.class);
    private final Map<CubeFace, TerrainTheme[][]> themes = new EnumMap<>(CubeFace.class);

    public NetherDemoSpec(CubeGeometry geometry, WorldSeeds seeds) {
        this.cells = geometry.faceSize() / 16;
        CubeSurface surface = new CubeSurface(geometry);
        // A wavelength of L blocks needs angular frequency pi*faceSize/L on the
        // unit-cube axis (p spans 2 over faceSize blocks), so a patch stays ~L
        // blocks whatever the face compression (overworld 10240 / nether 1280).
        double biomeFreq = Math.PI * geometry.faceSize() / BIOME_BLOCKS;
        double terrainFreq = Math.PI * geometry.faceSize() / TERRAIN_BLOCKS;
        for (CubeFace face : CubeFace.values()) {
            double[][] h = new double[cells][cells];
            TerrainTheme[][] t = new TerrainTheme[cells][cells];
            for (int cx = 0; cx < cells; cx++) {
                for (int cz = 0; cz < cells; cz++) {
                    double wx = geometry.faceMinX(face) + cx * 16 + 8;
                    double wz = geometry.faceMinZ(face) + cz * 16 + 8;
                    Vec3 p = surface.point(face, wx, wz);
                    double height = 44.0 + 12.0 * organicField(p, terrainFreq, seeds, 23);
                    h[cx][cz] = height;
                    t[cx][cz] = themeFor(height, organicField(p, biomeFreq, seeds, 17));
                }
            }
            heights.put(face, h);
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

    private static TerrainTheme themeFor(double height, double field) {
        if (height < LAVA_LEVEL + 3) {
            return TerrainTheme.BASALT_DELTAS; // lava shores
        }
        if (field < -1.0) {
            return TerrainTheme.SOUL_SAND_VALLEY;
        }
        if (field < -0.1) {
            return TerrainTheme.NETHER_WASTES;
        }
        if (field < 0.9) {
            return TerrainTheme.CRIMSON_FOREST;
        }
        return TerrainTheme.WARPED_FOREST;
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
        return heights.get(face)[cellX][cellZ];
    }

    @Override
    public TerrainTheme themeAt(CubeFace face, int cellX, int cellZ) {
        return themes.get(face)[cellX][cellZ];
    }
}
