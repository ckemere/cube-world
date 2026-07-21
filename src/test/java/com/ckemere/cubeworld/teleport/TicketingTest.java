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
}
