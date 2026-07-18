# cubemap — sphere↔cube orientation previews

Reprojects Earth data through CubeWorld's `CubeSurface` embedding so we can see
where the seams and pillars fall before committing an orientation. The
projection math here is the single source of truth mirrored from
`src/main/java/com/ckemere/cubeworld/geometry/CubeSurface.java` — keep them in
sync.

## Setup

```bash
python3 -m pip install --user --break-system-packages -r requirements.txt
```

(No venv: this box's `python3 -m venv` ships without pip. `--user` keeps it out
of the OS packages.)

## Use

Run from this directory (`tools/cubemap`):

```bash
python -m cubemap all   --roll -75            # base + overlay + net
python -m cubemap orient --roll -75           # equirect with seam/pillar overlay
python -m cubemap net    --roll -75 --face-px 400
python -m cubemap sweep  --from -90 --to -60 --step 5   # contact sheet
```

Outputs land in `out/`. Natural Earth coastline data auto-downloads to `data/`
on first run.

- **red lines** = the 4 equatorial seams + the 2 polar-face boundaries
- **yellow dot** = pillar over ocean, **orange dot** = pillar over land
- `--roll` spins the cube about the polar axis (the main knob)
- `--tilt` tips the poles off the geographic poles (not recommended — moves the
  ice caps off the top/bottom faces and pillars into temperate latitudes)

## Current status

The base map is **schematic**: Natural Earth coastlines + a latitude-band tint,
enough to judge orientation. When we want a data-accurate biome preview, add a
`from_raster` builder in `earthdata.py` (WorldClim/GEBCO) with the same output
contract — an `H×W×3` uint8 array, lon −180..180 across, lat 90..−90 down — and
everything downstream works unchanged.
