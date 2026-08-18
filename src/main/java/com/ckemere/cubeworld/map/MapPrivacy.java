package com.ckemere.cubeworld.map;

import java.util.Locale;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

/**
 * Location privacy for the live web map.
 *
 * <p>The map website asks the plugin for player positions ({@code /cubeworld
 * mapplayers}) instead of reading raw entity NBT, so two kinds of privacy can
 * be applied server-side:
 *
 * <ul>
 *   <li><b>Hiding</b> — a player using the vanilla "don't find me" mechanics is
 *       omitted entirely: sneaking, invisibility, spectator mode, or wearing a
 *       mob head / carved pumpkin (the locator-bar tricks, applied fully).
 *   <li><b>Precision</b> — a per-player setting ({@code /cubeworld
 *       mapprecision high|medium|low}, stored on the player's PDC). Medium and
 *       low report the position snapped to the center of a 128- or 512-block
 *       cell. Snapping, not random jitter, on purpose: jitter re-rolled per
 *       poll averages back to the true position; a cell center never reveals
 *       more than the cell no matter how long you watch. The web map draws the
 *       marker sized to the cell so the uncertainty is honest.
 * </ul>
 */
public final class MapPrivacy {

    /** Marker precision: the quantization cell size in blocks (0 = exact). */
    public enum Precision {
        HIGH(0), MEDIUM(128), LOW(512);

        public final int cell;

        Precision(int cell) {
            this.cell = cell;
        }
    }

    private static final NamespacedKey PRECISION_KEY =
            new NamespacedKey("cubeworld", "map_precision");

    private MapPrivacy() {
    }

    /** True when the player is using any vanilla hide mechanic. */
    public static boolean isHiddenFromMap(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR || p.isSneaking() || p.isInvisible()
                || p.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            return true;
        }
        ItemStack helmet = p.getInventory().getHelmet();
        if (helmet == null) {
            return false;
        }
        Material m = helmet.getType();
        String n = m.name();
        return m == Material.CARVED_PUMPKIN || n.endsWith("_SKULL") || n.endsWith("_HEAD");
    }

    public static Precision precision(Player p) {
        String v = p.getPersistentDataContainer().get(PRECISION_KEY, PersistentDataType.STRING);
        if (v == null) {
            return Precision.HIGH;
        }
        try {
            return Precision.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Precision.HIGH;
        }
    }

    public static void setPrecision(Player p, Precision precision) {
        p.getPersistentDataContainer().set(PRECISION_KEY, PersistentDataType.STRING,
                precision.name());
    }

    /** Snap to the center of the enclosing cell. */
    public static int quantize(int v, int cell) {
        return Math.floorDiv(v, cell) * cell + cell / 2;
    }
}
