package com.ckemere.cubeworld.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CubeGeometryTest {

    private static final int S = 800;
    private final CubeGeometry geo = new CubeGeometry(S);

    @Test
    void faceSizeMustBeMultipleOf32() {
        assertThrows(IllegalArgumentException.class, () -> new CubeGeometry(50));
        assertThrows(IllegalArgumentException.class, () -> new CubeGeometry(-800));
        assertThrows(IllegalArgumentException.class, () -> new CubeGeometry(0));
    }

    @Test
    void faceCenters() {
        assertEquals(CubeFace.NORTH_POLE, geo.faceAt(0, 0));
        assertEquals(CubeFace.EQ_PRIME, geo.faceAt(0, S));
        assertEquals(CubeFace.EQ_EAST, geo.faceAt(S, 0));
        assertEquals(CubeFace.EQ_BACK, geo.faceAt(0, -S));
        assertEquals(CubeFace.EQ_WEST, geo.faceAt(-S, 0));
        assertEquals(CubeFace.SOUTH_POLE, geo.faceAt(0, 2 * S));
    }

    @Test
    void faceBoundariesAreHalfOpen() {
        // North-pole face spans [-400, 400) on both axes.
        assertEquals(CubeFace.NORTH_POLE, geo.faceAt(-S / 2, -S / 2));
        assertEquals(CubeFace.NORTH_POLE, geo.faceAt(S / 2 - 1, S / 2 - 1));
        assertEquals(CubeFace.EQ_EAST, geo.faceAt(S / 2, 0));
        assertEquals(CubeFace.EQ_WEST, geo.faceAt(-S / 2 - 1, 0));
        assertEquals(CubeFace.EQ_PRIME, geo.faceAt(0, S / 2));
        assertEquals(CubeFace.EQ_BACK, geo.faceAt(0, -S / 2 - 1));
    }

    @Test
    void outsideTheCrossIsNull() {
        // Diagonal neighbors of the center face are not part of the cross.
        assertNull(geo.faceAt(S, S));
        assertNull(geo.faceAt(-S, -S));
        assertNull(geo.faceAt(S, -S));
        assertNull(geo.faceAt(-S, S));
        // Beyond the arms.
        assertNull(geo.faceAt(2 * S, 0));
        assertNull(geo.faceAt(0, 3 * S));
        assertNull(geo.faceAt(0, -2 * S));
        // Beside the south-pole face.
        assertNull(geo.faceAt(S, 2 * S));
    }

    @Test
    void localCoordinatesSpanZeroToFaceSize() {
        assertEquals(0, geo.localX(CubeFace.NORTH_POLE, -S / 2));
        assertEquals(S - 1, geo.localX(CubeFace.NORTH_POLE, S / 2 - 1));
        assertEquals(0, geo.localZ(CubeFace.EQ_PRIME, S / 2));
        assertEquals(S - 1, geo.localZ(CubeFace.EQ_PRIME, S + S / 2 - 1));
        assertEquals(0, geo.localX(CubeFace.EQ_EAST, S / 2));
        assertEquals(S - 1, geo.localX(CubeFace.EQ_EAST, S + S / 2 - 1));
    }

    @Test
    void faceMinCornersAreChunkAligned() {
        for (CubeFace face : CubeFace.values()) {
            assertEquals(0, Math.floorMod(geo.faceMinX(face), 16), "minX of " + face);
            assertEquals(0, Math.floorMod(geo.faceMinZ(face), 16), "minZ of " + face);
        }
    }
}
