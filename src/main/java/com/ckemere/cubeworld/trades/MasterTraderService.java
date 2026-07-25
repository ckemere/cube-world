package com.ckemere.cubeworld.trades;

import com.ckemere.cubeworld.CubeWorldPlugin;
import com.ckemere.cubeworld.teleport.TeleportService;
import com.ckemere.cubeworld.teleport.TeleportService.Station;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sends rare "Master Traders" to visit the special cities. Every {@link #CHECK_TICKS}
 * a low-probability roll picks one special city that has a player nearby and no
 * Master Trader already present, and spawns a wandering trader there carrying a
 * random subset of exceptional goods (see {@link MasterTrades}). It despawns after
 * {@link #DESPAWN_TICKS}, so catching one is a recurring treasure-hunt; at most
 * {@link #MAX_ACTIVE} exist at once.
 *
 * <p>Wandering traders are a natural fit: each is its own {@link org.bukkit.inventory.Merchant},
 * so its trades are fully ours to set, and it renders with the stock wandering-trader
 * skin on any vanilla client — no resource pack, no villager type/biome machinery.
 */
public final class MasterTraderService {

    private static final long CHECK_TICKS = 6000L;      // roll every 5 minutes
    private static final double SPAWN_CHANCE = 0.34;    // ... and only sometimes
    private static final int DESPAWN_TICKS = 24000;     // ~20 min to trade before it leaves
    private static final double CITY_RANGE = 96.0;      // "at the city" radius
    private static final int MAX_ACTIVE = 3;
    private static final int STOCK = 6;                 // exceptional trades per visit

    private final CubeWorldPlugin plugin;
    private final TeleportService teleport;
    private final MasterTrades trades;
    private final NamespacedKey tagKey;
    private BukkitTask task;

    public MasterTraderService(CubeWorldPlugin plugin, TeleportService teleport) {
        this.plugin = plugin;
        this.teleport = teleport;
        this.trades = new MasterTrades(teleport);
        this.tagKey = new NamespacedKey(plugin, "master_trader");
    }

    public void start() {
        task = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::tick, CHECK_TICKS, CHECK_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        List<Station> eligible = new ArrayList<>();
        int active = 0;
        for (Station s : teleport.all()) {
            if (!s.city()) {
                continue;
            }
            World w = plugin.getServer().getWorld(s.world());
            if (w == null || !w.isChunkLoaded(s.x() >> 4, s.z() >> 4)) {
                continue;   // nobody around to see it — skip
            }
            if (masterTraderNear(w, s)) {
                active++;   // this city already has one
            } else if (playerNear(w, s)) {
                eligible.add(s);
            }
        }
        if (active >= MAX_ACTIVE || eligible.isEmpty()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() > SPAWN_CHANCE) {
            return;         // keep them rare
        }
        spawnTrader(eligible.get(ThreadLocalRandom.current().nextInt(eligible.size())));
    }

    private boolean playerNear(World w, Station s) {
        for (Player p : w.getPlayers()) {
            if (within(p.getLocation(), s, CITY_RANGE)) {
                return true;
            }
        }
        return false;
    }

    private boolean masterTraderNear(World w, Station s) {
        Location c = new Location(w, s.x() + 0.5, s.y(), s.z() + 0.5);
        for (Entity e : w.getNearbyEntities(c, CITY_RANGE, 64, CITY_RANGE)) {
            if (e instanceof WanderingTrader t
                    && t.getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean within(Location loc, Station s, double r) {
        double dx = loc.getX() - (s.x() + 0.5);
        double dz = loc.getZ() - (s.z() + 0.5);
        return dx * dx + dz * dz <= r * r;
    }

    /**
     * Testing/ops hook: immediately spawn a Master Trader at the special city
     * nearest to {@code near} (same world). Returns the city name, or null if no
     * special city exists in that world.
     */
    public String forceSpawnNearest(Location near) {
        World w = near.getWorld();
        if (w == null) {
            return null;
        }
        Station best = null;
        double bestD = Double.MAX_VALUE;
        for (Station s : teleport.all()) {
            if (!s.city() || !s.world().equals(w.getName())) {
                continue;
            }
            double dx = s.x() - near.getX();
            double dz = s.z() - near.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        if (best == null) {
            return null;
        }
        spawnTrader(best);
        return best.name();
    }

    /** Testing/ops hook: spawn a Master Trader at a specific city (force-loads its
     * chunk first so it works even from the console with nobody nearby). */
    public boolean forceSpawnAt(Station city) {
        World w = plugin.getServer().getWorld(city.world());
        if (w == null || !city.city()) {
            return false;
        }
        w.getChunkAt(city.x() >> 4, city.z() >> 4);   // ensure loaded
        spawnTrader(city);
        return true;
    }

    private void spawnTrader(Station s) {
        World w = plugin.getServer().getWorld(s.world());
        if (w == null) {
            return;
        }
        // Beside the station pad, on the pad's level so it starts in the plaza.
        Location at = new Location(w, s.x() + 2.5, s.y() + 1, s.z() + 2.5);
        w.spawn(at, WanderingTrader.class, t -> {
            t.setDespawnDelay(DESPAWN_TICKS);
            t.setCanDrinkPotion(false);      // don't turn invisible at night
            t.setCanDrinkMilk(false);
            t.setRecipes(trades.randomStock(STOCK));
            t.customName(Component.text("✦ Master Trader ✦", NamedTextColor.GOLD));
            t.setCustomNameVisible(true);
            t.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        announce(w, s);
    }

    private void announce(World w, Station s) {
        Component msg = Component.text("✦ A Master Trader has arrived in " + s.name() + "!",
                NamedTextColor.GOLD);
        for (Player p : w.getPlayers()) {
            if (within(p.getLocation(), s, CITY_RANGE * 2)) {
                p.sendMessage(msg);
                p.playSound(p.getLocation(), Sound.ENTITY_WANDERING_TRADER_REAPPEARED, 1.0f, 1.0f);
            }
        }
    }
}
