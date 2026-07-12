package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.MarginSource;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Flat test-world generator: each cube face is a uniform slab with its own
 * biome and surface blocks. The void strips within {@code marginBlocks} of a
 * stitched edge generate as mirrors of the terrain across the cube edge
 * (resolved per column through the seam transform), so approaching a seam
 * shows the far side. Everything else outside the cross is void.
 *
 * <p>Because the generator is deterministic, a margin column and its source
 * column always agree without any copying.
 */
public final class CubeWorldChunkGenerator extends ChunkGenerator {

    public static final int SURFACE_Y = 63;

    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final int marginBlocks;
    private final CubeWorldBiomeProvider biomeProvider;

    public CubeWorldChunkGenerator(CubeTopology topology, int marginBlocks) {
        this.topology = topology;
        this.geometry = topology.geometry();
        this.marginBlocks = marginBlocks;
        this.biomeProvider = new CubeWorldBiomeProvider(topology, marginBlocks);
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        int minY = chunkData.getMinHeight();
        CubeFace face = geometry.faceAt(chunkX << 4, chunkZ << 4);
        if (face != null) {
            // Faces are chunk-aligned: the whole chunk shares one theme.
            fillColumnRegion(chunkData, 0, 0, 16, 16, minY, FaceTheme.of(face));
            return;
        }
        // Off the cross: per-column margin resolution.
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                double wx = (chunkX << 4) + lx + 0.5;
                double wz = (chunkZ << 4) + lz + 0.5;
                FaceTheme theme = marginTheme(wx, wz);
                if (theme != null) {
                    fillColumnRegion(chunkData, lx, lz, lx + 1, lz + 1, minY, theme);
                }
            }
        }
    }

    /** Theme of the real column this margin column mirrors, or null when not in a margin. */
    private @Nullable FaceTheme marginTheme(double wx, double wz) {
        MarginSource source = topology.marginSource(wx, wz, marginBlocks);
        if (source == null) {
            return null;
        }
        CubeFace sourceFace = geometry.faceAt(
                (int) Math.floor(source.source().x()), (int) Math.floor(source.source().z()));
        return sourceFace == null ? null : FaceTheme.of(sourceFace);
    }

    private void fillColumnRegion(ChunkData chunkData, int x0, int z0, int x1, int z1,
                                  int minY, FaceTheme theme) {
        chunkData.setRegion(x0, minY, z0, x1, minY + 1, z1, Material.BEDROCK);
        chunkData.setRegion(x0, minY + 1, z0, x1, SURFACE_Y - 3, z1, Material.STONE);
        chunkData.setRegion(x0, SURFACE_Y - 3, z0, x1, SURFACE_Y, z1, theme.fillerBlock());
        chunkData.setRegion(x0, SURFACE_Y, z0, x1, SURFACE_Y + 1, z1, theme.topBlock());
        if (theme.snowCovered()) {
            chunkData.setRegion(x0, SURFACE_Y + 1, z0, x1, SURFACE_Y + 2, z1, Material.SNOW);
        }
    }

    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random,
                             int x, int z, @NotNull HeightMap heightMap) {
        return SURFACE_Y + 1;
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
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
