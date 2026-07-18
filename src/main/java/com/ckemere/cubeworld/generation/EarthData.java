package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.Vec3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads the CWE1 Earth-raster bundle exported by the {@code cubemap} tool and
 * samples it through the same cube-surface orientation the preview uses:
 * cube-surface point -&gt; (roll rotation) -&gt; lon/lat -&gt; bilinear raster lookup.
 * This is the bridge from the Python ingest to the Java generator; the height
 * layer drives terrain, temp/precip drive biome climate.
 *
 * <p>The rotation mirrors {@code cubemap.geometry.dir_to_lonlat} with tilt 0:
 * a rotation about the polar (Y) axis by {@code roll} degrees.
 */
public final class EarthData {

    /** Raw value {@code == NODATA} means "no data" (ocean cells in climate layers). */
    public static final short NODATA = -32768;

    public static final class Layer {
        final short[] data;
        final int width;
        final int height;
        final double scale;
        final double offset;

        Layer(short[] data, int width, int height, double scale, double offset) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.offset = offset;
        }
    }

    private final double roll;
    private final double cosRoll;
    private final double sinRoll;
    private final Map<String, Layer> layers;

    private EarthData(double roll, Map<String, Layer> layers) {
        this.roll = roll;
        this.cosRoll = Math.cos(Math.toRadians(roll));
        this.sinRoll = Math.sin(Math.toRadians(roll));
        this.layers = layers;
    }

    public double roll() {
        return roll;
    }

    public boolean hasLayer(String name) {
        return layers.containsKey(name);
    }

    public static EarthData load(Path path) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buf.get(magic);
        if (magic[0] != 'C' || magic[1] != 'W' || magic[2] != 'E' || magic[3] != '1') {
            throw new IOException("not a CWE1 file: " + path);
        }
        double roll = buf.getFloat();
        int nLayers = buf.getInt();
        // headers
        String[] names = new String[nLayers];
        int[] ws = new int[nLayers];
        int[] hs = new int[nLayers];
        double[] scales = new double[nLayers];
        double[] offsets = new double[nLayers];
        for (int i = 0; i < nLayers; i++) {
            byte[] nm = new byte[8];
            buf.get(nm);
            int len = 0;
            while (len < 8 && nm[len] != 0) {
                len++;
            }
            names[i] = new String(nm, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
            ws[i] = buf.getInt();
            hs[i] = buf.getInt();
            scales[i] = buf.getFloat();
            offsets[i] = buf.getFloat();
        }
        Map<String, Layer> layers = new LinkedHashMap<>();
        for (int i = 0; i < nLayers; i++) {
            int n = ws[i] * hs[i];
            short[] data = new short[n];
            buf.asShortBuffer().get(data);           // bulk copy this layer's block
            buf.position(buf.position() + n * 2);    // advance past it
            layers.put(names[i], new Layer(data, ws[i], hs[i], scales[i], offsets[i]));
        }
        return new EarthData(roll, layers);
    }

    /** Cube-surface point -&gt; geographic (lon, lat) in degrees, applying the roll. */
    public double[] toLonLat(Vec3 p) {
        double n = Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z());
        double x = p.x() / n, y = p.y() / n, z = p.z() / n;
        double gx = cosRoll * x + sinRoll * z;
        double gz = -sinRoll * x + cosRoll * z;
        double lat = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, y))));
        double lon = Math.toDegrees(Math.atan2(gx, gz));
        return new double[] {lon, lat};
    }

    /** Bilinear sample of a layer at lon/lat (degrees). NaN if the layer is
     * missing or the four corners are all nodata. */
    public double sample(String layer, double lon, double lat) {
        Layer l = layers.get(layer);
        if (l == null) {
            return Double.NaN;
        }
        double fx = (lon + 180.0) / 360.0 * l.width;
        double fy = (90.0 - lat) / 180.0 * l.height;
        if (fx < 0) {
            fx += l.width;
        }
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        double tx = fx - x0;
        double ty = fy - y0;
        x0 = Math.floorMod(x0, l.width);
        int x1 = (x0 + 1) % l.width;
        y0 = Math.max(0, Math.min(l.height - 1, y0));
        int y1 = Math.min(l.height - 1, y0 + 1);
        double v00 = raw(l, x0, y0), v10 = raw(l, x1, y0);
        double v01 = raw(l, x0, y1), v11 = raw(l, x1, y1);
        // if any corner is nodata, fall back to nearest valid among the four
        if (Double.isNaN(v00) || Double.isNaN(v10) || Double.isNaN(v01) || Double.isNaN(v11)) {
            double best = Double.NaN;
            for (double v : new double[] {v00, v10, v01, v11}) {
                if (!Double.isNaN(v)) {
                    best = v;
                    break;
                }
            }
            return best;
        }
        return (v00 * (1 - tx) * (1 - ty) + v10 * tx * (1 - ty)
                + v01 * (1 - tx) * ty + v11 * tx * ty) * l.scale + l.offset;
    }

    private static double raw(Layer l, int x, int y) {
        short s = l.data[y * l.width + x];
        return s == NODATA ? Double.NaN : s;
    }

    /** Convenience: sample a layer at a cube-surface point. */
    public double at(String layer, Vec3 cubePoint) {
        double[] ll = toLonLat(cubePoint);
        return sample(layer, ll[0], ll[1]);
    }
}
