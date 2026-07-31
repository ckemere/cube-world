package com.ckemere.cubeworld.map;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.EdgeTransform;
import com.ckemere.cubeworld.geometry.Side;
import com.ckemere.cubeworld.geometry.Vec2;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Renders a 128x128 top-down terrain image the way a vanilla map does -- each
 * column coloured by its top block's vanilla map colour, shaded by the height
 * step to the column to its north -- but SEAM-FLATTENED for the cube net.
 *
 * <p>The point of a custom renderer here is only the seam. A vanilla map is a
 * flat square in world coordinates; our six faces sit in a plus-shaped net with
 * void between the arms, so a vanilla map held near a face edge renders that
 * void. Where a pixel falls off the map's home face into the net void, this
 * folds it onto the topologically-adjacent face via {@link CubeTopology} so the
 * neighbour's terrain continues across the seam. Crossings already contiguous in
 * the net (the equator strip, the poles above/below the prime face) need no fold
 * and are drawn directly. Corners -- where two seams meet -- are left as void;
 * that is pillar territory the player can't stand on anyway.
 *
 * <p>Colours come from live blocks, so player edits show, exactly like a vanilla
 * map. It NEVER triggers chunk generation: unloaded columns read as
 * "unexplored", so the map fills in as the player walks (and callers that want a
 * full image force-load {@link #sourceChunks} first).
 */
public final class CubeMapImage {

    public static final int VOID_ARGB = 0xFF10141C;        // off every face
    public static final int UNEXPLORED_ARGB = 0xFF3A4150;  // on a face, chunk not loaded
    public static final int SIZE = 128;

    private static final long NO_POINT = Long.MIN_VALUE;
    private static final int B_LOW = 180, B_NORMAL = 220, B_HIGH = 255;  // vanilla brightness /255

    private CubeMapImage() {
    }

    /** 128x128 ARGB pixels plus the flattened surface height per pixel
     * (Integer.MIN_VALUE where void/unexplored). */
    public record Rendered(int[] argb, int[] height, int size) {
    }

    public static Rendered render(World world, CubeGeometry geom, CubeTopology topo,
                                  int centerX, int centerZ, int blocksPerPixel) {
        int[] argb = new int[SIZE * SIZE];
        int[] height = new int[SIZE * SIZE];
        Color[] base = new Color[SIZE * SIZE];
        boolean[] unexplored = new boolean[SIZE * SIZE];
        CubeFace home = geom.faceAt(centerX, centerZ);

        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                int wx = centerX + (px - SIZE / 2) * blocksPerPixel;
                int wz = centerZ + (py - SIZE / 2) * blocksPerPixel;
                int idx = py * SIZE + px;
                long packed = resolve(geom, topo, home, wx, wz);
                if (packed == NO_POINT) {
                    height[idx] = Integer.MIN_VALUE;
                    continue;
                }
                int sx = (int) (packed >> 32);
                int sz = (int) packed;
                if (!world.isChunkLoaded(sx >> 4, sz >> 4)) {
                    unexplored[idx] = true;
                    height[idx] = Integer.MIN_VALUE;
                    continue;
                }
                Block top = world.getHighestBlockAt(sx, sz, HeightMap.MOTION_BLOCKING);
                base[idx] = top.getBlockData().getMapColor();
                height[idx] = top.getY();
            }
        }

        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                int idx = py * SIZE + px;
                if (unexplored[idx]) {
                    argb[idx] = UNEXPLORED_ARGB;
                    continue;
                }
                Color c = base[idx];
                if (c == null) {
                    argb[idx] = VOID_ARGB;
                    continue;
                }
                int mod = B_NORMAL;
                if (py > 0) {
                    int north = height[(py - 1) * SIZE + px];
                    if (north != Integer.MIN_VALUE) {
                        mod = height[idx] > north ? B_HIGH : height[idx] < north ? B_LOW : B_NORMAL;
                    }
                }
                int r = c.getRed() * mod / 255;
                int g = c.getGreen() * mod / 255;
                int b = c.getBlue() * mod / 255;
                argb[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return new Rendered(argb, height, SIZE);
    }

    /**
     * The distinct source chunks a render of this centre/scale reads, AFTER seam
     * folding -- so a caller (the offline PNG dump) can force-load exactly the
     * footprint (both the home face and any neighbour across a seam) and no more.
     */
    public static Set<Long> sourceChunks(CubeGeometry geom, CubeTopology topo,
                                         int centerX, int centerZ, int blocksPerPixel) {
        Set<Long> chunks = new HashSet<>();
        CubeFace home = geom.faceAt(centerX, centerZ);
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                int wx = centerX + (px - SIZE / 2) * blocksPerPixel;
                int wz = centerZ + (py - SIZE / 2) * blocksPerPixel;
                long packed = resolve(geom, topo, home, wx, wz);
                if (packed == NO_POINT) {
                    continue;
                }
                int cx = (int) (packed >> 32) >> 4;
                int cz = (int) packed >> 4;
                chunks.add(((long) cx << 32) | (cz & 0xffffffffL));
            }
        }
        return chunks;
    }

    private static long resolve(CubeGeometry geom, CubeTopology topo, CubeFace home,
                                int wx, int wz) {
        if (geom.faceAt(wx, wz) != null) {
            return pack(wx, wz);                 // already on a face -- contiguous
        }
        if (home == null) {
            return NO_POINT;
        }
        Side side = topo.exitSide(home, wx, wz);
        if (side == null) {
            return NO_POINT;
        }
        EdgeTransform t = topo.transformFor(home, side);
        if (t == null) {
            return NO_POINT;
        }
        Vec2 p = t.applyPoint(wx + 0.5, wz + 0.5);
        int sx = (int) Math.floor(p.x());
        int sz = (int) Math.floor(p.z());
        return geom.faceAt(sx, sz) != null ? pack(sx, sz) : NO_POINT;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
