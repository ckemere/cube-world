package com.ckemere.cubeworld.generation;

import com.ckemere.cubeworld.geometry.Vec3;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceRules.ConditionSource;
import net.minecraft.world.level.levelgen.SurfaceRules.RuleSource;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * A rebuild of vanilla's nether surface rule ({@code SurfaceRuleData.nether})
 * whose six 2D noise conditions sample at the sphere-folded cube point instead
 * of raw plane coordinates. Everything else — the biome/vertical/hole/stone-depth
 * structure and the exact block choices — is transcribed faithfully via the
 * public {@link SurfaceRules} factories, so vanilla's own {@code SurfaceSystem}
 * still drives placement; only WHERE the patch noises are sampled changes.
 *
 * <p>Why: the nether terrain and biomes are already folded and seam-continuous,
 * but the surface "skin" (soul_sand vs soul_soil, basalt vs blackstone, nylium
 * vs netherrack, gravel patches) is chosen by 2D noises the vanilla surface
 * system reads at raw coords. At a cube seam, face A's edge column and its
 * partner (face B, mirrored into A's margin) then disagree on the top block —
 * a visible one-block "pop" when a player teleports across the seam. Sampling
 * these noises at the folded point makes both sides agree, so the skin is
 * seam-perfect too. {@link SurfaceRules.Context} is {@code final} and
 * {@link NormalNoise} has a private constructor, so neither can be subclassed;
 * rebuilding the rule with a folded {@link ConditionSource} that reads the
 * public {@code Context.blockX/blockZ} is the clean way in.
 */
public final class NetherSurfaceFold {

    private NetherSurfaceFold() {
    }

    /** The nether surface rule with its noise conditions folded onto the sphere. */
    public static RuleSource foldedNetherRule(HolderGetter<Biome> biomes,
                                              MapSampler sampler, double radius) {
        ConditionSource aboveNetherLavaLevel = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(31), 0);
        ConditionSource aboveNetherLavaSurface = SurfaceRules.yBlockCheck(VerticalAnchor.absolute(32), 0);
        ConditionSource bandBottom = SurfaceRules.yStartCheck(VerticalAnchor.absolute(30), 0);
        ConditionSource bandTop = SurfaceRules.not(SurfaceRules.yStartCheck(VerticalAnchor.absolute(35), 0));
        ConditionSource closeToCeiling = SurfaceRules.yBlockCheck(VerticalAnchor.belowTop(5), 0);
        ConditionSource hole = SurfaceRules.hole();

        // The six folded 2D patch noises (thresholds copied from SurfaceRuleData).
        ConditionSource soulSandLayer = folded(Noises.SOUL_SAND_LAYER, -0.012, sampler, radius);
        ConditionSource gravelLayer = folded(Noises.GRAVEL_LAYER, -0.012, sampler, radius);
        ConditionSource patch = folded(Noises.PATCH, -0.012, sampler, radius);
        ConditionSource netherrackNoise = folded(Noises.NETHERRACK, 0.54, sampler, radius);
        ConditionSource netherWart = folded(Noises.NETHER_WART, 1.17, sampler, radius);
        ConditionSource stateSelector = folded(Noises.NETHER_STATE_SELECTOR, 0.0, sampler, radius);

        RuleSource bedrock = state(Blocks.BEDROCK.defaultBlockState());
        RuleSource netherrack = state(Blocks.NETHERRACK.defaultBlockState());
        RuleSource basalt = state(Blocks.BASALT.defaultBlockState());
        RuleSource blackstone = state(Blocks.BLACKSTONE.defaultBlockState());
        RuleSource gravel = state(Blocks.GRAVEL.defaultBlockState());
        RuleSource soulSand = state(Blocks.SOUL_SAND.defaultBlockState());
        RuleSource soulSoil = state(Blocks.SOUL_SOIL.defaultBlockState());
        RuleSource lava = state(Blocks.LAVA.defaultBlockState());
        RuleSource warpedWart = state(Blocks.WARPED_WART_BLOCK.defaultBlockState());
        RuleSource warpedNylium = state(Blocks.WARPED_NYLIUM.defaultBlockState());
        RuleSource netherWartBlock = state(Blocks.NETHER_WART_BLOCK.defaultBlockState());
        RuleSource crimsonNylium = state(Blocks.CRIMSON_NYLIUM.defaultBlockState());

        RuleSource gravelPatch = SurfaceRules.ifTrue(patch,
                SurfaceRules.ifTrue(bandBottom, SurfaceRules.ifTrue(bandTop, gravel)));

        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.verticalGradient("bedrock_floor",
                        VerticalAnchor.bottom(), VerticalAnchor.aboveBottom(5)), bedrock),
                SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.verticalGradient("bedrock_roof",
                        VerticalAnchor.belowTop(5), VerticalAnchor.top())), bedrock),
                SurfaceRules.ifTrue(closeToCeiling, netherrack),
                SurfaceRules.ifTrue(isBiome(biomes, Biomes.BASALT_DELTAS),
                        SurfaceRules.sequence(
                                SurfaceRules.ifTrue(SurfaceRules.UNDER_CEILING, basalt),
                                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, SurfaceRules.sequence(
                                        gravelPatch,
                                        SurfaceRules.ifTrue(stateSelector, basalt),
                                        blackstone)))),
                SurfaceRules.ifTrue(isBiome(biomes, Biomes.SOUL_SAND_VALLEY),
                        SurfaceRules.sequence(
                                SurfaceRules.ifTrue(SurfaceRules.UNDER_CEILING, SurfaceRules.sequence(
                                        SurfaceRules.ifTrue(stateSelector, soulSand), soulSoil)),
                                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, SurfaceRules.sequence(
                                        gravelPatch,
                                        SurfaceRules.ifTrue(stateSelector, soulSand), soulSoil)))),
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR,
                        SurfaceRules.sequence(
                                SurfaceRules.ifTrue(SurfaceRules.not(aboveNetherLavaSurface),
                                        SurfaceRules.ifTrue(hole, lava)),
                                SurfaceRules.ifTrue(isBiome(biomes, Biomes.WARPED_FOREST),
                                        SurfaceRules.ifTrue(SurfaceRules.not(netherrackNoise),
                                                SurfaceRules.ifTrue(aboveNetherLavaLevel, SurfaceRules.sequence(
                                                        SurfaceRules.ifTrue(netherWart, warpedWart), warpedNylium)))),
                                SurfaceRules.ifTrue(isBiome(biomes, Biomes.CRIMSON_FOREST),
                                        SurfaceRules.ifTrue(SurfaceRules.not(netherrackNoise),
                                                SurfaceRules.ifTrue(aboveNetherLavaLevel, SurfaceRules.sequence(
                                                        SurfaceRules.ifTrue(netherWart, netherWartBlock), crimsonNylium)))))),
                SurfaceRules.ifTrue(isBiome(biomes, Biomes.NETHER_WASTES),
                        SurfaceRules.sequence(
                                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR,
                                        SurfaceRules.ifTrue(soulSandLayer, SurfaceRules.sequence(
                                                SurfaceRules.ifTrue(SurfaceRules.not(hole),
                                                        SurfaceRules.ifTrue(bandBottom, SurfaceRules.ifTrue(bandTop, soulSand))),
                                                netherrack))),
                                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR,
                                        SurfaceRules.ifTrue(aboveNetherLavaLevel,
                                                SurfaceRules.ifTrue(bandTop,
                                                        SurfaceRules.ifTrue(gravelLayer, SurfaceRules.sequence(
                                                                SurfaceRules.ifTrue(aboveNetherLavaSurface, gravel),
                                                                SurfaceRules.ifTrue(SurfaceRules.not(hole), gravel)))))))),
                netherrack);
    }

    @SafeVarargs
    private static ConditionSource isBiome(HolderGetter<Biome> biomes, ResourceKey<Biome>... keys) {
        return SurfaceRules.isBiome(biomes, keys);
    }

    private static RuleSource state(net.minecraft.world.level.block.state.BlockState s) {
        return SurfaceRules.state(s);
    }

    private static ConditionSource folded(ResourceKey<NormalNoise.NoiseParameters> noiseKey,
                                          double min, MapSampler sampler, double radius) {
        return new FoldedNoiseCondition(noiseKey, min, Double.MAX_VALUE, sampler, radius);
    }

    /**
     * A {@link ConditionSource} equivalent to vanilla's {@code noise_threshold}
     * (2D), but sampling the real {@link NormalNoise} at the sphere-folded cube
     * point of {@code (blockX, blockZ)} so the result is continuous across cube
     * seams. Config is immutable and shared across chunks; the per-chunk
     * {@link SurfaceRules.Condition} it returns caches the last column's value.
     */
    private static final class FoldedNoiseCondition implements ConditionSource {
        private final ResourceKey<NormalNoise.NoiseParameters> noiseKey;
        private final double min;
        private final double max;
        private final MapSampler sampler;
        private final double radius;

        FoldedNoiseCondition(ResourceKey<NormalNoise.NoiseParameters> noiseKey, double min,
                             double max, MapSampler sampler, double radius) {
            this.noiseKey = noiseKey;
            this.min = min;
            this.max = max;
            this.sampler = sampler;
            this.radius = radius;
        }

        @Override
        public SurfaceRules.Condition apply(SurfaceRules.Context ctx) {
            NormalNoise noise = ctx.randomState.getOrCreateNoise(noiseKey);
            return new SurfaceRules.Condition() {
                private long lastKey = Long.MIN_VALUE;
                private boolean lastVal;

                @Override
                public boolean test() {
                    int bx = ctx.blockX;
                    int bz = ctx.blockZ;
                    long key = ((long) bx << 32) ^ (bz & 0xffffffffL);
                    if (key != lastKey) {
                        lastKey = key;
                        double v;
                        Vec3 p = sampler.cubePointAt(bx + 0.5, bz + 0.5);
                        if (p == null) {
                            v = noise.getValue(bx, 0.0, bz); // off the net: raw (masked anyway)
                        } else {
                            double n = Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z());
                            double r = radius / (n < 1e-9 ? 1.0 : n);
                            v = noise.getValue(p.x() * r, p.y() * r, p.z() * r);
                        }
                        lastVal = v >= min && v <= max;
                    }
                    return lastVal;
                }
            };
        }

        @Override
        public MapCodec<? extends ConditionSource> codec() {
            // Built in code and installed via reflection, never serialized.
            throw new UnsupportedOperationException("FoldedNoiseCondition is not serializable");
        }
    }
}
