package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.generation.OreDeposits.Ore;
import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Enriches specific ores where the world's terrain corresponds to real Earth mineral
 * provinces (see {@link OreDeposits}). Runs only on freshly generated overworld chunks
 * -- so it takes effect on world (re)generation, layering extra veins on top of
 * vanilla's own ore placement without disturbing already-explored terrain.
 *
 * <p>Each province's lat/lon is inverted to a world (x,z) once at startup (the same
 * numerical inversion the {@code /cubeworld findlatlon} command uses). A chunk inside a
 * province gets {@code coreVeins x freq x strength} extra veins of that ore, each
 * {@code veinSize x size} blocks, where {@code strength} tapers linearly from 1 at the
 * centre to 0 at the province radius. Placement is deterministic from the world seed,
 * so a regen reproduces the same deposits.
 *
 * <p>Mirrors {@link com.ckemere.cubeworld.city.VillageGroundFixer}: a queue filled from
 * {@link ChunkLoadEvent} and drained a few chunks per tick on the main thread, so a
 * mass regen never blocks the server.
 */
public final class OreEnrichment implements Listener {

    /** Host rock we are willing to convert into ore (natural stone only). */
    private static boolean isHostRock(Material m) {
        return switch (m) {
            case STONE, GRANITE, DIORITE, ANDESITE, DEEPSLATE, TUFF -> true;
            default -> false;
        };
    }

    /** Deepslate/tuff host -> deepslate ore; stone host -> stone ore. */
    private static Material oreFor(Ore ore, Material host) {
        return (host == Material.DEEPSLATE || host == Material.TUFF)
                ? ore.deepslateOre : ore.stoneOre;
    }

    /** Reverse map: an ore block (either variant) -> its Ore, for counting vanilla ore. */
    private static final Map<Material, Ore> ORE_OF_BLOCK = new java.util.HashMap<>();

    static {
        for (Ore o : Ore.values()) {
            ORE_OF_BLOCK.put(o.stoneOre, o);
            ORE_OF_BLOCK.put(o.deepslateOre, o);
        }
    }

    /** Y band scanned when counting a chunk's existing vanilla ore (covers all bands). */
    private static final int SCAN_MIN = -64;
    private static final int SCAN_MAX = 128;

    private static final int PER_TICK = 4;

    private final CubeWorldPlugin plugin;

    private final boolean enabled =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.oreEnrichment", "true"));
    // Total in-province ore density as a multiple of vanilla (the "3-4x more veins").
    private final double freqMult =
            Double.parseDouble(System.getProperty("cubeworld.oreFreq", "3.5"));
    // How much bigger added veins are than nominal (the "2-3x bigger veins").
    private final double sizeMult =
            Double.parseDouble(System.getProperty("cubeworld.oreSize", "2.5"));
    private final int radius =
            Integer.parseInt(System.getProperty("cubeworld.oreRadius", "200"));
    // How far the noise can push strength up or down. 0 restores the plain cone.
    private final double noiseAmp =
            Double.parseDouble(System.getProperty("cubeworld.oreNoise", "0.35"));
    // Noise wavelength in blocks. Strength is sampled once per chunk (at its centre),
    // so anything below ~64 is invisible; 100-300 gives lobes a few chunks across.
    private final double noiseScale =
            Double.parseDouble(System.getProperty("cubeworld.oreNoiseScale", "160"));
    // At or above this strength a chunk gets one guaranteed oversized vein, so the
    // prospecting payoff is actually findable by digging rather than only by luck.
    private final double motherlodeAt =
            Double.parseDouble(System.getProperty("cubeworld.oreMotherlode", "0.9"));

    /** Per-ore province centres as {x, z, radius} in world blocks (radius 0 = default). */
    private Map<Ore, List<int[]>> centres;
    /** Seeded from the world so the lobe pattern is stable across a regen. */
    private long noiseSeed;

    private final ArrayDeque<long[]> queue = new ArrayDeque<>();  // packed chunk (x,z)
    private int enrichedChunks;

    public OreEnrichment(CubeWorldPlugin plugin) {
        this.plugin = plugin;
        if (!enabled) {
            plugin.getLogger().info("OreEnrichment: DISABLED via -Dcubeworld.oreEnrichment=false");
            return;
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 20L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!enabled
                || !e.isNewChunk()
                || !plugin.isCubeWorld(e.getWorld())
                || e.getWorld().getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        synchronized (queue) {
            queue.add(new long[] {e.getChunk().getX(), e.getChunk().getZ()});
        }
    }

    private void drain() {
        if (centres == null && !prime()) {
            return;               // Earth data not ready yet; try again next tick
        }
        for (int i = 0; i < PER_TICK; i++) {
            long[] cc;
            synchronized (queue) {
                cc = queue.poll();
            }
            if (cc == null) {
                return;
            }
            World w = plugin.getServer().getWorlds().isEmpty()
                    ? null : plugin.getServer().getWorlds().get(0);
            if (w == null) {
                return;
            }
            if (!w.isChunkLoaded((int) cc[0], (int) cc[1])) {
                continue;       // gone before we got to it; it was enriched at gen or not needed
            }
            try {
                enrich(w, (int) cc[0], (int) cc[1]);
            } catch (Exception ex) {
                plugin.getLogger().warning("OreEnrichment: " + ex);
            }
        }
    }

    /** Invert every province's lat/lon to a world (x,z). Returns false if not ready. */
    private boolean prime() {
        if (plugin.getServer().getWorlds().isEmpty() || !plugin.maps().hasEarthData()) {
            return false;
        }
        World w = plugin.getServer().getWorlds().get(0);
        EarthData earth = plugin.maps().earthData();
        MapSampler sampler = plugin.maps().mapFor(w.getSeed()).sampler();
        CubeGeometry geom = plugin.geometry();
        noiseSeed = w.getSeed() * 0x2545F4914F6CDD1DL;
        Map<Ore, List<int[]>> out = new EnumMap<>(Ore.class);
        int deep = 0;
        for (var entry : OreDeposits.PROVINCES.entrySet()) {
            List<int[]> pts = new ArrayList<>();
            for (double[] ll : entry.getValue()) {
                int[] xz = worldOf(earth, sampler, geom, ll[0], ll[1]);
                if (xz != null) {
                    int r = ll.length > 2 ? (int) Math.round(ll[2]) : 0;
                    pts.add(new int[] {xz[0], xz[1], r});
                    if (r > 0) {
                        deep++;
                    }
                }
            }
            out.put(entry.getKey(), pts);
        }
        // Test province: gold right at spawn (Ethiopia), so it can be checked without
        // hunting. This one is intentionally known; the rest are quiet.
        out.get(Ore.GOLD).add(new int[] {11973, -856, 0});
        centres = out;
        int total = out.values().stream().mapToInt(List::size).sum();
        plugin.getLogger().info("OreEnrichment: primed " + total
                + " ore provinces (" + deep + " deep-water), radius " + radius
                + ", freq x" + freqMult + ", size x" + sizeMult
                + ", noise " + noiseAmp + "@" + noiseScale + "b");
        return true;
    }

    /** Numerically invert world->lon/lat (coarse per-face scan then refine). */
    private int[] worldOf(EarthData earth, MapSampler sampler, CubeGeometry geom,
                          double tLat, double tLon) {
        double bx = 0;
        double bz = 0;
        double best = Double.MAX_VALUE;
        double fs = geom.faceSize();
        for (CubeFace f : CubeFace.values()) {
            double x0 = geom.faceMinX(f);
            double z0 = geom.faceMinZ(f);
            for (int i = 0; i <= 64; i++) {
                for (int j = 0; j <= 64; j++) {
                    double x = x0 + i * (fs / 64.0);
                    double z = z0 + j * (fs / 64.0);
                    double d = err(earth, sampler, x, z, tLat, tLon);
                    if (d < best) {
                        best = d;
                        bx = x;
                        bz = z;
                    }
                }
            }
        }
        for (double step = fs / 64.0; step > 0.4; step /= 2.0) {
            for (int i = -2; i <= 2; i++) {
                for (int j = -2; j <= 2; j++) {
                    double x = bx + i * step;
                    double z = bz + j * step;
                    double d = err(earth, sampler, x, z, tLat, tLon);
                    if (d < best) {
                        best = d;
                        bx = x;
                        bz = z;
                    }
                }
            }
        }
        return best > 1.0 ? null : new int[] {(int) Math.round(bx), (int) Math.round(bz)};
    }

    private double err(EarthData earth, MapSampler sampler,
                       double x, double z, double tLat, double tLon) {
        Vec3 p = sampler.cubePointAt(x, z);
        if (p == null) {
            return Double.MAX_VALUE / 4;
        }
        double[] ll = earth.toLonLat(p);
        double dLat = ll[1] - tLat;
        double dLon = ll[0] - tLon;
        if (dLon > 180) {
            dLon -= 360;
        }
        if (dLon < -180) {
            dLon += 360;
        }
        dLon *= Math.cos(Math.toRadians(tLat));
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    /** Smoothstep, so the interpolated noise has no visible lattice creases. */
    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** Deterministic hash of a noise-lattice corner to 0..1. */
    private double corner(int ordinal, int xi, int zi) {
        long h = noiseSeed
                ^ (ordinal * 0x165667B19E3779F9L)
                ^ (xi * 0x9E3779B97F4A7C15L)
                ^ (zi * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    /**
     * Value noise in -1..+1, per ore so two ores sharing a province do not get
     * identical lobes. Bilinear with a smoothstep fade -- cheap, and we only need
     * it once per chunk per ore.
     */
    private double noise(Ore ore, double wx, double wz) {
        double fx = wx / noiseScale;
        double fz = wz / noiseScale;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double sx = smooth(fx - x0);
        double sz = smooth(fz - z0);
        int o = ore.ordinal();
        double a = corner(o, x0, z0) + (corner(o, x0 + 1, z0) - corner(o, x0, z0)) * sx;
        double b = corner(o, x0, z0 + 1)
                + (corner(o, x0 + 1, z0 + 1) - corner(o, x0, z0 + 1)) * sx;
        return (a + (b - a) * sz) * 2.0 - 1.0;
    }

    /**
     * Enrichment strength (0..1) for an ore at a world (x,z): a cone falling from 1
     * at the nearest province centre to 0 at that province's radius, plus a noise
     * field. The noise does two jobs at once -- it makes the boundary irregular
     * instead of a perfect circle (so a province cannot be trilaterated from three
     * samples), and it breaks the interior into lobes so the deposit is not simply
     * "hotter towards the middle". Public so a probe command can report it.
     */
    public double strengthAt(Ore ore, double wx, double wz) {
        if (centres == null && !prime()) {
            return 0;
        }
        List<int[]> pts = centres.get(ore);
        if (pts == null) {
            return 0;
        }
        double best = 0;
        for (int[] c : pts) {
            double r = c[2] > 0 ? c[2] : radius;
            double dx = wx - c[0];
            double dz = wz - c[1];
            double d = Math.sqrt(dx * dx + dz * dz);
            // Search past the nominal edge so noise can bulge the boundary OUTWARD
            // as well as in; the cone goes negative out there and clamps to 0.
            if (d >= r * (1.0 + noiseAmp)) {
                continue;
            }
            double s = (1.0 - d / r) + noiseAmp * noise(ore, wx, wz);
            best = Math.max(best, Math.clamp(s, 0.0, 1.0));
        }
        return best;
    }

    private void enrich(World w, int cx, int cz) {
        int baseX = cx << 4;
        int baseZ = cz << 4;
        double chX = baseX + 8;
        double chZ = baseZ + 8;

        // Which ores are enriched here, and how strongly? Bail before the (costly)
        // chunk scan if this chunk is in no province.
        double[] strength = new double[Ore.values().length];
        boolean anyZone = false;
        for (Ore ore : Ore.values()) {
            double s = strengthAt(ore, chX, chZ);
            strength[ore.ordinal()] = s;
            anyZone |= s > 0;
        }
        if (!anyZone) {
            return;
        }

        // One snapshot for the whole chunk: the vanilla-ore tally reads it, and so
        // does every vein placement (to find where the host rock actually starts).
        ChunkSnapshot snap = w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
        // Count the chunk's own vanilla ore so the boost is proportional to what the
        // generator actually placed here (baselines vary wildly per ore and biome).
        int[] vanilla = countVanillaOre(w, snap);

        long seed = w.getSeed();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;
        boolean any = false;
        for (Ore ore : Ore.values()) {
            double s = strength[ore.ordinal()];
            if (s <= 0) {
                continue;
            }
            // Deterministic per (seed, chunk, ore): a regen reproduces the deposit.
            Random rng = new Random(seed
                    ^ (cx * 0x9E3779B97F4A7C15L)
                    ^ (cz * 0xC2B2AE3D27D4EB4FL)
                    ^ (ore.ordinal() * 0x165667B19E3779F9L));
            int v = vanilla[ore.ordinal()];
            // Estimate the chunk's vanilla vein count, then add (freqMult-1)x as many
            // (the "3-4x more veins"), never below a floor so biome-locked ores still
            // appear. Each added vein is sizeMult x nominal (the "2-3x bigger veins").
            double vanillaVeins = v / (double) ore.nominalVeinSize;
            double addedVeins = Math.max((freqMult - 1.0) * vanillaVeins, ore.floorVeins) * s;
            // Round PROBABILISTICALLY, not to nearest. Math.round() zeroed anything
            // under half a vein, so with floorVeins=3 the outer ~17% of every province
            // added literally nothing while the probe still reported "faint" there --
            // the instrument disagreed with the ground. The fractional part now
            // becomes the chance of one more vein, which is unbiased in the mean.
            int veins = (int) Math.floor(addedVeins);
            if (rng.nextDouble() < addedVeins - veins) {
                veins++;
            }
            int size = Math.max(2, (int) Math.round(ore.nominalVeinSize * sizeMult));
            int lo = Math.max(minY, ore.yMin);
            int hi = Math.min(maxY, ore.yMax);
            if (hi <= lo) {
                continue;
            }
            // The payoff vein. Without it a player who follows the detector to the
            // hottest chunk and sinks a shaft usually finds nothing: veins land at a
            // random x,z in the chunk over a y band ~100 blocks deep, so a 1x1 shaft
            // samples a tiny fraction of the volume. One big vein near the middle
            // makes the strongest reading mean something you can actually dig up.
            if (s >= motherlodeAt) {
                placeVein(w, ore, baseX + 4 + rng.nextInt(8), baseZ + 4 + rng.nextInt(8),
                        lo, hi, size * 3, rng, snap, true);
                any = true;
            }
            for (int k = 0; k < veins; k++) {
                placeVein(w, ore, baseX + rng.nextInt(16), baseZ + rng.nextInt(16),
                        lo, hi, size, rng, snap, false);
                any = true;
            }
        }
        if (any && (++enrichedChunks % 200 == 0)) {
            plugin.getLogger().info("OreEnrichment: enriched " + enrichedChunks + " chunks");
        }
    }

    /** Tally each ore's existing (vanilla-placed) blocks in the chunk, by ore index. */
    private int[] countVanillaOre(World w, ChunkSnapshot snap) {
        int[] out = new int[Ore.values().length];
        int lo = Math.max(w.getMinHeight(), SCAN_MIN);
        int hi = Math.min(w.getMaxHeight() - 1, SCAN_MAX);
        for (int y = lo; y <= hi; y++) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    Ore o = ORE_OF_BLOCK.get(snap.getBlockType(lx, y, lz));
                    if (o != null) {
                        out[o.ordinal()]++;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Highest y at or below {@code hi} where this column is host rock, or
     * {@link Integer#MIN_VALUE} if the column has none in range.
     *
     * <p>Needed because a vein's y was drawn from the ore's whole band, which for the
     * deep-water provinces is mostly open water -- emerald spans y 4..120 and the
     * seabed can sit far below that, so a vein would start mid-ocean, random-walk
     * through water finding no host rock, and place nothing. Offshore deposits would
     * have silently under-delivered. Also stops land veins starting in open air.
     */
    private static int topHostRock(ChunkSnapshot snap, int lx, int lz, int lo, int hi) {
        for (int y = hi; y >= lo; y--) {
            if (isHostRock(snap.getBlockType(lx, y, lz))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Grow one blob of ore from the given column, replacing only natural host rock. */
    private void placeVein(World w, Ore ore, int startX, int startZ,
                           int lo, int hi, int size, Random rng,
                           ChunkSnapshot snap, boolean motherlode) {
        int baseX = startX & ~15;
        int baseZ = startZ & ~15;
        int x = startX;
        int z = startZ;
        int top = topHostRock(snap, x - baseX, z - baseZ, lo, hi);
        if (top == Integer.MIN_VALUE) {
            return;                       // column is all water/air in this ore's band
        }
        // Motherlodes sit well inside the rock rather than skimming its surface, so
        // the payoff is reached by digging down rather than by stumbling over it.
        int y = motherlode
                ? lo + (int) Math.round((top - lo) * (0.25 + rng.nextDouble() * 0.4))
                : lo + rng.nextInt(top - lo + 1);
        int placed = 0;
        for (int step = 0; step < size * 4 && placed < size; step++) {
            // Keep strictly inside this chunk so we never touch (and force-load) a
            // neighbour that may not be generated yet.
            if (x >= baseX && x < baseX + 16 && z >= baseZ && z < baseZ + 16
                    && y >= lo && y <= hi) {
                Block b = w.getBlockAt(x, y, z);
                if (isHostRock(b.getType())) {
                    b.setType(oreFor(ore, b.getType()), false);
                    placed++;
                }
            }
            x += rng.nextInt(3) - 1;
            y += rng.nextInt(3) - 1;
            z += rng.nextInt(3) - 1;
        }
    }
}
