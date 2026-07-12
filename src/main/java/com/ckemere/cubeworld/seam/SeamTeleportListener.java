package com.ckemere.cubeworld.seam;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

/**
 * Teleports players across stitched seams the moment they walk over one,
 * rotating position, view yaw, and velocity so the crossing reads as
 * continuous terrain. (PlayerTeleportEvent has its own HandlerList, so this
 * handler does not re-fire for the teleports it causes.)
 */
public final class SeamTeleportListener implements Listener {

    private final SeamService seams;

    public SeamTeleportListener(SeamService seams) {
        this.seams = seams;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location dest = seams.seamDestination(event.getFrom(), event.getTo());
        if (dest == null) {
            return;
        }
        Player player = event.getPlayer();
        Vector velocity = seams.transformVelocity(event.getFrom(), event.getTo(), player.getVelocity());
        player.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN,
                TeleportFlag.Relative.VELOCITY_X,
                TeleportFlag.Relative.VELOCITY_Y,
                TeleportFlag.Relative.VELOCITY_Z,
                TeleportFlag.Relative.VELOCITY_ROTATION);
        player.setVelocity(velocity);
    }
}
