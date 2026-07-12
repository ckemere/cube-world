package com.ckemere.cubeworld.geometry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The stitching of the unfolded cube: which face sides are contiguous in the
 * net plane, which are torn cube edges, and the transform for crossing each
 * torn edge.
 *
 * <p>Each stitched edge is defined by its two boundary segments with endpoints
 * listed in corresponding cube-vertex order; the transform is the unique
 * orientation-preserving isometry mapping one segment onto the other, which
 * automatically carries the outward normal of one face onto the inward normal
 * of the other. Derivation (fold the cross, match vertices), h = faceSize/2:
 *
 * <pre>
 * link  faceA side   segment A            faceB side   segment B            turns
 * 1     EQ_PRIME  E  ( h, h)->( h,3h)     EQ_EAST   S  ( h, h)->( 3h, h)    1
 * 2     EQ_PRIME  W  (-h, h)->(-h,3h)     EQ_WEST   S  (-h, h)->(-3h, h)    3
 * 3     EQ_BACK   E  ( h,-h)->( h,-3h)    EQ_EAST   N  ( h,-h)->( 3h,-h)    3
 * 4     EQ_BACK   W  (-h,-h)->(-h,-3h)    EQ_WEST   N  (-h,-h)->(-3h,-h)    1
 * 5     SOUTH_POLE E ( h,3h)->( h,5h)     EQ_EAST   E  ( 3h, h)->( 3h,-h)   2
 * 6     SOUTH_POLE W (-h,3h)->(-h,5h)     EQ_WEST   W  (-3h, h)->(-3h,-h)   2
 * 7     SOUTH_POLE S ( h,5h)->(-h,5h)     EQ_BACK   N  ( h,-3h)->(-h,-3h)   0
 * </pre>
 *
 * The remaining five cube edges are contiguous in the net (the four around
 * NORTH_POLE, and EQ_PRIME–SOUTH_POLE).
 */
public final class CubeTopology {

    private final CubeGeometry geometry;
    private final List<EdgeLink> links;
    private final List<Vec2> pillarSites;

    public CubeTopology(CubeGeometry geometry) {
        this.geometry = geometry;
        double h = geometry.faceSize() / 2.0;
        List<EdgeLink> list = new ArrayList<>();
        list.add(link(CubeFace.EQ_PRIME, Side.EAST, v(h, h), v(h, 3 * h),
                CubeFace.EQ_EAST, Side.SOUTH, v(h, h), v(3 * h, h)));
        list.add(link(CubeFace.EQ_PRIME, Side.WEST, v(-h, h), v(-h, 3 * h),
                CubeFace.EQ_WEST, Side.SOUTH, v(-h, h), v(-3 * h, h)));
        list.add(link(CubeFace.EQ_BACK, Side.EAST, v(h, -h), v(h, -3 * h),
                CubeFace.EQ_EAST, Side.NORTH, v(h, -h), v(3 * h, -h)));
        list.add(link(CubeFace.EQ_BACK, Side.WEST, v(-h, -h), v(-h, -3 * h),
                CubeFace.EQ_WEST, Side.NORTH, v(-h, -h), v(-3 * h, -h)));
        list.add(link(CubeFace.SOUTH_POLE, Side.EAST, v(h, 3 * h), v(h, 5 * h),
                CubeFace.EQ_EAST, Side.EAST, v(3 * h, h), v(3 * h, -h)));
        list.add(link(CubeFace.SOUTH_POLE, Side.WEST, v(-h, 3 * h), v(-h, 5 * h),
                CubeFace.EQ_WEST, Side.WEST, v(-3 * h, h), v(-3 * h, -h)));
        list.add(link(CubeFace.SOUTH_POLE, Side.SOUTH, v(h, 5 * h), v(-h, 5 * h),
                CubeFace.EQ_BACK, Side.NORTH, v(h, -3 * h), v(-h, -3 * h)));
        this.links = List.copyOf(list);
        Set<Vec2> sites = new LinkedHashSet<>();
        for (EdgeLink l : this.links) {
            sites.add(l.a0());
            sites.add(l.a1());
            sites.add(l.b0());
            sites.add(l.b1());
        }
        this.pillarSites = List.copyOf(sites);
    }

    private static Vec2 v(double x, double z) {
        return new Vec2(x, z);
    }

    private static EdgeLink link(CubeFace faceA, Side sideA, Vec2 a0, Vec2 a1,
                                 CubeFace faceB, Side sideB, Vec2 b0, Vec2 b1) {
        int idxA = directionIndex(a1.x() - a0.x(), a1.z() - a0.z());
        int idxB = directionIndex(b1.x() - b0.x(), b1.z() - b0.z());
        int turns = Math.floorMod(idxB - idxA, 4);
        EdgeTransform transform = new EdgeTransform(turns, a0.x(), a0.z(), b0.x(), b0.z());
        return new EdgeLink(faceA, sideA, a0, a1, faceB, sideB, b0, b1, transform);
    }

    /** Index of an axis direction under the base rotation cycle (+X, -Z, -X, +Z). */
    private static int directionIndex(double dx, double dz) {
        if (dx > 0 && dz == 0) {
            return 0;
        }
        if (dx == 0 && dz < 0) {
            return 1;
        }
        if (dx < 0 && dz == 0) {
            return 2;
        }
        if (dx == 0 && dz > 0) {
            return 3;
        }
        throw new IllegalArgumentException("segment is not axis-aligned: (" + dx + ", " + dz + ")");
    }

    public CubeGeometry geometry() {
        return geometry;
    }

    public List<EdgeLink> links() {
        return links;
    }

    /**
     * The transform to apply when leaving {@code face} across {@code side},
     * or null when that side is contiguous in the net (no transform needed).
     */
    public EdgeTransform transformFor(CubeFace face, Side side) {
        for (EdgeLink l : links) {
            if (l.faceA() == face && l.sideA() == side) {
                return l.aToB();
            }
            if (l.faceB() == face && l.sideB() == side) {
                return l.aToB().inverse();
            }
        }
        return null;
    }

    /**
     * Which side of {@code face} the point (x, z) lies beyond; null if the
     * point is on the face. When beyond two sides (a corner), the side with
     * the larger overshoot wins.
     */
    public Side exitSide(CubeFace face, double x, double z) {
        double minX = geometry.faceMinX(face);
        double minZ = geometry.faceMinZ(face);
        double maxX = minX + geometry.faceSize();
        double maxZ = minZ + geometry.faceSize();
        double overEast = x - maxX;
        double overWest = minX - x;
        double overSouth = z - maxZ;
        double overNorth = minZ - z;
        Side side = null;
        double best = 0;
        if (overEast > best) {
            side = Side.EAST;
            best = overEast;
        }
        if (overWest > best) {
            side = Side.WEST;
            best = overWest;
        }
        if (overSouth > best) {
            side = Side.SOUTH;
            best = overSouth;
        }
        if (overNorth > best) {
            side = Side.NORTH;
        }
        return side;
    }

    /**
     * The transform for a move that left {@code fromFace} and arrived at
     * (x, z), or null when the move is contiguous in the net (same face or
     * an in-plane neighbor) and no teleport is needed.
     */
    public EdgeTransform crossingFor(CubeFace fromFace, double x, double z) {
        CubeFace toFace = geometry.faceAt((int) Math.floor(x), (int) Math.floor(z));
        if (toFace == fromFace) {
            return null;
        }
        Side side = exitSide(fromFace, x, z);
        if (side == null) {
            return null;
        }
        return transformFor(fromFace, side);
    }

    /** One direction of a stitched edge: the segment on {@code face}'s boundary, and the transform outward. */
    private record DirectedEdge(EdgeLink link, CubeFace face, Vec2 s0, Vec2 s1, EdgeTransform outward) {
    }

    private List<DirectedEdge> directedEdges() {
        List<DirectedEdge> dirs = new ArrayList<>();
        for (EdgeLink l : links) {
            dirs.add(new DirectedEdge(l, l.faceA(), l.a0(), l.a1(), l.aToB()));
            dirs.add(new DirectedEdge(l, l.faceB(), l.b0(), l.b1(), l.aToB().inverse()));
        }
        return dirs;
    }

    /**
     * Signed outward distance of (x, z) from the directed edge's boundary
     * (positive = outside the face), or NaN when the point is beyond the
     * segment's tangential span.
     */
    private double outwardDistance(DirectedEdge d, double x, double z) {
        double len = geometry.faceSize();
        double ux = Math.signum(d.s1().x() - d.s0().x());
        double uz = Math.signum(d.s1().z() - d.s0().z());
        double t = (x - d.s0().x()) * ux + (z - d.s0().z()) * uz;
        if (t < 0 || t > len) {
            return Double.NaN;
        }
        // Outward normal: perpendicular to the segment, away from the face center.
        double cx = geometry.faceMinX(d.face()) + len / 2.0;
        double cz = geometry.faceMinZ(d.face()) + len / 2.0;
        double nx;
        double nz;
        if (ux == 0) { // segment runs along Z: normal is +-X
            nx = Math.signum(d.s0().x() - cx);
            nz = 0;
        } else { // segment runs along X: normal is +-Z
            nx = 0;
            nz = Math.signum(d.s0().z() - cz);
        }
        return (x - d.s0().x()) * nx + (z - d.s0().z()) * nz;
    }

    /**
     * Resolves a point in a seam margin to the real position it mirrors, or
     * null when (x, z) is not within {@code margin} blocks beyond a stitched
     * edge. At corner overlaps (two candidate edges) the first match wins;
     * those wedges are pillar territory anyway.
     */
    public MarginSource marginSource(double x, double z, double margin) {
        for (DirectedEdge d : directedEdges()) {
            double out = outwardDistance(d, x, z);
            if (!Double.isNaN(out) && out > 0 && out <= margin) {
                return new MarginSource(d.link(), d.outward(), d.outward().applyPoint(x, z));
            }
        }
        return null;
    }

    /**
     * Margin images of a real position: for a point on a face within
     * {@code margin} blocks of one or more stitched edges, the mirrored
     * position(s) in the partner faces' margins (with the transform that
     * produced each, for rotating block states). Empty for points away from
     * stitched edges.
     */
    public List<MarginSource> marginImages(double x, double z, double margin) {
        List<MarginSource> images = new ArrayList<>();
        CubeFace face = geometry.faceAt((int) Math.floor(x), (int) Math.floor(z));
        if (face == null) {
            return images;
        }
        for (DirectedEdge d : directedEdges()) {
            if (d.face() != face) {
                continue;
            }
            double out = outwardDistance(d, x, z);
            if (!Double.isNaN(out) && out <= 0 && -out <= margin) {
                images.add(new MarginSource(d.link(), d.outward(), d.outward().applyPoint(x, z)));
            }
        }
        return images;
    }

    /**
     * Net-plane images of the eight cube vertices: the deduplicated endpoints
     * of the stitched segments. These are the spots that cannot be rendered
     * consistently and get corner pillars.
     */
    public List<Vec2> pillarSites() {
        return pillarSites;
    }

    /**
     * Is (x, z) within {@code radius} of a cube-vertex net image? Those spots
     * cannot be rendered consistently (a cube vertex has 270° of terrain, the
     * plane has 360°), so they are filled with unbreakable pillars.
     */
    public boolean inPillar(double x, double z, double radius) {
        for (Vec2 site : pillarSites()) {
            double dx = x - site.x();
            double dz = z - site.z();
            if (dx * dx + dz * dz <= radius * radius) {
                return true;
            }
        }
        return false;
    }
}
