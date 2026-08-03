package com.ckemere.cubeworld.geometry;

import java.util.EnumMap;
import java.util.Map;

/**
 * Folds a target's world position into a <em>receiver's</em> cube-face coordinate
 * frame, so that plain flat world-XZ direction and distance from the receiver to the
 * folded point give the true on-surface bearing/distance across a seam.
 *
 * <p>This is the shared geometry behind cube-aware navigation UI (the locator-bar
 * waypoint hook and map cursors): the vanilla client always does flat {@code atan2}
 * of {@code (target - receiver)}, which is only correct when both are on the same
 * face. Feeding it a folded position makes that naive math correct.
 *
 * <p>The fold is <em>exact</em> for same-face and single-seam-adjacent targets:
 * cube faces are flat, so unfolding the neighbour coplanar with the receiver's face
 * turns the bent geodesic into a straight line, and {@link EdgeTransform} is an
 * isometry (direction, distance and Y are all preserved). For a target more than one
 * seam away ({@link Folded#adjacent()} is false) there is no single consistent fold;
 * we return the nearest single-seam fold as a direction-only approximation, which the
 * caller should emit as an azimuth rather than a precise position.
 *
 * <p>Pure geometry: no game/NMS dependencies, so it is unit-tested directly.
 */
public final class CubeBearing {

    private final CubeGeometry geom;
    private final CubeTopology topo;
    /** (fromFace, toFace) -> the side of fromFace that borders toFace, for adjacent pairs. */
    private final Map<CubeFace, Map<CubeFace, Side>> sideToward = new EnumMap<>(CubeFace.class);

    public CubeBearing(CubeTopology topo) {
        this.topo = topo;
        this.geom = topo.geometry();
        for (CubeFace f : CubeFace.values()) {
            sideToward.put(f, new EnumMap<>(CubeFace.class));
        }
        for (EdgeLink l : topo.links()) {
            sideToward.get(l.faceA()).put(l.faceB(), l.sideA());
            sideToward.get(l.faceB()).put(l.faceA(), l.sideB());
        }
    }

    /**
     * A target expressed in the receiver's face frame. {@code adjacent} is true when the
     * fold is exact (same face or one seam away) and the caller may send it as a precise
     * position; false when it is a direction-only approximation for a far target.
     * {@code quarterTurns} is the net rotation the fold applied (0 for same face), so a
     * caller can rotate the source's facing/velocity to match ({@link EdgeTransform}
     * subtracts 90° per turn from a yaw).
     */
    public record Folded(double x, double z, boolean adjacent, int quarterTurns) {
    }

    /** Fold target {@code (sx,sz)} into the frame of the face containing {@code (rx,rz)}. */
    public Folded fold(double rx, double rz, double sx, double sz) {
        CubeFace a = geom.faceAt((int) Math.floor(rx), (int) Math.floor(rz));
        CubeFace b = geom.faceAt((int) Math.floor(sx), (int) Math.floor(sz));
        if (a == null || b == null) {
            return new Folded(sx, sz, a == b, 0);       // off the net: nothing to fold
        }
        if (a == b) {
            return new Folded(sx, sz, true, 0);         // same face: raw is already correct
        }
        Side s = sideToward.get(a).get(b);
        if (s != null) {                                // adjacent: exact single-seam fold
            EdgeTransform t = topo.transformFor(a, s).inverse();
            Vec2 f = t.applyPoint(sx, sz);
            return new Folded(f.x(), f.z(), true, t.quarterTurns());
        }
        // Non-adjacent: no single consistent fold. Approximate the initial direction by
        // the seam of A whose fold lands nearest the receiver (i.e. that most points at B).
        double bestSq = Double.MAX_VALUE;
        Vec2 best = null;
        int bestTurns = 0;
        for (Side side : Side.values()) {
            EdgeTransform t = topo.transformFor(a, side);
            if (t == null) {
                continue;
            }
            EdgeTransform inv = t.inverse();
            Vec2 f = inv.applyPoint(sx, sz);
            double dsq = sq(f.x() - rx) + sq(f.z() - rz);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = f;
                bestTurns = inv.quarterTurns();
            }
        }
        return best == null
                ? new Folded(sx, sz, false, 0)
                : new Folded(best.x(), best.z(), false, bestTurns);
    }

    private static double sq(double v) {
        return v * v;
    }
}
