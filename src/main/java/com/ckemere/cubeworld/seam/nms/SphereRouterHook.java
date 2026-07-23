package com.ckemere.cubeworld.seam.nms;

import com.ckemere.cubeworld.generation.MapSampler;
import com.ckemere.cubeworld.generation.SphereDensity;
import java.lang.reflect.Field;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Replaces the world's terrain {@link NoiseRouter} with a sphere-folded copy
 * ({@link SphereDensity}), so vanilla's own generator places every block —
 * terrain shape, surface rules, aquifers, lava, density caves and ore veins —
 * but sampled on the cube's sphere embedding, which makes it seam-consistent
 * for free. Must run before the world's spawn chunks generate (wired on
 * {@code WorldInitEvent}).
 *
 * <p>{@code RandomState.router} is {@code private final}; modern reflection
 * cannot rebind a final reference, so we use {@link sun.misc.Unsafe} the way
 * the platform itself does for late field patching. The noises inside the
 * router are already instantiated, so folding wraps the live noise nodes.
 */
public final class SphereRouterHook {

    private SphereRouterHook() {
    }

    public static boolean install(World world, MapSampler sampler, int faceSize,
                                  boolean earthHeight,
                                  com.ckemere.cubeworld.generation.EarthData earth, Logger log) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            RandomState rs = level.getChunkSource().randomState();
            if (rs == null) {
                log.warning("Sphere router hook: no RandomState yet for '" + world.getName() + "'.");
                return false;
            }
            NoiseRouter folded = SphereDensity.forSampler(sampler, faceSize, earthHeight, earth)
                    .fold(rs.router());
            putFinalObject(rs, RandomState.class.getDeclaredField("router"), folded);
            if (earthHeight) {
                disableAquifers(level, log);
            }
            log.info("Sphere router hook: vanilla terrain folded onto the cube for '"
                    + world.getName() + "'"
                    + (earthHeight ? " (Earth elevation)" : " (vanilla noise)") + ".");
            return true;
        } catch (Throwable t) {
            log.warning("Sphere router hook failed (" + t + "); demo terrain remains.");
            return false;
        }
    }

    /**
     * Fold vanilla's NETHER terrain onto the cube. Unlike the overworld, the
     * nether's whole terrain shape is the {@code BlendedNoise} 3D node (there is
     * no macro continents/erosion/depth field), so {@link SphereDensity#forNether}
     * folds that node too. We also rebind {@code RandomState.sampler} — the biome
     * {@link net.minecraft.world.level.biome.Climate.Sampler} is a separate final
     * field built from the UNFOLDED router at construction, so the MultiNoise
     * nether biome source would otherwise pick biomes on raw plane coords and
     * cliff at seams. Rebuilt from the folded router it is seam-consistent.
     * No aquifer disable: the nether uses the global lava fluid picker (its
     * settings already have aquifers off), which gives the lava sea for free.
     */
    public static boolean installNether(World world, MapSampler sampler, int faceSize, Logger log) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            RandomState rs = level.getChunkSource().randomState();
            if (rs == null) {
                log.warning("Nether router hook: no RandomState yet for '" + world.getName() + "'.");
                return false;
            }
            NoiseRouter folded = SphereDensity.forNether(sampler, faceSize).fold(rs.router());
            putFinalObject(rs, RandomState.class.getDeclaredField("router"), folded);
            // Rebind the biome climate sampler from the folded router so vanilla's
            // MultiNoise nether biome source samples the sphere-folded point too.
            try {
                java.util.List<net.minecraft.world.level.biome.Climate.ParameterPoint> spawnTarget =
                        netherSpawnTarget(level);
                net.minecraft.world.level.biome.Climate.Sampler foldedSampler =
                        new net.minecraft.world.level.biome.Climate.Sampler(
                                folded.temperature(), folded.vegetation(), folded.continents(),
                                folded.erosion(), folded.depth(), folded.ridges(), spawnTarget);
                putFinalObject(rs, RandomState.class.getDeclaredField("sampler"), foldedSampler);
                log.info("Nether biome sampler folded (seam-consistent MultiNoise biomes).");
            } catch (Throwable t) {
                log.warning("Nether biome sampler fold failed (" + t
                        + "); terrain is folded but biomes may cliff at seams.");
            }
            // Fold the surface rule's patch noises too, so the top-block skin
            // (soul_sand/soul_soil, basalt/blackstone, nylium/netherrack, gravel)
            // is seam-consistent as well — otherwise it pops a block at a seam.
            try {
                foldNetherSurfaceRule(level, sampler, faceSize, log);
            } catch (Throwable t) {
                log.warning("Nether surface-rule fold failed (" + t
                        + "); terrain/biomes folded but the surface skin may pop at seams.");
            }
            log.info("Nether router hook: vanilla nether terrain folded onto the cube for '"
                    + world.getName() + "'.");
            return true;
        } catch (Throwable t) {
            log.warning("Nether router hook failed (" + t + "); demo nether terrain remains.");
            return false;
        }
    }

    /** The nether NoiseGeneratorSettings' spawn target list (empty for the nether). */
    private static java.util.List<net.minecraft.world.level.biome.Climate.ParameterPoint>
            netherSpawnTarget(ServerLevel level) {
        net.minecraft.world.level.chunk.ChunkGenerator gen =
                level.getChunkSource().getGenerator();
        if (gen instanceof org.bukkit.craftbukkit.generator.CustomChunkGenerator ccg) {
            gen = ccg.getDelegate();
        }
        if (gen instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator nbcg) {
            return nbcg.generatorSettings().value().spawnTarget();
        }
        return java.util.List.of();
    }

    /**
     * Rebind the nether generator's settings to a copy whose surface rule is the
     * sphere-folded nether rule ({@link com.ckemere.cubeworld.generation.NetherSurfaceFold}),
     * so the surface system's patch noises sample the folded cube point and the
     * top-block skin is seam-consistent. Same fresh-generator + delegate-rebind
     * trick as {@link #disableAquifers}. Aquifers stay as-is (nether keeps its
     * native lava picker).
     */
    private static void foldNetherSurfaceRule(ServerLevel level, MapSampler sampler,
                                              int faceSize, Logger log) throws Exception {
        net.minecraft.world.level.chunk.ChunkGenerator gen =
                level.getChunkSource().getGenerator();
        org.bukkit.craftbukkit.generator.CustomChunkGenerator ccg = null;
        if (gen instanceof org.bukkit.craftbukkit.generator.CustomChunkGenerator c) {
            ccg = c;
            gen = c.getDelegate();
        }
        if (!(gen instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator nbcg)) {
            log.warning("Nether surface fold: not a noise generator.");
            return;
        }
        net.minecraft.world.level.levelgen.NoiseGeneratorSettings old =
                nbcg.generatorSettings().value();
        net.minecraft.core.HolderGetter<net.minecraft.world.level.biome.Biome> biomes =
                level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        double radius = com.ckemere.cubeworld.generation.SphereDensity.radiusFor(faceSize);
        net.minecraft.world.level.levelgen.SurfaceRules.RuleSource folded =
                com.ckemere.cubeworld.generation.NetherSurfaceFold.foldedNetherRule(
                        biomes, sampler, radius);
        net.minecraft.world.level.levelgen.NoiseGeneratorSettings copy =
                new net.minecraft.world.level.levelgen.NoiseGeneratorSettings(
                        old.noiseSettings(), old.defaultBlock(), old.defaultFluid(),
                        old.noiseRouter(), folded, old.spawnTarget(),
                        old.seaLevel(), old.disableMobGeneration(),
                        old.aquifersEnabled(), old.oreVeinsEnabled(), old.useLegacyRandomSource());
        net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator fresh =
                new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
                        nbcg.getBiomeSource(), net.minecraft.core.Holder.direct(copy));
        if (ccg != null) {
            putFinalObject(ccg, org.bukkit.craftbukkit.generator.CustomChunkGenerator.class
                    .getDeclaredField("delegate"), fresh);
        }
        log.info("Nether surface rule folded onto the cube (seam-consistent skin).");
    }

    /**
     * Disable aquifers so vanilla's global fluid picker fills every open space
     * below sea level with water AND never schedules a fluid update for it
     * (Aquifer.createDisabled.shouldScheduleFluidUpdate() == false), so ocean
     * water is consistent and never flows/drains. Swap the generator's settings
     * Holder for a copy with aquifersEnabled=false (the flag is a record
     * component and can't be poked in place).
     */
    private static void disableAquifers(ServerLevel level, Logger log) {
        try {
            net.minecraft.world.level.chunk.ChunkGenerator gen =
                    level.getChunkSource().getGenerator();
            if (gen instanceof org.bukkit.craftbukkit.generator.CustomChunkGenerator ccg) {
                gen = ccg.getDelegate();
            }
            if (!(gen instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator nbcg)) {
                log.warning("Aquifer disable: not a noise generator.");
                return;
            }
            net.minecraft.world.level.levelgen.NoiseGeneratorSettings old =
                    nbcg.generatorSettings().value();
            net.minecraft.world.level.levelgen.NoiseGeneratorSettings copy =
                    new net.minecraft.world.level.levelgen.NoiseGeneratorSettings(
                            old.noiseSettings(), old.defaultBlock(), old.defaultFluid(),
                            old.noiseRouter(), old.surfaceRule(), old.spawnTarget(),
                            old.seaLevel(), old.disableMobGeneration(),
                            false, old.oreVeinsEnabled(), old.useLegacyRandomSource());
            // Swapping the delegate's `settings` field alone doesn't take (the
            // final field read is cached), so build a FRESH generator with the
            // disabled settings and rebind the CustomChunkGenerator's delegate.
            net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator fresh =
                    new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
                            nbcg.getBiomeSource(), net.minecraft.core.Holder.direct(copy));
            putFinalObject(level.getChunkSource().getGenerator(),
                    org.bukkit.craftbukkit.generator.CustomChunkGenerator.class
                            .getDeclaredField("delegate"),
                    fresh);
            log.info("Aquifers disabled (ocean water is now stable source, no fluid ticks).");
        } catch (Throwable t) {
            log.warning("Could not disable aquifers (" + t + "); oceans may show artifacts.");
        }
    }

    /** Rebind a final object field via Unsafe (final blocks plain reflection). */
    private static void putFinalObject(Object target, Field field, Object value) throws Exception {
        sun.misc.Unsafe unsafe = unsafe();
        long offset = unsafe.objectFieldOffset(field);
        unsafe.putObject(target, offset, value);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }
}
