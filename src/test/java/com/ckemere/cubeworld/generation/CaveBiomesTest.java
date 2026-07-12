package com.ckemere.cubeworld.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeSurface;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.EdgeLink;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CaveBiomesTest {

    private static final int S = 800;

    private final CubeGeometry geo = new CubeGeometry(S);
    private final CubeTopology topo = new CubeTopology(geo);
    private final CubeSurface surface = new CubeSurface(geo);
    private final CaveBiomes caveBiomes = new CaveBiomes(WorldSeeds.from(20260712L));

    /**
     * Cave biome patches agree across stitched seams: identical edge points
     * (via both faces' charts) classify identically at every depth.
     */
    @Test
    void caveBiomesAgreeAcrossStitchedSeams() {
        for (EdgeLink l : topo.links()) {
            for (double t = 0.1; t <= 0.9; t += 0.1) {
                double ax = l.a0().x() + t * (l.a1().x() - l.a0().x());
                double az = l.a0().z() + t * (l.a1().z() - l.a0().z());
                double bx = l.b0().x() + t * (l.b1().x() - l.b0().x());
                double bz = l.b0().z() + t * (l.b1().z() - l.b0().z());
                for (int y = -50; y <= 40; y += 10) {
                    assertEquals(
                            caveBiomes.at(surface.point(l.faceA(), ax, az), y),
                            caveBiomes.at(surface.point(l.faceB(), bx, bz), y),
                            l.faceA() + "/" + l.sideA() + " t=" + t + " y=" + y);
                }
            }
        }
    }

    /** All three cave biomes actually occur somewhere underground. */
    @Test
    void allCaveBiomesOccur() {
        EnumSet<CaveBiomes.CaveBiome> seen = EnumSet.noneOf(CaveBiomes.CaveBiome.class);
        for (CubeFace face : CubeFace.values()) {
            for (int i = 0; i < 50; i++) {
                for (int j = 0; j < 50; j++) {
                    double wx = geo.faceMinX(face) + i * 16 + 8;
                    double wz = geo.faceMinZ(face) + j * 16 + 8;
                    for (int y = -55; y <= 40; y += 12) {
                        seen.add(caveBiomes.at(surface.point(face, wx, wz), y));
                    }
                }
            }
        }
        assertTrue(seen.contains(CaveBiomes.CaveBiome.LUSH), "lush occurs");
        assertTrue(seen.contains(CaveBiomes.CaveBiome.DRIPSTONE), "dripstone occurs");
        assertTrue(seen.contains(CaveBiomes.CaveBiome.DEEP_DARK), "deep dark occurs");
        assertTrue(seen.contains(CaveBiomes.CaveBiome.NONE), "plain underground occurs");
    }

    /** Nothing above the ceiling. */
    @Test
    void noCaveBiomesAboveCeiling() {
        for (CubeFace face : CubeFace.values()) {
            double cx = geo.faceMinX(face) + 400;
            double cz = geo.faceMinZ(face) + 400;
            for (int y = CaveBiomes.CAVE_BIOME_MAX_Y + 1; y < 100; y += 7) {
                assertEquals(CaveBiomes.CaveBiome.NONE, caveBiomes.at(surface.point(face, cx, cz), y));
            }
        }
    }
}
