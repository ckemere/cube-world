package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeSurface;
import com.ckemere.cubeworld.geometry.Vec3;

/**
 * The Earth map: per-cell height and theme sampled on demand from real
 * {@link EarthData} rasters (ETOPO 2022 elevation, WorldClim temperature and
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

    /** Non-linear land curve below {@link #HIGH_BREAK}: metres -> blocks above sea.
     * Slope rises monotonically (0.005 -> 0.046 blocks/m) so shorelines ramp gently
     * while inland ranges keep their height. Ends exactly at
     * 4000 m -> 114 blocks (= 4000 * LAND_EXAGGERATION) for continuity. */
    /**
     * Least freeboard, in blocks, that a pixel above 0 m is allowed to have.
     *
     * <p>1.0 was not enough. The curve gives a floodplain like Mesopotamia about
     * 0.3 blocks, so the floor was doing all the work and putting 64% of the region
     * exactly one block over the waterline -- where the residual terrain noise
     * (measured sd ~0.5 blocks even with the freeboard/factor coupling) dunks a
     * large share of it. The biome source was correctly calling it desert while the
     * player waded through it. Cradle-of-civilisation regions are all low
     * floodplains, so this is where it hurts most.
     *
     * <p>2.0 was tried and rejected: Mesopotamia still came out 35.6% standing
     * water against 13.2% at 3.0. The wobble sits right at this scale, so one block
     * of floor is the difference between a desert and a marsh. The cost of 3.0 is
     * that rivers, which cut to sea level, now run in banks about 3 blocks deep
     * instead of 1 -- measured median shoreline step +3 on river edges, though
     * OCEAN coasts stay gentle at median +1 with half the shoreline at sea level.
     */
    // 2, not 3, since the 3D noise is now attenuated near the waterline
    // (SphereDensity.NOISE_ATTENUATION) rather than being out-run by a raised
    // freeboard. Measured: shoreline wall 73% -> 41% unjumpable.
    //
    // 1 is IMPOSSIBLE, not merely risky. The target is where the density
    // zero-crossing sits, so a target of y=63 puts the topmost SOLID block at
    // y=62 -- the water surface itself. Every low-lying tile read 93-100%
    // drowned at freeboard 1 regardless of what else was tuned.
    //
    // Sweepable so the coastline can be searched without a rebuild.
    private static final double LAND_FREEBOARD_MIN =
            Double.parseDouble(System.getProperty("cubeworld.landFreeboard", "2.0"));

    // NOTE (measured, do not "fix" casually): these knots give 100 m only 0.5
    // blocks and 300 m only 1.8, so with LAND_FREEBOARD_MIN = 3 every land
    // column below ~450 m is floored to exactly y=65 -- the inhabited band of
    // the world is one flat table (lowland slope 0.39 blocks per 6) behind a
    // 3.8-block shoreline wall, 73% of it unjumpable.
    //
    // Steepening the low end was tried and measured: knots
    // {0,60,150,400,800,1500,2500,4000} -> {0,1.2,3.2,8,15,26,50,114} improved
    // the lowland slope to 0.55 (+41%) but moved the unjumpable shoreline
    // fraction only 73% -> 71%, because the step is set by the FLOOR, not the
    // curve. It also cost rmse(flat) 4.3 -> 5.0 and TOTAL 15.4 -> 15.8, and it
    // changes every land height, which is the change TODO item 7 trap 4 warns
    // deleted Antioch's village. Reverted as not worth it on its own.
    //
    // The shoreline wall cannot be fixed here. See FACTOR_PINNED in
    // SphereDensity: lowering the floor needs a tighter surface, a tighter
    // surface collapses vanilla's near-surface zone, and caves then eat the
    // shore. The cave-regime threshold has to be decoupled from `factor` first.
    private static final double[] LOW_M = {0, 100, 300, 800, 1500, 2500, 4000};
    private static final double[] LOW_B = {0, 0.5, 1.8, 6.0, 15.0, 45.0, 114.0};

    private static double interp(double x, double[] xs, double[] ys) {
        if (x <= xs[0]) {
            return ys[0];
        }
        for (int i = 1; i < xs.length; i++) {
            if (x <= xs[i]) {
                double t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
                return ys[i - 1] + t * (ys[i] - ys[i - 1]);
            }
        }
        return ys[ys.length - 1];
    }
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
                // Non-linear below the knee. A single 0.0285 blocks/m ramp made
                // 300 m of real coastal relief an 8.55-block riser, and because
                // 1 block ~ 1 km horizontally that step lands within a few blocks
                // of the waterline -- a cliff exactly where villages try to build.
                // This is gentler near sea level and steeper inland, and is
                // constructed to pass through 4000 m -> 114 blocks so every
                // elevation at or above the knee (Everest included) is unchanged.
                blocks = interp(meters, LOW_M, LOW_B);
            } else {
                blocks = HIGH_BREAK * LAND_EXAGGERATION
                        + (meters - HIGH_BREAK) * HIGH_EXAGGERATION;
            }
            // Anything the data calls land must clear the waterline. The curve
            // gives 100 m only half a block, which rounds onto the sea surface
            // even before noise touches it, so dry land came out as coast.
            y = SEA_LEVEL + Math.max(LAND_FREEBOARD_MIN, Math.min(blocks, LAND_CAP));
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
            // Same story as EarthClimate: WorldClim is land-only, so water fell
            // back to a latitude proxy -- and this one used a steeper 0.65 slope
            // than the climate path's 0.45, so the two disagreed about how warm
            // any given sea was. Both now read the real `sst` layer.
            temp = earth.sample("sst", ll[0], ll[1]);
        }
        if (Double.isNaN(temp)) {
            temp = 27.0 - Math.abs(ll[1]) * 0.65;
        }
        if (Double.isNaN(precip)) {
            precip = 700.0;
        }
        return classify(elev, temp, precip);
    }
}
