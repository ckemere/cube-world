package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.city.CityAnchors;
import com.ckemere.cubeworld.city.CityAnchors.CityAnchor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * The special-city structures, registered and anchored entirely in code — no
 * datapack. Replaces the {@code cubeworld_cities} world datapack plus
 * {@code VillageAnchorHook}.
 *
 * <p>Two halves, split by what vanilla actually requires where:
 *
 * <ol>
 *   <li>{@link #registerStructures} — one {@link JigsawStructure} registry entry
 *       PER CITY ({@code cubeworld:antioch}, …), built from
 *       {@link CityAnchors} and vanilla content only (the vanilla town-center
 *       start pools; tier parameters mirror the retired datapack — see
 *       {@link Tier}). The STRUCTURE registry is the one registry that
 *       genuinely needs entries — {@code StructureStart.createTag} serializes
 *       starts by registry id — and Paper exposes no API for it, so this
 *       unfreezes the {@link MappedRegistry} for the duration of the loop
 *       (same category of reflection as the ring-position hooks). Runs in
 *       onEnable, before worlds load; always runs, even with
 *       {@code -Dcubeworld.anchorCities=false}, so probe workflows can
 *       {@code /place structure cubeworld:<city>} on raw terrain.
 *   <li>{@link #injectWorld} — per world, fabricates one single-chunk
 *       {@link StructureSet} per city (a {@link ConcentricRingsStructurePlacement}
 *       whose ring list is exactly the city's chunk) and appends them to the
 *       state's {@code possibleStructureSets}; generation iterates that list
 *       directly ({@code ChunkGenerator.createStructures}), and the ring map +
 *       {@code placementsForStructure} entries make {@code /locate} work per
 *       city. Runs on WorldInitEvent — BEFORE spawn chunks, so unlike the old
 *       first-tick hook a city near spawn would anchor correctly. Skipped by
 *       {@code -Dcubeworld.anchorCities=false} (raw-terrain escape hatch).
 * </ol>
 *
 * <p>Structure sets need no registry entries (direct holders suffice — nothing
 * serializes them), which is why only the STRUCTURE registry is touched.
 */
public final class CityStructures {

    /** Size-tier parameters, matching the retired datapack's structure JSONs.
     * An unrecognized tier in the CSV is a loud warning, not a silent default. */
    private record Tier(int size, int maxDistance) {
    }

    private static final Map<String, Tier> TIERS = Map.of(
            "large", new Tier(7, 64),
            "huge", new Tier(9, 80));

    /** Salt for the fabricated placements. It only feeds the frequency
     * reduction hash, which frequency = 1 short-circuits — but keep it out of
     * vanilla's range anyway. Placements compare by identity, so all cities
     * sharing one salt is fine. */
    private static final int SALT = 7_650_001;

    private CityStructures() {
    }

    private static boolean anchoringDisabled() {
        return "false".equalsIgnoreCase(System.getProperty("cubeworld.anchorCities", "true"));
    }

    private static ResourceKey<Structure> keyOf(CityAnchor city) {
        return ResourceKey.create(Registries.STRUCTURE,
                Identifier.fromNamespaceAndPath("cubeworld", city.slug()));
    }

    // ------------------------------------------------------------ registration

    /**
     * Register one jigsaw structure per city into the (frozen) STRUCTURE
     * registry, and widen natural villages into forests. Call from onEnable —
     * after datapack registries loaded, before worlds load. Idempotent:
     * already-present keys are kept as-is.
     */
    public static int registerStructures(Logger log) {
        try {
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            Registry<Structure> structures = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            Registry<StructureTemplatePool> pools = server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);
            Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);

            // Natural-village widening is independent of the city list; a
            // missing CSV must not silently drop it.
            widenNaturalVillages(structures, biomes, log);

            List<CityAnchor> cities = CityAnchors.load();
            if (cities.isEmpty()) {
                log.warning("City structures: no cities in cities_anchor.csv.");
                return 0;
            }

            // A forced site must never be rejected by the biome check; the
            // honest form of "broad enough" is every overworld biome.
            HolderSet<Biome> biomeSet = biomes.get(BiomeTags.IS_OVERWORLD).orElseThrow();

            Field frozen = MappedRegistry.class.getDeclaredField("frozen");
            frozen.setAccessible(true);
            // register() defers value-binding to freeze(); since we restore the
            // frozen flag directly instead of re-running freeze(), bind each new
            // holder ourselves or the first .value() call throws "unbound value".
            Method bindValue = Holder.Reference.class.getDeclaredMethod("bindValue", Object.class);
            bindValue.setAccessible(true);
            // A Reference has a SECOND deferred binding: its tag set. Leaving it
            // unbound is a landmine — the first holder.is(tag) call (e.g. the cat
            // spawner's village check with a player standing in a city) throws
            // "Tags not bound" and crashes the tick loop. Bind the vanilla plains
            // village's tags, so cities also COUNT as villages for every
            // tag-driven mechanic (cat spawns, `/locate structure #minecraft:village`).
            Method bindTags = Holder.Reference.class
                    .getDeclaredMethod("bindTags", java.util.Collection.class);
            bindTags.setAccessible(true);
            List<net.minecraft.tags.TagKey<Structure>> villageTags =
                    structures.get(ResourceKey.create(Registries.STRUCTURE,
                            Identifier.withDefaultNamespace("village_plains")))
                            .map(h -> h.tags().toList())
                            .orElse(List.of());
            int added = 0;
            frozen.set(structures, false);
            try {
                for (CityAnchor c : cities) {
                    ResourceKey<Structure> key = keyOf(c);
                    if (structures.get(key).isPresent()) {
                        continue;                      // already registered this boot
                    }
                    Identifier poolId = Identifier.withDefaultNamespace(
                            "village/" + c.pool() + "/town_centers");
                    Optional<Holder.Reference<StructureTemplatePool>> pool =
                            pools.get(ResourceKey.create(Registries.TEMPLATE_POOL, poolId));
                    if (pool.isEmpty()) {
                        log.warning("City structures: start pool " + poolId + " missing; "
                                + c.name() + " skipped.");
                        continue;
                    }
                    Tier tier = TIERS.get(c.size());
                    if (tier == null) {
                        log.warning("City structures: unknown size tier '" + c.size()
                                + "' for " + c.name() + "; skipped.");
                        continue;
                    }
                    JigsawStructure structure = new JigsawStructure(
                            new Structure.StructureSettings(biomeSet, Map.of(),
                                    GenerationStep.Decoration.SURFACE_STRUCTURES,
                                    TerrainAdjustment.BEARD_THIN),
                            pool.get(),
                            Optional.empty(),
                            tier.size(),
                            ConstantHeight.of(VerticalAnchor.absolute(0)),
                            true,
                            Optional.of(Heightmap.Types.WORLD_SURFACE_WG),
                            new JigsawStructure.MaxDistance(tier.maxDistance()),
                            List.of(),
                            DimensionPadding.ZERO,
                            JigsawStructure.DEFAULT_LIQUID_SETTINGS);
                    Holder.Reference<Structure> holder =
                            Registry.registerForHolder(structures, key, structure);
                    bindValue.invoke(holder, structure);
                    bindTags.invoke(holder, villageTags);
                    added++;
                }
            } finally {
                frozen.set(structures, true);
            }
            log.info("City structures: " + added + "/" + cities.size()
                    + " registered in code (no datapack).");
            return added;
        } catch (Throwable t) {
            log.warning("City structures: registration failed (" + t + "); cities absent.");
            return 0;
        }
    }

    /** Biomes merged into vanilla {@code village_plains}'s allowed set, replacing
     * the datapack's {@code has_structure/village_plains} tag append. Minecraft
     * ships no forest village at all, which left whole correctly-classified
     * continents (eastern North America, most of Europe) settlement-free:
     * measured, this deciduous group plus sunflower_plains is 4.72% of the
     * surface against 10.38% total village-eligible. */
    private static final String[] FOREST_VILLAGE_BIOMES = {
        "sunflower_plains", "flower_forest", "forest", "birch_forest",
        "old_growth_birch_forest", "dark_forest", "cherry_grove",
    };

    /**
     * Let NATURAL plains villages settle temperate forests: swap
     * {@code minecraft:village_plains}'s settings for a copy whose biome set is
     * the original plus {@link #FOREST_VILLAGE_BIOMES}.
     */
    private static void widenNaturalVillages(Registry<Structure> structures,
                                             Registry<Biome> biomes, Logger log) {
        try {
            Optional<Holder.Reference<Structure>> vp = structures.get(ResourceKey.create(
                    Registries.STRUCTURE, Identifier.withDefaultNamespace("village_plains")));
            if (vp.isEmpty()) {
                log.warning("City structures: minecraft:village_plains missing; "
                        + "forest villages not widened.");
                return;
            }
            Structure structure = vp.get().value();
            Field settingsField = Structure.class.getDeclaredField("settings");
            settingsField.setAccessible(true);
            Structure.StructureSettings old =
                    (Structure.StructureSettings) settingsField.get(structure);
            List<Holder<Biome>> merged = new ArrayList<>(old.biomes().stream().toList());
            int addedBiomes = 0;
            for (String id : FOREST_VILLAGE_BIOMES) {
                Optional<Holder.Reference<Biome>> b = biomes.get(
                        ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(id)));
                if (b.isPresent() && !merged.contains(b.get())) {
                    merged.add(b.get());
                    addedBiomes++;
                }
            }
            settingsField.set(structure, new Structure.StructureSettings(
                    HolderSet.direct(List.copyOf(merged)), old.spawnOverrides(),
                    old.step(), old.terrainAdaptation()));
            log.info("City structures: village_plains widened by " + addedBiomes
                    + " forest biomes (natural forest villages).");
        } catch (Throwable t) {
            log.warning("City structures: village_plains widening failed (" + t + ").");
        }
    }

    // -------------------------------------------------------------- anchoring

    /**
     * Fabricate one single-chunk structure set per city and splice it into this
     * world's generator state. Call from WorldInitEvent (before spawn chunks).
     */
    @SuppressWarnings("unchecked")
    public static boolean injectWorld(World world, Logger log) {
        if (anchoringDisabled()) {
            log.info("City anchor: DISABLED via -Dcubeworld.anchorCities=false "
                    + "(raw terrain only, no city villages).");
            return false;
        }
        List<CityAnchor> cities = CityAnchors.load();
        if (cities.isEmpty()) {
            return false;
        }
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            // Build vanilla's positions + derived maps first so nothing later
            // recomputes over our splices (the guard flag flips here).
            state.ensureStructuresGenerated();

            Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);

            Field ringField = ChunkGeneratorStructureState.class.getDeclaredField("ringPositions");
            ringField.setAccessible(true);
            Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> rings =
                    (Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>>) ringField.get(state);
            Field placementsField = ChunkGeneratorStructureState.class.getDeclaredField("placementsForStructure");
            placementsField.setAccessible(true);
            Map<Structure, List<StructurePlacement>> placements =
                    (Map<Structure, List<StructurePlacement>>) placementsField.get(state);
            // possibleStructureSets is an immutable list; replace the field.
            Field setsField = ChunkGeneratorStructureState.class.getDeclaredField("possibleStructureSets");
            setsField.setAccessible(true);
            List<Holder<StructureSet>> sets =
                    new ArrayList<>((List<Holder<StructureSet>>) setsField.get(state));

            int anchored = 0;
            for (CityAnchor c : cities) {
                Optional<Holder.Reference<Structure>> holder = structures.get(keyOf(c));
                if (holder.isEmpty()) {
                    log.warning("City anchor: " + keyOf(c).identifier() + " not registered; skipped.");
                    continue;
                }
                ConcentricRingsStructurePlacement placement = new ConcentricRingsStructurePlacement(
                        Vec3i.ZERO, StructurePlacement.FrequencyReductionMethod.DEFAULT, 1.0F,
                        SALT, Optional.empty(),
                        // distance/spread/count feed only the vanilla ring
                        // search, which never runs: positions are supplied
                        // directly below.
                        32, 3, 1, HolderSet.direct(List.of()));
                rings.put(placement, CompletableFuture.completedFuture(
                        List.of(new ChunkPos(c.x() >> 4, c.z() >> 4))));
                placements.computeIfAbsent(holder.get().value(), k -> new ArrayList<>()).add(placement);
                sets.add(Holder.direct(new StructureSet(holder.get(), placement)));
                anchored++;
            }
            setsField.set(state, sets);

            log.info("City anchor: " + anchored + " cities anchored in '"
                    + world.getName() + "' (per-city structures, WorldInit).");
            return anchored > 0;
        } catch (Throwable t) {
            log.warning("City anchor hook failed (" + t + "); anchored cities absent.");
            return false;
        }
    }
}
