package com.ckemere.cubeworld.seam;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Two-way seam sync, player side: interactions with margin blocks are
 * forwarded to the real blocks they mirror. Authoritative state lives only in
 * real chunks; the margin reflects the outcome. Corner pillars stay immutable.
 */
public final class MarginInteractionListener implements Listener {

    private final org.bukkit.plugin.Plugin plugin;
    private final MirrorService mirrors;

    public MarginInteractionListener(org.bukkit.plugin.Plugin plugin, MirrorService mirrors) {
        this.plugin = plugin;
        this.mirrors = mirrors;
    }

    /**
     * Cancelled place/break events restore their captured pre-event block
     * state after handlers run, clobbering any mirror write made inside the
     * handler — the forwarded change would vanish from the margin while the
     * real side keeps it. Re-push the mirror a tick later, after cleanup.
     */
    private void repushLater(Block source) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> mirrors.pushToMirrors(source));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        double x = block.getX() + 0.5;
        double z = block.getZ() + 0.5;
        if (mirrors.isPillar(x, z)) {
            event.setCancelled(true);
            return;
        }
        if (!mirrors.isMargin(x, z)) {
            return; // real-side break: MirrorSyncListener pushes it out
        }
        event.setCancelled(true);
        // Capture drops before the source is broken, using the player's tool.
        Block source = mirrors.sourceBlock(block);
        if (source == null) {
            return;
        }
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        java.util.Collection<ItemStack> drops = source.getDrops(tool, event.getPlayer());
        Block broken = mirrors.forwardBreak(block);
        if (broken != null) {
            repushLater(broken);
            if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
                for (ItemStack drop : drops) {
                    block.getWorld().dropItemNaturally(block.getLocation(), drop);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        double x = block.getX() + 0.5;
        double z = block.getZ() + 0.5;
        if (mirrors.isPillar(x, z)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "The corner pillars are immutable.", NamedTextColor.YELLOW));
            return;
        }
        if (!mirrors.isMargin(x, z)) {
            return; // real-side place: MirrorSyncListener pushes it out
        }
        event.setCancelled(true);
        Block placed = mirrors.forwardPlace(block, event.getBlockPlaced().getBlockData());
        if (placed == null) {
            return;
        }
        repushLater(placed);
        // The forward succeeded and the mirror will show the block; consume
        // the item ourselves since the local placement was cancelled.
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            ItemStack inHand = event.getItemInHand();
            inHand.setAmount(inHand.getAmount() - 1);
        }
    }

    /** Opening a container in a margin opens the real container across the seam. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!mirrors.isMargin(block.getX() + 0.5, block.getZ() + 0.5)) {
            return;
        }
        Block source = mirrors.sourceBlock(block);
        if (source != null && source.getState() instanceof Container container) {
            event.setCancelled(true);
            event.getPlayer().openInventory(container.getInventory());
        }
    }
}
