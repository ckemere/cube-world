package com.ckemere.cubeworld;

import com.ckemere.cubeworld.generation.CubeWorldChunkGenerator;
import com.ckemere.cubeworld.geometry.CubeFace;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CubeWorldPlugin extends JavaPlugin {

    /** Edge length of one cube face in blocks (50 chunks). */
    public static final int FACE_SIZE = 50 * 16;

    private final CubeGeometry geometry = new CubeGeometry(FACE_SIZE);

    @Override
    public void onEnable() {
        getLogger().info("CubeWorld enabled (face size " + FACE_SIZE + " blocks)");
    }

    public CubeGeometry geometry() {
        return geometry;
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        return new CubeWorldChunkGenerator(geometry);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("ping")) {
            sender.sendMessage(Component.text("CubeWorld: pong!", NamedTextColor.GREEN));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("face")) {
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
        return false;
    }
}
