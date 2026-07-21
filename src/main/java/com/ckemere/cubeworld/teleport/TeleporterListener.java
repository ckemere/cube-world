package com.ckemere.cubeworld.teleport;

import java.util.ArrayList;
import java.util.List;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Wires the teleport network into the world: raise a station by placing a
 * Teleporter Core on a 3x3 amethyst pad, open the network by right-clicking a
 * station, travel by paying lapis, and build the 30 preloaded city stations as
 * their chunks load.
 */
public final class TeleporterListener implements Listener {

    private static final double NEARBY_RADIUS = 20000.0;   // "nearby" destinations without a map

    private final Plugin plugin;
    private final TeleportService svc;

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
        if (hasAmethystPad(b)) {
            svc.register(b.getLocation(), autoName(b), false);
            svc.spark(b.getLocation());
            e.getPlayer().sendMessage(Component.text("Teleport station raised.", NamedTextColor.AQUA));
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
        if (s.city()) {                      // protect the preloaded city network
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

    private boolean hasAmethystPad(Block core) {
        World w = core.getWorld();
        int r = TeleportService.BASE_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (w.getBlockAt(core.getX() + dx, core.getY() - 1, core.getZ() + dz)
                        .getType() != Material.AMETHYST_BLOCK) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ open it
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null
                || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        TeleportService.Station s = svc.stationAt(e.getClickedBlock().getLocation());
        if (s == null) {
            return;
        }
        e.setCancelled(true);                // suppress lodestone compass behaviour
        Player p = e.getPlayer();
        Location src = e.getClickedBlock().getLocation();

        List<TeleportService.Station> dests = new ArrayList<>(svc.near(src, NEARBY_RADIUS));
        TeleportService.Station mapTarget = mapTarget(p);   // a drawn map unlocks a far jump
        if (mapTarget != null && dests.stream().noneMatch(d -> d.key().equals(mapTarget.key()))
                && !mapTarget.key().equals(s.key())) {
            dests.add(0, mapTarget);
        }
        if (dests.isEmpty()) {
            p.sendMessage(Component.text(
                    "No stations in range. Bring a drawn map of a distant station.",
                    NamedTextColor.GRAY));
            return;
        }
        p.openInventory(new TeleportMenu(svc, p, src, dests, svc.countLapis(p)).getInventory());
    }

    /** The station nearest a held filled-map's centre, if the map is in hand. */
    private TeleportService.Station mapTarget(Player p) {
        for (ItemStack it : new ItemStack[] {p.getInventory().getItemInMainHand(),
                p.getInventory().getItemInOffHand()}) {
            if (it != null && it.getType() == Material.FILLED_MAP
                    && it.getItemMeta() instanceof MapMeta mm && mm.hasMapView()) {
                MapView v = mm.getMapView();
                if (v == null) {
                    continue;
                }
                Location c = new Location(v.getWorld(), v.getCenterX(), 0, v.getCenterZ());
                TeleportService.Station best = null;
                double bd = Double.MAX_VALUE;
                for (TeleportService.Station st : svc.all()) {
                    if (!st.world().equals(v.getWorld().getName())) {
                        continue;
                    }
                    double d = svc.dist(c, st);
                    if (d < bd) {
                        bd = d;
                        best = st;
                    }
                }
                if (best != null && bd < 400) {     // map must actually cover a station
                    return best;
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------- travel
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof TeleportMenu menu)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        TeleportService.Station dest = menu.destinationAt(e.getRawSlot());
        if (dest != null) {
            svc.travel(p, menu.source(), dest);
        }
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
        // build next tick so the chunk (and its neighbours for surface height) settle
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
                for (int dy = 1; dy <= 3; dy++) {           // headroom for arriving players
                    w.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR, false);
                }
            }
        }
        w.getBlockAt(x, y + 1, z).setType(Material.LODESTONE, false);
        svc.register(new Location(w, x, y + 1, z), name, true);
    }

    private String autoName(Block core) {
        return "Station " + core.getX() + "," + core.getZ();
    }
}
