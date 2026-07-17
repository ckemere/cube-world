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
    private final SeamService seams;
    private final MirrorService mirrors;
    private final MapService maps;

    public CubeWorldCommand(CubeGeometry geometry, SeamService seams, MirrorService mirrors,
                            MapService maps) {
        this.geometry = geometry;
        this.seams = seams;
        this.mirrors = mirrors;
        this.maps = maps;
    }

    /** The sampler for the main world's seed. */
    private MapSampler sampler() {
        return maps.mapFor(org.bukkit.Bukkit.getWorlds().get(0).getSeed()).sampler();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ping" -> {
                sender.sendMessage(Component.text("CubeWorld: pong!", NamedTextColor.GREEN));
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
        if (args.length == 1) {
            for (String sub : new String[] {"ping", "face", "tp", "simulate"}) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
            for (CubeFace face : CubeFace.values()) {
                String name = face.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(name);
                }
            }
        }
        return out;
    }
}
