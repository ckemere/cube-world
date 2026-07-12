package com.ckemere.cubeworld.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeSurface;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.EdgeLink;
import com.ckemere.cubeworld.geometry.Vec2;
import org.junit.jupiter.api.Test;

/**
 * The seed contract: same seed reproduces the identical world; different
 * seeds produce different worlds; and every seed's world is seam-consistent
 * (the seed parameterizes global cube-surface fields, so continuity is
 * structural, not luck).
 */
class SeedBehaviorTest {

    private static final int S = 800;
    private static final long[] SEEDS = {0L, 1L, 42L, -7L, 20260712L, Long.MIN_VALUE};

    private final CubeGeometry geo = new CubeGeometry(S);
    private final CubeTopology topo = new CubeTopology(geo);
    private final CubeSurface surface = new CubeSurface(geo);

    @Test
    void sameSeedReproducesIdentically() {
        MapSampler a = new MapSampler(topo, new SphericalDemoSpec(geo, WorldSeeds.from(42L)));
        MapSampler b = new MapSampler(topo, new SphericalDemoSpec(geo, WorldSeeds.from(42L)));
        for (double x = -1100; x <= 1100; x += 137) {
            for (double z = -1100; z <= 1900; z += 211) {
                assertEquals(a.heightAt(x, z), b.heightAt(x, z), 0.0);
                assertEquals(a.themeAt(x, z), b.themeAt(x, z));
            }
        }
    }

    @Test
    void differentSeedsProduceDifferentTerrain() {
        MapSampler a = new MapSampler(topo, new SphericalDemoSpec(geo, WorldSeeds.from(1L)));
        MapSampler b = new MapSampler(topo, new SphericalDemoSpec(geo, WorldSeeds.from(2L)));
        int differing = 0;
        int total = 0;
        for (double x = -350; x <= 350; x += 55) {
            for (double z = -350; z <= 350; z += 55) {
                total++;
                if (Math.abs(a.heightAt(x, z) - b.heightAt(x, z)) > 1.0) {
                    differing++;
                }
            }
        }
        assertTrue(differing > total / 2,
                "seeds should reshape most terrain: " + differing + "/" + total);
    }

    @Test
    void everySeedIsSeamConsistent() {
        for (long seed : SEEDS) {
            WorldSeeds seeds = WorldSeeds.from(seed);
            MapSampler sampler = new MapSampler(topo, new SphericalDemoSpec(geo, seeds));
            CaveCarver carver = new CaveCarver(seeds);
            CaveBiomes caveBiomes = new CaveBiomes(seeds);
            for (EdgeLink l : topo.links()) {
                for (double t = 0.2; t <= 0.8; t += 0.2) {
                    double ax = l.a0().x() + t * (l.a1().x() - l.a0().x());
                    double az = l.a0().z() + t * (l.a1().z() - l.a0().z());
                    double bx = l.b0().x() + t * (l.b1().x() - l.b0().x());
                    double bz = l.b0().z() + t * (l.b1().z() - l.b0().z());
                    // Caves and cave biomes: exact agreement at identified edge points.
                    for (int y = -40; y <= 30; y += 14) {
                        assertEquals(carver.carved(surface.point(l.faceA(), ax, az), y, 70),
                                carver.carved(surface.point(l.faceB(), bx, bz), y, 70),
                                "seed " + seed + " carver " + l.faceA() + "/" + l.sideA());
                        assertEquals(caveBiomes.at(surface.point(l.faceA(), ax, az), y),
                                caveBiomes.at(surface.point(l.faceB(), bx, bz), y),
                                "seed " + seed + " caveBiome " + l.faceA() + "/" + l.sideA());
                    }
                    // Heights: margin point equals its source exactly.
                    Vec2 outside = outwardOf(l, t);
                    Vec2 sourcePt = l.aToB().applyPoint(outside.x(), outside.z());
                    assertEquals(sampler.heightAt(sourcePt.x(), sourcePt.z()),
                            sampler.heightAt(outside.x(), outside.z()), 1e-9,
                            "seed " + seed + " height " + l.faceA() + "/" + l.sideA());
                }
            }
        }
    }

    private Vec2 outwardOf(EdgeLink l, double t) {
        double px = l.a0().x() + t * (l.a1().x() - l.a0().x());
        double pz = l.a0().z() + t * (l.a1().z() - l.a0().z());
        double cx = geo.faceMinX(l.faceA()) + geo.faceSize() / 2.0;
        double cz = geo.faceMinZ(l.faceA()) + geo.faceSize() / 2.0;
        if (l.a0().x() == l.a1().x()) {
            px += Math.signum(px - cx) * 0.25;
        } else {
            pz += Math.signum(pz - cz) * 0.25;
        }
        return new Vec2(px, pz);
    }
}
