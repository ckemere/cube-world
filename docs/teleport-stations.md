# Teleport Stations

Fast travel across the Earth-scale world (≈1 km/block) through a network of
teleport stations. You reach a station by paying lapis at another — the network
grows as stations are built.

## Building a station

1. Lay a **3×3 floor of amethyst blocks**.
2. Craft a **Teleporter Core** and place it on the **center** block.

```
Teleporter Core recipe (shaped):     The station:
  S E S     S = sculk shrieker           [ Core ]   <- lodestone (right-click)
  E C E     E = eye of ender            A A A A A   <- 3×3 amethyst pad
  S E S     C = conduit
```

Raising a station announces its **four-word code** (e.g. *nine red roaring
delta*) — its address on the network. Rename the Core in an anvil first to name
the station.

### Ownership & protection

Whoever places a station **owns** it. The owner breaks the Core normally, which
removes the station and splices it out of both rings.

A non-owner *can* steal a Core, but it's **warded**: it takes **3 Minecraft days
of cumulative mining** (72,000 ticks ≈ 1 hour of real mining time) to work it
loose, and the whole time the **Elder Guardian's mining-fatigue curse** dogs the
miner. A boss-bar gauge shows how close they are; the wear is **credited by
wall-clock mining time** (so no pickaxe mines it faster) and **persists** across
sessions, so an attacker can chip away — but an hour of mining to grief one
teleporter is deterrent enough. The **amethyst pad is fully protected** from
non-owners, and the 30 preloaded **city stations have no owner and can't be mined
by anyone** (they seed the whole network).

### Active vs inactive

A station works only while its **full 3×3 amethyst pad is intact**. Mine a pad
block (owners only — or let a creeper do it) and the station goes **inactive**:
its ambient shimmer turns to grey smoke and right-clicking it just says it needs
its pad restored. Replace the amethyst and it's live again.

## Traveling (the trade menu)

**Right-click a station** to open its trade menu — a real villager-style trade
window. Each row shows a **map of the destination** as the result and its price
in lapis. Select the row (the client loads your lapis into the slots and lights
the arrow), then **take the result to travel** — the lapis is spent and you're
teleported (you don't keep the map; it's just the ticket preview).

Every station always offers **two destinations** — its neighbours in two
independent rings that thread the whole network, so you can reach anywhere by
hopping, and each ring is an alternate route. Cost scales with distance
(≈1 lapis / 1500 blocks, capped 64).

Two more trades appear depending on what you hold:
- **Hold a ticket book** (any book containing a station's four-word code) → that
  station is offered as a third trade. Codes are matched **case-insensitively**,
  so you can write your own ticket by copying the four words into any book.
- **Hold a book & quill** → a *"buy return ticket to here"* trade. This is a
  genuine trade — **1 book & quill + 4 lapis → a written-book ticket** for this
  station — so it costs a book to get a book, and you can get back later.

Every destination row shows its code, so you can learn and share addresses.

## The preloaded city network

The 30 historical cities each ship with a station at the town center, seeding the
rings from the start. They show as **teal diamonds** on the live map.

## Admin / testing commands

| Command | Effect |
|---|---|
| `/cubeworld tpcore` | Give yourself a Teleporter Core. |
| `/cubeworld tpstations` | List stations with their codes. |
| `/cubeworld tpoffers <name>` | Show a station's two ring destinations. |
| `/cubeworld tpticket <name>` | Give a ticket book to that station (round-trip checked). |
| `/cubeworld tpto <name>` | Travel to a named station (charges lapis). |
| `/cubeworld tpremove <name>` | Deregister a station. |
| `/cubeworld tpsim <x> <y> <z>` | Build a test pad+core and raise it. |

## Implementation

- **`Ticketing`** (pure, unit-tested) — deterministic four-word codes and
  case-insensitive parsing of book text.
- **`RingNetwork`** (pure, unit-tested) — two independent rings + `twoDestinations`
  (two distinct next-hops).
- **`TeleportService`** — the Core item + recipe, the station registry with codes
  (`stations.csv`) and rings (`rings.csv`), offer resolution, ticket-book
  create/parse, distance cost, and `travel`.
- **`TeleporterListener`** — raise/remove, right-click → build a merchant of
  trades, `PlayerPurchaseEvent` → travel (intercept a destination row) or let a
  return-ticket trade complete for real, and build the 30 city stations as
  chunks load.
- The live map reads `stations.csv` and paints each station as a teal diamond.
