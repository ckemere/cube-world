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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        Bukkit.getScheduler().runTask(plugin, () -> mirrors.pushToMirrors(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // The block is removed after the event completes; push the new state
        // (air) on the next tick.
        Block block = event.getBlock();
        Bukkit.getScheduler().runTask(plugin, () -> mirrors.pushToMirrors(block));
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
