package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.MarginSource;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/**
 * One biome per cube face; seam margins take the biome of the face they
 * mirror (so grass/foliage colors continue across the seam); void elsewhere.
 */
public final class CubeWorldBiomeProvider extends BiomeProvider {

    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final int marginBlocks;

    public CubeWorldBiomeProvider(CubeTopology topology, int marginBlocks) {
        this.topology = topology;
        this.geometry = topology.geometry();
        this.marginBlocks = marginBlocks;
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        CubeFace face = geometry.faceAt(x, z);
        if (face != null) {
            return FaceTheme.of(face).biome();
        }
        MarginSource source = topology.marginSource(x + 0.5, z + 0.5, marginBlocks);
        if (source != null) {
            CubeFace sourceFace = geometry.faceAt(
                    (int) Math.floor(source.source().x()), (int) Math.floor(source.source().z()));
            if (sourceFace != null) {
                return FaceTheme.of(sourceFace).biome();
            }
        }
        return Biome.THE_VOID;
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
