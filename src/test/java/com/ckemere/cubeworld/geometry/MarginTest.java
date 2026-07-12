package com.ckemere.cubeworld.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarginTest {

    private static final int S = 800;
    private static final double H = S / 2.0;
    private static final double MARGIN = 96;

    private final CubeGeometry geo = new CubeGeometry(S);
    private final CubeTopology topo = new CubeTopology(geo);

    @Test
    void marginBeyondStitchedEdgeResolvesToPartnerFace() {
        // 5 blocks beyond EQ_PRIME's east edge, mid-face: mirrors EQ_EAST.
        MarginSource source = topo.marginSource(H + 5, 2 * H, MARGIN);
        assertNotNull(source);
        CubeFace face = geo.faceAt((int) Math.floor(source.source().x()),
                (int) Math.floor(source.source().z()));
        assertEquals(CubeFace.EQ_EAST, face);
        // 5 blocks inside EQ_EAST's south edge, tangentially aligned:
        assertEquals(2 * H, source.source().x(), 1e-9);
        assertEquals(H - 5, source.source().z(), 1e-9);
    }

    @Test
    void marginRespectsDepthLimit() {
        assertNotNull(topo.marginSource(H + MARGIN - 0.5, 2 * H, MARGIN));
        assertNull(topo.marginSource(H + MARGIN + 0.5, 2 * H, MARGIN));
    }

    @Test
    void pointsOnFacesAreNotMargins() {
        assertNull(topo.marginSource(0, 0, MARGIN));
        assertNull(topo.marginSource(H - 1, 2 * H, MARGIN));
    }

    @Test
    void deepVoidIsNotMargin() {
        // Center of the diagonal void cell, far from both edges.
        assertNull(topo.marginSource(2 * H, 2 * H, MARGIN));
    }

    @Test
    void inPlaneBoundariesHaveNoMargin() {
        // Just south of NORTH_POLE (on EQ_PRIME): a real face, not a margin.
        assertNull(topo.marginSource(0, H + 5, MARGIN));
    }

    @Test
    void marginAndImageAreInverse() {
        double x = H + 7.25;
        double z = 2 * H + 33;
        MarginSource source = topo.marginSource(x, z, MARGIN);
        assertNotNull(source);
        // The real point's images must include the margin point we started at.
        List<MarginSource> images = topo.marginImages(
                source.source().x(), source.source().z(), MARGIN);
        boolean found = false;
        for (MarginSource image : images) {
            if (Math.abs(image.source().x() - x) < 1e-9 && Math.abs(image.source().z() - z) < 1e-9) {
                found = true;
            }
        }
        assertTrue(found, "real point should mirror back to the margin point");
    }

    @Test
    void realPointNearSeamHasImage() {
        // Inside EQ_PRIME near its east edge.
        List<MarginSource> images = topo.marginImages(H - 3, 2 * H, MARGIN);
        assertEquals(1, images.size());
        // The image lies in the void beyond EQ_EAST's south edge.
        Vec2 img = images.get(0).source();
        assertNull(geo.faceAt((int) Math.floor(img.x()), (int) Math.floor(img.z())));
    }

    @Test
    void realPointNearTwoSeamsHasTwoImages() {
        // Inside SOUTH_POLE near its south-east corner: east + south edges.
        List<MarginSource> images = topo.marginImages(H - 3, 5 * H - 3, MARGIN);
        assertEquals(2, images.size());
    }

    @Test
    void realPointAwayFromSeamsHasNoImages() {
        assertTrue(topo.marginImages(0, 0, MARGIN).isEmpty());
        // Near an in-plane boundary: still no images.
        assertTrue(topo.marginImages(0, H - 2, MARGIN).isEmpty());
    }
}
