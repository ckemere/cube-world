package com.ckemere.cubeworld.city;

import com.ckemere.cubeworld.CubeWorldPlugin;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Fills the air under village floors so nothing is left standing on stilts.
 *
 * <p>Works from vanilla's own structure data rather than guessing at block
 * materials. A village is a jigsaw of named template pieces, and the pools
 * separate exactly along the line that matters here:
 *
 * <pre>
 *   village/&lt;style&gt;/houses        houses AND workshops   -&gt; fill
 *   village/&lt;style&gt;/streets       paths villagers walk   -&gt; fill
 *   village/&lt;style&gt;/town_centers  the well / plaza       -&gt; fill
 *   village/&lt;style&gt;/trees         trees                  -&gt; leave alone
 *   village/&lt;style&gt;/decor, terminators, villagers, zombie -&gt; leave alone
 * </pre>
 *
 * <p>For each qualifying piece the floor is the minimum Y of that piece's solid
 * blocks taken over the WHOLE piece. That single number is the crux: earlier
 * versions took a per-column minimum, and a column holding only a roof overhang
 * has its roof as that minimum, so the fill started just under the eave and packed
 * the room below it with dirt. A whole-piece minimum cannot reach a roof, because
 * the roof is by definition above the floor.
 *
 * <p>Only air strictly below the floor is filled, out to the piece footprint
 * expanded by one block in X and Z, and the fill copies whatever solid block the
 * column lands on so the plinth matches the ground it grows from.
 */
public final class VillageGroundFixer implements Listener {

    /** Template pool paths whose pieces get a floor. */
    private static final String[] FILL_POOLS = {"/houses/", "/streets/", "/town_centers/"};

    /** Footprint is grown by this many blocks in X and Z. */
    private static final int MARGIN = 1;

    /** Give up after this many blocks of air below a floor. */
    private static final int MAX_DEPTH = 24;

    /** Pieces repaired per tick. */
    private static final int PER_TICK = 4;

    private static final Field ELEMENT_FIELD;

    static {
        Field f = null;
        try {
            f = PoolElementStructurePiece.class.getDeclaredField("element");
            f.setAccessible(true);
        } catch (Exception ignored) {
            f = null;               // without it we cannot tell houses from trees
        }
        ELEMENT_FIELD = f;
    }

    private final CubeWorldPlugin plugin;
    private final java.util.ArrayDeque<Chunk> queue = new java.util.ArrayDeque<>();
    /** Pieces already handled, keyed by their bounding-box minimum corner. */
    private final Set<Long> done = new HashSet<>();
    private int pieces;
    private int filled;

    public VillageGroundFixer(CubeWorldPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 20L, 1L);
        if (ELEMENT_FIELD == null) {
            plugin.getLogger().warning(
                    "VillageGroundFixer: cannot read jigsaw template names; disabled.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!plugin.isCubeWorld(e.getWorld())
                || e.getWorld().getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        synchronized (queue) {
            queue.add(e.getChunk());
        }
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
                    scanChunk(c);
                } catch (Exception ex) {
                    plugin.getLogger().warning("VillageGroundFixer: " + ex);
                }
            }
        }
    }

    /** Find village pieces overlapping this chunk and floor any not yet done. */
    private void scanChunk(Chunk c) {
        if (ELEMENT_FIELD == null) {
            return;
        }
        World bw = c.getWorld();
        ServerLevel level = ((CraftWorld) bw).getHandle();
        // Accept every structure start in the chunk and let the template-pool filter
        // decide. Matching on the structure's own name would not work: the historical
        // cities are anchored as cubeworld:plains_large, desert_huge and so on, which
        // carry no "village" in their id even though their start_pool -- and therefore
        // every piece they place -- is minecraft:village/<style>/*.
        java.util.List<StructureStart> starts = level.structureManager()
                .startsForStructure(new ChunkPos(c.getX(), c.getZ()), st -> true);
        for (StructureStart start : starts) {
            for (StructurePiece piece : start.getPieces()) {
                if (!wanted(piece)) {
                    continue;
                }
                BoundingBox bb = piece.getBoundingBox();
                long id = (((long) bb.minX()) << 40) ^ (((long) bb.minY()) << 20) ^ bb.minZ();
                if (done.contains(id)) {
                    continue;
                }
                if (!boxLoaded(bw, bb)) {
                    continue;       // retry when the rest of it loads
                }
                done.add(id);
                floorPiece(bw, bb);
                pieces++;
                if (pieces % 100 == 0) {
                    plugin.getLogger().info("VillageGroundFixer: " + pieces
                            + " pieces, " + filled + " blocks");
                }
            }
        }
    }

    /** Houses, workshops, streets and the town centre; never trees or decor. */
    private static boolean wanted(StructurePiece piece) {
        if (!(piece instanceof PoolElementStructurePiece pe)) {
            return false;
        }
        String tpl;
        try {
            tpl = String.valueOf(ELEMENT_FIELD.get(pe));
        } catch (Exception e) {
            return false;
        }
        for (String p : FILL_POOLS) {
            if (tpl.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean boxLoaded(World w, BoundingBox bb) {
        for (int cx = (bb.minX() - MARGIN) >> 4; cx <= (bb.maxX() + MARGIN) >> 4; cx++) {
            for (int cz = (bb.minZ() - MARGIN) >> 4; cz <= (bb.maxZ() + MARGIN) >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Floor one building: minimum Y of its solid blocks over the WHOLE piece, then
     * fill air below that across the footprint plus a one-block margin.
     */
    private void floorPiece(World w, BoundingBox bb) {
        int floor = Integer.MAX_VALUE;
        outer:
        for (int y = bb.minY(); y <= bb.maxY(); y++) {
            for (int x = bb.minX(); x <= bb.maxX(); x++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    if (w.getBlockAt(x, y, z).getType().isSolid()) {
                        floor = y;
                        break outer;
                    }
                }
            }
        }
        if (floor == Integer.MAX_VALUE) {
            return;
        }
        for (int x = bb.minX() - MARGIN; x <= bb.maxX() + MARGIN; x++) {
            for (int z = bb.minZ() - MARGIN; z <= bb.maxZ() + MARGIN; z++) {
                fillColumn(w, x, z, floor);
            }
        }
    }

    /** Fill the air gap between {@code floor} and the first solid block below it,
     * copying that block's material so the plinth matches the surrounding ground. */
    private void fillColumn(World w, int x, int z, int floor) {
        int y = floor - 1;
        int gap = 0;
        while (y > w.getMinHeight() && gap < MAX_DEPTH) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (m.isSolid()) {
                break;
            }
            if (m != Material.AIR && m != Material.CAVE_AIR && m != Material.WATER) {
                break;              // vegetation or another fluid: leave it
            }
            gap++;
            y--;
        }
        if (gap == 0) {
            return;                 // already supported
        }
        Material fill = w.getBlockAt(x, Math.max(y, w.getMinHeight()), z).getType();
        if (!fill.isSolid()) {
            fill = Material.DIRT;
        }
        for (int fy = y + 1; fy <= floor - 1; fy++) {
            w.getBlockAt(x, fy, z).setType(fill, false);
            filled++;
        }
    }
}
