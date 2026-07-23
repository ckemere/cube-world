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

    /**
     * Vanilla places every nether block: the world's noise router is folded onto
     * the sphere ({@link SphereDensity#forNether}) on {@code WorldInitEvent}, so
     * netherrack terrain with relief, the lava sea (~y32), the bedrock roof/floor,
     * the swiss-cheese caverns of the 3D noise, and MultiNoise nether biomes all
     * come from vanilla's own generator but seam-consistently. This pass then
     * only masks the off-net cross gaps (void) and the corner pillars (bedrock) —
     * the two things the fold cannot express.
     */
    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
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
                    chunkData.setRegion(lx, minY, lz, lx + 1, maxY, lz + 1, Material.AIR); // deep void
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
        // null -> Paper uses the dimension's own MultiNoise nether biome source.
        // Its Climate.Sampler is folded onto the sphere by the router hook, so the
        // five vanilla nether biomes are selected on the folded point and stay
        // continuous across every seam. (The old CubeNetherBiomeProvider drove
        // biomes from a sine theme field — the rejected demo approach.)
        return null;
    }

    /** The nether biome provider, kept for the offline biome-raster export tool. */
    public CubeNetherBiomeProvider biomeProvider() {
        return biomeProvider;
    }

    @Override
    public boolean shouldGenerateNoise() {
        // Vanilla fills netherrack terrain, the lava sea, the bedrock roof/floor
        // and the 3D-noise caverns from the sphere-folded nether router.
        return true;
    }

    @Override
    public boolean shouldGenerateSurface() {
        // Vanilla nether surface rules (netherrack top, soul soil, basalt, the
        // bedrock roof + floor slabs).
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        // Chunk-seeded vanilla carvers cannot match across seams; the folded 3D
        // BlendedNoise already gives the nether its characteristic open caverns
        // and overhangs seam-consistently, so no extra carvers.
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
