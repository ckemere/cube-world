package com.ckemere.cubeworld.generation;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.block.CraftBiome;

/**
 * Maps a 6-parameter vanilla climate point to the biome vanilla itself would
 * choose, by querying the real overworld {@link MultiNoiseBiomeSource}
 * parameter list. This is the "hook real code" path for biomes: instead of a
 * hand-rolled climate-to-biome table, we hand vanilla the climate and take the
 * biome it returns — so all ~55 overworld biomes (and the cave biomes, at
 * depth) are reachable, exactly as vanilla partitions the climate space.
 */
public final class VanillaBiomeMapper {

    private final MultiNoiseBiomeSource source;

    public VanillaBiomeMapper() {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        Registry<MultiNoiseBiomeSourceParameterList> reg =
                server.registryAccess().lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        Holder<MultiNoiseBiomeSourceParameterList> preset =
                reg.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        this.source = MultiNoiseBiomeSource.createFromPreset(preset);
    }

    /** All six climate params in vanilla units (roughly [-1, 1]). */
    public Biome biome(double temperature, double humidity, double continentalness,
                       double erosion, double depth, double weirdness) {
        Holder<net.minecraft.world.level.biome.Biome> h = source.getNoiseBiome(
                Climate.target((float) temperature, (float) humidity, (float) continentalness,
                        (float) erosion, (float) depth, (float) weirdness));
        return CraftBiome.minecraftHolderToBukkit(h);
    }

    /** Every biome this mapper can return — for BiomeProvider.getBiomes(). */
    public java.util.List<Biome> possibleBiomes() {
        java.util.List<Biome> out = new java.util.ArrayList<>();
        for (Holder<net.minecraft.world.level.biome.Biome> h : source.possibleBiomes()) {
            out.add(CraftBiome.minecraftHolderToBukkit(h));
        }
        return out;
    }
}
