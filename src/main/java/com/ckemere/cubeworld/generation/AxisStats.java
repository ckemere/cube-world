package com.ckemere.cubeworld.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Measures the DISTRIBUTION of the six climate axes, for either vanilla's own
 * noise or our Earth-derived fields, on the same grid and in the same format so
 * the two can be compared directly.
 *
 * <p>This exists because of the calibration problem behind the generator
 * rewrite: vanilla's {@code offset}/{@code factor}/{@code jaggedness} splines
 * have knots at very specific axis values (factor keys on continentalness at
 * -0.19/-0.15/-0.1/0.03/0.06 -- a narrow band around zero; jaggedness is
 * identically 0 for continentalness <= -0.11), and the biome table's boxes
 * likewise assume vanilla's spread. Feeding those splines a field with a
 * different distribution parks every sample on one spline segment. That is the
 * same failure already measured as {@code windswept_savanna} at 17% of land,
 * one level further down.
 *
 * <p>So the target is not "our axes should look physical", it is "our axes
 * should have vanilla's DISTRIBUTION while keeping Earth's ARRANGEMENT". This
 * command produces the left-hand side of that comparison; {@code earth} mode
 * produces the right-hand side.
 *
 * <p>Vanilla mode builds a FRESH {@link RandomState} from the overworld preset
 * rather than reading the live one, because the live router has already been
 * folded onto the cube by {@link SphereRouterHook} and its leaves replaced.
 * {@link RandomState#sampler()} is the flattened (uncached) sampler, so it is
 * safe to evaluate at arbitrary positions outside chunk generation.
 */
public final class AxisStats {

    private AxisStats() {
    }

    /** Vanilla's own axis distribution, from a clean unfolded RandomState. */
    public static String vanilla(World world, int samplesPerSide, int strideBlocks) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        RandomState rs;
        try {
            rs = RandomState.create(
                    level.registryAccess()
                            .lookupOrThrow(Registries.NOISE_SETTINGS)
                            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value(),
                    level.registryAccess().lookupOrThrow(Registries.NOISE),
                    world.getSeed());
        } catch (Throwable t) {
            return "axisstats vanilla: could not build RandomState (" + t + ")";
        }
        Climate.Sampler s = rs.sampler();

        int n = samplesPerSide * samplesPerSide;
        double[] temp = new double[n];
        double[] hum = new double[n];
        double[] cont = new double[n];
        double[] eros = new double[n];
        double[] weird = new double[n];
        int i = 0;
        // Quart coordinates: the sampler takes quarts, and stride is in blocks.
        int quartStride = Math.max(1, strideBlocks / 4);
        for (int a = 0; a < samplesPerSide; a++) {
            for (int b = 0; b < samplesPerSide; b++) {
                Climate.TargetPoint p = s.sample(a * quartStride, 0, b * quartStride);
                temp[i] = Climate.unquantizeCoord(p.temperature());
                hum[i] = Climate.unquantizeCoord(p.humidity());
                cont[i] = Climate.unquantizeCoord(p.continentalness());
                eros[i] = Climate.unquantizeCoord(p.erosion());
                weird[i] = Climate.unquantizeCoord(p.weirdness());
                i++;
            }
        }
        return render("VANILLA noise (fresh RandomState, seed " + world.getSeed() + ")",
                n, temp, hum, cont, eros, weird);
    }

    /** Our Earth-derived axis distribution, on the same kind of grid. */
    public static String earth(EarthData earthData, MapSampler sampler, long seed,
                               int samplesPerSide, int strideBlocks) {
        if (earthData == null) {
            return "axisstats earth: no Earth data loaded.";
        }
        List<double[]> rows = new ArrayList<>();
        // Walk a grid centred on the origin so we cover several cube faces
        // rather than a single region.
        int half = samplesPerSide / 2;
        for (int a = -half; a < half; a++) {
            for (int b = -half; b < half; b++) {
                double wx = a * (double) strideBlocks + 0.5;
                double wz = b * (double) strideBlocks + 0.5;
                double surfaceY = sampler.heightAt(wx, wz);
                double[] c = EarthClimate.params(earthData, sampler, wx, wz,
                        (int) Math.round(surfaceY), seed);
                if (c != null) {
                    rows.add(c);
                }
            }
        }
        int n = rows.size();
        if (n == 0) {
            return "axisstats earth: no samples on the net.";
        }
        double[] temp = new double[n];
        double[] hum = new double[n];
        double[] cont = new double[n];
        double[] eros = new double[n];
        double[] weird = new double[n];
        double[] relief = new double[n];
        double[] elevs = new double[n];
        for (int i = 0; i < n; i++) {
            double[] c = rows.get(i);
            temp[i] = c[0];
            hum[i] = c[1];
            cont[i] = c[2];
            eros[i] = c[3];
            weird[i] = c[5];
            relief[i] = c[9];
            elevs[i] = c[6];
        }
        String extra = percentileLine("relief(m)", relief)
                + percentileLine("elevation(m)", elevs)
                + landOnly("relief(m) LAND", relief, elevs)
                + landOnly("elevation LAND", elevs, elevs);
        return render("EARTH fields (EarthClimate.params at the surface)",
                n, temp, hum, cont, eros, weird) + extra;
    }

    // ---- reporting -------------------------------------------------------

    /** Vanilla's own band edges, from OverworldBiomeBuilder. */
    private static final double[] T_EDGES = {-1.0, -0.45, -0.15, 0.2, 0.55, 1.0};
    private static final double[] H_EDGES = {-1.0, -0.35, -0.1, 0.1, 0.3, 1.0};
    private static final double[] E_EDGES = {-1.0, -0.78, -0.375, -0.2225, 0.05, 0.45, 0.55, 1.0};
    private static final double[] C_EDGES = {-1.2, -1.05, -0.455, -0.19, -0.11, 0.03, 0.3, 1.0};
    private static final String[] C_NAMES = {
        "mushroom", "deep_ocean", "ocean", "coast", "near_inland", "mid_inland", "far_inland"};

    private static String render(String title, int n, double[] t, double[] h,
                                 double[] c, double[] e, double[] w) {
        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(title).append("  n=").append(n).append(" ==\n");
        sb.append(percentileLine("temperature", t));
        sb.append(percentileLine("humidity", h));
        sb.append(percentileLine("continentalness", c));
        sb.append(percentileLine("erosion", e));
        sb.append(percentileLine("weirdness", w));
        sb.append("-- band occupancy (%) --\n");
        sb.append(bandLine("T rows", t, T_EDGES, null));
        sb.append(bandLine("H cols", h, H_EDGES, null));
        sb.append(bandLine("E bands", e, E_EDGES, null));
        sb.append(bandLine("C bands", c, C_EDGES, C_NAMES));
        return sb.toString();
    }

    private static String percentileLine(String name, double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        // Quantiles, not just a spread: building a quantile MAP from our fields
        // onto vanilla's distribution needs matched knots at both ends.
        return String.format(Locale.ROOT,
                "%-16s p01 %7.2f p05 %7.2f p10 %7.2f p25 %7.2f p50 %7.2f "
                + "p75 %7.2f p90 %7.2f p95 %7.2f p99 %7.2f%n",
                name, pct(s, 1), pct(s, 5), pct(s, 10), pct(s, 25), pct(s, 50),
                pct(s, 75), pct(s, 90), pct(s, 95), pct(s, 99));
    }

    /** Same percentile line but restricted to columns whose elevation >= 0. */
    private static String landOnly(String name, double[] v, double[] elev) {
        int m = 0;
        for (double e : elev) {
            if (e >= 0) {
                m++;
            }
        }
        if (m == 0) {
            return "";
        }
        double[] out = new double[m];
        int j = 0;
        for (int i = 0; i < v.length; i++) {
            if (elev[i] >= 0) {
                out[j++] = v[i];
            }
        }
        return percentileLine(name, out);
    }

    private static double pct(double[] sorted, double p) {
        int i = (int) Math.round((p / 100.0) * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static String bandLine(String name, double[] v, double[] edges, String[] names) {
        int[] counts = new int[edges.length - 1];
        for (double x : v) {
            for (int i = 0; i < counts.length; i++) {
                // last band is inclusive at the top
                if (x >= edges[i] && (x < edges[i + 1] || i == counts.length - 1)) {
                    counts[i]++;
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%-10s", name));
        for (int i = 0; i < counts.length; i++) {
            String label = names != null ? names[i] : String.valueOf(i);
            sb.append(String.format(Locale.ROOT, " %s=%.1f", label, 100.0 * counts[i] / v.length));
        }
        return sb.append('\n').toString();
    }
}
