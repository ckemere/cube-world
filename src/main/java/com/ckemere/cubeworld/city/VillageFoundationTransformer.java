package com.ckemere.cubeworld.city;

import com.ckemere.cubeworld.CubeWorldPlugin;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Orientable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.AsyncStructureGenerateEvent;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.util.BlockTransformer;

/**
 * Supports village pieces <em>as the structure is generated</em>, instead of repairing
 * the world afterwards.
 *
 * <p>{@link VillageGroundFixer} runs off {@link org.bukkit.event.world.ChunkLoadEvent},
 * long after generation, and by then every useful fact is gone: it cannot tell a block
 * the jigsaw placed from one the terrain generator made, so it reconstructs that by
 * guessing — lowest-solid scans, a whitelist of "natural" materials, string-matching
 * template names out of a reflected private field. Wrong guesses compound, and the worst
 * of them plinthed a 24-block substrate pillar under every tree trunk column.
 *
 * <p>Here the hook is {@link AsyncStructureGenerateEvent}, which fires while the
 * structure is being written and hands us a {@link BlockTransformer}. The transformer is
 * called for each block the structure places, so a block's provenance is not inferred —
 * it is given. The {@link LimitedRegion} it receives is the sanctioned way to read and
 * write during async generation.
 *
 * <p>The rules are deliberately local. Each one extends something the structure already
 * contains, downward, until it meets ground:
 *
 * <pre>
 *   path block      (dirt path / gravel)  -&gt; fill with DIRT
 *   foundation      (full solid cube)     -&gt; repeat that same block
 *   trunk           (vertical log only)   -&gt; repeat that log, one column only
 *   stairs/slabs/…  (non-cube)            -&gt; NOT extended; counted as unsupported
 * </pre>
 *
 * <p>Nothing is removed and nothing is invented: no substrate guess, no grass cap, no
 * landscaping. A tree gets at most its own trunk column, never one per log.
 */
public final class VillageFoundationTransformer implements Listener {

    /** How far down a column will chase the ground before giving up. */
    private static final int MAX_DROP = 32;

    private final CubeWorldPlugin plugin;
    private final NamespacedKey key;

    /** -Dcubeworld.villageFoundation=false disables the generation-time support. */
    private final boolean enabled =
            !"false".equalsIgnoreCase(System.getProperty("cubeworld.villageFoundation", "false"));

    private final AtomicLong filled = new AtomicLong();
    private final AtomicLong unsupported = new AtomicLong();

    public VillageFoundationTransformer(CubeWorldPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "village_foundation");
        if (!enabled) {
            plugin.getLogger().info(
                    "VillageFoundation: DISABLED via -Dcubeworld.villageFoundation=false");
        }
    }

    public long filledBlocks() {
        return filled.get();
    }

    public long unsupportedColumns() {
        return unsupported.get();
    }

    private final AtomicLong events = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onStructureGenerate(AsyncStructureGenerateEvent event) {
        long n = events.incrementAndGet();
        // Log the first few unconditionally, BEFORE any filter. Built once without this
        // and could not tell "the event never fires" from "the event fires and my rules
        // never match" -- two very different bugs with identical symptoms.
        if (n <= 5) {
            plugin.getLogger().info("VillageFoundation: event #" + n
                    + " structure=" + event.getStructure().getKey()
                    + " cause=" + event.getCause()
                    + " world=" + event.getWorld().getName()
                    + " cube=" + plugin.isCubeWorld(event.getWorld())
                    + " enabled=" + enabled);
        }
        if (!enabled || !plugin.isCubeWorld(event.getWorld())) {
            return;
        }
        event.setBlockTransformer(key, this::transform);
    }

    /** Counters, so `/cubeworld villagefoundation` can say whether this ran at all. */
    public String stats() {
        return "events=" + events.get() + " transformCalls=" + calls.get()
                + " filled=" + filled.get() + " unsupported=" + unsupported.get()
                + " enabled=" + enabled;
    }

    /**
     * Called for every block the structure writes. We never change the block itself —
     * we return it untouched — and use the callback only as the moment at which we know
     * a structure block is landing at (x, y, z), so its column can be supported.
     */
    private BlockState transform(LimitedRegion region, int x, int y, int z,
                                 BlockState state, BlockTransformer.TransformationState ts) {
        calls.incrementAndGet();
        Material m = state.getType();
        Material fill = fillFor(region, x, y, z, m);
        if (fill != null) {
            support(region, x, y, z, fill);
        }
        return state;
    }

    /**
     * The material to extend downward for this block, or null to leave the column alone.
     * Only blocks that are supposed to meet the ground qualify.
     */
    private Material fillFor(LimitedRegion region, int x, int y, int z, Material m) {
        if (isPath(m)) {
            return Material.DIRT;               // a floating path becomes dirt beneath
        }
        if (isTrunk(region, x, y, z, m)) {
            return m;                           // vertical log: repeat the trunk
        }
        if (isFoundation(m)) {
            return m;                           // full cube: repeat the foundation
        }
        return null;                            // stairs, slabs, fences, plants, air…
    }

    /** Fill air (or water) below a structure block until it reaches something solid. */
    private void support(LimitedRegion region, int x, int y, int z, Material fill) {
        if (!region.isInRegion(x, y, z)) {
            return;
        }
        int gap = 0;
        int yy = y - 1;
        while (gap < MAX_DROP && yy > region.getWorld().getMinHeight()) {
            if (!region.isInRegion(x, yy, z)) {
                return;                          // outside the writable buffer: leave it
            }
            Material below = region.getType(x, yy, z);
            if (below.isSolid()) {
                break;                           // already supported
            }
            if (below != Material.AIR && below != Material.CAVE_AIR
                    && below != Material.WATER) {
                break;                           // vegetation or another fluid: stop
            }
            gap++;
            yy--;
        }
        if (gap == 0) {
            return;
        }
        if (gap >= MAX_DROP) {
            unsupported.incrementAndGet();       // nothing to stand on: leave it hanging
            return;
        }
        for (int fy = yy + 1; fy < y; fy++) {
            region.setType(x, fy, z, fill);
            filled.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ predicates

    /** Village path surfaces. */
    private static boolean isPath(Material m) {
        return m == Material.DIRT_PATH || m == Material.GRAVEL;
    }

    /**
     * A full solid cube is safe to repeat downward. Stairs, slabs, fences, doors, walls
     * and plants are not: repeating them builds a column of stairs, which is how the
     * naive form of this rule produced 90 stacked oak stairs under one house.
     */
    private static boolean isFoundation(Material m) {
        if (!m.isSolid() || !m.isOccluding()) {
            return false;
        }
        String n = m.name();
        return !(n.endsWith("_STAIRS") || n.endsWith("_SLAB") || n.endsWith("_FENCE")
                || n.endsWith("_GATE") || n.endsWith("_DOOR") || n.endsWith("_WALL")
                || n.endsWith("_SIGN") || n.endsWith("_BED"));
    }

    /**
     * A trunk is a log placed with its axis vertical. Horizontal logs are branches and
     * decorative beams; extending those downward would be exactly the "blocks around
     * trees" artifact this replaces.
     */
    private static boolean isTrunk(LimitedRegion region, int x, int y, int z, Material m) {
        String n = m.name();
        if (!(n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM"))) {
            return false;
        }
        return region.getBlockData(x, y, z) instanceof Orientable o && o.getAxis() == Axis.Y;
    }
}
