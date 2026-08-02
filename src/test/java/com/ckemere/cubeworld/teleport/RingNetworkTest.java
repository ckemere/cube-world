package com.ckemere.cubeworld.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RingNetworkTest {

    @Test
    void successorsAreCyclicAndPerRing() {
        RingNetwork n = new RingNetwork();
        n.insert("x", 0, 0);
        n.insert("y", 1, 1);          // A: [x,y]    B: [x,y]
        n.insert("z", 2, 1);          // A: [x,y,z]  B: [x,z,y]
        assertEquals("y", n.successorA("x"));
        assertEquals("z", n.successorA("y"));
        assertEquals("x", n.successorA("z"));   // wraps
        assertEquals("z", n.successorB("x"));
        assertEquals("y", n.successorB("z"));
        assertEquals("x", n.successorB("y"));   // wraps
        // the two rings give different next-hops (independent routes)
        assertNotEquals(n.successorA("x"), n.successorB("x"));
    }

    @Test
    void singleOrEmptyHasNoSuccessor() {
        RingNetwork n = new RingNetwork();
        assertNull(n.successorA("nobody"));
        n.insert("solo", 0, 0);
        assertNull(n.successorA("solo"));       // can't travel to itself
        assertNull(n.successorB("solo"));
    }

    @Test
    void removeSplicesBothRings() {
        RingNetwork n = new RingNetwork();
        n.insert("a", 0, 0);
        n.insert("b", 1, 1);
        n.insert("c", 2, 2);
        n.remove("b");
        assertFalse(n.contains("b"));
        assertEquals(2, n.size());
        assertEquals("c", n.successorA("a"));   // b spliced out
        assertNull(n.successorA("b"));
    }

    @Test
    void randomInsertKeepsAllAndStaysDistinctPerRing() {
        RingNetwork n = new RingNetwork();
        Random r = new Random(42);
        for (int i = 0; i < 30; i++) {
            n.insertRandom("s" + i, r);
        }
        assertEquals(30, n.size());
        // the two rings are (very likely) different orderings
        assertNotEquals(n.ringA(), n.ringB());
        List<String> ra = n.ringA();
        assertEquals(30, ra.size());
        assertEquals(30, ra.stream().distinct().count());
    }

    @Test
    void twoDestinationsAreAlwaysDistinctForThreePlus() {
        RingNetwork n = new RingNetwork();
        Random r = new Random(7);
        for (int i = 0; i < 40; i++) {
            n.insertRandom("s" + i, r);
        }
        for (int i = 0; i < 40; i++) {
            String k = "s" + i;
            List<String> d = n.twoDestinations(k);
            assertEquals(2, d.size(), "not two for " + k);
            assertNotEquals(d.get(0), d.get(1), "coincided for " + k);
            assertNotEquals(k, d.get(0));
            assertNotEquals(k, d.get(1));
        }
    }

    @Test
    void twoDestinationsDegradesGracefullyWhenSmall() {
        RingNetwork n = new RingNetwork();
        assertEquals(0, n.twoDestinations("nobody").size());
        n.insert("a", 0, 0);
        assertEquals(0, n.twoDestinations("a").size());        // only itself
        n.insert("b", 1, 1);
        assertEquals(1, n.twoDestinations("a").size());        // one other station
    }
}
