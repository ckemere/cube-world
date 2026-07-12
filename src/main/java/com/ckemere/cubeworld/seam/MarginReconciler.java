package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.MarginSource;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * The guarantee behind the mirrors. Event hooks give low-latency sync but can
 * never be complete — generation-time decorations, plugin writes, and any
 * missed event leave margins stale. This reconciler continuously sweeps
 * loaded margin chunks, comparing every column block-for-block against its
 * rotated source and repairing differences, so margins converge on truth no
 * matter what caused the change. Freshly loaded margin chunks jump the queue.
 */
public final class MarginReconciler implements Listener {

    /** Columns repaired per tick (each column spans the full world height). */
    private static final int COLUMNS_PER_TICK = 24;

    private final CubeTopology topology;
    private final int marginBlocks;
    private final Set<Long> loadedMarginChunks = new LinkedHashSet<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private long currentChunk;
    private int cursor = -1;

    public MarginReconciler(CubeTopology topology, int marginBlocks) {
        this.topology = topology;
        this.marginBlocks = marginBlocks;
    }

    public void bootstrap(World world) {
        for (Chunk chunk : world.getLoadedChunks()) {
            noteLoaded(chunk);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        noteLoaded(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        loadedMarginChunks.remove(key(event.getChunk().getX(), event.getChunk().getZ()));
    }

    private void noteLoaded(Chunk chunk) {
        if (isMarginChunk(chunk.getX(), chunk.getZ()) && loadedMarginChunks.add(key(chunk.getX(), chunk.getZ()))) {
            queue.addFirst(key(chunk.getX(), chunk.getZ()));
        }
    }

    private boolean isMarginChunk(int chunkX, int chunkZ) {
        int wx = chunkX << 4;
        int wz = chunkZ << 4;
        for (int[] probe : new int[][] {{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}}) {
            double x = wx + probe[0] + 0.5;
            double z = wz + probe[1] + 0.5;
            if (topology.geometry().faceAt((int) Math.floor(x), (int) Math.floor(z)) == null
                    && topology.marginSource(x, z, marginBlocks) != null) {
                return true;
            }
        }
        return false;
    }

    /** Runs every tick with a fixed column budget. */
    public void tick(World world) {
        int budget = COLUMNS_PER_TICK;
        while (budget > 0) {
            if (cursor < 0) {
                Long next = queue.pollFirst();
                while (next != null && !loadedMarginChunks.contains(next)) {
                    next = queue.pollFirst();
                }
                if (next == null) {
                    if (loadedMarginChunks.isEmpty()) {
                        return;
                    }
                    queue.addAll(loadedMarginChunks);
                    next = queue.pollFirst();
                }
                currentChunk = next;
                cursor = 0;
            }
            int chunkX = (int) (currentChunk >> 32);
            int chunkZ = (int) (long) currentChunk;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                cursor = -1;
                continue;
            }
            while (cursor < 256 && budget > 0) {
                syncColumn(world, (chunkX << 4) + (cursor & 15), (chunkZ << 4) + (cursor >> 4));
                cursor++;
                budget--;
            }
            if (cursor >= 256) {
                cursor = -1; // chunk complete; it re-enters via the refill cycle
            }
        }
    }

    /** Make one margin column exactly match its rotated source column. */
    private void syncColumn(World world, int wx, int wz) {
        double cx = wx + 0.5;
        double cz = wz + 0.5;
        if (topology.geometry().faceAt(wx, wz) != null
                || topology.inPillar(cx, cz, marginBlocks)) {
            return;
        }
        MarginSource source = topology.marginSource(cx, cz, marginBlocks);
        if (source == null) {
            return;
        }
        int sx = (int) Math.floor(source.source().x());
        int sz = (int) Math.floor(source.source().z());
        if (!world.isChunkLoaded(sx >> 4, sz >> 4)) {
            return;
        }
        // Data flows source -> margin: rotate by the inverse of margin->source.
        StructureRotation rotation = MirrorService.structureRotation(
                4 - source.toSource().quarterTurns());
        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            Block sourceBlock = world.getBlockAt(sx, y, sz);
            Block marginBlock = world.getBlockAt(wx, y, wz);
            BlockData want = sourceBlock.getBlockData();
            if (rotation != StructureRotation.NONE) {
                want = want.clone();
                want.rotate(rotation);
            }
            if (!marginBlock.getBlockData().equals(want)) {
                marginBlock.setBlockData(want, false);
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
