package com.ckemere.cubeworld.lifesteal;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.teleport.TeleportService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Continuous sanctuary/wilds indication, checked once a second.
 *
 * <p>The HUD carrier is the Luck/Unluck effect pair applied at amplifier −1:
 * the effect's whole implementation is one attribute modifier whose amount is
 * scaled by {@code amplifier + 1} (MobEffect.java:209), so −1 yields a 0.0
 * modifier — icon on, luck untouched. Verified: nothing in vanilla survival
 * grants either effect (no brewing recipe for Luck, zero appliers of Unluck),
 * so the icons are ours alone. Milk clears them; the next tick reapplies.
 *
 * <p>Transitions get one action-bar line + a sound, with hysteresis: you
 * enter sanctuary at the radius but only fall back to the wilds 16 blocks
 * past it, so pacing the border does not flicker or ding repeatedly.
 */
public final class ZoneTracker {

    private static final int HYSTERESIS = 16;

    private final CubeWorldPlugin plugin;
    private final HeartService hearts;
    private final Map<UUID, Boolean> inSanctuary = new HashMap<>();

    public ZoneTracker(CubeWorldPlugin plugin, HeartService hearts) {
        this.plugin = plugin;
        this.hearts = hearts;
    }

    public void tick() {
        if (!hearts.enabled()) {
            return;
        }
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.isCubeWorld(p.getWorld())) {
                inSanctuary.remove(p.getUniqueId());
                continue;
            }
            Boolean prev = inSanctuary.get(p.getUniqueId());
            TeleportService.Station near = hearts.nearest(p.getLocation());
            double d = near == null ? Double.MAX_VALUE : hearts.distTo(p.getLocation(), near);
            boolean safe = d <= hearts.radius() + (Boolean.TRUE.equals(prev) ? HYSTERESIS : 0);

            apply(p, safe ? PotionEffectType.LUCK : PotionEffectType.UNLUCK);
            p.removePotionEffect(safe ? PotionEffectType.UNLUCK : PotionEffectType.LUCK);

            if (prev != null && prev != safe) {
                if (safe) {
                    p.sendActionBar(Component.text("✦ Sanctuary of " + near.name(),
                            NamedTextColor.AQUA));
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
                } else {
                    p.sendActionBar(Component.text("⚠ The wilds — hearts are at stake",
                            NamedTextColor.RED));
                    p.playSound(p.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.6f, 0.7f);
                }
            }
            inSanctuary.put(p.getUniqueId(), safe);
        }
    }

    private void apply(Player p, PotionEffectType type) {
        PotionEffect cur = p.getPotionEffect(type);
        if (cur == null || cur.getDuration() != PotionEffect.INFINITE_DURATION
                || cur.getAmplifier() != -1) {
            p.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, -1,
                    false, false, true));
        }
    }

    /** Whether this player was in sanctuary at the last tick (for the command). */
    public boolean lastKnownSafe(Player p) {
        return Boolean.TRUE.equals(inSanctuary.get(p.getUniqueId()));
    }
}
