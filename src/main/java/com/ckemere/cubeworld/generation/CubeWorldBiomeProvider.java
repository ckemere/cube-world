package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

/**
 * Three-dimensional biomes: at and near the surface, the map spec's theme
 * (continued into seam margins via source resolution); deep underground,
 * cave biomes selected by seam-consistent noise over the cube-surface point
 * ({@link CaveBiomes}), so lush/dripstone/deep-dark patches continue across
 * stitched edges like everything else. Void far off the net.
 */
public final class CubeWorldBiomeProvider extends BiomeProvider {

    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final MapService maps;
    private final int marginBlocks;

    public CubeWorldBiomeProvider(CubeTopology topology, MapService maps, int marginBlocks) {
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
        MapService.CubeWorldMap map = maps.mapFor(worldInfo.getSeed());
        MapSampler sampler = map.sampler();
        double wx = x + 0.5;
        double wz = z + 0.5;
        if (y < sampler.heightAt(wx, wz) - CaveBiomes.SURFACE_BUFFER) {
            Vec3 p = sampler.cubePointAt(wx, wz);
            if (p != null) {
                switch (map.caveBiomes().at(p, y)) {
                    case LUSH -> {
                        return Biome.LUSH_CAVES;
                    }
                    case DRIPSTONE -> {
                        return Biome.DRIPSTONE_CAVES;
                    }
                    case DEEP_DARK -> {
                        return Biome.DEEP_DARK;
                    }
                    case NONE -> {
                        // fall through to the surface theme
                    }
                }
            }
        }
        return ThemeBlocks.biome(sampler.themeAt(wx, wz));
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        List<Biome> biomes = new ArrayList<>();
        for (TerrainTheme theme : TerrainTheme.values()) {
            biomes.add(ThemeBlocks.biome(theme));
        }
        biomes.add(Biome.LUSH_CAVES);
        biomes.add(Biome.DRIPSTONE_CAVES);
        biomes.add(Biome.DEEP_DARK);
        biomes.add(Biome.THE_VOID);
        return biomes;
    }
}
