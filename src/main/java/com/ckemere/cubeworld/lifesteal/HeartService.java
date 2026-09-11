package com.ckemere.cubeworld.lifesteal;

import com.ckemere.cubeworld.teleport.TeleportService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Lifesteal hearts: the count, the max-health modifier, the items, the
 * recipes, and the sanctuary test.
 *
 * <p>Everything stateful lives on the player: the heart count in PDC
 * ({@code cubeworld:hearts}) and a single additive MAX_HEALTH modifier
 * ({@code cubeworld:hearts}, {@code (hearts-10) * 2} HP) reasserted on join
 * and after every change — base value is never touched, so other plugins'
 * and vanilla's own modifiers compose.
 *
 * <p>Sanctuary = within {@code lifesteal.radius} blocks (world plane) of any
 * registered teleport station — the 30 cities and every player-raised pad:
 * placing a station founds a safe zone. Net-adjacent face edges are
 * continuous in world coordinates so the plain distance is correct there;
 * stations hard against a STITCHED seam do not yet project their zone across
 * it (no city is near one; revisit if players build there).
 */
public final class HeartService {

    private final Plugin plugin;
    private final TeleportService teleport;
    private final NamespacedKey heartsKey;      // PDC: player heart count
    private final NamespacedKey healthKey;      // the max-health modifier
    private final NamespacedKey heartItemKey;   // PDC: item is a Heart
    private final NamespacedKey heartKindKey;   // PDC: which death story it tells
    private final NamespacedKey fragmentKey;    // PDC: item is a Heart Fragment
    private final NamespacedKey fragmentRecipe;
    private final NamespacedKey heartRecipe;

    public HeartService(Plugin plugin, TeleportService teleport) {
        this.plugin = plugin;
        this.teleport = teleport;
        this.heartsKey = new NamespacedKey(plugin, "hearts");
        this.healthKey = new NamespacedKey(plugin, "hearts");
        this.heartItemKey = new NamespacedKey(plugin, "heart_item");
        this.heartKindKey = new NamespacedKey(plugin, "heart_kind");
        this.fragmentKey = new NamespacedKey(plugin, "heart_fragment");
        this.fragmentRecipe = new NamespacedKey(plugin, "heart_fragment_recipe");
        this.heartRecipe = new NamespacedKey(plugin, "heart_recipe");
    }

    // ------------------------------------------------------------------ knobs
    public boolean enabled() {
        return plugin.getConfig().getBoolean("lifesteal.enabled", true);
    }

    public int radius() {
        return plugin.getConfig().getInt("lifesteal.radius", 512);
    }

    public int floor() {
        return plugin.getConfig().getInt("lifesteal.floor", 3);
    }

    public int cap() {
        return plugin.getConfig().getInt("lifesteal.cap", 20);
    }

    public int start() {
        return plugin.getConfig().getInt("lifesteal.start", 10);
    }

    // ----------------------------------------------------------------- hearts
    public int hearts(Player p) {
        Integer v = p.getPersistentDataContainer().get(heartsKey, PersistentDataType.INTEGER);
        return v == null ? start() : Math.max(floor(), Math.min(cap(), v));
    }

    public void setHearts(Player p, int n) {
        n = Math.max(floor(), Math.min(cap(), n));
        p.getPersistentDataContainer().set(heartsKey, PersistentDataType.INTEGER, n);
        applyModifier(p);
    }

    /** Reassert the max-health modifier from the stored count (idempotent). */
    public void applyModifier(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst == null) {
            return;
        }
        for (AttributeModifier m : List.copyOf(inst.getModifiers())) {
            if (healthKey.equals(m.getKey())) {
                inst.removeModifier(m);
            }
        }
        double delta = (hearts(p) - 10) * 2.0;
        if (delta != 0) {
            inst.addModifier(new AttributeModifier(healthKey, delta,
                    AttributeModifier.Operation.ADD_NUMBER));
        }
        if (p.getHealth() > inst.getValue()) {
            p.setHealth(inst.getValue());
        }
    }

    // ------------------------------------------------------------------ items
    /** Which story a spilled heart tells: how its previous owner died. All
     * variants eat identically (+1 heart) — the variant is flavor and record. */
    public enum HeartKind {
        TRAVELER("Heart of the Traveler", NamedTextColor.RED,
                "Spilled by a traveler slain by another."),
        EXPLORER("Heart of the Explorer", NamedTextColor.AQUA,
                "Spilled where exploration turned fatal."),
        WANDERER("Heart of the Wanderer", NamedTextColor.LIGHT_PURPLE,
                "Spilled by a wanderer the night caught."),
        FORGED("Heart of the Artificer", NamedTextColor.GOLD,
                "Forged from four fragments.");

        final String title;
        final NamedTextColor color;
        final String story;

        HeartKind(String title, NamedTextColor color, String story) {
            this.title = title;
            this.color = color;
            this.story = story;
        }
    }

    /** The edible heart: a nether star that grants +1 max heart when eaten. */
    public ItemStack createHeart(HeartKind kind, int amount) {
        return createHeart(kind, amount, null);
    }

    /**
     * A heart with provenance: gift it and the recipient reads whose it was
     * and how it was lost — the {@code epitaph} is the vanilla death message,
     * stamped verbatim into the lore. Forged hearts carry no epitaph, so a
     * battle trophy can never be counterfeited at a crafting table.
     */
    public ItemStack createHeart(HeartKind kind, int amount, String epitaph) {
        ItemStack it = new ItemStack(Material.NETHER_STAR, amount);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(kind.title, kind.color)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text("Eat to gain a permanent heart.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(kind.story, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (epitaph != null && !epitaph.isBlank()) {
            lore.add(Component.text("“" + epitaph + "”", NamedTextColor.DARK_PURPLE)
                    .decoration(TextDecoration.ITALIC, true));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(heartItemKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(heartKindKey, PersistentDataType.STRING,
                kind.name());
        it.setItemMeta(meta);
        it.setData(DataComponentTypes.FOOD, FoodProperties.food()
                .nutrition(0).saturation(0f).canAlwaysEat(true).build());
        it.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(1.6f).build());
        return it;
    }

    public boolean isHeart(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta()
                .getPersistentDataContainer().has(heartItemKey, PersistentDataType.BYTE);
    }

    /** A Heart Fragment (firework star — the old SMP heart texture, as a nod). */
    public ItemStack createFragment(int amount) {
        ItemStack it = new ItemStack(Material.FIREWORK_STAR, amount);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Heart Fragment", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Four forge a Heart of the Traveler.",
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(fragmentKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    /** Fragment: a totem sunk in redstone blocks. Heart: four fragments around
     * a netherite core on diamond. Registered once; getRecipe guards reload. */
    public void registerRecipes() {
        if (plugin.getServer().getRecipe(fragmentRecipe) == null) {
            ShapedRecipe r = new ShapedRecipe(fragmentRecipe, createFragment(1));
            r.shape("RRR", "RTR", "RRR");
            r.setIngredient('R', Material.REDSTONE_BLOCK);
            r.setIngredient('T', Material.TOTEM_OF_UNDYING);
            plugin.getServer().addRecipe(r);
        }
        if (plugin.getServer().getRecipe(heartRecipe) == null) {
            ShapedRecipe r = new ShapedRecipe(heartRecipe, createHeart(HeartKind.FORGED, 1));
            r.shape("FDF", "DND", "FDF");
            r.setIngredient('F', new RecipeChoice.ExactChoice(createFragment(1)));
            r.setIngredient('D', Material.DIAMOND_BLOCK);
            r.setIngredient('N', Material.NETHERITE_INGOT);
            plugin.getServer().addRecipe(r);
        }
    }

    // -------------------------------------------------------------- sanctuary
    /** The nearest station within the safe radius, or null (the wilds). */
    public TeleportService.Station sanctuaryAt(Location loc) {
        return nearestWithin(loc, radius());
    }

    /** Nearest station regardless of distance, or null if none registered. */
    public TeleportService.Station nearest(Location loc) {
        return nearestWithin(loc, Integer.MAX_VALUE);
    }

    private TeleportService.Station nearestWithin(Location loc, int max) {
        TeleportService.Station best = null;
        double bestD = (double) max * max;
        String world = loc.getWorld() == null ? "" : loc.getWorld().getName();
        for (TeleportService.Station s : teleport.all()) {
            if (!s.world().equals(world)) {
                continue;
            }
            double dx = loc.getX() - s.x();
            double dz = loc.getZ() - s.z();
            double d2 = dx * dx + dz * dz;
            if (d2 <= bestD) {
                bestD = d2;
                best = s;
            }
        }
        return best;
    }

    public double distTo(Location loc, TeleportService.Station s) {
        return Math.hypot(loc.getX() - s.x(), loc.getZ() - s.z());
    }
}
