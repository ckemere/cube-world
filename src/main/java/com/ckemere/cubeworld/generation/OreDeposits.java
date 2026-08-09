package com.ckemere.cubeworld.generation;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * Where real Earth is unusually rich in each ore, and how strongly to enrich it.
 *
 * <p>Each {@link Ore} carries the vanilla block it places (a stone and a deepslate
 * variant, chosen from the actual host rock), the Y band it occurs in, and the
 * per-chunk enrichment applied at the <em>centre</em> of a province: {@code coreVeins}
 * extra veins on top of vanilla, each {@code veinSize} blocks. Both taper linearly to
 * zero at the province radius, so a zone is a soft disc, not a hard edge -- you can
 * research its rough location by how strong the enrichment feels, but not pin the
 * exact centre. These numbers are tuned to the requested "3-4x more veins, 2-3x
 * bigger" feel and can be dialled globally with {@code -Dcubeworld.oreFreq} /
 * {@code -Dcubeworld.oreSize} without recompiling.
 *
 * <p>The coordinates are deliberately undocumented in play: they are real mineral
 * provinces (Kimberley diamonds, Sar-e-Sang lapis, Mogok rubies, ...), so they can be
 * found by geographic knowledge and by sampling enrichment strength, not handed out.
 * Redstone stands in for red gemstones -- ruby and garnet deposits.
 */
public final class OreDeposits {

    private OreDeposits() {
    }

    /**
     * An ore and its enrichment profile.
     *
     * <ul>
     * <li>{@code yMin}/{@code yMax} bracket vanilla's occurrence band (added veins land
     *     only here).
     * <li>{@code nominalVeinSize} is a vanilla-ish average vein size; added veins are a
     *     multiple of it (see {@code cubeworld.oreSize}), so they read as bigger.
     * <li>{@code floorVeins} is the minimum extra veins per chunk at a province centre.
     *     It guarantees a province still reads rich in stingy chunks -- and, crucially,
     *     gives biome-locked ores like emerald (vanilla: mountains only) a presence in
     *     their real provinces (Muzo, Panjshir) regardless of the local biome, where
     *     the proportional term would otherwise be zero.
     * </ul>
     *
     * <p>Enrichment estimates the chunk's own vanilla vein count (its ore blocks divided
     * by {@code nominalVeinSize}) and adds a multiple of that, so both knobs apply
     * independently and every province lands near the same multiplier despite this
     * generator's very uneven per-ore baselines.
     */
    public enum Ore {
        DIAMOND (Material.DIAMOND_ORE,  Material.DEEPSLATE_DIAMOND_ORE,  -63,  16,  5, 3),
        LAPIS   (Material.LAPIS_ORE,    Material.DEEPSLATE_LAPIS_ORE,    -63,  60,  6, 3),
        // Redstone == red gemstones (ruby / garnet).
        REDSTONE(Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, -63,  15,  7, 3),
        EMERALD (Material.EMERALD_ORE,  Material.DEEPSLATE_EMERALD_ORE,    4, 120,  3, 4),
        GOLD    (Material.GOLD_ORE,     Material.DEEPSLATE_GOLD_ORE,     -63,  32,  7, 3),
        IRON    (Material.IRON_ORE,     Material.DEEPSLATE_IRON_ORE,     -24,  64,  8, 3),
        COPPER  (Material.COPPER_ORE,   Material.DEEPSLATE_COPPER_ORE,   -16,  96, 12, 3),
        COAL    (Material.COAL_ORE,     Material.DEEPSLATE_COAL_ORE,       8, 128, 13, 3);

        public final Material stoneOre;
        public final Material deepslateOre;
        public final int yMin;
        public final int yMax;
        public final int nominalVeinSize;
        public final int floorVeins;

        Ore(Material stoneOre, Material deepslateOre, int yMin, int yMax,
            int nominalVeinSize, int floorVeins) {
            this.stoneOre = stoneOre;
            this.deepslateOre = deepslateOre;
            this.yMin = yMin;
            this.yMax = yMax;
            this.nominalVeinSize = nominalVeinSize;
            this.floorVeins = floorVeins;
        }
    }

    /** {lat, lon, radius} in degrees; radius 0 means "use the global default". */
    private static double[] p(double lat, double lon) {
        return new double[] {lat, lon, 0};
    }

    /**
     * A province with its own radius in blocks. Used for the offshore fields, which
     * are genuinely enormous in reality -- the Clarion-Clipperton Zone alone is about
     * 4.5 million km2 -- and which have to repay a much harder journey (deep ocean or
     * polar water, so breathing gear or a conduit) than any land deposit.
     */
    private static double[] p(double lat, double lon, double radius) {
        return new double[] {lat, lon, radius};
    }

    /** Radius for the deep-water fields. See {@link #p(double, double, double)}. */
    private static final double SEA = 800;

    /**
     * Real Earth mineral provinces per ore. Diamond and lapis get 5 each; the rest
     * get 10. Kept intentionally quiet in play (see class doc).
     */
    public static final Map<Ore, List<double[]>> PROVINCES = Map.of(
        Ore.DIAMOND, List.of(
            p(-28.74,  24.77),   // Kimberley, South Africa
            p( 62.53, 113.99),   // Mirny, Siberia, Russia
            p(-21.30,  25.37),   // Orapa, Botswana
            p(-16.71, 128.40),   // Argyle, Western Australia
            p( 64.50, -110.30),  // Diavik / Ekati, NWT, Canada
            // --- deep water (see SEA) ---
            p(-26.50,  14.50, SEA),  // Debmarine Atlantic 1, Namibia -- real marine diamonds
            p( 85.00,  85.00, SEA)), // Gakkel Ridge, Arctic Ocean -- polar
        Ore.LAPIS, List.of(
            p( 36.18,  70.80),   // Sar-e-Sang, Badakhshan, Afghanistan
            p(-30.60, -70.90),   // Flor de los Andes, Chile
            p( 51.65, 103.72),   // Slyudyanka, Lake Baikal, Russia
            p( 38.82, -106.75),  // Italian Mountain, Colorado, USA
            p( 38.10,  72.30),   // Lyadzhuar Dara, Pamir, Tajikistan
            // --- deep water: real nodule fields, lapis is our own mapping ---
            p( -7.00, -90.00, SEA),  // Peru Basin nodule field
            p(-20.00, -160.00, SEA)), // Cook Islands EEZ, cobalt-rich nodules
        Ore.REDSTONE, List.of(  // ruby / garnet
            p( 22.92,  96.51),   // Mogok, Myanmar (ruby)
            p(  6.68,  80.40),   // Ratnapura, Sri Lanka
            p(-22.68,  45.18),   // Ilakaka, Madagascar
            p(-13.13,  39.00),   // Montepuez, Mozambique (ruby)
            p( -6.20,  36.30),   // Winza, Tanzania (ruby)
            p( 34.55,  69.90),   // Jegdalek, Afghanistan (ruby)
            p( 22.10, 104.72),   // Luc Yen, Vietnam (ruby)
            p( 43.68, -74.00),   // Gore Mountain, New York, USA (garnet)
            p( 20.50,  84.50),   // Odisha, India (garnet)
            p( 50.50,  14.00)),  // Bohemia, Czechia (pyrope garnet)
        Ore.EMERALD, List.of(
            p(  5.53, -74.10),   // Muzo, Colombia
            p( -4.88, -73.37),   // Chivor, Colombia
            p(-13.10,  28.10),   // Kafubu, Zambia
            p( 57.07,  61.40),   // Malyshevo, Urals, Russia
            p(-19.62, -43.23),   // Itabira, Minas Gerais, Brazil
            p( 35.40,  69.80),   // Panjshir, Afghanistan
            p( 34.80,  72.35),   // Swat, Pakistan
            p(-20.60,  29.80),   // Sandawana, Zimbabwe
            p( 24.63,  34.80),   // Sikait (Cleopatra's mines), Egypt
            p(-21.23,  48.34),   // Mananjary, Madagascar
            // --- deep water: real fields, emerald is our own mapping ---
            p(-10.00,  75.00, SEA),  // Central Indian Ocean Basin nodule field
            p(-56.00, -30.00, SEA)), // East Scotia Ridge, Southern Ocean -- polar
        Ore.GOLD, List.of(
            p(-26.27,  27.50),   // Witwatersrand, South Africa
            p( 37.96, -120.38),  // Mother Lode, California, USA
            p(-30.75, 121.47),   // Kalgoorlie, Western Australia
            p( 64.06, -139.43),  // Klondike, Yukon, Canada
            p( 12.95,  78.28),   // Kolar, India
            p(  6.20,  -1.67),   // Obuasi (Ashanti), Ghana
            p( -6.98, -78.51),   // Yanacocha, Peru
            p( 40.80, -116.13),  // Carlin, Nevada, USA
            p( -6.02, -49.67),   // Serra Pelada, Brazil
            p(  5.90,  38.90),   // Adola / Lega Dembi, Ethiopia
            // --- deep water: seafloor massive sulfides really are gold-bearing ---
            p( 26.14, -44.83, SEA),  // TAG hydrothermal field, Mid-Atlantic Ridge
            p( 21.36,  38.05, SEA),  // Atlantis II Deep, Red Sea -- Au/Ag muds
            p(-22.20, -176.60, SEA), // Lau Basin, SW Pacific
            p( 10.00, -145.00, SEA)), // Clarion-Clipperton Zone -- the big one
        Ore.IRON, List.of(
            p( 67.85,  20.22),   // Kiruna, Sweden
            p(-23.36, 119.68),   // Mount Whaleback, Pilbara, Australia
            p( -6.00, -50.16),   // Carajas, Brazil
            p( 47.50, -92.60),   // Mesabi Range, Minnesota, USA
            p( 47.91,  33.39),   // Kryvyi Rih, Ukraine
            p( 41.12, 122.99),   // Anshan, China
            p( 51.28,  37.53),   // Kursk Magnetic Anomaly, Russia
            p( 18.68,  81.25),   // Bailadila, Odisha/Chhattisgarh, India
            p(  7.55,  -8.48),   // Nimba, Liberia/Guinea
            p( 22.73, -12.48)),  // Zouerat, Mauritania
        Ore.COPPER, List.of(
            p(-22.31, -68.90),   // Chuquicamata, Chile
            p( 40.52, -112.15),  // Bingham Canyon, Utah, USA
            p(-12.82,  28.21),   // Kitwe, Copperbelt, Zambia
            p( -4.06, 137.11),   // Grasberg, Indonesia
            p( 33.05, -109.36),  // Morenci, Arizona, USA
            p( 47.80,  67.71),   // Zhezkazgan, Kazakhstan
            p(-16.53, -71.60),   // Cerro Verde, Peru
            p( 47.24, -88.31),   // Keweenaw, Michigan, USA
            p( 69.35,  88.20),   // Norilsk, Russia
            p( 51.40,  16.20)),  // Lubin, Poland
        Ore.COAL, List.of(
            p( 38.00, -81.00),   // Appalachia, West Virginia, USA
            p( 51.50,   7.20),   // Ruhr, Germany
            p( 40.00, 113.30),   // Datong coalfield, Shanxi, China
            p( 54.00,  86.50),   // Kuzbass, Russia
            p( 44.00, -105.50),  // Powder River, Wyoming, USA
            p( 50.30,  19.00),   // Upper Silesia, Poland
            p(-22.50, 148.50),   // Bowen Basin, Australia
            p( 23.75,  86.42),   // Jharia, India
            p( 48.30,  38.50),   // Donbas, Ukraine
            p(-25.87,  29.23))   // Witbank, South Africa
    );
}
