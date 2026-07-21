package com.ckemere.cubeworld.teleport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The teleport network in-world: raise a station by placing a Teleporter Core on
 * a 3x3 amethyst pad; right-click a station to open its trade menu; select a
 * trade to travel. The menu always offers the two ring neighbours, plus the
 * target of a held ticket book and a "buy return ticket" trade when holding
 * paper. City stations build as their chunks load.
 */
public final class TeleporterListener implements Listener {

    private static final int RETURN_LAPIS = 4;      // flat cost of a return ticket

    private final Plugin plugin;
    private final TeleportService svc;
    private final Map<UUID, Menu> open = new HashMap<>();

    /** A player's currently-open teleport menu: source + trade index mapping. */
    private record Menu(TeleportService.Station source, List<TeleportService.Offer> offers,
                        int returnIndex) {
    }

    public TeleporterListener(Plugin plugin, TeleportService svc) {
        this.plugin = plugin;
        this.svc = svc;
    }

    // ------------------------------------------------------------ raise / break
    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!svc.isCore(e.getItemInHand())) {
            return;
        }
        Block b = e.getBlockPlaced();
        if (svc.tryRaise(b.getLocation(), coreName(e.getItemInHand(), b))) {
            svc.spark(b.getLocation());
            TeleportService.Station s = svc.stationAt(b.getLocation());
            e.getPlayer().sendMessage(Component.text("Teleport station raised — code: "
                    + (s == null ? "?" : s.code()), NamedTextColor.AQUA));
        } else {
            e.getPlayer().sendMessage(Component.text(
                    "A teleporter needs a 3x3 amethyst-block pad beneath it.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        TeleportService.Station s = svc.stationAt(e.getBlock().getLocation());
        if (s == null) {
            return;
        }
        if (s.city()) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text(
                    s.name() + " is a protected city station.", NamedTextColor.RED));
            return;
        }
        svc.unregister(e.getBlock().getLocation());
        e.setDropItems(false);
        e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), svc.createCore(1));
        e.getPlayer().sendMessage(Component.text("Teleport station removed.", NamedTextColor.YELLOW));
    }

    // ------------------------------------------------------------------ open it
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null
                || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        TeleportService.Station s = svc.stationAt(e.getClickedBlock().getLocation());
        if (s == null) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();

        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        TeleportService.Station ticketTarget = svc.ticketTarget(main);
        if (ticketTarget == null) {
            ticketTarget = svc.ticketTarget(off);
        }
        boolean hasPaper = main.getType() == Material.PAPER || off.getType() == Material.PAPER;

        List<TeleportService.Offer> offers = svc.offers(s, ticketTarget);
        List<MerchantRecipe> recipes = new ArrayList<>();
        for (TeleportService.Offer o : offers) {
            int cost = svc.lapisCost(e.getClickedBlock().getLocation(), o.dest());
            MerchantRecipe r = new MerchantRecipe(destIcon(o, cost), Integer.MAX_VALUE);
            r.addIngredient(new ItemStack(Material.LAPIS_LAZULI, Math.max(1, Math.min(64, cost))));
            recipes.add(r);
        }
        int returnIndex = -1;
        if (hasPaper) {
            returnIndex = recipes.size();
            MerchantRecipe ret = new MerchantRecipe(svc.ticketBook(s), Integer.MAX_VALUE);
            ret.addIngredient(new ItemStack(Material.PAPER, 1));
            ret.addIngredient(new ItemStack(Material.LAPIS_LAZULI, RETURN_LAPIS));
            recipes.add(ret);
        }
        if (recipes.isEmpty()) {
            p.sendMessage(Component.text(
                    "The network is still forming — no destinations from here yet.",
                    NamedTextColor.GRAY));
            return;
        }
        Merchant m = Bukkit.createMerchant(Component.text("Teleporter · " + s.name(),
                NamedTextColor.DARK_AQUA));
        m.setRecipes(recipes);
        p.openMerchant(m, true);
        open.put(p.getUniqueId(), new Menu(s, offers, returnIndex));
    }

    /** Select a trade → act (we never complete a real trade). */
    @EventHandler
    public void onTradeSelect(TradeSelectEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        Menu menu = open.get(p.getUniqueId());
        if (menu == null) {
            return;
        }
        e.setCancelled(true);
        int i = e.getIndex();
        if (i == menu.returnIndex) {
            buyReturnTicket(p, menu.source);
        } else if (i >= 0 && i < menu.offers.size()) {
            svc.travel(p, menu.source.location(plugin), menu.offers.get(i).dest());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        open.remove(e.getPlayer().getUniqueId());
    }

    private void buyReturnTicket(Player p, TeleportService.Station here) {
        if (!p.getInventory().contains(Material.PAPER, 1) || svc.countLapis(p) < RETURN_LAPIS) {
            p.sendMessage(Component.text("A return ticket costs 1 paper + " + RETURN_LAPIS + " lapis.",
                    NamedTextColor.RED));
            return;
        }
        p.getInventory().removeItem(new ItemStack(Material.PAPER, 1));
        svc.removeLapis(p, RETURN_LAPIS);
        p.closeInventory();
        p.getInventory().addItem(svc.ticketBook(here));
        p.sendMessage(Component.text("Return ticket to " + here.name() + " printed (code: "
                + here.code() + ").", NamedTextColor.AQUA));
    }

    private ItemStack destIcon(TeleportService.Offer o, int cost) {
        TeleportService.Station d = o.dest();
        ItemStack it = new ItemStack(o.kind() == TeleportService.OfferKind.TICKET
                ? Material.PAPER : Material.FILLED_MAP);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(d.name(), d.city() ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                lore(switch (o.kind()) {
                    case RING_A -> "Ring route Ⅰ";
                    case RING_B -> "Ring route Ⅱ";
                    case TICKET -> "Your ticket";
                }, NamedTextColor.GRAY),
                lore("Cost: " + cost + " lapis", NamedTextColor.BLUE),
                lore("Code: " + d.code(), NamedTextColor.DARK_GRAY),
                lore("Select to travel", NamedTextColor.GREEN)));
        it.setItemMeta(m);
        return it;
    }

    private static Component lore(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------ build preloaded city sites
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        int cx = e.getChunk().getX();
        int cz = e.getChunk().getZ();
        if (svc.pendingChunkPeek(cx, cz) == 0) {
            return;
        }
        List<TeleportService.CityBuild> builds = svc.pendingBuildsFor(cx, cz);
        if (builds.isEmpty()) {
            return;
        }
        World w = e.getWorld();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (TeleportService.CityBuild c : builds) {
                buildCityStation(w, c.x(), c.z(), c.name());
            }
        });
    }

    private void buildCityStation(World w, int x, int z, String name) {
        int y = w.getHighestBlockYAt(x, z);
        int r = TeleportService.BASE_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                w.getBlockAt(x + dx, y, z + dz).setType(Material.AMETHYST_BLOCK, false);
                for (int dy = 1; dy <= 3; dy++) {
                    w.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR, false);
                }
            }
        }
        w.getBlockAt(x, y + 1, z).setType(Material.LODESTONE, false);
        svc.register(new Location(w, x, y + 1, z), name, true);
    }

    /** Station name from a renamed core (anvil), else auto from coordinates. */
    private String coreName(ItemStack core, Block b) {
        if (core != null && core.hasItemMeta() && core.getItemMeta().hasDisplayName()) {
            String n = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(core.getItemMeta().displayName());
            if (!n.isBlank() && !n.equals("Teleporter Core")) {
                return n;
            }
        }
        return "Station " + b.getX() + "," + b.getZ();
    }
}
