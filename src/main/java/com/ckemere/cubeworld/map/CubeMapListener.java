package com.ckemere.cubeworld.map;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.teleport.TeleportService;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Attaches {@link CubeMapRenderer} to <em>every</em> map created in a cube world.
 *
 * <p>Without this there were effectively two kinds of map. A map from
 * {@code /cubeworld map axumchest} got the cube-aware renderer; a map a player
 * crafted or took from the creative menu got plain vanilla rendering — and on a
 * cube world vanilla is simply wrong. Vanilla assumes an infinite flat plane, so
 * across a face seam it draws void, and its player marker uses raw x/z, which
 * stops meaning anything once you have crossed onto another face.
 *
 * <p>That one gap produced three separate player-visible bugs: the player dot
 * vanished when you walked off the map instead of clamping to the edge, the
 * map-centre locator bar never appeared (its lookup requires a CubeMapRenderer on
 * the view), and teleport-station markers never showed.
 *
 * <p><b>Why this cannot stall the server.</b> {@link CubeMapImage} skips chunks
 * that are not loaded rather than loading them, so rendering only ever reads what
 * is already in memory: unexplored ground stays blank and fills in as the player
 * travels. The demo command was the thing that stalled the main thread, because it
 * force-loaded the whole footprint up front before rendering. We deliberately do
 * not do that here.
 */
public final class CubeMapListener implements Listener {

    private final CubeWorldPlugin plugin;
    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final TeleportService teleport;

    public CubeMapListener(CubeWorldPlugin plugin, CubeGeometry geometry,
                           CubeTopology topology, TeleportService teleport) {
        this.plugin = plugin;
        this.geometry = geometry;
        this.topology = topology;
        this.teleport = teleport;
    }

    /**
     * Fires when a map is first created <em>or</em> loaded from disk, so maps that
     * already exist in an older world are upgraded on the next server start rather
     * than being stuck vanilla forever.
     */
    @EventHandler
    public void onMapInitialize(MapInitializeEvent event) {
        MapView view = event.getMap();
        World world = view.getWorld();
        if (world == null || !plugin.isCubeWorld(world)) {
            return;                       // the end, or a non-cube world: leave vanilla alone
        }
        for (MapRenderer r : view.getRenderers()) {
            if (r instanceof CubeMapRenderer) {
                return;                   // already ours (e.g. the demo command built it)
            }
        }
        // Deliberately KEEP vanilla's CraftMapRenderer and append ours after it.
        // Removing it would silently delete every vanilla decoration the map carries:
        // the red X on a shipwreck/buried-treasure map, cartographer explorer-map
        // targets, banner markers and the framed-map "+" pin. Our renderer paints
        // over vanilla's pixels and MERGES its cursors, so the X survives.
        // Vanilla scale is a power-of-two exponent: 0 = 1 block/pixel .. 4 = 16.
        int blocksPerPixel = 1 << view.getScale().getValue();
        view.setTrackingPosition(true);
        view.setUnlimitedTracking(true);
        view.addRenderer(new CubeMapRenderer(geometry, topology, teleport,
                view.getCenterX(), view.getCenterZ(), blocksPerPixel));
    }
}
