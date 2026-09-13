package com.ckemere.cubeworld.lifesteal;

import com.ckemere.cubeworld.CubeWorldPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;

/**
 * The Cardiograph: a spyglass that reads hearts. Raise it at a traveler and
 * their heart's composition appears — an action-bar line plus a floating
 * text panel over their head that ONLY THE SCANNER SEES (a non-persistent
 * {@link TextDisplay} riding the target, {@code visibleByDefault(false)} +
 * per-player {@code showEntity}: the same stock-client display-entity
 * vocabulary as the rest of CubeWorld). Lower it after a steady look and
 * the full dossier — every absorbed death, oldest first — prints to chat.
 *
 * <p>Ticked at 4 Hz, and only for players actively scoped on a Cardiograph,
 * so the idle cost is a hand-raised check per online player.
 */
public final class Cardiograph {

    private static final int RANGE = 48;
    private static final int DOSSIER_TICKS = 6;   // ~1.5s locked before release counts

    private final CubeWorldPlugin plugin;
    private final HeartService hearts;
    private final HeartLedger ledger;
    private final NamespacedKey itemKey;
    private final Map<UUID, Scan> scans = new HashMap<>();

    private static final class Scan {
        UUID target;
        TextDisplay panel;
        int lockedTicks;
    }

    public Cardiograph(CubeWorldPlugin plugin, HeartService hearts, HeartLedger ledger) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.ledger = ledger;
        this.itemKey = new NamespacedKey(plugin, "cardiograph");
    }

    // ------------------------------------------------------------------ item
    public ItemStack createItem() {
        ItemStack it = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Cardiograph", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Raise it at a traveler to read", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("the making of their heart.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    /** The eye above the heart above the pulse, framed in brass: spyglass /
     * copper–Heart of the Sea–copper / redstone block. The Heart of the Sea
     * is the gate — one treasure hunt, not a grind. */
    public void registerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "cardiograph_recipe");
        if (plugin.getServer().getRecipe(key) == null) {
            org.bukkit.inventory.ShapedRecipe r =
                    new org.bukkit.inventory.ShapedRecipe(key, createItem());
            r.shape(" S ", "CHC", " R ");
            r.setIngredient('S', Material.SPYGLASS);
            r.setIngredient('C', Material.COPPER_INGOT);
            r.setIngredient('H', Material.HEART_OF_THE_SEA);
            r.setIngredient('R', Material.REDSTONE_BLOCK);
            plugin.getServer().addRecipe(r);
        }
    }

    public boolean isCardiograph(ItemStack it) {
        return it != null && it.getType() == Material.SPYGLASS && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer()
                        .has(itemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ tick
    public void tick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            boolean scoping = p.isHandRaised() && isCardiograph(p.getActiveItem());
            Player target = null;
            if (scoping) {
                Entity e = p.getTargetEntity(RANGE, false);
                if (e instanceof Player t && !t.equals(p)) {
                    target = t;
                }
            }
            Scan scan = scans.get(p.getUniqueId());
            if (target != null) {
                if (scan == null) {
                    scan = new Scan();
                    scans.put(p.getUniqueId(), scan);
                }
                if (!target.getUniqueId().equals(scan.target)) {
                    dropPanel(scan);
                    scan.target = target.getUniqueId();
                    scan.lockedTicks = 0;
                    scan.panel = spawnPanel(p, target);
                }
                scan.lockedTicks++;
                scan.panel.text(panelText(target));
                p.sendActionBar(Component.text("♥ " + hearts.hearts(target) + " — "
                        + ledger.summary(target), NamedTextColor.RED));
            } else if (scan != null) {
                // Released (or lost the target): a steady read earns the dossier.
                if (scan.lockedTicks >= DOSSIER_TICKS && scan.target != null) {
                    Player t = plugin.getServer().getPlayer(scan.target);
                    if (t != null) {
                        dossier(p, t);
                    }
                }
                dropPanel(scan);
                scans.remove(p.getUniqueId());
            }
        }
    }

    /** Remove a departing player's panel and any scan of them. */
    public void forget(Player p) {
        Scan s = scans.remove(p.getUniqueId());
        if (s != null) {
            dropPanel(s);
        }
        for (Scan other : scans.values()) {
            if (p.getUniqueId().equals(other.target)) {
                dropPanel(other);
                other.target = null;
            }
        }
    }

    // --------------------------------------------------------------- display
    private TextDisplay spawnPanel(Player viewer, Player target) {
        TextDisplay d = target.getWorld().spawn(
                target.getLocation().add(0, 2.4, 0), TextDisplay.class, td -> {
                    td.setPersistent(false);
                    td.setVisibleByDefault(false);
                    td.setBillboard(Display.Billboard.CENTER);
                    td.setSeeThrough(false);
                    td.setBackgroundColor(Color.fromARGB(150, 12, 8, 20));
                    td.setTransformationMatrix(new Matrix4f().translate(0f, 0.55f, 0f));
                    td.text(panelText(target));
                });
        target.addPassenger(d);                       // rides the target: follows free
        viewer.showEntity(plugin, d);                 // only the scanner sees it
        return d;
    }

    private void dropPanel(Scan scan) {
        if (scan.panel != null) {
            scan.panel.remove();
            scan.panel = null;
        }
    }

    private Component panelText(Player t) {
        var entries = ledger.entries(t);
        Component c = Component.text(t.getName(), NamedTextColor.WHITE)
                .append(Component.text("  ♥ " + hearts.hearts(t), NamedTextColor.RED))
                .append(Component.newline())
                .append(Component.text(ledger.summary(t), NamedTextColor.GRAY));
        if (!entries.isEmpty()) {
            var last = entries.get(entries.size() - 1);
            if (!last.epitaph().isBlank()) {
                c = c.append(Component.newline()).append(Component.text(
                        "“" + last.epitaph() + "”", NamedTextColor.DARK_PURPLE)
                        .decoration(TextDecoration.ITALIC, true));
            }
        }
        return c;
    }

    private void dossier(Player viewer, Player t) {
        viewer.sendMessage(Component.text("— the heart of " + t.getName() + " —",
                NamedTextColor.GOLD));
        viewer.sendMessage(Component.text("♥ " + hearts.hearts(t) + " — "
                + ledger.summary(t), NamedTextColor.RED));
        var entries = ledger.entries(t);
        if (entries.isEmpty()) {
            viewer.sendMessage(Component.text("Every beat their own.", NamedTextColor.GRAY));
            return;
        }
        for (var e : entries) {
            viewer.sendMessage(Component.text("  ♥ ", NamedTextColor.RED)
                    .append(Component.text(e.kind().title, NamedTextColor.GRAY))
                    .append(e.epitaph().isBlank() ? Component.empty()
                            : Component.text("  “" + e.epitaph() + "”",
                                    NamedTextColor.DARK_PURPLE)
                                    .decoration(TextDecoration.ITALIC, true)));
        }
    }
}
