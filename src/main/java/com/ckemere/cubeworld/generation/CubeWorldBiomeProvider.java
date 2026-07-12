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
 * Biomes from the map spec (per chunk cell), continued into seam margins via
 * the sampler's source resolution; void far off the net.
 */
public final class CubeWorldBiomeProvider extends BiomeProvider {

    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final MapSampler sampler;
    private final int marginBlocks;

    public CubeWorldBiomeProvider(CubeTopology topology, MapSampler sampler, int marginBlocks) {
        this.topology = topology;
        this.geometry = topology.geometry();
        this.sampler = sampler;
        this.marginBlocks = marginBlocks;
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        boolean onFace = geometry.faceAt(x, z) != null;
        if (!onFace && topology.marginSource(x + 0.5, z + 0.5, marginBlocks) == null) {
            return Biome.THE_VOID;
        }
        return ThemeBlocks.biome(sampler.themeAt(x + 0.5, z + 0.5));
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        List<Biome> biomes = new ArrayList<>();
        for (TerrainTheme theme : TerrainTheme.values()) {
            biomes.add(ThemeBlocks.biome(theme));
        }
        biomes.add(Biome.THE_VOID);
        return biomes;
    }
}
