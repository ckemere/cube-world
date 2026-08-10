package com.ckemere.cubeworld.seam.nms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Diagnostic: replay vanilla's village decision for one chunk with the REAL
 * engine objects, so the offline predictor
 * ({@code tools/playermap/structures/village_placement.py}) can be diffed
 * against ground truth instead of against outcomes.
 *
 * <p>Everything that decides a village is printed for every variant, in the
 * order {@link ChunkGenerator#createStructures} tries them: the rotation and
 * town-centre template the jigsaw drew, that template's bounding box, the
 * centre vanilla derives from it, the height the generator reports there, the
 * quart cell the biome question lands in, the biome, and the verdict. The
 * variant/rotation/template line comes from the pieces the engine actually
 * built, not from a reimplementation.
 *
 * <p>Cheap and chunk-safe: the whole chain reads
 * {@code ChunkGenerator.getBaseHeight} and the biome source, neither of which
 * touches a chunk. Nothing here loads or generates terrain.
 *
 * <p>Op-only (it is not in {@code CubeWorldCommand.PUBLIC_SUBCOMMANDS}).
 */
public final class VillagePlacementProbe {

    private VillagePlacementProbe() {
    }

    /**
     * One line per village variant tried, plus a RESULT line.
     *
     * <p>Format is deliberately terse and machine-parsable — it is meant to be
     * driven over RCON and diffed against the Python model.
     */
    public static List<String> probe(World world, int cx, int cz) {
        List<String> out = new ArrayList<>();
        ServerLevel level = ((CraftWorld) world).getHandle();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        RandomState randomState = state.randomState();
        RegistryAccess registries = level.registryAccess();
        StructureTemplateManager templates = level.getServer().getStructureManager();
        Registry<StructureSet> sets = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        StructureSet villages = sets.getValueOrThrow(BuiltinStructureSets.VILLAGES);
        ChunkPos chunkPos = new ChunkPos(cx, cz);
        long seed = state.getLevelSeed();

        boolean candidate = villages.placement().isStructureChunk(state, cx, cz);
        out.add(String.format(Locale.ROOT, "chunk (%d,%d) seed=%d candidate=%b",
                cx, cz, seed, candidate));

        // ChunkGenerator.createStructures: weighted draw without replacement over
        // the set's entries, from a WorldgenRandom seeded per chunk.
        List<StructureSet.StructureSelectionEntry> options =
                new ArrayList<>(villages.structures());
        net.minecraft.world.level.levelgen.WorldgenRandom order =
                new net.minecraft.world.level.levelgen.WorldgenRandom(
                        new net.minecraft.world.level.levelgen.LegacyRandomSource(0L));
        order.setLargeFeatureSeed(seed, cx, cz);
        int total = 0;
        for (StructureSet.StructureSelectionEntry option : options) {
            total += option.weight();
        }

        String result = "none";
        while (!options.isEmpty()) {
            int choice = order.nextInt(total);
            int index = 0;
            for (StructureSet.StructureSelectionEntry option : options) {
                choice -= option.weight();
                if (choice < 0) {
                    break;
                }
                index++;
            }
            StructureSet.StructureSelectionEntry picked = options.get(index);
            boolean valid = describe(out, picked, registries, generator, randomState,
                    templates, seed, chunkPos, level);
            if (valid) {
                result = picked.structure().getRegisteredName();
                break;
            }
            options.remove(index);
            total -= picked.weight();
        }
        out.add("RESULT " + result);
        return out;
    }

    /** One variant: draw, box, centre, height, quart cell, biome, verdict. */
    private static boolean describe(List<String> out,
                                    StructureSet.StructureSelectionEntry entry,
                                    RegistryAccess registries,
                                    ChunkGenerator generator,
                                    RandomState randomState,
                                    StructureTemplateManager templates,
                                    long seed,
                                    ChunkPos chunkPos,
                                    ServerLevel level) {
        String name = entry.structure().getRegisteredName();
        Structure structure = entry.structure().value();
        if (!(structure instanceof JigsawStructure jigsaw)) {
            out.add(name + " not-a-jigsaw");
            return false;
        }
        Structure.GenerationContext context = new Structure.GenerationContext(
                registries, generator, generator.getBiomeSource(), randomState, templates,
                seed, chunkPos, level, holder -> true);
        Optional<Structure.GenerationStub> stub = jigsaw.findGenerationPoint(context);
        if (stub.isEmpty()) {
            out.add(name + " no-generation-point");
            return false;
        }
        net.minecraft.core.BlockPos pos = stub.get().position();

        // The centre piece the engine built: its element and rotation are what the
        // Python model has to reproduce. (X/Z of its box are untouched by the
        // vertical move that follows the draw.)
        String element = "?";
        String rotation = "?";
        String box = "?";
        List<StructurePiece> pieces = stub.get().getPiecesBuilder().build().pieces();
        if (!pieces.isEmpty() && pieces.get(0) instanceof PoolElementStructurePiece centre) {
            element = centre.getElement().toString();
            rotation = centre.getRotation().name();
            BoundingBox b = centre.getBoundingBox();
            box = String.format(Locale.ROOT, "[%d,%d..%d,%d]", b.minX(), b.minZ(), b.maxX(), b.maxZ());
        }

        int height = generator.getFirstFreeHeight(pos.getX(), pos.getZ(),
                Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()),
                QuartPos.fromBlock(pos.getZ()), randomState.sampler());
        boolean ok = structure.biomes().contains(biome);
        out.add(String.format(Locale.ROOT,
                "%s rot=%s tpl=%s box=%s pos=(%d,%d,%d) firstFree=%d quart=(%d,%d,%d) biome=%s ok=%b",
                name, rotation, element, box,
                pos.getX(), pos.getY(), pos.getZ(), height,
                QuartPos.toBlock(QuartPos.fromBlock(pos.getX())),
                QuartPos.toBlock(QuartPos.fromBlock(pos.getY())),
                QuartPos.toBlock(QuartPos.fromBlock(pos.getZ())),
                biome.unwrapKey().map(k -> k.identifier().toString()).orElse("?"), ok));
        return ok;
    }
}
