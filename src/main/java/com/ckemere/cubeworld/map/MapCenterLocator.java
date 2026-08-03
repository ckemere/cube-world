package com.ckemere.cubeworld.map;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.geometry.CubeBearing;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.Waypoint;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

/**
 * Adds a locator-bar dot pointing to the centre of a cube map the player is holding.
 *
 * <p>Vanilla has no "where is this map's home" indicator, and the locator bar's own
 * dots are player-only. Here, while a player holds one of our {@link CubeMapRenderer}
 * maps, we send them a single custom waypoint at the map's centre in a distinct colour,
 * folded into their face frame with {@link CubeBearing} so it points across seams
 * correctly (position for a same/adjacent-face centre, azimuth for a far one). It is a
 * per-holder packet, not a broadcast waypoint, so only the holder sees it.
 */
public final class MapCenterLocator {

    /** Stable, non-player identifier for the holder's single map-centre dot. */
    private static final UUID ID = UUID.nameUUIDFromBytes("cubeworld:map-center".getBytes());
    private static final int COLOR = 0xFFD54A;          // gold, distinct from player dots

    private final CubeWorldPlugin plugin;
    private final CubeBearing bearing;
    /** Players currently shown the dot (so we TRACK once then UPDATE, and UNTRACK on drop). */
    private final Set<UUID> shown = new HashSet<>();

    public MapCenterLocator(CubeWorldPlugin plugin, CubeBearing bearing) {
        this.plugin = plugin;
        this.bearing = bearing;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 10L);
    }

    private void tick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            MapView view = heldCubeMap(p);
            World w = p.getWorld();
            boolean eligible = view != null && plugin.isCubeWorld(w)
                    && w.getEnvironment() == World.Environment.NORMAL;
            if (eligible) {
                send(p, view.getCenterX(), view.getCenterZ());
            } else if (shown.remove(p.getUniqueId())) {
                connection(p).send(ClientboundTrackedWaypointPacket.removeWaypoint(ID));
            }
        }
    }

    private void send(Player p, int centerX, int centerZ) {
        double px = p.getLocation().getX();
        double pz = p.getLocation().getZ();
        CubeBearing.Folded f = bearing.fold(px, pz, centerX + 0.5, centerZ + 0.5);
        Waypoint.Icon icon = new Waypoint.Icon();
        icon.color = Optional.of(COLOR);

        boolean firstTime = shown.add(p.getUniqueId());
        ClientboundTrackedWaypointPacket packet;
        if (f.adjacent()) {
            Vec3i pos = new Vec3i((int) Math.round(f.x()), (int) Math.round(p.getLocation().getY()),
                    (int) Math.round(f.z()));
            packet = firstTime
                    ? ClientboundTrackedWaypointPacket.addWaypointPosition(ID, icon, pos)
                    : ClientboundTrackedWaypointPacket.updateWaypointPosition(ID, icon, pos);
        } else {
            // Far centre: send a pure direction. Match vanilla's azimuth convention
            // (EntityAzimuthConnection): rotate (receiver - source) 90deg CW, then atan2.
            Vec3 dir = new Vec3(px - f.x(), 0, pz - f.z()).rotateClockwise90();
            float angle = (float) Math.atan2(dir.z(), dir.x());
            packet = firstTime
                    ? ClientboundTrackedWaypointPacket.addWaypointAzimuth(ID, icon, angle)
                    : ClientboundTrackedWaypointPacket.updateWaypointAzimuth(ID, icon, angle);
        }
        connection(p).send(packet);
    }

    private static net.minecraft.server.network.ServerGamePacketListenerImpl connection(Player p) {
        return ((CraftPlayer) p).getHandle().connection;
    }

    /** The MapView of a held filled map rendered by our CubeMapRenderer, or null. */
    private MapView heldCubeMap(Player p) {
        MapView v = cubeMapOf(p.getInventory().getItemInMainHand());
        return v != null ? v : cubeMapOf(p.getInventory().getItemInOffHand());
    }

    private MapView cubeMapOf(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP
                || !(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapView()) {
            return null;
        }
        MapView view = meta.getMapView();
        if (view == null) {
            return null;
        }
        return view.getRenderers().stream().anyMatch(r -> r instanceof CubeMapRenderer)
                ? view : null;
    }
}
