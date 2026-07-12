package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeSurface;
import com.ckemere.cubeworld.geometry.Vec3;
import java.util.EnumMap;
import java.util.Map;

/**
 * Demo map: rolling continents computed from a smooth function of the cube
 * surface point, precomputed per chunk cell. Because the function lives on
 * the (folded) cube, heights and themes agree along every identified edge by
 * construction — no per-edge fixups. Swap the function for real planetary
 * data later; the plumbing stays identical.
 */
public final class SphericalDemoSpec implements MapSpec {

    public static final int SEA_LEVEL = 62;

    private final int cells;
    private final Map<CubeFace, double[][]> heights = new EnumMap<>(CubeFace.class);
    private final Map<CubeFace, TerrainTheme[][]> themes = new EnumMap<>(CubeFace.class);

    public SphericalDemoSpec(CubeGeometry geometry) {
        this.cells = geometry.faceSize() / 16;
        CubeSurface surface = new CubeSurface(geometry);
        for (CubeFace face : CubeFace.values()) {
            double[][] h = new double[cells][cells];
            TerrainTheme[][] t = new TerrainTheme[cells][cells];
            for (int cx = 0; cx < cells; cx++) {
                for (int cz = 0; cz < cells; cz++) {
                    double wx = geometry.faceMinX(face) + cx * 16 + 8;
                    double wz = geometry.faceMinZ(face) + cz * 16 + 8;
                    double height = heightFunction(surface.point(face, wx, wz));
                    h[cx][cz] = height;
                    t[cx][cz] = themeFor(height);
                }
            }
            heights.put(face, h);
            themes.put(face, t);
        }
    }

    /** Smooth "continents" on the cube surface; range roughly 48..92. */
    private static double heightFunction(Vec3 p) {
        return 69.0
                + 10.0 * Math.sin(3.0 * p.x() + 0.5) * Math.cos(2.0 * p.z() - 1.0)
                + 8.0 * Math.sin(2.2 * p.y() + 2.0)
                + 4.0 * Math.sin(5.0 * (p.x() + p.y() + p.z()));
    }

    private static TerrainTheme themeFor(double height) {
        if (height < SEA_LEVEL - 2) {
            return TerrainTheme.OCEAN;
        }
        if (height < SEA_LEVEL + 2) {
            return TerrainTheme.BEACH;
        }
        if (height < 78) {
            return TerrainTheme.PLAINS;
        }
        if (height < 85) {
            return TerrainTheme.HIGHLANDS;
        }
        return TerrainTheme.SNOWCAP;
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
