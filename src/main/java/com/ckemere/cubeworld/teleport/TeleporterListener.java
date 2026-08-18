package com.ckemere.cubeworld.teleport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.MerchantRecipe;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The teleport network in-world: raise a station by placing a Teleporter Core on
 * a 3x3 amethyst pad; right-click a station to open its trade menu; buy a trade
 * (drop in the lapis, take the result) to travel. The menu always offers the two
 * ring neighbours, plus the target of a held ticket book and a "buy return
 * ticket" trade when holding a book &amp; quill. City stations build as their
 * chunks load.
 */
public final class TeleporterListener implements Listener {

    private static final int RETURN_LAPIS = 4;      // flat cost of a return ticket
    private static final int WARD_PERIOD = 4;       // ticks the ward ticker credits per run
    private static final int REGEN_PERIOD = 600;    // heal cadence for idle worn cores (ticks)
    // Wear healed per regen run: REGEN_PERIOD scaled by the mine:heal ratio.
    private static final long REGEN_STEP =
            (long) REGEN_PERIOD * TeleportService.WARD_TICKS / TeleportService.WARD_REGEN_TICKS;

    private final Plugin plugin;
    private final TeleportService svc;
    private final Map<UUID, Menu> open = new HashMap<>();
    private final Map<UUID, Ward> warding = new HashMap<>();
    // Forensics so an owner can see their teleporter was attacked: who last mined
    // each core, when (world game-time ticks), and when its owner was last pinged.
    private final Map<String, String> lastAttacker = new HashMap<>();
    private final Map<String, Long> lastTamperTick = new HashMap<>();
    private final Map<String, Long> lastAlertTick = new HashMap<>();

    /** A player's currently-open teleport menu: source + trade index mapping. */
    private record Menu(TeleportService.Station source, List<TeleportService.Offer> offers,
                        int returnIndex) {
    }

    /** A non-owner's in-progress attempt to mine a core loose, with its gauge. */
    private record Ward(String key, org.bukkit.boss.BossBar bar) {
    }

    public TeleporterListener(Plugin plugin, TeleportService svc) {
        this.plugin = plugin;
        this.svc = svc;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::wardTick,
                WARD_PERIOD, WARD_PERIOD);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::regenTick,
                REGEN_PERIOD, REGEN_PERIOD);
    }

    // ------------------------------------------------------------ raise / break
    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!svc.isCore(e.getItemInHand())) {
            return;
        }
        Block b = e.getBlockPlaced();
        if (svc.tryRaise(b.getLocation(), coreName(e.getItemInHand(), b),
                e.getPlayer().getUniqueId().toString())) {
            svc.spark(b.getLocation());
            TeleportService.Station s = svc.stationAt(b.getLocation());
            e.getPlayer().sendMessage(Component.text("Teleport station raised — code: "
                    + (s == null ? "?" : s.code()) + " (yours to break; others can't).",
                    NamedTextColor.AQUA));
        } else {
            e.getPlayer().sendMessage(Component.text(
                    "A teleporter needs a 3x3 amethyst-block pad beneath it.", NamedTextColor.RED));
        }
    }

    /**
     * Only a station's owner may break its core outright. A non-owner can still
     * mine it loose, but the core is warded: it takes {@link TeleportService#WARD_TICKS}
     * of cumulative mining (see {@link #wardTick}), during which the Elder
     * Guardian's curse dogs the miner — so griefing a teleporter costs an hour of
     * real mining, not a swing. The pad amethyst is fully protected from
     * non-owners; the owner may mine it, deactivating the station until repaired.
     */
    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        Player p = e.getPlayer();
        TeleportService.Station core = svc.stationAt(block.getLocation());
        if (core != null) {
            if (core.ownedBy(p.getUniqueId())) {
                svc.unregister(block.getLocation());             // owner: remove + splice rings
                e.setDropItems(false);
                block.getWorld().dropItemNaturally(block.getLocation(), svc.createCore(1));
                p.sendMessage(Component.text("Teleport station removed.", NamedTextColor.YELLOW));
            } else {
                e.setCancelled(true);                            // non-owner: the ward governs removal
            }
            return;
        }
        if (block.getType() == Material.AMETHYST_BLOCK) {
            TeleportService.Station base = svc.stationForBaseBlock(
                    block.getWorld(), block.getX(), block.getY(), block.getZ());
            if (base == null) {
                return;
            }
            if (!base.ownedBy(p.getUniqueId())) {
                e.setCancelled(true);
                curse(p, block, base);
            } else {
                p.sendMessage(Component.text(base.name()
                        + " deactivated — restore its amethyst pad to reactivate.",
                        NamedTextColor.YELLOW));
            }
        }
    }

    /** A non-owner starts mining: begin/continue warding the core (letting the dig
     * animation run so the ward ticker can time it), or repel them from the pad. */
    @EventHandler
    public void onDamage(BlockDamageEvent e) {
        Block block = e.getBlock();
        Player p = e.getPlayer();
        TeleportService.Station core = svc.stationAt(block.getLocation());
        if (core != null) {
            if (core.ownedBy(p.getUniqueId())) {
                return;                                          // owner mines it normally
            }
            if (!core.hasOwner()) {
                e.setCancelled(true);                            // city station: never mineable
                curse(p, block, core);
                return;
            }
            fatigue(p);
            recordTamper(core, p, block.getWorld().getGameTime());
            if (!warding.containsKey(p.getUniqueId())) {
                p.playSound(block.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1f);
                org.bukkit.boss.BossBar bar = Bukkit.createBossBar(
                        wardTitle(core), org.bukkit.boss.BarColor.RED,
                        org.bukkit.boss.BarStyle.SEGMENTED_10);
                bar.setProgress(Math.min(1.0, (double) svc.wearOf(core.key())
                        / TeleportService.WARD_TICKS));
                bar.addPlayer(p);
                warding.put(p.getUniqueId(), new Ward(core.key(), bar));
                p.sendMessage(Component.text(core.name()
                        + " is warded — mining it loose will take a very long while.",
                        NamedTextColor.RED));
                alertOwner(core, p);
            }
            return;
        }
        if (block.getType() == Material.AMETHYST_BLOCK) {
            TeleportService.Station base = svc.stationForBaseBlock(
                    block.getWorld(), block.getX(), block.getY(), block.getZ());
            if (base != null && !base.ownedBy(p.getUniqueId())) {
                e.setCancelled(true);
                curse(p, block, base);
            }
        }
    }

    /** The player stopped mining — bank the wear so far and drop the gauge. */
    @EventHandler
    public void onDamageAbort(org.bukkit.event.block.BlockDamageAbortEvent e) {
        endWard(warding.remove(e.getPlayer().getUniqueId()), true);
    }

    /**
     * Advances every active ward: for each non-owner still aimed at the core
     * they're mining, credit the elapsed ticks toward {@link TeleportService#WARD_TICKS}
     * and update their gauge. When the wear reaches the threshold the core finally
     * comes loose (and drops to the miner); if they look away or leave, the wear
     * is banked so a later session resumes it.
     */
    private void wardTick() {
        if (warding.isEmpty()) {
            return;
        }
        var it = warding.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            Ward wd = en.getValue();
            TeleportService.Station s = svc.byKey(wd.key());
            if (p == null || !p.isOnline() || s == null) {
                endWard(wd, s != null);
                it.remove();
                continue;
            }
            Block target = p.getTargetBlockExact(6);
            boolean mining = target != null && target.getX() == s.x() && target.getY() == s.y()
                    && target.getZ() == s.z() && p.getWorld().getName().equals(s.world());
            if (!mining) {
                endWard(wd, true);                               // stepped away: bank progress
                it.remove();
                continue;
            }
            long total = svc.addWear(wd.key(), WARD_PERIOD);
            fatigue(p);
            recordTamper(s, p, p.getWorld().getGameTime());
            Location coreLoc = new Location(p.getWorld(), s.x(), s.y(), s.z());
            p.getWorld().spawnParticle(org.bukkit.Particle.ELDER_GUARDIAN,
                    coreLoc.clone().add(0.5, 0.6, 0.5), 1);
            double frac = Math.min(1.0, (double) total / TeleportService.WARD_TICKS);
            wd.bar().setProgress(frac);
            wd.bar().setTitle(wardTitle(s) + " — " + (int) (frac * 100) + "%");
            if (frac >= 1.0) {
                svc.unregister(coreLoc);                         // splices rings + clears wear
                coreLoc.getBlock().setType(Material.AIR);
                p.getWorld().dropItemNaturally(coreLoc, svc.createCore(1));
                p.getWorld().playSound(coreLoc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.6f);
                p.sendMessage(Component.text("The wards fail — " + s.name() + " comes loose.",
                        NamedTextColor.YELLOW));
                endWard(wd, false);
                it.remove();
            }
        }
    }

    private void endWard(Ward wd, boolean save) {
        if (wd == null) {
            return;
        }
        wd.bar().removeAll();
        if (save) {
            svc.save();
        }
    }

    /** Slowly heal every worn core that isn't being actively mined right now, so
     * an abandoned grief attempt fades instead of banking forever. */
    private void regenTick() {
        java.util.Set<String> worn = svc.wornKeys();
        if (worn.isEmpty()) {
            return;
        }
        java.util.Set<String> beingMined = new java.util.HashSet<>();
        for (Ward wd : warding.values()) {
            beingMined.add(wd.key());
        }
        boolean changed = false;
        for (String key : worn) {
            if (!beingMined.contains(key)) {
                svc.regenWear(key, REGEN_STEP);
                changed = true;
            }
        }
        if (changed) {
            svc.save();
        }
    }

    private static String wardTitle(TeleportService.Station s) {
        return "Warding " + s.name();
    }

    /** Remember who is mining a core and when, for the owner to read later. */
    private void recordTamper(TeleportService.Station s, Player p, long gameTime) {
        lastAttacker.put(s.key(), p.getName());
        lastTamperTick.put(s.key(), gameTime);
    }

    /** Ping the owner (if online) the first time an attack starts, throttled so a
     * griefer can't spam them by tapping the core repeatedly. */
    private void alertOwner(TeleportService.Station s, Player attacker) {
        if (!s.hasOwner()) {
            return;
        }
        long now = attacker.getWorld().getGameTime();
        Long last = lastAlertTick.get(s.key());
        if (last != null && now - last < 6000) {           // 5 real minutes
            return;
        }
        try {
            Player owner = Bukkit.getPlayer(java.util.UUID.fromString(s.owner()));
            if (owner != null && owner.isOnline() && !owner.equals(attacker)) {
                owner.sendMessage(Component.text("⚠ Someone is mining your teleporter "
                        + s.name() + " (" + s.x() + ", " + s.z() + ")!", NamedTextColor.RED));
                lastAlertTick.put(s.key(), now);
            }
        } catch (IllegalArgumentException ignored) {
            // malformed owner UUID — nothing to alert
        }
    }

    /** Keep the Elder Guardian's mining-fatigue curse on a would-be griefer. */
    private void fatigue(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 30, 2,
                false, false, true));
    }

    /** Repel a non-owner from a protected block (the pad, or a city station). */
    private void curse(Player p, Block block, TeleportService.Station s) {
        fatigue(p);
        p.playSound(block.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1f);
        block.getWorld().spawnParticle(org.bukkit.Particle.ELDER_GUARDIAN,
                block.getLocation().add(0.5, 0.5, 0.5), 3);
        p.sendMessage(Component.text(s.hasOwner()
                ? "This teleporter isn't yours — the amethyst wards you off."
                : s.name() + " is a protected city station.", NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ open it
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null
                || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        TeleportService.Station s = svc.stationAt(e.getClickedBlock().getLocation());
        if (s == null) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();

        if (!svc.baseIntact(e.getClickedBlock().getLocation())) {
            p.sendMessage(Component.text(s.name()
                    + " is inactive — restore its 3x3 amethyst pad to use it.",
                    NamedTextColor.RED));
            svc.spark(e.getClickedBlock().getLocation());
            return;
        }

        long wear = svc.wearOf(s.key());
        if (wear > 0) {
            int pct = (int) Math.min(100, wear * 100 / TeleportService.WARD_TICKS);
            String who = lastAttacker.get(s.key());
            String detail = "";
            if (who != null) {
                Long t = lastTamperTick.get(s.key());
                long mins = t == null ? 0 : Math.max(1,
                        (e.getClickedBlock().getWorld().getGameTime() - t) / 1200);
                detail = " — last mined by " + who + " ~" + mins + "m ago";
            }
            p.sendMessage(Component.text("⚠ " + s.name() + " shows tamper damage: "
                    + pct + "% worn" + detail, NamedTextColor.GOLD));
        }

        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        // Resolve the held ticket once: its code, whether it maps to a real
        // station, and whether it's a trader mystery ticket (destination masked
        // in the menu until travel).
        String code = svc.ticketCode(main);
        boolean mystery = code != null && svc.isMysteryTicket(main);
        if (code == null) {
            code = svc.ticketCode(off);
            mystery = code != null && svc.isMysteryTicket(off);
        }
        TeleportService.Station ticketTarget = svc.stationByCode(code);
        // A well-formed code with no station is an "uncharted" destination:
        // hashed from the code + world seed, one-way, delivered at the surface.
        TeleportService.Station uncharted =
                (code != null && ticketTarget == null) ? svc.unchartedFor(code, s) : null;
        boolean hasBook = main.getType() == Material.WRITABLE_BOOK
                || off.getType() == Material.WRITABLE_BOOK;

        List<TeleportService.Offer> offers = svc.offers(s, ticketTarget, uncharted);
        List<MerchantRecipe> recipes = new ArrayList<>();
        for (TeleportService.Offer o : offers) {
            int cost = svc.lapisCost(e.getClickedBlock().getLocation(), o.dest());
            MerchantRecipe r = new MerchantRecipe(destIcon(o, cost, mystery), Integer.MAX_VALUE);
            r.addIngredient(new ItemStack(Material.LAPIS_LAZULI, Math.max(1, Math.min(64, cost))));
            recipes.add(r);
        }
        int returnIndex = -1;
        if (hasBook) {
            returnIndex = recipes.size();
            MerchantRecipe ret = new MerchantRecipe(svc.ticketBook(s), Integer.MAX_VALUE);
            ret.addIngredient(new ItemStack(Material.WRITABLE_BOOK, 1));
            ret.addIngredient(new ItemStack(Material.LAPIS_LAZULI, RETURN_LAPIS));
            recipes.add(ret);
        }
        if (recipes.isEmpty()) {
            p.sendMessage(Component.text(
                    "The network is still forming — no destinations from here yet.",
                    NamedTextColor.GRAY));
            return;
        }
        Merchant m = Bukkit.createMerchant(Component.text("Teleporter · " + s.name(),
                NamedTextColor.DARK_AQUA));
        m.setRecipes(recipes);
        p.openMerchant(m, true);
        open.put(p.getUniqueId(), new Menu(s, offers, returnIndex));
    }

    /**
     * The player completed a trade (took the result) at a station merchant. We
     * drive the real trade machinery: selecting a row auto-loads the lapis into
     * the slots (vanilla), and taking the result lands here.
     *
     * <p>A destination row is intercepted — cancelled so the player never keeps
     * the map "preview", then we close (which returns the staged lapis to the
     * inventory so {@code travel} can charge it) and teleport. The return-ticket
     * row is a genuine trade: we let it complete so a book &amp; quill + lapis
     * really becomes a written ticket.
     */
    @EventHandler
    public void onPurchase(PlayerPurchaseEvent e) {
        Player p = e.getPlayer();
        Menu menu = open.get(p.getUniqueId());
        if (menu == null) {
            return;                                 // not one of our station merchants
        }
        int idx = -1;
        if (p.getOpenInventory().getTopInventory() instanceof MerchantInventory mi) {
            idx = mi.getSelectedRecipeIndex();
        }
        if (idx == menu.returnIndex && menu.returnIndex >= 0) {
            e.setRewardExp(false);                  // a real trade: book & quill + lapis -> ticket
            return;
        }
        e.setCancelled(true);                       // never hand out the destination "preview" map
        if (idx >= 0 && idx < menu.offers.size()) {
            TeleportService.Offer offer = menu.offers.get(idx);
            TeleportService.Station dest = offer.dest();
            Location source = menu.source.location(plugin);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                p.closeInventory();                 // returns the staged lapis to the inventory
                boolean ok = offer.kind() == TeleportService.OfferKind.UNCHARTED
                        ? svc.travelUncharted(p, source, dest)
                        : svc.travel(p, source, dest);
                // Tickets are single-use: any ticket-based transfer (real
                // station or uncharted) consumes one matching book on success.
                if (ok && (offer.kind() == TeleportService.OfferKind.TICKET
                        || offer.kind() == TeleportService.OfferKind.UNCHARTED)) {
                    consumeTicket(p, dest.code());
                }
            });
        }
    }

    /** Remove one held book whose text carries this code (main hand, then off). */
    private void consumeTicket(Player p, String code) {
        for (ItemStack it : new ItemStack[] {
                p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
            if (code != null && code.equals(svc.ticketCode(it))) {
                it.setAmount(it.getAmount() - 1);
                p.sendMessage(Component.text("Your ticket is spent.", NamedTextColor.GRAY));
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        open.remove(e.getPlayer().getUniqueId());
    }

    private ItemStack destIcon(TeleportService.Offer o, int cost, boolean mystery) {
        TeleportService.Station d = o.dest();
        boolean ticketKind = o.kind() == TeleportService.OfferKind.TICKET
                || o.kind() == TeleportService.OfferKind.UNCHARTED;
        boolean masked = o.kind() == TeleportService.OfferKind.UNCHARTED
                || (o.kind() == TeleportService.OfferKind.TICKET && mystery);
        ItemStack it = new ItemStack(o.kind() == TeleportService.OfferKind.UNCHARTED
                ? Material.MAP : ticketKind ? Material.PAPER : Material.FILLED_MAP);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(masked ? "???" : d.name(),
                masked ? NamedTextColor.LIGHT_PURPLE : d.city() ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(lore(switch (o.kind()) {
            case RING_A -> "Ring route Ⅰ";
            case RING_B -> "Ring route Ⅱ";
            case TICKET -> mystery ? "Your mystery ticket" : "Your ticket";
            case UNCHARTED -> "This code matches no station";
        }, NamedTextColor.GRAY));
        lore.add(lore("Cost: " + cost + " lapis", NamedTextColor.BLUE));
        if (!masked) {
            lore.add(lore("Code: " + d.code(), NamedTextColor.DARK_GRAY));
        }
        if (ticketKind) {
            lore.add(lore("One-way — the ticket is spent", NamedTextColor.DARK_PURPLE));
        }
        lore.add(lore("Buy (take the map) to travel", NamedTextColor.GREEN));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private static Component lore(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------ build preloaded city sites
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        int cx = e.getChunk().getX();
        int cz = e.getChunk().getZ();
        if (svc.pendingChunkPeek(cx, cz) == 0) {
            return;
        }
        List<TeleportService.CityBuild> builds = svc.pendingBuildsFor(cx, cz);
        if (builds.isEmpty()) {
            return;
        }
        World w = e.getWorld();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (TeleportService.CityBuild c : builds) {
                svc.buildCityStation(w, c.x(), c.z(), c.name());
            }
        });
    }

    /** Station name from a renamed core (anvil), else auto from coordinates. */
    private String coreName(ItemStack core, Block b) {
        if (core != null && core.hasItemMeta() && core.getItemMeta().hasDisplayName()) {
            String n = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(core.getItemMeta().displayName());
            if (!n.isBlank() && !n.equals("Teleporter Core")) {
                return n;
            }
        }
        return "Station " + b.getX() + "," + b.getZ();
    }
}
