package com.ckemere.cubeworld.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ckemere.cubeworld.geometry.Vec3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class EarthDataTest {

    /** Hand-build a tiny CWE1 file and read it back. */
    @Test
    void roundTripsSyntheticFile() throws IOException {
        int w = 4, h = 2;
        // height layer: values 0..7 (raw), scale 1
        short[] vals = {0, 1, 2, 3, 4, 5, 6, 7};
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + (8 + 16) + w * h * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.put("CWE1".getBytes(StandardCharsets.US_ASCII));
        buf.putFloat(-70.0f);
        buf.putInt(1);
        byte[] nm = new byte[8];
        byte[] hb = "height".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(hb, 0, nm, 0, hb.length);
        buf.put(nm);
        buf.putInt(w);
        buf.putInt(h);
        buf.putFloat(2.0f);   // scale
        buf.putFloat(10.0f);  // offset
        for (short v : vals) {
            buf.putShort(v);
        }
        Path tmp = Files.createTempFile("cwe1", ".dat");
        Files.write(tmp, buf.array());

        EarthData d = EarthData.load(tmp);
        assertTrue(d.hasLayer("height"));
        assertEquals(-70.0, d.roll(), 1e-4);
        // pixel (0,0) is lon -180, lat 90 -> raw 0 -> 0*2+10 = 10
        assertEquals(10.0, d.sample("height", -180.0, 90.0), 1e-6);
        Files.deleteIfExists(tmp);
    }

    /** The roll must place the North Pole face center at lat 90 and the
     * EQ_PRIME face center at lon = roll (=-70), lat 0. */
    @Test
    void orientationMatchesRoll() throws IOException {
        EarthData d = loadReal();
        Assumptions.assumeTrue(d != null, "real earth.dat not present");
        double[] npole = d.toLonLat(new Vec3(0, 1, 0));   // NORTH_POLE center
        assertEquals(90.0, npole[1], 0.01);
        double[] prime = d.toLonLat(new Vec3(0, 0, 1));    // EQ_PRIME center (+Z)
        assertEquals(0.0, prime[1], 0.01);
        assertEquals(-70.0, prime[0], 0.01);
    }

    /** Sanity-check the real raster content: Sahara hot & dry, deep Pacific deep. */
    @Test
    void realRasterContentIsPlausible() throws IOException {
        EarthData d = loadReal();
        Assumptions.assumeTrue(d != null, "real earth.dat not present");
        // Sahara ~ (25E, 23N)
        assertTrue(d.sample("temp", 25.0, 23.0) > 20.0, "Sahara should be hot");
        assertTrue(d.sample("precip", 25.0, 23.0) < 200.0, "Sahara should be dry");
        // deep Pacific ~ (-150, 0)
        assertTrue(d.sample("height", -150.0, 0.0) < -3000.0, "deep ocean should be deep");
        // Amazon ~ (-62, -3): wet
        assertTrue(d.sample("precip", -62.0, -3.0) > 1500.0, "Amazon should be wet");
    }

    private static EarthData loadReal() throws IOException {
        for (String p : new String[] {"tools/cubemap/out/earth.dat", "run/earth.dat"}) {
            Path path = Path.of(p);
            if (Files.exists(path)) {
                return EarthData.load(path);
            }
        }
        return null;
    }
}
