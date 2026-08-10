package com.ckemere.cubeworld.city;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * What a village repair <em>would</em> do, computed without touching the world.
 *
 * <p>The existing {@link VillageGroundFixer} decides and mutates in the same pass, so
 * the only way to see its reasoning is to look at the damage afterwards. Separating the
 * plan from the application means a rule can be rendered — orange for a block we would
 * add, yellow for one we would remove, red for a piece we would refuse — and argued
 * about before it ever runs for real.
 *
 * <p>The rules implemented here are deliberately <em>local</em>: every added block copies
 * something the structure already contains, or is plain dirt under a path. Nothing is
 * invented from a substrate guess, and nothing is landscaped. In particular a tree gets
 * at most ONE column extended — its actual trunk — where the old code plinthed every
 * column whose lowest block happened to be a log.
 */
public final class VillageFixPlan {

    /** A single planned change. */
    public record Change(int x, int y, int z, Material material, Kind kind) { }

    public enum Kind { ADD, REMOVE, REJECT }

    /** Per-piece result, so the preview can step through one structure at a time. */
    public static final class PiecePlan {
        public final String template;
        public final String category;
        public final BoundingBox box;
        public final List<Change> changes = new ArrayList<>();
        /** Set when a rule says the piece should not have been placed at all. */
        public String rejectReason;
        /** Columns whose fill would pass through water (the coastal question). */
        public int throughWater;
        /** Columns skipped because their lowest block is an overhang, not foundation. */
        public int overhangs;

        PiecePlan(String template, String category, BoundingBox box) {
            this.template = template;
            this.category = category;
            this.box = box;
        }

        public long adds() {
            return changes.stream().filter(c -> c.kind() == Kind.ADD).count();
        }

        public long removes() {
            return changes.stream().filter(c -> c.kind() == Kind.REMOVE).count();
        }
    }

    /** How far down we will chase the ground before declaring a column unsupportable. */
    public static final int MAX_DROP = 32;

    private VillageFixPlan() {
    }

    // ------------------------------------------------------------------ helpers

    /** True for the blocks a structure piece is made of, as opposed to terrain. */
    private static boolean isStructural(Material m) {
        return m.isSolid();
    }

    /**
     * A full solid cube — the only thing whose column genuinely needs ground under it.
     *
     * <p>Counting every structure block with air beneath it overstates the problem
     * badly: a roof eave, a porch stair, a fence rail and a torch all have air below by
     * design. On one house that inflated the figure from a handful of real unsupported
     * foundation blocks to 104, of which 98 were {@code OAK_STAIRS} — an overhang doing
     * exactly what an overhang should. Extending those downward is also what would have
     * built a 90-block column of stairs, so the same predicate that fixes the metric
     * fixes the rule.
     */
    static boolean isFoundationBlock(Material m) {
        if (!m.isSolid() || !m.isOccluding()) {
            return false;
        }
        String n = m.name();
        return !(n.endsWith("_STAIRS") || n.endsWith("_SLAB") || n.endsWith("_FENCE")
                || n.endsWith("_GATE") || n.endsWith("_DOOR") || n.endsWith("_WALL")
                || n.endsWith("_SIGN") || n.endsWith("_BED") || n.endsWith("_LEAVES")
                || n.endsWith("_TRAPDOOR") || n.endsWith("_CARPET"));
    }

    /** True where a fill may pass: air, cave air, or water (the coastal case). */
    private static boolean passable(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.WATER;
    }

    /** Lowest y in [minY,maxY] holding a solid block in this column, or MIN_VALUE. */
    private static int lowestSolidInBox(World w, int x, int z, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            if (w.getBlockAt(x, y, z).getType().isSolid()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * First solid block strictly below {@code fromY}, and whether the drop crossed
     * water. Returns MIN_VALUE if nothing solid within {@link #MAX_DROP}.
     */
    private static int groundBelow(World w, int x, int z, int fromY, boolean[] wetOut) {
        boolean wet = false;
        for (int n = 1, y = fromY - 1; n <= MAX_DROP && y > w.getMinHeight(); n++, y--) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (m == Material.WATER) {
                wet = true;
            }
            if (!passable(m)) {
                wetOut[0] = wet;
                return y;
            }
        }
        wetOut[0] = wet;
        return Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ the rules

    /**
     * Floating path: fill the air beneath a street block with plain dirt.
     * Streets are filled to their footprint exactly — a skirt spills dirt sideways off
     * the path, and a street box already reaches the shore.
     */
    static void planStreet(World w, PiecePlan p) {
        BoundingBox bb = p.box;
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                int floor = lowestSolidInBox(w, x, z, bb.minY(), bb.maxY());
                if (floor == Integer.MIN_VALUE) {
                    continue;
                }
                boolean[] wet = new boolean[1];
                int ground = groundBelow(w, x, z, floor, wet);
                if (ground == Integer.MIN_VALUE) {
                    continue;                       // nothing to stand on within MAX_DROP
                }
                if (wet[0]) {
                    p.throughWater++;
                }
                for (int y = ground + 1; y < floor; y++) {
                    p.changes.add(new Change(x, y, z, Material.DIRT, Kind.ADD));
                }
            }
        }
    }

    /**
     * Floating foundation: repeat the piece's own lowest block downward until it meets
     * the ground. Copying the foundation block is the whole point — it keeps the plinth
     * made of the same material the builder used, instead of a guessed substrate with an
     * invented grass cap.
     *
     * <p>If a column cannot reach ground within {@link #MAX_DROP} the piece is marked
     * REJECT rather than left on stilts.
     */
    static void planBuilding(World w, PiecePlan p) {
        BoundingBox bb = p.box;
        int unsupported = 0;
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                int floor = lowestSolidInBox(w, x, z, bb.minY(), bb.maxY());
                if (floor == Integer.MIN_VALUE) {
                    continue;
                }
                Material foundation = w.getBlockAt(x, floor, z).getType();
                if (!isFoundationBlock(foundation)) {
                    p.overhangs++;              // eave, stair, fence: air below is correct
                    continue;
                }
                boolean[] wet = new boolean[1];
                int ground = groundBelow(w, x, z, floor, wet);
                if (ground == Integer.MIN_VALUE) {
                    unsupported++;
                    continue;
                }
                if (wet[0]) {
                    p.throughWater++;
                }
                for (int y = ground + 1; y < floor; y++) {
                    p.changes.add(new Change(x, y, z, foundation, Kind.ADD));
                }
            }
        }
        if (unsupported > 0) {
            p.rejectReason = unsupported + " column(s) find no ground within " + MAX_DROP;
        }
    }

    /**
     * Floating tree: extend ONLY the trunk. The trunk is the bottom-most vertically
     * oriented log — axis=y — which distinguishes a real trunk from the horizontal logs
     * some village trees use as branches or decoration. Exactly one column is extended,
     * with the trunk's own material; the canopy is left hanging as vanilla drew it.
     */
    static void planTree(World w, PiecePlan p) {
        BoundingBox bb = p.box;
        int bestY = Integer.MAX_VALUE;
        int bx = 0;
        int bz = 0;
        Material trunk = null;
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                for (int y = bb.minY(); y <= bb.maxY(); y++) {
                    org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                    if (!isVerticalLog(b)) {
                        continue;
                    }
                    if (y < bestY) {
                        bestY = y;
                        bx = x;
                        bz = z;
                        trunk = b.getType();
                    }
                    break;                          // lowest in this column is enough
                }
            }
        }
        if (trunk == null) {
            return;                                 // no vertical trunk: leave it alone
        }
        boolean[] wet = new boolean[1];
        int ground = groundBelow(w, bx, bz, bestY, wet);
        if (ground == Integer.MIN_VALUE) {
            p.rejectReason = "trunk at (" + bx + "," + bestY + "," + bz
                    + ") finds no ground within " + MAX_DROP;
            return;
        }
        if (wet[0]) {
            p.throughWater++;
        }
        for (int y = ground + 1; y < bestY; y++) {
            p.changes.add(new Change(bx, y, bz, trunk, Kind.ADD));
        }
    }

    /** A log/wood/stem placed with its axis vertical — i.e. an actual trunk. */
    private static boolean isVerticalLog(org.bukkit.block.Block b) {
        String n = b.getType().name();
        if (!(n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM"))) {
            return false;
        }
        return b.getBlockData() instanceof org.bukkit.block.data.Orientable o
                && o.getAxis() == org.bukkit.Axis.Y;
    }

    // ------------------------------------------------------------------ entry point

    /** Plan one piece. {@code category} is "street", "building" or "tree". */
    public static PiecePlan planPiece(World w, String template, String category,
                                      BoundingBox box) {
        PiecePlan p = new PiecePlan(template, category, box);
        switch (category) {
            case "street" -> planStreet(w, p);
            case "building" -> planBuilding(w, p);
            case "tree" -> planTree(w, p);
            default -> { }
        }
        return p;
    }

    /** Group a plan's changes by material, for a compact report. */
    public static Map<String, Integer> byMaterial(PiecePlan p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Change c : p.changes) {
            out.merge(c.material().name(), 1, Integer::sum);
        }
        return out;
    }
}
