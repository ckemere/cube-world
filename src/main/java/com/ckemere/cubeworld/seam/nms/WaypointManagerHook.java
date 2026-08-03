package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.geometry.CubeBearing;
import java.lang.reflect.Field;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Swaps a cube level's {@code ServerLevel.waypointManager} for a cube-aware
 * {@link CubeWaypointManager}, so locator-bar dots point along the cube geodesic. The
 * field is {@code final}, so we rebind it with {@code Unsafe} the way
 * {@link SphereRouterHook} does. Install per cube overworld/nether; leave the End vanilla.
 */
public final class WaypointManagerHook {

    private WaypointManagerHook() {
    }

    public static void install(World world, CubeBearing bearing, Logger log) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            CubeWaypointManager mgr = new CubeWaypointManager(level, bearing, log);
            putFinalObject(level, ServerLevel.class.getDeclaredField("waypointManager"), mgr);
            for (ServerPlayer p : level.players()) {   // migrate anyone already here
                mgr.addPlayer(p);
            }
            log.info("Cube locator bar active in world '" + world.getName() + "'");
        } catch (Throwable t) {
            log.warning("WaypointManagerHook: install failed (" + t + "); vanilla locator bar kept.");
        }
    }

    /** Rebind a final object field via Unsafe (final blocks plain reflection). */
    private static void putFinalObject(Object target, Field field, Object value) throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        unsafe.putObject(target, unsafe.objectFieldOffset(field), value);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }
}
