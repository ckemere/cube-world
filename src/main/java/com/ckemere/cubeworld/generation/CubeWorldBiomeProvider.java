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
 * Biomes for the cube overworld. With Earth data loaded, biomes come from
 * vanilla's own climate-to-biome partition ({@link VanillaBiomeMapper}) fed
 * real climate ({@link EarthClimate}) — so the full ~55 overworld biomes and
 * the cave biomes (at depth) are reachable, seam-consistent because the
 * climate is a continuous function of the cube point. Without Earth data it
 * falls back to the demo theme system. Void off the net.
 */
public final class CubeWorldBiomeProvider extends BiomeProvider {

    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final MapService maps;
    private final int marginBlocks;
    private volatile VanillaBiomeMapper vanilla;

    public CubeWorldBiomeProvider(CubeTopology topology, MapService maps, int marginBlocks) {
        this.topology = topology;
        this.geometry = topology.geometry();
        this.maps = maps;
        this.marginBlocks = marginBlocks;
    }

    private VanillaBiomeMapper vanilla() {
        VanillaBiomeMapper v = vanilla;
        if (v == null) {
            synchronized (this) {
                v = vanilla;
                if (v == null) {
                    v = new VanillaBiomeMapper();
                    vanilla = v;
                }
            }
        }
        return v;
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

        EarthData earth = maps.earthData();
        if (earth != null) {
            return earthBiome(earth, sampler, wx, wz, y, worldInfo.getSeed());
        }

        // demo fallback: theme + noise cave biomes
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
                    }
                }
            }
        }
        return ThemeBlocks.biome(sampler.themeAt(wx, wz));
    }

    /**
     * The biome at the top of a column — the surface height's biome, used by
     * the biome-census command to tally the whole planet surface.
     */
    /** The live vanilla parameter list behind this provider, for coverage checks. */
    public VanillaBiomeMapper mapper() {
        return vanilla();          // lazily built; the field may still be null
    }

    public Biome surfaceBiome(WorldInfo worldInfo, int x, int z) {
        MapSampler sampler = maps.mapFor(worldInfo.getSeed()).sampler();
        int y = (int) Math.round(sampler.heightAt(x + 0.5, z + 0.5));
        return getBiome(worldInfo, x, y, z);
    }

    /**
     * Per-thread, per-column biome cache. Climate params (temperature/humidity/
     * continentalness/erosion/weirdness/elevation) depend only on (x, z), and
     * depth — the one y-dependent param — is clamped to [-0.1, 0.9], so nearly
     * every cell in a column shares one of the two clamp values and thus one
     * biome. Caching the column's params + its river class + the deep/shallow
     * biomes turns ~96 full biome lookups per column into ~16. Output-identical:
     * the deep/shallow biomes are keyed on the exact clamp values.
     */
    private static final class Col {
        int x = Integer.MIN_VALUE;
        int z = Integer.MIN_VALUE;
        double[] c;
        double surfaceY;
        int river; // 0 none, 1 river, 2 frozen
        Biome deep;    // biome at depth 0.9 (clamped underground)
        Biome shallow; // biome at depth -0.1 (clamped above surface)
        Biome ocean;   // ocean biome when below sea level (elev < 0)
    }

    // A chunk has only 16 biome columns but they are queried interleaved across
    // ~96 Y levels, so a direct-mapped set of slots (not one) keeps them all hot.
    private static final int COL_SLOTS = 64;
    private final ThreadLocal<Col[]> colCache = ThreadLocal.withInitial(() -> {
        Col[] a = new Col[COL_SLOTS];
        for (int i = 0; i < a.length; i++) {
            a[i] = new Col();
        }
        return a;
    });

    private Biome earthBiome(EarthData earth, MapSampler sampler, double wx, double wz, int y,
                             long seed) {
        int cx = (int) Math.floor(wx);
        int cz = (int) Math.floor(wz);
        Col col = colCache.get()[(cx * 31 + cz) & (COL_SLOTS - 1)];
        if (col.x != cx || col.z != cz) {
            long tc = System.nanoTime();
            col.c = EarthClimate.params(earth, sampler, wx, wz, y, seed);
            GenProfiler.add("biome.climate", tc);
            col.surfaceY = sampler.heightAt(wx, wz);
            col.river = col.c == null ? 0 : classifyRiver(earth, sampler, wx, wz, col.c);
            col.deep = null;
            col.shallow = null;
            col.ocean = null;
            col.x = cx;
            col.z = cz;
        }
        double[] c = col.c;
        if (c == null) {
            return Biome.THE_VOID;
        }
        double depth = EarthClimate.depth(col.surfaceY, y);
        if (depth < 0.15 && col.river != 0 && c[6] >= 0) {
            return col.river == 2 ? Biome.FROZEN_RIVER : Biome.RIVER;
        }
        // Below sea level, near the surface = an OCEAN biome (matches the water
        // the generator carves). Shallow seas (Red Sea, Persian Gulf, Med
        // shelves) sit at only ~-50 m -> continentalness ~-0.15, which vanilla
        // reads as near-inland land and would map to desert/plains. Assign the
        // ocean biome directly by temperature + depth instead. Deeper down
        // (depth >= 0.15) the vanilla cave/deep logic below still applies.
        if (c[6] < 0 && depth < 0.15) {
            if (col.ocean == null) {
                col.ocean = oceanBiome(c);
            }
            return col.ocean;
        }
        // Cache ONLY the saturated plateau, where depth is constant by definition
        // of the clamp, so caching is lossless. Everything between the clamps is
        // computed per-Y below, which is what lets cave biomes stack vertically
        // (surface biome -> lush/dripstone at 0.2-0.9 -> deep dark past 1.1).
        if (depth >= EarthClimate.DEEP_PLATEAU) {
            if (col.deep == null) {
                col.deep = vanillaBiome(c, EarthClimate.DEEP_PLATEAU);
            }
            return col.deep;
        }
        if (depth <= -0.1) {
            if (col.shallow == null) {
                col.shallow = vanillaBiome(c, -0.1);
            }
            return col.shallow;
        }
        return vanillaBiome(c, depth);
    }

    /** Ocean biome for a below-sea-level column, by temperature param and depth
     * (deep variants below ~1 km; warm has no deep variant in vanilla). */
    private static Biome oceanBiome(double[] c) {
        double t = c[0];              // temperature param
        boolean deep = c[6] < -1000;  // >1 km deep
        if (t < -0.45) {
            return deep ? Biome.DEEP_FROZEN_OCEAN : Biome.FROZEN_OCEAN;
        }
        if (t < -0.15) {
            return deep ? Biome.DEEP_COLD_OCEAN : Biome.COLD_OCEAN;
        }
        if (t < 0.2) {
            return deep ? Biome.DEEP_OCEAN : Biome.OCEAN;
        }
        if (t < 0.45) {
            return deep ? Biome.DEEP_LUKEWARM_OCEAN : Biome.LUKEWARM_OCEAN;
        }
        return Biome.WARM_OCEAN;
    }

    private Biome vanillaBiome(double[] c, double depth) {
        long tv = System.nanoTime();
        Biome b = vanilla().biome(c[0], c[1], c[2], c[3], depth, c[5]);
        GenProfiler.add("biome.vanillaMap", tv);
        return b;
    }

    /** River class at a column (0 none, 1 river, 2 frozen), sampled once. Uses
     * the shared {@link EarthClimate#riverStrength} so the biome coincides
     * exactly with the water the chunk generator carves. */
    private int classifyRiver(EarthData earth, MapSampler sampler, double wx, double wz, double[] c) {
        if (c[6] < 0) {
            return 0;
        }
        if (EarthClimate.riverStrength(earth, sampler, wx, wz) > EarthClimate.RIVER_THRESHOLD) {
            return c[7] < -2 ? 2 : 1;
        }
        return 0;
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        if (maps.hasEarthData()) {
            List<Biome> biomes = new ArrayList<>(vanilla().possibleBiomes());
            biomes.add(Biome.THE_VOID);
            return biomes;
        }
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
