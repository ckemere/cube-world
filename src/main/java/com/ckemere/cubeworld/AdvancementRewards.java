package com.ckemere.cubeworld;

import com.ckemere.cubeworld.teleport.TeleportService;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * The CubeWorld expedition advancements (poles, summits, circumnavigation —
 * everything {@link ExplorationAchievements} grants) each award one Teleporter
 * Core: reach a far place the hard way once, and you have earned the means to
 * open a shortcut to it. Vanilla advancements award nothing — expeditions are
 * the fun ones here.
 *
 * <p>Anti-refarm: completions are also recorded on the player's PDC, so an
 * admin revoke + re-grant cycle (the only way a done advancement fires again)
 * does not mint extra cores.
 */
public final class AdvancementRewards implements Listener {

    private static final String NAMESPACE = "cubeworld";

    /** PDC key holding a comma-joined set of already-rewarded advancement keys. */
    private static final NamespacedKey REWARDED =
            new NamespacedKey(NAMESPACE, "advancement_rewards");

    private final TeleportService teleport;

    public AdvancementRewards(TeleportService teleport) {
        this.teleport = teleport;
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        NamespacedKey key = e.getAdvancement().getKey();
        if (!NAMESPACE.equals(key.getNamespace())) {
            return;                                 // vanilla advancements award nothing
        }
        String name = key.getKey();
        if ("root".equals(name)) {
            return;
        }
        Player p = e.getPlayer();
        String stored = p.getPersistentDataContainer().get(REWARDED, PersistentDataType.STRING);
        Set<String> rewarded = stored == null || stored.isEmpty()
                ? new HashSet<>() : new HashSet<>(Arrays.asList(stored.split(",")));
        if (!rewarded.add(name)) {
            return;                                 // already rewarded once
        }
        p.getPersistentDataContainer().set(REWARDED, PersistentDataType.STRING,
                String.join(",", rewarded));

        ItemStack core = teleport.createCore(1);
        for (ItemStack left : p.getInventory().addItem(core).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        p.sendMessage(Component.text(
                "Your expedition has earned a Teleporter Core.", NamedTextColor.AQUA));
    }
}
