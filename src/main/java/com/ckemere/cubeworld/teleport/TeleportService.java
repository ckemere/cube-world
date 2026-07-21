package com.ckemere.cubeworld.teleport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Registry and rules for teleport stations. A station is a Teleporter Core (a
 * reskinned lodestone) placed on a 3x3 amethyst-block pad; the pad + core is the
 * multiblock. The block at a registered station location is the workstation —
 * right-clicking it opens the network menu. Travel costs lapis, scaled by
 * distance, so a denser network of your own stations is cheaper to cross.
 *
 * <p>The 30 historical cities are pre-seeded and physically built when their
 * chunk first loads, so the network exists from the start. State persists to
 * {@code stations.csv} in the plugin data folder.
 */
public final class TeleportService {

    public static final int BASE_RADIUS = 1;            // 3x3 amethyst pad
    private static final double BLOCKS_PER_LAPIS = 1500.0;
    private static final int MAX_LAPIS = 64;

    /** A registered station: a workstation block location, name, and ticket code. */
    public record Station(String world, int x, int y, int z, String name, boolean city,
                          String code) {
        public String key() {
            return world + ":" + x + ":" + y + ":" + z;
        }

        public Location location(Plugin plugin) {
            World w = plugin.getServer().getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    /** A destination the menu can offer, tagged by how it was reached. */
    public enum OfferKind { RING_A, RING_B, TICKET }

    public record Offer(Station dest, OfferKind kind) {
    }

    private final Plugin plugin;
    private final NamespacedKey coreKey;                 // PDC marker on the item
    private final NamespacedKey recipeKey;
    private final Map<String, Station> stations = new LinkedHashMap<>();
    private final Map<String, Station> byCode = new java.util.HashMap<>();
    private final RingNetwork rings = new RingNetwork();
    private final java.util.Random ringRandom = new java.util.Random();
    // pending city stations to build physically on chunk load: chunkKey -> list
    private final Map<Long, List<PendingCity>> pendingByChunk = new LinkedHashMap<>();

    private record PendingCity(int x, int z, String name) {
    }

    public TeleportService(Plugin plugin) {
        this.plugin = plugin;
        this.coreKey = new NamespacedKey(plugin, "teleporter_core");
        this.recipeKey = new NamespacedKey(plugin, "teleporter_core_recipe");
    }

    // ---------------------------------------------------------------- the item
    /** A Teleporter Core: a lodestone carrying our PDC marker + custom name. */
    public ItemStack createCore(int amount) {
        ItemStack it = new ItemStack(Material.LODESTONE, amount);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Teleporter Core", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Place on a 3x3 amethyst pad", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("to raise a teleport station.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(coreKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public boolean isCore(ItemStack it) {
        if (it == null || it.getType() != Material.LODESTONE || !it.hasItemMeta()) {
            return false;
        }
        return it.getItemMeta().getPersistentDataContainer()
                .has(coreKey, PersistentDataType.BYTE);
    }

    public void registerRecipe() {
        if (plugin.getServer().getRecipe(recipeKey) == null) {
            ShapedRecipe r = new ShapedRecipe(recipeKey, createCore(1));
            r.shape("EAE", "ALA", "EAE");        // ender pearls + amethyst around a lodestone
            r.setIngredient('E', Material.ENDER_PEARL);
            r.setIngredient('A', Material.AMETHYST_SHARD);
            r.setIngredient('L', Material.LODESTONE);
            plugin.getServer().addRecipe(r);
        }
    }

    /** Sanity-check the item logic everything else keys off — logged at startup. */
    public void selfTest() {
        boolean roundtrip = isCore(createCore(1));
        boolean recipe = plugin.getServer().getRecipe(recipeKey) != null;
        plugin.getLogger().info("Teleport self-test: core PDC roundtrip "
                + (roundtrip ? "OK" : "FAILED") + ", recipe "
                + (recipe ? "registered" : "MISSING") + ".");
    }

    // --------------------------------------------------------- raise a station
    /** Register a station if the core block sits on a full 3x3 amethyst pad. */
    public boolean tryRaise(Location core, String name) {
        World w = core.getWorld();
        if (w == null) {
            return false;
        }
        for (int dx = -BASE_RADIUS; dx <= BASE_RADIUS; dx++) {
            for (int dz = -BASE_RADIUS; dz <= BASE_RADIUS; dz++) {
                if (w.getBlockAt(core.getBlockX() + dx, core.getBlockY() - 1, core.getBlockZ() + dz)
                        .getType() != Material.AMETHYST_BLOCK) {
                    return false;
                }
            }
        }
        register(core, name, false);
        return true;
    }

    // ------------------------------------------------------------- the registry
    public Station stationAt(Location loc) {
        return stations.get(loc.getWorld().getName() + ":" + loc.getBlockX() + ":"
                + loc.getBlockY() + ":" + loc.getBlockZ());
    }

    public Station register(Location loc, String name, boolean city) {
        String code = freshCode(loc.getBlockX(), loc.getBlockZ());
        Station s = new Station(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(),
                loc.getBlockZ(), name, city, code);
        stations.put(s.key(), s);
        byCode.put(code, s);
        rings.insertRandom(s.key(), ringRandom);
        save();
        return s;
    }

    public void unregister(Location loc) {
        Station s = stationAt(loc);
        if (s != null) {
            drop(s);
            save();
        }
    }

    private void drop(Station s) {
        stations.remove(s.key());
        byCode.remove(s.code());
        rings.remove(s.key());
    }

    /** A deterministic, currently-unique ticket code for a site. */
    private String freshCode(int x, int z) {
        long seed = (((long) x) << 32) ^ (z & 0xffffffffL);
        for (long salt = 0; salt < 64; salt++) {
            String code = Ticketing.codeFor(seed + salt * 0x9e3779b97f4a7c15L);
            if (!byCode.containsKey(code)) {
                return code;
            }
        }
        return Ticketing.codeFor(seed ^ System.identityHashCode(this));  // vanishingly unlikely
    }

    /** Remove a station by name (admin). Returns the removed station, or null. */
    public Station removeByName(String name) {
        for (Station s : stations.values()) {
            if (s.name().equalsIgnoreCase(name)) {
                drop(s);
                save();
                return s;
            }
        }
        return null;
    }

    public List<Station> all() {
        return new ArrayList<>(stations.values());
    }

    public Station byKey(String key) {
        return stations.get(key);
    }

    public Station stationByCode(String code) {
        return code == null ? null : byCode.get(code);
    }

    // --------------------------------------------------------- offers / tickets
    /** The two (distinct) ring destinations for a station, plus a ticket target. */
    public List<Offer> offers(Station source, Station ticketTarget) {
        List<Offer> out = new ArrayList<>();
        List<String> dests = rings.twoDestinations(source.key());
        if (dests.size() > 0) {
            addOffer(out, stations.get(dests.get(0)), OfferKind.RING_A, source);
        }
        if (dests.size() > 1) {
            addOffer(out, stations.get(dests.get(1)), OfferKind.RING_B, source);
        }
        if (ticketTarget != null) {
            addOffer(out, ticketTarget, OfferKind.TICKET, source);
        }
        return out;
    }

    private void addOffer(List<Offer> out, Station dest, OfferKind kind, Station source) {
        if (dest == null || dest.key().equals(source.key())) {
            return;
        }
        for (Offer o : out) {
            if (o.dest().key().equals(dest.key())) {
                return;                            // no duplicate destinations
            }
        }
        out.add(new Offer(dest, kind));
    }

    /** A written-book ticket for a station: its name + four-word code. */
    public ItemStack ticketBook(Station s) {
        ItemStack it = new ItemStack(Material.WRITTEN_BOOK);
        org.bukkit.inventory.meta.BookMeta m = (org.bukkit.inventory.meta.BookMeta) it.getItemMeta();
        m.title(Component.text("Ticket: " + s.name()));
        m.author(Component.text("CubeWorld Transit"));
        m.addPages(Component.text("Teleport ticket\n\nTo: " + s.name() + "\n\nCode:\n" + s.code()
                + "\n\nWrite these four words in\nany book to make a ticket."));
        it.setItemMeta(m);
        return it;
    }

    /** The station a held book-ticket points to (parsed from its text), or null. */
    public Station ticketTarget(ItemStack it) {
        if (it == null || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.BookMeta bm)) {
            return null;
        }
        if (it.getType() != Material.WRITTEN_BOOK && it.getType() != Material.WRITABLE_BOOK) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Component page : bm.pages()) {
            sb.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(page)).append(' ');
        }
        return stationByCode(Ticketing.parse(sb.toString()));
    }

    /** Stations within {@code radius} blocks of {@code from} (excluding itself), nearest first. */
    public List<Station> near(Location from, double radius) {
        List<Station> out = new ArrayList<>();
        for (Station s : stations.values()) {
            if (!s.world().equals(from.getWorld().getName())) {
                continue;
            }
            double d = dist(from, s);
            if (d > 0 && d <= radius) {
                out.add(s);
            }
        }
        out.sort((a, b) -> Double.compare(dist(from, a), dist(from, b)));
        return out;
    }

    public double dist(Location from, Station s) {
        double dx = from.getX() - s.x();
        double dz = from.getZ() - s.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Lapis cost of a hop, scaled by distance and capped. */
    public int lapisCost(Location from, Station to) {
        int c = (int) Math.round(dist(from, to) / BLOCKS_PER_LAPIS);
        return Math.max(1, Math.min(MAX_LAPIS, c));
    }

    // --------------------------------------------------------- city pre-seeding
    /** Load the 30 city sites so their stations can be built when chunks load.
     * Cities already built (present in the persisted registry) are skipped so a
     * restart doesn't rebuild — and re-stack — them. */
    public void seedCities() {
        java.util.Set<String> built = new java.util.HashSet<>();
        for (Station s : stations.values()) {
            if (s.city()) {
                built.add(s.name());
            }
        }
        try (InputStream in = plugin.getResource("cities_anchor.csv")) {
            if (in == null) {
                return;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] p = line.split(",");
                    if (p.length < 5) {
                        continue;
                    }
                    String name = p[4].trim();
                    if (built.contains(name)) {
                        continue;                    // already built in a prior session
                    }
                    int x = Integer.parseInt(p[0].trim());
                    int z = Integer.parseInt(p[1].trim());
                    pendingByChunk.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> new ArrayList<>())
                            .add(new PendingCity(x, z, name));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Teleport: failed to seed cities (" + e + ").");
        }
    }

    /** City sites waiting to be built in the given chunk, or null. Removed once returned. */
    public List<PendingCity> takePendingFor(int chunkX, int chunkZ) {
        return pendingByChunk.remove(chunkKey(chunkX, chunkZ));
    }

    public int pendingCount() {
        return pendingByChunk.values().stream().mapToInt(List::size).sum();
    }

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    // --------------------------------------------------------------- accessors
    public int pendingChunkPeek(int cx, int cz) {
        List<PendingCity> l = pendingByChunk.get(chunkKey(cx, cz));
        return l == null ? 0 : l.size();
    }

    public record CityBuild(int x, int z, String name) {
    }

    /** Convert internal pending records to a public shape for the listener. */
    public List<CityBuild> pendingBuildsFor(int cx, int cz) {
        List<PendingCity> l = takePendingFor(cx, cz);
        if (l == null) {
            return List.of();
        }
        List<CityBuild> out = new ArrayList<>();
        for (PendingCity p : l) {
            out.add(new CityBuild(p.x(), p.z(), p.name()));
        }
        return out;
    }

    // ----------------------------------------------------------------- travel
    /** Charge lapis and teleport the player onto a destination station's core.
     * Shared by the GUI and the /cubeworld tpto command. Returns success. */
    public boolean travel(org.bukkit.entity.Player p, Location source, Station dest) {
        int cost = lapisCost(source, dest);
        if (countLapis(p) < cost) {
            p.sendMessage(net.kyori.adventure.text.Component.text("Not enough lapis (need " + cost
                    + ").", net.kyori.adventure.text.format.NamedTextColor.RED));
            return false;
        }
        World w = plugin.getServer().getWorld(dest.world());
        if (w == null) {
            p.sendMessage(net.kyori.adventure.text.Component.text("Destination unavailable.",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return false;
        }
        removeLapis(p, cost);
        p.closeInventory();
        spark(source);
        Location stand = new Location(w, dest.x() + 0.5, dest.y() + 1, dest.z() + 0.5,
                p.getLocation().getYaw(), p.getLocation().getPitch());
        p.teleport(stand);
        spark(stand);
        p.playSound(stand, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
        p.sendMessage(net.kyori.adventure.text.Component.text("Teleported to " + dest.name() + " ("
                + cost + " lapis).", net.kyori.adventure.text.format.NamedTextColor.AQUA));
        return true;
    }

    public Station byName(String name) {
        for (Station s : stations.values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    public int countLapis(org.bukkit.entity.Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == Material.LAPIS_LAZULI) {
                n += it.getAmount();
            }
        }
        return n;
    }

    public void removeLapis(org.bukkit.entity.Player p, int amount) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (amount <= 0) {
                break;
            }
            if (it != null && it.getType() == Material.LAPIS_LAZULI) {
                int take = Math.min(amount, it.getAmount());
                it.setAmount(it.getAmount() - take);
                amount -= take;
            }
        }
    }

    public void spark(Location l) {
        if (l.getWorld() != null) {
            l.getWorld().spawnParticle(org.bukkit.Particle.REVERSE_PORTAL,
                    l.clone().add(0.5, 1.0, 0.5), 40, 0.3, 0.5, 0.3, 0.05);
        }
    }

    // ---------------------------------------------------------------- ambience
    /** Gentle particles above every loaded station (scheduled ~every 2s). */
    public void ambientTick() {
        for (Station s : stations.values()) {
            World w = plugin.getServer().getWorld(s.world());
            if (w == null || !w.isChunkLoaded(s.x() >> 4, s.z() >> 4)) {
                continue;
            }
            w.spawnParticle(org.bukkit.Particle.WITCH, s.x() + 0.5, s.y() + 1.1, s.z() + 0.5,
                    4, 0.22, 0.35, 0.22, 0.0);
        }
    }

    // -------------------------------------------------------------- persistence
    private Path file() {
        return plugin.getDataFolder().toPath().resolve("stations.csv");
    }

    private Path ringsFile() {
        return plugin.getDataFolder().toPath().resolve("rings.csv");
    }

    public void load() {
        Path f = file();
        if (Files.exists(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    // world,x,y,z,city,code,name  (code/name have no commas)
                    String[] p = line.split(",", 7);
                    if (p.length < 6) {
                        continue;
                    }
                    int x = Integer.parseInt(p[1]);
                    int y = Integer.parseInt(p[2]);
                    int z = Integer.parseInt(p[3]);
                    boolean city = Boolean.parseBoolean(p[4]);
                    String code = p.length >= 7 ? p[5] : freshCode(x, z);   // migrate old rows
                    String name = p.length >= 7 ? p[6] : p[5];
                    Station s = new Station(p[0], x, y, z, name, city, code);
                    stations.put(s.key(), s);
                    byCode.put(code, s);
                }
            } catch (IOException | NumberFormatException e) {
                plugin.getLogger().warning("Teleport: failed to load stations (" + e + ").");
            }
        }
        Path rf = ringsFile();
        if (Files.exists(rf)) {
            try {
                List<String> lines = Files.readAllLines(rf, StandardCharsets.UTF_8);
                rings.load(lines.size() > 0 ? splitKeys(lines.get(0)) : List.of(),
                        lines.size() > 1 ? splitKeys(lines.get(1)) : List.of());
            } catch (IOException e) {
                plugin.getLogger().warning("Teleport: failed to load rings (" + e + ").");
            }
        }
        // reconcile: drop ring entries for gone stations; add stations missing from the rings
        rings.ringA().removeIf(k -> !stations.containsKey(k));
        rings.ringB().removeIf(k -> !stations.containsKey(k));
        for (Station s : stations.values()) {
            if (!rings.contains(s.key())) {
                rings.insertRandom(s.key(), ringRandom);
            }
        }
    }

    private static List<String> splitKeys(String line) {
        List<String> out = new ArrayList<>();
        for (String k : line.split(",")) {
            if (!k.isBlank()) {
                out.add(k.trim());
            }
        }
        return out;
    }

    public void save() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            StringBuilder sb = new StringBuilder("# world,x,y,z,city,code,name\n");
            for (Station s : stations.values()) {
                sb.append(s.world()).append(',').append(s.x()).append(',').append(s.y())
                        .append(',').append(s.z()).append(',').append(s.city()).append(',')
                        .append(s.code()).append(',').append(s.name().replace(',', ' ')).append('\n');
            }
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
            Files.writeString(ringsFile(),
                    String.join(",", rings.ringA()) + "\n" + String.join(",", rings.ringB()) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Teleport: failed to save stations (" + e + ").");
        }
    }
}
