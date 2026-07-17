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
 * Portal travel between the cube overworld and the cube nether preserves the
 * cube-surface position: both worlds share the same face size and topology,
 * so a portal at (face, local) links to (face, local) in the sibling world —
 * no vanilla /8 coordinate scatter, which would tear seam-adjacent points
 * hundreds of blocks apart in the nether. (An 8x-travel nether needs faces
 * scaled 1:8, which the Earth-map world will pick sizes for; the linkage
 * below only assumes the two worlds share one geometry.)
 *
 * <p>Destinations are clamped to the face interior so vanilla never builds
 * the return portal inside a margin or pillar, and y is looked up from the
 * destination world's own height field.
 */
public final class PortalLinkListener implements Listener {

    /** Keep vanilla-created return portals this far inside the face edge. */
    private static final int EDGE_CLEARANCE = 24;

    private final CubeWorldPlugin plugin;
    private final CubeGeometry geometry;
    private final MapService maps;

    public PortalLinkListener(CubeWorldPlugin plugin, CubeGeometry geometry, MapService maps) {
        this.plugin = plugin;
        this.geometry = geometry;
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
        double x = from.getX();
        double z = from.getZ();
        CubeFace face = geometry.faceAt((int) Math.floor(x), (int) Math.floor(z));
        if (face == null) {
            return null; // margin portals are cancelled elsewhere
        }
        int size = geometry.faceSize();
        double minX = geometry.faceMinX(face) + EDGE_CLEARANCE;
        double minZ = geometry.faceMinZ(face) + EDGE_CLEARANCE;
        x = Math.clamp(x, minX, minX + size - 2.0 * EDGE_CLEARANCE);
        z = Math.clamp(z, minZ, minZ + size - 2.0 * EDGE_CLEARANCE);
        boolean toNether = target.getEnvironment() == World.Environment.NETHER;
        MapService.CubeWorldMap map = maps.mapFor(target.getSeed());
        MapSampler sampler = toNether ? map.netherSampler() : map.sampler();
        int floorLevel = toNether ? CubeNetherChunkGenerator.LAVA_LEVEL
                : CubeWorldChunkGenerator.SEA_LEVEL;
        double y = Math.max(sampler.heightAt(x, z), floorLevel) + 1;
        return new Location(target, x, y, z, from.getYaw(), from.getPitch());
    }
}
