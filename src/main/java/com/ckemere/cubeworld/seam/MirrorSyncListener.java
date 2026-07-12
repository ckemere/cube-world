package com.ckemere.cubeworld.seam;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Runtime consistency for seam margins:
 * <ul>
 *   <li>player changes to real blocks near a stitched edge propagate to the
 *       mirrored margin copies (rotated appropriately);</li>
 *   <li>margins never evolve on their own — physics, liquid flow, and mob
 *       spawning are suppressed there.</li>
 * </ul>
 * Margin-side edits are forwarded by {@link MarginInteractionListener}.
 */
public final class MirrorSyncListener implements Listener {

    private final Plugin plugin;
    private final MirrorService mirrors;

    public MirrorSyncListener(Plugin plugin, MirrorService mirrors) {
        this.plugin = plugin;
        this.mirrors = mirrors;
    }

    /** Push blocks to their mirrors next tick, once the change has applied. */
    private void pushLater(Iterable<Block> blocks) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Block block : blocks) {
                mirrors.pushToMirrors(block);
            }
        });
    }

    private void pushLater(Block block) {
        pushLater(java.util.List.of(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        pushLater(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        pushLater(event.getBlock());
    }

    /** Trees and giant mushrooms growing (sapling growth, bone meal). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(org.bukkit.event.world.StructureGrowEvent event) {
        java.util.List<Block> blocks = new java.util.ArrayList<>();
        for (org.bukkit.block.BlockState state : event.getBlocks()) {
            blocks.add(state.getBlock());
        }
        pushLater(blocks);
    }

    /** Crops, sugar cane, etc. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(org.bukkit.event.block.BlockGrowEvent event) {
        pushLater(event.getBlock());
    }

    /** Grass/mycelium/fire spread. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(org.bukkit.event.block.BlockSpreadEvent event) {
        pushLater(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(org.bukkit.event.block.BlockFadeEvent event) {
        pushLater(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(org.bukkit.event.block.BlockBurnEvent event) {
        pushLater(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(org.bukkit.event.block.LeavesDecayEvent event) {
        pushLater(event.getBlock());
    }

    /** Endermen, falling blocks landing, etc. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        pushLater(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        pushLater(new java.util.ArrayList<>(event.blockList()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        pushLater(new java.util.ArrayList<>(event.blockList()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        java.util.List<Block> blocks = new java.util.ArrayList<>();
        blocks.add(event.getBlock());
        blocks.add(event.getBlock().getRelative(event.getDirection()));
        for (Block moved : event.getBlocks()) {
            blocks.add(moved);
            blocks.add(moved.getRelative(event.getDirection()));
        }
        pushLater(blocks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        java.util.List<Block> blocks = new java.util.ArrayList<>();
        blocks.add(event.getBlock());
        blocks.add(event.getBlock().getRelative(event.getDirection()));
        for (Block moved : event.getBlocks()) {
            blocks.add(moved);
            blocks.add(moved.getRelative(event.getDirection()));
        }
        pushLater(blocks);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (mirrors.isMargin(event.getBlock().getX() + 0.5, event.getBlock().getZ() + 0.5)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent event) {
        if (mirrors.isMargin(event.getToBlock().getX() + 0.5, event.getToBlock().getZ() + 0.5)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        if (mirrors.isMargin(event.getLocation().getX(), event.getLocation().getZ())) {
            event.setCancelled(true);
        }
    }
}
