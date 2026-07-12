package com.ckemere.cubeworld;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class CubeWorldPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("CubeWorld enabled");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("ping")) {
            sender.sendMessage(Component.text("CubeWorld: pong!", NamedTextColor.GREEN));
            return true;
        }
        return false;
    }
}
