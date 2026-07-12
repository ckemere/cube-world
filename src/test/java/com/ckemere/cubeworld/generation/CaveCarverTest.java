package com.ckemere.cubeworld.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeSurface;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.EdgeLink;
import org.junit.jupiter.api.Test;

class CaveCarverTest {

    private static final int S = 800;

    private final CubeGeometry geo = new CubeGeometry(S);
    private final CubeTopology topo = new CubeTopology(geo);
    private final CubeSurface surface = new CubeSurface(geo);
    private final CaveCarver carver = new CaveCarver(WorldSeeds.from(20260712L));

    /** Tunnels agree across stitched seams: same edge point, same carving. */
    @Test
    void carvingAgreesAcrossStitchedSeams() {
        for (EdgeLink l : topo.links()) {
            for (double t = 0.1; t <= 0.9; t += 0.05) {
                double ax = l.a0().x() + t * (l.a1().x() - l.a0().x());
                double az = l.a0().z() + t * (l.a1().z() - l.a0().z());
                double bx = l.b0().x() + t * (l.b1().x() - l.b0().x());
                double bz = l.b0().z() + t * (l.b1().z() - l.b0().z());
                for (int y = -50; y <= 40; y += 5) {
                    assertEquals(
                            carver.carved(surface.point(l.faceA(), ax, az), y, 70),
                            carver.carved(surface.point(l.faceB(), bx, bz), y, 70),
                            l.faceA() + "/" + l.sideA() + " t=" + t + " y=" + y);
                }
            }
        }
    }

    /** Carved fraction at depth is cave-like: a few percent, not zero, not half. */
    @Test
    void carvedDensityIsReasonable() {
        int carved = 0;
        int total = 0;
        for (CubeFace face : CubeFace.values()) {
            for (int i = 0; i < 40; i++) {
                for (int j = 0; j < 40; j++) {
                    double wx = geo.faceMinX(face) + i * 20 + 3;
                    double wz = geo.faceMinZ(face) + j * 20 + 3;
                    for (int y = -40; y <= 40; y += 4) {
                        total++;
                        if (carver.carved(surface.point(face, wx, wz), y, 75)) {
                            carved++;
                        }
                    }
                }
            }
        }
        double fraction = (double) carved / total;
        assertTrue(fraction > 0.01, "caves exist: " + fraction);
        assertTrue(fraction < 0.20, "not swiss cheese: " + fraction);
    }

    /** The roof holds: nothing carved within ROOF blocks of the surface. */
    @Test
    void roofIsNeverBreached() {
        for (CubeFace face : CubeFace.values()) {
            double cx = geo.faceMinX(face) + 123;
            double cz = geo.faceMinZ(face) + 321;
            double surfaceHeight = 64;
            for (int y = (int) surfaceHeight - CaveCarver.ROOF + 1; y < surfaceHeight + 5; y++) {
                assertFalse(carver.carved(surface.point(face, cx, cz), y, surfaceHeight),
                        face + " y=" + y);
            }
        }
    }
}
