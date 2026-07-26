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

## 3. Village ground fill — algorithm is still wrong

Symptom: dirt still appears under roofs at Antioch.

Cause: the plinth level is a **per-column** minimum (with a 1-block neighbour ring),
not a per-STRUCTURE minimum. A column containing only an eave or roof overhang has
that roof as its own lowest village block, so the fill starts just under the roof
and packs the air beneath it.

Agreed direction: take the minimum Y over the **whole structure**, so the fill
reference is the true foundation and can never sit under a roof.

Vanilla already knows the buildings, which avoids all material guesswork:
- `StructureManager.getStructureAt(BlockPos, Structure)` / `startsForStructure(...)`
- `StructureStart.getBoundingBox()` and its `PiecesContainer`
- `StructurePiece.getBoundingBox()` — one box per jigsaw element, i.e. per building

Still to decide (see discussion): whole-village minimum vs per-piece minimum, and
how to handle buildings that span chunks.

## 4. Open: stilted village columns

13.8% of Antioch's village columns still have >=3 blocks of air beneath. Should be
resolved by fixing item 3.
