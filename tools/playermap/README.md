# playermap — live player positions + real blocks on the globe

An HTTP server that overlays live players **and the world's real generated
blocks** onto the CubeWorld globe. It polls the Minecraft server over RCON,
folds each world (x, z) to a cube-surface point (the globe's embedding), and the
page projects a marker onto the spinning globe every frame.

Where chunks have actually been generated, the six face textures are repainted
with the **real surface block** of every column (read straight from the region
files) — grass, stone, sand, snow, water, forest canopy, etc. Unexplored areas
keep the biome/elevation model. As you explore, the map fills in with what the
generator actually made.

## How the real-block overlay works
- `anvil.py` — minimal Anvil region reader: pulls the `WORLD_SURFACE` top block
  of every column in every `minecraft:full` chunk.
- `realmap.py` — maps each column to its face-texture pixel (same embedding as
  the globe), colours it by block type, and composites over the model textures.
  Cached by region-file mtime; a throttled `save-all flush` (SAVE_EVERY, 20s)
  flushes freshly explored chunks so they appear within ~20s.
- The page live-refreshes textures from `/faces` every 8s, so exploration shows
  up without a reload.

Needs Pillow + numpy (already used by `../cubemap`); not stdlib-only anymore.

## Flat face maps (`faceview.py`)
**Click a cube face on the globe** and it opens `/face?f=<FACE>` — that one face
as a flat, window-filling map. Browser Back (or the `← globe` button) returns to
the rotating cube. On the flat page:

- **click anywhere → the world x,z under the pointer**, plus a live cursor
  readout, a pin, and copy buttons (`x z`, or a ready-made `/tp`). This is the
  thing the globe could never answer.
- structures and cities are a marker **overlay** with per-layer checkboxes,
  counts and hover labels. `confidence: "superset"` markers are drawn **hollow**
  — those layers over-report (see `structures/compute.SUPERSET_LAYERS`), so the
  marker may not exist in the world.
- drag pans, scroll zooms, double-click fits; a coordinate grid snaps to round
  world blocks and is clipped to the face (off the face, x,z belong to a
  different face).
- background select: composited terrain or the biome raster.

The 30 historical cities are a **real layer** now, on the globe as well: they
used to be anonymous amber dots baked into the face textures by
`realmap.composite_uris`, which meant they could be neither switched off nor
identified. `/cities` serves them with names, and the globe draws them on a
canvas overlay with a checkbox and hover labels.

### Routes
| route | serves |
| --- | --- |
| `/` | the rotating globe (also `/index.html`) |
| `/face?f=EQ_EAST[&u=&v=&dim=&seed=&src=]` | flat map of one face (`u,v` place the pin) |
| `/faceimage?f=EQ_EAST&bg=terrain\|biomes\|nether` | that face's texture as image bytes |
| `/markers?[face=&dim=&seed=]` | `{seed, faceSize, layers:{name:[{x,z,face,u,v,type,confidence}]}}` |
| `/cities` | the 30 cities, marker shape + the cube point the globe projects |
| `/fixtures/<name>.json` | development fixtures (see below) |

`/markers` is the shape to build against: `x,z` (world blocks) and `confidence`
are derived here from the overlay markers, which carry only face-local `u,v`.
`x = u*faceSize/2 + gridCol*faceSize` — the exact inverse of
`structures/cubegate.world_to_faceuv`, so a marker's `x,z` is the block its dot
sits on. Unknown paths now 404; they used to fall through to the globe, so a
typo returned 1.6 MB of the wrong page with a 200.

`fixtures/structures_sample.json` is a hand-written `/markers` response for
developing the page against a fixed set of markers:
`/face?f=EQ_EAST&src=/fixtures/structures_sample.json` (only `/fixtures/…`
sources are accepted).

## Run
```bash
python3 -m cubemap globe          # first, generate the globe the map embeds
cd ../playermap && python3 server.py
```
Serves on :8080 (PORT overrides). Env: RCON_HOST/RCON_PORT/RCON_PW, FACE_SIZE,
WORLD_REGION_DIR (defaults to the dev server's overworld region dir), SAVE_EVERY.
Open http://<host>:8080/ — drag to spin; players are red pins, explored ground
shows real blocks.
