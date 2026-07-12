package com.ckemere.cubeworld.geometry;

/**
 * Orientation-preserving isometry of the net plane that carries the outside of
 * one stitched edge onto the inside of its partner edge: a rotation by
 * {@code quarterTurns} × 90° about the plane, expressed as
 * {@code p' = R^k (p - a0) + b0}.
 *
 * <p>The base rotation R maps (dx, dz) → (dz, -dx). In Minecraft terms
 * (X east, Z south, viewed from above with north up) that is a 90°
 * counter-clockwise turn on screen, and subtracts 90° from an entity's yaw.
 */
public final class EdgeTransform {

    private final int quarterTurns;
    private final double ax;
    private final double az;
    private final double bx;
    private final double bz;

    public EdgeTransform(int quarterTurns, double ax, double az, double bx, double bz) {
        this.quarterTurns = Math.floorMod(quarterTurns, 4);
        this.ax = ax;
        this.az = az;
        this.bx = bx;
        this.bz = bz;
    }

    public int quarterTurns() {
        return quarterTurns;
    }

    /** Rotate a direction/velocity vector (no translation). */
    public Vec2 applyVector(double dx, double dz) {
        double x = dx;
        double z = dz;
        for (int i = 0; i < quarterTurns; i++) {
            double nx = z;
            double nz = -x;
            x = nx;
            z = nz;
        }
        return new Vec2(x, z);
    }

    /** Map a point across the stitched edge. */
    public Vec2 applyPoint(double px, double pz) {
        Vec2 rotated = applyVector(px - ax, pz - az);
        return new Vec2(rotated.x() + bx, rotated.z() + bz);
    }

    /** Adjust a Minecraft yaw (degrees; 0 = +Z/south, 90 = -X/west). */
    public float applyYaw(float yaw) {
        float result = yaw - 90.0f * quarterTurns;
        while (result >= 180.0f) {
            result -= 360.0f;
        }
        while (result < -180.0f) {
            result += 360.0f;
        }
        return result;
    }

    public EdgeTransform inverse() {
        return new EdgeTransform(4 - quarterTurns, bx, bz, ax, az);
    }
}
