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
            return earthBiome(earth, sampler, wx, wz, y);
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

    private Biome earthBiome(EarthData earth, MapSampler sampler, double wx, double wz, int y) {
        Vec3 p = sampler.cubePointAt(wx, wz);
        if (p == null) {
            return Biome.THE_VOID;
        }
        double[] ll = earth.toLonLat(p);
        double lon = ll[0];
        double lat = ll[1];
        double elev = earth.sample("height", lon, lat);
        double temp = earth.sample("temp", lon, lat);
        double precip = earth.sample("precip", lon, lat);
        if (Double.isNaN(temp)) {
            temp = 27.0 - Math.abs(lat) * 0.65;
        }
        if (Double.isNaN(precip)) {
            precip = 700.0;
        }
        double rugged = ruggedness(earth, lon, lat, elev);
        double surfaceY = sampler.heightAt(wx, wz);
        double humidityParam = EarthClimate.humidity(precip);
        return vanilla().biome(
                EarthClimate.temperature(temp, humidityParam),
                humidityParam,
                EarthClimate.continentalness(elev),
                EarthClimate.erosion(rugged),
                EarthClimate.depth(surfaceY, y),
                EarthClimate.weirdness(p, elev));
    }

    /** Local relief in metres, ~0.08 deg (~9 km) around the point. */
    private static double ruggedness(EarthData earth, double lon, double lat, double elev) {
        double d = 0.08;
        double max = 0;
        for (double[] o : new double[][] {{d, 0}, {-d, 0}, {0, d}, {0, -d}}) {
            double h = earth.sample("height", lon + o[0], lat + o[1]);
            if (!Double.isNaN(h)) {
                max = Math.max(max, Math.abs(h - elev));
            }
        }
        return max;
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
