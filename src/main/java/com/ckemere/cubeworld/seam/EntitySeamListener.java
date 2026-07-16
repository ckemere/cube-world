package com.ckemere.cubeworld.seam;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Interactions with margin clones are forwarded to the real entity they
 * mirror; stray clones from previous runs are culled as chunks load.
 */
public final class EntitySeamListener implements Listener {

    private final EntityMirrorService entityMirrors;
    private final PartnerTicketService partnerTickets;
    private final MirrorService mirrors;

    public EntitySeamListener(EntityMirrorService entityMirrors, PartnerTicketService partnerTickets,
                              MirrorService mirrors) {
        this.entityMirrors = entityMirrors;
        this.partnerTickets = partnerTickets;
        this.mirrors = mirrors;
    }

    /**
     * Margin portals are functional replicas of real portals across the seam,
     * but their coordinates map to a divergent nether location (margin/8 !=
     * source/8) — and clones must never leave their manager's world. Verified
     * live: an unguarded clone rode a mirrored portal to the nether and left
     * a duplicate cart plus a junk portal behind.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(org.bukkit.event.entity.EntityPortalEvent event) {
        if (entityMirrors.isClone(event.getEntity())
                || mirrors.isMargin(event.getFrom().getX(), event.getFrom().getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(org.bukkit.event.player.PlayerPortalEvent event) {
        if (mirrors.isMargin(event.getFrom().getX(), event.getFrom().getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCloneDamaged(EntityDamageByEntityEvent event) {
        if (!entityMirrors.isClone(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        Entity source = entityMirrors.sourceOf(event.getEntity());
        if (source instanceof org.bukkit.entity.Damageable damageable && source.isValid()) {
            damageable.damage(event.getDamage(), event.getDamager());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCloneInteract(PlayerInteractEntityEvent event) {
        if (entityMirrors.isClone(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        entityMirrors.cullStrayClones(event.getChunk());
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof org.bukkit.entity.Mob mob) {
                partnerTickets.reviewLoadedMob(mob);
            }
        }
    }
}
