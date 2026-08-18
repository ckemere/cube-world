package com.ckemere.cubeworld.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TicketingTest {

    @Test
    void codeIsFourValidWordsAndDeterministic() {
        String code = Ticketing.codeFor(12345L);
        assertEquals(4, code.split(" ").length);
        assertTrue(Ticketing.isValid(code), code);
        assertEquals(code, Ticketing.codeFor(12345L));     // deterministic
    }

    @Test
    void parseIsCaseInsensitiveAndPunctuationTolerant() {
        String code = Ticketing.codeFor(999L);              // e.g. "two azure swift otter"
        String[] w = code.split(" ");
        String messy = "Ticket to: " + w[0].toUpperCase() + " " + w[1] + "  "
                + w[2].substring(0, 1).toUpperCase() + w[2].substring(1) + " " + w[3] + "! keep safe";
        assertEquals(code, Ticketing.parse(messy));
    }

    @Test
    void parseRejectsNonCodes() {
        assertNull(Ticketing.parse("just some random words here nothing"));
        assertNull(Ticketing.parse(""));
        assertNull(Ticketing.parse(null));
        assertFalse(Ticketing.isValid("crimson seven otter swift"));  // wrong order
    }

    @Test
    void codesAreWellSpreadAcrossSeeds() {
        // 2000 sequential seeds should yield mostly-distinct codes (good avalanche)
        Set<String> codes = new HashSet<>();
        for (long s = 0; s < 2000; s++) {
            codes.add(Ticketing.codeFor(s));
        }
        assertTrue(codes.size() > 1980, "too many collisions: " + codes.size());
        assertTrue(Ticketing.SPACE > 150_000, "code space too small");
    }

    @Test
    void indexOfRoundTripsAndRejectsGarbage() {
        for (long seed : new long[] {0L, 1L, 42L, -7L, Long.MAX_VALUE}) {
            String code = Ticketing.codeFor(seed);
            long idx = Ticketing.indexOf(code);
            assertTrue(idx >= 0 && idx < Ticketing.SPACE, code + " -> " + idx);
            assertEquals(idx, Ticketing.indexOf(code));    // deterministic
        }
        assertEquals(-1, Ticketing.indexOf(null));
        assertEquals(-1, Ticketing.indexOf("not a code at all"));
        assertEquals(-1, Ticketing.indexOf("crimson seven otter swift"));
    }

    @Test
    void unchartedIsDeterministicOnNetAndSeedSensitive() {
        int faceSize = 10240;
        int half = faceSize / 2;
        int inset = 128;
        Set<Long> spread = new HashSet<>();
        for (long i = 0; i < 200; i++) {
            String code = Ticketing.codeFor(i);
            int[] a = Ticketing.unchartedXZ(code, 8675309L, faceSize);
            int[] b = Ticketing.unchartedXZ(code, 8675309L, faceSize);
            assertEquals(a[0], b[0]);                      // same code+seed -> same place
            assertEquals(a[1], b[1]);
            // on a valid face cell of the cross net, inset from every edge
            int col = Math.floorDiv(a[0] + half, faceSize);
            int row = Math.floorDiv(a[1] + half, faceSize);
            boolean onNet = (row == 0 && col >= -1 && col <= 2) || (col == 0 && (row == -1 || row == 1));
            assertTrue(onNet, a[0] + "," + a[1]);
            int lx = a[0] - (col * faceSize - half);
            int lz = a[1] - (row * faceSize - half);
            assertTrue(lx >= inset && lx < faceSize - inset, "lx " + lx);
            assertTrue(lz >= inset && lz < faceSize - inset, "lz " + lz);
            spread.add(((long) a[0] << 32) ^ (a[1] & 0xffffffffL));
            // a different world seed sends the same code somewhere else (almost surely)
            int[] c = Ticketing.unchartedXZ(code, 1L, faceSize);
            assertTrue(c[0] != a[0] || c[1] != a[1] || i > 0);
        }
        assertTrue(spread.size() > 190, "poor spread: " + spread.size());
    }
}
