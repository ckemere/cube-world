package com.ckemere.cubeworld.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Locks the Java value-noise to be bit-identical to the map tool's
 * biomegen/noise.py (reference values generated there). If these drift, the
 * generated world's biomes stop matching the map.
 */
class ClimateNoiseTest {

    private static final double EPS = 1e-9;

    @Test
    void hashMatchesPython() {
        assertEquals(0.7392578125, ClimateNoise.hash01(5, -3, 100), EPS);
        assertEquals(0.4096832275390625, ClimateNoise.hash01(-1234, 5678, 20260723), EPS);
    }

    @Test
    void valueNoiseMatchesPython() {
        assertEquals(0.40254753017985073, ClimateNoise.vnoise(9381.0, -1737.0, 11, 340.0), EPS);
    }

    @Test
    void fbmMatchesPython() {
        assertEquals(-0.1441511914336712, ClimateNoise.fbm(9381.0, -1737.0, 20260723, 340.0, 3), EPS);
        assertEquals(0.2684056787112596, ClimateNoise.fbm(-5000.5, 8000.25, 20260795, 578.0, 3), EPS);
    }
}
