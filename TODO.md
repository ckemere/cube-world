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
