package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.generation.MapSampler;
import com.ckemere.cubeworld.generation.SphereDensity;
import java.lang.reflect.Field;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Replaces the world's terrain {@link NoiseRouter} with a sphere-folded copy
 * ({@link SphereDensity}), so vanilla's own generator places every block —
 * terrain shape, surface rules, aquifers, lava, density caves and ore veins —
 * but sampled on the cube's sphere embedding, which makes it seam-consistent
 * for free. Must run before the world's spawn chunks generate (wired on
 * {@code WorldInitEvent}).
 *
 * <p>{@code RandomState.router} is {@code private final}; modern reflection
 * cannot rebind a final reference, so we use {@link sun.misc.Unsafe} the way
 * the platform itself does for late field patching. The noises inside the
 * router are already instantiated, so folding wraps the live noise nodes.
 */
public final class SphereRouterHook {

    private SphereRouterHook() {
    }

    public static boolean install(World world, MapSampler sampler, int faceSize, Logger log) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            RandomState rs = level.getChunkSource().randomState();
            if (rs == null) {
                log.warning("Sphere router hook: no RandomState yet for '" + world.getName() + "'.");
                return false;
            }
            NoiseRouter folded = SphereDensity.forSampler(sampler, faceSize).fold(rs.router());
            putFinalObject(rs, RandomState.class.getDeclaredField("router"), folded);
            log.info("Sphere router hook: vanilla terrain folded onto the cube for '"
                    + world.getName() + "'.");
            return true;
        } catch (Throwable t) {
            log.warning("Sphere router hook failed (" + t + "); demo terrain remains.");
            return false;
        }
    }

    /** Rebind a final object field via Unsafe (final blocks plain reflection). */
    private static void putFinalObject(Object target, Field field, Object value) throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        long offset = unsafe.objectFieldOffset(field);
        unsafe.putObject(target, offset, value);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }
}
