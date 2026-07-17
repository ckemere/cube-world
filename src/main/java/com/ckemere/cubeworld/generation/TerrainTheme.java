package com.ckemere.cubeworld.generation;

/**
 * Elevation-banded terrain classes for map-spec cells. Pure data (no Bukkit
 * types) so map logic stays unit-testable; {@link ThemeBlocks} maps each
 * theme to concrete biomes and blocks at generation time.
 */
public enum TerrainTheme {
    OCEAN,
    BEACH,
    PLAINS,
    HIGHLANDS,
    SNOWCAP,
    // Nether surface themes (the cube nether is an open hellscape).
    NETHER_WASTES,
    CRIMSON_FOREST,
    WARPED_FOREST,
    SOUL_SAND_VALLEY,
    BASALT_DELTAS
}
