package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeSurface;
import com.ckemere.cubeworld.geometry.Vec3;

/**
 * The Earth map: per-cell height and theme sampled on demand from real
 * {@link EarthData} rasters (GEBCO elevation, WorldClim temperature and
 * precipitation) through the cube-surface embedding. Continuity across seams
 * is automatic — the embedding folds continuously, so cells on either side of
 * a stitched edge map to the same lon/lat.
 *
 * <p>Sampling is lazy (no per-face precompute) so large faces don't stall at
 * first generation. Real elevation (metres) maps to block height by a
 * piecewise-linear vertical curve: ~20x exaggeration on land (mountains read
 * as mountains at 1&nbsp;km/block) and a gentler slope below sea level so the
 * deepest trenches still fit above bedrock. Themes are a coarse Whittaker
 * classification of the two climate fields — a first-pass biome set; fuller
 * vanilla biome selection comes later.
 */
public final class EarthMapSpec implements MapSpec {

    public static final int SEA_LEVEL = SphericalDemoSpec.SEA_LEVEL;

    // ~28.5x vertical exaggeration on land: Everest (8849 m) lands ~252 blocks
    // above sea, just under the build ceiling, so the full vertical range is
    // used and every mountain is proportionally taller. Ocean is gentler so
    // the deepest trench (-10935 m) still clears bedrock.
    private static final double LAND_EXAGGERATION = 0.0285;  // blocks per metre up
    private static final double OCEAN_SCALE = 0.01145;       // blocks per metre down
    private static final double LAND_CAP = 253.0;
    private static final double OCEAN_CAP = -125.0;

    private final CubeGeometry geometry;
    private final CubeSurface surface;
    private final EarthData earth;
    private final int cells;

    public EarthMapSpec(CubeGeometry geometry, EarthData earth) {
        this.geometry = geometry;
        this.surface = new CubeSurface(geometry);
        this.earth = earth;
        this.cells = geometry.faceSize() / 16;
    }

    private double[] cellLonLat(CubeFace face, int cellX, int cellZ) {
        double wx = geometry.faceMinX(face) + cellX * 16 + 8;
        double wz = geometry.faceMinZ(face) + cellZ * 16 + 8;
        Vec3 p = surface.point(face, wx, wz);
        return earth.toLonLat(p);
    }

    private static double elevationToBlockY(double meters) {
        double y;
        if (meters >= 0) {
            y = SEA_LEVEL + Math.min(meters * LAND_EXAGGERATION, LAND_CAP);
        } else {
            y = SEA_LEVEL + Math.max(meters * OCEAN_SCALE, OCEAN_CAP);
        }
        return Math.max(-60.0, Math.min(315.0, y));
    }

    private static TerrainTheme classify(double elev, double temp, double precip) {
        if (elev < -5) {
            return TerrainTheme.OCEAN;
        }
        if (elev < 3) {
            return TerrainTheme.BEACH;
        }
        if (elev > 3500 || temp < -14) {
            return TerrainTheme.SNOWCAP;
        }
        if (temp < -6) {
            return TerrainTheme.TUNDRA;
        }
        if (temp < 3) {
            return TerrainTheme.TAIGA;
        }
        if (temp < 18) {
            return precip < 500 ? TerrainTheme.PLAINS : TerrainTheme.FOREST;
        }
        if (precip < 250) {
            return TerrainTheme.DESERT;
        }
        if (precip < 900) {
            return TerrainTheme.SAVANNA;
        }
        return TerrainTheme.JUNGLE;
    }

    @Override
    public int cellsPerFace() {
        return cells;
    }

    @Override
    public double heightAt(CubeFace face, int cellX, int cellZ) {
        double[] ll = cellLonLat(face, cellX, cellZ);
        return elevationToBlockY(earth.sample("height", ll[0], ll[1]));
    }

    @Override
    public TerrainTheme themeAt(CubeFace face, int cellX, int cellZ) {
        double[] ll = cellLonLat(face, cellX, cellZ);
        double elev = earth.sample("height", ll[0], ll[1]);
        double temp = earth.sample("temp", ll[0], ll[1]);
        double precip = earth.sample("precip", ll[0], ll[1]);
        if (Double.isNaN(temp)) {
            temp = 27.0 - Math.abs(ll[1]) * 0.65;
        }
        if (Double.isNaN(precip)) {
            precip = 700.0;
        }
        return classify(elev, temp, precip);
    }
}
