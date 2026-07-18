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

    public static Material topBlock(TerrainTheme theme) {
        return switch (theme) {
            case OCEAN -> Material.GRAVEL;
            case BEACH -> Material.SAND;
            case PLAINS, HIGHLANDS -> Material.GRASS_BLOCK;
            case SNOWCAP -> Material.SNOW_BLOCK;
            case DESERT -> Material.SAND;
            case SAVANNA, FOREST, JUNGLE, TAIGA -> Material.GRASS_BLOCK;
            case TUNDRA -> Material.GRASS_BLOCK;
            case NETHER_WASTES -> Material.NETHERRACK;
            case CRIMSON_FOREST -> Material.CRIMSON_NYLIUM;
            case WARPED_FOREST -> Material.WARPED_NYLIUM;
            case SOUL_SAND_VALLEY -> Material.SOUL_SAND;
            case BASALT_DELTAS -> Material.BASALT;
        };
    }

    public static Material fillerBlock(TerrainTheme theme) {
        return switch (theme) {
            case OCEAN -> Material.STONE;
            case BEACH -> Material.SANDSTONE;
            case PLAINS -> Material.DIRT;
            case HIGHLANDS, SNOWCAP -> Material.STONE;
            case DESERT -> Material.SANDSTONE;
            case SAVANNA, FOREST, JUNGLE, TAIGA, TUNDRA -> Material.DIRT;
            case NETHER_WASTES, CRIMSON_FOREST, WARPED_FOREST -> Material.NETHERRACK;
            case SOUL_SAND_VALLEY -> Material.SOUL_SOIL;
            case BASALT_DELTAS -> Material.BLACKSTONE;
        };
    }

    public static boolean snowCovered(TerrainTheme theme) {
        return theme == TerrainTheme.SNOWCAP || theme == TerrainTheme.TUNDRA;
    }
}
