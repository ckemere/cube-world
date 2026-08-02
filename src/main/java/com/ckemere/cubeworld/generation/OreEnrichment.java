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
import org.bukkit.Chunk;
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

    /** Per-ore province centres in world blocks; built lazily once the world is up. */
    private Map<Ore, List<int[]>> centres;

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
        Map<Ore, List<int[]>> out = new EnumMap<>(Ore.class);
        for (var entry : OreDeposits.PROVINCES.entrySet()) {
            List<int[]> pts = new ArrayList<>();
            for (double[] ll : entry.getValue()) {
                int[] xz = worldOf(earth, sampler, geom, ll[0], ll[1]);
                if (xz != null) {
                    pts.add(xz);
                }
            }
            out.put(entry.getKey(), pts);
        }
        // Test province: gold right at spawn (Ethiopia), so it can be checked without
        // hunting. This one is intentionally known; the rest are quiet.
        out.get(Ore.GOLD).add(new int[] {11973, -856});
        centres = out;
        int total = out.values().stream().mapToInt(List::size).sum();
        plugin.getLogger().info("OreEnrichment: primed " + total
                + " ore provinces (radius " + radius + ", freq x" + freqMult
                + ", size x" + sizeMult + ")");
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

    /**
     * Enrichment strength (0..1) for an ore at a world (x,z): 1 at the nearest
     * province centre, tapering linearly to 0 at {@link #radius}. Public so a probe
     * command can report it.
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
            double dx = wx - c[0];
            double dz = wz - c[1];
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < radius) {
                best = Math.max(best, 1.0 - d / radius);
            }
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

        // Count the chunk's own vanilla ore so the boost is proportional to what the
        // generator actually placed here (baselines vary wildly per ore and biome).
        int[] vanilla = countVanillaOre(w, cx, cz);

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
            int veins = (int) Math.round(addedVeins);
            if (veins <= 0) {
                continue;
            }
            int size = Math.max(2, (int) Math.round(ore.nominalVeinSize * sizeMult));
            int lo = Math.max(minY, ore.yMin);
            int hi = Math.min(maxY, ore.yMax);
            if (hi <= lo) {
                continue;
            }
            for (int k = 0; k < veins; k++) {
                placeVein(w, ore, baseX, baseZ, lo, hi, size, rng);
                any = true;
            }
        }
        if (any && (++enrichedChunks % 200 == 0)) {
            plugin.getLogger().info("OreEnrichment: enriched " + enrichedChunks + " chunks");
        }
    }

    /** Tally each ore's existing (vanilla-placed) blocks in the chunk, by ore index. */
    private int[] countVanillaOre(World w, int cx, int cz) {
        int[] out = new int[Ore.values().length];
        Chunk c = w.getChunkAt(cx, cz);
        ChunkSnapshot snap = c.getChunkSnapshot(false, false, false);
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

    /** Grow one blob of ore from a random point, replacing only natural host rock. */
    private void placeVein(World w, Ore ore, int baseX, int baseZ,
                           int lo, int hi, int size, Random rng) {
        int x = baseX + rng.nextInt(16);
        int z = baseZ + rng.nextInt(16);
        int y = lo + rng.nextInt(hi - lo + 1);
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
