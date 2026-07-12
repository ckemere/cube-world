package com.ckemere.cubeworld.generation;

import org.bukkit.Material;
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
        };
    }

    public static Material topBlock(TerrainTheme theme) {
        return switch (theme) {
            case OCEAN -> Material.GRAVEL;
            case BEACH -> Material.SAND;
            case PLAINS, HIGHLANDS -> Material.GRASS_BLOCK;
            case SNOWCAP -> Material.SNOW_BLOCK;
        };
    }

    public static Material fillerBlock(TerrainTheme theme) {
        return switch (theme) {
            case OCEAN -> Material.STONE;
            case BEACH -> Material.SANDSTONE;
            case PLAINS -> Material.DIRT;
            case HIGHLANDS, SNOWCAP -> Material.STONE;
        };
    }

    public static boolean snowCovered(TerrainTheme theme) {
        return theme == TerrainTheme.SNOWCAP;
    }
}
