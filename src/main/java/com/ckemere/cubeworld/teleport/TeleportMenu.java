package com.ckemere.cubeworld.teleport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The teleport-network GUI opened from a station. An inventory (not a custom
 * screen — those are off-limits) whose destination items each carry a distance
 * and lapis cost; clicking one pays and travels. Identifying the menu by its
 * {@link InventoryHolder} keeps click handling unambiguous.
 */
public final class TeleportMenu implements InventoryHolder {

    private final Inventory inv;
    private final Location source;
    private final Map<Integer, TeleportService.Station> slotDest = new HashMap<>();

    public TeleportMenu(TeleportService svc, Player player, Location source,
                        List<TeleportService.Station> destinations, int playerLapis) {
        this.source = source;
        int rows = Math.max(2, Math.min(6, 1 + (destinations.size() + 8) / 9));
        this.inv = Bukkit.createInventory(this, rows * 9,
                Component.text("Teleport Network", NamedTextColor.DARK_AQUA));

        inv.setItem(4, info(playerLapis, destinations.size()));

        int slot = 9;
        for (TeleportService.Station s : destinations) {
            if (slot >= rows * 9) {
                break;
            }
            int cost = svc.lapisCost(source, s);
            int dist = (int) Math.round(svc.dist(source, s));
            inv.setItem(slot, destinationIcon(s, dist, cost, playerLapis >= cost));
            slotDest.put(slot, s);
            slot++;
        }
    }

    private ItemStack info(int lapis, int count) {
        ItemStack it = new ItemStack(Material.ENDER_EYE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Teleport Network", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                line(count + " destination(s) in range", NamedTextColor.GRAY),
                line("You hold " + lapis + " lapis", NamedTextColor.BLUE)));
        it.setItemMeta(m);
        return it;
    }

    private ItemStack destinationIcon(TeleportService.Station s, int dist, int cost, boolean afford) {
        ItemStack it = new ItemStack(s.city() ? Material.FILLED_MAP : Material.LODESTONE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(s.name(), s.city() ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                line("Distance: " + dist + " blocks", NamedTextColor.GRAY),
                line("Cost: " + cost + " lapis", afford ? NamedTextColor.BLUE : NamedTextColor.RED),
                line(afford ? "Click to travel" : "Not enough lapis",
                        afford ? NamedTextColor.GREEN : NamedTextColor.RED)));
        it.setItemMeta(m);
        return it;
    }

    private static Component line(String text, NamedTextColor c) {
        return Component.text(text, c).decoration(TextDecoration.ITALIC, false);
    }

    public TeleportService.Station destinationAt(int slot) {
        return slotDest.get(slot);
    }

    public Location source() {
        return source;
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }
}
