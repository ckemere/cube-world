package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.geometry.CubeBearing;
import net.minecraft.core.Vec3i;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointTransmitter;

/**
 * A cube-aware locator-bar connection between a source and a receiver.
 *
 * <p>Replaces vanilla's {@code EntityBlock/Chunk/AzimuthConnection}, which point the dot
 * along a raw world-XZ line. Here the source is first folded into the receiver's face
 * frame with {@link CubeBearing}, so the client's flat math yields the true on-surface
 * bearing across a seam: a POSITION waypoint for a same/adjacent-face source (exact
 * direction, distance and elevation) and an AZIMUTH for a far one.
 */
final class CubeConnection implements WaypointTransmitter.Connection {

    private static final double ANGLE_EPS = 0.0087266;   // ~0.5 degrees, as vanilla

    private final LivingEntity source;
    private final ServerPlayer receiver;
    private final Waypoint.Icon icon;
    private final CubeBearing bearing;
    private final boolean adjacentAtConnect;

    private Vec3i lastPos;
    private float lastAngle;

    CubeConnection(LivingEntity source, ServerPlayer receiver, Waypoint.Icon icon,
                   CubeBearing bearing) {
        this.source = source;
        this.receiver = receiver;
        this.icon = icon;
        this.bearing = bearing;
        this.adjacentAtConnect = fold().adjacent();
    }

    private CubeBearing.Folded fold() {
        Vec3 s = source.position();
        Vec3 r = receiver.position();
        return bearing.fold(r.x(), r.z(), s.x(), s.z());
    }

    @Override
    public void connect() {
        CubeBearing.Folded f = fold();
        if (f.adjacent()) {
            lastPos = pos(f);
            send(ClientboundTrackedWaypointPacket.addWaypointPosition(source.getUUID(), icon, lastPos));
        } else {
            lastAngle = azimuth(f);
            send(ClientboundTrackedWaypointPacket.addWaypointAzimuth(source.getUUID(), icon, lastAngle));
        }
    }

    @Override
    public void update() {
        CubeBearing.Folded f = fold();
        if (f.adjacent()) {                       // mode can't have flipped: isBroken caught it
            Vec3i p = pos(f);
            if (!p.equals(lastPos)) {
                lastPos = p;
                send(ClientboundTrackedWaypointPacket.updateWaypointPosition(source.getUUID(), icon, p));
            }
        } else {
            float a = azimuth(f);
            if (Math.abs(a - lastAngle) > ANGLE_EPS) {
                lastAngle = a;
                send(ClientboundTrackedWaypointPacket.updateWaypointAzimuth(source.getUUID(), icon, a));
            }
        }
    }

    @Override
    public void disconnect() {
        send(ClientboundTrackedWaypointPacket.removeWaypoint(source.getUUID()));
    }

    @Override
    public boolean isBroken() {
        // Rebuild when the source leaves range/visibility, or crosses the adjacent<->far
        // boundary (which flips the position<->azimuth mode).
        return WaypointTransmitter.doesSourceIgnoreReceiver(source, receiver)
                || fold().adjacent() != adjacentAtConnect;
    }

    private Vec3i pos(CubeBearing.Folded f) {
        return new Vec3i((int) Math.round(f.x()),
                (int) Math.round(source.position().y()), (int) Math.round(f.z()));
    }

    private float azimuth(CubeBearing.Folded f) {
        Vec3 r = receiver.position();
        Vec3 dir = new Vec3(r.x() - f.x(), 0, r.z() - f.z()).rotateClockwise90();
        return (float) Math.atan2(dir.z(), dir.x());
    }

    private void send(ClientboundTrackedWaypointPacket packet) {
        receiver.connection.send(packet);
    }
}
