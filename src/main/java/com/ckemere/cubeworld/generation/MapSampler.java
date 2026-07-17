package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeSurface;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.MarginSource;
import com.ckemere.cubeworld.geometry.Vec2;
import com.ckemere.cubeworld.geometry.Vec3;

/**
 * Samples a {@link MapSpec} at block resolution: bilinear interpolation
 * between chunk-cell centers for height, nearest cell for theme.
 *
 * <p>The seam-aware part: near a face edge, the 2x2 interpolation
 * neighborhood extends past the face. Off-face cell centers are resolved to
 * their real cells — directly for in-plane neighbors (world coordinates are
 * continuous there), through the stitched-edge transform otherwise. The
 * transforms are grid isometries (faces are chunk-aligned), so off-face
 * centers land exactly on partner-face cell centers and interpolation is
 * continuous across every seam.
 */
public final class MapSampler {

    private final CubeGeometry geometry;
    private final CubeTopology topology;
    private final CubeSurface surface;
    private final MapSpec spec;

    public MapSampler(CubeTopology topology, MapSpec spec) {
        this.topology = topology;
        this.geometry = topology.geometry();
        this.surface = new CubeSurface(topology.geometry());
        this.spec = spec;
    }

    public MapSpec spec() {
        return spec;
    }

    /** A world position resolved onto a face: margins collapse to their source. */
    private record Resolved(CubeFace face, double x, double z) {
    }

    private Resolved resolve(double worldX, double worldZ) {
        CubeFace face = geometry.faceAt((int) Math.floor(worldX), (int) Math.floor(worldZ));
        if (face != null) {
            return new Resolved(face, worldX, worldZ);
        }
        MarginSource source = topology.marginSource(worldX, worldZ, 16 * 8);
        if (source == null) {
            return null;
        }
        double sx = source.source().x();
        double sz = source.source().z();
        CubeFace sourceFace = geometry.faceAt((int) Math.floor(sx), (int) Math.floor(sz));
        return sourceFace == null ? null : new Resolved(sourceFace, sx, sz);
    }

    /**
     * The cube-surface 3D point for a world position (margins resolved to
     * their source), or null off the net. Continuous input for any
     * seam-consistent function of position (cave biomes, future climate...).
     */
    public Vec3 cubePointAt(double worldX, double worldZ) {
        Resolved r = resolve(worldX, worldZ);
        return r == null ? null : surface.point(r.face(), r.x(), r.z());
    }

    /**
     * Interpolated surface height at a world position (margins resolved to
     * their source).
     */
    public double heightAt(double worldX, double worldZ) {
        Resolved r = resolve(worldX, worldZ);
        if (r == null) {
            return SphericalDemoSpec.SEA_LEVEL; // deep void: pillars/void only
        }
        CubeFace face = r.face();
        double worldXr = r.x();
        double worldZr = r.z();
        // Continuous cell coordinates: cell centers at local 8, 24, 40, ...
        double fx = (worldXr - geometry.faceMinX(face) - 8.0) / 16.0;
        double fz = (worldZr - geometry.faceMinZ(face) - 8.0) / 16.0;
        int ix = (int) Math.floor(fx);
        int iz = (int) Math.floor(fz);
        double tx = fx - ix;
        double tz = fz - iz;
        double h00 = cellHeight(face, ix, iz);
        double h10 = cellHeight(face, ix + 1, iz);
        double h01 = cellHeight(face, ix, iz + 1);
        double h11 = cellHeight(face, ix + 1, iz + 1);
        return (1 - tx) * (1 - tz) * h00 + tx * (1 - tz) * h10
                + (1 - tx) * tz * h01 + tx * tz * h11;
    }

    /** Theme of the nearest cell (resolved through seams the same way). */
    public TerrainTheme themeAt(double worldX, double worldZ) {
        Resolved r = resolve(worldX, worldZ);
        if (r == null) {
            return spec.fallbackTheme();
        }
        int cx = clampCell((int) Math.floor((r.x() - geometry.faceMinX(r.face())) / 16.0));
        int cz = clampCell((int) Math.floor((r.z() - geometry.faceMinZ(r.face())) / 16.0));
        return spec.themeAt(r.face(), cx, cz);
    }

    /**
     * Height of the cell at (possibly off-face) cell indices of {@code face},
     * resolving across edges via the world position of the cell's center.
     */
    private double cellHeight(CubeFace face, int cellX, int cellZ) {
        int cells = spec.cellsPerFace();
        if (cellX >= 0 && cellX < cells && cellZ >= 0 && cellZ < cells) {
            return spec.heightAt(face, cellX, cellZ);
        }
        double centerX = geometry.faceMinX(face) + cellX * 16 + 8;
        double centerZ = geometry.faceMinZ(face) + cellZ * 16 + 8;
        CubeFace direct = geometry.faceAt((int) Math.floor(centerX), (int) Math.floor(centerZ));
        if (direct != null) {
            // In-plane neighbor: world coordinates are continuous.
            return spec.heightAt(direct,
                    clampCell((int) Math.floor((centerX - geometry.faceMinX(direct)) / 16.0)),
                    clampCell((int) Math.floor((centerZ - geometry.faceMinZ(direct)) / 16.0)));
        }
        MarginSource source = topology.marginSource(centerX, centerZ, 32);
        if (source != null) {
            Vec2 mapped = source.source();
            CubeFace partner = geometry.faceAt((int) Math.floor(mapped.x()), (int) Math.floor(mapped.z()));
            if (partner != null) {
                return spec.heightAt(partner,
                        clampCell((int) Math.floor((mapped.x() - geometry.faceMinX(partner)) / 16.0)),
                        clampCell((int) Math.floor((mapped.z() - geometry.faceMinZ(partner)) / 16.0)));
            }
        }
        // Corner wedge (pillar shadow): clamp to this face's edge cell.
        return spec.heightAt(face, clampCell(cellX), clampCell(cellZ));
    }

    private int clampCell(int idx) {
        return Math.max(0, Math.min(spec.cellsPerFace() - 1, idx));
    }
}
