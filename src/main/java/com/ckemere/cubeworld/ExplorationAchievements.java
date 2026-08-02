package com.ckemere.cubeworld;

import com.ckemere.cubeworld.generation.EarthData;
import com.ckemere.cubeworld.generation.MapSampler;
import com.ckemere.cubeworld.generation.MapService;
import com.ckemere.cubeworld.geometry.Vec3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Exploration achievements: reach each pole, climb the Seven Summits, and
 * circumnavigate the planet. The advancement definitions are written as a
 * datapack into the world folder (so the client shows real toasts and an
 * advancement tab); every criterion is {@code minecraft:impossible}, so only
 * this class grants them, from the cube geometry that a datapack can't express
 * (a folded pole, a summit coordinate, a lap's worth of longitude).
 */
public final class ExplorationAchievements {

    private static final String NS = "cubeworld";
    private static final int POLE_RADIUS = 96;          // blocks from the pole point
    private static final int SUMMIT_RADIUS = 44;        // horizontal blocks from a summit
    private static final int SUMMIT_DROP = 22;          // must be within this of the summit height
    private static final double LON_STEP_MAX = 30.0;    // ignore bigger jumps (teleports)

    /** name key, display title, world x/z, summit block y, metres. */
    private record Summit(String key, String title, int x, int z, int y, int m) {}

    private static final Summit[] SUMMITS = {
        new Summit("everest", "Mount Everest", 2181, -7282, 248, 8849),
        new Summit("aconcagua", "Aconcagua", -1, 13521, 220, 6961),
        new Summit("denali", "Denali", -2569, 407, 209, 6190),
        new Summit("kilimanjaro", "Kilimanjaro", 10528, -1600, 205, 5895),
        new Summit("elbrus", "Mount Elbrus", 5012, -2070, 201, 5642),
        new Summit("vinson", "Vinson Massif", -280, 19479, 190, 4892),
        new Summit("kosciuszko", "Mount Kosciuszko", -4038, -15057, 126, 2228),
    };

    // pole points = the polar face centres (geographic poles under the embedding)
    private static final int NORTH_X = 0, NORTH_Z = 0;
    private static final int SOUTH_X = 0, SOUTH_Z = 20480;

    private final MapService maps;
    private final NamespacedKey lastLonKey;
    private final NamespacedKey cumLonKey;

    public ExplorationAchievements(Plugin plugin, MapService maps) {
        this.maps = maps;
        this.lastLonKey = new NamespacedKey(plugin, "circ_last_lon");
        this.cumLonKey = new NamespacedKey(plugin, "circ_cum_lon");
    }

    /** Poll online players in cube overworlds and award anything newly earned. */
    public void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            double x = p.getLocation().getX();
            double y = p.getLocation().getY();
            double z = p.getLocation().getZ();
            checkPoles(p, x, z);
            checkSummits(p, x, y, z);
            checkCircumnavigation(p, w, x, z);
        }
    }

    private void checkPoles(Player p, double x, double z) {
        if (within(x, z, NORTH_X, NORTH_Z, POLE_RADIUS)) {
            grant(p, "north_pole");
        }
        if (within(x, z, SOUTH_X, SOUTH_Z, POLE_RADIUS)) {
            grant(p, "south_pole");
        }
        if (isDone(p, "north_pole") && isDone(p, "south_pole")) {
            grant(p, "both_poles");
        }
    }

    private void checkSummits(Player p, double x, double y, double z) {
        boolean all = true;
        for (Summit s : SUMMITS) {
            if (within(x, z, s.x, s.z, SUMMIT_RADIUS) && y >= s.y - SUMMIT_DROP) {
                grant(p, "summit_" + s.key);
            }
            all &= isDone(p, "summit_" + s.key);
        }
        if (all) {
            grant(p, "seven_summits");
        }
    }

    /**
     * Accumulate signed longitude travelled; a net full turn (>=360 deg) around
     * the polar axis is a circumnavigation. Ignore per-tick jumps (teleports)
     * and the degenerate longitude near the poles.
     */
    private void checkCircumnavigation(Player p, World w, double x, double z) {
        EarthData earth = maps.earthData();
        if (earth == null || isDone(p, "circumnavigate")) {
            return;
        }
        MapSampler sampler = maps.mapFor(w.getSeed()).sampler();
        Vec3 cp = sampler.cubePointAt(x, z);
        if (cp == null) {
            return;
        }
        double[] ll = earth.toLonLat(cp);
        double lon = ll[0];
        if (Math.abs(ll[1]) > 80.0) {
            return; // near a pole longitude is meaningless; don't accumulate
        }
        var pdc = p.getPersistentDataContainer();
        Double last = pdc.get(lastLonKey, PersistentDataType.DOUBLE);
        double cum = pdc.getOrDefault(cumLonKey, PersistentDataType.DOUBLE, 0.0);
        if (last != null) {
            double d = wrap180(lon - last);
            if (Math.abs(d) < LON_STEP_MAX) {
                cum += d;
            }
        }
        pdc.set(lastLonKey, PersistentDataType.DOUBLE, lon);
        pdc.set(cumLonKey, PersistentDataType.DOUBLE, cum);
        if (Math.abs(cum) >= 360.0) {
            grant(p, "circumnavigate");
        }
    }

    private static boolean within(double x, double z, int cx, int cz, int r) {
        double dx = x - cx;
        double dz = z - cz;
        return dx * dx + dz * dz <= (double) r * r;
    }

    private static double wrap180(double deg) {
        double d = (deg + 180.0) % 360.0;
        if (d < 0) {
            d += 360.0;
        }
        return d - 180.0;
    }

    private boolean isDone(Player p, String key) {
        Advancement a = Bukkit.getAdvancement(new NamespacedKey(NS, key));
        return a != null && p.getAdvancementProgress(a).isDone();
    }

    private void grant(Player p, String key) {
        Advancement a = Bukkit.getAdvancement(new NamespacedKey(NS, key));
        if (a == null) {
            return;
        }
        AdvancementProgress prog = p.getAdvancementProgress(a);
        if (prog.isDone()) {
            return;
        }
        for (String crit : prog.getRemainingCriteria()) {
            prog.awardCriteria(crit);
        }
    }

    // ---- datapack authoring -------------------------------------------------

    /**
     * Write the advancement datapack into the world folder. Returns true if the
     * files changed (caller should reload data). Idempotent.
     */
    public static boolean writeDatapack(java.io.File worldFolder) {
        try {
            Path root = worldFolder.toPath().resolve("datapacks").resolve("cubeworld_exploration");
            Path adv = root.resolve("data").resolve(NS).resolve("advancement");
            Files.createDirectories(adv);
            boolean[] changed = {false};
            writeIfChanged(root.resolve("pack.mcmeta"),
                    "{\"pack\":{\"description\":\"CubeWorld exploration achievements\","
                            + "\"min_format\":[107,0],\"max_format\":107}}", changed);

            write(adv, "root", advRoot(), changed);
            write(adv, "north_pole", adv("root", "Top of the World",
                    "Stand at the North Pole", "powder_snow_bucket", "task", false), changed);
            write(adv, "south_pole", adv("root", "Bottom of the World",
                    "Stand at the South Pole", "packed_ice", "task", false), changed);
            write(adv, "both_poles", adv("north_pole", "Pole to Pole",
                    "Reach both the North and South Poles", "snowball", "goal", false), changed);
            write(adv, "circumnavigate", adv("root", "Around the World",
                    "Travel a full lap around the planet", "filled_map", "challenge", false), changed);

            String[][] peaks = {
                {"everest", "Roof of the World", "Summit Mount Everest (8,849 m)", "diamond"},
                {"aconcagua", "Sentinel of Stone", "Summit Aconcagua (6,961 m)", "emerald"},
                {"denali", "The High One", "Summit Denali (6,190 m)", "iron_block"},
                {"kilimanjaro", "Snows of the Equator", "Summit Kilimanjaro (5,895 m)", "powder_snow_bucket"},
                {"elbrus", "Crown of Europe", "Summit Mount Elbrus (5,642 m)", "quartz_block"},
                {"vinson", "The Frozen Continent", "Summit Vinson Massif (4,892 m)", "blue_ice"},
                {"kosciuszko", "Down Under and Up Over", "Summit Mount Kosciuszko (2,228 m)", "moss_block"},
            };
            for (String[] pk : peaks) {
                write(adv, "summit_" + pk[0], adv("root", pk[1], pk[2], pk[3], "goal", false), changed);
            }
            write(adv, "seven_summits", adv("summit_everest", "The Seven Summits",
                    "Summit the highest peak on every continent", "netherite_pickaxe",
                    "challenge", false), changed);
            return changed[0];
        } catch (IOException e) {
            return false;
        }
    }

    private static void write(Path dir, String name, String json, boolean[] changed) throws IOException {
        writeIfChanged(dir.resolve(name + ".json"), json, changed);
    }

    private static void writeIfChanged(Path file, String content, boolean[] changed) throws IOException {
        if (Files.exists(file) && content.equals(Files.readString(file, StandardCharsets.UTF_8))) {
            return;
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        changed[0] = true;
    }

    private static String advRoot() {
        return "{\"display\":{\"icon\":{\"id\":\"minecraft:compass\"},"
                + "\"title\":" + text("CubeWorld Explorer") + ","
                + "\"description\":" + text("Adventure across a world folded into a cube") + ","
                + "\"frame\":\"task\",\"show_toast\":false,\"announce_to_chat\":false,"
                + "\"background\":\"minecraft:gui/advancements/backgrounds/adventure\"},"
                + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}},"
                + "\"requirements\":[[\"done\"]]}";
    }

    private static String adv(String parent, String title, String desc, String icon,
                              String frame, boolean hidden) {
        return "{\"parent\":\"" + NS + ":" + parent + "\","
                + "\"display\":{\"icon\":{\"id\":\"minecraft:" + icon + "\"},"
                + "\"title\":" + text(title) + ",\"description\":" + text(desc) + ","
                + "\"frame\":\"" + frame + "\",\"show_toast\":true,"
                + "\"announce_to_chat\":true,\"hidden\":" + hidden + "},"
                + "\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}},"
                + "\"requirements\":[[\"done\"]]}";
    }

    private static String text(String s) {
        return "{\"text\":\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
