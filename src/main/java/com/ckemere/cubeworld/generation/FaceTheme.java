package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import org.bukkit.Material;
import org.bukkit.block.Biome;

/**
 * Per-face look for the flat test world: a distinct biome (for grass/foliage
 * color on a vanilla client) plus matching surface blocks so boundaries are
 * obvious at a glance.
 */
public enum FaceTheme {
    NORTH_POLE(CubeFace.NORTH_POLE, Biome.SNOWY_PLAINS, Material.GRASS_BLOCK, Material.DIRT, true),
    EQ_PRIME(CubeFace.EQ_PRIME, Biome.PLAINS, Material.GRASS_BLOCK, Material.DIRT, false),
    EQ_EAST(CubeFace.EQ_EAST, Biome.DESERT, Material.SAND, Material.SANDSTONE, false),
    EQ_BACK(CubeFace.EQ_BACK, Biome.JUNGLE, Material.GRASS_BLOCK, Material.DIRT, false),
    EQ_WEST(CubeFace.EQ_WEST, Biome.BADLANDS, Material.RED_SAND, Material.TERRACOTTA, false),
    SOUTH_POLE(CubeFace.SOUTH_POLE, Biome.MUSHROOM_FIELDS, Material.MYCELIUM, Material.DIRT, false);

    private final CubeFace face;
    private final Biome biome;
    private final Material topBlock;
    private final Material fillerBlock;
    private final boolean snowCovered;

    FaceTheme(CubeFace face, Biome biome, Material topBlock, Material fillerBlock, boolean snowCovered) {
        this.face = face;
        this.biome = biome;
        this.topBlock = topBlock;
        this.fillerBlock = fillerBlock;
        this.snowCovered = snowCovered;
    }

    public static FaceTheme of(CubeFace face) {
        for (FaceTheme theme : values()) {
            if (theme.face == face) {
                return theme;
            }
        }
        throw new IllegalStateException("No theme for face " + face);
    }

    public Biome biome() {
        return biome;
    }

    public Material topBlock() {
        return topBlock;
    }

    public Material fillerBlock() {
        return fillerBlock;
    }

    public boolean snowCovered() {
        return snowCovered;
    }
}
