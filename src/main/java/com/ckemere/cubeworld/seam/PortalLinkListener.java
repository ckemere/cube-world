package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.generation.CubeNetherChunkGenerator;
import com.ckemere.cubeworld.generation.CubeWorldChunkGenerator;
import com.ckemere.cubeworld.generation.MapSampler;
import com.ckemere.cubeworld.generation.MapService;
import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.Vec2;
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

    /** How far inside the face edge to keep a vanilla-created return portal, in
     * DESTINATION blocks — just enough that the 4-wide portal frame can't poke
     * past a seam into a margin (mirror-reset territory would eat the frame).
     * This is a small, invertibility-preserving nudge. It replaces an old blanket
     * 24-block pull that, applied on the 1:8 nether face, was 24*8 = 192 OVERWORLD
     * blocks: it shoved the nether landing so far inward that the return couldn't
     * invert it and landed outside {@link #OVERWORLD_SEARCH}, spawning a duplicate
     * portal for any overworld portal within ~192 blocks of a seam. */
    private static final int FRAME_CLEARANCE = 8;

    /** Corner pillars (cube vertices) are unbreakable; a return portal can't be
     * built inside one. Matches the pillar radius used by the generators. */
    private static final int PILLAR_CLEARANCE = CubeWorldPlugin.MARGIN_BLOCKS;

    /** Portal-reuse search radius per destination. The (u,v) mapping is an exact
     * inverse, so a round trip returns to the original portal; a miss only comes
     * from vanilla creating the far-side portal a few blocks off, or the small
     * {@link #FRAME_CLEARANCE} nudge near a seam. The 1:8 nether compression
     * AMPLIFIES any such offset by 8x on the nether->overworld leg, so the
     * overworld search must be ~8x larger or vanilla can't find the original
     * portal and builds a duplicate. Small radius in the compressed nether avoids
     * linking to a different nearby portal. */
    private static final int NETHER_SEARCH = 16;
    private static final int OVERWORLD_SEARCH = 160;   // >= 8 * NETHER_SEARCH, margin

    private final CubeWorldPlugin plugin;
    private final CubeGeometry geometry;          // overworld cube
    private final CubeGeometry netherGeometry;    // 1:8 nether cube
    private final CubeTopology topology;          // overworld pillars/seams
    private final CubeTopology netherTopology;    // nether pillars/seams
    private final MapService maps;

    public PortalLinkListener(CubeWorldPlugin plugin, CubeGeometry geometry,
                              CubeGeometry netherGeometry, CubeTopology topology,
                              CubeTopology netherTopology, MapService maps) {
        this.plugin = plugin;
        this.geometry = geometry;
        this.netherGeometry = netherGeometry;
        this.topology = topology;
        this.netherTopology = netherTopology;
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
            event.setSearchRadius(searchRadiusFor(linked));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        Location linked = linkedDestination(event.getFrom(), event.getTo());
        if (linked != null) {
            event.setTo(linked);
            event.setSearchRadius(searchRadiusFor(linked));
        }
    }

    private static int searchRadiusFor(Location dest) {
        return dest.getWorld().getEnvironment() == World.Environment.NETHER
                ? NETHER_SEARCH : OVERWORLD_SEARCH;
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
        // Same face-local (u,v) on the destination cube (a different size, giving
        // the 1:8 compression). This is an EXACT inverse, so a round trip returns
        // to the original portal — we deliberately do NOT pull the destination
        // toward the face center. It is nudged only where vanilla physically can't
        // keep a portal: off the seam margins, and out of the corner pillars.
        double fu = (x - src.faceMinX(face)) / src.faceSize();
        double fv = (z - src.faceMinZ(face)) / src.faceSize();
        double dx = dst.faceMinX(face) + fu * dst.faceSize();
        double dz = dst.faceMinZ(face) + fv * dst.faceSize();
        CubeTopology dstTopo = toNether ? netherTopology : topology;
        double[] safe = keepBuildable(dst, face, dstTopo, dx, dz);
        dx = safe[0];
        dz = safe[1];
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
     * Nudge a destination (dx, dz) to somewhere vanilla can actually keep a
     * portal, changing it as little as possible so the round trip stays close to
     * an exact inverse:
     *
     * <ol>
     *   <li>{@link #FRAME_CLEARANCE} blocks off each face edge, so the portal
     *       frame doesn't cross a seam into a margin that the mirror service would
     *       reset out from under it; and</li>
     *   <li>outside any corner pillar (unbreakable cube-vertex bedrock) — the old
     *       small pull never actually cleared these, so a return portal could land
     *       inside one near a corner.</li>
     * </ol>
     *
     * Away from seams and corners this is a no-op and the mapping is exact.
     */
    private double[] keepBuildable(CubeGeometry g, CubeFace face, CubeTopology topo,
                                   double x, double z) {
        double minX = g.faceMinX(face) + FRAME_CLEARANCE;
        double maxX = g.faceMinX(face) + g.faceSize() - FRAME_CLEARANCE;
        double minZ = g.faceMinZ(face) + FRAME_CLEARANCE;
        double maxZ = g.faceMinZ(face) + g.faceSize() - FRAME_CLEARANCE;
        x = Math.clamp(x, minX, maxX);
        z = Math.clamp(z, minZ, maxZ);
        // Corner pillars sit at cube-vertex net images with radius PILLAR_CLEARANCE.
        // If the point is inside one, shove it just past the rim (radially, or
        // toward the face center if it landed dead on the site), then re-clamp so
        // we never leave the buildable face.
        for (Vec2 site : topo.pillarSites()) {
            double ddx = x - site.x();
            double ddz = z - site.z();
            double d2 = ddx * ddx + ddz * ddz;
            double r = PILLAR_CLEARANCE;
            if (d2 <= r * r) {
                double d = Math.sqrt(d2);
                double ux, uz;
                if (d < 1.0e-6) {
                    ux = (g.faceMinX(face) + g.faceSize() / 2.0) - site.x();
                    uz = (g.faceMinZ(face) + g.faceSize() / 2.0) - site.z();
                    double n = Math.hypot(ux, uz);
                    ux /= n;
                    uz /= n;
                } else {
                    ux = ddx / d;
                    uz = ddz / d;
                }
                x = Math.clamp(site.x() + ux * (r + 1), minX, maxX);
                z = Math.clamp(site.z() + uz * (r + 1), minZ, maxZ);
            }
        }
        return new double[]{x, z};
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
