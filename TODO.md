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
