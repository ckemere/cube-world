# CubeWorld — Paper plugin

Minecraft world structured as six cube faces so the map wraps at the date line and poles (see README.md for the concept).

## Hard constraints (every feature)

- Server-side only. Must work on a stock vanilla client: no client mod, no resource pack assumed.
- Custom items = vanilla base item + item data components (custom name/lore/components), never new item types.
- Pseudo-geometry = display entities. Custom UI = inventory GUIs, not custom screens.
- Anything the player sees must render correctly on an unmodified client.

## API accuracy

Training-data Bukkit/Spigot patterns predate the 2026 Paper hard fork and are often wrong. Check docs.papermc.io for current APIs before writing code against memory.

**Read the decompiled server source. Do not reason about vanilla/CraftBukkit behaviour from memory — the full remapped Java is on this box.** paperweight's dev bundle ships sources, not just classes:

```bash
JAR=$(ls -d ~/.gradle/caches/paperweight-userdev/v2/work/applyDevBundlePatches_*/output.jar | head -1)
javap -p -cp "$JAR" org.bukkit.craftbukkit.map.CraftMapView          # fields + signatures
unzip -o -q "$JAR" "*/MapItemSavedData.java" -d /tmp/nms             # actual method bodies
grep -rn trackingPosition /tmp/nms                                    # then just read it
```

`javap -p` answers "what exists"; unzipping answers "what does it do". Either takes under a minute, and **both beat a plausible-sounding guess every time**. Guessing here is not cheap — in one session it produced a per-frame full-canvas repaint to prevent an overwrite that `CraftMapView`'s per-renderer canvases make impossible, a "cursor merge" that read our own canvas and therefore merged nothing, and an invented risk of deleting treasure-map decorations that one `grep` disproves. All three were reverted; none needed to be written.

Corollary: when you catch yourself writing "probably", "I suspect", "this may be because", or proposing a fix whose justification you have not verified — stop and go read the source or measure it. If something genuinely cannot be established (rendering that needs a real client, say), state that plainly instead of filling the gap with a theory.

**Comments in this repo are not automatically ground truth.** Commits are authored `Caleb (Claude) Kemere`, so a confident-sounding comment may be a previous session's *hypothesis*. Treat them as leads to verify — e.g. `tools/playermap/structures/compute.py` asserts "the biome filter is not what fails", but it only ever measured two of the seventeen overlay layers.

Known traps:

- paper-api coordinates: `io.papermc.paper:paper-api:<mcver>.build.<n>-<status>` (pinned in build.gradle.kts; get exact strings from repo.papermc.io maven-metadata.xml when bumping).
- Paper 26.1+ requires **Java 25** — for BOTH compile and *run*. Gradle's toolchain provisions 25 for the build, but the **system default `java` is 21** (`/usr/lib/jvm/java-21-openjdk-amd64`), and Paper won't even load under it (`class file version 69.0 vs 65.0`). A stable system 25 is available: `sudo apt install -y openjdk-25-jdk`. Leave the *default* `java` at 21 (Gradle 9.6's daemon runs on it); `run-server.sh` auto-picks a Java ≥25 for the server.
- `plugin.yml` is still the manifest; paper-plugin.yml remains experimental.

## Build & test workflow

- `./gradlew build` — jar lands in `build/libs/`; runs JUnit tests (geometry core is pure Java, test it hard).
- **Run the dev server with `./run-server.sh`, NOT `./gradlew runServer`.** On this 7.6 GB box gradle's ~4 GB daemon on top of the server OOM-kills the machine (it has repeatedly crashed Claude Code and the server). `run-server.sh` launches Paper directly — no daemon, capped 2.6 GB heap — and auto-resolves the two things that are easy to get wrong: it picks a **Java ≥25** (default `java` is 21 → Paper fails to load) and runs the **paperclip bundler** jar (`~/.gradle/caches/run-task-jars/paper/jars/<mcver>/<build>.jar`, Main-Class `io.papermc.paperclip.Main`), NOT `run/versions/<ver>/paper-<ver>.jar` (the extracted server, which dies with `NoClassDefFoundError: joptsimple/OptionException`). MC version + Paper build are read from build.gradle.kts. One-time: prime the bundler cache with a single `./gradlew runServer` (Ctrl-C once it starts) if it's not cached yet.
- **Since 2026-08-18 both servers run under systemd** (units + sudoers in `deploy/`, installed system-wide): `cubeworld-server` (Paper via `run-server.sh`) and `cubeworld-map` (playermap on **port 80**, not 8080). Passwordless control via a sudoers drop-in, but it matches EXACT arguments — `sudo systemctl start|stop|restart|status cubeworld-server|cubeworld-map` with **no extra flags** (`--no-pager` etc. → password prompt/denied). Server log: `run/logs/latest.log` (rotates per start; after a restart, wait for a NEW "Done" — grep can match the previous instance's line). Map redeploy without sudo: edit `server.py`, kill its process — `Restart=always` respawns it on the new code. The old tmux workflow is retired.
- Drive the server over RCON (enabled in `run/server.properties`, port 25575, password `cubeworld-dev`), e.g. `python3 <scratchpad>/rcon.py '<command>' ...` — responses come back inline. Chunks are unloaded without players: `forceload add x1 z1 x2 z2` before block probes, `forceload remove all` after. Bare `execute if block ...` returns "Test passed/failed". For bulk offline block inspection prefer reading the region files directly (Anvil+NBT) over slow per-block RCON — see `tools/playermap/anvil.py`.
- To restart with a new build: `sudo systemctl stop cubeworld-server` (or RCON `stop` — a clean stop STAYS down by design, `Restart=on-failure`); `./gradlew build`; `sudo systemctl start cubeworld-server`. **Stop the server before building** (frees RAM for the 4 GB build; don't run a build and a server at once). One-off flag runs (e.g. `-Dcubeworld.placeWatch=true`): stop the unit, run `./run-server.sh <flags>` by hand, and restart the unit when done.
- The world generator is wired via `run/bukkit.yml` (`worlds.world.generator: CubeWorld`); plugin.yml needs `load: STARTUP` or the default world ignores the generator. **Regenerating terrain = delete `run/world*` AND `run/plugins/CubeWorld/{stations.csv,rings.csv}`** (the teleport registry). If you delete the world but keep the registry, `seedCities` sees the 30 cities as already built, skips them, and the old stations end up pointing at empty terrain with no physical pad. The biome rasters under `run/plugins/CubeWorld/biomes/` also need rebuilding (seed_cities.py does that).
- **Fresh-world bring-up (2 steps — automate, don't hand-drive):** (1) `./run-server.sh` — ONE boot suffices (no datapack, no double boot): city structures are registered in code (`CityStructures`); the log shows "City structures: 30/30 registered in code" then "City anchor: 30 cities anchored in 'world' (per-city structures, WorldInit)". Each city is its own registry entry (`cubeworld:antioch`, …) — `/locate structure cubeworld:<city>` works, and `/place structure cubeworld:<city>` works even with `-Dcubeworld.anchorCities=false` (raw-terrain probing). (2) `python3 tools/seed_cities.py` — force-loads all 30 city chunks so every teleport **station** builds and both rings fill to 30/30, **and** regenerates the map biome data (`cubeworld biomeraster overworld|nether` + `dumpbiomeparams` → `run/plugins/CubeWorld/biomes/*.cwbr`), then saves + clears force-loads. Both are needed after a regen: without the station seeding the teleport network shows "The network is still forming"; without the biome rasters the web map's **structure overlay comes up empty** (it biome-filters against `overworld.cwbr`). Then restart the map webserver: `sudo systemctl restart cubeworld-map` (serves on :80).
- **Look at the terrain, don't just count blocks.** `python3 tools/voxcam.py <x> <z> --yaw 45 --pitch 42 -r 200 -o shot.png` renders an arbitrary camera straight from the region files in a couple of seconds and ~130 MB (no client, no server, runs beside the dev server). Magenta = a block missing from realmap.py's palette, not a world bug. Terrain problems that took three regen cycles to chase through block statistics have been settled by one aerial frame.
- After implementing a feature: build, restart the dev server, and tell the user exactly what to type in-game to test it.
