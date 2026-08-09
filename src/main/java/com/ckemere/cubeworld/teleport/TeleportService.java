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
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
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

    // A non-owner can mine a core loose, but only after this much cumulative
    // ACTIVE mining time (wall-clock, so it's tool-independent). 1 MC day =
    // 20 real minutes = 24000 ticks; three days = an hour of real mining, spread
    // over as many sessions as they like (the wear persists). Tune here.
    public static final long WARD_TICKS = 3L * 24000L;

    // A core left alone slowly heals its wear: a fully-worn core recovers over
    // this much real time (~4 real hours, several MC days), so grief has to be
    // sustained, not nibbled — mining is four times faster than healing. A core
    // that is being actively mined does not heal.
    public static final long WARD_REGEN_TICKS = 4L * WARD_TICKS;

    /** A registered station: a workstation block location, name, ticket code and
     * owner. {@code owner} is the placing player's UUID, or "" for the preloaded
     * city stations (which belong to nobody and are protected from everyone). */
    public record Station(String world, int x, int y, int z, String name, boolean city,
                          String code, String owner) {
        public String key() {
            return world + ":" + x + ":" + y + ":" + z;
        }

        public Location location(Plugin plugin) {
            World w = plugin.getServer().getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }

        public boolean hasOwner() {
            return owner != null && !owner.isEmpty();
        }

        /** True if this player placed the station (never true for city stations). */
        public boolean ownedBy(java.util.UUID uuid) {
            return hasOwner() && owner.equals(uuid.toString());
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
    private final Map<String, Long> wear = new java.util.HashMap<>();   // key -> mined ticks
    private final java.util.Set<String> cracked = new java.util.HashSet<>();  // showing crack overlay
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
            // Sculk shriekers at the corners (they summon the Warden — a nod to
            // stations being fixed "destinations"), eyes of ender on the edges,
            // and a conduit at the heart.
            ShapedRecipe r = new ShapedRecipe(recipeKey, createCore(1));
            r.shape("SES", "ECE", "SES");
            r.setIngredient('S', Material.SCULK_SHRIEKER);
            r.setIngredient('E', Material.ENDER_EYE);
            r.setIngredient('C', Material.CONDUIT);
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
    public boolean tryRaise(Location core, String name, String owner) {
        World w = core.getWorld();
        if (w == null || !baseIntact(core)) {
            return false;
        }
        register(core, name, false, owner);
        return true;
    }

    /** True while a station's full 3x3 amethyst pad is present beneath its core.
     * A station whose base is breached goes inactive until the pad is restored. */
    public boolean baseIntact(Location core) {
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
        return true;
    }

    /** The station whose 3x3 amethyst base includes the block at (bx,by,bz), or
     * null. Used to protect/deactivate a station when its pad is mined: the core
     * sits one above the pad, centred, so it is at (bx+dx, by+1, bz+dz). */
    public Station stationForBaseBlock(World w, int bx, int by, int bz) {
        for (int dx = -BASE_RADIUS; dx <= BASE_RADIUS; dx++) {
            for (int dz = -BASE_RADIUS; dz <= BASE_RADIUS; dz++) {
                Station s = stations.get(w.getName() + ":" + (bx + dx) + ":" + (by + 1)
                        + ":" + (bz + dz));
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------- the registry
    public Station stationAt(Location loc) {
        return stations.get(loc.getWorld().getName() + ":" + loc.getBlockX() + ":"
                + loc.getBlockY() + ":" + loc.getBlockZ());
    }

    public Station register(Location loc, String name, boolean city, String owner) {
        String code = freshCode(loc.getBlockX(), loc.getBlockZ());
        Station s = new Station(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(),
                loc.getBlockZ(), name, city, code, owner == null ? "" : owner);
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
        wear.remove(s.key());
        cracked.remove(s.key());
        rings.remove(s.key());
    }

    /** Add mined ticks to a core's wear and return the new total. Persists lazily
     * (callers save when a mining session ends). */
    public long addWear(String key, long ticks) {
        long total = wear.merge(key, ticks, Long::sum);
        return total;
    }

    public long wearOf(String key) {
        return wear.getOrDefault(key, 0L);
    }

    /** Station keys that currently carry wear (a snapshot, safe to iterate). */
    public java.util.Set<String> wornKeys() {
        return new java.util.HashSet<>(wear.keySet());
    }

    /** Heal a core by {@code step} ticks, forgetting it once fully recovered. */
    public void regenWear(String key, long step) {
        Long cur = wear.get(key);
        if (cur == null) {
            return;
        }
        long next = cur - step;
        if (next <= 0) {
            wear.remove(key);
        } else {
            wear.put(key, next);
        }
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
    /**
     * The city name from field 5 of cities_anchor.csv, minus any trailing inline
     * {@code #} comment. Several rows annotate why a site was moved, e.g.
     * {@code ...,Rome   # moved 11km off the water: 80% -> 92% land}; without
     * stripping, the whole annotation became part of the station name and showed
     * up in the registry and in player-facing station listings.
     */
    public static String cityNameOf(String field) {
        int hash = field.indexOf('#');
        return (hash < 0 ? field : field.substring(0, hash)).trim();
    }

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
                    String name = cityNameOf(p[4]);
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

    // ---------------------------------------------------- build a city station
    /**
     * Physically raise a preloaded city station when its chunk loads. Prefer to
     * co-opt a village house — swap a 3x3 of its floor for an amethyst pad, clear
     * the bed and furniture in that footprint, and set the Core where the bed was,
     * so the station reads as the town's teleport hall. Where there's no house,
     * fall back to a clean pad on the real ground (never a tree, rooftop or water).
     * Either way a copper torch stands beside it as a quiet marker. Call on the
     * main thread, off the chunk-load event (it may load nearby chunks).
     */
    public void buildCityStation(World w, int x, int z, String name) {
        int[] spot = null;
        Block bed = findHouseBed(w, x, z, 20);
        if (bed != null) {
            // Co-opt the house, but place the 3x3 pad on an INTERIOR floor patch
            // whose whole footprint (and its headroom) is clear of walls, so the
            // build only pulls up floor tiles and clears furniture — never breaks
            // a wall. Search outward from the bed for the nearest fitting patch.
            spot = interiorPadSpot(w, bed.getX(), bed.getY(), bed.getZ(), 4);
        }
        if (spot == null) {
            // No house (or none with room for a clean pad): a tidy pad on the
            // real ground near the centre, again only where nothing must break.
            spot = openGroundSpot(w, x, z, 10);
        }
        int cx = spot[0];
        int cy = spot[1];
        int cz = spot[2];
        layStation(w, cx, cy, cz);
        supportPad(w, cx, cy, cz);
        placeCopperTorch(w, cx, cy, cz);
        register(new Location(w, cx, cy, cz), name, true, "");
    }

    /** Can the pad legitimately occupy this block — i.e. is it air, a plant, or
     * removable furniture (bed, carpet, a workstation "table"), rather than a
     * structural wall we must not break? Solid structure returns false. */
    private static boolean clearable(Material m) {
        if (!m.isSolid()) {
            return true;                         // air, torches, plants, carpets, flowers
        }
        if (Tag.BEDS.isTagged(m)) {
            return true;
        }
        String n = m.name();
        return n.endsWith("_TABLE") || m == Material.LOOM || m == Material.BARREL
                || m == Material.LECTERN || m == Material.COMPOSTER || m == Material.CAULDRON
                || m == Material.BELL || m == Material.STONECUTTER || m == Material.GRINDSTONE
                || m == Material.DECORATED_POT || m == Material.FLOWER_POT;
    }

    /** Does a 3x3 pad centred here rest on a solid floor with wall-free headroom?
     * Every footprint cell needs solid ground one below and three clearable
     * blocks above, so laying the pad only replaces floor + furniture. */
    private boolean padFits(World w, int cx, int cy, int cz) {
        for (int dx = -BASE_RADIUS; dx <= BASE_RADIUS; dx++) {
            for (int dz = -BASE_RADIUS; dz <= BASE_RADIUS; dz++) {
                if (!w.getBlockAt(cx + dx, cy - 1, cz + dz).getType().isSolid()) {
                    return false;                // no floor to stand the pad on
                }
                for (int dy = 0; dy <= 2; dy++) {
                    if (!clearable(w.getBlockAt(cx + dx, cy + dy, cz + dz).getType())) {
                        return false;            // a wall (or other structure) is in the way
                    }
                }
            }
        }
        return true;
    }

    /** Nearest interior spot to the bed (same floor level) where a pad fits without
     * breaking walls, or null if the house is too tight. */
    private int[] interiorPadSpot(World w, int bx, int by, int bz, int radius) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long d = (long) dx * dx + (long) dz * dz;
                if (d < bestD && padFits(w, bx + dx, by, bz + dz)) {
                    bestD = d;
                    best = new int[] {bx + dx, by, bz + dz};
                }
            }
        }
        return best;
    }

    /** Nearest clear patch of real ground to the city centre where a pad fits, so
     * a house-less city still gets a clean pad that breaks nothing. */
    private int[] openGroundSpot(World w, int x, int z, int radius) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = x + dx;
                int cz = z + dz;
                int cy = groundY(w, cx, cz) + 1;
                long d = (long) dx * dx + (long) dz * dz;
                if (d < bestD && padFits(w, cx, cy, cz)) {
                    bestD = d;
                    best = new int[] {cx, cy, cz};
                }
            }
        }
        return best != null ? best : new int[] {x, groundY(w, x, z) + 1, z};
    }

    /** The nearest village bed to a city centre (each bed marks a house), or null.
     * Loads the search area so a just-arrived city can still find its houses. */
    private Block findHouseBed(World w, int cx, int cz, int radius) {
        for (int ccx = (cx - radius) >> 4; ccx <= (cx + radius) >> 4; ccx++) {
            for (int ccz = (cz - radius) >> 4; ccz <= (cz + radius) >> 4; ccz++) {
                w.getChunkAt(ccx, ccz);          // ensure generated + loaded
            }
        }
        Block best = null;
        long bestD = Long.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int bx = cx + dx;
                int bz = cz + dz;
                int top = Math.min(w.getMaxHeight() - 1, w.getHighestBlockYAt(bx, bz) + 1);
                for (int y = top; y >= top - 14 && y > w.getMinHeight() + 1; y--) {
                    if (Tag.BEDS.isTagged(w.getBlockAt(bx, y, bz).getType())) {
                        // Only a ground-floor bed: its floor (y-1) must rest on solid
                        // ground (y-2), so we skip stilted / upper-storey / over-water
                        // beds that would leave the pad floating.
                        if (w.getBlockAt(bx, y - 2, bz).getType().isSolid()) {
                            long d = (long) dx * dx + (long) dz * dz;
                            if (d < bestD) {
                                bestD = d;
                                best = w.getBlockAt(bx, y, bz);
                            }
                        }
                        break;                       // one bed per column
                    }
                }
            }
        }
        return best;
    }

    /** First solid, non-foliage block from the surface down — the real ground,
     * skipping tree canopy, water and plants a naive heightmap lands on. */
    private int groundY(World w, int x, int z) {
        int top = Math.min(w.getMaxHeight() - 1, w.getHighestBlockYAt(x, z));
        for (int y = top; y > w.getMinHeight() + 1; y--) {
            if (isGround(w.getBlockAt(x, y, z).getType())) {
                return y;
            }
        }
        return top;
    }

    private static boolean isGround(Material m) {
        if (!m.isSolid()) {
            return false;                            // air, water, lava, plants, snow layer
        }
        String n = m.name();
        return !(n.endsWith("_LEAVES") || n.endsWith("_LOG") || n.endsWith("_WOOD")
                || n.endsWith("_STEM") || n.contains("SAPLING") || m == Material.SNOW);
    }

    /** Lay the 3x3 amethyst pad, clear three blocks of headroom, set the Core. */
    private void layStation(World w, int cx, int cy, int cz) {
        for (int dx = -BASE_RADIUS; dx <= BASE_RADIUS; dx++) {
            for (int dz = -BASE_RADIUS; dz <= BASE_RADIUS; dz++) {
                w.getBlockAt(cx + dx, cy - 1, cz + dz).setType(Material.AMETHYST_BLOCK, false);
                for (int dy = 0; dy <= 2; dy++) {
                    w.getBlockAt(cx + dx, cy + dy, cz + dz).setType(Material.AIR, false);
                }
            }
        }
        w.getBlockAt(cx, cy, cz).setType(Material.LODESTONE, false);
    }

    /** Give the pad a foundation: fill any air/water directly beneath each pad
     * block down to the first solid ground, so a pad on a slope edge or over
     * water reads as grounded rather than floating. */
    private void supportPad(World w, int cx, int cy, int cz) {
        for (int dx = -BASE_RADIUS; dx <= BASE_RADIUS; dx++) {
            for (int dz = -BASE_RADIUS; dz <= BASE_RADIUS; dz++) {
                int depth = 0;
                for (int y = cy - 2; y > w.getMinHeight() + 1 && depth < 12; y--, depth++) {
                    Block b = w.getBlockAt(cx + dx, y, cz + dz);
                    if (b.getType().isSolid()) {
                        break;                       // reached support
                    }
                    b.setType(Material.COBBLESTONE, false);
                }
            }
        }
    }

    /** Stand a copper torch on solid ground beside the pad as a quiet marker.
     * Prefer just outside the pad; failing that (walls, a tight house), stand it
     * on a pad corner so every station still gets its beacon. */
    private void placeCopperTorch(World w, int cx, int cy, int cz) {
        int r = BASE_RADIUS + 1;
        for (int[] a : new int[][] {{r, 0}, {-r, 0}, {0, r}, {0, -r}}) {
            Block below = w.getBlockAt(cx + a[0], cy - 1, cz + a[1]);
            Block at = w.getBlockAt(cx + a[0], cy, cz + a[1]);
            if (below.getType().isSolid() && at.getType() == Material.AIR) {
                at.setType(Material.COPPER_TORCH, false);
                return;
            }
        }
        for (int[] c : new int[][] {{BASE_RADIUS, BASE_RADIUS}, {-BASE_RADIUS, BASE_RADIUS},
                {BASE_RADIUS, -BASE_RADIUS}, {-BASE_RADIUS, -BASE_RADIUS}}) {
            if (w.getBlockAt(cx + c[0], cy, cz + c[1]).getType() == Material.AIR) {
                w.getBlockAt(cx + c[0], cy, cz + c[1]).setType(Material.COPPER_TORCH, false);
                return;
            }
        }
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
    /** Station ambience (scheduled ~every 2s): an active station (intact pad)
     * gives a gentle portal shimmer; an inactive one (breached pad) coughs grey
     * smoke so you can see at a glance that it needs its amethyst restored. */
    public void ambientTick() {
        for (Station s : stations.values()) {
            World w = plugin.getServer().getWorld(s.world());
            if (w == null || !w.isChunkLoaded(s.x() >> 4, s.z() >> 4)) {
                continue;
            }
            Location loc = new Location(w, s.x(), s.y(), s.z());
            if (baseIntact(loc)) {
                w.spawnParticle(org.bukkit.Particle.WITCH, s.x() + 0.5, s.y() + 1.1, s.z() + 0.5,
                        4, 0.22, 0.35, 0.22, 0.0);
            } else {
                w.spawnParticle(org.bukkit.Particle.SMOKE, s.x() + 0.5, s.y() + 1.0, s.z() + 0.5,
                        6, 0.18, 0.15, 0.18, 0.01);
            }
            // Show accumulated mining as vanilla break-cracks, so anyone looking
            // sees a core that's been under attack. Clear once it fully heals.
            long wv = wearOf(s.key());
            if (wv > 0) {
                sendCrack(w, loc, s.key(), (float) Math.min(1.0, (double) wv / WARD_TICKS));
                cracked.add(s.key());
            } else if (cracked.remove(s.key())) {
                sendCrack(w, loc, s.key(), 0f);
            }
        }
    }

    private void sendCrack(World w, Location loc, String key, float progress) {
        int id = 0x6C000000 | (key.hashCode() & 0x00FFFFFF);   // stable per-block source id
        for (org.bukkit.entity.Player p : w.getPlayersSeeingChunk(loc.getBlockX() >> 4,
                loc.getBlockZ() >> 4)) {
            p.sendBlockDamage(loc, progress, id);
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
                    // world,x,y,z,city,code,owner,wear,name (all but name comma-free).
                    // Shorter rows are older formats (no wear / no owner / no code).
                    String[] p = line.split(",", 9);
                    if (p.length < 6) {
                        continue;
                    }
                    int x = Integer.parseInt(p[1]);
                    int y = Integer.parseInt(p[2]);
                    int z = Integer.parseInt(p[3]);
                    boolean city = Boolean.parseBoolean(p[4]);
                    String code = p.length >= 7 ? p[5] : freshCode(x, z);
                    String owner = p.length >= 8 ? p[6] : "";
                    long w = p.length >= 9 ? parseWear(p[7]) : 0L;
                    String name = p.length >= 9 ? p[8]
                            : (p.length == 8 ? p[7] : (p.length == 7 ? p[6] : p[5]));
                    Station s = new Station(p[0], x, y, z, name, city, code, owner);
                    stations.put(s.key(), s);
                    byCode.put(code, s);
                    if (w > 0) {
                        wear.put(s.key(), w);
                    }
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

    private static long parseWear(String s) {
        try {
            return Math.max(0L, Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
            return 0L;
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
            StringBuilder sb = new StringBuilder("# world,x,y,z,city,code,owner,wear,name\n");
            for (Station s : stations.values()) {
                sb.append(s.world()).append(',').append(s.x()).append(',').append(s.y())
                        .append(',').append(s.z()).append(',').append(s.city()).append(',')
                        .append(s.code()).append(',').append(s.owner()).append(',')
                        .append(wearOf(s.key())).append(',')
                        .append(s.name().replace(',', ' ')).append('\n');
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
