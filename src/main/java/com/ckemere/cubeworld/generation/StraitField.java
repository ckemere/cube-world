package com.ckemere.cubeworld.generation;

/**
 * Curated sea straits and ship canals that the real Earth needs but the coarse
 * elevation raster (or a shallow sill) would otherwise leave as a dry land
 * bridge: Bering, Gibraltar, Bab-el-Mandeb, Bosphorus/Dardanelles, Hormuz,
 * Malacca, the Danish straits, the Dover Strait, and the Panama/Suez canals.
 *
 * <p>Each strait is one or more geodesic-ish segments in (lon, lat). A world
 * column's strait membership is a pure function of its geographic position
 * (which itself is a continuous function of the cube point), so straits are
 * seam-consistent by construction. Where a column falls inside a corridor the
 * generator cuts a flat sea-level channel with a solid bed, connecting the seas
 * on either side into one navigable waterway.
 */
public final class StraitField {

    /** Blocks per degree of latitude (equator: 4 faces * faceSize / 360). */
    private static final double DEG2BLK = (4.0 * (640 * 16)) / 360.0;   // ~113.8

    /** A straight corridor in lon/lat with a half-width and channel depth. */
    private record Seg(double lon0, double lat0, double lon1, double lat1,
                       double halfWidthBlocks, int depthBlocks) {
    }

    /** Result of a hit: the channel bed depth (blocks below sea) at this column. */
    public record Hit(int depthBlocks) {
    }

    // Endpoints sit in open water on each side so the cut always reaches sea;
    // half-widths give navigable channels (canals narrower than natural straits).
    private static final Seg[] SEGMENTS = {
        // Bering Strait (Pacific <-> Arctic), broad and shallow land bridge.
        new Seg(-172.0, 67.0, -166.0, 63.5, 40, 8),
        // Gibraltar (Atlantic <-> Mediterranean).
        new Seg(-6.1, 36.05, -5.2, 35.85, 22, 10),
        // Bab-el-Mandeb (Red Sea <-> Gulf of Aden).
        new Seg(43.1, 13.0, 43.5, 12.3, 22, 9),
        // Bosphorus (Black Sea <-> Sea of Marmara).
        new Seg(28.95, 41.25, 29.15, 40.98, 12, 8),
        // Dardanelles (Marmara <-> Aegean).
        new Seg(26.15, 40.25, 26.75, 40.0, 12, 8),
        // Strait of Hormuz (Persian Gulf <-> Gulf of Oman).
        new Seg(55.9, 26.7, 56.7, 26.2, 26, 9),
        // Strait of Malacca (Andaman Sea <-> South China Sea), two segments.
        new Seg(98.5, 5.5, 101.5, 2.8, 40, 9),
        new Seg(101.5, 2.8, 104.2, 1.2, 40, 9),
        // Danish straits (North Sea/Kattegat <-> Baltic): Great Belt + Oresund.
        new Seg(10.9, 55.4, 11.0, 54.7, 12, 7),
        new Seg(12.55, 56.1, 12.7, 55.5, 10, 7),
        // Dover Strait / English Channel narrows (North Sea <-> Atlantic).
        new Seg(1.3, 51.05, 2.0, 50.85, 16, 7),
        // Panama Canal (Caribbean <-> Pacific), a cut isthmus.
        new Seg(-79.95, 9.4, -79.45, 8.85, 8, 8),
        // Suez Canal (Mediterranean <-> Red Sea), a cut isthmus.
        new Seg(32.3, 31.3, 32.55, 29.85, 8, 8),
    };

    private StraitField() {
    }

    /**
     * Return the channel spec if (lon, lat) lies inside any strait corridor,
     * else null. The corridor is a fixed-width band around each segment; the
     * deeper of two overlapping corridors wins.
     */
    public static Hit sample(double lon, double lat) {
        int best = 0;
        for (Seg s : SEGMENTS) {
            double midLat = 0.5 * (s.lat0 + s.lat1);
            double cosLat = Math.cos(Math.toRadians(midLat));
            // Local planar coords in blocks (lon scaled by cos(lat)).
            double ax = wrapLon(s.lon0 - lon) * cosLat * DEG2BLK;
            double ay = (s.lat0 - lat) * DEG2BLK;
            double bx = wrapLon(s.lon1 - lon) * cosLat * DEG2BLK;
            double by = (s.lat1 - lat) * DEG2BLK;
            double dist = pointToSeg(0, 0, ax, ay, bx, by);
            if (dist <= s.halfWidthBlocks && s.depthBlocks > best) {
                best = s.depthBlocks;
            }
        }
        return best > 0 ? new Hit(best) : null;
    }

    private static double wrapLon(double d) {
        while (d > 180) {
            d -= 360;
        }
        while (d < -180) {
            d += 360;
        }
        return d;
    }

    /** Distance from point p to segment a-b in the local planar block metric. */
    private static double pointToSeg(double px, double py,
                                     double ax, double ay, double bx, double by) {
        double vx = bx - ax;
        double vy = by - ay;
        double wx = px - ax;
        double wy = py - ay;
        double len2 = vx * vx + vy * vy;
        double t = len2 <= 1e-9 ? 0.0 : (wx * vx + wy * vy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + t * vx;
        double cy = ay + t * vy;
        double dx = px - cx;
        double dy = py - cy;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
