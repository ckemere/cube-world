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

## Run
```bash
python3 -m cubemap globe          # first, generate the globe the map embeds
cd ../playermap && python3 server.py
```
Serves on :8080 (PORT overrides). Env: RCON_HOST/RCON_PORT/RCON_PW, FACE_SIZE,
WORLD_REGION_DIR (defaults to the dev server's overworld region dir), SAVE_EVERY.
Open http://<host>:8080/ — drag to spin; players are red pins, explored ground
shows real blocks.
