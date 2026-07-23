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
    private static final double LAND_EXAGGERATION = 0.0285;  // blocks per metre up (<=HIGH_BREAK)
    private static final double HIGH_BREAK = 4000.0;         // metres: exaggeration knee
    private static final double HIGH_EXAGGERATION = 0.0146;  // blocks per metre above HIGH_BREAK
    // Oceans read deeper than the old gentle scale, but capped near vanilla's
    // deepest (~45 blocks): 100+-block abyssal water tanks generation (vanilla
    // lighting + fluid ticks through the whole water column).
    private static final double OCEAN_SHELF = 0.020;         // blocks per metre down (<=OCEAN_BREAK)
    private static final double OCEAN_BREAK = 1500.0;        // metres: ocean exaggeration knee
    // Steeper abyssal + a deeper cap so real trenches read as trenches: a -3000 m
    // sea floor sits ~36 blocks down, -6000 m ~48, the deepest (-10935 m) hits the
    // 60-block cap (y3, still clears bedrock). Safe now aquifers are off and ocean
    // water is stable source (no fluid ticks); only bounded lighting cost remains.
    private static final double OCEAN_DEEP = 0.004;          // blocks per metre below OCEAN_BREAK
    private static final double OCEAN_FLOOR = 60.0;          // max blocks below sea (min y 3)
    private static final double LAND_CAP = 253.0;
    private static final double TERRAIN_CEIL = 250.0;        // just under vanilla's top slide

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

    public static double elevationToBlockY(double meters) {
        double y;
        if (meters >= 0) {
            // Full exaggeration up to HIGH_BREAK so ordinary ranges tower;
            // gentler above it so the 6000-8849 m giants stay correctly ordered
            // and fit under vanilla's terrain top-slide (~y252), which otherwise
            // clips every 8000er to the same ceiling.
            double blocks;
            if (meters <= HIGH_BREAK) {
                blocks = meters * LAND_EXAGGERATION;
            } else {
                blocks = HIGH_BREAK * LAND_EXAGGERATION
                        + (meters - HIGH_BREAK) * HIGH_EXAGGERATION;
            }
            y = SEA_LEVEL + Math.min(blocks, LAND_CAP);
        } else {
            // Mirror the mountain treatment downward: steep for shelves and
            // moderate seas so they read deep, gentler for the abyssal tail so
            // the deepest trench (-10935 m) still clears bedrock.
            double depth = -meters;
            double blocks;
            if (depth <= OCEAN_BREAK) {
                blocks = depth * OCEAN_SHELF;
            } else {
                blocks = OCEAN_BREAK * OCEAN_SHELF + (depth - OCEAN_BREAK) * OCEAN_DEEP;
            }
            y = SEA_LEVEL - Math.min(blocks, OCEAN_FLOOR);
        }
        return Math.max(-60.0, Math.min(TERRAIN_CEIL, y));
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
