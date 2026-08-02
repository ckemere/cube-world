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

    /**
     * Vanilla places every block on both worlds: the world's noise router is
     * folded onto the sphere ({@link com.ckemere.cubeworld.generation.SphereDensity}),
     * so terrain, caves, aquifers, lava and ores come from vanilla's own
     * generator but seam-consistently. On the Earth world the fold also pins the
     * surface to real elevation (the hybrid); the demo world uses vanilla noise.
     * Our {@code generateSurface} then only masks off-net gaps and pillars.
     */
    private boolean vanillaTerrain() {
        return true;
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        if (vanillaTerrain()) {
            long tm = System.nanoTime();
            maskVanillaColumns(chunkX, chunkZ, chunkData);
            GenProfiler.add("maskColumns", tm);
            long tw = System.nanoTime();
            carveWater(worldInfo, chunkX, chunkZ, chunkData);
            GenProfiler.add("carveWater", tw);
            return;
        }
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

    /** True where the Earth river/lake mask marks a watercourse (above sea).
     * Shares {@link EarthClimate#riverStrength} with the biome layer so water
     * and the river biome coincide. */
    private boolean isRiver(MapSampler sampler, double wx, double wz) {
        return EarthClimate.riverStrength(maps.earthData(), sampler, wx, wz)
                > EarthClimate.RIVER_THRESHOLD;
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

    /**
     * With vanilla driving block placement we only override the two things the
     * fold cannot express: the off-net cross gaps (cleared to void) and the
     * corner pillars (solid bedrock). On-net and margin columns keep vanilla's
     * blocks — margins already match their source because both fold to the same
     * cube point.
     */
    private void maskVanillaColumns(int chunkX, int chunkZ, ChunkData chunkData) {
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
                    chunkData.setRegion(lx, minY, lz, lx + 1, maxY, lz + 1, Material.AIR);
                }
            }
        }
    }

    /** Neighbourhood used to level a river's water line against raster bumps
     * (fallback only, when river_y is somehow absent). */
    private static final double[][] RIVER_KERNEL =
            {{12, 0}, {-12, 0}, {0, 12}, {0, -12}};

    // River-channel geometry. The mask is a distance ramp (1 on centreline,
    // falling to 0 over ~3 px). Columns with strength >= WATER hold water;
    // columns in [RIM, WATER) form a solid containing wall one ring outward, so
    // the water body is always bounded by solid at the waterline (no lateral
    // leak into a vanilla canyon at the bank). Both sit on a thick solid bed so
    // vanilla caves cannot puncture and drain the watercourse.
    private static final double RIVER_WATER_THRESHOLD = EarthClimate.RIVER_THRESHOLD; // 0.70
    private static final double RIVER_RIM_THRESHOLD = 0.50;
    private static final int RIVER_WATER_DEPTH = 4;   // water blocks (bedTop+1..waterTop)
    private static final int RIVER_BED_THICK = 8;     // solid bed blocks below the water
    private static final int RIVER_HEADROOM = 2;      // air cleared above the water surface

    /**
     * Place water that vanilla can't, because our terrain follows real elevation
     * instead of dipping to sea level. Two cases, both after vanilla's noise and
     * surface stages (the column already holds finished blocks):
     *
     * <ul>
     *   <li><b>Seas</b> (Earth elevation &lt; 0): where cheese noise poked the
     *   shallow seabed above sea level, drop it back and fill water to y63 — so
     *   the Mediterranean and other shelves stop showing dry "ocean" patches.
     *   <li><b>Rivers</b> (river/lake mask on land): incise a channel and fill
     *   it with water. The water line is the MINIMUM elevation over a small
     *   neighbourhood, so a raster bump becomes a carved canyon instead of the
     *   river appearing to climb it.
     * </ul>
     */
    /**
     * Post-carver re-seal ({@code -Dcubeworld.resealWater=}).
     *
     * <p>Bukkit runs the stages in this order:
     * {@code fillFromNoise -> generateSurface -> applyCarvers -> generateCaves}.
     * Rivers and straits are cut in {@code generateSurface}, so vanilla's
     * carvers -- which run afterwards -- can drive a tunnel straight through a
     * riverbed or a strait floor. The result is the classic wart: a river that
     * runs past a cave mouth and pours into it.
     *
     * <p>{@code generateCaves} is called immediately AFTER the carvers and is
     * the only hook on that side of them, so the water beds are simply laid
     * again here. Re-running the same carve is safe: it clears a channel to the
     * water surface, refills source water and re-lays a solid bed, so a column
     * the carvers left intact ends up byte-identical and one they breached is
     * repaired.
     *
     * <p>Only the river and strait paths are repeated -- not the ocean gap-fill
     * or the shoal clean-up, which reason about the column as a whole and are
     * not what a carver damages.
     */
    @Override
    public void generateCaves(@NotNull WorldInfo worldInfo, @NotNull Random random,
                              int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        if (!RESEAL_WATER || !vanillaTerrain()) {
            return;
        }
        EarthData earth = maps.earthData();
        if (earth == null || !earth.hasLayer("height")) {
            return;
        }
        boolean hasRivers = earth.hasLayer("river");
        MapSampler sampler = maps.mapFor(worldInfo.getSeed()).sampler();
        int minY = chunkData.getMinHeight();
        long t0 = System.nanoTime();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                double wx = (chunkX << 4) + lx + 0.5;
                double wz = (chunkZ << 4) + lz + 0.5;
                com.ckemere.cubeworld.geometry.Vec3 p = sampler.cubePointAt(wx, wz);
                if (p == null) {
                    continue;
                }
                double[] ll = earth.toLonLat(p);
                StraitField.Hit strait = StraitField.sample(ll[0], ll[1]);
                if (strait != null) {
                    carveStraitColumn(chunkData, lx, lz, minY, strait.depthBlocks(), earth, ll);
                    continue;
                }
                if (!hasRivers) {
                    continue;
                }
                double r = earth.sample("river", ll[0], ll[1]);
                if (Double.isNaN(r) || r <= RIVER_RIM_THRESHOLD) {
                    continue;
                }
                int predicted = (int) Math.round(sampler.heightAt(wx, wz));
                if (predicted > SEA_LEVEL) {
                    carveRiverColumn(earth, sampler, chunkData, lx, lz, wx, wz,
                            minY, predicted, ll, r);
                }
            }
        }
        GenProfiler.add("resealWater", t0);
    }

    private static final boolean RESEAL_WATER =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.resealWater", "true"));

    private void carveWater(WorldInfo worldInfo, int chunkX, int chunkZ, ChunkData chunkData) {
        EarthData earth = maps.earthData();
        if (earth == null || !earth.hasLayer("height")) {
            return;
        }
        boolean hasRivers = earth.hasLayer("river");
        MapSampler sampler = maps.mapFor(worldInfo.getSeed()).sampler();
        int minY = chunkData.getMinHeight();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                boolean surfWater = chunkData.getType(lx, SEA_LEVEL - 1, lz) == Material.WATER;
                boolean solidSea = chunkData.getType(lx, SEA_LEVEL, lz).isSolid();
                // Void / dry above-sea air with no solid: nothing to place here.
                if (!surfWater && !solidSea) {
                    continue;
                }
                double wx = (chunkX << 4) + lx + 0.5;
                double wz = (chunkZ << 4) + lz + 0.5;
                com.ckemere.cubeworld.geometry.Vec3 p = sampler.cubePointAt(wx, wz);
                if (p == null) {
                    continue; // void / off-net (already handled by masking)
                }
                double[] ll = earth.toLonLat(p);

                // 1. Sea straits / ship canals FIRST: cut a flat sea-level channel
                // through the land bridge so the seas on each side connect (Bering,
                // Gibraltar, Bab-el-Mandeb, Bosphorus, Panama/Suez, ...). Takes
                // precedence over everything so its solid bed always seals.
                StraitField.Hit strait = StraitField.sample(ll[0], ll[1]);
                if (strait != null) {
                    carveStraitColumn(chunkData, lx, lz, minY, strait.depthBlocks(),
                            earth, ll);
                    continue;
                }

                // 2. Rivers on land: carve BEFORE the ocean fill so the solid,
                // cave-proof bed is always laid — even where vanilla noise made a
                // near-sea river column look ocean-like (which would otherwise let
                // the gap-fill below claim it and skip the river). A river column
                // whose land still stands above sea gets a channel; one that has
                // reached the coast falls through to the ocean fill (its mouth).
                if (hasRivers) {
                    double r = earth.sample("river", ll[0], ll[1]);
                    if (!Double.isNaN(r) && r > RIVER_RIM_THRESHOLD) {
                        int predicted = (int) Math.round(sampler.heightAt(wx, wz));
                        if (predicted > SEA_LEVEL) {
                            carveRiverColumn(earth, sampler, chunkData, lx, lz, wx, wz,
                                    minY, predicted, ll, r);
                            continue;
                        }
                    }
                }

                // 3. Ocean gap-fill: density caves in the seabed can leave air
                // pockets right under the surface water. (Aquifers are back ON
                // now -- see SphereRouterHook -- and handle the water table below
                // the seabed; this only closes voids inside the ocean column
                // itself, which the aquifer deliberately leaves to the global
                // fluid picker.)
                // Fill any such gap down to the first solid seabed with source water.
                if (surfWater) {
                    int y = SEA_LEVEL - 1;
                    while (y >= minY && chunkData.getType(lx, y, lz) == Material.WATER) {
                        y--;
                    }
                    if (y >= minY && !chunkData.getType(lx, y, lz).isSolid()) {
                        int seabed = y;
                        while (seabed >= minY && !chunkData.getType(lx, seabed, lz).isSolid()) {
                            seabed--;
                        }
                        chunkData.setRegion(lx, seabed + 1, lz, lx + 1, SEA_LEVEL, lz + 1,
                                Material.WATER);
                    }
                    continue;
                }

                // 4. Shoal: solid poked above the sea in an ocean cell. Clear it to
                // the raster seabed, lay a gravel floor, and fill water.
                boolean nearSea = !chunkData.getType(lx, SEA_LEVEL + 8, lz).isSolid();
                double elev = nearSea ? earth.sample("height", ll[0], ll[1]) : 1.0;
                if (elev < 0) {
                    int seabed = Math.min((int) Math.round(sampler.heightAt(wx, wz)),
                            SEA_LEVEL - 2);
                    for (int y = SEA_LEVEL + 40; y > seabed; y--) {
                        if (chunkData.getType(lx, y, lz).isSolid()) {
                            chunkData.setBlock(lx, y, lz, Material.AIR);
                        }
                    }
                    chunkData.setRegion(lx, Math.max(minY, seabed - 2), lz, lx + 1, seabed + 1,
                            lz + 1, Material.GRAVEL);
                    chunkData.setRegion(lx, seabed + 1, lz, lx + 1, SEA_LEVEL, lz + 1,
                            Material.WATER);
                }
            }
        }
    }

    /**
     * Carve one column of a river channel. The water surface is the precomputed
     * DOWNHILL {@code river_y} (monotone non-increasing source->mouth, so the
     * river never flows uphill); it now covers the whole ramp footprint after
     * the export dilation, with a neighbourhood-min fallback for any residual
     * gap. Rather than the old thin per-column stamp (which floated pools over
     * vanilla canyons and let caves drain them), we rebuild a fixed cross
     * section that ignores vanilla noise inside the footprint:
     *
     * <ul>
     *   <li><b>Water columns</b> (strength &ge; WATER): a solid biome bed
     *   {@code RIVER_BED_THICK} deep (overwriting any cave void — cave-proof),
     *   {@code RIVER_WATER_DEPTH} source-water blocks on top, and open air above.
     *   <li><b>Rim columns</b> (RIM..WATER): a solid wall filled up through the
     *   waterline so the water body is contained even where vanilla cut a canyon
     *   right at the bank. Natural terrain above the waterline is left alone.
     * </ul>
     *
     * Everything is a continuous function of the cube point (river_y, strength,
     * climate) so the channel is seam-consistent by construction.
     */
    private void carveRiverColumn(EarthData earth, MapSampler sampler, ChunkData chunkData,
                                  int lx, int lz, double wx, double wz, int minY,
                                  int predicted, double[] ll, double rstr) {
        int waterTop;
        double rym = EarthClimate.riverWaterY(earth, sampler, wx, wz);
        if (!Double.isNaN(rym)) {
            waterTop = (int) Math.round(EarthMapSpec.elevationToBlockY(rym));
            waterTop = Math.min(waterTop, predicted - 1);   // always a channel
            // river_y carries the upstream running-minimum downstream, so where the
            // river crosses a lower basin it would otherwise perch ABOVE the local
            // land (water at y66 beside y64 savanna) and get a stone levee built up
            // to contain it. Clamp to the local terrain-neighbourhood minimum -- the
            // same level the NaN fallback uses -- so the channel never sits above the
            // ground it cuts through. Only lowers a perched river; a river already in
            // a valley (river_y <= local ground) is unaffected.
            double hmin = sampler.heightAt(wx, wz);
            for (double[] o : RIVER_KERNEL) {
                hmin = Math.min(hmin, sampler.heightAt(wx + o[0], wz + o[1]));
            }
            waterTop = Math.min(waterTop, (int) Math.round(hmin) - 1);
        } else {
            double hmin = sampler.heightAt(wx, wz);
            for (double[] o : RIVER_KERNEL) {
                hmin = Math.min(hmin, sampler.heightAt(wx + o[0], wz + o[1]));
            }
            waterTop = Math.max((int) Math.round(hmin) - 1, predicted - 14);
        }
        if (waterTop <= SEA_LEVEL) {
            // The downhill surface has reached the sea. Wherever the land still
            // stands even a block above sea (vast low basins like the Amazon and
            // lower Mississippi that the vertical scale squashes toward sea
            // level), incise a flush sea-level channel so the river stays a
            // continuous water ribbon instead of breaking into dry gaps;
            // otherwise it is truly the coast and vanilla's ocean fill takes over.
            if (predicted > SEA_LEVEL) {
                waterTop = SEA_LEVEL;
            } else {
                return;
            }
        }
        int bedTop = waterTop - RIVER_WATER_DEPTH;          // topmost solid bed block
        int bedBottom = Math.max(minY + 1, bedTop - RIVER_BED_THICK + 1);

        if (rstr < RIVER_WATER_THRESHOLD) {
            // Rim: raise/patch a solid wall through the waterline so the adjacent
            // water can't leak past it, but keep any natural terrain above. Build it
            // as a grassy dirt bank (grass crest at the waterline over dirt) rather
            // than bare stone, so the shoreline reads as natural ground.
            for (int y = bedBottom; y <= waterTop; y++) {
                if (!chunkData.getType(lx, y, lz).isSolid()) {
                    Material bank = (y == waterTop) ? Material.GRASS_BLOCK : Material.DIRT;
                    chunkData.setBlock(lx, y, lz, bank);
                }
            }
            return;
        }

        // Water column. Clear a channel down to the water surface.
        int surf = surfaceOf(chunkData, lx, lz, predicted);
        int clearTop = Math.max(surf, waterTop) + RIVER_HEADROOM;
        for (int y = clearTop; y > waterTop; y--) {
            chunkData.setBlock(lx, y, lz, Material.AIR);
        }
        // Source water.
        chunkData.setRegion(lx, bedTop + 1, lz, lx + 1, waterTop + 1, lz + 1, Material.WATER);
        // Solid bed: a thin biome-appropriate cosmetic cap over stone, thick
        // enough that caves below can't reach the water. Overwrites cave voids.
        Material cap = riverBedBlock(earth, ll);
        chunkData.setRegion(lx, bedBottom, lz, lx + 1, bedTop - 1, lz + 1, Material.STONE);
        chunkData.setRegion(lx, bedTop - 1, lz, lx + 1, bedTop + 1, lz + 1, cap);
    }

    /**
     * Carve one column of a sea strait / canal: excavate any land down to a
     * solid bed {@code depth} blocks below sea and fill the channel with source
     * water to the sea surface, so the corridor becomes navigable water joining
     * the seas on either side. Adjacent out-of-corridor land forms the banks;
     * at the ends the channel merges with the existing ocean.
     */
    private void carveStraitColumn(ChunkData chunkData, int lx, int lz, int minY,
                                   int depth, EarthData earth, double[] ll) {
        int bed = SEA_LEVEL - depth;
        int ceil = Math.min(chunkData.getMaxHeight() - 1, SEA_LEVEL + 48);
        for (int y = ceil; y > bed; y--) {
            if (chunkData.getType(lx, y, lz).isSolid()) {
                chunkData.setBlock(lx, y, lz, Material.AIR);
            }
        }
        // Fill to the ocean surface block (y = SEA_LEVEL) so the channel is
        // flush with the sea it joins (upper bound is exclusive).
        chunkData.setRegion(lx, bed + 1, lz, lx + 1, SEA_LEVEL + 1, lz + 1, Material.WATER);
        chunkData.setRegion(lx, Math.max(minY + 1, bed - 2), lz, lx + 1, bed + 1, lz + 1,
                riverBedBlock(earth, ll));
    }

    /** Biome-appropriate river/lake bed cosmetic: sand in warm-dry country,
     * gravel elsewhere. Sits on the solid stone bed so it never gravity-falls. */
    private Material riverBedBlock(EarthData earth, double[] ll) {
        double temp = earth.sample("temp", ll[0], ll[1]);
        double precip = earth.sample("precip", ll[0], ll[1]);
        if (Double.isNaN(temp)) {
            temp = 27.0 - Math.abs(ll[1]) * 0.65;
        }
        if (Double.isNaN(precip)) {
            precip = 700.0;
        }
        if (temp > 15 && precip < 500) {
            return Material.SAND;          // desert / savanna washes
        }
        return Material.GRAVEL;            // temperate & cold river beds
    }

    /** Topmost solid block near the predicted height (vanilla's finished surface). */
    private int surfaceOf(ChunkData chunkData, int lx, int lz, int predicted) {
        int top = Math.min(chunkData.getMaxHeight() - 1, predicted + 30);
        int bottom = Math.max(chunkData.getMinHeight(), predicted - 30);
        for (int y = top; y >= bottom; y--) {
            if (chunkData.getType(lx, y, lz).isSolid()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
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

    /** The cube biome provider (for the biome-census command). */
    public CubeWorldBiomeProvider biomeProvider() {
        return biomeProvider;
    }

    @Override
    public boolean shouldGenerateNoise() {
        // Demo world: vanilla fills terrain/caves/aquifers/ores from the folded
        // router. Earth world: our real-elevation column fill in generateSurface.
        return vanillaTerrain();
    }

    @Override
    public boolean shouldGenerateSurface() {
        // Demo world: vanilla surface rules (grass/sand/gravel by biome).
        return vanillaTerrain();
    }

    /**
     * Vanilla's carvers -- {@code CaveWorldCarver} and {@code CanyonWorldCarver},
     * i.e. tunnel caves and ravines ({@code -Dcubeworld.carvers=}).
     *
     * <p>This used to return false, with a comment saying carvers "do not engage
     * with custom generators on 26.x" and that {@link CaveCarver} handled caves
     * instead. Both halves were wrong. CraftBukkit's
     * {@code CustomChunkGenerator.applyCarvers} calls straight through to the
     * delegate whenever this returns true, and {@code CaveCarver} has been dead
     * code since {@code vanillaTerrain()} was hardcoded to true -- it only runs
     * in the branch of {@code generateSurface} that is now unreachable. So the
     * world had no ravines and no tunnel caves at all, only what the density
     * field carves.
     *
     * <p>The remaining half of the objection is real but small: carvers are
     * seeded per source chunk ({@code setLargeFeatureSeed(seed, chunkX, chunkZ)}),
     * so a tunnel crossing a cube seam is cut on one side and not the other.
     * That is a wart confined to the twelve seam lines, against having tunnels
     * and ravines everywhere else -- and carvers share the chunk's aquifer, so
     * with aquifers back on they inherit the hydrology too.
     */
    @Override
    public boolean shouldGenerateCaves() {
        return CARVERS;
    }

    private static final boolean CARVERS =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.carvers", "true"));

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
