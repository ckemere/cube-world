package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.MarginSource;
import com.ckemere.cubeworld.seam.MirrorService;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Vanilla's neighbor-update reasoning is plane-local: a block that changes
 * tells its six geometric neighbors, and a block whose topological neighbor
 * lives across a stitched seam never hears anything. This updater replaces
 * the level's {@link CollectingNeighborUpdater} and does two things with the
 * traffic passing through it:
 *
 * <ul>
 *   <li><b>Cross-seam notification:</b> every update broadcast that
 *       originates within one block of a stitched edge also delivers a
 *       synthetic neighbor update to the origin's topological partner on the
 *       far face, delayed two ticks so the mirror-sync write (one tick) lands
 *       first — the poked block re-evaluates against margin mirrors that
 *       already reflect the change.</li>
 *   <li><b>Change detection for mirroring:</b> every position that update
 *       traffic touches near a seam is re-pushed to its margin images next
 *       tick. This catches state changes that fire no Bukkit event (command
 *       blocks, /setblock, observer-driven flips, note block tuning) within
 *       a tick instead of waiting for the reconciler sweep.</li>
 * </ul>
 *
 * <p>Liquid targets are never poked: {@code LiquidSeamService} owns
 * cross-seam liquid evaluation, and a raw poke would make vanilla drain
 * water that is legitimately fed from across the seam. Mirror pushes write
 * with physics off, so nothing here re-enters the updater.
 */
public final class SeamNeighborUpdater extends CollectingNeighborUpdater {

    private final ServerLevel level;
    private final CubeTopology topology;
    private final MirrorService mirrors;
    private final Plugin plugin;
    private final Set<Long> pending = new HashSet<>();
    private final Set<Long> pendingMirror = new HashSet<>();

    public SeamNeighborUpdater(ServerLevel level, int maxChainedNeighborUpdates,
                               CubeTopology topology, MirrorService mirrors, Plugin plugin) {
        super(level, maxChainedNeighborUpdates);
        this.level = level;
        this.topology = topology;
        this.mirrors = mirrors;
        this.plugin = plugin;
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block,
                                                  Direction skipFace, Orientation orientation) {
        super.updateNeighborsAtExceptFromFacing(pos, block, skipFace, orientation);
        forwardAcrossSeam(pos, block);
        mirrorLater(pos);
    }

    @Override
    public void shapeUpdate(Direction direction, BlockState neighborState, BlockPos pos,
                            BlockPos neighborPos, int flags, int recursionLeft) {
        super.shapeUpdate(direction, neighborState, pos, neighborPos, flags, recursionLeft);
        mirrorLater(pos);
        mirrorLater(neighborPos);
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, Orientation orientation) {
        super.neighborChanged(pos, block, orientation);
        mirrorLater(pos);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block,
                                Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, pos, block, orientation, movedByPiston);
        mirrorLater(pos);
    }

    /**
     * Update traffic touched this position: if it has margin images, re-push
     * it next tick (after the change has settled). Batched — one flush task
     * per tick regardless of traffic volume.
     */
    private void mirrorLater(BlockPos pos) {
        if (topology.marginImages(pos.getX() + 0.5, pos.getZ() + 0.5,
                mirrors.marginBlocks()).isEmpty()) {
            return;
        }
        boolean scheduleFlush = pendingMirror.isEmpty();
        if (!pendingMirror.add(pos.asLong())) {
            return;
        }
        if (!scheduleFlush) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Long[] batch = pendingMirror.toArray(new Long[0]);
            pendingMirror.clear();
            org.bukkit.World world = level.getWorld();
            for (Long packed : batch) {
                BlockPos p = BlockPos.of(packed);
                if (!level.isLoaded(p)) {
                    continue;
                }
                try {
                    mirrors.pushToMirrors(world.getBlockAt(p.getX(), p.getY(), p.getZ()));
                } catch (Throwable t) {
                    plugin.getLogger().warning("seam mirror push failed at " + p + ": " + t);
                }
            }
        });
    }

    private void forwardAcrossSeam(BlockPos origin, Block block) {
        MarginSource across = topology.acrossSeam(origin.getX() + 0.5, origin.getZ() + 0.5);
        if (across == null) {
            return;
        }
        BlockPos target = new BlockPos((int) Math.floor(across.source().x()), origin.getY(),
                (int) Math.floor(across.source().z()));
        long key = target.asLong();
        if (!pending.add(key)) {
            return; // already scheduled this tick window
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(key);
            try {
                if (!level.isLoaded(target)) {
                    return;
                }
                BlockState state = level.getBlockState(target);
                if (!state.getFluidState().isEmpty()) {
                    return; // liquids: owned by LiquidSeamService
                }
                level.neighborChanged(target, block, null);
            } catch (Throwable t) {
                plugin.getLogger().warning("cross-seam neighbor update failed at "
                        + target + ": " + t);
            }
        }, 2L);
    }
}
