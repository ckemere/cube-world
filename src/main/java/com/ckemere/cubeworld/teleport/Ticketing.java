package com.ckemere.cubeworld.teleport;

import java.util.Locale;
import java.util.Set;

/**
 * Ticket codes: a memorable four-word address per station —
 * {@code number color adjective noun}, e.g. "seven crimson swift otter". Codes
 * are generated deterministically from a seed (with collision re-rolls handled
 * by the caller) and parsed case-insensitively out of arbitrary book text, so a
 * player who learns the words can write their own ticket.
 *
 * <p>Pure logic — no Bukkit — so it is unit-tested directly.
 */
public final class Ticketing {

    static final String[] NUMBERS = {
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve"};
    static final String[] COLORS = {
        "red", "orange", "amber", "gold", "green", "teal", "cyan", "blue",
        "indigo", "violet", "purple", "magenta", "crimson", "scarlet", "silver", "azure"};
    static final String[] ADJECTIVES = {
        "swift", "quiet", "ancient", "hidden", "bright", "silent", "golden", "frozen",
        "burning", "gentle", "restless", "wandering", "sacred", "lonely", "distant",
        "sleeping", "roaring", "whispering", "shining", "fading", "crooked", "twisted",
        "humble", "noble", "weary", "bold", "fierce", "calm", "merry", "solemn", "wild", "grand"};
    static final String[] NOUNS = {
        "otter", "spire", "harbor", "ember", "willow", "raven", "falcon", "anvil",
        "lantern", "meadow", "grotto", "cairn", "beacon", "thicket", "hollow", "bramble",
        "marsh", "fjord", "dune", "reef", "gully", "basin", "ridge", "glade", "brook",
        "cove", "crag", "delta", "fen", "heath", "moor", "vale"};

    private static final Set<String> NUM = Set.of(NUMBERS);
    private static final Set<String> COL = Set.of(COLORS);
    private static final Set<String> ADJ = Set.of(ADJECTIVES);
    private static final Set<String> NOU = Set.of(NOUNS);

    /** total distinct codes — for tests / sanity. */
    public static final long SPACE =
            (long) NUMBERS.length * COLORS.length * ADJECTIVES.length * NOUNS.length;

    private Ticketing() {
    }

    /** A deterministic four-word code for a seed. */
    public static String codeFor(long seed) {
        long h = mix(seed);
        String n = NUMBERS[(int) Long.remainderUnsigned(h, NUMBERS.length)];
        h = Long.divideUnsigned(h, NUMBERS.length);
        String c = COLORS[(int) Long.remainderUnsigned(h, COLORS.length)];
        h = Long.divideUnsigned(h, COLORS.length);
        String a = ADJECTIVES[(int) Long.remainderUnsigned(h, ADJECTIVES.length)];
        h = Long.divideUnsigned(h, ADJECTIVES.length);
        String o = NOUNS[(int) Long.remainderUnsigned(h, NOUNS.length)];
        return n + " " + c + " " + a + " " + o;
    }

    /**
     * Find a valid {@code number color adjective noun} run in arbitrary text
     * (case-insensitive, punctuation-tolerant), and return it normalized
     * (lowercase, single-spaced), or {@code null} if none is present.
     */
    public static String parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] w = text.toLowerCase(Locale.ROOT).split("[^a-z]+");
        for (int i = 0; i + 3 < w.length; i++) {
            if (NUM.contains(w[i]) && COL.contains(w[i + 1])
                    && ADJ.contains(w[i + 2]) && NOU.contains(w[i + 3])) {
                return w[i] + " " + w[i + 1] + " " + w[i + 2] + " " + w[i + 3];
            }
        }
        return null;
    }

    /** Whether a string is a well-formed code (four words, right categories). */
    public static boolean isValid(String code) {
        return code != null && code.equals(parse(code));
    }

    /**
     * The mixed-radix index of a canonical code in [0, {@link #SPACE}), or -1
     * if the string is not a canonical code. Inverse of the digit order
     * {@link #codeFor} emits, so every code has a stable numeric identity.
     */
    public static long indexOf(String canonicalCode) {
        if (canonicalCode == null) {
            return -1;
        }
        String[] w = canonicalCode.split(" ");
        if (w.length != 4) {
            return -1;
        }
        int n = idx(NUMBERS, w[0]);
        int c = idx(COLORS, w[1]);
        int a = idx(ADJECTIVES, w[2]);
        int o = idx(NOUNS, w[3]);
        if (n < 0 || c < 0 || a < 0 || o < 0) {
            return -1;
        }
        return ((((long) o * ADJECTIVES.length + a) * COLORS.length + c) * NUMBERS.length) + n;
    }

    private static int idx(String[] words, String w) {
        for (int i = 0; i < words.length; i++) {
            if (words[i].equals(w)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The deterministic "uncharted" destination for a code that matches no
     * station: world (x, z) on a valid cube face, inset 128 blocks from every
     * face edge (clear of seam margins and corner pillars). Same code + same
     * world seed → same destination, forever. Pure math so it is unit-testable;
     * the caller resolves the surface Y at travel time.
     */
    public static int[] unchartedXZ(String canonicalCode, long worldSeed, int faceSize) {
        long h = mix(indexOf(canonicalCode) ^ worldSeed);
        com.ckemere.cubeworld.geometry.CubeFace face =
                com.ckemere.cubeworld.geometry.CubeFace.values()[
                        (int) Long.remainderUnsigned(h, 6)];
        long hx = mix(h + 0x9E3779B97F4A7C15L);
        long hz = mix(hx + 0x9E3779B97F4A7C15L);
        int inset = 128;
        int span = faceSize - 2 * inset;
        int x = face.gridCol() * faceSize - faceSize / 2 + inset
                + (int) Long.remainderUnsigned(hx, span);
        int z = face.gridRow() * faceSize - faceSize / 2 + inset
                + (int) Long.remainderUnsigned(hz, span);
        return new int[] {x, z};
    }

    // SplitMix64 — good avalanche so adjacent seeds give unrelated codes.
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
