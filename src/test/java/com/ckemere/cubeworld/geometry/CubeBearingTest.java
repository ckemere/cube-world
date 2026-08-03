package com.ckemere.cubeworld.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubeBearingTest {

    private final CubeGeometry geom = new CubeGeometry(1024);
    private final CubeTopology topo = new CubeTopology(geom);
    private final CubeBearing bearing = new CubeBearing(topo);

    /**
     * Fold each face's centre into every neighbour's frame; the folded point must land
     * just beyond the shared edge (its {@code exitSide} from the home face equals the
     * seam's side). Exercises all 12 seams in both directions, including the rotated
     * pole seams -- so a backwards inverse or wrong side would fail here, not in-game.
     */
    @Test
    void adjacentFaceFoldsBeyondTheSharedEdge() {
        for (EdgeLink l : topo.links()) {
            check(l.faceA(), l.faceB(), l.sideA());
            check(l.faceB(), l.faceA(), l.sideB());
        }
    }

    private void check(CubeFace home, CubeFace target, Side expectedSide) {
        double rx = geom.faceMinX(home) + geom.faceSize() / 2.0;
        double rz = geom.faceMinZ(home) + geom.faceSize() / 2.0;
        double sx = geom.faceMinX(target) + geom.faceSize() / 2.0;
        double sz = geom.faceMinZ(target) + geom.faceSize() / 2.0;

        CubeBearing.Folded f = bearing.fold(rx, rz, sx, sz);

        assertTrue(f.adjacent(), home + " -> " + target + " should fold as adjacent");
        Side exit = topo.exitSide(home, f.x(), f.z());
        assertEquals(expectedSide, exit,
                "folded " + target + " centre should exit " + home + " via " + expectedSide
                        + " but exited " + exit + " at (" + f.x() + ", " + f.z() + ")");
    }

    /** Same face: fold is the identity. */
    @Test
    void sameFaceIsIdentity() {
        double x = geom.faceMinX(CubeFace.EQ_PRIME) + 100.5;
        double z = geom.faceMinZ(CubeFace.EQ_PRIME) + 60.5;
        double rx = geom.faceMinX(CubeFace.EQ_PRIME) + geom.faceSize() / 2.0;
        double rz = geom.faceMinZ(CubeFace.EQ_PRIME) + geom.faceSize() / 2.0;
        CubeBearing.Folded f = bearing.fold(rx, rz, x, z);
        assertTrue(f.adjacent());
        assertEquals(x, f.x(), 1e-9);
        assertEquals(z, f.z(), 1e-9);
    }
}
