package com.ckemere.cubeworld.lifesteal;

import com.ckemere.cubeworld.CubeWorldPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * The lifesteal loop. Death in the wilds costs a heart and spills it where
 * you fell — a Heart of the Traveler item anyone may eat, glowing and
 * despawn-proof, so death sites become places. Death inside a station's
 * sanctuary is plain vanilla. At the floor nothing is lost and nothing
 * drops (no heart printing). Totem pops never reach PlayerDeathEvent, so
 * totems keep their full value.
 *
 * <p>The heart is minted, not taken from inventory — keepInventory worlds
 * still spill it, and a PvP kill yields exactly one heart either way.
 */
public final class LifestealListener implements Listener {

    private final CubeWorldPlugin plugin;
    private final HeartService hearts;

    public LifestealListener(CubeWorldPlugin plugin, HeartService hearts) {
        this.plugin = plugin;
        this.hearts = hearts;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (!hearts.enabled() || !plugin.isCubeWorld(p.getWorld())) {
            return;
        }
        if (hearts.sanctuaryAt(p.getLocation()) != null) {
            return;                                 // sanctuary: vanilla death
        }
        int n = hearts.hearts(p);
        if (n <= hearts.floor()) {
            e.deathMessage(e.deathMessage() == null ? null
                    : e.deathMessage().append(Component.text(" (worn to the bone)",
                            NamedTextColor.DARK_GRAY)));
            return;                                 // at the floor: lose nothing, drop nothing
        }
        hearts.setHearts(p, n - 1);
        String epitaph = e.deathMessage() == null ? p.getName()
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(e.deathMessage());
        Item drop = p.getWorld().dropItemNaturally(p.getLocation(),
                hearts.createHeart(kindOf(p), 1, epitaph));
        drop.setUnlimitedLifetime(true);            // never despawns
        drop.setInvulnerable(true);                 // survives the lava that killed you
        drop.setGlowing(true);                      // death sites are visible
        p.sendMessage(Component.text("A heart spills where you fell — " + (n - 1)
                + " remain.", NamedTextColor.RED));
    }

    /**
     * Which heart a death spills. Player kill (melee or projectile — vanilla
     * credits the shooter as killer) → TRAVELER. Any other entity damager —
     * mobs, mob arrows, creeper blasts — → WANDERER. Everything the world
     * itself did (falls, lava, drowning, starving) → EXPLORER.
     */
    private HeartService.HeartKind kindOf(Player p) {
        if (p.getKiller() != null) {
            return HeartService.HeartKind.TRAVELER;
        }
        if (p.getLastDamageCause() instanceof
                org.bukkit.event.entity.EntityDamageByEntityEvent) {
            return HeartService.HeartKind.WANDERER;
        }
        return HeartService.HeartKind.EXPLORER;
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!hearts.isHeart(e.getItem())) {
            return;
        }
        Player p = e.getPlayer();
        if (!hearts.enabled() || !plugin.isCubeWorld(p.getWorld())) {
            e.setCancelled(true);
            return;
        }
        int n = hearts.hearts(p);
        if (n >= hearts.cap()) {
            e.setCancelled(true);
            p.sendMessage(Component.text("Your heart is already as strong as it can be ("
                    + hearts.cap() + ").", NamedTextColor.GRAY));
            return;
        }
        hearts.setHearts(p, n + 1);
        p.setHealth(Math.min(p.getHealth() + 2.0,
                p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()));
        p.sendMessage(Component.text("Your heart grows stronger — " + (n + 1) + " hearts.",
                NamedTextColor.RED));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (hearts.enabled()) {
            hearts.applyModifier(e.getPlayer());
        }
    }
}
