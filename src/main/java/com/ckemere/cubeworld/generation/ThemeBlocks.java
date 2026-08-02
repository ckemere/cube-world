package com.ckemere.cubeworld.generation;

import org.bukkit.block.Biome;

/** Bukkit-side rendering of {@link TerrainTheme}: biome and surface blocks. */
public final class ThemeBlocks {

    private ThemeBlocks() {
    }

    public static Biome biome(TerrainTheme theme) {
        return switch (theme) {
            case OCEAN -> Biome.OCEAN;
            case BEACH -> Biome.BEACH;
            case PLAINS -> Biome.PLAINS;
            case HIGHLANDS -> Biome.WINDSWEPT_HILLS;
            case SNOWCAP -> Biome.SNOWY_PLAINS;
            case DESERT -> Biome.DESERT;
            case SAVANNA -> Biome.SAVANNA;
            case FOREST -> Biome.FOREST;
            case JUNGLE -> Biome.JUNGLE;
            case TAIGA -> Biome.TAIGA;
            case TUNDRA -> Biome.SNOWY_TAIGA;
            case NETHER_WASTES -> Biome.NETHER_WASTES;
            case CRIMSON_FOREST -> Biome.CRIMSON_FOREST;
            case WARPED_FOREST -> Biome.WARPED_FOREST;
            case SOUL_SAND_VALLEY -> Biome.SOUL_SAND_VALLEY;
            case BASALT_DELTAS -> Biome.BASALT_DELTAS;
        };
    }
}
