package com.ckemere.cubeworld.seam;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Carries players across stitched seams the moment they walk over one,
 * rotating position, view yaw, and velocity so the crossing reads as
 * continuous terrain.
 *
 * <p>The crossing is applied via {@link PlayerMoveEvent#setTo} rather than
 * {@code Player#teleport}: a teleport issued from inside a move handler races
 * against the client's in-flight movement packets and rubber-bands the player
 * at the boundary, while setTo makes the crossing the move's own destination
 * with proper client ack semantics. Velocity is re-applied a tick later (the
 * position sync zeroes it).
 */
public final class SeamTeleportListener implements Listener {

    private final com.ckemere.cubeworld.CubeWorldPlugin plugin;
    private final SeamService seams;

    public SeamTeleportListener(com.ckemere.cubeworld.CubeWorldPlugin plugin, SeamService seams) {
        this.plugin = plugin;
        this.seams = seams;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.isCubeWorld(event.getFrom().getWorld())) {
            return;
        }
        Location dest = seams.seamDestination(event.getFrom(), event.getTo());
        if (dest == null) {
            return;
        }
        Player player = event.getPlayer();
        Vector velocity = seams.transformVelocity(event.getFrom(), event.getTo(), player.getVelocity());
        event.setTo(dest);
        plugin.getLogger().info(() -> String.format("Seam crossing: %s (%.1f, %.1f) -> (%.1f, %.1f)",
                player.getName(), event.getFrom().getX(), event.getFrom().getZ(), dest.getX(), dest.getZ()));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.setVelocity(velocity);
            }
        });
    }
}
