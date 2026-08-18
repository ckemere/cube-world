package com.ckemere.cubeworld;

import com.ckemere.cubeworld.generation.MapSampler;
import com.ckemere.cubeworld.generation.MapService;
import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.seam.MirrorService;
import com.ckemere.cubeworld.seam.SeamService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class CubeWorldCommand implements CommandExecutor, TabCompleter {

    private final CubeGeometry geometry;
    private final CubeGeometry netherGeometry;
    private final SeamService seams;
    private final MirrorService mirrors;
    private final MapService maps;
    private final com.ckemere.cubeworld.teleport.TeleportService teleport;
    private final com.ckemere.cubeworld.trades.MasterTraderService masterTraders;
    private final com.ckemere.cubeworld.generation.OreEnrichment oreEnrichment;
    private final com.ckemere.cubeworld.generation.Prospector prospector;

    public CubeWorldCommand(CubeGeometry geometry, CubeGeometry netherGeometry, SeamService seams,
                            MirrorService mirrors, MapService maps,
                            com.ckemere.cubeworld.teleport.TeleportService teleport,
                            com.ckemere.cubeworld.trades.MasterTraderService masterTraders,
                            com.ckemere.cubeworld.generation.OreEnrichment oreEnrichment,
                            com.ckemere.cubeworld.generation.Prospector prospector) {
        this.prospector = prospector;
        this.geometry = geometry;
        this.netherGeometry = netherGeometry;
        this.seams = seams;
        this.mirrors = mirrors;
        this.maps = maps;
        this.teleport = teleport;
        this.masterTraders = masterTraders;
        this.oreEnrichment = oreEnrichment;
    }

    /** Angular error (deg) between the lon/lat at world (x,z) and a target, or a
     * huge value off the net. Used to invert the world -> lon/lat projection. */
    private double latLonErr(com.ckemere.cubeworld.generation.EarthData earth,
                             double x, double z, double tLat, double tLon) {
        com.ckemere.cubeworld.geometry.Vec3 p = sampler().cubePointAt(x, z);
        if (p == null) {
            return Double.MAX_VALUE / 4;
        }
        double[] ll = earth.toLonLat(p);
        double dLat = ll[1] - tLat;
        double dLon = ll[0] - tLon;
        if (dLon > 180) {
            dLon -= 360;
        }
        if (dLon < -180) {
            dLon += 360;
        }
        dLon *= Math.cos(Math.toRadians(tLat));   // scale to real angular distance
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    /** The sampler for the main world's seed. */
    /** Fraction of a disc of radius {@code r} whose target surface is above sea level. */
    private double landFraction(int cx, int cz, int r, int step, int margin) {
        double dry = 0.0;
        int total = 0;
        for (int dz = -r; dz <= r; dz += step) {
            for (int dx = -r; dx <= r; dx += step) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                total++;
                // Continuous dryness rather than a threshold: a hard cut at
                // sea+2 scores every floodplain city 0 (Kaifeng, Alexandria and
                // Angkor all sit under 2 blocks of target), which makes the
                // search compare noise. Ramp over `margin` blocks instead.
                double fbk = sampler().heightAt(cx + dx, cz + dz)
                        - com.ckemere.cubeworld.generation.EarthMapSpec.SEA_LEVEL;
                dry += Math.clamp(fbk / Math.max(1, margin), 0.0, 1.0);
            }
        }
        return total == 0 ? 0.0 : dry / total;
    }

    private MapSampler sampler() {
        return maps.mapFor(org.bukkit.Bukkit.getWorlds().get(0).getSeed()).sampler();
    }

    /**
     * The only subcommands a non-op may run. Everything else is admin/research:
     * it either mutates the world, grants items, reveals the teleport network
     * (which players are meant to discover), or x-rays terrain.
     *
     * <p>Deliberately an allow-list, not a deny-list: a new subcommand added
     * later defaults to admin-only rather than silently becoming public.
     */
    private static final java.util.Set<String> PUBLIC_SUBCOMMANDS =
            java.util.Set.of("ping", "oreprobe", "findlatlon", "mapprecision");

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            return false;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        // Console/RCON passes both checks (ConsoleCommandSender has every
        // permission), so tools/ keeps working unchanged.
        String needed = PUBLIC_SUBCOMMANDS.contains(sub) ? "cubeworld.use" : "cubeworld.admin";
        if (!sender.hasPermission(needed)) {
            sender.sendMessage(Component.text(
                    PUBLIC_SUBCOMMANDS.contains(sub)
                            ? "You do not have permission to use CubeWorld commands."
                            : "That is an operator-only command. Try: "
                              + String.join(", ", new java.util.TreeSet<>(PUBLIC_SUBCOMMANDS)),
                    NamedTextColor.RED));
            return true;
        }
        switch (sub) {
            case "ping" -> {
                sender.sendMessage(Component.text("CubeWorld: pong!", NamedTextColor.GREEN));
                return true;
            }
            case "villagepreview" -> {
                org.bukkit.World vw = null;
                for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
                    if (w.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                        vw = w;
                        break;
                    }
                }
                if (vw == null || args.length < 3) {
                    sender.sendMessage(Component.text(
                            "Usage: /cubeworld villagepreview <chunkX> <chunkZ> [list|<index>] [mark]",
                            NamedTextColor.RED));
                    return true;
                }
                return com.ckemere.cubeworld.city.VillageFixPreview.run(sender, vw, args);
            }
            case "prospector" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    return true;
                }
                p.getInventory().addItem(prospector.create());
                p.sendMessage(Component.text(
                        "Gave an untuned Prospector. Craft it together with an ore sample "
                        + "(e.g. a gold ingot) to tune it.", NamedTextColor.AQUA));
                return true;
            }
            case "reciperecheck" -> {
                teleport.registerRecipe();
                teleport.selfTest();
                sender.sendMessage(Component.text(
                        "Re-registered the Teleporter Core recipe; see console for the self-test.",
                        NamedTextColor.AQUA));
                return true;
            }
            case "tpcore" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    return true;
                }
                p.getInventory().addItem(teleport.createCore(1));
                p.sendMessage(Component.text("Gave a Teleporter Core.", NamedTextColor.AQUA));
                return true;
            }
            case "tpto" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /cubeworld tpto <station name>",
                            NamedTextColor.RED));
                    return true;
                }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                var dest = teleport.byName(name);
                if (dest == null) {
                    p.sendMessage(Component.text("No station named '" + name + "'.", NamedTextColor.RED));
                    return true;
                }
                teleport.travel(p, p.getLocation(), dest);
                return true;
            }
            case "tpremove" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /cubeworld tpremove <station name>",
                            NamedTextColor.RED));
                    return true;
                }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                var removed = teleport.removeByName(name);
                sender.sendMessage(removed == null
                        ? Component.text("No station named '" + name + "'.", NamedTextColor.RED)
                        : Component.text("Removed station " + removed.name() + ".", NamedTextColor.YELLOW));
                return true;
            }
            case "tpoffers" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /cubeworld tpoffers <station name>",
                            NamedTextColor.RED));
                    return true;
                }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                var s = teleport.byName(name);
                if (s == null) {
                    sender.sendMessage(Component.text("No station named '" + name + "'.", NamedTextColor.RED));
                    return true;
                }
                var offers = teleport.offers(s, null);
                sender.sendMessage(Component.text(s.name() + " offers " + offers.size()
                        + " ring destination(s):", NamedTextColor.AQUA));
                for (var o : offers) {
                    sender.sendMessage(Component.text("  " + o.kind() + " -> " + o.dest().name()
                            + " [" + o.dest().code() + "]", NamedTextColor.GRAY));
                }
                return true;
            }
            case "tpticket" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /cubeworld tpticket <station name>",
                            NamedTextColor.RED));
                    return true;
                }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                var s = teleport.byName(name);
                if (s == null) {
                    sender.sendMessage(Component.text("No station named '" + name + "'.", NamedTextColor.RED));
                    return true;
                }
                org.bukkit.inventory.ItemStack book = teleport.ticketBook(s);
                var back = teleport.ticketTarget(book);          // create -> parse -> resolve
                boolean ok = back != null && back.key().equals(s.key());
                sender.sendMessage(Component.text("Ticket roundtrip " + s.name() + ": "
                        + (ok ? "OK" : "FAILED") + "  [" + s.code() + "]",
                        ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                if (sender instanceof Player p) {
                    p.getInventory().addItem(book);
                    p.sendMessage(Component.text("Gave you the ticket book.", NamedTextColor.AQUA));
                }
                return true;
            }
            case "mastertrader" -> {
                if (args.length >= 2) {                       // by city name (console/RCON friendly)
                    String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                    var s = teleport.byName(name);
                    if (s == null || !s.city()) {
                        sender.sendMessage(Component.text("No special city named '" + name + "'.",
                                NamedTextColor.RED));
                        return true;
                    }
                    boolean ok = masterTraders.forceSpawnAt(s);
                    sender.sendMessage(ok
                            ? Component.text("Master Trader summoned to " + s.name() + ".", NamedTextColor.GOLD)
                            : Component.text("Could not spawn (world not loaded).", NamedTextColor.RED));
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Usage: /cubeworld mastertrader <city name>",
                            NamedTextColor.RED));
                    return true;
                }
                String city = masterTraders.forceSpawnNearest(p.getLocation());
                sender.sendMessage(city != null
                        ? Component.text("Master Trader summoned to " + city + ".", NamedTextColor.GOLD)
                        : Component.text("No special city found in this world.", NamedTextColor.RED));
                return true;
            }
            case "tpsim" -> {
                // build a test pad + core at x,y,z and run the raise logic (console-testable)
                if (args.length != 4) {
                    sender.sendMessage(Component.text("Usage: /cubeworld tpsim <x> <y> <z>",
                            NamedTextColor.RED));
                    return true;
                }
                try {
                    int x = Integer.parseInt(args[1]);
                    int y = Integer.parseInt(args[2]);
                    int z = Integer.parseInt(args[3]);
                    org.bukkit.World w = org.bukkit.Bukkit.getWorlds().get(0);
                    w.getChunkAt(x >> 4, z >> 4).load(true);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            w.getBlockAt(x + dx, y - 1, z + dz).setType(org.bukkit.Material.AMETHYST_BLOCK);
                        }
                    }
                    w.getBlockAt(x, y, z).setType(org.bukkit.Material.LODESTONE);
                    String owner = sender instanceof Player sp ? sp.getUniqueId().toString() : "";
                    boolean ok = teleport.tryRaise(new org.bukkit.Location(w, x, y, z), "SimTest",
                            owner);
                    sender.sendMessage(Component.text("tpsim raise=" + ok + " at " + x + "," + y + ","
                            + z, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("Coordinates must be integers.", NamedTextColor.RED));
                }
                return true;
            }
            case "tpstations" -> {
                var list = teleport.all();
                sender.sendMessage(Component.text(list.size() + " teleport stations registered ("
                        + teleport.pendingCount() + " cities pending build).", NamedTextColor.AQUA));
                int shown = 0;
                for (var s : list) {
                    if (shown++ >= 20) {
                        break;
                    }
                    sender.sendMessage(Component.text("  " + s.name() + (s.city() ? " [city]" : "")
                            + " @ " + s.x() + "," + s.z() + "  [" + s.code() + "]", NamedTextColor.GRAY));
                }
                return true;
            }
            case "face" -> {
                return handleFace(sender);
            }
            case "tp" -> {
                return handleTp(sender, args);
            }
            case "simulate" -> {
                return handleSimulate(sender, args);
            }
            case "mirrorpush" -> {
                return handleMirrorPush(sender, args);
            }
            case "nudgeanchors" -> {
                // Cities are anchored at their true lat/lon, but our coastline lands a
                // few blocks from where the raster says, so a coastal city can have a
                // third of its footprint in the sea -- the jigsaw then builds out over
                // water and the ground fixer plinths it, which is where the "cliffs"
                // come from. Search for a nearby centre that is mostly land, using the
                // generator's own target surface so no chunks need generating.
                int searchR = args.length > 1 ? Integer.parseInt(args[1]) : 48;
                int footprint = args.length > 2 ? Integer.parseInt(args[2]) : 48;
                // A column only counts as land if its target clears sea level by enough
                // to survive the residual noise; "above sea level" alone overstates land
                // badly (spec said 87% at Antioch where the world delivered 61%).
                int margin = args.length > 3 ? Integer.parseInt(args[3]) : 2;
                final double want = 0.92;
                final int step = 4;
                java.util.List<String> rows = new java.util.ArrayList<>();
                try {
                    for (com.ckemere.cubeworld.city.CityAnchors.CityAnchor c
                            : com.ckemere.cubeworld.city.CityAnchors.load()) {
                        int cx = c.x();
                        int cz = c.z();
                        String name = c.name();
                        double base = landFraction(cx, cz, footprint, step, margin);
                        int bx = cx;
                        int bz = cz;
                        double bf = base;
                        double bd = 0.0;
                        for (int dz = -searchR; dz <= searchR; dz += step) {
                            for (int dx = -searchR; dx <= searchR; dx += step) {
                                double f = landFraction(cx + dx, cz + dz, footprint, step, margin);
                                double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                                // nearest centre that is good enough; else the best one
                                boolean better = (bf < want)
                                        ? (f > bf + 1e-9 || (f >= want && d < bd))
                                        : (f >= want && d < bd);
                                if (better) {
                                    bf = f;
                                    bd = d;
                                    bx = cx + dx;
                                    bz = cz + dz;
                                }
                            }
                        }
                        rows.add(String.format(Locale.ROOT,
                                "%s,%d,%d,%d,%d,%.0f%%,%.0f%%,%.0fkm",
                                name, cx, cz, bx, bz, base * 100, bf * 100, bd * 0.98));
                    }
                } catch (Exception e) {
                    sender.sendMessage(Component.text("nudgeanchors failed: " + e, NamedTextColor.RED));
                    return true;
                }
                org.bukkit.Bukkit.getLogger().info(
                        "nudgeanchors name,oldX,oldZ,newX,newZ,landBefore,landAfter,moved");
                for (String r : rows) {
                    org.bukkit.Bukkit.getLogger().info("nudgeanchors " + r);
                }
                sender.sendMessage(Component.text(
                        "Evaluated " + rows.size() + " cities; see console.", NamedTextColor.AQUA));
                return true;
            }
            case "height" -> {
                if (args.length != 3) {
                    sender.sendMessage(Component.text("Usage: /cubeworld height <x> <z>", NamedTextColor.RED));
                    return true;
                }
                try {
                    double hx = Double.parseDouble(args[1]);
                    double hz = Double.parseDouble(args[2]);
                    org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
                    int actual = world.getHighestBlockYAt((int) Math.floor(hx), (int) Math.floor(hz));
                    sender.sendMessage(Component.text(String.format(Locale.ROOT,
                            "height at (%.1f, %.1f): spec %.2f, world %d, theme %s",
                            hx, hz, sampler().heightAt(hx, hz), actual, sampler().themeAt(hx, hz)),
                            NamedTextColor.AQUA));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Coordinates must be numbers.", NamedTextColor.RED));
                }
                return true;
            }
            case "blockat" -> {
                if (args.length != 4 && args.length != 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /cubeworld blockat <x> <y> <z> [world]", NamedTextColor.RED));
                    return true;
                }
                try {
                    int bx = Integer.parseInt(args[1]);
                    int by = Integer.parseInt(args[2]);
                    int bz = Integer.parseInt(args[3]);
                    org.bukkit.World world = args.length == 5
                            ? org.bukkit.Bukkit.getWorld(args[4])
                            : org.bukkit.Bukkit.getWorlds().get(0);
                    if (world == null) {
                        sender.sendMessage(Component.text("Unknown world: " + args[4], NamedTextColor.RED));
                        return true;
                    }
                    sender.sendMessage(Component.text(String.format(Locale.ROOT, "%s(%d,%d,%d): %s",
                            world.getName(), bx, by, bz,
                            world.getBlockAt(bx, by, bz).getBlockData().getAsString()),
                            NamedTextColor.AQUA));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Coordinates must be integers.", NamedTextColor.RED));
                }
                return true;
            }
            case "climateat" -> {
                if (args.length != 3 && args.length != 4) {
                    sender.sendMessage(Component.text("Usage: /cubeworld climateat <x> <z> [y]", NamedTextColor.RED));
                    return true;
                }
                int bx = Integer.parseInt(args[1]);
                int bz = Integer.parseInt(args[2]);
                com.ckemere.cubeworld.generation.EarthData earth = maps.earthData();
                if (earth == null) {
                    sender.sendMessage(Component.text("No Earth data loaded.", NamedTextColor.YELLOW));
                    return true;
                }
                int by = args.length == 4 ? Integer.parseInt(args[3])
                        : (int) Math.round(sampler().heightAt(bx + 0.5, bz + 0.5));
                double[] c = com.ckemere.cubeworld.generation.EarthClimate.params(
                        earth, sampler(), bx + 0.5, bz + 0.5, by,
                        org.bukkit.Bukkit.getWorlds().get(0).getSeed());
                if (c == null) {
                    sender.sendMessage(Component.text("off net", NamedTextColor.YELLOW));
                    return true;
                }
                sender.sendMessage(Component.text(String.format(Locale.ROOT,
                        "T=%.2f H=%.2f C=%.2f E=%.2f D=%.2f W=%.2f | elev=%.0f temp=%.1f precip=%.0f",
                        c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]), NamedTextColor.AQUA));
                return true;
            }
            case "riverscan" -> {
                int cx = Integer.parseInt(args[1]);
                int cz = Integer.parseInt(args[2]);
                int side = args.length > 3 ? Integer.parseInt(args[3]) : 64;
                int stride = args.length > 4 ? Integer.parseInt(args[4]) : 2;
                com.ckemere.cubeworld.generation.EarthData ed = maps.earthData();
                if (ed == null) {
                    sender.sendMessage(Component.text("no Earth data", NamedTextColor.RED));
                    return true;
                }
                int hits = 0;
                double best = 0;
                int bx = cx;
                int bz = cz;
                int half = side / 2;
                for (int a = -half; a < half; a++) {
                    for (int b = -half; b < half; b++) {
                        int x = cx + a * stride;
                        int z = cz + b * stride;
                        double rs = com.ckemere.cubeworld.generation.EarthClimate
                                .riverStrength(ed, sampler(), x + 0.5, z + 0.5);
                        if (rs > best) {
                            best = rs;
                            bx = x;
                            bz = z;
                        }
                        if (rs > 0.5) {
                            hits++;
                        }
                    }
                }
                sender.sendMessage(Component.text(String.format(Locale.ROOT,
                        "riverscan (%d,%d) %dx%d stride %d: max strength %.3f at (%d,%d); "
                        + "%d columns > 0.5", cx, cz, side, side, stride, best, bx, bz, hits),
                        NamedTextColor.AQUA));
                return true;
            }
            case "biomecoverage" -> {
                String out = com.ckemere.cubeworld.generation.TerrainEval.biomeCoverage(
                        org.bukkit.Bukkit.getWorlds().get(0), maps.earthData(), sampler(),
                        org.bukkit.Bukkit.getWorlds().get(0).getSeed(),
                        args.length > 1 ? Integer.parseInt(args[1]) : 160,
                        args.length > 2 ? Integer.parseInt(args[2]) : 340);
                for (String line : out.split("\n")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
                }
                return true;
            }
            case "riverat" -> {
                int bx = Integer.parseInt(args[1]);
                int bz = Integer.parseInt(args[2]);
                com.ckemere.cubeworld.generation.EarthData ed = maps.earthData();
                if (ed == null) {
                    sender.sendMessage(Component.text("no Earth data", NamedTextColor.RED));
                    return true;
                }
                double rs = com.ckemere.cubeworld.generation.EarthClimate.riverStrength(
                        ed, sampler(), bx + 0.5, bz + 0.5);
                double ry = com.ckemere.cubeworld.generation.EarthClimate.riverWaterY(
                        ed, sampler(), bx + 0.5, bz + 0.5);
                double h = sampler().heightAt(bx + 0.5, bz + 0.5);
                sender.sendMessage(Component.text(String.format(Locale.ROOT,
                        "river at (%d,%d): strength=%.3f  river_y=%s m  surface y=%.2f",
                        bx, bz, rs,
                        Double.isNaN(ry) ? "NaN" : String.format(Locale.ROOT, "%.0f", ry), h),
                        NamedTextColor.AQUA));
                return true;
            }
            case "biomecolumn" -> {
                String out = com.ckemere.cubeworld.generation.TerrainEval.biomeColumn(
                        org.bukkit.Bukkit.getWorlds().get(0), sampler(),
                        Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                        args.length > 3 ? Integer.parseInt(args[3]) : 4);
                for (String line : out.split("\n")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
                }
                return true;
            }
            case "undergroundcensus" -> {
                String out = com.ckemere.cubeworld.generation.TerrainEval.undergroundCensus(
                        org.bukkit.Bukkit.getWorlds().get(0), maps.earthData(), sampler(),
                        Integer.parseInt(args[1]),
                        args.length > 2 ? Integer.parseInt(args[2]) : 200,
                        args.length > 3 ? Integer.parseInt(args[3]) : 300);
                for (String line : out.split("\n")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
                }
                return true;
            }
            case "biomehist" -> {
                String out = com.ckemere.cubeworld.generation.TerrainEval.biomeHist(
                        org.bukkit.Bukkit.getWorlds().get(0),
                        Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                        args.length > 3 ? Integer.parseInt(args[3]) : 16,
                        args.length > 4 ? Integer.parseInt(args[4]) : 8);
                for (String line : out.split("\n")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
                }
                return true;
            }
            case "surfacedump" -> {
                String out = com.ckemere.cubeworld.generation.TerrainEval.surfaceDump(
                        org.bukkit.Bukkit.getWorlds().get(0), sampler(),
                        Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                        Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                for (String line : out.split("\n")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.WHITE));
                }
                return true;
            }
            case "drownprobe" -> {
                String out = com.ckemere.cubeworld.generation.TerrainEval.drownProbe(
                        org.bukkit.Bukkit.getWorlds().get(0), maps.earthData(), sampler(),
                        Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                        args.length > 3 ? Integer.parseInt(args[3]) : 12,
                        args.length > 4 ? Integer.parseInt(args[4]) : 24,
                        args.length > 5 ? Integer.parseInt(args[5]) : 12);
                for (String line : out.split("\n")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
                }
                return true;
            }
            case "evaluate" -> {
                boolean withTerrain = !(args.length > 1 && args[1].equalsIgnoreCase("fast"));
                int side = args.length > 2 ? Integer.parseInt(args[2]) : 12;
                int stride = args.length > 3 ? Integer.parseInt(args[3]) : 24;
                String out = com.ckemere.cubeworld.generation.TerrainEval.run(
                        org.bukkit.Bukkit.getWorlds().get(0), maps.earthData(), sampler(),
                        side, stride, withTerrain);
                for (String line : out.split("\n")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
                }
                return true;
            }
            case "axisstats" -> {
                String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "vanilla";
                int side = args.length > 2 ? Integer.parseInt(args[2]) : 400;
                int stride = args.length > 3 ? Integer.parseInt(args[3]) : 64;
                String out;
                if (mode.startsWith("v")) {
                    out = com.ckemere.cubeworld.generation.AxisStats.vanilla(
                            org.bukkit.Bukkit.getWorlds().get(0), side, stride);
                } else {
                    out = com.ckemere.cubeworld.generation.AxisStats.earth(
                            maps.earthData(), sampler(),
                            org.bukkit.Bukkit.getWorlds().get(0).getSeed(), side, stride);
                }
                for (String line : out.split("\n")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
                }
                return true;
            }
            case "routerprobe" -> {
                org.bukkit.World w = args.length > 1
                        ? org.bukkit.Bukkit.getWorld(args[1])
                        : (sender instanceof org.bukkit.entity.Player pl
                                ? pl.getWorld() : org.bukkit.Bukkit.getWorlds().get(0));
                if (w == null) {
                    sender.sendMessage(Component.text("no such world", NamedTextColor.RED));
                    return true;
                }
                for (String line : com.ckemere.cubeworld.generation.RouterProbe.report(w).split("\n")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
                }
                return true;
            }
            case "terrainprobe" -> {
                if (args.length != 3) {
                    sender.sendMessage(Component.text("Usage: /cubeworld terrainprobe <x> <z>",
                            NamedTextColor.RED));
                    return true;
                }
                var sd = com.ckemere.cubeworld.generation.SphereDensity.LAST;
                if (sd == null) {
                    sender.sendMessage(Component.text("no SphereDensity (hook not installed)",
                            NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage(Component.text(sd.probeTerms(
                        Integer.parseInt(args[1]) + 0.5, Integer.parseInt(args[2]) + 0.5),
                        NamedTextColor.AQUA));
                return true;
            }
            case "shapeprobe" -> {
                // Calibration/reconnaissance for driving vanilla's FACTOR and
                // JAGGEDNESS from Earth relief. NoiseRouter exposes continents/
                // erosion/ridges but NOT offset/factor/jaggedness (those live inside
                // finalDensity), so before writing a replacement we need to know
                // (a) what the tree looks like, (b) whether the router's exposed
                // leaves are identity-shared with the nodes inside the tree, and
                // (c) what value ranges the splines actually produce.
                org.bukkit.World w = org.bukkit.Bukkit.getWorlds().get(0);
                var level = ((org.bukkit.craftbukkit.CraftWorld) w).getHandle();
                var rs = level.getChunkSource().randomState();
                var router = rs.router();
                java.util.Map<String, Integer> hist = new java.util.TreeMap<>();
                java.util.List<net.minecraft.world.level.levelgen.DensityFunction> all =
                        new java.util.ArrayList<>();
                net.minecraft.world.level.levelgen.DensityFunction.Visitor probe = node -> {
                    hist.merge(node.getClass().getSimpleName(), 1, Integer::sum);
                    all.add(node);
                    return node;
                };
                router.finalDensity().mapAll(probe);
                sender.sendMessage(Component.text("finalDensity tree: " + all.size()
                        + " nodes", NamedTextColor.AQUA));
                StringBuilder sb = new StringBuilder();
                hist.forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
                sender.sendMessage(Component.text(sb.toString(), NamedTextColor.GRAY));
                // identity sharing with the router's exposed leaves?
                record Probe(String name, net.minecraft.world.level.levelgen.DensityFunction f) { }
                for (Probe p : new Probe[] {
                        new Probe("continents", router.continents()),
                        new Probe("erosion", router.erosion()),
                        new Probe("ridges", router.ridges()),
                        new Probe("depth", router.depth())}) {
                    long n = all.stream().filter(x -> x == p.f()).count();
                    sender.sendMessage(Component.text(String.format(Locale.ROOT,
                            "  %-11s identity-shared occurrences in finalDensity: %d   "
                            + "[%.3f .. %.3f]", p.name(), n, p.f().minValue(), p.f().maxValue()),
                            n > 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
                }
                // spline nodes = the offset/factor/jaggedness family
                int si = 0;
                for (var n : all) {
                    if (n instanceof net.minecraft.world.level.levelgen.DensityFunctions.Spline sp) {
                        sender.sendMessage(Component.text(String.format(Locale.ROOT,
                                "  spline[%d] range [%.3f .. %.3f]", si++,
                                sp.minValue(), sp.maxValue()), NamedTextColor.YELLOW));
                    }
                }
                return true;
            }
            case "findlatlon" -> {
                if (args.length != 3) {
                    sender.sendMessage(Component.text("Usage: /cubeworld findlatlon <lat> <lon>",
                            NamedTextColor.RED));
                    return true;
                }
                com.ckemere.cubeworld.generation.EarthData earth = maps.earthData();
                if (earth == null) {
                    sender.sendMessage(Component.text("No Earth data loaded.", NamedTextColor.YELLOW));
                    return true;
                }
                double tLat = Double.parseDouble(args[1]);
                double tLon = Double.parseDouble(args[2]);
                // Coarse scan every face, then refine: the forward map
                // (world -> cube point -> lon/lat) is the only one we have, so
                // invert it numerically. Cheap and exact enough (<1 block).
                double bx = 0;
                double bz = 0;
                double best = Double.MAX_VALUE;
                for (CubeFace f : CubeFace.values()) {
                    double x0 = geometry.faceMinX(f);
                    double z0 = geometry.faceMinZ(f);
                    for (int i = 0; i <= 64; i++) {
                        for (int j = 0; j <= 64; j++) {
                            double x = x0 + i * (geometry.faceSize() / 64.0);
                            double z = z0 + j * (geometry.faceSize() / 64.0);
                            double d = latLonErr(earth, x, z, tLat, tLon);
                            if (d < best) {
                                best = d;
                                bx = x;
                                bz = z;
                            }
                        }
                    }
                }
                for (double step = geometry.faceSize() / 64.0; step > 0.4; step /= 2.0) {
                    for (int i = -2; i <= 2; i++) {
                        for (int j = -2; j <= 2; j++) {
                            double x = bx + i * step;
                            double z = bz + j * step;
                            double d = latLonErr(earth, x, z, tLat, tLon);
                            if (d < best) {
                                best = d;
                                bx = x;
                                bz = z;
                            }
                        }
                    }
                }
                int rx = (int) Math.round(bx);
                int rz = (int) Math.round(bz);
                CubeFace f = geometry.faceAt(rx, rz);
                sender.sendMessage(Component.text(String.format(Locale.ROOT,
                        "lat %.4f lon %.4f -> world (%d, %d) on %s  [err %.3f deg]",
                        tLat, tLon, rx, rz, f == null ? "margin" : f.displayName(), best),
                        NamedTextColor.AQUA));
                return true;
            }
            case "oreprobe" -> {
                double px;
                double pz;
                if (args.length == 3) {
                    px = Double.parseDouble(args[1]);
                    pz = Double.parseDouble(args[2]);
                } else if (sender instanceof Player pl) {
                    px = pl.getLocation().getX();
                    pz = pl.getLocation().getZ();
                } else {
                    sender.sendMessage(Component.text("Usage: /cubeworld oreprobe [<x> <z>]",
                            NamedTextColor.RED));
                    return true;
                }
                if (oreEnrichment == null) {
                    sender.sendMessage(Component.text("Ore enrichment is not active.",
                            NamedTextColor.YELLOW));
                    return true;
                }
                // Report only local *strength*, never the province centre: this is the
                // research tool -- sample several spots to triangulate a deposit.
                java.util.List<String> hits = new java.util.ArrayList<>();
                for (com.ckemere.cubeworld.generation.OreDeposits.Ore ore
                        : com.ckemere.cubeworld.generation.OreDeposits.Ore.values()) {
                    double s = oreEnrichment.strengthAt(ore, px, pz);
                    if (s > 0) {
                        String band = s > 0.66 ? "strong" : s > 0.33 ? "moderate" : "faint";
                        hits.add(String.format(Locale.ROOT, "%s: %s (%.0f%%)",
                                ore.name().toLowerCase(Locale.ROOT), band, s * 100));
                    }
                }
                if (hits.isEmpty()) {
                    sender.sendMessage(Component.text(
                            "No ore enrichment detected here. Keep prospecting.",
                            NamedTextColor.GRAY));
                } else {
                    hits.sort(java.util.Collections.reverseOrder());
                    sender.sendMessage(Component.text("Ore survey: "
                            + String.join(", ", hits), NamedTextColor.GOLD));
                }
                return true;
            }
            case "biomeat" -> {
                if (args.length != 3 && args.length != 4) {
                    sender.sendMessage(Component.text("Usage: /cubeworld biomeat <x> <z> [y]", NamedTextColor.RED));
                    return true;
                }
                try {
                    int bx = Integer.parseInt(args[1]);
                    int bz = Integer.parseInt(args[2]);
                    org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
                    world.getChunkAt(bx >> 4, bz >> 4).load(true);
                    int by = args.length == 4 ? Integer.parseInt(args[3])
                            : (int) Math.round(sampler().heightAt(bx + 0.5, bz + 0.5));
                    org.bukkit.block.Biome b = world.getBiome(bx, by, bz);
                    sender.sendMessage(Component.text(String.format(Locale.ROOT,
                            "biome at (%d,%d,%d): %s", bx, by, bz, b.getKey()), NamedTextColor.AQUA));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Coordinates must be integers.", NamedTextColor.RED));
                }
                return true;
            }
            case "villagedebug" -> {
                // Ground truth for tools/playermap/structures/village_placement.py:
                // replay vanilla's village decision for one chunk with the real
                // engine objects. Reads only getBaseHeight + the biome source, so
                // it loads no chunks. Op-only (not in PUBLIC_SUBCOMMANDS).
                if (args.length != 3) {
                    sender.sendMessage(Component.text(
                            "Usage: /cubeworld villagedebug <chunkX> <chunkZ>", NamedTextColor.RED));
                    return true;
                }
                try {
                    int qcx = Integer.parseInt(args[1]);
                    int qcz = Integer.parseInt(args[2]);
                    for (String line : com.ckemere.cubeworld.seam.nms.VillagePlacementProbe.probe(
                            org.bukkit.Bukkit.getWorlds().get(0), qcx, qcz)) {
                        sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Chunk coordinates must be integers.",
                            NamedTextColor.RED));
                } catch (RuntimeException e) {
                    sender.sendMessage(Component.text("villagedebug failed: " + e,
                            NamedTextColor.RED));
                }
                return true;
            }
            case "scan" -> {
                if (args.length != 8 && args.length != 9) {
                    sender.sendMessage(Component.text(
                            "Usage: /cubeworld scan <x1> <y1> <z1> <x2> <y2> <z2> <material> [world]",
                            NamedTextColor.RED));
                    return true;
                }
                int[] c = new int[6];
                for (int i = 0; i < 6; i++) {
                    c[i] = Integer.parseInt(args[i + 1]);
                }
                org.bukkit.Material want = org.bukkit.Material.matchMaterial(args[7]);
                if (want == null) {
                    sender.sendMessage(Component.text("Unknown material: " + args[7], NamedTextColor.RED));
                    return true;
                }
                org.bukkit.World sworld = args.length == 9
                        ? org.bukkit.Bukkit.getWorld(args[8])
                        : org.bukkit.Bukkit.getWorlds().get(0);
                if (sworld == null) {
                    sender.sendMessage(Component.text("Unknown world: " + args[8], NamedTextColor.RED));
                    return true;
                }
                int count = 0;
                StringBuilder first = new StringBuilder();
                for (int x = Math.min(c[0], c[3]); x <= Math.max(c[0], c[3]); x++) {
                    for (int y = Math.min(c[1], c[4]); y <= Math.max(c[1], c[4]); y++) {
                        for (int z = Math.min(c[2], c[5]); z <= Math.max(c[2], c[5]); z++) {
                            if (sworld.getBlockAt(x, y, z).getType() == want) {
                                if (count < 5) {
                                    first.append(String.format(Locale.ROOT, " (%d,%d,%d)", x, y, z));
                                }
                                count++;
                            }
                        }
                    }
                }
                sender.sendMessage(Component.text(String.format(Locale.ROOT,
                        "scan %s in %s: %d match(es)%s", want, sworld.getName(), count, first),
                        NamedTextColor.AQUA));
                return true;
            }
            case "fluidprobe" -> {
                if (args.length != 4 && args.length != 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /cubeworld fluidprobe <x> <y> <z> [world]", NamedTextColor.RED));
                    return true;
                }
                int fx = Integer.parseInt(args[1]);
                int fy = Integer.parseInt(args[2]);
                int fz = Integer.parseInt(args[3]);
                org.bukkit.World bworld = args.length == 5
                        ? org.bukkit.Bukkit.getWorld(args[4])
                        : org.bukkit.Bukkit.getWorlds().get(0);
                net.minecraft.server.level.ServerLevel level =
                        ((org.bukkit.craftbukkit.CraftWorld) bworld).getHandle();
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(fx, fy, fz);
                long before = level.getFluidTicks().count();
                boolean willTick = level.getFluidTicks().willTickThisTick(pos,
                        net.minecraft.world.level.material.Fluids.WATER);
                boolean pending = level.getFluidTicks().hasScheduledTick(pos,
                        net.minecraft.world.level.material.Fluids.WATER);
                level.scheduleTick(pos, net.minecraft.world.level.material.Fluids.WATER, 1);
                long after = level.getFluidTicks().count();
                sender.sendMessage(Component.text(String.format(Locale.ROOT,
                        "fluidticks count %d -> %d (pending@pos=%s willTick=%s) gametime=%d",
                        before, after, pending, willTick, level.getGameTime()),
                        NamedTextColor.AQUA));
                return true;
            }
            case "respawn" -> {
                if (args.length != 2) {
                    sender.sendMessage(Component.text("Usage: /cubeworld respawn <player>", NamedTextColor.RED));
                    return true;
                }
                Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
                } else if (!target.isDead()) {
                    sender.sendMessage(Component.text(args[1] + " is not dead.", NamedTextColor.YELLOW));
                } else {
                    target.spigot().respawn();
                    sender.sendMessage(Component.text("Respawned " + args[1] + ".", NamedTextColor.AQUA));
                }
                return true;
            }
            case "biomecensus" -> {
                return handleBiomeCensus(sender, args);
            }
            case "biomeraster" -> {
                Integer atY = null;
                if (args.length >= 3) {
                    try {
                        atY = Integer.valueOf(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text(
                                "Usage: /cubeworld biomeraster <overworld|nether> [y]",
                                NamedTextColor.RED));
                        return true;
                    }
                }
                return handleBiomeRaster(sender, args.length >= 2 ? args[1] : "overworld", atY);
            }
            case "dumpbiomeparams" -> {
                return handleDumpBiomeParams(sender);
            }
            case "strongholds" -> {
                return handleStrongholds(sender);
            }
            case "refreshmap" -> {
                return handleRefreshMap(sender);
            }
            case "map" -> {
                return handleMap(sender, args);
            }
            case "mapprecision" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    var cur = com.ckemere.cubeworld.map.MapPrivacy.precision(p);
                    sender.sendMessage(Component.text(
                            "Your web-map precision is " + cur.name().toLowerCase(Locale.ROOT)
                                    + (cur.cell > 0 ? " (within ~" + cur.cell + " blocks)" : " (exact)")
                                    + ". Usage: /cubeworld mapprecision <high|medium|low>",
                            NamedTextColor.AQUA));
                    return true;
                }
                com.ckemere.cubeworld.map.MapPrivacy.Precision prec;
                try {
                    prec = com.ckemere.cubeworld.map.MapPrivacy.Precision
                            .valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(Component.text(
                            "Usage: /cubeworld mapprecision <high|medium|low>", NamedTextColor.RED));
                    return true;
                }
                com.ckemere.cubeworld.map.MapPrivacy.setPrecision(p, prec);
                sender.sendMessage(Component.text(switch (prec) {
                    case HIGH -> "Web map shows your exact position.";
                    case MEDIUM -> "Web map shows your position within ~128 blocks.";
                    case LOW -> "Web map shows your position within ~512 blocks.";
                }, NamedTextColor.GREEN));
                sender.sendMessage(Component.text(
                        "Sneaking, invisibility, or wearing a mob head / carved pumpkin"
                                + " hides you from the map entirely.",
                        NamedTextColor.GRAY));
                return true;
            }
            case "mapplayers" -> {
                // Machine-readable feed for the map website (tools/playermap):
                // hide mechanics applied, positions quantized per player setting.
                StringBuilder json = new StringBuilder("[");
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    org.bukkit.World w = p.getWorld();
                    if (w.getEnvironment() != org.bukkit.World.Environment.NORMAL
                            || !(w.getGenerator()
                                    instanceof com.ckemere.cubeworld.generation.CubeWorldChunkGenerator)) {
                        continue;               // only the cube overworld is on the map
                    }
                    if (com.ckemere.cubeworld.map.MapPrivacy.isHiddenFromMap(p)) {
                        continue;
                    }
                    var prec = com.ckemere.cubeworld.map.MapPrivacy.precision(p);
                    var loc = p.getLocation();
                    int x = loc.getBlockX();
                    int z = loc.getBlockZ();
                    if (json.length() > 1) {
                        json.append(',');
                    }
                    json.append("{\"name\":\"")
                            .append(p.getName().replace("\\", "").replace("\"", ""))
                            .append('"');
                    if (prec.cell > 0) {
                        json.append(",\"x\":")
                                .append(com.ckemere.cubeworld.map.MapPrivacy.quantize(x, prec.cell))
                                .append(",\"z\":")
                                .append(com.ckemere.cubeworld.map.MapPrivacy.quantize(z, prec.cell))
                                .append(",\"r\":").append(prec.cell / 2);
                    } else {
                        json.append(",\"x\":").append(x)
                                .append(",\"y\":").append(loc.getBlockY())
                                .append(",\"z\":").append(z)
                                .append(",\"r\":0");
                    }
                    json.append('}');
                }
                json.append(']');
                sender.sendMessage(Component.text(json.toString()));
                return true;
            }
            case "genprof" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
                    com.ckemere.cubeworld.generation.GenProfiler.reset();
                    sender.sendMessage(Component.text("genprof reset.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text(
                            com.ckemere.cubeworld.generation.GenProfiler.dump(), NamedTextColor.AQUA));
                }
                return true;
            }
            case "marginbreak" -> {
                return handleMarginEdit(sender, args, null);
            }
            case "marginplace" -> {
                if (args.length != 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /cubeworld marginplace <x> <y> <z> <material>", NamedTextColor.RED));
                    return true;
                }
                return handleMarginEdit(sender, args, args[4]);
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Tally the surface biome over the whole planet (all six faces) on a grid,
     * scaling each sample to step*step blocks. Runs on the calling thread (the
     * biome function is the same one chunk gen uses concurrently), so a coarse
     * step keeps the pause short. Usage: /cubeworld biomecensus [step].
     */
    private boolean handleBiomeCensus(CommandSender sender, String[] args) {
        int step = 64;
        if (args.length >= 2) {
            try {
                step = Math.max(16, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("step must be an integer (>=16).", NamedTextColor.RED));
                return true;
            }
        }
        org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
        if (!(world.getGenerator() instanceof
                com.ckemere.cubeworld.generation.CubeWorldChunkGenerator gen)) {
            sender.sendMessage(Component.text("Overworld is not a cube world.", NamedTextColor.RED));
            return true;
        }
        com.ckemere.cubeworld.generation.CubeWorldBiomeProvider bp = gen.biomeProvider();
        java.util.Map<org.bukkit.block.Biome, Long> counts = new java.util.HashMap<>();
        long samples = 0;
        int size = geometry.faceSize();
        long t0 = System.currentTimeMillis();
        for (CubeFace face : CubeFace.values()) {
            int minX = geometry.faceMinX(face);
            int minZ = geometry.faceMinZ(face);
            for (int x = minX + step / 2; x < minX + size; x += step) {
                for (int z = minZ + step / 2; z < minZ + size; z += step) {
                    counts.merge(bp.surfaceBiome(world, x, z), 1L, Long::sum);
                    samples++;
                }
            }
        }
        long blocksPerSample = (long) step * step;
        long ms = System.currentTimeMillis() - t0;
        java.util.List<java.util.Map.Entry<org.bukkit.block.Biome, Long>> sorted =
                new java.util.ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        sender.sendMessage(Component.text(String.format(Locale.ROOT,
                "Biome census  (step %d, %,d samples, %d biomes, %dms)  1 block ~ 0.95 km2:",
                step, samples, counts.size(), ms), NamedTextColor.GOLD));
        sender.sendMessage(Component.text(String.format(Locale.ROOT,
                "%-26s %16s  %7s", "biome", "blocks", "share"), NamedTextColor.GRAY));
        for (java.util.Map.Entry<org.bukkit.block.Biome, Long> e : sorted) {
            sender.sendMessage(Component.text(String.format(Locale.ROOT,
                    "%-26s %,16d  %6.2f%%",
                    e.getKey().getKey().getKey(),
                    e.getValue() * blocksPerSample,
                    100.0 * e.getValue() / samples), NamedTextColor.AQUA));
        }
        sender.sendMessage(Component.text(String.format(Locale.ROOT,
                "TOTAL surface: %,d blocks  (6 faces x %d^2)",
                samples * blocksPerSample, size), NamedTextColor.GOLD));
        return true;
    }

    /**
     * Export a per-chunk surface-biome raster for all six faces to
     * {@code plugins/CubeWorld/biomes/overworld.cwbr}, so the map tool can
     * biome-filter computed structure positions offline. Uses the exact biome
     * provider chunk gen uses (ground truth) and is seed-independent, so it's
     * computed once. Big-endian CWBR format: magic, version, faceSize, chunks,
     * palette (writeUTF keys), then per face: name, minX, minZ, chunks^2 shorts
     * (palette index), row-major with the x-index outer and z-index inner.
     */
    /**
     * Rebuild every artefact the web map reads out of the plugin, in one go.
     *
     * <p>These used to be three separate commands that had to be remembered in
     * the right combination after any generator change. They were not: the
     * depth raster sat a build behind and the biome page four hours behind,
     * with nothing anywhere saying so. One command, and
     * {@code CubeWorldPlugin} warns at startup when the files are older than
     * the plugin itself.
     */
    /**
     * Cube-aware map items: a seam-flattening {@link com.ckemere.cubeworld.map.CubeMapRenderer}.
     *
     * <p>{@code map dump <cx> <cz> <blocksPerPixel>} renders offline to a PNG so
     * the result can be eyeballed without a client. {@code map axumchest} builds a
     * few demo maps (a local Axum view and two that straddle face seams) and drops
     * a chest of them beside the Axum teleporter.
     */
    private boolean handleMap(CommandSender sender, String[] args) {
        org.bukkit.World world = null;
        for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
            if (w.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                world = w;
                break;
            }
        }
        if (world == null) {
            sender.sendMessage(Component.text("No overworld.", NamedTextColor.RED));
            return true;
        }
        com.ckemere.cubeworld.geometry.CubeTopology topo =
                new com.ckemere.cubeworld.geometry.CubeTopology(geometry);
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";

        if (sub.equals("dump") && args.length >= 5) {
            int cx = Integer.parseInt(args[2]);
            int cz = Integer.parseInt(args[3]);
            int bpp = Integer.parseInt(args[4]);
            java.util.Set<Long> chunks =
                    com.ckemere.cubeworld.map.CubeMapImage.sourceChunks(geometry, topo, cx, cz, bpp);
            if (chunks.size() > MAX_MAP_DUMP_CHUNKS) {
                sender.sendMessage(Component.text("Refusing: " + chunks.size()
                        + " chunks would need loading (cap " + MAX_MAP_DUMP_CHUNKS
                        + "). Use a smaller blocksPerPixel.", NamedTextColor.RED));
                return true;
            }
            setChunksLoaded(world, chunks, true);
            var r = com.ckemere.cubeworld.map.CubeMapImage.render(world, geometry, topo, cx, cz, bpp);
            setChunksLoaded(world, chunks, false);
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(r.size(), r.size(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int py = 0; py < r.size(); py++) {
                for (int px = 0; px < r.size(); px++) {
                    img.setRGB(px, py, r.argb()[py * r.size() + px]);
                }
            }
            java.nio.file.Path out = org.bukkit.Bukkit.getPluginManager().getPlugin("CubeWorld")
                    .getDataFolder().toPath().resolve("mapdump_" + cx + "_" + cz + "_" + bpp + ".png");
            try {
                javax.imageio.ImageIO.write(img, "png", out.toFile());
            } catch (java.io.IOException e) {
                sender.sendMessage(Component.text("PNG write failed: " + e, NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("map dump -> " + out, NamedTextColor.AQUA));
            return true;
        }

        if (sub.equals("axumchest")) {
            int ax = 11974, az = -1360;                       // Axum teleporter (EQ_EAST)
            // (label, centreX, centreZ, blocksPerPixel). The EQ_EAST face spans
            // x 5120..15360, z -5120..5120; the last two straddle its east and
            // north edges to exercise the seam fold.
            Object[][] specs = {
                {"Axum (local)", ax, az, 4},
                {"East seam (EQ_EAST|EQ_BACK)", 15360, az, 4},
                {"North seam (EQ_EAST|NORTH_POLE)", ax, -5120, 4},
            };
            java.util.List<org.bukkit.inventory.ItemStack> items = new java.util.ArrayList<>();
            for (Object[] s : specs) {
                String label = (String) s[0];
                int cx = (int) s[1], cz = (int) s[2], bpp = (int) s[3];
                // Warm the render cache now, while we can bound-load the footprint,
                // so the map has content the first time it is opened.
                java.util.Set<Long> chunks =
                        com.ckemere.cubeworld.map.CubeMapImage.sourceChunks(geometry, topo, cx, cz, bpp);
                setChunksLoaded(world, chunks, true);
                org.bukkit.map.MapView view = org.bukkit.Bukkit.createMap(world);
                for (org.bukkit.map.MapRenderer old : new java.util.ArrayList<>(view.getRenderers())) {
                    view.removeRenderer(old);
                }
                view.setCenterX(cx);
                view.setCenterZ(cz);
                view.setScale(scaleForBpp(bpp));
                view.setTrackingPosition(true);
                view.setUnlimitedTracking(true);              // keep drawing our pixels off-center
                com.ckemere.cubeworld.map.CubeMapRenderer rend =
                        new com.ckemere.cubeworld.map.CubeMapRenderer(geometry, topo, teleport, cx, cz, bpp);
                rend.prime(world);                            // fill cache while footprint is loaded
                view.addRenderer(rend);
                setChunksLoaded(world, chunks, false);
                org.bukkit.inventory.ItemStack item =
                        new org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP);
                org.bukkit.inventory.meta.MapMeta mm =
                        (org.bukkit.inventory.meta.MapMeta) item.getItemMeta();
                mm.setMapView(view);
                mm.displayName(Component.text(label, NamedTextColor.AQUA));
                item.setItemMeta(mm);
                items.add(item);
            }
            // Chest on the surface a couple of blocks from the teleporter.
            forceloadBox(world, ax - 16, az - 16, ax + 16, az + 16, true);
            int chx = ax + 2, chz = az;
            int chy = world.getHighestBlockYAt(chx, chz) + 1;
            org.bukkit.block.Block b = world.getBlockAt(chx, chy, chz);
            b.setType(org.bukkit.Material.CHEST, false);
            // On a placed TileState, getInventory() is the LIVE inventory and
            // writes through immediately -- no update() (which, forced, re-places
            // the block and wipes the tile entity). getBlockInventory() is the
            // snapshot half and does NOT persist; that was the empty-chest bug.
            int added = 0;
            if (b.getState() instanceof org.bukkit.block.Container c) {
                for (org.bukkit.inventory.ItemStack it : items) {
                    c.getInventory().addItem(it);
                    added++;
                }
            }
            forceloadBox(world, ax - 16, az - 16, ax + 16, az + 16, false);
            sender.sendMessage(Component.text(
                    "map axumchest: " + added + " maps -> chest at "
                            + chx + "," + chy + "," + chz, NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text(
                "Usage: /cubeworld map dump <cx> <cz> <blocksPerPixel> | map axumchest",
                NamedTextColor.RED));
        return true;
    }

    private static org.bukkit.map.MapView.Scale scaleForBpp(int bpp) {
        return switch (bpp) {
            case 1 -> org.bukkit.map.MapView.Scale.CLOSEST;
            case 2 -> org.bukkit.map.MapView.Scale.CLOSE;
            case 4 -> org.bukkit.map.MapView.Scale.NORMAL;
            case 8 -> org.bukkit.map.MapView.Scale.FAR;
            default -> org.bukkit.map.MapView.Scale.FARTHEST;
        };
    }

    private static void forceloadBox(org.bukkit.World w, int x1, int z1, int x2, int z2, boolean on) {
        for (int cx = x1 >> 4; cx <= (x2 >> 4); cx++) {
            for (int cz = z1 >> 4; cz <= (z2 >> 4); cz++) {
                w.setChunkForceLoaded(cx, cz, on);
            }
        }
    }

    /** Cap on chunks a single map render may force-load on this 2.6 GB-heap box.
     * A blocksPerPixel of 4 is ~1024 chunks; 8+ blows past this and OOMs. */
    private static final int MAX_MAP_DUMP_CHUNKS = 1400;

    private static void setChunksLoaded(org.bukkit.World w, java.util.Set<Long> chunks, boolean on) {
        for (long key : chunks) {
            w.setChunkForceLoaded((int) (key >> 32), (int) key, on);
        }
    }

    private boolean handleRefreshMap(CommandSender sender) {
        long t0 = System.currentTimeMillis();
        handleBiomeRaster(sender, "overworld", null);
        for (int y : MAP_DEPTH_RASTERS) {
            handleBiomeRaster(sender, "overworld", y);
        }
        handleStrongholds(sender);
        sender.sendMessage(Component.text(
                "refreshmap: done in " + (System.currentTimeMillis() - t0) + " ms",
                NamedTextColor.GREEN));
        return true;
    }

    /** Depths the web map needs a biome raster at. -27 is ancient_city's
     * start_height.absolute; structures that start underground are biome-tested
     * there, not at the surface. */
    public static final int[] MAP_DEPTH_RASTERS = {-27};

    /**
     * Dump the world's stronghold (= end portal) positions for the web map.
     *
     * <p>Every stronghold holds exactly one end portal, so this is the "where
     * are the end portals" layer. Written from
     * {@link com.ckemere.cubeworld.seam.nms.StrongholdSphereHook#currentPositions}
     * -- the same list eye-of-ender targeting uses -- so the map cannot drift
     * from the world the way a hand-maintained coordinate file can.
     */
    private boolean handleStrongholds(CommandSender sender) {
        org.bukkit.World world = null;
        for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
            if (w.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                world = w;
                break;
            }
        }
        if (world == null) {
            sender.sendMessage(Component.text("No overworld.", NamedTextColor.RED));
            return true;
        }
        var positions = com.ckemere.cubeworld.seam.nms.StrongholdSphereHook.currentPositions(world);
        if (positions.isEmpty()) {
            sender.sendMessage(Component.text(
                    "No stronghold ring positions available.", NamedTextColor.RED));
            return true;
        }
        int size = geometry.faceSize();
        double half = size / 2.0;
        StringBuilder sb = new StringBuilder("[\n");
        int written = 0;
        for (var cp : positions) {
            int bx = (cp.x() << 4) + 8;
            int bz = (cp.z() << 4) + 8;
            CubeFace f = geometry.faceAt(bx, bz);
            if (f == null) {
                continue;                      // fell in a void cell of the cross
            }
            double u = (bx - (geometry.faceMinX(f) + half)) / half;
            double v = (bz - (geometry.faceMinZ(f) + half)) / half;
            if (written > 0) {
                sb.append(",\n");
            }
            sb.append(String.format(java.util.Locale.ROOT,
                    "  {\"face\":\"%s\",\"u\":%.6f,\"v\":%.6f,\"x\":%d,\"z\":%d}",
                    f.name(), u, v, bx, bz));
            written++;
        }
        sb.append("\n]\n");
        java.nio.file.Path out = org.bukkit.Bukkit.getPluginManager().getPlugin("CubeWorld")
                .getDataFolder().toPath().resolve("strongholds.json");
        try {
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, sb.toString());
        } catch (java.io.IOException e) {
            sender.sendMessage(Component.text("Write failed: " + e, NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text(
                "strongholds: " + written + " of " + positions.size()
                        + " end portals -> " + out, NamedTextColor.AQUA));
        return true;
    }

    private boolean handleBiomeRaster(CommandSender sender, String dim, Integer atY) {
        boolean nether = dim.equalsIgnoreCase("nether");
        org.bukkit.World world = null;
        for (org.bukkit.World w : org.bukkit.Bukkit.getWorlds()) {
            if ((w.getEnvironment() == org.bukkit.World.Environment.NETHER) == nether) {
                world = w;
                break;
            }
        }
        // biome sampler per dimension (both are position-based, seed-independent)
        java.util.function.BiFunction<Integer, Integer, org.bukkit.block.Biome> sample;
        if (nether) {
            if (world == null || !(world.getGenerator() instanceof
                    com.ckemere.cubeworld.generation.CubeNetherChunkGenerator)) {
                sender.sendMessage(Component.text("Nether is not a cube world.", NamedTextColor.RED));
                return true;
            }
            // Sample the REAL vanilla nether biome (folded MultiNoise source), not
            // NetherDemoSpec's zone approximation. installNether rebinds
            // RandomState.sampler, so getNoiseBiome at raw coords is the actual
            // seam-consistent biome the game generates — no chunk gen needed.
            net.minecraft.server.level.ServerLevel level =
                    ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
            net.minecraft.world.level.biome.Climate.Sampler climate =
                    level.getChunkSource().randomState().sampler();
            net.minecraft.world.level.chunk.ChunkGenerator ncg =
                    level.getChunkSource().getGenerator();
            if (ncg instanceof org.bukkit.craftbukkit.generator.CustomChunkGenerator ccg) {
                ncg = ccg.getDelegate();
            }
            net.minecraft.world.level.biome.BiomeSource src = ncg.getBiomeSource();
            sample = (x, z) -> org.bukkit.craftbukkit.block.CraftBiome.minecraftHolderToBukkit(
                    src.getNoiseBiome(x >> 2, 64 >> 2, z >> 2, climate));
        } else {
            if (world == null || !(world.getGenerator() instanceof
                    com.ckemere.cubeworld.generation.CubeWorldChunkGenerator gen)) {
                sender.sendMessage(Component.text("Overworld is not a cube world.", NamedTextColor.RED));
                return true;
            }
            org.bukkit.World ow = world;
            var bp = gen.biomeProvider();
            if (atY == null) {
                sample = (x, z) -> bp.surfaceBiome(ow, x, z);
            } else {
                // A specific depth, for answering a STRUCTURE PLACEMENT question,
                // so it must sample the way placement does -- which is not the
                // Bukkit provider. CustomChunkGenerator.getBiomeSource() returns
                // `delegate.getBiomeSource()`, i.e. vanilla's own source; the
                // Bukkit BiomeProvider only paints biomes into chunks as they
                // generate. Structure.isValidBiome therefore reads vanilla's
                // MultiNoise source over our rebound router:
                //   getNoiseBiome(QuartPos.fromBlock(startPos.getX()),
                //                 QuartPos.fromBlock(startPos.getY()), ...)
                net.minecraft.server.level.ServerLevel level =
                        ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
                net.minecraft.world.level.biome.Climate.Sampler climate =
                        level.getChunkSource().randomState().sampler();
                net.minecraft.world.level.biome.BiomeSource osrc =
                        level.getChunkSource().getGenerator().getBiomeSource();
                int qy = atY >> 2;
                sample = (x, z) -> org.bukkit.craftbukkit.block.CraftBiome.minecraftHolderToBukkit(
                        osrc.getNoiseBiome(x >> 2, qy, z >> 2, climate));
            }
        }
        CubeGeometry geom = nether ? netherGeometry : geometry;   // nether cube is 1:8
        int size = geom.faceSize();
        int chunks = size >> 4;
        long t0 = System.currentTimeMillis();
        java.util.LinkedHashMap<String, Integer> palette = new java.util.LinkedHashMap<>();
        java.util.List<short[]> grids = new java.util.ArrayList<>();
        for (CubeFace f : CubeFace.values()) {
            int minX = geom.faceMinX(f);
            int minZ = geom.faceMinZ(f);
            short[] grid = new short[chunks * chunks];
            // Surface rasters sample the chunk CENTRE -- they are drawn as a map,
            // and surface biomes are broad enough that +8 is representative.
            // A depth raster exists to answer a structure placement question
            // instead, so it samples the exact block vanilla tests: jigsaw
            // structures start at `new BlockPos(chunkPos.getMinBlockX(), height,
            // chunkPos.getMinBlockZ())`, the chunk CORNER. At depth that
            // distinction decides the answer -- corner and centre are different
            // quart cells, and cave biomes vary per quart, so sampling the centre
            // made the ancient-city filter guess rather than agree.
            int off = (atY == null) ? 8 : 0;
            for (int i = 0; i < chunks; i++) {
                int x = minX + (i << 4) + off;
                for (int j = 0; j < chunks; j++) {
                    int z = minZ + (j << 4) + off;
                    String key = sample.apply(x, z).getKey().toString();
                    Integer idx = palette.get(key);
                    if (idx == null) {
                        idx = palette.size();
                        palette.put(key, idx);
                    }
                    grid[i * chunks + j] = idx.shortValue();
                }
            }
            grids.add(grid);
        }
        java.nio.file.Path out = org.bukkit.Bukkit.getPluginManager().getPlugin("CubeWorld")
                .getDataFolder().toPath().resolve("biomes")
                .resolve((nether ? "nether" : "overworld")
                        + (atY == null ? "" : "_y" + atY) + ".cwbr");
        try {
            java.nio.file.Files.createDirectories(out.getParent());
            try (java.io.DataOutputStream o = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(out)))) {
                o.writeBytes("CWBR");
                o.writeInt(1);
                o.writeInt(size);
                o.writeInt(chunks);
                o.writeInt(palette.size());
                for (String k : palette.keySet()) {
                    o.writeUTF(k);
                }
                int fi = 0;
                for (CubeFace f : CubeFace.values()) {
                    o.writeUTF(f.name());
                    o.writeInt(geom.faceMinX(f));
                    o.writeInt(geom.faceMinZ(f));
                    for (short s : grids.get(fi++)) {
                        o.writeShort(s);
                    }
                }
            }
        } catch (java.io.IOException e) {
            sender.sendMessage(Component.text("biomeraster write failed: " + e, NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text(String.format(Locale.ROOT,
                "biomeraster: %d^2 x6 faces, %d biomes -> %s (%dms)",
                chunks, palette.size(), out, System.currentTimeMillis() - t0),
                NamedTextColor.GREEN));
        return true;
    }

    /**
     * Dump vanilla's overworld multi-noise biome partition (the ~N climate
     * parameter points + their biomes) to JSON, so a portable biome generator
     * can reproduce {@code VanillaBiomeMapper} outside the game. Values are the
     * game's quantised longs (climate value * 10000). One-time extraction.
     */
    private boolean handleDumpBiomeParams(CommandSender sender) {
        try {
            var server = ((org.bukkit.craftbukkit.CraftServer) org.bukkit.Bukkit.getServer())
                    .getServer();
            var reg = server.registryAccess().lookupOrThrow(
                    net.minecraft.core.registries.Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
            var preset = reg.getOrThrow(
                    net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD);
            var source = net.minecraft.world.level.biome.MultiNoiseBiomeSource
                    .createFromPreset(preset);
            var method = net.minecraft.world.level.biome.MultiNoiseBiomeSource.class
                    .getDeclaredMethod("parameters");
            method.setAccessible(true);
            var plist = method.invoke(source);
            @SuppressWarnings("unchecked")
            var values = (java.util.List<com.mojang.datafixers.util.Pair<
                    net.minecraft.world.level.biome.Climate.ParameterPoint,
                    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>>)
                    plist.getClass().getMethod("values").invoke(plist);
            java.util.List<String> rows = new java.util.ArrayList<>();
            for (var pair : values) {
                var p = pair.getFirst();
                String id = org.bukkit.craftbukkit.block.CraftBiome
                        .minecraftHolderToBukkit(pair.getSecond()).getKey().toString();
                long[] q = {p.temperature().min(), p.temperature().max(),
                        p.humidity().min(), p.humidity().max(),
                        p.continentalness().min(), p.continentalness().max(),
                        p.erosion().min(), p.erosion().max(),
                        p.depth().min(), p.depth().max(),
                        p.weirdness().min(), p.weirdness().max(), p.offset()};
                StringBuilder row = new StringBuilder("  [\"").append(id).append("\"");
                for (long v : q) {
                    row.append(",").append(v);
                }
                rows.add(row.append("]").toString());
            }
            java.nio.file.Path out = org.bukkit.Bukkit.getPluginManager().getPlugin("CubeWorld")
                    .getDataFolder().toPath().resolve("biomes").resolve("overworld_params.json");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, "[\n" + String.join(",\n", rows) + "\n]\n");
            sender.sendMessage(Component.text("dumped " + rows.size() + " biome params -> " + out,
                    NamedTextColor.GREEN));
        } catch (Exception e) {
            sender.sendMessage(Component.text("dump failed: " + e, NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleFace(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players have a position.", NamedTextColor.RED));
            return true;
        }
        Location loc = player.getLocation();
        CubeFace face = geometry.faceAt(loc.getBlockX(), loc.getBlockZ());
        if (face == null) {
            player.sendMessage(Component.text("You are outside the cube net (the void).", NamedTextColor.RED));
        } else {
            player.sendMessage(Component.text(
                    "Face: " + face.displayName()
                            + "  local (" + geometry.localX(face, loc.getBlockX())
                            + ", " + geometry.localZ(face, loc.getBlockZ()) + ")"
                            + " of " + geometry.faceSize(),
                    NamedTextColor.AQUA));
        }
        return true;
    }

    private boolean handleTp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can teleport.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(Component.text("Usage: /cubeworld tp <face>", NamedTextColor.RED));
            return true;
        }
        CubeFace face;
        try {
            face = CubeFace.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text("Unknown face. Faces: " + faceNames(), NamedTextColor.RED));
            return true;
        }
        double cx = geometry.faceMinX(face) + geometry.faceSize() / 2.0;
        double cz = geometry.faceMinZ(face) + geometry.faceSize() / 2.0;
        double y = Math.max(sampler().heightAt(cx, cz), com.ckemere.cubeworld.generation.SphericalDemoSpec.SEA_LEVEL) + 2.0;
        Location dest = new Location(player.getWorld(), cx, y, cz,
                player.getYaw(), player.getPitch());
        player.teleportAsync(dest);
        player.sendMessage(Component.text("Teleported to " + face.displayName() + " center.", NamedTextColor.AQUA));
        return true;
    }

    /**
     * Dry-run of the seam logic from the console: given a from-position and a
     * to-position, print where a crossing entity would come out. Lets the
     * seam wiring be exercised over RCON without a connected player.
     */
    private boolean handleSimulate(CommandSender sender, String[] args) {
        if (args.length != 6) {
            sender.sendMessage(Component.text(
                    "Usage: /cubeworld simulate <fromX> <fromZ> <toX> <toZ> <yaw>", NamedTextColor.RED));
            return true;
        }
        double fromX;
        double fromZ;
        double toX;
        double toZ;
        float yaw;
        try {
            fromX = Double.parseDouble(args[1]);
            fromZ = Double.parseDouble(args[2]);
            toX = Double.parseDouble(args[3]);
            toZ = Double.parseDouble(args[4]);
            yaw = Float.parseFloat(args[5]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Arguments must be numbers.", NamedTextColor.RED));
            return true;
        }
        Location from = new Location(null, fromX, 64, fromZ);
        Location to = new Location(null, toX, 64, toZ, yaw, 0);
        Location dest = seams.seamDestination(from, to);
        if (dest == null) {
            sender.sendMessage(Component.text("simulate: no seam crossing", NamedTextColor.YELLOW));
        } else {
            CubeFace face = geometry.faceAt(dest.getBlockX(), dest.getBlockZ());
            sender.sendMessage(Component.text(String.format(Locale.ROOT,
                    "simulate: -> (%.2f, %.2f) yaw %.1f on %s",
                    dest.getX(), dest.getZ(), dest.getYaw(),
                    face == null ? "VOID" : face.name()), NamedTextColor.AQUA));
        }
        return true;
    }

    /**
     * Debug hook: push a real block's state to its margin mirrors, exactly as
     * the place/break listener would. Lets mirror sync be exercised over RCON
     * (console setblock does not fire BlockPlaceEvent).
     */
    private boolean handleMirrorPush(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sender.sendMessage(Component.text("Usage: /cubeworld mirrorpush <x> <y> <z>", NamedTextColor.RED));
            return true;
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Coordinates must be integers.", NamedTextColor.RED));
            return true;
        }
        org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
        mirrors.pushToMirrors(world.getBlockAt(x, y, z));
        sender.sendMessage(Component.text("mirrorpush: done", NamedTextColor.AQUA));
        return true;
    }

    /**
     * Debug hook: forward a break (material == null) or placement at a margin
     * position to its real source block, exactly as the margin interaction
     * listener would for a player.
     */
    private boolean handleMarginEdit(CommandSender sender, String[] args, String material) {
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Coordinates must be integers.", NamedTextColor.RED));
            return true;
        }
        org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
        org.bukkit.block.Block margin = world.getBlockAt(x, y, z);
        org.bukkit.block.Block source;
        if (material == null) {
            source = mirrors.forwardBreak(margin);
        } else {
            org.bukkit.block.data.BlockData data;
            try {
                data = org.bukkit.Bukkit.createBlockData(material);
            } catch (IllegalArgumentException e) {
                sender.sendMessage(Component.text("Unknown block data: " + material, NamedTextColor.RED));
                return true;
            }
            source = mirrors.forwardPlace(margin, data);
        }
        if (source == null) {
            sender.sendMessage(Component.text("Not a forwardable margin position.", NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text(String.format(Locale.ROOT,
                    "forwarded to source (%d, %d, %d)", source.getX(), source.getY(), source.getZ()),
                    NamedTextColor.AQUA));
        }
        return true;
    }

    private String faceNames() {
        StringBuilder sb = new StringBuilder();
        for (CubeFace face : CubeFace.values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(face.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        // Completions must not reveal more than the sender may run: non-admins
        // see exactly the public subcommands, admins the curated working set.
        boolean admin = sender.hasPermission("cubeworld.admin");
        if (args.length == 1) {
            String[] subs = admin
                    ? new String[] {"ping", "face", "tp", "simulate", "biomeat",
                            "biomeraster", "refreshmap", "map", "mapprecision",
                            "strongholds", "tpcore", "tpstations"}
                    : new java.util.TreeSet<>(PUBLIC_SUBCOMMANDS).toArray(new String[0]);
            for (String sub : subs) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("tp") && admin) {
            for (CubeFace face : CubeFace.values()) {
                String name = face.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(name);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mapprecision")) {
            for (String level : new String[] {"high", "medium", "low"}) {
                if (level.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(level);
                }
            }
        }
        return out;
    }
}
