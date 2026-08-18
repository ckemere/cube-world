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

    /** The nether cube is 1:8, a true 8x-travel nether. Its theme noise lives in
     * normalised cube space, so an eighth-size face makes nether biomes an eighth
     * the size in blocks automatically. */
    public static final int NETHER_FACE_SIZE = FACE_SIZE / 8;
    public static final int NETHER_SCALE = 8;

    /** Overworld spawn: Addis Ababa, Ethiopia, folded to the net at roll -70. */
    private static final int SPAWN_X = 11973;
    private static final int SPAWN_Z = -856;

    /** Depth of the mirrored seam margins in blocks (6 chunks; match view-distance). */
    public static final int MARGIN_BLOCKS = 6 * 16;

    private final CubeGeometry geometry = new CubeGeometry(FACE_SIZE);
    private final CubeTopology topology = new CubeTopology(geometry);
    private final CubeGeometry netherGeometry = new CubeGeometry(NETHER_FACE_SIZE);
    private final CubeTopology netherTopology = new CubeTopology(netherGeometry);
    private final MapService maps = new MapService(topology, netherTopology);
    private final SeamService seams = new SeamService(topology);
    private final MirrorService mirrors = new MirrorService(topology, MARGIN_BLOCKS);
    private final MirrorService netherMirrors = new MirrorService(netherTopology, MARGIN_BLOCKS);
    private final Map<UUID, WorldServices> perWorld = new LinkedHashMap<>();
    private com.ckemere.cubeworld.teleport.TeleportService teleport;
    private com.ckemere.cubeworld.trades.MasterTraderService masterTraders;
    private com.ckemere.cubeworld.generation.OreEnrichment oreEnrichment;
    private com.ckemere.cubeworld.generation.Prospector prospector;
    private com.ckemere.cubeworld.city.VillagePlacementWatcher placementWatcher;

    public com.ckemere.cubeworld.generation.Prospector prospector() {
        return prospector;
    }

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
        getServer().getPluginManager().registerEvents(
                new PortalLinkListener(this, geometry, netherGeometry, topology,
                        netherTopology, maps), this);
        // Teleport network: reskinned-lodestone stations on amethyst pads, with
        // the 30 cities pre-seeded (built as their chunks load).
        teleport = new com.ckemere.cubeworld.teleport.TeleportService(this);
        teleport.load();
        teleport.registerRecipe();
        teleport.selfTest();
        // ...and again once the server has finished loading. plugin.yml sets
        // load: STARTUP (the default world ignores the generator otherwise), so
        // onEnable runs BEFORE datapacks load -- and RecipeManager.apply does
        // "this.recipes = recipes", replacing the whole map and discarding any
        // recipe a plugin added first. Registering here too survives that, and
        // covers /reload for the same reason. registerRecipe is a no-op when the
        // recipe is already present, so the startup call is left in place for the
        // case where nothing reloads.
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onServerLoad(org.bukkit.event.server.ServerLoadEvent e) {
                teleport.registerRecipe();
                teleport.selfTest();
            }
        }, this);
        teleport.seedCities();
        getServer().getPluginManager().registerEvents(
                new com.ckemere.cubeworld.teleport.TeleporterListener(this, teleport), this);
        // Expedition advancements (cubeworld:*) each award one Teleporter Core.
        getServer().getPluginManager().registerEvents(new AdvancementRewards(teleport), this);
        getServer().getScheduler().runTaskTimer(this, teleport::ambientTick, 40L, 40L);
        // Repair the ground under the forced city villages once the structure
        // exists: fill water and voids across the walkable footprint so nothing
        // floats and no villager can walk into deep water.
        getServer().getPluginManager().registerEvents(
                new com.ckemere.cubeworld.city.VillageGroundFixer(this), this);
        // Generation-time support: hooks AsyncStructureGenerateEvent so a village piece
        // is founded as it is written, with the block's provenance known rather than
        // reconstructed afterwards. Run with -Dcubeworld.villageFix=false to disable the
        // old chunk-load repair and see this one's output on its own.
        getServer().getPluginManager().registerEvents(
                new com.ckemere.cubeworld.city.VillageFoundationTransformer(this), this);
        // Watch-only recorder for the same event: -Dcubeworld.placeWatch=true logs every
        // block a structure places (order, replaced block, gap below) for offline study.
        // Only registered when enabled, so the default config pays no dispatch cost.
        placementWatcher = new com.ckemere.cubeworld.city.VillagePlacementWatcher(this);
        if (placementWatcher.isEnabled()) {
            getServer().getPluginManager().registerEvents(placementWatcher, this);
        }
        // Enrich ores where terrain corresponds to real Earth mineral provinces.
        oreEnrichment = new com.ckemere.cubeworld.generation.OreEnrichment(this);
        getServer().getPluginManager().registerEvents(oreEnrichment, this);
        // The carried ore detector that reads the same field.
        prospector = new com.ckemere.cubeworld.generation.Prospector(this, oreEnrichment);
        prospector.registerRecipes();
        prospector.start();
        // Re-aim thrown Eyes of Ender along the cube geodesic toward the nearest
        // stronghold, so they point at the correct seam instead of a raw XZ line.
        com.ckemere.cubeworld.geometry.CubeBearing bearing =
                new com.ckemere.cubeworld.geometry.CubeBearing(topology);
        getServer().getPluginManager().registerEvents(
                new com.ckemere.cubeworld.seam.EnderEyeListener(this, bearing), this);
        // Locator-bar dot toward the centre of a cube map the player is holding.
        new com.ckemere.cubeworld.map.MapCenterLocator(this, bearing);
        // Every map made in a cube world gets the seam-flattening renderer, so a
        // crafted map behaves like the demo ones instead of falling back to vanilla
        // (which draws void across seams and drops the player marker off-map).
        getServer().getPluginManager().registerEvents(
                new com.ckemere.cubeworld.map.CubeMapListener(
                        this, geometry, topology, teleport), this);
        // Rare "Master Traders" visit the special cities with exceptional goods.
        masterTraders = new com.ckemere.cubeworld.trades.MasterTraderService(this, teleport);
        masterTraders.start();
        loadEarthData();
        // Register the 30 per-city village structures (cubeworld:<city>) into
        // the frozen STRUCTURE registry — code replaces the old cities
        // datapack. Must precede world load; onEnable (load: STARTUP) is.
        // Also widens vanilla village_plains biomes so natural villages settle
        // temperate forests. Runs even with -Dcubeworld.anchorCities=false so
        // `/place structure cubeworld:<city>` works on raw-terrain probes.
        com.ckemere.cubeworld.seam.nms.CityStructures.registerStructures(getLogger());
        // Fold vanilla's terrain router onto the sphere BEFORE spawn chunks
        // generate. WorldInitEvent fires during world load (this STARTUP plugin
        // is already enabled), which is early enough; the first server tick is
        // not. Demo world only — the Earth world keeps its real-elevation fill.
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onWorldInit(org.bukkit.event.world.WorldInitEvent e) {
                World w = e.getWorld();
                if (isCubeWorld(w) && w.getEnvironment() == World.Environment.NORMAL) {
                    // Earth world: terrain follows real elevation (hybrid); demo
                    // world: pure vanilla noise. Either way vanilla places blocks.
                    com.ckemere.cubeworld.seam.nms.SphereRouterHook.install(
                            w, maps.mapFor(w.getSeed()).sampler(), FACE_SIZE,
                            maps.hasEarthData(), maps.earthData(), getLogger());
                    // Anchor the per-city village structures at their historical
                    // chunks — before spawn chunks, so even a city near spawn
                    // would generate (the old first-tick hook could not).
                    com.ckemere.cubeworld.seam.nms.CityStructures.injectWorld(w, getLogger());
                } else if (isCubeWorld(w) && w.getEnvironment() == World.Environment.NETHER) {
                    // Cube nether: fold vanilla nether density (BlendedNoise + biome
                    // sampler) onto the 1:8 nether cube so real netherrack terrain,
                    // the lava sea, the bedrock roof/floor, caves and MultiNoise
                    // nether biomes all appear AND stay seamless across every seam.
                    com.ckemere.cubeworld.seam.nms.SphereRouterHook.installNether(
                            w, maps.mapFor(w.getSeed()).netherSampler(), NETHER_FACE_SIZE,
                            getLogger());
                }
            }
        }, this);
        // Worlds are not loaded yet during onEnable (load: STARTUP); wire the
        // per-world services on the first server tick.
        getServer().getScheduler().runTask(this, () -> {
            for (World world : getServer().getWorlds()) {
                if (isCubeWorld(world)) {
                    setupWorld(world);
                }
            }
        });
        // Exploration achievements: write the advancement datapack into each
        // cube overworld (reloading data only when it is first created), then
        // poll for poles / summits / circumnavigation once a second.
        ExplorationAchievements exploration = new ExplorationAchievements(this, maps);
        getServer().getScheduler().runTask(this, () -> {
            boolean wrote = false;
            for (World world : getServer().getWorlds()) {
                if (isCubeWorld(world) && world.getEnvironment() == World.Environment.NORMAL) {
                    // getWorldFolder() points at the dimension subfolder on
                    // Paper 26.x; datapacks are scanned from the world root.
                    java.io.File worldRoot =
                            new java.io.File(getServer().getWorldContainer(), world.getName());
                    wrote |= ExplorationAchievements.writeDatapack(worldRoot);
                }
            }
            if (wrote) {
                getLogger().info("Exploration datapack written; reloading data.");
                getServer().reloadData();
            }
        });
        getServer().getScheduler().runTaskTimer(this, exploration::tick, 100L, 20L);

        CubeWorldCommand executor = new CubeWorldCommand(geometry, netherGeometry, seams, mirrors,
                maps, teleport, masterTraders, oreEnrichment, prospector);
        PluginCommand command = getCommand("cubeworld");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("CubeWorld enabled (face size " + FACE_SIZE + " blocks)");
        warnIfMapArtefactsStale();
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
                    // Sidecar rasters (distance-to-coast) live beside earth.dat so
                    // a layer can be added without rewriting the 359 MB bundle.
                    for (java.nio.file.Path side : new java.nio.file.Path[] {
                            p.resolveSibling("coast.dat"),
                            java.nio.file.Path.of("coast.dat")}) {
                        if (java.nio.file.Files.exists(side)) {
                            try {
                                earth.merge(side);
                                getLogger().info("Merged sidecar raster " + side);
                            } catch (Exception ex) {
                                getLogger().warning("Failed to merge " + side + ": " + ex);
                            }
                            break;
                        }
                    }
                    maps.setEarthData(earth);
                    getLogger().info("Loaded Earth data from " + p + " (roll " + earth.roll()
                            + ", coast=" + earth.hasLayer("coast") + ")");
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
        if (world.getEnvironment() == World.Environment.NORMAL) {
            com.ckemere.cubeworld.seam.nms.StrongholdSphereHook.install(
                    world, geometry, topology, MARGIN_BLOCKS, this, getLogger());
            // (city villages are anchored earlier, at WorldInit — CityStructures)
        }
        // the nether cube is 1:8, so its seams run on the nether topology/mirrors
        boolean nether = world.getEnvironment() == World.Environment.NETHER;
        CubeTopology topo = nether ? netherTopology : topology;
        MirrorService mir = nether ? netherMirrors : mirrors;
        // Cube-aware locator bar: dots for other players point along the cube geodesic.
        // Overworld + nether only (the End is standard vanilla). -Dcubeworld.cubeLocator=false
        // keeps the vanilla (flat) locator bar.
        if (isCubeWorld(world)
                && !"false".equalsIgnoreCase(System.getProperty("cubeworld.cubeLocator", "true"))) {
            com.ckemere.cubeworld.seam.nms.WaypointManagerHook.install(
                    world, new com.ckemere.cubeworld.geometry.CubeBearing(topo), getLogger());
        }
        LiquidSeamService liquids = new LiquidSeamService(topo, mir, world);
        EntityMirrorService entityMirrors = new EntityMirrorService(this, topo, MARGIN_BLOCKS);
        PartnerTicketService tickets = new PartnerTicketService(this, topo, MARGIN_BLOCKS);
        MarginReconciler reconciler = new MarginReconciler(topo, MARGIN_BLOCKS, world);
        getServer().getPluginManager().registerEvents(liquids, this);
        getServer().getPluginManager().registerEvents(reconciler, this);
        reconciler.bootstrap(world);
        getServer().getScheduler().runTaskTimer(this, () -> liquids.tick(world), 100L, 5L);
        getServer().getScheduler().runTaskTimer(this, () -> entityMirrors.tick(world), 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, () -> tickets.refresh(world), 40L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> reconciler.tick(world), 60L, 1L);
        com.ckemere.cubeworld.seam.nms.NmsSeamHook.install(world, topo, mir, this, getLogger());
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
        if (masterTraders != null) {
            masterTraders.stop();
        }
        if (teleport != null) {
            teleport.save();
        }
        if (placementWatcher != null) {
            placementWatcher.close();
        }
    }

    public CubeGeometry geometry() {
        return geometry;
    }

    /** The map service (Earth data + per-seed samplers) for coordinate work. */
    public MapService maps() {
        return maps;
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        if (worldName.contains("nether")) {
            return new CubeNetherChunkGenerator(netherTopology, maps, MARGIN_BLOCKS);
        }
        return new CubeWorldChunkGenerator(topology, maps, MARGIN_BLOCKS);
    }

    /**
     * Say so, loudly, when the web map's inputs are older than this plugin.
     *
     * <p>The rasters and the stronghold dump are produced by commands, so
     * nothing tied them to the generator that produced them. After a rebuild
     * they keep serving the previous world's answers and look completely
     * normal: during one session the depth raster sat a build behind and the
     * biome page four hours behind, and the only symptom was numbers quietly
     * disagreeing. A map that is wrong without saying so is worse than one that
     * is missing.
     */
    private void warnIfMapArtefactsStale() {
        try {
            java.io.File jar = new java.io.File(getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            long built = jar.lastModified();
            java.nio.file.Path dir = getDataFolder().toPath();
            java.util.List<String> names = new java.util.ArrayList<>();
            names.add("biomes/overworld.cwbr");
            for (int y : CubeWorldCommand.MAP_DEPTH_RASTERS) {
                names.add("biomes/overworld_y" + y + ".cwbr");
            }
            names.add("strongholds.json");
            java.util.List<String> stale = new java.util.ArrayList<>();
            for (String n : names) {
                java.io.File f = dir.resolve(n).toFile();
                if (!f.exists()) {
                    stale.add(n + " (missing)");
                } else if (f.lastModified() < built) {
                    stale.add(n + " (" + ((built - f.lastModified()) / 60000) + " min behind)");
                }
            }
            if (!stale.isEmpty()) {
                getLogger().warning("Web map inputs are older than this build: "
                        + String.join(", ", stale)
                        + " -- run `/cubeworld refreshmap` or the map will show the"
                        + " PREVIOUS generator's world.");
            }
        } catch (Throwable t) {
            // never let a freshness check stop startup
        }
    }

}
