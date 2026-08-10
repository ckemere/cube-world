package com.ckemere.cubeworld.city;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Renders a {@link VillageFixPlan} into the world as coloured wool so a repair rule can
 * be inspected before it is trusted, one structure piece at a time.
 *
 * <pre>
 *   ORANGE_WOOL  a block the rule would ADD
 *   YELLOW_WOOL  a block the rule would REMOVE
 *   RED_WOOL     a piece the rule would REJECT (its floor ring is marked)
 * </pre>
 *
 * <p>Nothing else is written: the plan is computed against the untouched world and only
 * the markers are placed, so a wrong rule shows up as wrong-coloured wool rather than as
 * damage that has to be regenerated away. `list` mode writes nothing at all.
 *
 * <p>This exists because the repair currently decides and mutates in one pass on chunk
 * load, which leaves no way to see its reasoning — the 24-block dirt pillars under trees
 * were only found by noticing them in a render days later.
 */
public final class VillageFixPreview {

    private static final Field ELEMENT_FIELD;

    static {
        Field f = null;
        try {
            f = PoolElementStructurePiece.class.getDeclaredField("element");
            f.setAccessible(true);
        } catch (Exception ignored) {
            f = null;
        }
        ELEMENT_FIELD = f;
    }

    private VillageFixPreview() {
    }

    private static String templateOf(StructurePiece piece) {
        if (ELEMENT_FIELD == null || !(piece instanceof PoolElementStructurePiece pe)) {
            return null;
        }
        try {
            return String.valueOf(ELEMENT_FIELD.get(pe));
        } catch (Exception e) {
            return null;
        }
    }

    /** street / building / tree / null (not repaired). */
    private static String categoryOf(String tpl) {
        if (tpl == null) {
            return null;
        }
        if (tpl.contains("/streets/")) {
            return "street";
        }
        if (tpl.contains("/houses/") || tpl.contains("/town_centers/")) {
            return "building";
        }
        if (tpl.contains("/trees/")) {
            return "tree";
        }
        return null;
    }

    /** Short name for reporting: the last path element of the template location. */
    private static String shortName(String tpl) {
        int i = tpl.lastIndexOf('/');
        String s = i < 0 ? tpl : tpl.substring(i + 1);
        return s.replaceAll("[\\]\\[]", "").trim();
    }

    /**
     * {@code /cubeworld villagepreview <chunkX> <chunkZ> [list|<index>] [mark]}
     *
     * <p>Without {@code mark} nothing is written — it reports only. With {@code mark} the
     * wool goes in. An index restricts the work to a single piece.
     */
    public static boolean run(CommandSender sender, World world, String[] args) {
        int cx;
        int cz;
        try {
            cx = Integer.parseInt(args[1]);
            cz = Integer.parseInt(args[2]);
        } catch (Exception e) {
            sender.sendMessage(Component.text(
                    "Usage: /cubeworld villagepreview <chunkX> <chunkZ> [list|<index>] [mark]",
                    NamedTextColor.RED));
            return true;
        }
        String which = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "list";
        boolean mark = args.length > 4 && args[4].equalsIgnoreCase("mark");

        ServerLevel level = ((CraftWorld) world).getHandle();
        List<StructureStart> starts = level.structureManager()
                .startsForStructure(new ChunkPos(cx, cz), st -> true);

        List<StructurePiece> pieces = new ArrayList<>();
        List<String> cats = new ArrayList<>();
        for (StructureStart start : starts) {
            for (StructurePiece piece : start.getPieces()) {
                String cat = categoryOf(templateOf(piece));
                if (cat != null) {
                    pieces.add(piece);
                    cats.add(cat);
                }
            }
        }
        if (pieces.isEmpty()) {
            sender.sendMessage(Component.text("No repairable village pieces in chunk ("
                    + cx + "," + cz + ").", NamedTextColor.YELLOW));
            return true;
        }

        int only = -1;
        if (!"list".equals(which)) {
            try {
                only = Integer.parseInt(which);
            } catch (NumberFormatException e) {
                only = -1;
            }
        }

        long totalAdd = 0;
        long totalRemove = 0;
        int rejects = 0;
        for (int i = 0; i < pieces.size(); i++) {
            if (only >= 0 && i != only) {
                continue;
            }
            BoundingBox bb = pieces.get(i).getBoundingBox();
            String tpl = shortName(templateOf(pieces.get(i)));
            VillageFixPlan.PiecePlan p =
                    VillageFixPlan.planPiece(world, tpl, cats.get(i), bb);
            totalAdd += p.adds();
            totalRemove += p.removes();
            if (p.rejectReason != null) {
                rejects++;
            }
            // Placement geometry, which is the thing actually under test. Vanilla slides
            // a piece until box.minY + groundLevelDelta meets getFirstFreeHeight, and the
            // Beardifier then pulls terrain toward that same line. Printing the line and
            // the real ground beneath it shows directly whether the contract held.
            int gld = pieces.get(i) instanceof PoolElementStructurePiece pe
                    ? pe.getGroundLevelDelta() : -1;
            int groundLine = bb.minY() + Math.max(gld, 0);
            int midX = (bb.minX() + bb.maxX()) / 2;
            int midZ = (bb.minZ() + bb.maxZ()) / 2;
            int realGround = groundUnder(world, midX, midZ, bb.minY());
            sender.sendMessage(Component.text(
                    String.format(Locale.ROOT,
                            "[%d] %-9s %-30s minY=%-4d gld=%-3d line=%-4d ground=%-5s d=%-4s add=%-5d %s",
                            i, p.category, tpl, bb.minY(), gld, groundLine,
                            realGround == Integer.MIN_VALUE ? "?" : realGround,
                            realGround == Integer.MIN_VALUE ? "?" : (groundLine - realGround),
                            p.adds(),
                            (p.overhangs > 0 ? "overhang=" + p.overhangs + " " : "")
                                    + (p.throughWater > 0 ? "water=" + p.throughWater : "")),
                    NamedTextColor.AQUA));
            if (p.rejectReason != null) {
                sender.sendMessage(Component.text("      REJECT: " + p.rejectReason,
                        NamedTextColor.RED));
            }
            if (!VillageFixPlan.byMaterial(p).isEmpty()) {
                sender.sendMessage(Component.text("      fill: "
                        + VillageFixPlan.byMaterial(p), NamedTextColor.GRAY));
            }
            if (mark) {
                apply(world, p);
            }
        }
        sender.sendMessage(Component.text(
                String.format(Locale.ROOT,
                        "%d piece(s); would add %d, remove %d, reject %d. %s",
                        only >= 0 ? 1 : pieces.size(), totalAdd, totalRemove, rejects,
                        mark ? "MARKED with wool." : "Nothing written (add 'mark' to place wool)."),
                NamedTextColor.GOLD));
        return true;
    }

    /**
     * Highest natural ground at (x, z) at or below {@code from}, ignoring blocks the
     * village itself placed. Structure materials are excluded by hand because at this
     * point the piece is already in the world and we want the terrain it landed on.
     */
    private static int groundUnder(World w, int x, int z, int from) {
        for (int y = from; y > w.getMinHeight(); y--) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (!m.isSolid()) {
                continue;
            }
            String n = m.name();
            boolean built = n.contains("PLANK") || n.contains("STAIRS") || n.contains("LOG")
                    || n.contains("COBBLESTONE") || n.contains("PATH") || n.contains("FENCE")
                    || n.contains("SLAB") || n.contains("DOOR") || n.contains("GLASS")
                    || n.contains("WOOL") || n.contains("HAY") || n.contains("BOOKSHELF");
            if (!built) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Place the markers. Only wool is written; the planned blocks are NOT applied. */
    private static void apply(World w, VillageFixPlan.PiecePlan p) {
        for (VillageFixPlan.Change c : p.changes) {
            Material marker = switch (c.kind()) {
                case ADD -> Material.ORANGE_WOOL;
                case REMOVE -> Material.YELLOW_WOOL;
                case REJECT -> Material.RED_WOOL;
            };
            w.getBlockAt(c.x(), c.y(), c.z()).setType(marker, false);
        }
        if (p.rejectReason != null) {
            BoundingBox bb = p.box;
            for (int x = bb.minX(); x <= bb.maxX(); x++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    boolean edge = x == bb.minX() || x == bb.maxX()
                            || z == bb.minZ() || z == bb.maxZ();
                    if (edge) {
                        w.getBlockAt(x, bb.minY(), z).setType(Material.RED_WOOL, false);
                    }
                }
            }
        }
    }
}
