# Teleport Stations

Fast travel across the Earth-scale world (≈1 km/block) through a network of
player- and city-built teleport stations. You can only travel *to* a station
that exists, so the network grows as you build.

## Building a station

1. Lay a **3×3 floor of amethyst blocks**.
2. Craft a **Teleporter Core** and place it on the **center** block.

```
Teleporter Core recipe (shaped):     The station (side view):
  E A E     E = ender pearl              [ Core ]   <- lodestone (right-click)
  A L A     A = amethyst shard          A A A A A   <- 3×3 amethyst pad
  E A E     L = lodestone
```

Placing the core on a valid pad raises the station (you'll see
"Teleport station raised"). **Rename the core in an anvil first** to name the
station; otherwise it's named from its coordinates.

Break the core to remove your station and get the core back. City stations are
protected and can't be broken.

## Traveling

**Right-click a station's core** to open the network menu:

- **Nearby stations** are listed as clickable destinations — each shows its
  distance and lapis cost. Click one to pay and teleport.
- **Hold a drawn map** covering a distant station when you right-click, and that
  far station is unlocked as a destination (a precise long jump).
- Cost scales with distance (≈1 lapis per 1500 blocks, capped at 64), so a
  denser network of your own stations is cheaper to hop across.

## The preloaded city network

The 30 historical cities each ship with a station built at the town center, so
there's a network to travel between from the start. They show as **teal
diamonds** on the live map (alongside the amber city dots and violet stronghold
rings).

## Admin / testing commands

| Command | Effect |
|---|---|
| `/cubeworld tpcore` | Give yourself a Teleporter Core (skip crafting). |
| `/cubeworld tpstations` | List registered stations + pending city builds. |
| `/cubeworld tpto <name>` | Travel to a named station (charges lapis). |
| `/cubeworld tpremove <name>` | Deregister a station. |
| `/cubeworld tpsim <x> <y> <z>` | Build a test pad+core and raise it (console-safe). |

## How it works (implementation)

- A station is a **reskinned lodestone** (`TeleportService.createCore`,
  PDC-marked) on a 3×3 amethyst pad. The registry (`stations.csv` in the plugin
  data folder) is the source of truth for which lodestones are stations.
- `TeleporterListener` raises on place (`tryRaise` checks the pad), removes on
  break, opens the menu on right-click, travels on menu click, and builds the
  30 city stations as their chunks load (`seedCities` skips ones already built,
  so restarts don't re-stack them).
- `TeleportService.travel` charges lapis and teleports; the GUI and
  `/cubeworld tpto` share it.
- The live map reads `stations.csv` and paints each station as a teal diamond.
