package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

/**
 * Strongholds, cube-corrected. Vanilla places its ~128 strongholds in
 * concentric rings around world origin (0, 0) — which on the net is the centre
 * of the North Pole face, so the outer rings spiral off into the empty void
 * cells of the cross and are lost. Instead we distribute the same number of
 * points evenly on the sphere (a Fibonacci spiral) and fold each to the cube
 * net via the inverse embedding, spreading strongholds across all six faces.
 *
 * <p>The positions are injected into {@code ChunkGeneratorStructureState}'s
 * ring-position map before vanilla computes its own, so the rest of the
 * stronghold machinery (placement, eye-of-ender targeting) uses ours.
 */
public final class StrongholdSphereHook {

    private StrongholdSphereHook() {
    }

    public static boolean install(World world, CubeGeometry geometry, CubeTopology topology,
                                  int marginBlocks, Plugin plugin, Logger log) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            ConcentricRingsStructurePlacement placement = findStrongholdPlacement(level);
            if (placement == null) {
                log.info("Stronghold hook: no concentric-rings placement; leaving vanilla strongholds.");
                return false;
            }
            List<ChunkPos> positions = spherePositions(placement.count(), geometry, topology, marginBlocks);

            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            Field ringField = ChunkGeneratorStructureState.class.getDeclaredField("ringPositions");
            ringField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> ringMap =
                    (Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>>) ringField.get(state);
            ringMap.put(placement, CompletableFuture.completedFuture(positions));

            Field genFlag = ChunkGeneratorStructureState.class.getDeclaredField("hasGeneratedPositions");
            genFlag.setAccessible(true);
            genFlag.setBoolean(state, true);

            log.info("Stronghold hook: " + positions.size() + " strongholds distributed across the cube.");
            return true;
        } catch (Throwable t) {
            log.warning("Stronghold hook failed (" + t + "); vanilla strongholds remain.");
            return false;
        }
    }

    private static ConcentricRingsStructurePlacement findStrongholdPlacement(ServerLevel level) {
        for (StructureSet set : level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)) {
            if (set.placement() instanceof ConcentricRingsStructurePlacement crp) {
                return crp;
            }
        }
        return null;
    }

    /** Fibonacci-sphere points folded to cube-net chunk positions, skipping pillars. */
    private static List<ChunkPos> spherePositions(int count, CubeGeometry geometry,
                                                  CubeTopology topology, int marginBlocks) {
        List<ChunkPos> out = new ArrayList<>();
        double golden = Math.PI * (Math.sqrt(5.0) - 1.0);
        for (int i = 0; i < count; i++) {
            double yv = 1.0 - (i / (double) (count - 1)) * 2.0;   // 1 .. -1
            double r = Math.sqrt(Math.max(0.0, 1.0 - yv * yv));
            double theta = golden * i;
            double xv = Math.cos(theta) * r;
            double zv = Math.sin(theta) * r;
            int[] xz = dirToNet(xv, yv, zv, geometry);
            if (topology.inPillar(xz[0] + 0.5, xz[1] + 0.5, marginBlocks)) {
                continue;   // never in a corner pillar
            }
            out.add(new ChunkPos(xz[0] >> 4, xz[1] >> 4));
        }
        return out;
    }

    /**
     * Inverse of {@code CubeSurface.point}: a cube-frame direction to a net
     * world (x, z). Dominant axis picks the face; the other two give (u, v).
     */
    private static int[] dirToNet(double x, double y, double z, CubeGeometry geometry) {
        double ax = Math.abs(x);
        double ay = Math.abs(y);
        double az = Math.abs(z);
        CubeFace face;
        double u;
        double v;
        if (ay >= ax && ay >= az) {
            double s = 1.0 / ay;
            double px = x * s;
            double pz = z * s;
            if (y > 0) {
                face = CubeFace.NORTH_POLE; u = px; v = pz;
            } else {
                face = CubeFace.SOUTH_POLE; u = px; v = -pz;
            }
        } else if (az >= ax) {
            double s = 1.0 / az;
            double px = x * s;
            double py = y * s;
            if (z > 0) {
                face = CubeFace.EQ_PRIME; u = px; v = -py;
            } else {
                face = CubeFace.EQ_BACK; u = px; v = py;
            }
        } else {
            double s = 1.0 / ax;
            double py = y * s;
            double pz = z * s;
            if (x > 0) {
                face = CubeFace.EQ_EAST; u = -py; v = pz;
            } else {
                face = CubeFace.EQ_WEST; u = py; v = pz;
            }
        }
        double h = geometry.faceSize() / 2.0;
        int wx = (int) Math.round((u + 1.0) * h + geometry.faceMinX(face));
        int wz = (int) Math.round((v + 1.0) * h + geometry.faceMinZ(face));
        return new int[] {wx, wz};
    }
}
