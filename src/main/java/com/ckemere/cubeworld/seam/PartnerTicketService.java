package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.MarginSource;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Keeps the far side of a seam alive. When a player stands near a stitched
 * edge, the partner strip across the cube is hundreds of net-blocks away and
 * would normally be unloaded — entities crossing the seam would freeze the
 * moment they arrive, their margin clones would mirror statues, and
 * non-persistent mobs would despawn (vanilla's 128-block rule measures
 * net-plane distance, not cube distance). This service holds plugin chunk
 * tickets (entity-ticking) for a small radius around each player's mirrored
 * positions and despawn-protects mobs that are topologically near a player,
 * refreshed every second.
 */
public final class PartnerTicketService {

    private static final int TICKET_RADIUS = 3;
    /** Vanilla's hard despawn distance. */
    private static final double DESPAWN_RANGE = 128.0;

    private final Plugin plugin;
    private final CubeTopology topology;
    private final int marginBlocks;
    private final Set<Long> held = new HashSet<>();
    private final NamespacedKey keptKey;
    private final NamespacedKey cloneKey;
    private final Set<UUID> keptMobs = new HashSet<>();

    public PartnerTicketService(Plugin plugin, CubeTopology topology, int marginBlocks) {
        this.plugin = plugin;
        this.topology = topology;
        this.marginBlocks = marginBlocks;
        this.keptKey = new NamespacedKey(plugin, "seam-kept");
        this.cloneKey = new NamespacedKey(plugin, "clone-of");
    }

    public void refresh(World world) {
        Set<Long> needed = new HashSet<>();
        Set<UUID> protectedNow = new HashSet<>();
        for (Player player : world.getPlayers()) {
            Location loc = player.getLocation();
            for (MarginSource image : topology.marginImages(loc.getX(), loc.getZ(), marginBlocks)) {
                int cx = ((int) Math.floor(image.source().x())) >> 4;
                int cz = ((int) Math.floor(image.source().z())) >> 4;
                for (int dx = -TICKET_RADIUS; dx <= TICKET_RADIUS; dx++) {
                    for (int dz = -TICKET_RADIUS; dz <= TICKET_RADIUS; dz++) {
                        needed.add(key(cx + dx, cz + dz));
                    }
                }
                protectMobsNear(world, new Location(world, image.source().x(), loc.getY(),
                        image.source().z()), protectedNow);
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
        releaseStaleProtection(protectedNow);
    }

    /**
     * A mob within despawn range of a player's cross-seam image is
     * topologically close to that player: exempt it from far-away despawn
     * until nobody is near either side.
     */
    private void protectMobsNear(World world, Location image, Set<UUID> protectedNow) {
        for (Entity entity : world.getNearbyEntities(image, DESPAWN_RANGE, DESPAWN_RANGE, DESPAWN_RANGE)) {
            if (!(entity instanceof Mob mob)
                    || mob.getPersistentDataContainer().has(cloneKey, PersistentDataType.STRING)) {
                continue;
            }
            protectedNow.add(mob.getUniqueId());
            if (mob.getRemoveWhenFarAway() && keptMobs.add(mob.getUniqueId())) {
                mob.setRemoveWhenFarAway(false);
                mob.getPersistentDataContainer().set(keptKey, PersistentDataType.BYTE, (byte) 1);
            }
        }
    }

    /** Mobs no longer near any player image get their vanilla despawn back. */
    private void releaseStaleProtection(Set<UUID> protectedNow) {
        Iterator<UUID> it = keptMobs.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            if (protectedNow.contains(id)) {
                continue;
            }
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Mob mob && mob.isValid()) {
                mob.setRemoveWhenFarAway(true);
                mob.getPersistentDataContainer().remove(keptKey);
            }
            it.remove();
        }
    }

    /** Restore vanilla despawn for kept mobs loaded from disk (e.g. after a crash). */
    public void reviewLoadedMob(Mob mob) {
        if (mob.getPersistentDataContainer().has(keptKey, PersistentDataType.BYTE)
                && !keptMobs.contains(mob.getUniqueId())) {
            mob.setRemoveWhenFarAway(true);
            mob.getPersistentDataContainer().remove(keptKey);
        }
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
