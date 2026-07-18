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
                double exactHeight = sampler.heightAt(wx, wz) + ridgeDetail(sampler, wx, wz);
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
                } else if (isRiver(sampler, wx, wz)) {
                    // incise a shallow channel and fill with water (real river path)
                    chunkData.setBlock(lx, height, lz, Material.AIR);
                    chunkData.setRegion(lx, height - 3, lz, lx + 1, height, lz + 1, Material.WATER);
                    chunkData.setBlock(lx, height - 4, lz, Material.GRAVEL);
                } else if (ThemeBlocks.snowCovered(theme)) {
                    chunkData.setRegion(lx, height + 1, lz, lx + 1, height + 2, lz + 1,
                            Material.SNOW);
                }
                carveCaves(map, chunkData, lx, lz, wx, wz, minY, exactHeight);
            }
        }
    }

    /**
     * Per-block ridge roughness for mountains: the elevation raster is coarse
     * (2 arc-min), so peaks come out smooth. Add high-frequency noise of the
     * cube point (seam-safe), scaled by local ruggedness so lowlands stay flat
     * and only real mountains get jagged — recovering some peak sharpness the
     * data resolution can't provide.
     */
    private double ridgeDetail(MapSampler sampler, double wx, double wz) {
        EarthData earth = maps.earthData();
        if (earth == null) {
            return 0.0;
        }
        com.ckemere.cubeworld.geometry.Vec3 p = sampler.cubePointAt(wx, wz);
        if (p == null) {
            return 0.0;
        }
        double[] ll = earth.toLonLat(p);
        double elev = earth.sample("height", ll[0], ll[1]);
        if (elev < 350) {
            return 0.0;                        // lowlands stay smooth
        }
        double rugged = EarthClimate.ruggedness(earth, ll[0], ll[1], elev);
        double amp = Math.min((rugged - 150.0) / 700.0, 1.0) * 16.0;
        if (amp <= 0) {
            return 0.0;
        }
        double n = Math.sin(430 * p.x() + 0.3) * Math.cos(410 * p.z() - 0.7)
                + 0.6 * Math.sin(770 * p.y() + 1.1) * Math.cos(690 * p.x())
                + 0.3 * Math.sin(1500 * p.z() + 0.5) * Math.cos(1400 * p.x());
        return amp * Math.max(-1.0, Math.min(1.0, n / 1.7));
    }

    /** True where the Earth river/lake mask marks a watercourse (above sea). */
    private boolean isRiver(MapSampler sampler, double wx, double wz) {
        EarthData earth = maps.earthData();
        if (earth == null || !earth.hasLayer("river")) {
            return false;
        }
        com.ckemere.cubeworld.geometry.Vec3 p = sampler.cubePointAt(wx, wz);
        if (p == null) {
            return false;
        }
        double[] ll = earth.toLonLat(p);
        return earth.sample("river", ll[0], ll[1]) > 0.2;
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

    /**
     * Chunks of face-edge buffer inside which no structure may start. Jigsaw
     * structures (villages, outposts) sprawl at most 80 blocks from their
     * start, so 8 chunks keeps every piece on-face while letting villages
     * reach into the mirror zone (visible across the seam once reconciled).
     */
    private static final int STRUCTURE_EDGE_BUFFER_CHUNKS = 8;

    /**
     * Structures are chunk-seeded and plane-local: one straddling a stitched
     * edge would be chopped by the margin (which always mirrors the far
     * side). Sprawlier underground structures (mineshafts, strongholds) may
     * still poke past the buffer; margin overhangs from those are erased by
     * the reconciler, and on-face truncation is underground and benign.
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
