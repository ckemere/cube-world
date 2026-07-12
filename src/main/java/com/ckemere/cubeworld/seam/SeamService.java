package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.EdgeTransform;
import com.ckemere.cubeworld.geometry.Vec2;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * Bridges the pure geometry to Bukkit: given an entity movement, decides
 * whether it crossed a stitched seam and produces the transformed
 * destination. Authoritative rule: anything crossing a stitched edge is
 * teleported to the partner face; the world outside the cross is never a
 * legitimate place to be.
 */
public final class SeamService {

    private final CubeGeometry geometry;
    private final CubeTopology topology;

    public SeamService(CubeTopology topology) {
        this.topology = topology;
        this.geometry = topology.geometry();
    }

    public CubeTopology topology() {
        return topology;
    }

    /**
     * The seam crossing for a move from {@code from} to {@code to}, or null
     * when the move stays within the net (same face or in-plane neighbor).
     * The returned location carries transformed x/z, unchanged y, rotated
     * yaw, and unchanged pitch.
     */
    public @Nullable Location seamDestination(Location from, Location to) {
        CubeFace fromFace = geometry.faceAt(from.getBlockX(), from.getBlockZ());
        if (fromFace == null) {
            return null; // already off the net (margins, void): not ours to fix here
        }
        EdgeTransform transform = topology.crossingFor(fromFace, to.getX(), to.getZ());
        if (transform == null) {
            return null;
        }
        Vec2 mapped = transform.applyPoint(to.getX(), to.getZ());
        Location dest = to.clone();
        dest.setX(mapped.x());
        dest.setZ(mapped.z());
        dest.setYaw(transform.applyYaw(to.getYaw()));
        return dest;
    }

    /** Rotate a velocity vector for the same crossing that produced {@code seamDestination}. */
    public Vector transformVelocity(Location from, Location to, Vector velocity) {
        CubeFace fromFace = geometry.faceAt(from.getBlockX(), from.getBlockZ());
        if (fromFace == null) {
            return velocity;
        }
        EdgeTransform transform = topology.crossingFor(fromFace, to.getX(), to.getZ());
        if (transform == null) {
            return velocity;
        }
        Vec2 rotated = transform.applyVector(velocity.getX(), velocity.getZ());
        return new Vector(rotated.x(), velocity.getY(), rotated.z());
    }
}
