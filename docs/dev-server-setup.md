# Standing up a CubeWorld dev server on a fresh machine

Written for a second box used to experiment on world generation without
disturbing the main dev server. Debian/Ubuntu commands; adapt as needed.

## 1. Packages

```bash
sudo apt update
sudo apt install -y \
    openjdk-25-jdk openjdk-21-jdk \
    git tmux curl unzip \
    python3 python3-numpy python3-pil
```

**Both JDKs are deliberate.** Paper 26.1+ needs **Java 25** to compile *and* run —
it will not even load under 21 (`class file version 69.0 vs 65.0`). But Gradle
9.6.1's daemon wants 21, so leave the *system default* `java` at 21 and let
`run-server.sh` pick a Java >= 25 for the server, which it does automatically.

```bash
sudo update-alternatives --config java     # choose the 21 entry
java -version                              # should say 21
ls -d /usr/lib/jvm/*25*                    # 25 must exist for the server
```

`python3-numpy` and `python3-pil` are needed by `tools/voxcam.py` and the web map.
No pip installs are required.

## 2. Clone and get the data

```bash
git clone https://github.com/ckemere/cube-world.git
cd cube-world
git checkout nightly
```

**`run/` is gitignored in its entirety**, so a fresh clone has no world data, no
server config, and — critically — **no Earth rasters**. The generator cannot do
anything without them. Copy these from a working box:

| file | size | what it is |
|---|---|---|
| `run/earth.dat` | ~343 MB | elevation / temperature / precipitation rasters |
| `run/coast.dat` | ~4.5 MB | coast-distance sidecar (CWE1) |

```bash
mkdir -p run
scp you@mainbox:~/projects/cube-world/run/earth.dat run/
scp you@mainbox:~/projects/cube-world/run/coast.dat run/
```

Copying beats regenerating: `earth.dat` is built out-of-core from ETOPO source and
regenerating it on a small box will OOM the machine.

## 3. Server config

`run/eula.txt`:

```
eula=true
```

`run/bukkit.yml` — **this is what wires the generator in**; without it the default
world ignores CubeWorld entirely:

```yaml
worlds:
  world:
    generator: CubeWorld
  world_nether:
    generator: CubeWorld
```

`run/server.properties` — the settings that matter:

```properties
level-name=world
level-type=minecraft\:normal
online-mode=false
enforce-secure-profile=false
enable-rcon=true
rcon.port=25575
rcon.password=cubeworld-dev
broadcast-rcon-to-ops=true
view-distance=6
max-players=20
```

RCON is not optional for this workflow — every tool in `tools/` drives the server
through it. `online-mode=false` lets test clients and the azalea bot join without
Microsoft auth.

## 4. Prime the Paper bundler cache (one time)

```bash
./gradlew runServer      # Ctrl-C as soon as it finishes starting
```

This populates `~/.gradle/caches/run-task-jars/paper/jars/26.2/60.jar`, the
**paperclip bundler** that `run-server.sh` launches. Do not use
`run/versions/26.2/paper-26.2.jar` — that is the extracted server and dies with
`NoClassDefFoundError: joptsimple/OptionException`.

After this once, never run `./gradlew runServer` again — see the RAM warning.

## 5. Build and run

```bash
./gradlew build          # jar lands in build/libs/
./run-server.sh          # NOT ./gradlew runServer
```

Detached, so RCON can drive it:

```bash
tmux new-session -d -s cubeworld -x 200 -y 50
tmux send-keys -t cubeworld './run-server.sh 2>&1 | tee /tmp/runserver.log; exec bash' Enter
```

`run-server.sh` forwards extra args to the JVM:

```bash
./run-server.sh -Dcubeworld.anchorCities=false   # raw terrain, no city villages
./run-server.sh -Dcubeworld.villageFix=false     # no village ground repair
```

Both are useful for generation work — they remove everything downstream of the
generator so you are looking at raw output.

## 6. Fresh-world bring-up (three steps, automate it)

```bash
./run-server.sh                       # boot 1: logs "no cubeworld structure sets found"
#   (it writes the cities datapack during this boot; stop it)
./run-server.sh                       # boot 2: logs "30 cities anchored across 6 tiers"
python3 tools/seed_cities.py          # builds all 30 teleport stations + biome rasters
cd tools/playermap && python3 server.py &      # web map on :8080
```

Skipping step 3 leaves the teleport network reporting "still forming" and the map's
structure overlay empty.

Regenerating terrain means deleting **`run/world*` AND
`run/plugins/CubeWorld/{stations.csv,rings.csv}`**. Keep the registry and
`seedCities` thinks the 30 cities already exist, skips them, and the stations end
up pointing at empty terrain.

## 7. RAM — the one that bites

A Paper server at 2.6 GB heap plus a Gradle build at ~4 GB will OOM-kill a small
box. There is usually no swap.

**Stop the server before every build.** This has killed the machine repeatedly,
disconnecting players mid-session:

```bash
python3 - <<'PY'
# or just: RCON "stop"
PY
./gradlew build
./run-server.sh
```

If you have the RAM (16 GB+) this is a non-issue and you can ignore it.

## 8. Working on world generation specifically

```bash
# the six climate params, the biome, and the density target at a column
/cubeworld climateat <x> <z>
/cubeworld biomeat   <x> <z>
/cubeworld terrainprobe <x> <z>      # "natural" = the density path's own target
/cubeworld biomecensus               # global share per biome
/cubeworld findlatlon <lat> <lon>    # real coordinates -> world x,z

# look at it, rather than counting blocks
python3 tools/voxcam.py <x> <z> --yaw 45 --pitch 42 -r 200 -o shot.png
```

**Before changing any code, reproduce vanilla's biome choice offline.**
`run/plugins/CubeWorld/biomes/overworld_params.json` is vanilla's real parameter
list (7594 entries), each `[biome, tMin,tMax, hMin,hMax, cMin,cMax, eMin,eMax,
dMin,dMax, wMin,wMax, offset]` scaled by 10000. Vanilla's fitness is: per axis
`d = max(v-hi, lo-v, 0)`, sum of `d*d`, plus `offset*offset`; lowest wins.

```python
import json
E = json.load(open('run/plugins/CubeWorld/biomes/overworld_params.json'))
def dist(v, lo, hi):
    a = v - hi; b = lo - v
    return a if a > 0 else max(b, 0)
def fitness(e, pt):
    _, tl,th, hl,hh, cl,ch, el,eh, dl,dh, wl,wh, off = e
    return sum(dist(v, lo, hi) ** 2 for v, (lo, hi) in
               zip(pt, ((tl,th),(hl,hh),(cl,ch),(el,eh),(dl,dh),(wl,wh)))) + off*off
# params scaled by 10000: T, H, C, E, depth, W
print(sorted((fitness(e, (-100,-2300,8900,4600,400,-1700)), e[0]) for e in E)[:5])
```

That answers "why did I get this biome" in seconds and with certainty. Selection is
**nearest-neighbour, not box containment**, which is the single biggest source of
surprises — see `TODO.md` item 7 for the full set of traps.

## 9. Regenerating `earth.dat` and `coast.dat` from source

Copying is faster, but the pipeline is reproducible. Everything below was read
out of the code and the existing files' headers, and both download URLs were
checked live (HTTP 200) on 2026-07-27.

### What the files actually contain

`run/earth.dat` — CWE1, roll **-70.0** (the locked orientation), **5 layers**, 343 MB:

| layer | grid | scale | source |
|---|---|---|---|
| `height` | 10800x5400 | 1.0 | ETOPO 2022, 60 arc-sec, downsampled by 2 |
| `temp` | 2160x1080 | 0.1 | WorldClim v2.1 10m **BIO1** (annual mean temp) |
| `precip` | 2160x1080 | 1.0 | WorldClim v2.1 10m **BIO12** (annual precip) |
| `river` | 10800x5400 | 0.001 | Natural Earth rivers/lakes, as a distance ramp |
| `river_y` | 10800x5400 | 1.0 | river surface elevation |

`run/coast.dat` — CWE1, **1 layer** `coast` 2160x1080, 4.5 MB: true
distance-to-ocean in km, chamfer transform. Derived FROM `earth.dat`, so it must
be rebuilt whenever `earth.dat` changes.

### Extra Python packages

Beyond `python3-numpy` / `python3-pil` from section 1:

```bash
python3 -m pip install --user --break-system-packages netCDF4 tifffile imagecodecs
```

`netCDF4` reads the ETOPO `.nc`; `tifffile` reads the WorldClim GeoTIFFs.
**`imagecodecs` is not optional** — the WorldClim TIFFs are LZW-compressed and
`tifffile` cannot decode them without it, so the export dies partway through with
`ValueError: <COMPRESSION.LZW: 5> requires the 'imagecodecs' package`.
(`--break-system-packages` because this box's `python3 -m venv` ships without pip;
a venv is cleaner if yours works.)

If `python3 -m pip` reports "No module named pip", bootstrap it without root:

```bash
curl -sSL -o get-pip.py https://bootstrap.pypa.io/get-pip.py
python3 get-pip.py --user --break-system-packages
```

### Source data

Into `tools/cubemap/data/`:

```bash
mkdir -p tools/cubemap/data && cd tools/cubemap/data

# ETOPO 2022, 60 arc-second surface elevation (~450 MB)
curl -o etopo_60s.nc \
  "https://www.ngdc.noaa.gov/thredds/fileServer/global/ETOPO2022/60s/60s_surface_elev_netcdf/ETOPO_2022_v1_60s_N90W180_surface.nc"

# WorldClim v2.1 10-minute bioclimatic variables (~130 MB zip)
curl -O "https://geodata.ucdavis.edu/climate/worldclim/2_1/base/wc2.1_10m_bio.zip"
unzip -j wc2.1_10m_bio.zip 'wc2.1_10m_bio_1.tif' 'wc2.1_10m_bio_12.tif'
```

**Natural Earth river and lake vectors must be fetched by hand.** The auto-download
in `cubemap/earthdata.py` only fetches the coarse **110m** layers, which are used for
preview renders. The `export` command reads the **10m** ones, and if they are absent
it silently writes an ALL-ZERO river layer -- `earth.dat` looks fine, the header
lists `river` and `river_y`, and the world simply has no rivers anywhere. That
happened once and was only caught by probing the raster directly.

```bash
cd tools/cubemap/data
BASE=https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson
curl -O "$BASE/ne_10m_rivers_lake_centerlines.geojson"   # ~7 MB, 1455 features
curl -O "$BASE/ne_10m_lakes.geojson"                     # ~5 MB, 1355 features
```

Coastline vectors for the preview renders do auto-download, so nothing to do there.

### Build

```bash
# 1. earth.dat  (STOP THE SERVER FIRST -- see the memory note below)
cd tools/cubemap
python3 -m cubemap export --dest ../../run/earth.dat

# 2. coast.dat  (reads run/earth.dat, so it must come second)
cd ../..
python3 tools/compact/build_coast.py            # default 2160 wide
```

`--roll` defaults to the locked `EARTH_ROLL_DEG` of -70. **Do not change it**
unless you intend to move every coastline and every one of the 30 anchored
cities. `--height-step 2` is what produces the 10800x5400 height layer; step 1
would quadruple the file.

### Memory

This is the step the memory note warns about. The export holds 10800x5400 arrays
(116 MB each as int16, 233 MB as float32 intermediates) for several layers at
once, so peak use runs to a couple of GB. **Stop the Paper server before running
it** or the box will OOM-kill something.

### Verify

```bash
python3 - <<'PY'
import struct
for path in ("run/earth.dat", "run/coast.dat"):
    with open(path, "rb") as f:
        magic = f.read(4); roll = struct.unpack("<f", f.read(4))[0]
        n = struct.unpack("<i", f.read(4))[0]
        print(f"{path}: {magic!r} roll={roll} layers={n}")
        for _ in range(n):
            name = f.read(8).rstrip(b"\0").decode()
            w, h = struct.unpack("<ii", f.read(8))
            sc, off = struct.unpack("<ff", f.read(8))
            print(f"   {name:8s} {w}x{h} scale={sc}")
PY
```

Expect exactly the five layers above with `roll=-70.0`. **Also check the river layer
is not empty** -- the header alone will not tell you. Read the raw arrays and count
non-zeros: `height`/`temp`/`precip` come out ~99%, and **`river` should be about
2-3%**. A `river` of 0.00% means the 10m Natural Earth vectors were missing when the
export ran, and the world will have no rivers at all. Then regenerate the world
(section 6) — the rasters are read at world-gen time, so existing chunks keep the
old terrain.

### Not part of the pipeline

`tools/compact/sst.py` is an **analysis script only** — it writes no file and no
sea-surface-temperature layer exists in `earth.dat`. It needs a WOA23 extract
fetched by hand (the URL is in its header) and exists to study ocean-temperature
coverage. Ignore it when rebuilding.

## Sea-surface temperature (the `sst` layer)

WorldClim is land-only, so every water column used to fall back to a latitude
proxy (`27 - 0.45*|lat|`). Measured against WOA23 that proxy is 5.3 C cold on
average, 6.4 C RMSE, and lands **64% of ocean area in the wrong vanilla
temperature band** — warm ocean survived only as a thin equatorial stripe.
Rebuild the layer with:

    curl -s "$(python3 -c "import sys;sys.path.insert(0,'tools/compact');import sst;print(sst.WOA_URL)")" \
         -o tools/compact/out/woa_sst.ascii     # ~680 KB, WOA23 1 deg annual mean
    python3 tools/compact/sst.py                # -> out/sst_total.npy (hole-free)
    python3 tools/compact/add_sst.py            # appends `sst` to run/earth.dat in place

`add_sst.py` streams, so it does not need the full ETOPO/WorldClim rebuild.
A full `export_earth` run picks `sst_total.npy` up automatically if present.
Kill switch: `-Dcubeworld.sst=false` restores the latitude proxy.
