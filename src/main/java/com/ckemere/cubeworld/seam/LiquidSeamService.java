package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.MarginSource;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Owns the lifecycle of liquids that crossed a seam. Vanilla only drains
 * flowing liquid when a neighbor update triggers a re-check — and a
 * cross-seam liquid's feeder is on another face, so plugging the source
 * would leave the far side wet forever. Every forwarded liquid block is
 * watched: if its topological feeder across the seam stops supplying it (and
 * nothing local feeds it), it drains with physics on so vanilla cascades the
 * rest. Chunks loading near stitched edges are scanned so stale cross-seam
 * liquid from before a restart self-heals.
 */
public final class LiquidSeamService implements Listener {

    private final CubeTopology topology;
    private final MirrorService mirrors;
    private final World world;
    private final Set<Long> watched = new HashSet<>();

    public LiquidSeamService(CubeTopology topology, MirrorService mirrors, World world) {
        this.topology = topology;
        this.mirrors = mirrors;
        this.world = world;
    }

    /** Register a block that received liquid forwarded across a seam. */
    public void watch(Block block) {
        watched.add(pack(block.getX(), block.getY(), block.getZ()));
    }

    /** Adopt liquid found at seam edges in freshly loaded chunks. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (!chunk.getWorld().equals(this.world)) {
            return;
        }
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (topology.acrossSeam(baseX + lx + 0.5, baseZ + lz + 0.5) == null) {
                    continue;
                }
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    Block block = world.getBlockAt(baseX + lx, y, baseZ + lz);
                    if (isLiquid(block)) {
                        watch(block);
                    }
                }
            }
        }
    }

    /** Periodic feeder check for all watched liquid blocks. */
    public void tick(World world) {
        Iterator<Long> it = watched.iterator();
        while (it.hasNext()) {
            long packed = it.next();
            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(x, y, z);
            if (!isLiquid(block)) {
                it.remove();
                continue;
            }
            if (fedAcrossSeam(world, block) || fedLocally(block)) {
                continue;
            }
            block.setType(Material.AIR, true);
            mirrors.pushToMirrors(block);
            it.remove();
        }
    }

    private boolean fedAcrossSeam(World world, Block block) {
        MarginSource across = topology.acrossSeam(block.getX() + 0.5, block.getZ() + 0.5);
        if (across == null) {
            return false;
        }
        Block feeder = world.getBlockAt((int) Math.floor(across.source().x()), block.getY(),
                (int) Math.floor(across.source().z()));
        if (feeder.getType() != block.getType() || !(feeder.getBlockData() instanceof Levelled fl)) {
            return false;
        }
        int mine = ((Levelled) block.getBlockData()).getLevel();
        return fl.getLevel() == 0 || fl.getLevel() >= 8 || fl.getLevel() < mine;
    }

    private boolean fedLocally(Block block) {
        Block above = block.getRelative(BlockFace.UP);
        if (above.getType() == block.getType()) {
            return true;
        }
        int mine = ((Levelled) block.getBlockData()).getLevel();
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Block neighbor = block.getRelative(face);
            if (neighbor.getType() == block.getType()
                    && neighbor.getBlockData() instanceof Levelled nl
                    && (nl.getLevel() == 0 || nl.getLevel() >= 8 || nl.getLevel() < mine)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLiquid(Block block) {
        return (block.getType() == Material.WATER || block.getType() == Material.LAVA)
                && block.getBlockData() instanceof Levelled level
                && level.getLevel() != 0; // sources are player-made; never auto-drain them
    }

    /** x in bits 38-63, z in bits 12-37, y in bits 0-11 (all sign-extended on unpack). */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    private static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }
}
