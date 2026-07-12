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
    SNOWCAP
}
