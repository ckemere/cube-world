package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/** One biome per cube face; void outside the cross. */
public final class CubeWorldBiomeProvider extends BiomeProvider {

    private final CubeGeometry geometry;

    public CubeWorldBiomeProvider(CubeGeometry geometry) {
        this.geometry = geometry;
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        CubeFace face = geometry.faceAt(x, z);
        return face == null ? Biome.THE_VOID : FaceTheme.of(face).biome();
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        List<Biome> biomes = new ArrayList<>();
        for (FaceTheme theme : FaceTheme.values()) {
            biomes.add(theme.biome());
        }
        biomes.add(Biome.THE_VOID);
        return biomes;
    }
}
