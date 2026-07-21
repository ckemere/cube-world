package com.ckemere.cubeworld.seam.nms;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

/**
 * Anchors an extra-large village at each historical city. The
 * {@code cubeworld:*} datapack defines six jigsaw structures —
 * {plains,desert,savanna} x {large size 11, huge size 14} — each with a broad
 * biome set (so it is never rejected at a forced site) and each carried by a
 * structure set with a {@code concentric_rings} placement (count 0, no vanilla
 * positions). This hook injects the real city chunk positions into those
 * placements' ring maps — the exact mechanism {@link StrongholdSphereHook}
 * uses — so vanilla generates the right-size, right-style village at every city
 * while the stock {@code minecraft:villages} set keeps sprinkling normal
 * villages everywhere else.
 *
 * <p>Runs on the first tick (after spawn generation, so
 * {@code generatePositions} has already built {@code placementsForStructure}),
 * immediately after the stronghold hook.
 */
public final class VillageAnchorHook {

    private static final String NS = "cubeworld";
    private static final String RESOURCE = "cities_anchor.csv";

    private VillageAnchorHook() {
    }

    public static boolean install(World world, Plugin plugin, Logger log) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            state.ensureStructuresGenerated();      // populate placements + ring maps (idempotent)

            Map<String, List<ChunkPos>> byCombo = loadCities(plugin, log);
            if (byCombo.isEmpty()) {
                log.warning("Village anchor: no cities loaded from " + RESOURCE + ".");
                return false;
            }

            Field ringField = ChunkGeneratorStructureState.class.getDeclaredField("ringPositions");
            ringField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> ringMap =
                    (Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>>)
                            ringField.get(state);

            int sets = 0;
            int placed = 0;
            for (StructureSet set : level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)) {
                if (!(set.placement() instanceof ConcentricRingsStructurePlacement crp)) {
                    continue;
                }
                for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                    String name = entry.structure().getRegisteredName();   // "cubeworld:plains_large"
                    if (!name.startsWith(NS + ":")) {
                        continue;
                    }
                    List<ChunkPos> positions = byCombo.get(name.substring(NS.length() + 1));
                    if (positions == null || positions.isEmpty()) {
                        continue;
                    }
                    ringMap.put(crp, CompletableFuture.completedFuture(positions));
                    sets++;
                    placed += positions.size();
                }
            }
            if (sets == 0) {
                log.warning("Village anchor: no cubeworld structure sets found — is the "
                        + "cities datapack loaded? (needs to be present at world load)");
                return false;
            }
            log.info("Village anchor: " + placed + " cities anchored across " + sets + " tiers.");
            return true;
        } catch (Throwable t) {
            log.warning("Village anchor hook failed (" + t + "); anchored cities absent.");
            return false;
        }
    }

    /** cities_anchor.csv (worldX,worldZ,pool,size) -> "{pool}_{size}" -> chunk positions. */
    private static Map<String, List<ChunkPos>> loadCities(Plugin plugin, Logger log) {
        Map<String, List<ChunkPos>> out = new HashMap<>();
        try (InputStream in = plugin.getResource(RESOURCE)) {
            if (in == null) {
                log.warning("Village anchor: bundled " + RESOURCE + " missing.");
                return out;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] p = line.split(",");
                    if (p.length < 4) {
                        continue;
                    }
                    int x = Integer.parseInt(p[0].trim());
                    int z = Integer.parseInt(p[1].trim());
                    String combo = p[2].trim() + "_" + p[3].trim();
                    out.computeIfAbsent(combo, k -> new ArrayList<>())
                            .add(new ChunkPos(x >> 4, z >> 4));
                }
            }
        } catch (Exception e) {
            log.warning("Village anchor: failed reading " + RESOURCE + " (" + e + ").");
        }
        return out;
    }
}
