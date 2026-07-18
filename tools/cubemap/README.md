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
python -m cubemap all   --roll -70            # base + overlay + net
python -m cubemap orient --roll -70           # equirect with seam/pillar overlay
python -m cubemap net    --roll -70 --face-px 400
python -m cubemap sweep  --from -90 --to -60 --step 5 --mode overlay   # contact sheet
python -m cubemap globe                        # spinnable WebGL globe (real elevation)
```

Outputs land in `out/`. Natural Earth coastline data auto-downloads to `data/`
on first run. The default orientation is the locked `EARTH_ROLL_DEG` (−70°).

### globe

`globe` reprojects real ETOPO 2022 elevation onto the six faces (hypsometric
tint + latitude snow line), inlines them as JPEG data URIs, and emits a
self-contained `out/globe.html` — a drag-to-spin cube that morphs to a sphere,
raw WebGL so it runs under the artifact CSP with no libraries. It needs the
ETOPO grid at `data/etopo_60s.nc`:

```bash
curl -o data/etopo_60s.nc \
  "https://www.ngdc.noaa.gov/thredds/fileServer/global/ETOPO2022/60s/60s_surface_elev_netcdf/ETOPO_2022_v1_60s_N90W180_surface.nc"
python3 -m pip install --user --break-system-packages netCDF4
```

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
