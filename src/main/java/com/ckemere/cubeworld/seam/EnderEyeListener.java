package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.geometry.CubeBearing;
import com.ckemere.cubeworld.seam.nms.StrongholdSphereHook;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderSignal;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;

/**
 * Makes thrown Eyes of Ender fly toward the correct cube seam.
 *
 * <p>Vanilla aims the eye along a raw world-XZ line toward the nearest stronghold
 * (see {@code EyeOfEnder.updateDeltaMovement}), so a stronghold on another cube face
 * sends the eye visibly the wrong way -- and its flat "nearest" search can even pick a
 * stronghold that is far by surface distance. On spawn we re-aim the eye at the
 * <em>geodesically</em> nearest stronghold, folded into the thrower's face frame with
 * {@link CubeBearing}. The eye's own per-tick steering then flies it toward the seam
 * that leads there, so repeated throws triangulate correctly.
 *
 * <p>We can't read the stronghold back off the eye -- by spawn time {@code signalTo}
 * has already collapsed the target to a point 12 blocks along the raw direction -- so
 * we take the ring positions from {@link StrongholdSphereHook#currentPositions} and
 * pick/fold our own. {@code setTargetLocation} re-runs {@code signalTo}, recomputing the
 * flight direction from the folded target. Only eyes vanilla actually launched (target
 * present) are re-aimed; a targetless eye still just shatters.
 */
public final class EnderEyeListener implements Listener {

    private final CubeWorldPlugin plugin;
    private final CubeBearing bearing;

    public EnderEyeListener(CubeWorldPlugin plugin, CubeBearing bearing) {
        this.plugin = plugin;
        this.bearing = bearing;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof EnderSignal signal)) {
            return;
        }
        World w = signal.getWorld();
        if (!plugin.isCubeWorld(w) || w.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        if (signal.getTargetLocation() == null) {
            return;                         // vanilla found no stronghold: let it shatter
        }
        double ex = signal.getLocation().getX();
        double ez = signal.getLocation().getZ();
        double ey = signal.getLocation().getY();

        double bestSq = Double.MAX_VALUE;
        double bx = 0;
        double bz = 0;
        boolean found = false;
        for (ChunkPos c : StrongholdSphereHook.currentPositions(w)) {
            double sx = c.getMiddleBlockX() + 0.5;
            double sz = c.getMiddleBlockZ() + 0.5;
            CubeBearing.Folded f = bearing.fold(ex, ez, sx, sz);
            double dx = f.x() - ex;
            double dz = f.z() - ez;
            double dsq = dx * dx + dz * dz;
            if (dsq < bestSq) {
                bestSq = dsq;
                bx = f.x();
                bz = f.z();
                found = true;
            }
        }
        if (found) {
            // Y is irrelevant to the eye's horizontal aim (signalTo forces +8 up), but
            // keep it near the eye so nothing looks odd.
            signal.setTargetLocation(new Location(w, bx, ey, bz));
        }
    }
}
