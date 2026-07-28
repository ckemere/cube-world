package com.ckemere.cubeworld.generation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Reconnaissance for the "leaf replacement" generator rewrite: walks a live
 * {@link NoiseRouter} and reports every node a {@code mapAll} visitor can reach,
 * resolving noise leaves to their REGISTRY KEY rather than guessing from value
 * ranges the way {@link SphereDensity}'s {@code isFactorSpline} does.
 *
 * <p>The rewrite depends on two properties that are cheap to assert but
 * expensive to be wrong about, so this measures both instead of trusting a
 * source reading:
 *
 * <ol>
 *   <li><b>Leaves are reachable inside splines.</b> {@code Spline.mapChildren}
 *       maps its coordinates, so a visitor applied to the router should see the
 *       continentalness/erosion/ridge nodes MORE than once — once for the
 *       router's own slot and once per spline (offset, factor, jaggedness) that
 *       reads them. A count of 1 would mean splines hold private copies and the
 *       whole approach is dead.</li>
 *   <li><b>Leaves are identifiable by key.</b> {@code RandomState}'s wiring
 *       rebuilds every {@code NoiseHolder} as {@code new NoiseHolder(noiseData,
 *       instantiate)}, preserving the {@code Holder}, so {@code unwrapKey()}
 *       should resolve. If it comes back empty we are stuck with fingerprinting.</li>
 * </ol>
 *
 * <p>Read-only: the visitor returns every node unchanged, so running this cannot
 * disturb generation.
 */
public final class RouterProbe {

    private RouterProbe() {
    }

    /** Multi-line report for {@code /cubeworld routerprobe}. */
    public static String report(World world) {
        RandomState rs;
        try {
            rs = ((CraftWorld) world).getHandle().getChunkSource().randomState();
        } catch (Throwable t) {
            return "routerprobe: no RandomState (" + t + ")";
        }
        if (rs == null) {
            return "routerprobe: no RandomState for '" + world.getName() + "'";
        }
        // TWO routers matter and they look nothing alike:
        //  - rs.router() is the LIVE one, already folded by SphereRouterHook, so
        //    every noise leaf is hidden behind a SphereDensity.Remap (whose
        //    mapChildren returns `this` for idempotence). Walking it tells us
        //    what our current hook did, not what vanilla offers.
        //  - the generator settings' router is vanilla's own, pre-fold. That is
        //    what a leaf-replacement visitor would actually be applied to, so
        //    that is where the key-resolution question has to be answered.
        return walk("LIVE (folded)", rs.router())
                + "\n" + walk("VANILLA (generator settings, pre-fold)", settingsRouter(world));
    }

    private static NoiseRouter settingsRouter(World world) {
        try {
            net.minecraft.world.level.chunk.ChunkGenerator gen =
                    ((CraftWorld) world).getHandle().getChunkSource().getGenerator();
            if (gen instanceof org.bukkit.craftbukkit.generator.CustomChunkGenerator ccg) {
                gen = ccg.getDelegate();
            }
            if (gen instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator nbcg) {
                return nbcg.generatorSettings().value().noiseRouter();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    private static String walk(String label, NoiseRouter router) {
        if (router == null) {
            return "== " + label + " ==\n   unavailable\n";
        }

        // Count every node the visitor reaches. Identity map so two structurally
        // equal nodes are still counted separately -- we care about how many
        // distinct objects exist, and how often each is visited.
        Map<String, Integer> byClass = new LinkedHashMap<>();
        Map<String, Integer> noiseKeyHits = new LinkedHashMap<>();
        IdentityHashMap<DensityFunction, Integer> seen = new IdentityHashMap<>();
        List<String> notes = new ArrayList<>();

        DensityFunction.Visitor counting = new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction node) {
                seen.merge(node, 1, Integer::sum);
                String cls = node.getClass().getSimpleName();
                byClass.merge(cls, 1, Integer::sum);
                String key = noiseKeyOf(node);
                if (key != null) {
                    noiseKeyHits.merge(key, 1, Integer::sum);
                }
                return node;                      // read-only
            }
        };
        router.mapAll(counting);

        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(label).append(" ==\n");
        sb.append("distinct nodes reached: ").append(seen.size())
          .append("   total visits: ").append(seen.values().stream().mapToInt(Integer::intValue).sum())
          .append('\n');

        sb.append("-- node classes --\n");
        byClass.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(20)
                .forEach(e -> sb.append(String.format("   %-28s %d%n", e.getKey(), e.getValue())));

        sb.append("-- noise leaves by REGISTRY KEY (visits) --\n");
        if (noiseKeyHits.isEmpty()) {
            sb.append("   NONE RESOLVED -- unwrapKey() empty; key-based replacement is NOT viable\n");
        } else {
            noiseKeyHits.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> sb.append(String.format("   %-46s %d%n", e.getKey(), e.getValue())));
        }

        // The three that decide whether the rewrite works at all.
        sb.append("-- verdict --\n");
        for (String k : new String[] {"minecraft:continentalness", "minecraft:erosion", "minecraft:ridge"}) {
            int n = noiseKeyHits.getOrDefault(k, 0);
            sb.append(String.format("   %-28s visits=%d  %s%n", k, n,
                    n > 1 ? "REACHED INSIDE SPLINES (replaceable)"
                          : n == 1 ? "only once -- splines may hold private copies"
                                   : "NOT FOUND"));
        }
        for (String s : notes) {
            sb.append("   ").append(s).append('\n');
        }
        return sb.toString();
    }

    /**
     * The registry key of a node's noise, or null if it has none. Works for both
     * {@code Noise} and {@code ShiftedNoise} (and anything else exposing a
     * {@code noise()} accessor returning a {@code NoiseHolder}). Both record
     * types are non-public, so the accessor is reached reflectively.
     */
    static String noiseKeyOf(DensityFunction node) {
        try {
            Method m = node.getClass().getMethod("noise");
            m.setAccessible(true);
            Object holder = m.invoke(node);
            if (!(holder instanceof DensityFunction.NoiseHolder nh)) {
                return null;
            }
            Holder<net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters> data =
                    nh.noiseData();
            // 26.2: ResourceKey exposes identifier(); the pre-fork location() is gone.
            return data.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
