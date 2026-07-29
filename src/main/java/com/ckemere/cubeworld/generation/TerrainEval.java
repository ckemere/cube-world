package com.ckemere.cubeworld.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Scored evaluation of the generator over a fixed suite of small tiles, so a
 * change can be shown to be an improvement rather than asserted to be one.
 *
 * <p>Every number here is cheap enough to sit inside a parameter sweep: the
 * expensive part is finding the generated surface, which needs the density
 * field, so that is bounded by searching a window around the elevation the
 * raster asks for rather than scanning the whole column.
 *
 * <p>VALIDATED against real generated chunks (Sahara, 151 columns, region files
 * read directly): emulated minus real is mean +0.24, median +0.38, sd 1.90
 * blocks; 89% of columns agree within 1 block and 96% within 2. So terrain RMSE
 * has a ~2-block noise floor and the drown rate has a few-percent outlier tail
 * -- differences smaller than that between two runs are not real. Biome metrics
 * do not go through this path and carry no such caveat.
 *
 * <p>Deliberately NOT chunk generation. Generating real chunks would be the
 * ground truth, but at ~1 s per chunk a single scorecard would take an hour and
 * no sweep would be possible. Instead the surface is located by evaluating the
 * live folded {@code finalDensity} directly -- the same function chunk gen
 * integrates -- which is exact up to the 4x8x4 cell interpolation that chunk
 * gen applies afterwards. Treat absolute RMSE as approximate; treat DIFFERENCES
 * between runs as real, since the bias is identical across runs.
 */
public final class TerrainEval {

    private TerrainEval() {
    }

    // ---- the tile suite --------------------------------------------------

    /**
     * A named place with what we expect to find there. Coordinates were resolved
     * with {@code /cubeworld findlatlon} against the locked -70 deg roll, so they
     * move if the roll ever changes.
     */
    record Tile(String name, int x, int z, int strideDiv, boolean land, String... expect) {
    }

    /**
     * Tiles carry their own SCALE (strideDiv divides the global stride), because
     * a fixed stride tests the wrong thing at different tiles. At stride 24 the
     * Everest tile spans ~288 km -- Tibet, the Himalaya AND the Nepal Terai --
     * where meadow, grove and jungle are all genuinely correct, so it scored
     * 64.6% while the massif itself measured 91.7% frozen_peaks. That was the
     * metric being coarse, not the generator being wrong.
     */
    // "river"/"frozen_river" is an accepted answer on every land tile. When these
    // expectations were first written the world had NO rivers at all (earth.dat's
    // river layer was empty), so their arrival read as a 5-point regression. Every
    // land tile here sits in a real basin -- Niger, Amazon, Missouri, Mackenzie,
    // Tigris/Euphrates, Rhine -- so river is the correct biome, not a miss.
    private static final Tile[] TILES = {
        // land: the regions whose biomes we have repeatedly got wrong
        new Tile("Sahara",       9611, -2189, 1, true,  "desert", "badlands", "river", "frozen_river"),
        new Tile("Sahel",        8576, -1342, 1, true,  "savanna", "desert", "plains", "river", "frozen_river"),
        // tighter: at full stride this reaches the delta, where mangrove is right
        new Tile("Amazon",        903,   273, 2, true,  "jungle", "forest", "river", "frozen_river"),
        new Tile("Congo",       10419,     0, 1, true,  "jungle", "forest", "river", "frozen_river"),
        new Tile("GreatPlains", -2956, -4961, 1, true,  "plains", "savanna", "meadow", "forest", "river", "frozen_river"),
        new Tile("Siberia",       238,-12952, 1, true,  "taiga", "snowy", "tundra", "grove",
                "swamp", "river", "frozen_river"),
        // Hudson Bay Lowlands are the world's second-largest peatland, so boreal
        // wetland here is a correct answer, not a miss.
        new Tile("CanadaBoreal",-1792, -7136, 1, true,  "taiga", "snowy", "plains", "forest",
                "swamp", "river", "frozen_river"),
        new Tile("Mesopotamia", 12519, -3502, 1, true,  "desert", "savanna", "swamp", "plains", "river", "frozen_river"),
        new Tile("BlackForest",  4509, -9282, 1, true,  "forest", "plains", "taiga", "river", "frozen_river"),
        new Tile("Indonesia",   21199,   181, 1, true,  "jungle", "forest", "ocean", "beach",
                "swamp", "river", "frozen_river"),
        // 6x tighter: the massif, not the whole Himalayan transect
        new Tile("Everest",     18299, -2957, 6, true,  "peaks", "snowy", "grove", "slopes", "river", "frozen_river"),
        // The measured cave-biome-at-surface site from TODO.md item 7, kept as a
        // tile so that regression stays visible in every scorecard.
        new Tile("DripstoneSite",-3075,-5332, 1, true, "plains", "forest", "taiga", "meadow",
                "savanna", "desert", "snowy", "birch", "jungle", "swamp", "beach", "stony", "river", "frozen_river"),
        new Tile("Marianas",    23704, -1214, 1, false, "ocean"),
        new Tile("MidAtlantic",  5120,     0, 1, false, "ocean"),
    };

    /** Vanilla's own erosion-band occupancy (%), measured with axisstats. */
    private static final double[] VANILLA_E_BANDS = {0.8, 14.6, 14.3, 34.1, 31.1, 2.2, 2.8};
    private static final double[] E_EDGES = {-1.0, -0.78, -0.375, -0.2225, 0.05, 0.45, 0.55, 1.0};

    /** Above this elevation, a drowned column is a real defect rather than an
     * unavoidable coastline-resolution artefact: at ~1 km per block a column the
     * raster calls +5 m is genuinely ambiguous, one at +100 m is not. */
    private static final double DROWN_SERIOUS_M = 60.0;

    /**
     * Relief (m) below which the surface is expected to HUG the elevation data.
     * Above it, deviation is the intended terrain character, not error:
     * SphereDensity drops factor toward FACTOR_RUGGED precisely so the 3D noise
     * gets ~25 blocks to carve cliffs and overhangs. Scoring raw RMSE over
     * mountains rewards flattening them, so only flat-ground RMSE is scored and
     * rugged RMSE is reported alongside.
     */
    private static final double RUGGED_M = 150.0;

    private static final String[] CAVE_BIOMES = {
        "dripstone", "lush_caves", "sulfur", "deep_dark"};

    // ---- evaluation ------------------------------------------------------

    public static String run(World world, EarthData earth, MapSampler sampler,
                             int side, int stride, boolean withTerrain) {
        if (earth == null) {
            return "evaluate: no Earth data loaded.";
        }
        CubeWorldBiomeProvider bp;
        if (!(world.getGenerator() instanceof CubeWorldChunkGenerator gen)) {
            return "evaluate: overworld is not a cube world.";
        }
        bp = gen.biomeProvider();

        NoiseRouter router = null;
        SphereDensity sd = SphereDensity.LAST;
        if (withTerrain) {
            try {
                router = ((CraftWorld) world).getHandle().getChunkSource().randomState().router();
            } catch (Throwable ignored) {
                // scored without terrain
            }
        }
        long seed = world.getSeed();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "== evaluate  %dx%d per tile, stride %d, terrain=%s ==%n",
                side, side, stride, withTerrain && router != null && sd != null));
        sb.append(String.format(Locale.ROOT, "%-13s %6s %7s %7s %7s %7s %7s %7s%n",
                "tile", "biome%", "rmseF", "drown%", "bad%", "tgtdr%", "land%", "cave%"));

        double sumBiome = 0, sumDrown = 0, sumSpur = 0, sumCave = 0, sumBadDrown = 0;
        double sumSqErr = 0, sumSqAll = 0;
        int nErr = 0, nAll = 0, nTiles = 0;
        List<Double> allErosion = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        for (Tile tile : TILES) {
            int n = 0, biomeHit = 0, drown = 0, spurious = 0, cave = 0, tgtDrown = 0;
            int badDrown = 0;
            // What the MISSES actually were -- without this a low biome% says
            // "wrong" but not "wrong how", which is the only useful part.
            java.util.Map<String, Integer> missed = new java.util.HashMap<>();
            double sq = 0;
            int nSq = 0;
            double sqFlat = 0;
            int nFlat = 0;
            int half = side / 2;
            for (int a = -half; a < half; a++) {
                for (int b = -half; b < half; b++) {
                    int st = Math.max(1, stride / tile.strideDiv());
                    double wx = tile.x() + a * (double) st + 0.5;
                    double wz = tile.z() + b * (double) st + 0.5;
                    double[] c = EarthClimate.params(earth, sampler, wx, wz,
                            (int) Math.round(sampler.heightAt(wx, wz)), seed);
                    if (c == null) {
                        continue;
                    }
                    n++;
                    allErosion.add(c[3]);
                    double elevM = c[6];

                    String biome = bp.surfaceBiome(world, (int) wx, (int) wz)
                            .getKey().getKey();
                    if (matches(biome, tile.expect())) {
                        biomeHit++;
                    } else {
                        missed.merge(biome, 1, Integer::sum);
                    }
                    for (String cb : CAVE_BIOMES) {
                        if (biome.contains(cb)) {
                            cave++;
                            break;
                        }
                    }

                    if (router != null && sd != null) {
                        // The height the DENSITY PATH aims at, incl. restored
                        // summits -- not the cell grid, which is ~45 blocks
                        // lower at Everest.
                        double target = sd.targetSurfaceY(wx, wz);
                        double got = surfaceY(sd, router, wx, wz, target);
                        if (!Double.isNaN(got)) {
                            // Land/water correctness is judged against the DATA
                            // (elevM), not against the target height, so a bug in
                            // the height curve still shows up here.
                            if (elevM >= 0 && got < EarthMapSpec.SEA_LEVEL) {
                                drown++;
                                if (elevM >= DROWN_SERIOUS_M) {
                                    badDrown++;
                                }
                            }
                            // Split the cause: if the height the density path is
                            // AIMING at is already below sea for a column the
                            // raster calls land, the loss happened in the height
                            // curve / cell-grid resolution, not in the 3D noise.
                            // MapSampler.heightAt is a 16-block CELL grid, i.e.
                            // ~16 km at this scale, so a coastline finer than
                            // that cannot be represented at all.
                            if (elevM >= 0 && target < EarthMapSpec.SEA_LEVEL) {
                                tgtDrown++;
                            }
                            if (elevM < 0 && got >= EarthMapSpec.SEA_LEVEL) {
                                spurious++;
                            }
                            double e = got - target;
                            sq += e * e;
                            nSq++;
                            if (c[9] < RUGGED_M) {
                                sqFlat += e * e;
                                nFlat++;
                            }
                        }
                    }
                }
            }
            if (n == 0) {
                continue;
            }
            nTiles++;
            double biomePct = 100.0 * biomeHit / n;
            double rmse = nFlat > 0 ? Math.sqrt(sqFlat / nFlat) : Double.NaN;
            sumBiome += biomePct;
            sumDrown += 100.0 * drown / n;
            sumSpur += 100.0 * spurious / n;
            sumCave += 100.0 * cave / n;
            sumBadDrown += 100.0 * badDrown / n;
            sumSqErr += sqFlat;
            nErr += nFlat;
            sumSqAll += sq;
            nAll += nSq;
            sb.append(String.format(Locale.ROOT,
                    "%-13s %6.1f %7.1f %7.1f %7.1f %7.1f %7.1f %7.2f%n",
                    tile.name(), biomePct, rmse, 100.0 * drown / n,
                    100.0 * badDrown / n, 100.0 * tgtDrown / n,
                    100.0 * spurious / n, 100.0 * cave / n));
            if (!missed.isEmpty() && biomePct < 99.0) {
                final int total = n;
                String top = missed.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(3)
                        .map(e -> e.getKey() + " "
                                + String.format(Locale.ROOT, "%.0f%%", 100.0 * e.getValue() / total))
                        .reduce((a, b) -> a + ", " + b).orElse("");
                sb.append("              missed: ").append(top).append('\n');
            }
        }

        double biome = sumBiome / nTiles;
        double drownPct = sumDrown / nTiles;
        double spurPct = sumSpur / nTiles;
        double cavePct = sumCave / nTiles;
        double badDrownPct = sumBadDrown / nTiles;
        double rmse = nErr > 0 ? Math.sqrt(sumSqErr / nErr) : 0;
        double rmseAll = nAll > 0 ? Math.sqrt(sumSqAll / nAll) : 0;
        // The erosion-spread term is a GLOBAL property ("does our erosion have
        // vanilla's distribution"), and vanilla's reference bands were measured
        // over a whole world. Measuring it over the tile suite instead compares
        // a ~85%-land sample against a whole-world reference and reports a
        // difference that is pure sampling bias -- it read 0.312 while the true
        // global figure was 0.02. So it is sampled on its own global grid.
        double[] shore = shoreStats(world, earth, sampler, sd, router, seed);
        double eDist = erosionBandDistance(globalErosion(earth, sampler, seed));
        double eDistTiles = erosionBandDistance(allErosion);

        // Shoreline quality is a real axis -- an unjumpable wall at every coast
        // is a defect a player meets constantly -- and leaving it unscored meant
        // a change that halved it looked like a regression. Weight 0.1 puts it
        // on the same order as the other terms (73% -> 7.3 points).
        double unjumpable = shore != null ? shore[1] : 0.0;
        // Headline: lower is better. Weights are stated so nothing hides.
        double total = (100 - biome) * 1.0
                + unjumpable * 0.1
                + drownPct * 2.0
                + spurPct * 2.0
                + cavePct * 5.0
                + rmse * 1.0
                + eDist * 50.0;

        sb.append(String.format(Locale.ROOT,
                "-- means --   biome %.1f%%  rmse(flat) %.1f  drown %.1f%% (of which >=%.0fm: %.2f%%)"
                + "  spurland %.1f%%  cave %.2f%%%n",
                biome, rmse, drownPct, DROWN_SERIOUS_M, badDrownPct, spurPct, cavePct));
        sb.append(String.format(Locale.ROOT,
                "rmse over ALL columns incl. rugged: %.1f  (not scored -- deviation on"
                + " mountains is intended character)%n", rmseAll));
        if (shore != null) {
            sb.append(String.format(Locale.ROOT,
                    "shoreline: step %.1f blk, %.0f%% unjumpable (>2)   lowland slope "
                    + "%.2f blk per %d blk  (n=%.0f)%n",
                    shore[0], shore[1], shore[2], 6, shore[3]));
        }
        sb.append(String.format(Locale.ROOT,
                "erosion-band L1 vs vanilla: %.3f global  (%.3f over tiles, land-biased -- "
                + "not scored)%n", eDist, eDistTiles));
        sb.append(String.format(Locale.ROOT,
                "TOTAL %.1f  = (100-biome) + shoreWall*0.1 + drown*2 + spurland*2 + cave*5"
                + " + rmse + eDist*50"
                + "   [lower is better]%n", total));
        sb.append(String.format(Locale.ROOT, "(%d ms)%n", System.currentTimeMillis() - t0));
        return sb.toString();
    }

    /**
     * Erosion sampled on a coarse world-wide grid, for the distribution term.
     * Same shape of grid AxisStats uses, so the two agree.
     */
    private static List<Double> globalErosion(EarthData earth, MapSampler sampler, long seed) {
        List<Double> out = new ArrayList<>();
        int half = 110;
        int stride = 260;
        for (int a = -half; a < half; a++) {
            for (int b = -half; b < half; b++) {
                double wx = a * (double) stride + 0.5;
                double wz = b * (double) stride + 0.5;
                double[] c = EarthClimate.params(earth, sampler, wx, wz,
                        (int) Math.round(sampler.heightAt(wx, wz)), seed);
                if (c != null) {
                    out.add(c[3]);
                }
            }
        }
        return out;
    }

    /**
     * Shoreline and lowland-relief measurement.
     *
     * <p>Two failure modes with no metric until now, so both were invisible to
     * the scorecard: the shore being an unjumpable wall, and the land behind it
     * being a perfectly flat table. Returns
     * {@code [meanStep, fracUnjumpable, lowlandSlope, nShore]}.
     *
     * <p>{@code lowlandSlope} is the mean absolute height difference between
     * horizontally adjacent land columns below 450 m -- the band that
     * LAND_FREEBOARD_MIN currently pins flat. Near zero means a table.
     */
    static double[] shoreStats(World world, EarthData earth, MapSampler sampler,
                               SphereDensity sd, NoiseRouter router, long seed) {
        if (sd == null || router == null) {
            return null;
        }
        java.util.List<Double> steps = new ArrayList<>();
        java.util.List<Double> slopes = new ArrayList<>();
        int step = 6;
        // Walk several coastal boxes rather than the whole world: finding the
        // waterline by brute force over the net would dominate the runtime.
        int[][] boxes = {{6500, -1691}, {2586, 2494}, {9570, -4637}, {21199, 181},
                         {11247, -3083}, {-1792, -7136}};
        for (int[] c : boxes) {
            for (int a = -22; a < 22; a++) {
                for (int b = -22; b < 22; b++) {
                    double wx = c[0] + a * step + 0.5;
                    double wz = c[1] + b * step + 0.5;
                    double t0 = sd.targetSurfaceY(wx, wz);
                    double y0 = surfaceY(sd, router, wx, wz, t0);
                    if (Double.isNaN(y0)) {
                        continue;
                    }
                    double tE = sd.targetSurfaceY(wx + step, wz);
                    double yE = surfaceY(sd, router, wx + step, wz, tE);
                    if (Double.isNaN(yE)) {
                        continue;
                    }
                    boolean land0 = y0 >= EarthMapSpec.SEA_LEVEL + 1;
                    boolean landE = yE >= EarthMapSpec.SEA_LEVEL + 1;
                    if (land0 != landE) {
                        steps.add(Math.max(y0, yE) - EarthMapSpec.SEA_LEVEL);
                    } else if (land0) {
                        double[] p = EarthClimate.params(earth, sampler, wx, wz,
                                (int) Math.round(t0), seed);
                        if (p != null && p[6] >= 0 && p[6] < 450) {
                            slopes.add(Math.abs(yE - y0));
                        }
                    }
                }
            }
        }
        if (steps.isEmpty()) {
            return null;
        }
        double mean = steps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double unjump = steps.stream().filter(v -> v > 2.0).count() / (double) steps.size();
        double slope = slopes.isEmpty() ? 0
                : slopes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new double[] {mean, unjump * 100.0, slope, steps.size()};
    }

    private static boolean matches(String biome, String[] expect) {
        for (String e : expect) {
            if (biome.contains(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generated surface Y at a column, EMULATING VANILLA'S CELL LATTICE.
     *
     * <p>The first version of this sampled {@code finalDensity} pointwise and
     * was simply wrong: cross-checked against real generated chunks in the
     * Sahara it reported the surface at y61 where the world actually has y65,
     * and that 4-block bias manufactured a 2.5% "drowning" rate that does not
     * exist. Chunk generation never asks the density function about most
     * blocks -- it evaluates only the corners of 4x8x4 cells and interpolates
     * trilinearly (NoiseChunk.NoiseInterpolator), and the interpolation smooths
     * away exactly the pointwise dips the naive scan was falling into.
     *
     * <p>So this walks the same lattice: bilinear across the cell's four XZ
     * corners at each cell-Y plane, then linear between planes to locate the
     * zero crossing. Remaining difference from a real chunk is the surface
     * rules and carvers, which run afterwards and can only remove material.
     */
    private static double surfaceY(SphereDensity sd, NoiseRouter router,
                                   double wx, double wz, double target) {
        int x0 = Math.floorDiv((int) Math.floor(wx), CELL_XZ) * CELL_XZ;
        int z0 = Math.floorDiv((int) Math.floor(wz), CELL_XZ) * CELL_XZ;
        double fx = (wx - x0) / CELL_XZ;
        double fz = (wz - z0) / CELL_XZ;

        int topCell = Math.floorDiv((int) Math.min(248, target + 48) + 64, CELL_Y);
        int botCell = Math.floorDiv((int) Math.max(-64, target - 64) + 64, CELL_Y);
        double prevVal = Double.NaN;
        int prevY = 0;
        for (int cy = topCell; cy >= botCell; cy--) {
            int y = cy * CELL_Y - 64;
            double v = cellDensity(sd, router, x0, z0, y, fx, fz);
            if (v > 0) {
                if (!Double.isNaN(prevVal) && prevVal <= 0) {
                    double t = (0.0 - prevVal) / (v - prevVal);
                    return prevY + t * (y - prevY);
                }
                return y;
            }
            prevVal = v;
            prevY = y;
        }
        return Double.NaN;
    }

    private static final int CELL_XZ = 4;
    private static final int CELL_Y = 8;

    /** Bilinear across the cell's four XZ corners at one cell-Y plane. */
    private static double cellDensity(SphereDensity sd, NoiseRouter router,
                                      int x0, int z0, int y, double fx, double fz) {
        // +0.5: chunk generation folds the sphere at BLOCK CENTRES
        // (SphereDensity.sphereFrom uses bx + 0.5) and then rounds the folded
        // coordinate to an int, so evaluating at the integer corner instead
        // samples the 3D noise at a different lattice point entirely.
        double d00 = sd.probe(router, x0 + 0.5, y, z0 + 0.5);
        double d10 = sd.probe(router, x0 + CELL_XZ + 0.5, y, z0 + 0.5);
        double d01 = sd.probe(router, x0 + 0.5, y, z0 + CELL_XZ + 0.5);
        double d11 = sd.probe(router, x0 + CELL_XZ + 0.5, y, z0 + CELL_XZ + 0.5);
        double a = d00 + (d10 - d00) * fx;
        double b = d01 + (d11 - d01) * fx;
        return a + (b - a) * fz;
    }

    /** L1 distance (halved, so 0..1) between our erosion spread and vanilla's. */
    private static double erosionBandDistance(List<Double> erosion) {
        if (erosion.isEmpty()) {
            return 1.0;
        }
        double[] counts = new double[VANILLA_E_BANDS.length];
        for (double e : erosion) {
            for (int i = 0; i < counts.length; i++) {
                if (e >= E_EDGES[i] && (e < E_EDGES[i + 1] || i == counts.length - 1)) {
                    counts[i]++;
                    break;
                }
            }
        }
        double d = 0;
        for (int i = 0; i < counts.length; i++) {
            d += Math.abs(100.0 * counts[i] / erosion.size() - VANILLA_E_BANDS[i]);
        }
        return d / 200.0;
    }

    /**
     * Dump the terms for columns that DROWN, so the cause can be read off
     * rather than guessed. Prints the raster elevation, the cell-grid height,
     * the height the density path aims at, the shaping terms, and where the
     * surface actually landed.
     */
    public static String drownProbe(World world, EarthData earth, MapSampler sampler,
                                    int cx, int cz, int side, int stride, int maxRows) {
        SphereDensity sd = SphereDensity.LAST;
        if (sd == null || earth == null) {
            return "drownprobe: no SphereDensity / Earth data";
        }
        NoiseRouter router;
        try {
            router = ((CraftWorld) world).getHandle().getChunkSource().randomState().router();
        } catch (Throwable t) {
            return "drownprobe: no router (" + t + ")";
        }
        long seed = world.getSeed();
        StringBuilder sb = new StringBuilder("drownprobe around (" + cx + "," + cz + ")\n");
        sb.append(String.format(Locale.ROOT, "%9s %9s %8s %8s %8s %8s%n",
                "elev(m)", "relief(m)", "heightAt", "target", "surface", "drop"));
        int rows = 0;
        int half = side / 2;
        for (int a = -half; a < half && rows < maxRows; a++) {
            for (int b = -half; b < half && rows < maxRows; b++) {
                double wx = cx + a * (double) stride + 0.5;
                double wz = cz + b * (double) stride + 0.5;
                double[] c = EarthClimate.params(earth, sampler, wx, wz,
                        (int) Math.round(sampler.heightAt(wx, wz)), seed);
                if (c == null || c[6] < 0) {
                    continue;
                }
                double target = sd.targetSurfaceY(wx, wz);
                double got = surfaceY(sd, router, wx, wz, target);
                if (Double.isNaN(got) || got >= EarthMapSpec.SEA_LEVEL) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT, "%9.0f %9.0f %8.2f %8.2f %8.0f %8.2f%n",
                        c[6], c[9], sampler.heightAt(wx, wz), target, got, target - got));
                rows++;
            }
        }
        if (rows == 0) {
            sb.append("  (no drowning columns found)\n");
        }
        sb.append(sd.probeTerms(cx + 0.5, cz + 0.5)).append('\n');
        return sb.toString();
    }

    /**
     * Dump "x z emulatedSurfaceY" for a grid, so the emulator can be validated
     * against real generated chunks read from the region files. Without this
     * the terrain metrics are unfalsifiable.
     */
    public static String surfaceDump(World world, MapSampler sampler,
                                     int cx, int cz, int side, int stride) {
        SphereDensity sd = SphereDensity.LAST;
        if (sd == null) {
            return "surfacedump: no SphereDensity";
        }
        NoiseRouter router;
        try {
            router = ((CraftWorld) world).getHandle().getChunkSource().randomState().router();
        } catch (Throwable t) {
            return "surfacedump: no router";
        }
        StringBuilder sb = new StringBuilder();
        int half = side / 2;
        for (int a = -half; a < half; a++) {
            for (int b = -half; b < half; b++) {
                int x = cx + a * stride;
                int z = cz + b * stride;
                double target = sd.targetSurfaceY(x + 0.5, z + 0.5);
                double got = surfaceY(sd, router, x + 0.5, z + 0.5, target);
                sb.append(String.format(Locale.ROOT, "P %d %d %.2f %.2f%n",
                        x, z, got, target));
            }
        }
        return sb.toString();
    }

    /**
     * Recomputed surface-biome histogram over a grid. Unlike {@code biomeat},
     * which reads the STORED biome out of an already-generated chunk and so
     * reports pre-change answers forever, this calls the provider directly and
     * always reflects current code.
     */
    public static String biomeHist(World world, int cx, int cz, int side, int stride) {
        if (!(world.getGenerator() instanceof CubeWorldChunkGenerator gen)) {
            return "biomehist: not a cube world";
        }
        CubeWorldBiomeProvider bp = gen.biomeProvider();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int n = 0;
        int half = side / 2;
        for (int a = -half; a < half; a++) {
            for (int b = -half; b < half; b++) {
                String biome = bp.surfaceBiome(world, cx + a * stride, cz + b * stride)
                        .getKey().getKey();
                counts.merge(biome, 1, Integer::sum);
                n++;
            }
        }
        final int total = n;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "biomehist (%d,%d) %dx%d stride %d  n=%d%n", cx, cz, side, side, stride, n));
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .forEach(e -> sb.append(String.format(Locale.ROOT, "   %-28s %5.1f%%%n",
                        e.getKey(), 100.0 * e.getValue() / total)));
        return sb.toString();
    }

    /** Vertical biome profile down a column: what you would actually dig through. */
    public static String biomeColumn(World world, MapSampler sampler, int x, int z, int step) {
        if (!(world.getGenerator() instanceof CubeWorldChunkGenerator gen)) {
            return "biomecolumn: not a cube world";
        }
        CubeWorldBiomeProvider bp = gen.biomeProvider();
        double surf = sampler.heightAt(x + 0.5, z + 0.5);
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "biomecolumn (%d,%d) surface y=%.1f%n", x, z, surf));
        String last = null;
        for (int y = (int) Math.round(surf); y >= -64; y -= step) {
            String b = bp.getBiome(world, x, y, z).getKey().getKey();
            if (!b.equals(last)) {
                // Report blocks below the surface, not a hardcoded depth unit:
                // the depth scale is now selectable and the old /70 display was
                // simply wrong in the other modes.
                sb.append(String.format(Locale.ROOT, "   y%5d  %4.0f blk down  %s%n",
                        y, surf - y, b));
                last = b;
            }
        }
        return sb.toString();
    }

    /**
     * Global census of the biome a fixed number of blocks BELOW the surface.
     * The scorecard only checks that cave biomes stay OFF the surface; this
     * checks the opposite failure -- that they exist underground at all.
     */
    public static String undergroundCensus(World world, EarthData earth, MapSampler sampler,
                                           int below, int side, int stride) {
        if (!(world.getGenerator() instanceof CubeWorldChunkGenerator gen)) {
            return "undergroundcensus: not a cube world";
        }
        CubeWorldBiomeProvider bp = gen.biomeProvider();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int n = 0;
        int half = side / 2;
        for (int a = -half; a < half; a++) {
            for (int b = -half; b < half; b++) {
                int x = a * stride;
                int z = b * stride;
                double surf = sampler.heightAt(x + 0.5, z + 0.5);
                int y = (int) Math.round(surf) - below;
                if (y < -64) {
                    continue;
                }
                String bio = bp.getBiome(world, x, y, z).getKey().getKey();
                // The sampling box is a square but the cube NET is a cross, so
                // most of the box is off-net. Counting the_void buries every
                // real percentage under it.
                if (bio.equals("the_void")) {
                    continue;
                }
                counts.merge(bio, 1, Integer::sum);
                n++;
            }
        }
        final int total = n;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "underground census %d blocks below surface  n=%d%n", below, n));
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(12)
                .forEach(e -> sb.append(String.format(Locale.ROOT, "   %-28s %5.2f%%%n",
                        e.getKey(), 100.0 * e.getValue() / total)));
        return sb.toString();
    }

    /**
     * Which biomes in the LIVE parameter list our axes can actually produce.
     *
     * <p>We never fork vanilla's biome table -- {@link VanillaBiomeMapper} reads
     * the overworld preset out of the registry -- so a biome Mojang adds in an
     * update needs no code change from us. What it does need is for our
     * synthetic axes to REACH the corner of parameter space it was given, and
     * that is not automatic.
     *
     * <p>This is not hypothetical. {@code sulfur_caves} shipped in 26.2 and
     * never appeared anywhere in the world, because its box wants weirdness
     * &lt;= -0.85 and ours only reached -0.67. A biome can be perfectly
     * integrated and still be silently absent, and the symptom is nothing at
     * all. Run this after any version bump.
     */
    public static String biomeCoverage(World world, EarthData earth, MapSampler sampler,
                                       long seed, int side, int stride) {
        if (!(world.getGenerator() instanceof CubeWorldChunkGenerator gen)) {
            return "biomecoverage: not a cube world";
        }
        CubeWorldBiomeProvider bp = gen.biomeProvider();
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        int n = 0;
        int half = side / 2;
        for (int a = -half; a < half; a++) {
            for (int b = -half; b < half; b++) {
                int x = a * stride;
                int z = b * stride;
                double surf = sampler.heightAt(x + 0.5, z + 0.5);
                // Surface plus a depth sweep: the underground bands are only
                // reachable well below the surface, so a surface-only census
                // would report every cave biome as missing.
                for (double frac : new double[] {0.0, 0.25, 0.5, 0.75, 0.95}) {
                    int y = (int) Math.round(surf - frac * (surf + 64.0));
                    if (y < -64) {
                        continue;
                    }
                    String b2 = bp.getBiome(world, x, y, z).getKey().getKey();
                    if (!b2.equals("the_void")) {
                        seen.merge(b2, 1, Integer::sum);
                        n++;
                    }
                }
            }
        }
        java.util.List<String> all = new java.util.ArrayList<>();
        for (org.bukkit.block.Biome b2 : gen.biomeProvider().mapper().possibleBiomes()) {
            all.add(b2.getKey().getKey());
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String b2 : all) {
            if (!seen.containsKey(b2)) {
                missing.add(b2);
            }
        }
        java.util.Collections.sort(missing);
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "biomecoverage: %d samples, %d/%d biomes in the live parameter list reached%n",
                n, all.size() - missing.size(), all.size()));
        if (missing.isEmpty()) {
            sb.append("  ALL REACHABLE\n");
        } else {
            sb.append("  UNREACHABLE (in vanilla's table, never produced by our axes):\n");
            for (String b2 : missing) {
                sb.append("     ").append(b2).append('\n');
            }
        }
        java.util.List<java.util.Map.Entry<String, Integer>> rare =
                new java.util.ArrayList<>(seen.entrySet());
        rare.sort(java.util.Map.Entry.comparingByValue());
        sb.append("  rarest reached: ");
        for (int i = 0; i < Math.min(6, rare.size()); i++) {
            sb.append(rare.get(i).getKey()).append(' ')
              .append(String.format(Locale.ROOT, "%.3f%% ", 100.0 * rare.get(i).getValue() / n));
        }
        return sb.append('\n').toString();
    }

    /** For the record, so a scorecard can be reproduced. */
    public static String tiles() {
        return Arrays.stream(TILES)
                .map(t -> t.name() + "(" + t.x() + "," + t.z() + ")")
                .reduce((a, b) -> a + " " + b).orElse("");
    }
}
