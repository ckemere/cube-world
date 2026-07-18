package com.ckemere.cubeworld;

import com.ckemere.cubeworld.generation.CubeNetherChunkGenerator;
import com.ckemere.cubeworld.generation.CubeWorldChunkGenerator;
import com.ckemere.cubeworld.generation.MapService;
import com.ckemere.cubeworld.geometry.CubeGeometry;
import com.ckemere.cubeworld.geometry.CubeTopology;
import com.ckemere.cubeworld.seam.EntityMirrorService;
import com.ckemere.cubeworld.seam.EntitySeamListener;
import com.ckemere.cubeworld.seam.LiquidSeamService;
import com.ckemere.cubeworld.seam.MarginInteractionListener;
import com.ckemere.cubeworld.seam.MarginReconciler;
import com.ckemere.cubeworld.seam.MirrorService;
import com.ckemere.cubeworld.seam.MirrorSyncListener;
import com.ckemere.cubeworld.seam.PartnerTicketService;
import com.ckemere.cubeworld.seam.PillarGuardListener;
import com.ckemere.cubeworld.seam.PortalLinkListener;
import com.ckemere.cubeworld.seam.SeamService;
import com.ckemere.cubeworld.seam.SeamTeleportListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CubeWorldPlugin extends JavaPlugin {

    /** Edge length of one cube face in blocks (640 chunks; ~1 km/block Earth). */
    public static final int FACE_SIZE = 640 * 16;

    /** Overworld spawn: Addis Ababa, Ethiopia, folded to the net at roll -70. */
    private static final int SPAWN_X = 9381;
    private static final int SPAWN_Z = -1737;

    /** Depth of the mirrored seam margins in blocks (6 chunks; match view-distance). */
    public static final int MARGIN_BLOCKS = 6 * 16;

    private final CubeGeometry geometry = new CubeGeometry(FACE_SIZE);
    private final CubeTopology topology = new CubeTopology(geometry);
    private final MapService maps = new MapService(topology);
    private final SeamService seams = new SeamService(topology);
    private final MirrorService mirrors = new MirrorService(topology, MARGIN_BLOCKS);
    private final Map<UUID, WorldServices> perWorld = new LinkedHashMap<>();

    /** Per-world seam machinery. Both cube worlds share geometry and topology. */
    public record WorldServices(World world, LiquidSeamService liquids,
                                EntityMirrorService entityMirrors,
                                PartnerTicketService tickets, MarginReconciler reconciler) {
    }

    /** Worlds whose terrain is one of ours (overworld and nether cubes; never the end). */
    public boolean isCubeWorld(World world) {
        ChunkGenerator generator = world.getGenerator();
        return generator instanceof CubeWorldChunkGenerator
                || generator instanceof CubeNetherChunkGenerator;
    }

    public @Nullable WorldServices servicesFor(World world) {
        return perWorld.get(world.getUID());
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new SeamTeleportListener(this, seams), this);
        getServer().getPluginManager().registerEvents(new MirrorSyncListener(this, mirrors), this);
        getServer().getPluginManager().registerEvents(new MarginInteractionListener(this, mirrors), this);
        getServer().getPluginManager().registerEvents(new EntitySeamListener(this, mirrors), this);
        getServer().getPluginManager().registerEvents(new PillarGuardListener(this, topology, MARGIN_BLOCKS), this);
        getServer().getPluginManager().registerEvents(new PortalLinkListener(this, geometry, maps), this);
        loadEarthData();
        // Worlds are not loaded yet during onEnable (load: STARTUP); wire the
        // per-world services on the first server tick.
        getServer().getScheduler().runTask(this, () -> {
            for (World world : getServer().getWorlds()) {
                if (isCubeWorld(world)) {
                    setupWorld(world);
                }
            }
        });
        CubeWorldCommand executor = new CubeWorldCommand(geometry, seams, mirrors, maps);
        PluginCommand command = getCommand("cubeworld");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("CubeWorld enabled (face size " + FACE_SIZE + " blocks)");
    }

    /** Load the CWE1 Earth rasters (run dir, then plugin data folder). When
     * present the overworld generates real Earth instead of demo terrain. */
    private void loadEarthData() {
        for (java.nio.file.Path p : new java.nio.file.Path[] {
                java.nio.file.Path.of("earth.dat"),
                getDataFolder().toPath().resolve("earth.dat")}) {
            if (java.nio.file.Files.exists(p)) {
                try {
                    com.ckemere.cubeworld.generation.EarthData earth =
                            com.ckemere.cubeworld.generation.EarthData.load(p);
                    maps.setEarthData(earth);
                    getLogger().info("Loaded Earth data from " + p + " (roll " + earth.roll() + ")");
                    return;
                } catch (Exception e) {
                    getLogger().warning("Failed to load Earth data from " + p + ": " + e);
                }
            }
        }
        getLogger().info("No earth.dat found; using demo terrain.");
    }

    private void setupWorld(World world) {
        if (world.getEnvironment() == World.Environment.NORMAL && maps.hasEarthData()) {
            int sy = (int) Math.round(
                    maps.mapFor(world.getSeed()).sampler().heightAt(SPAWN_X + 0.5, SPAWN_Z + 0.5)) + 2;
            world.setSpawnLocation(SPAWN_X, Math.max(sy, 64), SPAWN_Z);
            getLogger().info("Overworld spawn set to Ethiopia (" + SPAWN_X + ", " + sy + ", " + SPAWN_Z + ")");
        }
        LiquidSeamService liquids = new LiquidSeamService(topology, mirrors, world);
        EntityMirrorService entityMirrors = new EntityMirrorService(this, topology, MARGIN_BLOCKS);
        PartnerTicketService tickets = new PartnerTicketService(this, topology, MARGIN_BLOCKS);
        MarginReconciler reconciler = new MarginReconciler(topology, MARGIN_BLOCKS, world);
        getServer().getPluginManager().registerEvents(liquids, this);
        getServer().getPluginManager().registerEvents(reconciler, this);
        reconciler.bootstrap(world);
        getServer().getScheduler().runTaskTimer(this, () -> liquids.tick(world), 100L, 5L);
        getServer().getScheduler().runTaskTimer(this, () -> entityMirrors.tick(world), 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, () -> tickets.refresh(world), 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> reconciler.tick(world), 60L, 1L);
        com.ckemere.cubeworld.seam.nms.NmsSeamHook.install(world, topology, mirrors, this, getLogger());
        perWorld.put(world.getUID(), new WorldServices(world, liquids, entityMirrors, tickets, reconciler));
        getLogger().info("Cube topology active in world '" + world.getName() + "'");
    }

    @Override
    public void onDisable() {
        for (WorldServices services : perWorld.values()) {
            services.entityMirrors().removeAllClones();
            services.tickets().releaseAll(services.world());
        }
        perWorld.clear();
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
        if (worldName.contains("nether")) {
            return new CubeNetherChunkGenerator(topology, maps, MARGIN_BLOCKS);
        }
        return new CubeWorldChunkGenerator(topology, maps, MARGIN_BLOCKS);
    }
}
