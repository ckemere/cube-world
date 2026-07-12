package com.ckemere.cubeworld;

import com.ckemere.cubeworld.generation.CubeWorldChunkGenerator;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.seam.MarginInteractionListener;
import com.ckemere.cubeworld.seam.MirrorService;
import com.ckemere.cubeworld.seam.MirrorSyncListener;
import com.ckemere.cubeworld.seam.SeamService;
import com.ckemere.cubeworld.seam.SeamTeleportListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CubeWorldPlugin extends JavaPlugin {

    /** Edge length of one cube face in blocks (50 chunks). */
    public static final int FACE_SIZE = 50 * 16;

    /** Depth of the mirrored seam margins in blocks (6 chunks; match view-distance). */
    public static final int MARGIN_BLOCKS = 6 * 16;

    private final CubeGeometry geometry = new CubeGeometry(FACE_SIZE);
    private final CubeTopology topology = new CubeTopology(geometry);
    private final SeamService seams = new SeamService(topology);
    private final MirrorService mirrors = new MirrorService(topology, MARGIN_BLOCKS);

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new SeamTeleportListener(seams), this);
        getServer().getPluginManager().registerEvents(new MirrorSyncListener(this, mirrors), this);
        getServer().getPluginManager().registerEvents(new MarginInteractionListener(mirrors), this);
        CubeWorldCommand executor = new CubeWorldCommand(geometry, seams, mirrors);
        PluginCommand command = getCommand("cubeworld");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("CubeWorld enabled (face size " + FACE_SIZE + " blocks)");
    }

    public CubeGeometry geometry() {
        return geometry;
    }

    public CubeTopology topology() {
        return topology;
    }

    public SeamService seams() {
        return seams;
    }

    public MirrorService mirrors() {
        return mirrors;
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        return new CubeWorldChunkGenerator(topology, MARGIN_BLOCKS);
    }
}
