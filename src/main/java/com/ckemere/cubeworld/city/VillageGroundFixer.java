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
import org.bukkit.block.Block;
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
 * <p>Only air strictly below the floor is filled, and the fill copies whatever solid
 * block the column lands on so the plinth matches the ground it grows from. Buildings
 * are filled one block past their footprint so no wall stands on the very edge of its
 * plinth; streets are filled to their footprint exactly, because a street box already
 * runs down to the shore and a skirt there spills dirt out over the water.
 */
public final class VillageGroundFixer implements Listener {

    /** Buildings: fill the footprint plus a one-block skirt, so no wall sits on an edge. */
    private static final String[] BUILDING_POOLS = {"/houses/", "/town_centers/"};

    /** Streets: fill the footprint exactly. A skirt here spills the fill sideways off
     * the path -- a street bounding box already reaches the shore, and the extra ring
     * pushes dirt out over open water. */
    private static final String[] STREET_POOLS = {"/streets/"};

    /** Pieces this fixer does not touch are marked with this margin. */
    private static final int SKIP = -1;

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

    /**
     * DEFAULT OFF as of the factor-relaxation work.
     *
     * <p>This fixer was never a village feature: it is compensation for terrain that
     * cannot be moved. {@code factor} is pinned to 64 wherever the freeboard rule bites,
     * which is hardest on flat low ground, and villages need flat ground -- so every city
     * lands where vanilla's Beardifier has almost no authority (0.43 blocks of
     * near-surface zone, 0.50 blocks of noise, measured at Antioch). Something then had
     * to plinth the buildings by hand, and what it produced was worse than what it fixed:
     * substrate pillars up to MAX_DEPTH tall standing in open air under tree trunks.
     *
     * <p>SphereDensity now relaxes the factor cap inside a city footprint so the beard can
     * do the job properly. That is NOT yet proven to remove the need for this -- the
     * pinned-vs-relaxed comparison was never completed on equal footing -- so the code is
     * kept and can be switched back on with -Dcubeworld.villageFix=true while that is
     * settled. It is off by default because leaving it on masks the very thing the next
     * measurement has to see.
     */
    private final boolean enabled =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.villageFix", "false"));

    /** Also CARVE away terrain that rises above a piece and buries it (uphill burial
     * on steep city sites -- "dirt walls around the walls"). -Dcubeworld.villageCarve=false
     * disables just the carve while keeping the fill. */
    private final boolean carve =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.villageCarve", "true"));

    /** How far up a buried column to clear terrain before giving up (steep sites). */
    private static final int CARVE_MAX = 48;

    public VillageGroundFixer(CubeWorldPlugin plugin) {
        this.plugin = plugin;
        if (!enabled) {
            plugin.getLogger().info("VillageGroundFixer: DISABLED via -Dcubeworld.villageFix=false");
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 20L, 1L);
        if (ELEMENT_FIELD == null) {
            plugin.getLogger().warning(
                    "VillageGroundFixer: cannot read jigsaw template names; disabled.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!enabled
                || !plugin.isCubeWorld(e.getWorld())
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
                int margin = marginFor(piece);
                boolean tree = margin == SKIP && isTreePiece(piece);
                if (margin == SKIP && !tree) {
                    continue;
                }
                BoundingBox bb = piece.getBoundingBox();
                long id = (((long) bb.minX()) << 40) ^ (((long) bb.minY()) << 20) ^ bb.minZ();
                if (done.contains(id)) {
                    continue;
                }
                if (!boxLoaded(bw, bb, Math.max(margin, 0))) {
                    continue;       // retry when the rest of it loads
                }
                done.add(id);
                if (tree) {
                    floorTree(bw, bb);
                    if (carve) {
                        deburyTree(bw, bb);
                    }
                } else {
                    floorPiece(bw, bb, margin, margin == 0);
                    if (carve && margin > 0) {           // buildings only; streets step down slopes
                        deburyBuilding(bw, bb, margin);
                    }
                }
                pieces++;
                if (pieces % 100 == 0) {
                    plugin.getLogger().info("VillageGroundFixer: " + pieces
                            + " pieces, " + filled + " blocks");
                }
            }
        }
    }

    /**
     * How far past its footprint a piece is filled, or {@link #SKIP} to leave it alone.
     * Houses, workshops and the town centre get a one-block skirt; streets get none;
     * trees, decor, terminators and the rest are never touched.
     */
    private static int marginFor(StructurePiece piece) {
        if (!(piece instanceof PoolElementStructurePiece pe)) {
            return SKIP;
        }
        String tpl;
        try {
            tpl = String.valueOf(ELEMENT_FIELD.get(pe));
        } catch (Exception e) {
            return SKIP;
        }
        for (String p : BUILDING_POOLS) {
            if (tpl.contains(p)) {
                return 1;
            }
        }
        for (String p : STREET_POOLS) {
            if (tpl.contains(p)) {
                return 0;
            }
        }
        return SKIP;
    }

    /** Village tree pieces (village/&lt;style&gt;/trees/*). Skipped by {@link #marginFor}
     * because they must not be floor-filled like a building, but on a slope the
     * jigsaw drops them at a fixed Y and their trunk is left hanging in the air --
     * a floating tree. {@link #floorTree} supports just the trunk. */
    private static final String[] TREE_POOLS = {"/trees/"};

    private static boolean isTreePiece(StructurePiece piece) {
        if (!(piece instanceof PoolElementStructurePiece pe)) {
            return false;
        }
        String tpl;
        try {
            tpl = String.valueOf(ELEMENT_FIELD.get(pe));
        } catch (Exception e) {
            return false;
        }
        for (String p : TREE_POOLS) {
            if (tpl.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Support a tree without burying it. Fills the gap under ONLY the columns whose
     * lowest solid block is a trunk (a log/wood), so a floating tree gets a plinth
     * under its trunk while the air under its canopy is left as air. fillColumn
     * itself is a no-op where the trunk already meets the ground.
     */
    private void floorTree(World w, BoundingBox bb) {
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                int floor = lowestSolid(w, x, z, bb.minY(), bb.maxY());
                if (floor == Integer.MAX_VALUE) {
                    continue;
                }
                String base = w.getBlockAt(x, floor, z).getType().name();
                if (base.endsWith("_LOG") || base.endsWith("_WOOD") || base.endsWith("_STEM")) {
                    fillColumn(w, x, z, floor);
                }
            }
        }
    }

    /** Whole-piece minimum solid Y (the piece's base/floor level), or MAX_VALUE. */
    private static int pieceFloor(World w, BoundingBox bb) {
        for (int y = bb.minY(); y <= bb.maxY(); y++) {
            for (int x = bb.minX(); x <= bb.maxX(); x++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    if (w.getBlockAt(x, y, z).getType().isSolid()) {
                        return y;
                    }
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * The carve half of a custom "beard": free a building from terrain that buries it on
     * the uphill side by levelling the pad to the piece's base. {@link #floorPiece} has
     * already filled columns BELOW the floor (the support half); this clears natural
     * ground ABOVE it across the whole footprint plus a one-block skirt.
     *
     * <p>Unlike vanilla's Beardifier this has no radius/soft-falloff limit, so it reaches
     * arbitrarily steep ground. It is safe inside the footprint because {@link
     * #deburyColumn} only removes a <em>contiguous run of natural terrain</em> starting
     * just above the base and stops at the first structure block or air -- so a wall, a
     * plank floor, or the open interior halts the carve immediately; only dirt/stone
     * heaped against the build is removed.
     */
    private void deburyBuilding(World w, BoundingBox bb, int margin) {
        int floor = pieceFloor(w, bb);
        if (floor == Integer.MAX_VALUE) {
            return;
        }
        int m = margin + 1;                  // footprint plus a one-block blend skirt
        for (int x = bb.minX() - m; x <= bb.maxX() + m; x++) {
            for (int z = bb.minZ() - m; z <= bb.maxZ() + m; z++) {
                deburyColumn(w, x, z, floor + 1);
            }
        }
    }

    /**
     * Free a tree trunk from terrain heaped against it on a slope. For each trunk
     * column, clears natural ground from the trunk base upward in the eight neighbouring
     * columns (logs/leaves are never terrain, so an adjacent trunk is untouched).
     */
    private void deburyTree(World w, BoundingBox bb) {
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                int floor = lowestSolid(w, x, z, bb.minY(), bb.maxY());
                if (floor == Integer.MAX_VALUE) {
                    continue;
                }
                String base = w.getBlockAt(x, floor, z).getType().name();
                if (!(base.endsWith("_LOG") || base.endsWith("_WOOD") || base.endsWith("_STEM"))) {
                    continue;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        deburyColumn(w, x + dx, z + dz, floor + 1);
                    }
                }
            }
        }
    }

    /**
     * Clear the contiguous column of natural terrain starting at {@code fromY}, stopping
     * at the first air or non-terrain (structure) block so nothing is left floating and
     * no structure block is touched. Re-caps the block just below with grass if it is
     * bare dirt, so the cleared apron reads as ground.
     */
    private void deburyColumn(World w, int x, int z, int fromY) {
        boolean carved = false;
        for (int n = 0, y = fromY; n < CARVE_MAX; n++, y++) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (!isTerrain(m)) {
                break;                       // air or structure: top of the buried column
            }
            w.getBlockAt(x, y, z).setType(Material.AIR, false);
            carved = true;
        }
        if (carved) {
            Block below = w.getBlockAt(x, fromY - 1, z);
            if (below.getType() == Material.DIRT || below.getType() == Material.COARSE_DIRT) {
                below.setType(Material.GRASS_BLOCK, false);
            }
        }
    }

    /** Natural ground materials safe to carve away (whitelist: never structure blocks). */
    private static boolean isTerrain(Material m) {
        return switch (m) {
            case DIRT, COARSE_DIRT, ROOTED_DIRT, GRASS_BLOCK, PODZOL, MYCELIUM,
                 DIRT_PATH, MUD, MUDDY_MANGROVE_ROOTS, CLAY,
                 SAND, RED_SAND, GRAVEL, SNOW, SNOW_BLOCK, POWDER_SNOW,
                 STONE, DEEPSLATE, ANDESITE, DIORITE, GRANITE, TUFF, CALCITE -> true;
            default -> false;
        };
    }

    private static boolean boxLoaded(World w, BoundingBox bb, int margin) {
        for (int cx = (bb.minX() - margin) >> 4; cx <= (bb.maxX() + margin) >> 4; cx++) {
            for (int cz = (bb.minZ() - margin) >> 4; cz <= (bb.maxZ() + margin) >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Floor one piece, by one of two rules.
     *
     * <p>Buildings use the minimum Y of their solid blocks over the WHOLE piece. That is
     * what keeps fill out from under roofs: a per-column minimum in a column holding only
     * an eave takes the eave as its floor and packs the room beneath it.
     *
     * <p>Streets use a PER-COLUMN minimum instead. A street has no overhang to be fooled
     * by, and it is built to descend a slope in steps -- so applying one whole-piece
     * number to it paves over every step below the highest end and buries the path.
     */
    private void floorPiece(World w, BoundingBox bb, int margin, boolean perColumn) {
        if (perColumn) {
            for (int x = bb.minX(); x <= bb.maxX(); x++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    int floor = lowestSolid(w, x, z, bb.minY(), bb.maxY());
                    if (floor != Integer.MAX_VALUE) {
                        fillColumn(w, x, z, floor);
                    }
                }
            }
            return;
        }
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
        for (int x = bb.minX() - margin; x <= bb.maxX() + margin; x++) {
            for (int z = bb.minZ() - margin; z <= bb.maxZ() + margin; z++) {
                fillColumn(w, x, z, floor);
            }
        }
    }

    /** Lowest solid block in one column within the piece's Y span, or MAX_VALUE. */
    private static int lowestSolid(World w, int x, int z, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            if (w.getBlockAt(x, y, z).getType().isSolid()) {
                return y;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * What a plinth should be made of, given the block it grows from.
     *
     * <p>The block the column lands on is often the wrong thing to copy. Topsoil only
     * makes sense as a surface -- stacking {@code dirt_path} four high leaves a tower of
     * path, and grass buried under a village never sees light. Structure blocks are worse
     * still: landing on a street's stairs and copying them stacks stairs on stairs. Only
     * genuine ground materials are copied through; everything else becomes dirt.
     */
    private static Material substrate(Material m) {
        return switch (m) {
            case DIRT, COARSE_DIRT, SAND, RED_SAND, GRAVEL, CLAY,
                 SANDSTONE, RED_SANDSTONE, TERRACOTTA,
                 STONE, DEEPSLATE, ANDESITE, DIORITE, GRANITE, TUFF, CALCITE -> m;
            default -> Material.DIRT;
        };
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
        Material fill = substrate(w.getBlockAt(x, Math.max(y, w.getMinHeight()), z).getType());
        Material cap = surfaceCap(fill);
        for (int fy = y + 1; fy <= floor - 1; fy++) {
            // Cap the exposed top of the plinth with the natural surface block, so
            // a fill over grassy ground reads as a grass-topped bank instead of a
            // raw dirt wall. The top block under a house is hidden anyway; it is
            // the skirt ring and the downhill lip where this actually shows.
            w.getBlockAt(x, fy, z).setType(fy == floor - 1 ? cap : fill, false);
            filled++;
        }
    }

    /** Natural surface version of a fill block. Dirt -- the default for grass and
     * temperate ground -- becomes grass; sand/gravel/stone are already their own
     * surface, so a desert or stony shore stays as it is. */
    private static Material surfaceCap(Material sub) {
        return sub == Material.DIRT ? Material.GRASS_BLOCK : sub;
    }
}
