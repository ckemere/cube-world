package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.generation.CubeNetherChunkGenerator;
import com.ckemere.cubeworld.generation.CubeWorldChunkGenerator;
import com.ckemere.cubeworld.generation.MapSampler;
import com.ckemere.cubeworld.generation.MapService;
import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Portal travel between the cube overworld and the cube nether maps by
 * cube-surface correspondence: a portal at (face, local u/v) links to the same
 * (face, local u/v) in the sibling world. Because the nether cube is 1:8, that
 * correspondence IS the 8x compression (nether coords = overworld/8) — but done
 * per-face so it stays seam-safe: an overworld point near a seam lands near the
 * corresponding nether seam, never scattered onto a different face.
 *
 * <p>Destinations are clamped to the face interior so vanilla never builds
 * the return portal inside a margin or pillar, and y is looked up from the
 * destination world's own height field.
 */
public final class PortalLinkListener implements Listener {

    /** Keep vanilla-created return portals this far inside the face edge. */
    private static final int EDGE_CLEARANCE = 24;

    private final CubeWorldPlugin plugin;
    private final CubeGeometry geometry;          // overworld cube
    private final CubeGeometry netherGeometry;    // 1:8 nether cube
    private final MapService maps;

    public PortalLinkListener(CubeWorldPlugin plugin, CubeGeometry geometry,
                              CubeGeometry netherGeometry, MapService maps) {
        this.plugin = plugin;
        this.geometry = geometry;
        this.netherGeometry = netherGeometry;
        this.maps = maps;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            return;
        }
        Location linked = linkedDestination(event.getFrom(), event.getTo());
        if (linked != null) {
            event.setTo(linked);
            event.setSearchRadius(16);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        Location linked = linkedDestination(event.getFrom(), event.getTo());
        if (linked != null) {
            event.setTo(linked);
            event.setSearchRadius(16);
        }
    }

    private Location linkedDestination(Location from, Location vanillaTo) {
        if (vanillaTo == null || !plugin.isCubeWorld(from.getWorld())) {
            return null;
        }
        World target = vanillaTo.getWorld();
        if (target == null || !plugin.isCubeWorld(target)) {
            return null; // the end, or some other plugin's world
        }
        boolean fromNether = from.getWorld().getEnvironment() == World.Environment.NETHER;
        boolean toNether = target.getEnvironment() == World.Environment.NETHER;
        CubeGeometry src = fromNether ? netherGeometry : geometry;
        CubeGeometry dst = toNether ? netherGeometry : geometry;
        double x = from.getX();
        double z = from.getZ();
        CubeFace face = src.faceAt((int) Math.floor(x), (int) Math.floor(z));
        if (face == null) {
            return null; // margin portals are cancelled elsewhere
        }
        // source face-local (u,v) in [0,1] -> the same (u,v) on the destination
        // cube (which is a different size, giving the 1:8 compression).
        double fu = (x - src.faceMinX(face)) / src.faceSize();
        double fv = (z - src.faceMinZ(face)) / src.faceSize();
        double clr = (double) EDGE_CLEARANCE / dst.faceSize();
        fu = Math.clamp(fu, clr, 1.0 - clr);
        fv = Math.clamp(fv, clr, 1.0 - clr);
        double dx = dst.faceMinX(face) + fu * dst.faceSize();
        double dz = dst.faceMinZ(face) + fv * dst.faceSize();
        double y;
        if (toNether) {
            // The nether is now real vanilla terrain (not the demo height field),
            // so probe the actual generated column for a safe floor above the
            // lava sea and below the bedrock roof — vanilla's portal forcer then
            // builds/searches near a spot the player can actually stand on.
            y = netherSurfaceY(target, (int) Math.floor(dx), (int) Math.floor(dz));
        } else {
            // Overworld destination: the sampler IS the real Earth surface.
            MapSampler sampler = maps.mapFor(target.getSeed()).sampler();
            y = Math.max(sampler.heightAt(dx, dz), CubeWorldChunkGenerator.SEA_LEVEL) + 1;
        }
        return new Location(target, dx, y, dz, from.getYaw(), from.getPitch());
    }

    /**
     * A safe standing Y in the real vanilla nether at (x, z): the highest solid
     * floor with two air blocks above, kept above the lava sea and below the
     * bedrock roof. Forces the destination chunk to generate first. Falls back to
     * just above the lava sea if the column is somehow all open.
     */
    private int netherSurfaceY(World nether, int x, int z) {
        nether.getChunkAt(x >> 4, z >> 4); // ensure the column is generated
        int roofUnderside = 122;   // bedrock roof sits at 125-127
        int floorMin = CubeNetherChunkGenerator.LAVA_LEVEL + 1;
        for (int y = roofUnderside; y >= floorMin; y--) {
            if (nether.getBlockAt(x, y, z).getType().isSolid()
                    && nether.getBlockAt(x, y + 1, z).isEmpty()
                    && nether.getBlockAt(x, y + 2, z).isEmpty()) {
                return y + 1;
            }
        }
        return floorMin + 1;
    }
}
