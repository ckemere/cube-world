package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;

/**
 * The map: per face, a square grid of chunk cells, each specifying a surface
 * height and a terrain theme. This is the unit map data will arrive in
 * (an Earth raster resampled per cube face, for instance).
 *
 * <p>Seam contract: cells along identified edges must describe the same
 * terrain. Implementations that sample a continuous function of the
 * {@link com.ckemere.cubeworld.geometry.CubeSurface} 3D point satisfy this by
 * construction.
 */
public interface MapSpec {

    /** Cells per face edge (chunk resolution: faceSize / 16). */
    int cellsPerFace();

    /** Surface height (block Y of the top solid block) for a cell. */
    double heightAt(CubeFace face, int cellX, int cellZ);

    /** Terrain theme for a cell. */
    TerrainTheme themeAt(CubeFace face, int cellX, int cellZ);
}
