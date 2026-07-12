package com.ckemere.cubeworld.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.EdgeLink;
import com.ckemere.cubeworld.geometry.Vec2;
import org.junit.jupiter.api.Test;

class MapSamplerTest {

    private static final int S = 800;
    private static final double EPS = 0.25;

    private final CubeGeometry geo = new CubeGeometry(S);
    private final CubeTopology topo = new CubeTopology(geo);
    private final MapSampler sampler = new MapSampler(topo, new SphericalDemoSpec(geo, WorldSeeds.from(20260712L)));

    /**
     * The prototype's core claim: interpolated height approaching a stitched
     * edge from face A equals the height at the corresponding point
     * approaching from face B. Sampled along every link (skipping segment
     * ends, which sit under corner pillars).
     */
    @Test
    void heightIsContinuousAcrossStitchedSeams() {
        for (EdgeLink l : topo.links()) {
            for (double t = 0.15; t <= 0.85; t += 0.1) {
                // A point just inside face A, EPS shy of the edge...
                Vec2 insideA = nudgeInward(l.faceA(), l.a0(), l.a1(), t);
                // ...and the point just inside face B that a crosser lands on:
                // transform of the mirrored just-outside point.
                Vec2 outsideA = nudgeOutward(l.faceA(), l.a0(), l.a1(), t);
                Vec2 insideB = l.aToB().applyPoint(outsideA.x(), outsideA.z());
                double hA = sampler.heightAt(insideA.x(), insideA.z());
                double hB = sampler.heightAt(insideB.x(), insideB.z());
                assertEquals(hA, hB, 0.35,
                        l.faceA() + "/" + l.sideA() + " t=" + t + ": " + hA + " vs " + hB);
            }
        }
    }

    /** Margin sampling continues the source face's terrain exactly. */
    @Test
    void marginHeightsMatchTheirSource() {
        for (EdgeLink l : topo.links()) {
            Vec2 outside = nudgeOutward(l.faceA(), l.a0(), l.a1(), 0.5);
            Vec2 source = l.aToB().applyPoint(outside.x(), outside.z());
            assertEquals(sampler.heightAt(source.x(), source.z()),
                    sampler.heightAt(outside.x(), outside.z()), 1e-9,
                    "margin of " + l.faceA() + "/" + l.sideA());
        }
    }

    /** The demo spec produces all bands somewhere (oceans and snowcaps exist). */
    @Test
    void demoSpecHasElevationVariety() {
        boolean sea = false;
        boolean snow = false;
        double min = 999;
        double max = -999;
        SphericalDemoSpec spec = new SphericalDemoSpec(geo, WorldSeeds.from(20260712L));
        for (com.ckemere.cubeworld.geometry.CubeFace face : com.ckemere.cubeworld.geometry.CubeFace.values()) {
            for (int cx = 0; cx < spec.cellsPerFace(); cx++) {
                for (int cz = 0; cz < spec.cellsPerFace(); cz++) {
                    double h = spec.heightAt(face, cx, cz);
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                    sea |= spec.themeAt(face, cx, cz) == TerrainTheme.OCEAN;
                    snow |= spec.themeAt(face, cx, cz) == TerrainTheme.SNOWCAP;
                }
            }
        }
        assertTrue(sea, "some ocean");
        assertTrue(snow, "some snowcap");
        assertTrue(min > 40 && max < 100, "sane height range: " + min + ".." + max);
    }

    private Vec2 edgePoint(Vec2 e0, Vec2 e1, double t) {
        return new Vec2(e0.x() + t * (e1.x() - e0.x()), e0.z() + t * (e1.z() - e0.z()));
    }

    private Vec2 nudge(com.ckemere.cubeworld.geometry.CubeFace face, Vec2 e0, Vec2 e1, double t, double sign) {
        Vec2 p = edgePoint(e0, e1, t);
        double cx = geo.faceMinX(face) + geo.faceSize() / 2.0;
        double cz = geo.faceMinZ(face) + geo.faceSize() / 2.0;
        if (e0.x() == e1.x()) {
            return new Vec2(p.x() + sign * Math.signum(p.x() - cx) * EPS, p.z());
        }
        return new Vec2(p.x(), p.z() + sign * Math.signum(p.z() - cz) * EPS);
    }

    private Vec2 nudgeInward(com.ckemere.cubeworld.geometry.CubeFace face, Vec2 e0, Vec2 e1, double t) {
        return nudge(face, e0, e1, t, -1);
    }

    private Vec2 nudgeOutward(com.ckemere.cubeworld.geometry.CubeFace face, Vec2 e0, Vec2 e1, double t) {
        return nudge(face, e0, e1, t, 1);
    }
}
