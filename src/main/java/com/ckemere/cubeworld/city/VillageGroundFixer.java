package com.ckemere.cubeworld.city;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.generation.EarthMapSpec;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Gives the anchored city villages dry, continuous ground to stand on.
 *
 * <p>{@code VillageAnchorHook} forces a village at each historical coordinate,
 * bypassing the site checks vanilla uses to avoid bad ground, and several of the
 * 30 are genuine ports and river cities. Measured on a clean regen, ~13% of
 * Antioch's village columns stood over water and ~15% were stilted.
 *
 * <p>Reshaping the terrain cannot fix that: a coastal city's village legitimately
 * sprawls over its bay, and lifting the seabed there would destroy the harbour and
 * the gentle coastline. So the terrain is left alone and the ground is repaired
 * AFTER the structure exists — the same "build on chunk load" pattern the teleport
 * stations use.
 *
 * <p>The footprint is DILATED before filling. Filling only the columns that carry
 * a village block would leave the gaps between buildings — the paths and yards
 * villagers actually walk on — as open water, which is precisely where they would
 * drown.
 *
 * <p>Only large bodies of water are filled. A village well or a decorative pond is
 * a couple of blocks across; the sea and a river are not. The 5x5 neighbour test
 * keeps wells intact while still closing a bay or a channel.
 */
public final class VillageGroundFixer implements Listener {

    /** Blocks of dilation around village material, so paths and yards are covered. */
    private static final int DILATE = 4;
    /** A water body at least this many of its 5x5 neighbours is "large" (sea, river,
     * lake) rather than a well or a decorative pool. */
    private static final int LARGE_BODY = 16;
    /** How far below the walking surface to keep filling before giving up. */
    private static final int MAX_FILL_DEPTH = 24;

    /** Blocks a jigsaw village is built from. Deliberately excludes materials that
     * also occur naturally in bulk (plain sandstone, terracotta, smooth stone),
     * because a false positive here would fill natural ground. */
    private static boolean isVillageBlock(Material m) {
        return switch (m) {
            case COBBLESTONE, MOSSY_COBBLESTONE, COBBLESTONE_STAIRS, COBBLESTONE_SLAB,
                 OAK_PLANKS, SPRUCE_PLANKS, BIRCH_PLANKS, ACACIA_PLANKS, JUNGLE_PLANKS,
                 OAK_STAIRS, SPRUCE_STAIRS, BIRCH_STAIRS, ACACIA_STAIRS,
                 OAK_LOG, SPRUCE_LOG, BIRCH_LOG, ACACIA_LOG,
                 STRIPPED_OAK_LOG, STRIPPED_SPRUCE_LOG, STRIPPED_BIRCH_LOG,
                 OAK_FENCE, SPRUCE_FENCE, BIRCH_FENCE, ACACIA_FENCE,
                 DIRT_PATH, HAY_BLOCK, BOOKSHELF, CRAFTING_TABLE, COMPOSTER,
                 BELL, LANTERN, GLASS_PANE, FARMLAND -> true;
            default -> false;
        };
    }

    /** Chunks repaired per tick. City seeding force-loads thousands of chunks at
     * once, and scheduling every repair with runTask() put them all on ONE tick --
     * millions of block operations at once. Throttled, but not too hard: at 2/tick
     * the queue drained slower than seeding force-loaded, so chunks unloaded before
     * they were repaired and the fill silently did nothing. Measured cost is only
     * ~500 blocks per chunk, so 10/tick is comfortable. */
    private static final int PER_TICK = 10;

    private final CubeWorldPlugin plugin;
    private final double radius;
    private final java.util.ArrayDeque<Chunk> queue = new java.util.ArrayDeque<>();
    private int fixed;
    private int chunks;
    private int seen;
    private int nearHits;

    public VillageGroundFixer(CubeWorldPlugin plugin, double radius) {
        this.plugin = plugin;
        this.radius = radius;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 20L, 1L);
        plugin.getLogger().info("VillageGroundFixer: " + plugin.cityAnchors().size()
                + " anchors, radius " + (int) radius);
    }

    private void drain() {
        for (int i = 0; i < PER_TICK; i++) {
            Chunk c;
            synchronized (queue) {
                c = queue.poll();
            }
            if (c == null) {
                return;
            }
            if (c.isLoaded()) {
                try {
                    int n = fix(c);
                    if (n > 0) {
                        fixed += n;
                        if (++chunks % 25 == 0) {
                            plugin.getLogger().info("VillageGroundFixer: " + chunks
                                    + " chunks, " + fixed + " blocks filled");
                        }
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("VillageGroundFixer: " + ex);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!e.isNewChunk() || !plugin.isCubeWorld(e.getWorld())) {
            return;
        }
        Chunk c = e.getChunk();
        seen++;
        if (!nearCity(c.getX() << 4, c.getZ() << 4)) {
            return;
        }
        if (++nearHits % 100 == 0) {
            plugin.getLogger().info("VillageGroundFixer: queued " + nearHits
                    + " city chunks (of " + seen + " new)");
        }
        // Queue rather than schedule: see PER_TICK.
        synchronized (queue) {
            queue.add(c);
        }
    }

    private boolean nearCity(int bx, int bz) {
        for (double[] a : plugin.cityAnchors()) {
            double dx = bx + 8 - a[0];
            double dz = bz + 8 - a[1];
            if (dx * dx + dz * dz <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    /** Fill water and voids under the village footprint so nothing floats and
     * nothing can walk into deep water. */
    private int fix(Chunk c) {
        World w = c.getWorld();
        int ox = c.getX() << 4;
        int oz = c.getZ() << 4;
        int top = w.getMaxHeight() - 1;

        // 1. mark columns carrying village material, with their surface height
        int[][] vy = new int[16][16];
        boolean any = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                vy[lx][lz] = Integer.MIN_VALUE;
                int y = w.getHighestBlockYAt(ox + lx, oz + lz);
                for (int probe = y; probe > y - 8 && probe > w.getMinHeight(); probe--) {
                    if (isVillageBlock(w.getBlockAt(ox + lx, probe, oz + lz).getType())) {
                        vy[lx][lz] = probe;
                        any = true;
                        break;
                    }
                }
            }
        }
        if (!any) {
            return 0;
        }

        // 2. dilate the FOOTPRINT ONLY (a boolean mask). The previous version
        // dilated the village-block HEIGHT and took the max, which for a column
        // beside a house is its ROOF -- then filled air downward from there and
        // buried the buildings in dirt. Height must never propagate sideways.
        boolean[][] near = new boolean[16][16];
        boolean[][] tmp = new boolean[16][16];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                boolean hit = false;
                for (int dx = -DILATE; dx <= DILATE && !hit; dx++) {
                    int nx = lx + dx;
                    hit = nx >= 0 && nx < 16 && vy[nx][lz] != Integer.MIN_VALUE;
                }
                tmp[lx][lz] = hit;
            }
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                boolean hit = false;
                for (int dz = -DILATE; dz <= DILATE && !hit; dz++) {
                    int nz = lz + dz;
                    hit = nz >= 0 && nz < 16 && tmp[lx][nz];
                }
                near[lx][lz] = hit;
            }
        }

        // 3. Two narrow repairs, neither of which may touch open air:
        //    (a) a large water body inside the footprint is filled from its own
        //        surface downward, turning the bay into ground at water level;
        //    (b) a void DIRECTLY under a village block is filled, so nothing is
        //        left stilted. Air above the local surface is never touched.
        int filledHere = 0;
        int seaTop = EarthMapSpec.SEA_LEVEL;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (!near[lx][lz]) {
                    continue;
                }
                int x = ox + lx;
                int z = oz + lz;

                // (a) water surface within the footprint
                int wtop = Integer.MIN_VALUE;
                for (int y = seaTop + 6; y > seaTop - MAX_FILL_DEPTH; y--) {
                    if (w.getBlockAt(x, y, z).getType() == Material.WATER) {
                        wtop = y;
                        break;
                    }
                }
                if (wtop != Integer.MIN_VALUE && largeBody(w, x, wtop, z)) {
                    Material fill = groundFor(w.getBiome(x, wtop, z));
                    for (int y = wtop, d = 0; y > w.getMinHeight() && d < MAX_FILL_DEPTH;
                            y--, d++) {
                        Block b = w.getBlockAt(x, y, z);
                        Material m = b.getType();
                        if (m == Material.WATER || m.isAir()) {
                            b.setType(fill, false);
                            filledHere++;
                        } else {
                            break;
                        }
                    }
                }

                // (b) void directly beneath a village block in THIS column only
                int v = vy[lx][lz];
                if (v != Integer.MIN_VALUE) {
                    Material fill = groundFor(w.getBiome(x, v, z));
                    for (int y = v - 1, d = 0; y > w.getMinHeight() && d < 12; y--, d++) {
                        Block b = w.getBlockAt(x, y, z);
                        Material m = b.getType();
                        if (m.isAir() || m == Material.WATER) {
                            b.setType(fill, false);
                            filledHere++;
                        } else {
                            break;
                        }
                    }
                }
            }
        }
        return filledHere;
    }

    /** True if (x,y,z) belongs to a large water body rather than a well or pond. */
    private static boolean largeBody(World w, int x, int y, int z) {
        int n = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (w.getBlockAt(x + dx, y, z + dz).getType() == Material.WATER) {
                    n++;
                }
            }
        }
        return n >= LARGE_BODY;
    }

    private static Material groundFor(Biome b) {
        String k = b.getKey().getKey();
        if (k.contains("desert") || k.contains("badlands")) {
            return Material.SANDSTONE;
        }
        if (k.contains("beach") || k.contains("mangrove")) {
            return Material.SAND;
        }
        if (k.contains("snowy") || k.contains("frozen") || k.contains("grove")) {
            return Material.STONE;
        }
        return Material.DIRT;
    }
}
