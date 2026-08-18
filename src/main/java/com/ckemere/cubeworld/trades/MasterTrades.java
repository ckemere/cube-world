package com.ckemere.cubeworld.trades;

import com.ckemere.cubeworld.teleport.TeleportService;
import com.ckemere.cubeworld.teleport.TeleportService.Station;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The catalog of exceptional goods a city Master Trader can carry. Each appearance
 * stocks a random subset (see {@link #randomStock(int)}), so no two visits are
 * quite alike and it's worth catching several over time. Result items are plain
 * vanilla items — a Heart of the Sea is a Heart of the Sea; the "exceptional" part
 * is that these are otherwise hard/impossible to buy, and the gear comes enchanted.
 * Prices are premium: most goods cost the better part of a stack of emeralds, and
 * combat gear additionally demands a tribute of gold ingots (the stronger the piece,
 * the more gold). A vanilla {@link MerchantRecipe} takes at most two ingredients, so
 * that's emeralds + gold for war goods and emeralds alone for everything else.
 */
public final class MasterTrades {

    private final TeleportService teleport;   // for the teleport-ticket good

    public MasterTrades(TeleportService teleport) {
        this.teleport = teleport;
    }

    /** A fresh random subset of {@code count} exceptional trades (or the whole
     * pool if it's smaller). Each call builds new mutable recipe instances. */
    public List<MerchantRecipe> randomStock(int count) {
        List<Supplier<MerchantRecipe>> pool = new ArrayList<>(pool());
        Collections.shuffle(pool, ThreadLocalRandom.current());
        List<MerchantRecipe> out = new ArrayList<>();
        for (int i = 0; i < pool.size() && out.size() < count; i++) {
            MerchantRecipe r = pool.get(i).get();
            if (r != null) {                    // a good may be unavailable (no stations yet)
                out.add(r);
            }
        }
        return out;
    }

    /** Suppliers so every stocked trade is a fresh instance (MerchantRecipe is
     * mutable — it tracks uses). */
    private List<Supplier<MerchantRecipe>> pool() {
        return List.of(
            () -> buy(item(Material.HEART_OF_THE_SEA), 48, 3),
            // A tool, not a weapon — priced high but no gold tribute.
            () -> buy(ench(Material.NETHERITE_PICKAXE, Enchantment.EFFICIENCY, 4,
                    Enchantment.UNBREAKING, 3, Enchantment.FORTUNE, 3, Enchantment.MENDING, 1), 60, 2),
            () -> buyArmed(ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5,
                    Enchantment.UNBREAKING, 3, Enchantment.LOOTING, 3, Enchantment.MENDING, 1), 64, 25, 2),
            () -> buyArmed(ench(Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 4,
                    Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1), 52, 16, 2),
            () -> buy(book(Enchantment.MENDING, 1), 48, 3),
            () -> buyArmed(item(Material.ENCHANTED_GOLDEN_APPLE), 56, 20, 2),
            () -> buy(item(Material.WITHER_SKELETON_SKULL), 52, 2),
            () -> buy(item(Material.CREEPER_HEAD), 40, 3),
            () -> buy(item(Material.PIGLIN_HEAD), 40, 3),
            () -> cityTicket(44, 4),
            // Cheaper than a named city ticket: you don't get to pick — the
            // destination is a real station, revealed only on travel.
            () -> mysteryTicket(16, 4),
            () -> buyArmed(item(Material.DIAMOND_HORSE_ARMOR), 48, 8, 3),
            () -> buy(item(Material.MUSIC_DISC_PIGSTEP), 42, 3),
            () -> buy(item(Material.BUDDING_AMETHYST), 56, 2),
            () -> buyArmed(ench(Material.TRIDENT, Enchantment.LOYALTY, 3,
                    Enchantment.UNBREAKING, 3), 58, 18, 2),
            () -> buyArmed(lingering(PotionEffectType.REGENERATION, 480, 1), 44, 10, 4),
            () -> buyArmed(ench(Material.CROSSBOW, Enchantment.MULTISHOT, 1,
                    Enchantment.QUICK_CHARGE, 3, Enchantment.MENDING, 1), 50, 14, 3));
    }

    // --------------------------------------------------------------- builders
    /** "Pay emeralds, receive item." Wandering-trader trades don't grant villager
     * XP (traders don't level), so no experience reward. */
    private static MerchantRecipe buy(ItemStack result, int emeralds, int maxUses) {
        MerchantRecipe r = new MerchantRecipe(result, 0, maxUses, false, 0, 0.0f);
        r.addIngredient(new ItemStack(Material.EMERALD, clampStack(emeralds)));
        return r;
    }

    /** "Pay emeralds <em>and</em> gold, receive item." The war-goods price: a vanilla
     * recipe allows exactly two ingredients, and combat gear spends both slots. */
    private static MerchantRecipe buyArmed(ItemStack result, int emeralds, int gold, int maxUses) {
        MerchantRecipe r = buy(result, emeralds, maxUses);
        r.addIngredient(new ItemStack(Material.GOLD_INGOT, clampStack(gold)));
        return r;
    }

    /** Ingredient counts are one stack at most. */
    private static int clampStack(int n) {
        return Math.max(1, Math.min(64, n));
    }

    /** A teleport ticket to a random special city — reuses the transit system. */
    private MerchantRecipe cityTicket(int emeralds, int maxUses) {
        List<Station> cities = new ArrayList<>();
        for (Station s : teleport.all()) {
            if (s.city()) {
                cities.add(s);
            }
        }
        ItemStack result = cities.isEmpty()
                ? item(Material.MAP)
                : teleport.ticketBook(cities.get(ThreadLocalRandom.current().nextInt(cities.size())));
        return buy(result, emeralds, maxUses);
    }

    /** A ticket to a random REAL station with the destination obscured; null
     * (trade skipped) while the network has no stations yet. */
    private MerchantRecipe mysteryTicket(int emeralds, int maxUses) {
        Station s = teleport.randomStation();
        return s == null ? null : buy(teleport.mysteryTicketBook(s), emeralds, maxUses);
    }

    private static ItemStack item(Material m) {
        return new ItemStack(m);
    }

    /** {@code ench(material, Enchantment, level, Enchantment, level, ...)}. */
    private static ItemStack ench(Material m, Object... pairs) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        for (int i = 0; i < pairs.length; i += 2) {
            meta.addEnchant((Enchantment) pairs[i], (Integer) pairs[i + 1], true);
        }
        it.setItemMeta(meta);
        return it;
    }

    /** An enchanted book carrying the given stored enchantment(s). */
    private static ItemStack book(Object... pairs) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) it.getItemMeta();
        for (int i = 0; i < pairs.length; i += 2) {
            meta.addStoredEnchant((Enchantment) pairs[i], (Integer) pairs[i + 1], true);
        }
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack lingering(PotionEffectType type, int seconds, int amplifier) {
        ItemStack it = new ItemStack(Material.LINGERING_POTION);
        PotionMeta meta = (PotionMeta) it.getItemMeta();
        meta.addCustomEffect(new PotionEffect(type, seconds * 20, amplifier), true);
        it.setItemMeta(meta);
        return it;
    }
}
