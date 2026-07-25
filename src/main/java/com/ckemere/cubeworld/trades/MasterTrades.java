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
 * Prices are premium (paid in emeralds).
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
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            out.add(pool.get(i).get());
        }
        return out;
    }

    /** Suppliers so every stocked trade is a fresh instance (MerchantRecipe is
     * mutable — it tracks uses). */
    private List<Supplier<MerchantRecipe>> pool() {
        return List.of(
            () -> buy(item(Material.HEART_OF_THE_SEA), 32, 3),
            () -> buy(ench(Material.NETHERITE_PICKAXE, Enchantment.EFFICIENCY, 4,
                    Enchantment.UNBREAKING, 3, Enchantment.FORTUNE, 3, Enchantment.MENDING, 1), 40, 2),
            () -> buy(ench(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5,
                    Enchantment.UNBREAKING, 3, Enchantment.LOOTING, 3, Enchantment.MENDING, 1), 45, 2),
            () -> buy(ench(Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 4,
                    Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1), 30, 2),
            () -> buy(book(Enchantment.MENDING, 1), 30, 3),
            () -> buy(item(Material.ENCHANTED_GOLDEN_APPLE), 40, 2),
            () -> buy(item(Material.WITHER_SKELETON_SKULL), 40, 2),
            () -> buy(item(Material.CREEPER_HEAD), 16, 3),
            () -> buy(item(Material.PIGLIN_HEAD), 16, 3),
            () -> cityTicket(24, 4),
            () -> buy(item(Material.DIAMOND_HORSE_ARMOR), 30, 3),
            () -> buy(item(Material.MUSIC_DISC_PIGSTEP), 24, 3),
            () -> buy(item(Material.BUDDING_AMETHYST), 40, 2),
            () -> buy(ench(Material.TRIDENT, Enchantment.LOYALTY, 3, Enchantment.UNBREAKING, 3), 35, 2),
            () -> buy(lingering(PotionEffectType.REGENERATION, 480, 1), 24, 4),
            () -> buy(ench(Material.CROSSBOW, Enchantment.MULTISHOT, 1,
                    Enchantment.QUICK_CHARGE, 3, Enchantment.MENDING, 1), 24, 3));
    }

    // --------------------------------------------------------------- builders
    /** "Pay emeralds, receive item." Wandering-trader trades don't grant villager
     * XP (traders don't level), so no experience reward. */
    private static MerchantRecipe buy(ItemStack result, int emeralds, int maxUses) {
        MerchantRecipe r = new MerchantRecipe(result, 0, maxUses, false, 0, 0.0f);
        r.addIngredient(new ItemStack(Material.EMERALD, Math.max(1, Math.min(64, emeralds))));
        return r;
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
