package com.ckemere.cubeworld.geometry;

/**
 * Embeds the net plane onto the surface of the unit cube [-1, 1]^3: the
 * inverse of unfolding. Any function of the 3D point is automatically
 * continuous across every edge — stitched or in-plane — because both sides of
 * an identified edge map to the same 3D points. This is the bridge to real
 * planetary data later (cube point -> lat/lon -> raster).
 *
 * <p>Orientations were derived by folding the equatorial band (NORTH_POLE is
 * the +Y top face, and the four equatorial faces all point north = +Y up their
 * -Z edge); {@code CubeSurfaceTest} verifies agreement along all 12 cube edges
 * against the {@link CubeTopology} identifications.
 */
public final class CubeSurface {

    private final CubeGeometry geometry;

    public CubeSurface(CubeGeometry geometry) {
        this.geometry = geometry;
    }

    /**
     * The 3D cube-surface point for a world-plane position on {@code face}.
     * Face-local coordinates are normalized to [-1, 1].
     */
    public Vec3 point(CubeFace face, double worldX, double worldZ) {
        double h = geometry.faceSize() / 2.0;
        double lx = (worldX - geometry.faceMinX(face)) / h - 1.0;
        double lz = (worldZ - geometry.faceMinZ(face)) / h - 1.0;
        // Every equatorial face has Y = -lz, so latitude (asin Y) rises toward
        // the -Z (north) edge: north is up on all four. The horizontal axis
        // walks longitude eastward continuously across the band.
        return switch (face) {
            case NORTH_POLE -> new Vec3(lx, 1.0, lz);
            case EQ_PRIME -> new Vec3(lx, -lz, 1.0);
            case SOUTH_POLE -> new Vec3(lx, -1.0, -lz);
            case EQ_EAST -> new Vec3(1.0, -lz, -lx);
            case EQ_BACK -> new Vec3(-lx, -lz, -1.0);
            case EQ_WEST -> new Vec3(-1.0, -lz, lx);
        };
    }
}
