package com.ckemere.cubeworld.city;

import com.ckemere.cubeworld.CubeWorldPlugin;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.AsyncStructureGenerateEvent;
import org.bukkit.generator.LimitedRegion;

/**
 * Records every block a structure places, in true engine order, without changing any
 * of them — the observation half of what {@link VillageFoundationTransformer} does.
 *
 * <p>Hooked on {@link AsyncStructureGenerateEvent}, which (verified in
 * {@code StructureStart.placeInChunk}) fires ONCE PER CHUNK a structure intersects:
 * the pieces overlapping that chunk are placed in jigsaw order, and within a piece
 * blocks arrive in template palette order. So the callback sequence within one event
 * IS the placement order, and a whole village is the union of its per-chunk events.
 *
 * <p>Output is CSV (append) at {@code plugins/CubeWorld/placewatch.csv}:
 *
 * <pre>
 *   E,&lt;event&gt;,&lt;structureKey&gt;,&lt;chunkX&gt;,&lt;chunkZ&gt;,&lt;cause&gt;,&lt;world&gt;
 *   B,&lt;event&gt;,&lt;seq&gt;,&lt;x&gt;,&lt;y&gt;,&lt;z&gt;,&lt;placed&gt;,&lt;replaced&gt;,&lt;gapBelow&gt;,&lt;waterBelow&gt;,&lt;ground&gt;
 * </pre>
 *
 * <p>{@code gapBelow} counts non-solid blocks under (x,y,z) at the moment of placement
 * (capped at {@link #SCAN_DEPTH}), {@code waterBelow} how many of those are water, and
 * {@code ground} the first solid block met, {@code ?} if none within the cap or the
 * column leaves the writable region. Nothing is filtered here: air the template stamps,
 * eaves, fences all appear — which block SHOULD have ground under it is an analysis
 * question, and answering it at record time would bake a hypothesis into the data.
 *
 * <p>Piece attribution is deliberately absent: the callback does not know pieces, and
 * the saved structure NBT (piece boxes + templates in placement order) recovers it
 * offline without guessing. Enabled with {@code -Dcubeworld.placeWatch=true}; default
 * off because it writes a line per structure block for every structure in every
 * generated chunk.
 */
public final class VillagePlacementWatcher implements Listener {

    /** How far down a column is probed for ground before writing {@code ?}. */
    private static final int SCAN_DEPTH = 40;

    private final CubeWorldPlugin plugin;
    private final NamespacedKey key;
    private final AtomicLong events = new AtomicLong();

    private final boolean enabled =
            "true".equalsIgnoreCase(System.getProperty("cubeworld.placeWatch", "false"));

    /** The plugin registers the listener only when this is true. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Guarded by {@code this}; lines from concurrent generation threads interleave,
     * which is fine because every line carries its event id. */
    private BufferedWriter out;
    private long linesSinceFlush;

    public VillagePlacementWatcher(CubeWorldPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "place_watch");
        if (enabled) {
            plugin.getLogger().info("PlaceWatch: recording structure placement to "
                    + new File(plugin.getDataFolder(), "placewatch.csv"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGenerate(AsyncStructureGenerateEvent event) {
        if (!enabled || !plugin.isCubeWorld(event.getWorld())) {
            return;
        }
        long id = events.incrementAndGet();
        write("E," + id + "," + org.bukkit.Registry.STRUCTURE.getKeyOrThrow(event.getStructure())
                + "," + event.getChunkX() + "," + event.getChunkZ()
                + "," + event.getCause() + "," + event.getWorld().getName());
        AtomicLong seq = new AtomicLong();
        event.setBlockTransformer(key, (region, x, y, z, state, ts) -> {
            record(region, x, y, z, state, id, seq.incrementAndGet());
            return state;
        });
    }

    /** The block is NOT yet placed when the transformer runs (verified in
     * {@code StructureTemplate.placeInWorld}: transform precedes setBlock), so reading
     * the region at (x,y,z) yields the block being replaced. */
    private void record(LimitedRegion region, int x, int y, int z, BlockState state,
                        long event, long seq) {
        Material replaced = region.isInRegion(x, y, z)
                ? region.getType(x, y, z) : null;
        int gap = 0;
        int water = 0;
        Material ground = null;
        int minY = region.getWorld().getMinHeight();
        for (int yy = y - 1; yy > minY && gap < SCAN_DEPTH; yy--) {
            if (!region.isInRegion(x, yy, z)) {
                break;
            }
            Material m = region.getType(x, yy, z);
            if (m.isSolid()) {
                ground = m;
                break;
            }
            if (m == Material.WATER) {
                water++;
            }
            gap++;
        }
        write("B," + event + "," + seq + "," + x + "," + y + "," + z
                + "," + state.getType().name()
                + "," + (replaced == null ? "?" : replaced.name())
                + "," + gap + "," + water
                + "," + (ground == null ? "?" : ground.name()));
    }

    private synchronized void write(String line) {
        try {
            if (out == null) {
                plugin.getDataFolder().mkdirs();
                out = new BufferedWriter(new FileWriter(
                        new File(plugin.getDataFolder(), "placewatch.csv"), true));
                out.write("# session " + java.time.Instant.now());
                out.newLine();
            }
            out.write(line);
            out.newLine();
            // Periodic flush for crash durability: a generation crash mid-city
            // otherwise loses the very blocks being investigated.
            if (++linesSinceFlush >= 500) {
                out.flush();
                linesSinceFlush = 0;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("PlaceWatch: " + e);
        }
    }

    /** Flush and close on plugin disable so the tail of the stream is not lost. */
    public synchronized void close() {
        if (out != null) {
            try {
                out.flush();
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
    }
}
