package com.ckemere.cubeworld.seam;

import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.geometry.EdgeTransform;
import com.ckemere.cubeworld.geometry.MarginSource;
import com.ckemere.cubeworld.geometry.Vec2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Entities and seams, one authoritative rule: any real entity that crosses a
 * stitched edge teleports to the partner face exactly like a player. Real
 * entities near a seam additionally get visual clones in the margin across it
 * — AI-less, invulnerable, non-persistent puppets whose position is re-synced
 * from the source every tick. Clones are pure output; interactions with them
 * are forwarded to the source by {@link EntitySeamListener}.
 */
public final class EntityMirrorService {

    private final Plugin plugin;
    private final CubeTopology topology;
    private final int marginBlocks;
    private final NamespacedKey cloneKey;

    /** Last face each tracked real entity was seen on (for crossing detection). */
    private final Map<UUID, CubeFace> lastFace = new HashMap<>();
    /** Real entity id -> its live clones, keyed by the transform's identity. */
    private final Map<UUID, List<Clone>> clones = new HashMap<>();

    private record Clone(Entity entity, int quarterTurns) {
    }

    public EntityMirrorService(Plugin plugin, CubeTopology topology, int marginBlocks) {
        this.plugin = plugin;
        this.topology = topology;
        this.marginBlocks = marginBlocks;
        this.cloneKey = new NamespacedKey(plugin, "clone-of");
    }

    public boolean isClone(Entity entity) {
        return entity.getPersistentDataContainer().has(cloneKey, PersistentDataType.STRING);
    }

    /** The real entity a clone mirrors, or null if unknown/unloaded. */
    public Entity sourceOf(Entity clone) {
        String id = clone.getPersistentDataContainer().get(cloneKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        try {
            return clone.getServer().getEntity(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Remove stray clones (e.g. left over from a crash) in a just-loaded chunk. */
    public void cullStrayClones(org.bukkit.Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (isClone(entity) && !isManaged(entity)) {
                entity.remove();
            }
        }
    }

    private boolean isManaged(Entity candidate) {
        for (List<Clone> list : clones.values()) {
            for (Clone c : list) {
                if (c.entity().getUniqueId().equals(candidate.getUniqueId())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void removeAllClones() {
        for (List<Clone> list : clones.values()) {
            for (Clone c : list) {
                c.entity().remove();
            }
        }
        clones.clear();
        lastFace.clear();
    }

    /** Runs every tick on the main world. */
    public void tick(World world) {
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player || isClone(entity) || !entity.isValid()) {
                continue;
            }
            handleCrossing(entity);
            syncClones(entity);
        }
        // Drop registry entries whose source disappeared — including sources
        // that left this world (e.g. rode a nether portal): getEntity is a
        // global lookup, and a clone must not outlive its source's presence
        // in the world it mirrors.
        Iterator<Map.Entry<UUID, List<Clone>>> it = clones.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<Clone>> entry = it.next();
            Entity source = org.bukkit.Bukkit.getEntity(entry.getKey());
            if (source == null || !source.isValid() || !source.getWorld().equals(world)) {
                for (Clone c : entry.getValue()) {
                    c.entity().remove();
                }
                it.remove();
                lastFace.remove(entry.getKey());
            }
        }
    }

    /** Carry a real entity that walked/flew/rolled across a stitched edge. */
    private void handleCrossing(Entity entity) {
        Location loc = entity.getLocation();
        CubeFace face = topology.geometry().faceAt(loc.getBlockX(), loc.getBlockZ());
        if (face != null) {
            lastFace.put(entity.getUniqueId(), face);
            return;
        }
        CubeFace from = lastFace.get(entity.getUniqueId());
        if (from == null) {
            return; // spawned off-net (e.g. dropped in a margin): leave it be
        }
        EdgeTransform transform = topology.crossingFor(from, loc.getX(), loc.getZ());
        if (transform == null) {
            return;
        }
        Vec2 mapped = transform.applyPoint(loc.getX(), loc.getZ());
        Vector velocity = entity.getVelocity();
        Vec2 rotated = transform.applyVector(velocity.getX(), velocity.getZ());
        Vector newVelocity = new Vector(rotated.x(), velocity.getY(), rotated.z());
        Location dest = loc.clone();
        dest.setX(mapped.x());
        dest.setZ(mapped.z());
        dest.setYaw(transform.applyYaw(loc.getYaw()));
        CubeFace newFace = topology.geometry().faceAt((int) Math.floor(mapped.x()), (int) Math.floor(mapped.z()));
        if (entity instanceof Projectile && !(entity instanceof org.bukkit.entity.FishHook)) {
            // A projectile's own yaw field lags its motion (server-side
            // rotation smoothing), so transforming it propagates a stale
            // heading. Derive the exact heading from the transformed velocity
            // instead, and spawn a fresh replacement bearing it — creation-
            // time rotation is the only write that fully sticks.
            double horizontal = Math.sqrt(newVelocity.getX() * newVelocity.getX()
                    + newVelocity.getZ() * newVelocity.getZ());
            if (horizontal > 1e-6 || Math.abs(newVelocity.getY()) > 1e-6) {
                dest.setYaw((float) Math.toDegrees(Math.atan2(-newVelocity.getX(), newVelocity.getZ())));
                dest.setPitch((float) Math.toDegrees(-Math.atan2(newVelocity.getY(), horizontal)));
            }
            replaceAcrossSeam(entity, dest, newVelocity, newFace);
            return;
        }
        entity.teleport(dest);
        entity.setVelocity(newVelocity);
        if (newFace != null) {
            lastFace.put(entity.getUniqueId(), newFace);
        }
    }

    private void replaceAcrossSeam(Entity old, Location dest, Vector velocity, CubeFace newFace) {
        lastFace.remove(old.getUniqueId());
        Entity fresh;
        if (old instanceof org.bukkit.entity.AbstractArrow oldArrow) {
            // Rotation writes on live projectiles never reach their lerp
            // state (snapshot/teleport variants all resume the old heading
            // and visibly twist). Creation-time rotation does: spawn a fresh
            // arrow at a location bearing the new yaw and copy state.
            fresh = spawnArrowCopy(oldArrow, dest);
        } else {
            org.bukkit.entity.EntitySnapshot snapshot = old.createSnapshot();
            fresh = snapshot == null ? null : snapshot.createEntity(dest);
        }
        old.remove();
        if (fresh == null) {
            return;
        }
        fresh.setVelocity(velocity);
        if (newFace != null) {
            lastFace.put(fresh.getUniqueId(), newFace);
        }
    }

    private Entity spawnArrowCopy(org.bukkit.entity.AbstractArrow old, Location dest) {
        Class<? extends org.bukkit.entity.AbstractArrow> type;
        if (old instanceof org.bukkit.entity.SpectralArrow) {
            type = org.bukkit.entity.SpectralArrow.class;
        } else if (old instanceof org.bukkit.entity.Trident) {
            type = org.bukkit.entity.Trident.class;
        } else {
            type = org.bukkit.entity.Arrow.class;
        }
        return dest.getWorld().spawn(dest, type, fresh -> {
            fresh.setDamage(old.getDamage());
            fresh.setCritical(old.isCritical());
            fresh.setPierceLevel(old.getPierceLevel());
            fresh.setPickupStatus(old.getPickupStatus());
            fresh.setShooter(old.getShooter());
            fresh.setFireTicks(old.getFireTicks());
            if (old instanceof org.bukkit.entity.Arrow oldTipped
                    && fresh instanceof org.bukkit.entity.Arrow freshTipped) {
                freshTipped.setBasePotionType(oldTipped.getBasePotionType());
                for (org.bukkit.potion.PotionEffect effect : oldTipped.getCustomEffects()) {
                    freshTipped.addCustomEffect(effect, true);
                }
            }
        });
    }

    /** Create/move/remove the margin clones of a real entity. */
    private void syncClones(Entity source) {
        Location loc = source.getLocation();
        List<MarginSource> images = topology.marginImages(loc.getX(), loc.getZ(), marginBlocks);
        List<Clone> existing = clones.computeIfAbsent(source.getUniqueId(), k -> new ArrayList<>());

        // Remove clones whose image no longer exists (moved away from seam).
        existing.removeIf(c -> {
            boolean still = images.stream()
                    .anyMatch(img -> img.toSource().quarterTurns() == c.quarterTurns());
            if (!still || !c.entity().isValid()) {
                c.entity().remove();
                return true;
            }
            return false;
        });

        for (MarginSource image : images) {
            Vec2 pos = image.source();
            if (topology.inPillar(pos.x(), pos.z(), marginBlocks)) {
                continue; // never spawn a clone inside a corner pillar
            }
            Location cloneLoc = loc.clone();
            cloneLoc.setX(pos.x());
            cloneLoc.setZ(pos.z());
            cloneLoc.setYaw(image.toSource().applyYaw(loc.getYaw()));
            if (!cloneLoc.isChunkLoaded()) {
                continue;
            }
            Clone clone = existing.stream()
                    .filter(c -> c.quarterTurns() == image.toSource().quarterTurns())
                    .findFirst().orElse(null);
            if (clone == null) {
                Entity spawned = spawnClone(source, cloneLoc);
                if (spawned != null) {
                    existing.add(new Clone(spawned, image.toSource().quarterTurns()));
                }
            } else {
                clone.entity().teleport(cloneLoc);
                Vector v = source.getVelocity();
                Vec2 rv = image.toSource().applyVector(v.getX(), v.getZ());
                clone.entity().setVelocity(new Vector(rv.x(), v.getY(), rv.z()));
            }
        }
        if (existing.isEmpty()) {
            clones.remove(source.getUniqueId());
        }
    }

    private Entity spawnClone(Entity source, Location at) {
        World world = at.getWorld();
        Entity spawned;
        if (source instanceof Item item) {
            Item drop = world.dropItem(at, item.getItemStack().clone());
            drop.setPickupDelay(32767);
            drop.setWillAge(false);
            spawned = drop;
        } else {
            // Snapshot copies the full appearance NBT — carried blocks
            // (endermen), wool colors, variants, equipment — so the clone
            // looks like its source, not just its species.
            org.bukkit.entity.EntitySnapshot snapshot = source.createSnapshot();
            spawned = snapshot != null
                    ? snapshot.createEntity(at)
                    : world.spawnEntity(at, source.getType());
        }
        spawned.getPersistentDataContainer().set(cloneKey, PersistentDataType.STRING,
                source.getUniqueId().toString());
        spawned.setPersistent(false);
        spawned.setSilent(true);
        spawned.setInvulnerable(true);
        spawned.setGravity(false);
        // A clone inside a mirrored portal must never travel dimensions.
        spawned.setPortalCooldown(Integer.MAX_VALUE);
        if (spawned instanceof LivingEntity living) {
            living.setAI(false);
            living.setCollidable(false);
        }
        return spawned;
    }
}
