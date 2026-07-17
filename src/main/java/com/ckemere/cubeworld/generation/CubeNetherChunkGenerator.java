package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The cube nether: same folded-cube topology as the overworld (identical
 * face size, so seams, margins, and pillars line up 1:1 with the surface
 * world), rendered as an open hellscape — netherrack terrain from the
 * nether map spec, lava seas, and the five nether biomes as continuous
 * fields. Structures (fortresses, bastions) follow the same edge-buffer
 * gating as overworld structures.
 */
public final class CubeNetherChunkGenerator extends ChunkGenerator {

    public static final int LAVA_LEVEL = NetherDemoSpec.LAVA_LEVEL;

    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final MapService maps;
    private final int marginBlocks;
    private final CubeNetherBiomeProvider biomeProvider;

    public CubeNetherChunkGenerator(CubeTopology topology, MapService maps, int marginBlocks) {
        this.topology = topology;
        this.geometry = topology.geometry();
        this.maps = maps;
        this.marginBlocks = marginBlocks;
        this.biomeProvider = new CubeNetherBiomeProvider(topology, maps, marginBlocks);
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        MapSampler sampler = maps.mapFor(worldInfo.getSeed()).netherSampler();
        int minY = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        boolean nearPillar = chunkNearPillar(chunkX, chunkZ);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                double wx = (chunkX << 4) + lx + 0.5;
                double wz = (chunkZ << 4) + lz + 0.5;
                if (nearPillar && topology.inPillar(wx, wz, marginBlocks)) {
                    chunkData.setRegion(lx, minY, lz, lx + 1, maxY, lz + 1, Material.BEDROCK);
                    continue;
                }
                boolean onFace = geometry.faceAt((int) Math.floor(wx), (int) Math.floor(wz)) != null;
                boolean inMargin = !onFace && topology.marginSource(wx, wz, marginBlocks) != null;
                if (!onFace && !inMargin) {
                    continue; // deep void
                }
                int height = (int) Math.round(sampler.heightAt(wx, wz));
                TerrainTheme theme = sampler.themeAt(wx, wz);
                chunkData.setRegion(lx, minY, lz, lx + 1, minY + 1, lz + 1, Material.BEDROCK);
                chunkData.setRegion(lx, minY + 1, lz, lx + 1, height - 3, lz + 1, Material.NETHERRACK);
                chunkData.setRegion(lx, height - 3, lz, lx + 1, height, lz + 1,
                        ThemeBlocks.fillerBlock(theme));
                chunkData.setRegion(lx, height, lz, lx + 1, height + 1, lz + 1,
                        ThemeBlocks.topBlock(theme));
                if (height < LAVA_LEVEL) {
                    chunkData.setRegion(lx, height + 1, lz, lx + 1, LAVA_LEVEL + 1, lz + 1,
                            Material.LAVA);
                }
            }
        }
    }

    private boolean chunkNearPillar(int chunkX, int chunkZ) {
        double cx = (chunkX << 4) + 8;
        double cz = (chunkZ << 4) + 8;
        return topology.inPillar(cx, cz, marginBlocks + 12);
    }

    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random,
                             int x, int z, @NotNull HeightMap heightMap) {
        MapSampler sampler = maps.mapFor(worldInfo.getSeed()).netherSampler();
        return (int) Math.round(Math.max(sampler.heightAt(x + 0.5, z + 0.5), LAVA_LEVEL)) + 1;
    }

    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return biomeProvider;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return true;
    }

    @Override
    public boolean shouldGenerateDecorations(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                             int chunkX, int chunkZ) {
        return vanillaAllowedIn(chunkX, chunkZ);
    }

    private boolean vanillaAllowedIn(int chunkX, int chunkZ) {
        int wx = chunkX << 4;
        int wz = chunkZ << 4;
        if (geometry.faceAt(wx, wz) == null) {
            return false;
        }
        return !chunkNearPillar(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateMobs(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                      int chunkX, int chunkZ) {
        return vanillaAllowedIn(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateStructures() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                            int chunkX, int chunkZ) {
        return structuresAllowedIn(chunkX, chunkZ);
    }

    private static final int STRUCTURE_EDGE_BUFFER_CHUNKS = 8;

    /**
     * Same seam berth as the overworld gate. Nether fortresses are ranged
     * (not jigsaw) and can sprawl past the buffer; margin overhangs are
     * erased by the reconciler like overworld mineshafts.
     */
    private boolean structuresAllowedIn(int chunkX, int chunkZ) {
        int wx = chunkX << 4;
        int wz = chunkZ << 4;
        CubeFace face = geometry.faceAt(wx + 8, wz + 8);
        if (face == null) {
            return false;
        }
        int lx = (wx + 8 - geometry.faceMinX(face)) >> 4;
        int lz = (wz + 8 - geometry.faceMinZ(face)) >> 4;
        int faceChunks = geometry.faceSize() >> 4;
        int edge = Math.min(Math.min(lx, faceChunks - 1 - lx),
                Math.min(lz, faceChunks - 1 - lz));
        if (edge < STRUCTURE_EDGE_BUFFER_CHUNKS) {
            return false;
        }
        return !topology.inPillar(wx + 8, wz + 8,
                marginBlocks + STRUCTURE_EDGE_BUFFER_CHUNKS * 16);
    }
}
