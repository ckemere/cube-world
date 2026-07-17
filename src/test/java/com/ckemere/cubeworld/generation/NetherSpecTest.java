package com.ckemere.cubeworld.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.EdgeLink;
import com.ckemere.cubeworld.geometry.Vec2;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * The cube nether obeys the same contracts as the overworld: seam-consistent
 * for every seed, distinct from the overworld's fields, and confined to the
 * nether theme palette.
 */
class NetherSpecTest {

    private static final int S = 800;
    private static final long[] SEEDS = {0L, 42L, 20260712L, Long.MIN_VALUE};

    private final CubeGeometry geo = new CubeGeometry(S);
    private final CubeTopology topo = new CubeTopology(geo);

    @Test
    void netherIsSeamConsistentForEverySeed() {
        for (long seed : SEEDS) {
            MapSampler sampler = new MapSampler(topo, new NetherDemoSpec(geo, WorldSeeds.from(seed)));
            for (EdgeLink l : topo.links()) {
                for (double t = 0.2; t <= 0.8; t += 0.2) {
                    Vec2 outside = outwardOf(l, t);
                    Vec2 sourcePt = l.aToB().applyPoint(outside.x(), outside.z());
                    assertEquals(sampler.heightAt(sourcePt.x(), sourcePt.z()),
                            sampler.heightAt(outside.x(), outside.z()), 1e-9,
                            "seed " + seed + " nether height " + l.faceA() + "/" + l.sideA());
                    assertEquals(sampler.themeAt(sourcePt.x(), sourcePt.z()),
                            sampler.themeAt(outside.x(), outside.z()),
                            "seed " + seed + " nether theme " + l.faceA() + "/" + l.sideA());
                }
            }
        }
    }

    @Test
    void netherTerrainDiffersFromOverworld() {
        WorldSeeds seeds = WorldSeeds.from(20260712L);
        MapSampler over = new MapSampler(topo, new SphericalDemoSpec(geo, seeds));
        MapSampler nether = new MapSampler(topo, new NetherDemoSpec(geo, seeds));
        int differing = 0;
        int total = 0;
        for (double x = -350; x <= 350; x += 55) {
            for (double z = -350; z <= 350; z += 55) {
                total++;
                if (Math.abs(over.heightAt(x, z) - nether.heightAt(x, z)) > 1.0) {
                    differing++;
                }
            }
        }
        assertTrue(differing > total / 2,
                "nether should not echo the overworld: " + differing + "/" + total);
    }

    @Test
    void netherUsesOnlyNetherThemes() {
        EnumSet<TerrainTheme> allowed = EnumSet.of(TerrainTheme.NETHER_WASTES,
                TerrainTheme.CRIMSON_FOREST, TerrainTheme.WARPED_FOREST,
                TerrainTheme.SOUL_SAND_VALLEY, TerrainTheme.BASALT_DELTAS);
        MapSampler sampler = new MapSampler(topo, new NetherDemoSpec(geo, WorldSeeds.from(7L)));
        EnumSet<TerrainTheme> seen = EnumSet.noneOf(TerrainTheme.class);
        for (double x = -1100; x <= 1100; x += 97) {
            for (double z = -1100; z <= 1900; z += 89) {
                TerrainTheme theme = sampler.themeAt(x, z);
                if (theme != null) {
                    assertTrue(allowed.contains(theme), "unexpected theme " + theme);
                    seen.add(theme);
                }
            }
        }
        assertTrue(seen.size() >= 3, "expected a varied nether, saw only " + seen);
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

    @Test
    void netherSeedsDiffer() {
        MapSampler a = new MapSampler(topo, new NetherDemoSpec(geo, WorldSeeds.from(1L)));
        MapSampler b = new MapSampler(topo, new NetherDemoSpec(geo, WorldSeeds.from(2L)));
        boolean differs = false;
        for (double x = -350; x <= 350 && !differs; x += 90) {
            for (double z = -350; z <= 350 && !differs; z += 90) {
                differs = Math.abs(a.heightAt(x, z) - b.heightAt(x, z)) > 1.0;
            }
        }
        assertNotEquals(false, differs, "different seeds must reshape the nether");
    }
}
