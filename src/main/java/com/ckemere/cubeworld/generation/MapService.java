package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeTopology;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-seed map bundles: the world seed selects the noise parameters, and
 * everything derived from them is cached and immutable (chunk generation is
 * multi-threaded). A data-driven spec (the eventual Earth map) would ignore
 * the seed for shape and keep it for caves and decoration detail.
 */
public final class MapService {

    /** Everything the generator needs for one world seed. */
    public record CubeWorldMap(long seed, MapSampler sampler, MapSampler netherSampler,
                               CaveCarver carver, CaveBiomes caveBiomes) {
    }

    private final CubeTopology topology;
    private final Map<Long, CubeWorldMap> cache = new ConcurrentHashMap<>();
    private volatile EarthData earth;

    public MapService(CubeTopology topology) {
        this.topology = topology;
    }

    /** Supply real Earth rasters; when set, the overworld uses {@link EarthMapSpec}
     * instead of the demo spec. Call before any chunk generates. */
    public void setEarthData(EarthData earth) {
        this.earth = earth;
        cache.clear();
    }

    public boolean hasEarthData() {
        return earth != null;
    }

    public CubeWorldMap mapFor(long seed) {
        return cache.computeIfAbsent(seed, s -> {
            WorldSeeds seeds = WorldSeeds.from(s);
            MapSpec overworldSpec = earth != null
                    ? new EarthMapSpec(topology.geometry(), earth)
                    : new SphericalDemoSpec(topology.geometry(), seeds);
            MapSampler sampler = new MapSampler(topology, overworldSpec);
            MapSampler netherSampler = new MapSampler(topology,
                    new NetherDemoSpec(topology.geometry(), seeds));
            return new CubeWorldMap(s, sampler, netherSampler,
                    new CaveCarver(seeds), new CaveBiomes(seeds));
        });
    }
}
