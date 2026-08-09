package com.ckemere.cubeworld.map;

import com.ckemere.cubeworld.geometry.CubeBearing;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.teleport.TeleportService;
import java.awt.Color;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * A {@link MapRenderer} that paints the seam-flattened terrain from
 * {@link CubeMapImage} onto a vanilla map item, and adds a marker for each
 * teleport station plus the viewing player.
 *
 * <p>Rough draft: the terrain is rendered once and cached (sampling 16k columns
 * per pass is too heavy to run every client tick), so live player edits appear
 * only after {@link #markDirty()} is called. Cursors, being cheap, refresh every
 * frame so the player marker tracks movement.
 */
public final class CubeMapRenderer extends MapRenderer {

    private final CubeGeometry geom;
    private final CubeTopology topo;
    private final CubeBearing bearing;
    private final TeleportService teleport;
    private final int centerX;
    private final int centerZ;
    private final int blocksPerPixel;

    private CubeMapImage.Rendered cached;
    private volatile boolean dirty = true;

    public CubeMapRenderer(CubeGeometry geom, CubeTopology topo, TeleportService teleport,
                           int centerX, int centerZ, int blocksPerPixel) {
        super(true);                 // contextual = per-player (for the player cursor)
        this.geom = geom;
        this.topo = topo;
        this.bearing = new CubeBearing(topo);
        this.teleport = teleport;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.blocksPerPixel = blocksPerPixel;
    }

    /** Force a terrain re-render on the next frame (e.g. after nearby edits). */
    public void markDirty() {
        dirty = true;
    }

    /**
     * Fill the terrain cache now, off the render thread, while the caller has the
     * footprint force-loaded. Lets a freshly-created map show content the first
     * time it is opened instead of rendering blank and filling in as chunks load.
     */
    public void prime(org.bukkit.World world) {
        cached = CubeMapImage.render(world, geom, topo, centerX, centerZ, blocksPerPixel);
        dirty = false;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        // Paint only when the image changed. CraftMapView gives every renderer its
        // OWN per-player canvas (Map<MapRenderer, Map<CraftPlayer, CraftMapCanvas>>),
        // so vanilla's renderer cannot overwrite ours and there is nothing to redraw.
        if (dirty || cached == null) {
            cached = CubeMapImage.render(player.getWorld(), geom, topo,
                    centerX, centerZ, blocksPerPixel);
            int size = cached.size();
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    int argb = cached.argb()[py * size + px];
                    canvas.setPixelColor(px, py,
                            new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF));
                }
            }
            dirty = false;
        }
        // Cursors from every renderer are accumulated into one RenderData.cursors
        // list, so vanilla's decorations (treasure X, explorer targets, banners)
        // survive alongside ours without us merging anything.
        canvas.setCursors(cursors(player));
    }

    private MapCursorCollection cursors(Player player) {
        MapCursorCollection cc = new MapCursorCollection();
        if (teleport != null) {
            for (TeleportService.Station s : teleport.all()) {
                addCursor(cc, s.x(), s.z(), MapCursor.Type.TARGET_POINT, s.name());
            }
        }
        addPlayer(cc, player);
        return cc;
    }

    /**
     * How far off the map, in map-widths, a station marker still gets clamped to the
     * edge instead of being dropped. A freshly crafted map is 1 block/pixel — only
     * 128 blocks across — so requiring a station to fall inside the footprint meant
     * an ordinary map never showed a single city. Clamping gives a bearing to the
     * nearby ones; the cap stops all 30 piling onto the border.
     */
    private static final double OFF_MAP_WIDTHS = 4.0;

    private void addCursor(MapCursorCollection cc, int wx, int wz,
                           MapCursor.Type type, String label) {
        // Fold the marker into the map's home-face frame so it lands where CubeMapImage
        // actually drew that face, not at its raw net position across a seam.
        CubeBearing.Folded f = bearing.fold(centerX + 0.5, centerZ + 0.5, wx + 0.5, wz + 0.5);
        double fx = (f.x() - centerX) / (double) blocksPerPixel;   // pixels from centre
        double fz = (f.z() - centerZ) / (double) blocksPerPixel;
        double limit = 64 * OFF_MAP_WIDTHS;
        if (Math.abs(fx) > limit || Math.abs(fz) > limit) {
            return;                     // too far to be worth a border marker
        }
        int cx = clampByte((int) Math.round(fx * 2));           // 2 cursor units per pixel
        int cz = clampByte((int) Math.round(fz * 2));
        cc.addCursor(new MapCursor((byte) cx, (byte) cz, (byte) 8, type, true, label));
    }

    private void addPlayer(MapCursorCollection cc, Player player) {
        CubeBearing.Folded f = bearing.fold(centerX + 0.5, centerZ + 0.5,
                player.getLocation().getX(), player.getLocation().getZ());
        double fx = (f.x() - centerX) / (double) blocksPerPixel;
        double fz = (f.z() - centerZ) / (double) blocksPerPixel;
        boolean off = Math.abs(fx) > 64 || Math.abs(fz) > 64;
        // Vanilla still draws its own PLAYER decoration (MapItemSavedData line ~218,
        // gated on trackingPosition). Where the fold is the identity and the player is
        // on the map, that decoration is already correct, so adding ours would just
        // stack a second arrow on the same pixel. Stand down and let vanilla have it;
        // we only take over where vanilla is wrong -- across a seam (folded position
        // differs from raw) or off the map (vanilla drops the marker, we clamp it).
        //
        // Turning trackingPosition off instead would also remove the item-frame "+"
        // marker, which is gated on the same flag; the treasure-map X is NOT, so it
        // survives either way.
        boolean foldIsIdentity = f.x() == player.getLocation().getX()
                && f.z() == player.getLocation().getZ();
        if (foldIsIdentity && !off) {
            return;
        }
        int cx = clampByte((int) Math.round(fx * 2));
        int cz = clampByte((int) Math.round(fz * 2));
        // Rotate the arrow by the fold's seam rotation so it points correctly on the
        // folded terrain (the neighbour face is drawn turned by the same amount).
        double yaw = player.getLocation().getYaw() - 90.0 * f.quarterTurns();
        int dir = ((int) Math.round(yaw / 22.5) + 8) & 15;
        cc.addCursor(new MapCursor((byte) cx, (byte) cz, (byte) dir,
                off ? MapCursor.Type.PLAYER_OFF_MAP : MapCursor.Type.PLAYER, true));
    }

    private static int clampByte(int v) {
        return Math.max(-128, Math.min(127, v));
    }
}
