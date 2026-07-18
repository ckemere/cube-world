package com.ckemere.cubeworld.generation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real mountain summits (>= 6000 m, from OpenStreetMap {@code natural=peak}
 * nodes, filtered against ETOPO). The coarse elevation raster averages sharp
 * summits down — Everest reads as ~5000 m, not 8849 — so terrain never reaches
 * the true peak heights. This restores them: at any (lon, lat) it returns the
 * highest cone rising from a nearby summit, which the caller maxes against the
 * base raster elevation. Because the cone drops below the raster within a few
 * km, summits are added on top of the range without filling the valleys.
 */
public final class PeakField {

    /** Cone radius: the summit's influence tapers linearly to 0 over this. */
    private static final double RADIUS_KM = 28.0;
    private static final double EARTH_R_KM = 6371.0;

    /** peaks binned by whole (lon, lat) degree: key -> list of {lon, lat, ele}. */
    private final Map<Long, List<double[]>> bins = new HashMap<>();

    private static volatile PeakField instance;

    public static PeakField get() {
        PeakField p = instance;
        if (p == null) {
            synchronized (PeakField.class) {
                p = instance;
                if (p == null) {
                    p = load();
                    instance = p;
                }
            }
        }
        return p;
    }

    private static PeakField load() {
        PeakField field = new PeakField();
        int n = 0;
        try (InputStream in = PeakField.class.getResourceAsStream("/peaks6000.csv")) {
            if (in == null) {
                return field;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = r.readLine(); // header: lat,lon,ele_m,name
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",", 4);
                if (f.length < 3) {
                    continue;
                }
                try {
                    double lat = Double.parseDouble(f[0]);
                    double lon = Double.parseDouble(f[1]);
                    double ele = Double.parseDouble(f[2]);
                    field.bins.computeIfAbsent(key((int) Math.floor(lon), (int) Math.floor(lat)),
                            k -> new ArrayList<>()).add(new double[] {lon, lat, ele});
                    n++;
                } catch (NumberFormatException ignore) {
                    // skip malformed row
                }
            }
        } catch (Exception e) {
            // no peaks -> field stays empty, terrain uses the raster only
        }
        field.count = n;
        return field;
    }

    private int count;

    public int count() {
        return count;
    }

    private static long key(int lonBin, int latBin) {
        return ((long) (lonBin + 180) << 16) | (latBin + 90);
    }

    /**
     * Highest summit-cone elevation (metres) at this position, or 0 if no
     * >=6000 m peak is within {@link #RADIUS_KM}. Continuous, so a summit is
     * reached exactly at its own coordinate (no cell averaging).
     */
    public double coneElevation(double lon, double lat) {
        int lb = (int) Math.floor(lon);
        int tb = (int) Math.floor(lat);
        double best = 0.0;
        for (int dl = -1; dl <= 1; dl++) {
            for (int dt = -1; dt <= 1; dt++) {
                List<double[]> list = bins.get(key(lb + dl, tb + dt));
                if (list == null) {
                    continue;
                }
                for (double[] p : list) {
                    double d = haversineKm(lon, lat, p[0], p[1]);
                    if (d < RADIUS_KM) {
                        double cone = p[2] * (1.0 - d / RADIUS_KM);
                        if (cone > best) {
                            best = cone;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static double haversineKm(double lon1, double lat1, double lon2, double lat2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_R_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
