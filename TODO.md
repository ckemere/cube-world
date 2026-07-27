# CubeWorld — running TODO

Ongoing list of agreed work. Newest context at the bottom of each item.

## 1. Rename a player-built teleport station

A player who raises a new station should be able to name it. Naming already exists
for the 30 city stations, and `TeleportService.register(loc, name, city, owner)`
takes a name, so the data model is ready — the gap is the player-facing flow.

Open question: how to surface it. Options, roughly in order of how vanilla-native
they feel:

- **Anvil-rename the Teleporter Core before placing it.** Zero new UI: the core is
  already a custom-named item, so the player renames it in an anvil and the station
  takes that name when raised. There is already precedent — commit 7ce390d
  ("Teleport: name stations via a renamed core"), so this may be *partly built*;
  check what exists before adding anything.
- A sign placed on/next to the pad, read on placement.
- An inventory GUI with an anvil-style text field (`MenuType.ANVIL` gives a
  rename box without a custom screen, which the stock-client rule requires).

Constraint: server-side only, stock client, no resource pack.

## 2. Master Trader prices are too low

Trades are in `trades/MasterTrades.java`. Current asks (emeralds): Heart of the Sea
32, netherite pickaxe 40, netherite sword 45, diamond chestplate 30, Mending book
30, ench. golden apple 40, wither skull 40, creeper/piglin head 16, city ticket 24,
diamond horse armour 30, Pigstep 24, budding amethyst 40, trident 35, Regen II 24,
ench. crossbow 24.

Wanted:
- Raise most of them; the piglin head is about right as a reference point.
- Top-tier items should cost **emeralds AND a material component** — e.g. a
  netherite "god" sword should demand emeralds *plus* a diamond sword.
  `MerchantRecipe.addIngredient` supports two ingredients, so this is a data change
  in `MasterTrades.pool()`, not new machinery.

## 3. Village ground fill — DONE (structure-aware)

`city/VillageGroundFixer.java` now works from vanilla's own jigsaw data:

- accepts **every** structure start in the chunk, then filters by template pool
  (`/houses/`, `/streets/`, `/town_centers/`). Filtering on the *structure name*
  was the bug that kept it silent for a whole round of testing — the anchored
  cities are `cubeworld:plains_large`, `desert_huge`, ... which contain no
  "village", even though their `start_pool` is `minecraft:village/<style>/*`.
- floor = minimum solid Y over the **whole piece**, so it can never sit under a
  roof; fills only air strictly below it.
- footprint grown by 1 block in X and Z; fill copies the material of the solid
  block the column lands on.

Measured at Antioch: ~8k blocks filled, houses solidly founded (verified in
cross-section). Residual "gaps" in the metrics are one-block **roof eaves**
overhanging sloped ground — correct to leave alone; filling them is the
dirt-to-the-ceilings regression.

Note `tools/compact/city_terrain.py`'s "stilted" number is misleading: it counts
air under the column's *topmost* block, which for a house is the roof, so it
reports room interiors as stilts. Measure from the *lowest* village block instead.

## 4. Streets that run below sea level  (open — needs a decision)

At Antioch 119 of 2209 village columns are `dirt_path` over water, and 68 have
water *directly above* the path: the jigsaw walks a street down the shore and the
last few blocks end up submerged at y61 with the sea surface at y62. The ground
fill is doing its job (solid ground under the path) — the path top is simply below
the waterline.

Options discussed:
- **Causeway** (preferred): inside house/street/town_center footprints, where water
  sits directly above the walkable village block, replace that water with the block
  below it, up to sea level. Places solid, never removes water, so no
  flow-physics problem. Turns the submerged street end into a small pier.
- Drain the footprint: removing water with physics off just floods back on the next
  block update.
- Regenerate the ground so cities never reach below sea level: this is the city pad,
  which was deliberately removed.

## 5. Atlantis — sunken swamp village in a glass dome (design agreed, not built)

A city under a giant glass dome on the deep Pacific floor, with a deep dark close
enough beneath it to be a live threat. Risk is stratified by ELEVATION, so the
player can read safety off the terrain.

### Site

`cubeworld findlatlon` + `climateat` + `terrainprobe`, measured:

| Site | World coords | Face | Seabed | Water column |
|---|---|---|---|---|
| Mariana Trench 11.35N 142.2E | 23704, -1214 | Equator 180 | y~=2 | 60 blocks |
| Point Nemo 48.88S 123.39W | -3588, 7574 | South Pole | y~=24 | 38 blocks |

**Use Mariana.** Nemo's 38 blocks are too shallow: any dome big enough for a
village pokes near the surface and stops reading as deep. Do NOT use 20N 160W --
it lands on x=-10240, exactly a face seam.

Climate at both sites: `E=0.43` -- the lowland-erosion floor. Our erosion curve is
gated to land relief and never sees bathymetry, so **deep dark cannot occur under
the ocean naturally** (it needs E in [-1.0,-0.375]). The trench's 10 km of relief
does NOT help. This must be a deliberate radial erosion override; then vanilla
selects `deep_dark`, grows real sculk, and places shriekers with `CAN_SUMMON` set
properly. Watch the falloff so it doesn't halo deep dark out under open ocean.

### Vertical layout

```
y 62  sea level
y 42  dome apex (r=40)          20 blocks of water above it
y 14  HILL TOP: teleport station + a few workstations. Provably safe.
y  2  town floor: houses, the resident master trader. Eruption zone.
y -3  sculk ceiling / shriekers
y-50  anchored ancient city
```

The hilltop is safe for two INDEPENDENT verified reasons: outside the +/-6 warden
spawn band, and outside the 8-block sensor radius so footsteps there are inaudible.
Keep sculk below y=8 or the guarantee weakens. Teleporter goes up there so arrival
is always safe even if a warden owns the town.

Emergent: workstations + beds on the hill are a breeding nucleus, so a warden sweep
is not a permanent loss of the settlement -- the town repopulates.

### Warden facts (javap-verified against paper-26.2 build 60)

- `SculkShriekerBlockEntity.trySummonWarden` -> `SpawnUtil.trySpawnMob(..., attempts=20,
  spread=5, yOffset=6, ON_TOP_OF_COLLIDER, checkCollision=false)`. Search is centred on
  the **shrieker**, X/Z +/-5, floor scanned TOP-DOWN from shrieker.Y+5 to shrieker.Y-7,
  spawn = floor+1. No LOS, connectivity, sculk-adjacency or light check.
  => **a warden CAN erupt inside the sealed town if the sculk ceiling is <=5 below the
  floor. No rift/tunnel needed.**
- Shriekers do NOT hear footsteps. Their listen tag is exactly
  `["minecraft:sculk_sensor_tendrils_clicking"]`; chain is player -> sensor (r=8) ->
  tendrils-clicking -> shrieker (r=8). **A shrieker we place can never summon**
  (`getStateForPlacement` returns default state, `CAN_SUMMON=false`) -- summoning must
  come from vanilla-generated deep dark. Hand-placed *sensors* work fine as relays.
- `EntityTypes.WARDEN` is `sized(0.9f, 2.9f)` and `WalkNodeEvaluator` allows a 1-block
  climb (step height 0.6, jump 1.125). **A warden cannot enter a 2-block doorway and a
  2-block ledge is an absolute barrier.** So villagers indoors are safe; only those
  caught outside die.
- Wool is two mechanics: `occludes_vibration_signals` = wool only, and needs ALL SIX
  raycasts blocked; `dampens_vibrations` = wool + carpets, checked against the block
  stepped on, so walking on either emits no vibration at all.
- Self-limiting: `hasNearbyWarden` checks a 48-block box (no pile-ups); an unprovoked
  warden digs away ~65 s after spawn (1200-tick cooldown + 100-tick animation), but any
  target contact resets it. Warning level lives on the PLAYER and decays 1 per 12000
  ticks, so a player primed at another deep dark arrives dangerous.

### Swamp village

No vanilla `village/swamp/*` jigsaw pool exists. Cheapest good option: reuse the plains
pools and run a **palette-swap pass** at build time (oak->mangrove, cobblestone->mossy,
hay->moss), same hook style as `VillageGroundFixer`. Set the dome interior biome to
`swamp` so villagers born there keep the swamp skin (offspring roll 50% biome /
25% each parent).

### The resident master trader (source-verified, paper dev bundle 26.2.build.60)

Use a **Villager**, not a WanderingTrader. Both already return `false` from
`removeWhenFarAway`, and villager trade lists are **append-only** -- `updateTrades` ->
`tryAcquireTrade` only ever `add`s, never `clear()`s. So recipes cannot be lost, only
polluted. `setVillagerLevel(5)` is the one decisive call: `VillagerData.canLevelUp` is
`level >= 1 && level < 5`, so level 5 means `updateTrades` never runs -- and it also
defeats `ResetProfession` (which needs job site absent AND xp 0 AND level <= 1), so no
workstation is needed. Profession must NOT be `NONE` or `customServerAiStep` closes the
trade GUI. Lives in the wool-lined "quiet house", which teaches the wool mechanic
diegetically and protects it from wardens by architecture.

- Set in the `world.spawn` consumer (runs AFTER `finalizeSpawn`, so it wins):
  `setVillagerType(SWAMP)`, `setProfession(...)`, `setVillagerLevel(5)`,
  `setVillagerExperience(250)`, then `setRecipes(...)`, custom name, and a **separate**
  PDC key from the wandering Master Traders (`masterTraderNear` matches only
  `WanderingTrader`, so the resident won't count against MAX_ACTIVE).
- **Cancel `EntityZapEvent`.** `Villager.thunderHit` converts to a Witch and
  `LightningBolt` ignores invulnerability -- the sleeper bug for a swamp. (The dome is
  sealed, so lightning may never reach it; guard anyway, it's cheap.)
- **Do NOT `setInvulnerable(true)`** -- it would make `Warden.canTargetEntity` skip the
  trader entirely and gut the risk/reward premise. Protect it geometrically instead.
- No workstation => `WorkAtPoi` never runs => never restocks. Schedule
  `villager.restock()` (resets uses, calls `resendOffersToTradingPlayer`). If reaching
  for `MerchantRecipe.setUses(0)`, read handles back via `getRecipes()` -- the objects
  passed to `setRecipes` are copied and your references are dead.
- Flavour: `showProgressBar()` true + level 5 => the client renders the **"Master"**
  tier label. A WanderingTrader hardcodes level 1 and no bar. (Client rendering not
  verified end-to-end -- confirm in-game.)

### Build order / open risks

1. Radial erosion + depth override at the site (generator side); verify the deep-dark
   halo falloff.
2. Anchor an `ancient_city` at ~y-50 via the same `VillageAnchorHook` structure-set
   injection the 30 cities use, rather than hoping vanilla's 24-chunk spacing obliges.
3. Anchor the village + shape the hill. **The hill re-opens the sloped-ground problem
   items 3/4 just fixed** -- keep it a broad gentle mound, expect to iterate on
   `VillageGroundFixer`.
4. Build the dome POST-generation with `setType(..., false)`: physics off, sealed glass
   shell, so interior air has no water neighbour and stays dry. Do NOT do this in the
   density field -- vanilla's aquifer stage floods cavities below sea level.
5. Teleport station on the hill, joining the ring network (also solves access).

## 6. Cliffs at Antioch — root cause found (overnight session)

**The plinths were never the bug; they were the symptom.** Rendering Antioch from
the air with `-Dcubeworld.villageFix=false` shows the jigsaw village standing in
open sea, joined to land along one edge only. `VillageGroundFixer` was faithfully
manufacturing land under it, and the plinth edges are what read as "4 to 7 block
cliffs". Measured: land covers 61% of the footprint within 48 blocks of the anchor.

Tooling that made this visible: a numpy voxel raycaster over the region files
(scratchpad `voxcam.py`, ~200 lines, 2.4 s/frame, 130 MB, no installs) reusing
`tools/playermap/anvil.py` + `realmap.py`'s palette. Worth productionising into
`tools/` -- three regen cycles were spent on this blind that one image settled.

### Things established, with the measurements

- **The freeboard/factor coupling works.** Against `terrainprobe`'s `natural`
  (the density path's own target), residual wobble at low freeboard is sd 0.5-1.6
  blocks. Do not tune `FREEBOARD_TIGHTNESS` further on transect noise.
- **Use the right oracle.** `MapSampler.heightAt` goes through the map CELL GRID;
  `terrainprobe natural` is what the density actually targets. They disagree, and
  comparing against the wrong one manufactures phantom problems.
- **Terrain height is not a free parameter.** Raising the low-elevation curve
  (100 m -> 2.6 blocks) gave no land improvement (61% -> 59%) and DELETED Antioch's
  village: `locate structure cubeworld:plains_large` went from 12 blocks to 2537.
  `depth` is one of the six climate axes, so moving the surface moves biome
  selection, and a city whose biome leaves `#cubeworld:anchor` never places.
  **Any elevation-curve change must be validated with `locate structure` for all
  30 cities.**
- Most cities are fine: at margin 0, **24 of 30 sit on 100% land**. Only Istanbul,
  Rome, Carthage, Ephesus, Antioch and Pachacamac are water-heavy -- all genuinely
  coastal, which is the point of them.

### RESOLVED

Five anchors moved onto nearby land (commit "Move five coastal city anchors off
the water"); Istanbul deliberately left on the Bosphorus. All 30 verified placing
within 128 blocks via `locate structure`. Antioch: land within 48 blocks 61% ->
70%, village blocks over water 5.75% -> 2.17%, and the render shows buildings on a
natural hillside with no plinths.

Final over-water figures by city (48-block box): Carthage 1.6%, Antioch 2.3%,
Rome 4.8%, Pachacamac 5.0%, Ephesus 6.8%, Istanbul 9.0%.

**The plinth taper was considered and NOT built.** Close renders of Istanbul's and
Carthage's waterfronts show houses on green shore with sand meeting the water --
the residual percentage is small overhangs near the shoreline, not visible walls.
Adding a taper would risk re-creating the dirt-to-the-ceilings failure for no
visible gain. Revisit only if a specific ugly waterfront turns up.

Note the anchor scoring OVER-predicts land, because it uses the generator's target
rather than the wobbled result: Carthage scored 93% but measures 50% (it is a
headland, which is correct for Carthage). Treat the score as a relative ranking
between candidate sites, not an absolute.

## 7. Biome generation — handoff for a fresh approach

We keep going in circles here. This is what is actually established, so the next
attempt does not re-derive it.

### How it is wired today

- `CubeWorldBiomeProvider` (a Bukkit `BiomeProvider`) computes the overworld biome
  itself: `EarthClimate` produces the six vanilla params from Earth rasters, and
  `VanillaBiomeMapper` hands them to vanilla's `MultiNoiseBiomeSource` (overworld
  preset) and takes back whatever it returns.
- The NETHER is different: `SphereRouterHook.installNether` rebinds
  `RandomState.sampler` (a `Climate.Sampler`) from the folded router. The overworld
  does NOT go through that path.
- So "fixing biomes" almost always means changing the six numbers `EarthClimate`
  emits, not changing any biome table.

### The traps, each of which has cost us a session

1. **Selection is NEAREST-NEIGHBOUR, not box containment.** A biome whose box does
   not contain the point still wins if it is closest. This is the single biggest
   source of surprises -- it is why swamps appear where the swamp box is not
   satisfied, and why cave biomes surface (below).
2. **You can compute vanilla's answer offline, exactly.**
   `run/plugins/CubeWorld/biomes/overworld_params.json` is vanilla's real parameter
   list (7594 entries), each `[biome, tMin,tMax, hMin,hMax, cMin,cMax, eMin,eMax,
   dMin,dMax, wMin,wMax, offset]` scaled by 10000. Vanilla's fitness is: per axis,
   `d = max(v-hi, lo-v, 0)`; sum of `d*d`; plus `offset*offset`; lowest wins. A
   dozen lines of Python answers "why did I get this biome" definitively -- use it
   before touching code.
3. **THREE different surface heights exist and they disagree.**
   `MapSampler.heightAt` (the map CELL GRID, several blocks off in rugged ground),
   `terrainprobe`'s `natural` (the density path's target), and the actual generated
   surface (target plus noise wobble). Comparing against the wrong one manufactures
   problems that are not there. `CubeWorldBiomeProvider` currently uses the cell grid.
4. **Terrain height is not free.** `depth` is one of the six axes, so moving the
   surface moves biome selection. Raising the low-elevation curve once deleted
   Antioch's village outright (`locate structure` went 12 blocks -> 2537). Validate
   any elevation change with `locate structure` across all 30 cities.

### Measurement tools that work

- `/cubeworld biomecensus` — global share per biome.
- `run/plugins/CubeWorld/biomes/overworld.cwbr` + `tools/playermap/structures/biomeraster.py`
  — regional breakdowns offline (chunk resolution, sampled at the surface).
- `/cubeworld climateat|biomeat|terrainprobe <x> <z>` — the six params, the biome,
  and the density target at a column.
- `python3 tools/voxcam.py <x> <z> --yaw .. --pitch ..` — look at it.

### Open, measured, not fixed

- **Cave biomes reach the surface.** ~0.95% of the surface around (-3075,-5332)
  is `dripstone_block`/`pointed_dripstone` with stray copper ore; the chunk stores
  `dripstone_caves` below ~y70 with `plains` above while the ground is at y67.
  Cause: `EarthClimate.depth` uses **70 blocks per unit** (vanilla uses 128) so the
  cave band starts ~14 blocks down, and nearest-neighbour means `dripstone_caves`
  starts WINNING at only ~10 blocks down (measured by sweeping the fitness). Combined
  with the cell-grid surface estimate sitting several blocks above real terrain in
  rugged ground, exposed hillsides land in the cave band.
  **Fix ready but NOT applied:** `CaveBiomes.SURFACE_BUFFER = 12` already exists but
  is only enforced on the demo fallback path (`CubeWorldBiomeProvider:67`), never on
  the Earth path. Enforce it in `earthBiome` -- hold `depth` below ~0.10 until the
  sample is at least SURFACE_BUFFER under the surface -- and raise the buffer to ~20
  so it clears both the 10-block win threshold and the surface-estimate error. This
  keeps the 70-per-unit scale, so deep dark and ancient cities stay reachable.
- **North America is a third birch.** 25-50N measures 20% `old_growth_birch_forest`
  + 12% `birch_forest` + 18% `taiga`, where the Great Plains should be grassland.
  Untouched; needs its own look at the T/H axes.
- **Swamp is UNDER target, not over:** 1.05% globally against the 5-8% wanted
  (North America 1.9%, Siberia 5.3% which is honest, Mesopotamia 0.0%). Ocean is
  72.1% against Earth's real 71%.
- **Wet floodplains as wetland** (discussed, not built): would need flow
  accumulation from the DEM, since precipitation alone never identifies the Fertile
  Crescent -- Mesopotamia is arid desert fed by exotic rivers. An elegant version is
  a biome-aware freeboard floor: drop `LAND_FREEBOARD_MIN` where `EarthClimate
  .wetland()` says wetland, so patchy standing water is correct rather than a bug.
