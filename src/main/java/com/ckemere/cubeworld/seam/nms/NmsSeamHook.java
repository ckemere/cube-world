package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.seam.MirrorService;
import java.lang.reflect.Field;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

/**
 * Installs the {@link SeamNeighborUpdater} into a world's ServerLevel by
 * swapping the {@code Level.neighborUpdater} field (located by type, not
 * name, to survive refactors). Failure is non-fatal: the plugin logs and
 * runs without cross-seam neighbor updates.
 */
public final class NmsSeamHook {

    private NmsSeamHook() {
    }

    public static boolean install(World world, CubeTopology topology, MirrorService mirrors,
                                  Plugin plugin, Logger log) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            SeamNeighborUpdater updater = new SeamNeighborUpdater(level,
                    level.getServer().getMaxChainedNeighborUpdates(), topology, mirrors, plugin);
            Field field = null;
            for (Field candidate : Level.class.getDeclaredFields()) {
                if (candidate.getType() == CollectingNeighborUpdater.class) {
                    field = candidate;
                    break;
                }
            }
            if (field == null) {
                log.warning("NMS seam hook: no CollectingNeighborUpdater field on Level; "
                        + "cross-seam neighbor updates disabled.");
                return false;
            }
            field.setAccessible(true);
            field.set(level, updater);
            log.info("NMS seam hook installed: cross-seam neighbor updates active.");
            return true;
        } catch (Throwable t) {
            log.warning("NMS seam hook failed to install (" + t
                    + "); cross-seam neighbor updates disabled.");
            return false;
        }
    }
}
