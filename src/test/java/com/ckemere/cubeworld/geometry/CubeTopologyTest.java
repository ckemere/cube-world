package com.ckemere.cubeworld.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CubeTopologyTest {

    private static final int S = 800;
    private static final double H = S / 2.0;
    private static final double EPS = 0.25;

    private final CubeGeometry geo = new CubeGeometry(S);
    private final CubeTopology topo = new CubeTopology(geo);

    @Test
    void sevenStitchedEdges() {
        assertEquals(7, topo.links().size());
    }

    @Test
    void endpointsMapToEndpoints() {
        for (EdgeLink l : topo.links()) {
            Vec2 m0 = l.aToB().applyPoint(l.a0().x(), l.a0().z());
            Vec2 m1 = l.aToB().applyPoint(l.a1().x(), l.a1().z());
            assertEquals(l.b0().x(), m0.x(), 1e-9, l + " a0.x");
            assertEquals(l.b0().z(), m0.z(), 1e-9, l + " a0.z");
            assertEquals(l.b1().x(), m1.x(), 1e-9, l + " a1.x");
            assertEquals(l.b1().z(), m1.z(), 1e-9, l + " a1.z");
        }
    }

    @Test
    void roundTripIsIdentity() {
        for (EdgeLink l : topo.links()) {
            EdgeTransform inv = l.aToB().inverse();
            for (double t : new double[] {0.1, 0.5, 0.9}) {
                double px = l.a0().x() + t * (l.a1().x() - l.a0().x()) + 7.25;
                double pz = l.a0().z() + t * (l.a1().z() - l.a0().z()) - 3.5;
                Vec2 out = l.aToB().applyPoint(px, pz);
                Vec2 back = inv.applyPoint(out.x(), out.z());
                assertEquals(px, back.x(), 1e-9);
                assertEquals(pz, back.z(), 1e-9);
            }
        }
    }

    /**
     * The load-bearing property: stepping just outside faceA across a stitched
     * edge must land just inside faceB, and vice versa, all along the edge.
     */
    @Test
    void outwardStepsLandInsidePartnerFace() {
        for (EdgeLink l : topo.links()) {
            for (double t : new double[] {0.05, 0.3, 0.5, 0.7, 0.95}) {
                Vec2 outsideA = pointBeyond(l.faceA(), l.a0(), l.a1(), t);
                assertNull(geo.faceAt((int) Math.floor(outsideA.x()), (int) Math.floor(outsideA.z())),
                        "point beyond " + l.faceA() + "/" + l.sideA() + " should be void");
                Vec2 mapped = l.aToB().applyPoint(outsideA.x(), outsideA.z());
                assertEquals(l.faceB(), geo.faceAt((int) Math.floor(mapped.x()), (int) Math.floor(mapped.z())),
                        "crossing " + l.faceA() + "/" + l.sideA() + " must land on " + l.faceB());

                Vec2 outsideB = pointBeyond(l.faceB(), l.b0(), l.b1(), t);
                Vec2 mappedBack = l.aToB().inverse().applyPoint(outsideB.x(), outsideB.z());
                assertEquals(l.faceA(), geo.faceAt((int) Math.floor(mappedBack.x()), (int) Math.floor(mappedBack.z())),
                        "crossing " + l.faceB() + "/" + l.sideB() + " must land on " + l.faceA());
            }
        }
    }

    /** A point at parameter t along the segment, nudged EPS outward from the face. */
    private Vec2 pointBeyond(CubeFace face, Vec2 e0, Vec2 e1, double t) {
        double px = e0.x() + t * (e1.x() - e0.x());
        double pz = e0.z() + t * (e1.z() - e0.z());
        double cx = geo.faceMinX(face) + geo.faceSize() / 2.0;
        double cz = geo.faceMinZ(face) + geo.faceSize() / 2.0;
        // Outward = away from the face center, perpendicular to the segment.
        if (e0.x() == e1.x()) {
            px += Math.signum(px - cx) * EPS;
        } else {
            pz += Math.signum(pz - cz) * EPS;
        }
        return new Vec2(px, pz);
    }

    /** Distance along the edge from the corresponding vertex is preserved. */
    @Test
    void tangentialContinuity() {
        for (EdgeLink l : topo.links()) {
            for (double d : new double[] {1, 123.456, S - 1}) {
                double ux = Math.signum(l.a1().x() - l.a0().x());
                double uz = Math.signum(l.a1().z() - l.a0().z());
                Vec2 onA = new Vec2(l.a0().x() + ux * d, l.a0().z() + uz * d);
                Vec2 mapped = l.aToB().applyPoint(onA.x(), onA.z());
                double vx = Math.signum(l.b1().x() - l.b0().x());
                double vz = Math.signum(l.b1().z() - l.b0().z());
                assertEquals(l.b0().x() + vx * d, mapped.x(), 1e-9);
                assertEquals(l.b0().z() + vz * d, mapped.z(), 1e-9);
            }
        }
    }

    @Test
    void expectedQuarterTurnsPerLink() {
        int[] expected = {1, 3, 3, 1, 2, 2, 0};
        for (int i = 0; i < 7; i++) {
            assertEquals(expected[i], topo.links().get(i).aToB().quarterTurns(),
                    "link " + (i + 1) + ": " + topo.links().get(i));
        }
    }

    @Test
    void yawRotation() {
        // Link 1 (turns=1): heading east (yaw -90) off EQ_PRIME becomes
        // heading north (yaw 180) into EQ_EAST.
        EdgeTransform t1 = topo.transformFor(CubeFace.EQ_PRIME, Side.EAST);
        assertNotNull(t1);
        assertEquals(180.0f, Math.abs(t1.applyYaw(-90.0f)), 1e-6);
        // Velocity east becomes velocity north.
        Vec2 vel = t1.applyVector(1.0, 0.0);
        assertEquals(0.0, vel.x(), 1e-9);
        assertEquals(-1.0, vel.z(), 1e-9);

        // Link 7 (turns=0): heading south stays heading south.
        EdgeTransform t7 = topo.transformFor(CubeFace.SOUTH_POLE, Side.SOUTH);
        assertNotNull(t7);
        assertEquals(0.0f, t7.applyYaw(0.0f), 1e-6);
    }

    @Test
    void inPlaneSidesHaveNoTransform() {
        for (Side side : Side.values()) {
            assertNull(topo.transformFor(CubeFace.NORTH_POLE, side));
        }
        assertNull(topo.transformFor(CubeFace.EQ_PRIME, Side.NORTH));
        assertNull(topo.transformFor(CubeFace.EQ_PRIME, Side.SOUTH));
        assertNull(topo.transformFor(CubeFace.SOUTH_POLE, Side.NORTH));
        assertNull(topo.transformFor(CubeFace.EQ_EAST, Side.WEST));
        assertNull(topo.transformFor(CubeFace.EQ_BACK, Side.SOUTH));
        assertNull(topo.transformFor(CubeFace.EQ_WEST, Side.EAST));
    }

    @Test
    void everyStitchedSideHasATransform() {
        record FS(CubeFace f, Side s) {}
        FS[] stitched = {
                new FS(CubeFace.EQ_PRIME, Side.EAST), new FS(CubeFace.EQ_PRIME, Side.WEST),
                new FS(CubeFace.EQ_BACK, Side.EAST), new FS(CubeFace.EQ_BACK, Side.WEST),
                new FS(CubeFace.EQ_BACK, Side.NORTH),
                new FS(CubeFace.EQ_EAST, Side.NORTH), new FS(CubeFace.EQ_EAST, Side.SOUTH),
                new FS(CubeFace.EQ_EAST, Side.EAST),
                new FS(CubeFace.EQ_WEST, Side.NORTH), new FS(CubeFace.EQ_WEST, Side.SOUTH),
                new FS(CubeFace.EQ_WEST, Side.WEST),
                new FS(CubeFace.SOUTH_POLE, Side.EAST), new FS(CubeFace.SOUTH_POLE, Side.WEST),
                new FS(CubeFace.SOUTH_POLE, Side.SOUTH),
        };
        assertEquals(14, stitched.length);
        for (FS fs : stitched) {
            assertNotNull(topo.transformFor(fs.f(), fs.s()), fs.f() + "/" + fs.s());
        }
    }

    @Test
    void crossingForDetectsStitchedAndIgnoresInPlane() {
        // Walking east off EQ_PRIME mid-edge: stitched.
        assertNotNull(topo.crossingFor(CubeFace.EQ_PRIME, H + EPS, 2 * H));
        // Walking north off EQ_PRIME onto NORTH_POLE: in-plane.
        assertNull(topo.crossingFor(CubeFace.EQ_PRIME, 0, H - EPS));
        assertNull(topo.crossingFor(CubeFace.EQ_PRIME, 0, H + EPS));
        // Staying on the face.
        assertNull(topo.crossingFor(CubeFace.EQ_PRIME, 0, 2 * H));
    }

    @Test
    void fourteenPillarSites() {
        List<Vec2> sites = topo.pillarSites();
        assertEquals(14, sites.size());
        // The four reflex corners of the cross (northern cube vertices).
        assertTrue(sites.contains(new Vec2(H, H)));
        assertTrue(sites.contains(new Vec2(-H, H)));
        assertTrue(sites.contains(new Vec2(H, -H)));
        assertTrue(sites.contains(new Vec2(-H, -H)));
        // A southern vertex appears at multiple net images.
        assertTrue(sites.contains(new Vec2(H, 3 * H)));
        assertTrue(sites.contains(new Vec2(3 * H, H)));
        assertTrue(sites.contains(new Vec2(H, 5 * H)));
        assertTrue(sites.contains(new Vec2(-H, 5 * H)));
    }

    /** State for walking the net: position, heading, current face. */
    private record Walker(CubeFace face, double x, double z, double dx, double dz) {
    }

    /** March straight ahead until just past the current face's boundary, then resolve the crossing. */
    private Walker cross(Walker w) {
        double minX = geo.faceMinX(w.face());
        double minZ = geo.faceMinZ(w.face());
        double maxX = minX + geo.faceSize();
        double maxZ = minZ + geo.faceSize();
        double x = w.x();
        double z = w.z();
        if (w.dx() > 0) {
            x = maxX + EPS;
        } else if (w.dx() < 0) {
            x = minX - EPS;
        } else if (w.dz() > 0) {
            z = maxZ + EPS;
        } else {
            z = minZ - EPS;
        }
        EdgeTransform t = topo.crossingFor(w.face(), x, z);
        double dx = w.dx();
        double dz = w.dz();
        if (t != null) {
            Vec2 p = t.applyPoint(x, z);
            Vec2 d = t.applyVector(dx, dz);
            x = p.x();
            z = p.z();
            dx = d.x();
            dz = d.z();
        }
        CubeFace face = geo.faceAt((int) Math.floor(x), (int) Math.floor(z));
        assertNotNull(face, "walker landed in void at (" + x + ", " + z + ")");
        return new Walker(face, x, z, dx, dz);
    }

    /**
     * Walking east around the equator crosses 4 stitched edges and returns to
     * the start with the original heading — the "international date line" wrap.
     */
    @Test
    void equatorialLoopClosesUp() {
        Walker w = new Walker(CubeFace.EQ_PRIME, 0, 2 * H, 1, 0);
        CubeFace[] expected = {CubeFace.EQ_EAST, CubeFace.EQ_BACK, CubeFace.EQ_WEST, CubeFace.EQ_PRIME};
        for (CubeFace f : expected) {
            w = cross(w);
            assertEquals(f, w.face());
        }
        assertEquals(2 * H, w.z(), 1e-9, "back on the starting equator row");
        assertEquals(1.0, w.dx(), 1e-9, "heading restored to east");
        assertEquals(0.0, w.dz(), 1e-9);
    }

    /**
     * Walking south along the prime meridian goes over the south pole face,
     * wraps to the far side, and comes back over the north pole — the
     * pole-crossing loop. Only one of the four boundaries is stitched.
     */
    @Test
    void meridianLoopClosesUp() {
        Walker w = new Walker(CubeFace.NORTH_POLE, 0, 0, 0, 1);
        CubeFace[] expected = {CubeFace.EQ_PRIME, CubeFace.SOUTH_POLE, CubeFace.EQ_BACK, CubeFace.NORTH_POLE};
        for (CubeFace f : expected) {
            w = cross(w);
            assertEquals(f, w.face());
        }
        assertEquals(0.0, w.x(), 1e-9, "back on the prime meridian column");
        assertEquals(0.0, w.dx(), 1e-9);
        assertEquals(1.0, w.dz(), 1e-9, "heading restored to south");
    }
}
