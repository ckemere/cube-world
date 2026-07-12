package com.ckemere.cubeworld;

import com.ckemere.cubeworld.generation.CubeWorldChunkGenerator;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.seam.EntityMirrorService;
import com.ckemere.cubeworld.seam.EntitySeamListener;
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
    private EntityMirrorService entityMirrors;

    @Override
    public void onEnable() {
        entityMirrors = new EntityMirrorService(this, topology, MARGIN_BLOCKS);
        getServer().getPluginManager().registerEvents(new SeamTeleportListener(seams), this);
        getServer().getPluginManager().registerEvents(new MirrorSyncListener(this, mirrors), this);
        getServer().getPluginManager().registerEvents(new MarginInteractionListener(mirrors), this);
        getServer().getPluginManager().registerEvents(new EntitySeamListener(entityMirrors), this);
        getServer().getScheduler().runTaskTimer(this,
                () -> entityMirrors.tick(getServer().getWorlds().get(0)), 1L, 1L);
        CubeWorldCommand executor = new CubeWorldCommand(geometry, seams, mirrors);
        PluginCommand command = getCommand("cubeworld");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("CubeWorld enabled (face size " + FACE_SIZE + " blocks)");
    }

    @Override
    public void onDisable() {
        if (entityMirrors != null) {
            entityMirrors.removeAllClones();
        }
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
