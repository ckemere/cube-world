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
 * Map-spec terrain: per-column height and theme from a {@link MapSampler},
 * which resolves seam margins to their source terrain and interpolates
 * heights across stitched edges. Corner pillars override everything at cube
 * vertices; deep void (outside faces, margins, and pillars) stays empty.
 */
public final class CubeWorldChunkGenerator extends ChunkGenerator {

    public static final int SEA_LEVEL = SphericalDemoSpec.SEA_LEVEL;

    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final MapService maps;
    private final int marginBlocks;
    private final CubeWorldBiomeProvider biomeProvider;

    public CubeWorldChunkGenerator(CubeTopology topology, MapService maps, int marginBlocks) {
        this.topology = topology;
        this.geometry = topology.geometry();
        this.maps = maps;
        this.marginBlocks = marginBlocks;
        this.biomeProvider = new CubeWorldBiomeProvider(topology, maps, marginBlocks);
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        MapService.CubeWorldMap map = maps.mapFor(worldInfo.getSeed());
        MapSampler sampler = map.sampler();
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
                double exactHeight = sampler.heightAt(wx, wz);
                int height = (int) Math.round(exactHeight);
                TerrainTheme theme = sampler.themeAt(wx, wz);
                chunkData.setRegion(lx, minY, lz, lx + 1, minY + 1, lz + 1, Material.BEDROCK);
                chunkData.setRegion(lx, minY + 1, lz, lx + 1, 0, lz + 1, Material.DEEPSLATE);
                chunkData.setRegion(lx, 0, lz, lx + 1, height - 3, lz + 1, Material.STONE);
                chunkData.setRegion(lx, height - 3, lz, lx + 1, height, lz + 1,
                        ThemeBlocks.fillerBlock(theme));
                chunkData.setRegion(lx, height, lz, lx + 1, height + 1, lz + 1,
                        ThemeBlocks.topBlock(theme));
                if (height < SEA_LEVEL) {
                    chunkData.setRegion(lx, height + 1, lz, lx + 1, SEA_LEVEL + 1, lz + 1,
                            Material.WATER);
                } else if (ThemeBlocks.snowCovered(theme)) {
                    chunkData.setRegion(lx, height + 1, lz, lx + 1, height + 2, lz + 1,
                            Material.SNOW);
                }
                carveCaves(map, chunkData, lx, lz, wx, wz, minY, exactHeight);
            }
        }
    }

    /** Carve seam-consistent caves into a finished column (air, lava at the bottom). */
    private void carveCaves(MapService.CubeWorldMap map, ChunkData chunkData, int lx, int lz,
                            double wx, double wz, int minY, double surfaceHeight) {
        com.ckemere.cubeworld.geometry.Vec3 p = map.sampler().cubePointAt(wx, wz);
        if (p == null) {
            return;
        }
        int top = (int) Math.floor(surfaceHeight - CaveCarver.ROOF);
        for (int y = minY + 6; y <= top; y++) {
            if (map.carver().carved(p, y, surfaceHeight)) {
                chunkData.setBlock(lx, y, lz,
                        y <= CaveCarver.LAVA_LEVEL ? Material.LAVA : Material.AIR);
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
        MapSampler sampler = maps.mapFor(worldInfo.getSeed()).sampler();
        return (int) Math.round(Math.max(sampler.heightAt(x + 0.5, z + 0.5), SEA_LEVEL)) + 1;
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
        // Vanilla carvers do not engage with custom generators on 26.x (and
        // are chunk-seeded, so they could never match across seams anyway);
        // CaveCarver handles caves seam-consistently instead.
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

    /**
     * Vanilla features (trees, grass, ores, cave flora) run only on real face
     * chunks away from pillars. Margins must stay a deterministic function of
     * their source (features are seeded per chunk and would diverge from the
     * terrain they mirror), so they get none; a follow-up could copy
     * near-seam features into margins the way block edits already sync.
     */
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
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
