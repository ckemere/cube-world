package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/**
 * Nether biomes from the nether map spec's theme field, continued into seam
 * margins via source resolution (same machinery as the overworld provider);
 * void far off the net.
 */
public final class CubeNetherBiomeProvider extends BiomeProvider {

    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final MapService maps;
    private final int marginBlocks;

    public CubeNetherBiomeProvider(CubeTopology topology, MapService maps, int marginBlocks) {
        this.topology = topology;
        this.geometry = topology.geometry();
        this.maps = maps;
        this.marginBlocks = marginBlocks;
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        boolean onFace = geometry.faceAt(x, z) != null;
        if (!onFace && topology.marginSource(x + 0.5, z + 0.5, marginBlocks) == null) {
            return Biome.THE_VOID;
        }
        MapSampler sampler = maps.mapFor(worldInfo.getSeed()).netherSampler();
        return ThemeBlocks.biome(sampler.themeAt(x + 0.5, z + 0.5));
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        List<Biome> biomes = new ArrayList<>();
        biomes.add(Biome.NETHER_WASTES);
        biomes.add(Biome.CRIMSON_FOREST);
        biomes.add(Biome.WARPED_FOREST);
        biomes.add(Biome.SOUL_SAND_VALLEY);
        biomes.add(Biome.BASALT_DELTAS);
        biomes.add(Biome.THE_VOID);
        return biomes;
    }
}
