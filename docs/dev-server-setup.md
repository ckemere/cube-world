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
