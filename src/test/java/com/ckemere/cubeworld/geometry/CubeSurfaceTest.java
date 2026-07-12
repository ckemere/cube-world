package com.ckemere.cubeworld.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubeSurfaceTest {

    private static final int S = 800;
    private static final double H = S / 2.0;
    private static final double EPS = 1e-9;

    private final CubeGeometry geo = new CubeGeometry(S);
    private final CubeTopology topo = new CubeTopology(geo);
    private final CubeSurface surface = new CubeSurface(geo);

    private void assertSamePoint(Vec3 a, Vec3 b, String context) {
        assertEquals(a.x(), b.x(), EPS, context + " x");
        assertEquals(a.y(), b.y(), EPS, context + " y");
        assertEquals(a.z(), b.z(), EPS, context + " z");
    }

    /** Every point maps onto the cube surface: one coordinate is exactly +-1. */
    @Test
    void pointsLieOnTheCubeSurface() {
        for (CubeFace face : CubeFace.values()) {
            double cx = geo.faceMinX(face) + H;
            double cz = geo.faceMinZ(face) + H;
            for (double dx : new double[] {-H + 1, 0, H - 1}) {
                for (double dz : new double[] {-H + 1, 0, H - 1}) {
                    Vec3 p = surface.point(face, cx + dx, cz + dz);
                    double max = Math.max(Math.abs(p.x()), Math.max(Math.abs(p.y()), Math.abs(p.z())));
                    assertEquals(1.0, max, EPS, face + " on surface");
                }
            }
        }
    }

    /**
     * The continuity theorem: along every stitched edge, corresponding
     * points (via the vertex-order parameterization) map to the same 3D
     * cube point from both faces.
     */
    @Test
    void stitchedEdgesAgreeInThreeSpace() {
        for (EdgeLink l : topo.links()) {
            for (double t = 0.0; t <= 1.0; t += 0.125) {
                double ax = l.a0().x() + t * (l.a1().x() - l.a0().x());
                double az = l.a0().z() + t * (l.a1().z() - l.a0().z());
                double bx = l.b0().x() + t * (l.b1().x() - l.b0().x());
                double bz = l.b0().z() + t * (l.b1().z() - l.b0().z());
                assertSamePoint(surface.point(l.faceA(), ax, az), surface.point(l.faceB(), bx, bz),
                        "link " + l.faceA() + "/" + l.sideA() + " t=" + t);
            }
        }
    }

    /** In-plane edges agree trivially too: same world point, both incident faces. */
    @Test
    void inPlaneEdgesAgreeInThreeSpace() {
        record Adj(CubeFace a, CubeFace b, double x0, double z0, double x1, double z1) {}
        Adj[] shared = {
                new Adj(CubeFace.NORTH_POLE, CubeFace.EQ_PRIME, -H, H, H, H),
                new Adj(CubeFace.NORTH_POLE, CubeFace.EQ_EAST, H, -H, H, H),
                new Adj(CubeFace.NORTH_POLE, CubeFace.EQ_BACK, -H, -H, H, -H),
                new Adj(CubeFace.NORTH_POLE, CubeFace.EQ_WEST, -H, -H, -H, H),
                new Adj(CubeFace.EQ_PRIME, CubeFace.SOUTH_POLE, -H, 3 * H, H, 3 * H),
        };
        for (Adj adj : shared) {
            for (double t = 0.0; t <= 1.0; t += 0.25) {
                double x = adj.x0() + t * (adj.x1() - adj.x0());
                double z = adj.z0() + t * (adj.z1() - adj.z0());
                assertSamePoint(surface.point(adj.a(), x, z), surface.point(adj.b(), x, z),
                        adj.a() + "-" + adj.b() + " t=" + t);
            }
        }
    }

    /** Face centers hit the six axis points. */
    @Test
    void faceCentersAreAxisPoints() {
        assertSamePoint(new Vec3(0, 1, 0), surface.point(CubeFace.NORTH_POLE, 0, 0), "N");
        assertSamePoint(new Vec3(0, -1, 0), surface.point(CubeFace.SOUTH_POLE, 0, 2 * S), "S");
        assertSamePoint(new Vec3(0, 0, 1), surface.point(CubeFace.EQ_PRIME, 0, S), "E0");
        assertSamePoint(new Vec3(0, 0, -1), surface.point(CubeFace.EQ_BACK, 0, -S), "E180");
        assertSamePoint(new Vec3(1, 0, 0), surface.point(CubeFace.EQ_EAST, S, 0), "E90");
        assertSamePoint(new Vec3(-1, 0, 0), surface.point(CubeFace.EQ_WEST, -S, 0), "E270");
    }

    /** Margin resolution and the embedding tell the same story: a margin
     *  point's source maps to the 3D point the margin visually continues. */
    @Test
    void marginSourcesAgreeInThreeSpace() {
        // Just beyond EQ_PRIME's east edge, mid-face.
        MarginSource src = topo.marginSource(H + 4, 2 * H, 96);
        assertNotNull(src);
        CubeFace sourceFace = geo.faceAt((int) Math.floor(src.source().x()), (int) Math.floor(src.source().z()));
        assertNotNull(sourceFace);
        Vec3 viaSource = surface.point(sourceFace, src.source().x(), src.source().z());
        // The margin point continues EQ_PRIME's chart past its edge; in 3D
        // that walks off the face onto the adjacent one. Nearness check: the
        // source's 3D point must be within the fold distance of the edge.
        Vec3 edge = surface.point(CubeFace.EQ_PRIME, H, 2 * H);
        double d = Math.abs(viaSource.x() - edge.x()) + Math.abs(viaSource.y() - edge.y())
                + Math.abs(viaSource.z() - edge.z());
        assertTrue(d < 0.05, "source 3D point near the fold edge, was " + d);
    }
}
