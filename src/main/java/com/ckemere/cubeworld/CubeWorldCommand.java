package com.ckemere.cubeworld;

import com.ckemere.cubeworld.generation.CubeWorldChunkGenerator;
import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
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

    public CubeWorldCommand(CubeGeometry geometry, SeamService seams) {
        this.geometry = geometry;
        this.seams = seams;
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
        Location dest = new Location(player.getWorld(), cx, CubeWorldChunkGenerator.SURFACE_Y + 2.0, cz,
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
