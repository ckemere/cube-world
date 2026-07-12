package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.Vec2;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * The bedrock pillars stop at build height, but the geometric inconsistency
 * at a cube vertex exists at every altitude — so the pillar cylinders extend
 * logically to infinity: players cannot move into the column above a pillar.
 */
public final class PillarGuardListener implements Listener {

    private final CubeTopology topology;
    private final int radius;

    public PillarGuardListener(CubeTopology topology, int radius) {
        this.topology = topology;
        this.radius = radius;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        int maxHeight = to.getWorld().getMaxHeight();
        if (to.getY() < maxHeight || !topology.inPillar(to.getX(), to.getZ(), radius)) {
            return;
        }
        Location from = event.getFrom();
        boolean fromInside = from.getY() >= maxHeight
                && topology.inPillar(from.getX(), from.getZ(), radius);
        if (fromInside) {
            // Somehow already inside the column: eject horizontally.
            event.setTo(ejected(from));
        } else {
            // Entering the column: solid invisible wall.
            event.setTo(from.clone());
        }
    }

    /** Nearest point just outside the pillar cylinder, same height. */
    private Location ejected(Location loc) {
        Vec2 nearest = null;
        double best = Double.MAX_VALUE;
        for (Vec2 site : topology.pillarSites()) {
            double dx = loc.getX() - site.x();
            double dz = loc.getZ() - site.z();
            double d2 = dx * dx + dz * dz;
            if (d2 < best) {
                best = d2;
                nearest = site;
            }
        }
        if (nearest == null) {
            return loc.clone();
        }
        double dx = loc.getX() - nearest.x();
        double dz = loc.getZ() - nearest.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        double scale = (radius + 2.0) / len;
        Location out = loc.clone();
        out.setX(nearest.x() + dx * scale);
        out.setZ(nearest.z() + dz * scale);
        return out;
    }
}
