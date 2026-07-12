package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.MarginSource;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Keeps the far side of a seam alive. When a player stands near a stitched
 * edge, the partner strip across the cube is hundreds of net-blocks away and
 * would normally be unloaded — entities crossing the seam would freeze the
 * moment they arrive, and their margin clones would mirror statues. This
 * service holds plugin chunk tickets (entity-ticking) for a small radius
 * around each player's mirrored positions, refreshed every second.
 */
public final class PartnerTicketService {

    private static final int TICKET_RADIUS = 3;

    private final Plugin plugin;
    private final CubeTopology topology;
    private final int marginBlocks;
    private final Set<Long> held = new HashSet<>();

    public PartnerTicketService(Plugin plugin, CubeTopology topology, int marginBlocks) {
        this.plugin = plugin;
        this.topology = topology;
        this.marginBlocks = marginBlocks;
    }

    public void refresh(World world) {
        Set<Long> needed = new HashSet<>();
        for (Player player : world.getPlayers()) {
            double x = player.getLocation().getX();
            double z = player.getLocation().getZ();
            for (MarginSource image : topology.marginImages(x, z, marginBlocks)) {
                int cx = ((int) Math.floor(image.source().x())) >> 4;
                int cz = ((int) Math.floor(image.source().z())) >> 4;
                for (int dx = -TICKET_RADIUS; dx <= TICKET_RADIUS; dx++) {
                    for (int dz = -TICKET_RADIUS; dz <= TICKET_RADIUS; dz++) {
                        needed.add(key(cx + dx, cz + dz));
                    }
                }
            }
        }
        for (long k : needed) {
            if (held.add(k)) {
                world.addPluginChunkTicket(chunkX(k), chunkZ(k), plugin);
            }
        }
        held.removeIf(k -> {
            if (!needed.contains(k)) {
                world.removePluginChunkTicket(chunkX(k), chunkZ(k), plugin);
                return true;
            }
            return false;
        });
    }

    public void releaseAll(World world) {
        for (long k : held) {
            world.removePluginChunkTicket(chunkX(k), chunkZ(k), plugin);
        }
        held.clear();
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }
}
