package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
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
 * biome and surface blocks; everything outside the cross is void.
 *
 * <p>Faces are chunk-aligned (enforced by {@link CubeGeometry}), so a chunk is
 * always entirely on one face or entirely outside.
 */
public final class CubeWorldChunkGenerator extends ChunkGenerator {

    public static final int SURFACE_Y = 63;

    private final CubeGeometry geometry;
    private final CubeWorldBiomeProvider biomeProvider;

    public CubeWorldChunkGenerator(CubeGeometry geometry) {
        this.geometry = geometry;
        this.biomeProvider = new CubeWorldBiomeProvider(geometry);
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        CubeFace face = geometry.faceAt(chunkX << 4, chunkZ << 4);
        if (face == null) {
            return; // outside the cross: void
        }
        FaceTheme theme = FaceTheme.of(face);
        int minY = chunkData.getMinHeight();
        chunkData.setRegion(0, minY, 0, 16, minY + 1, 16, Material.BEDROCK);
        chunkData.setRegion(0, minY + 1, 0, 16, SURFACE_Y - 3, 16, Material.STONE);
        chunkData.setRegion(0, SURFACE_Y - 3, 0, 16, SURFACE_Y, 16, theme.fillerBlock());
        chunkData.setRegion(0, SURFACE_Y, 0, 16, SURFACE_Y + 1, 16, theme.topBlock());
        if (theme.snowCovered()) {
            chunkData.setRegion(0, SURFACE_Y + 1, 0, 16, SURFACE_Y + 2, 16, Material.SNOW);
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
