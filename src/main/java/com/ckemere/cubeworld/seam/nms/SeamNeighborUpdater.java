package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.MarginSource;
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
 * the level's {@link CollectingNeighborUpdater}: every update broadcast that
 * originates within one block of a stitched edge also delivers a synthetic
 * neighbor update to the origin's topological partner on the far face.
 *
 * <p>The synthetic update is delayed two ticks so the mirror-sync write (one
 * tick) lands first — the poked block re-evaluates against margin mirrors
 * that already reflect the change. Liquid targets are skipped:
 * {@code LiquidSeamService} owns cross-seam liquid evaluation, and a raw poke
 * would make vanilla drain water that is legitimately fed from across the
 * seam.
 */
public final class SeamNeighborUpdater extends CollectingNeighborUpdater {

    private final ServerLevel level;
    private final CubeTopology topology;
    private final Plugin plugin;
    private final Set<Long> pending = new HashSet<>();

    public SeamNeighborUpdater(ServerLevel level, int maxChainedNeighborUpdates,
                               CubeTopology topology, Plugin plugin) {
        super(level, maxChainedNeighborUpdates);
        this.level = level;
        this.topology = topology;
        this.plugin = plugin;
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block,
                                                  Direction skipFace, Orientation orientation) {
        super.updateNeighborsAtExceptFromFacing(pos, block, skipFace, orientation);
        forwardAcrossSeam(pos, block);
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
