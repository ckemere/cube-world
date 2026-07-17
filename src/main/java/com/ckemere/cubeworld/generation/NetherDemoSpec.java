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

    private final int cells;
    private final Map<CubeFace, double[][]> heights = new EnumMap<>(CubeFace.class);
    private final Map<CubeFace, TerrainTheme[][]> themes = new EnumMap<>(CubeFace.class);

    public NetherDemoSpec(CubeGeometry geometry, WorldSeeds seeds) {
        this.cells = geometry.faceSize() / 16;
        CubeSurface surface = new CubeSurface(geometry);
        for (CubeFace face : CubeFace.values()) {
            double[][] h = new double[cells][cells];
            TerrainTheme[][] t = new TerrainTheme[cells][cells];
            for (int cx = 0; cx < cells; cx++) {
                for (int cz = 0; cz < cells; cz++) {
                    double wx = geometry.faceMinX(face) + cx * 16 + 8;
                    double wz = geometry.faceMinZ(face) + cz * 16 + 8;
                    Vec3 p = surface.point(face, wx, wz);
                    double height = heightFunction(p, seeds);
                    h[cx][cz] = height;
                    t[cx][cz] = themeFor(height, biomeField(p, seeds));
                }
            }
            heights.put(face, h);
            themes.put(face, t);
        }
    }

    /** Jagged basins and ridges; range roughly 22..72. Phases 17-20. */
    private static double heightFunction(Vec3 p, WorldSeeds seeds) {
        return 44.0
                + 14.0 * Math.sin(4.0 * p.x() + seeds.phase(17)) * Math.cos(3.0 * p.z() + seeds.phase(18))
                + 9.0 * Math.sin(3.1 * p.y() + seeds.phase(19))
                + 5.0 * Math.sin(7.0 * (p.x() - p.y() + p.z()) + seeds.phase(20));
    }

    /** Smooth field in roughly [-2, 2] partitioning the surface into biomes. */
    private static double biomeField(Vec3 p, WorldSeeds seeds) {
        return Math.sin(2.5 * p.x() + seeds.phase(21)) + Math.cos(3.5 * p.z() + seeds.phase(22))
                * Math.sin(1.7 * p.y() + seeds.phase(23));
    }

    private static TerrainTheme themeFor(double height, double field) {
        if (height < LAVA_LEVEL + 3) {
            return TerrainTheme.BASALT_DELTAS; // lava shores
        }
        if (field < -1.0) {
            return TerrainTheme.SOUL_SAND_VALLEY;
        }
        if (field < 0.2) {
            return TerrainTheme.NETHER_WASTES;
        }
        if (field < 1.1) {
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
