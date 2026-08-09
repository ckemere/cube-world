package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.generation.OreDeposits.Ore;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.persistence.PersistentDataType;

/**
 * A carried ore detector: a BRUSH whose enchantment glint blinks faster, and whose
 * click ticks faster, the closer the holder is to a mineral province of the ore it is
 * tuned to (see {@link OreEnrichment#strengthAt}).
 *
 * <p><b>Why these signals.</b> Everything here has to render on a stock client with no
 * resource pack, which rules out {@code custom_model_data} (it needs a pack to mean
 * anything). What the vanilla client will show us for free is the enchantment glint,
 * the durability bar, and the item name -- so the glint carries the rhythm, the bar
 * carries the fine gradient, and the name carries the number.
 *
 * <p><b>Why it never points.</b> {@code oreprobe} deliberately reports local strength
 * and never a province centre, so prospecting means sampling and triangulating. A
 * lodestone compass would have deleted that, so this reports "warmer/colder" only.
 *
 * <p>Components are rewritten only when the visible state actually changes: every
 * change is an inventory packet, and re-setting a held stack every tick both spams the
 * network and can interrupt item use.
 */
public final class Prospector {

    /** Marks a brush as a Prospector. */
    private final NamespacedKey markerKey;
    /** Stores the {@link Ore} it is tuned to, by name; absent means untuned. */
    private final NamespacedKey oreKey;
    private final CubeWorldPlugin plugin;
    private final OreEnrichment ore;

    /** Durability bar resolution -- higher than brush's native 64 for a smoother gauge. */
    private static final int GAUGE = 100;
    /** Ticks between strength samples. Cheap, but no need to run every tick. */
    private static final int PERIOD = 5;
    /** Blink period in ticks at zero and full strength. */
    private static final int BLINK_COLD = 20;
    private static final int BLINK_HOT = 2;

    /** Per-player view state, so we only push a packet when something visibly changed. */
    private record View(boolean glint, int damage, String name) { }

    private final Map<UUID, View> lastView = new HashMap<>();
    /** Ores the player was already told about here, so the hint fires on entry only. */
    private final Map<UUID, String> lastHint = new HashMap<>();
    private int tick;

    public Prospector(CubeWorldPlugin plugin, OreEnrichment ore) {
        this.plugin = plugin;
        this.ore = ore;
        this.markerKey = new NamespacedKey(plugin, "prospector");
        this.oreKey = new NamespacedKey(plugin, "prospector_ore");
    }

    // ------------------------------------------------------------------ the item

    /** A new, untuned Prospector. */
    public ItemStack create() {
        ItemStack it = new ItemStack(Material.BRUSH, 1);
        it.editPersistentDataContainer(pdc ->
                pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1));
        it.setData(DataComponentTypes.MAX_DAMAGE, GAUGE);
        it.setData(DataComponentTypes.DAMAGE, GAUGE - 1);
        applyName(it, null, 0);
        return it;
    }

    public boolean isProspector(ItemStack it) {
        return it != null && it.getType() == Material.BRUSH
                && it.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /** The ore this Prospector is tuned to, or null if untuned. */
    public Ore tunedOre(ItemStack it) {
        String s = it.getPersistentDataContainer().get(oreKey, PersistentDataType.STRING);
        if (s == null) {
            return null;
        }
        try {
            return Ore.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void applyName(ItemStack it, Ore tuned, double strength) {
        Component name = tuned == null
                ? Component.text("Prospector (untuned)", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                : Component.text("Prospector: ", NamedTextColor.GOLD)
                        .append(Component.text(pretty(tuned), NamedTextColor.YELLOW))
                        .append(Component.text(
                                String.format(Locale.ROOT, "  %.0f%%", strength * 100),
                                strength > 0 ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY))
                        .decoration(TextDecoration.ITALIC, false);
        it.setData(DataComponentTypes.ITEM_NAME, name);
    }

    private static String pretty(Ore o) {
        String n = o.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ------------------------------------------------------------------ recipes

    /**
     * One shaped recipe for the blank, and one shapeless "tuning" recipe per ore.
     * Deliberately cheap -- copper, redstone and an amethyst shard, all reachable
     * long before any structure raid -- because the device is the entry point to
     * prospecting, not a reward for it.
     */
    public void registerRecipes() {
        ShapedRecipe blank = new ShapedRecipe(new NamespacedKey(plugin, "prospector"), create());
        blank.shape(" A ", "CBC", " R ");
        blank.setIngredient('A', Material.AMETHYST_SHARD);
        blank.setIngredient('C', Material.COPPER_INGOT);
        blank.setIngredient('B', Material.BRUSH);
        blank.setIngredient('R', Material.REDSTONE);
        addRecipe(blank);

        for (Ore o : Ore.values()) {
            Material token = tuningToken(o);
            ItemStack out = create();
            out.editPersistentDataContainer(pdc ->
                    pdc.set(oreKey, PersistentDataType.STRING, o.name()));
            applyName(out, o, 0);
            ShapelessRecipe tune = new ShapelessRecipe(
                    new NamespacedKey(plugin, "prospector_tune_" + o.name().toLowerCase(Locale.ROOT)),
                    out);
            // Any Prospector (tuned or not) plus a sample of the target ore. Retuning
            // is just this same craft with a different sample.
            tune.addIngredient(new RecipeChoice.MaterialChoice(Material.BRUSH));
            tune.addIngredient(new RecipeChoice.MaterialChoice(token));
            addRecipe(tune);
        }
    }

    private void addRecipe(org.bukkit.inventory.Recipe r) {
        try {
            plugin.getServer().addRecipe(r);
        } catch (IllegalStateException e) {
            // already registered (plugin reload) -- harmless
        }
    }

    /** The item you tune with: the thing you'd actually be hunting for. */
    private static Material tuningToken(Ore o) {
        return switch (o) {
            case DIAMOND -> Material.DIAMOND;
            case LAPIS -> Material.LAPIS_LAZULI;
            case REDSTONE -> Material.REDSTONE;
            case EMERALD -> Material.EMERALD;
            case GOLD -> Material.GOLD_INGOT;
            case IRON -> Material.IRON_INGOT;
            case COPPER -> Material.COPPER_INGOT;
            case COAL -> Material.COAL;
        };
    }

    // ------------------------------------------------------------------ the loop

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::pulse, PERIOD, PERIOD);
    }

    private void pulse() {
        tick += PERIOD;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            ItemStack main = p.getInventory().getItemInMainHand();
            EquipmentSlot slot = EquipmentSlot.HAND;
            if (!isProspector(main)) {
                main = p.getInventory().getItemInOffHand();
                slot = EquipmentSlot.OFF_HAND;
            }
            if (!isProspector(main)) {
                lastView.remove(p.getUniqueId());
                lastHint.remove(p.getUniqueId());
                continue;
            }
            update(p, main, slot);
        }
    }

    private void update(Player p, ItemStack it, EquipmentSlot slot) {
        Ore tuned = tunedOre(it);
        double s = tuned == null ? 0
                : ore.strengthAt(tuned, p.getLocation().getX(), p.getLocation().getZ());

        // Blink period shrinks as the signal rises; at zero it never lights.
        boolean glint = false;
        if (s > 0) {
            int period = (int) Math.round(BLINK_COLD - (BLINK_COLD - BLINK_HOT) * s);
            period = Math.max(BLINK_HOT, period);
            glint = (tick / PERIOD) % Math.max(1, period / PERIOD) == 0;
        }
        // Bar full = hot. Never a full 100: at damage 0 the client draws no bar at all.
        int damage = (int) Math.round((1.0 - s) * (GAUGE - 1));
        String label = tuned == null ? "-" : tuned.name() + ":" + Math.round(s * 100);

        View want = new View(glint, damage, label);
        View have = lastView.get(p.getUniqueId());
        if (!want.equals(have)) {
            it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, glint);
            it.setData(DataComponentTypes.MAX_DAMAGE, GAUGE);
            it.setData(DataComponentTypes.DAMAGE, damage);
            applyName(it, tuned, s);
            if (slot == EquipmentSlot.HAND) {
                p.getInventory().setItemInMainHand(it);
            } else {
                p.getInventory().setItemInOffHand(it);
            }
            lastView.put(p.getUniqueId(), want);
        }
        // One click per blink, so ear and eye agree. PLAYERS category so it can be
        // muted in the vanilla sound options without silencing the whole game.
        if (glint) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS,
                    0.4f, (float) (0.8 + 0.9 * s));
        }
        hintOtherOres(p, tuned);
    }

    /**
     * Edge-triggered nudge when the holder walks into a province for some OTHER ore,
     * so overlapping provinces are discoverable without the tuned signal having to
     * compete with them. Fires on entry only -- a per-tick message would be noise.
     */
    private void hintOtherOres(Player p, Ore tuned) {
        StringBuilder others = new StringBuilder();
        for (Ore o : Ore.values()) {
            if (o == tuned) {
                continue;
            }
            if (ore.strengthAt(o, p.getLocation().getX(), p.getLocation().getZ()) > 0.15) {
                others.append(others.isEmpty() ? "" : ", ").append(pretty(o));
            }
        }
        String key = others.toString();
        String prev = lastHint.get(p.getUniqueId());
        if (key.equals(prev == null ? "" : prev)) {
            return;
        }
        lastHint.put(p.getUniqueId(), key);
        if (!key.isEmpty()) {
            p.sendActionBar(Component.text("The brush tingles — traces of " + key + " here",
                    NamedTextColor.GRAY));
        }
    }
}
