# Teleport Stations

Fast travel across the Earth-scale world (≈1 km/block) through a network of
teleport stations. You reach a station by paying lapis at another — the network
grows as stations are built.

## Building a station

1. Lay a **3×3 floor of amethyst blocks**.
2. Craft a **Teleporter Core** and place it on the **center** block.

```
Teleporter Core recipe (shaped):     The station:
  E A E     E = ender pearl              [ Core ]   <- lodestone (right-click)
  A L A     A = amethyst shard          A A A A A   <- 3×3 amethyst pad
  E A E     L = lodestone
```

Raising a station announces its **four-word code** (e.g. *nine red roaring
delta*) — its address on the network. Rename the Core in an anvil first to name
the station. Break the Core to remove your station and get it back; city
stations are protected.

## Traveling (the trade menu)

**Right-click a station** to open its trade menu. Each row is a destination
priced in lapis — **select a trade to travel** (you don't complete a real
trade; selecting acts immediately).

Every station always offers **two destinations** — its neighbours in two
independent rings that thread the whole network, so you can reach anywhere by
hopping, and each ring is an alternate route. Cost scales with distance
(≈1 lapis / 1500 blocks, capped 64).

Two more trades appear depending on what you hold:
- **Hold a ticket book** (any book containing a station's four-word code) → that
  station is offered as a third trade. Codes are matched **case-insensitively**,
  so you can write your own ticket by copying the four words into any book.
- **Hold paper** → a *"buy return ticket to here"* trade (1 paper + 4 lapis → a
  written-book ticket for this station), so you can get back later.

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
  trades, `TradeSelectEvent` → travel or print a return ticket, and build the 30
  city stations as chunks load.
- The live map reads `stations.csv` and paints each station as a teal diamond.
