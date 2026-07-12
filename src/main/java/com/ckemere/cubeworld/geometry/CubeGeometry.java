package com.ckemere.cubeworld.geometry;

/**
 * Maps world-plane block coordinates onto the unfolded-cube cross layout.
 * Pure math, no Bukkit types — everything else (generation, teleportation,
 * mirroring) consumes this.
 *
 * <p>The north-pole face is centered on the world origin: it spans
 * {@code [-faceSize/2, faceSize/2)} on both axes, so faces are chunk-aligned
 * as long as {@code faceSize} is a multiple of 32.
 */
public final class CubeGeometry {

    private final int faceSize;

    /**
     * @param faceSize edge length of one face in blocks; must be a positive
     *                 multiple of 32 so every face boundary is chunk-aligned
     */
    public CubeGeometry(int faceSize) {
        if (faceSize <= 0 || faceSize % 32 != 0) {
            throw new IllegalArgumentException("faceSize must be a positive multiple of 32, got " + faceSize);
        }
        this.faceSize = faceSize;
    }

    public int faceSize() {
        return faceSize;
    }

    /** The face containing world block (x, z), or null outside the cross. */
    public CubeFace faceAt(int x, int z) {
        int col = Math.floorDiv(x + faceSize / 2, faceSize);
        int row = Math.floorDiv(z + faceSize / 2, faceSize);
        for (CubeFace face : CubeFace.values()) {
            if (face.gridCol() == col && face.gridRow() == row) {
                return face;
            }
        }
        return null;
    }

    /** Minimum (north-west) world X of the face. */
    public int faceMinX(CubeFace face) {
        return face.gridCol() * faceSize - faceSize / 2;
    }

    /** Minimum (north-west) world Z of the face. */
    public int faceMinZ(CubeFace face) {
        return face.gridRow() * faceSize - faceSize / 2;
    }

    /** Face-local X in [0, faceSize) for a world X known to be on the face. */
    public int localX(CubeFace face, int x) {
        return x - faceMinX(face);
    }

    /** Face-local Z in [0, faceSize) for a world Z known to be on the face. */
    public int localZ(CubeFace face, int z) {
        return z - faceMinZ(face);
    }
}
