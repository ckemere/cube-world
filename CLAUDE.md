# CubeWorld — Paper plugin

Minecraft world structured as six cube faces so the map wraps at the date line and poles (see README.md for the concept).

## Hard constraints (every feature)

- Server-side only. Must work on a stock vanilla client: no client mod, no resource pack assumed.
- Custom items = vanilla base item + item data components (custom name/lore/components), never new item types.
- Pseudo-geometry = display entities. Custom UI = inventory GUIs, not custom screens.
- Anything the player sees must render correctly on an unmodified client.

## API accuracy

Training-data Bukkit/Spigot patterns predate the 2026 Paper hard fork and are often wrong. Check docs.papermc.io for current APIs before writing code against memory. Known traps:

- paper-api coordinates: `io.papermc.paper:paper-api:<mcver>.build.<n>-<status>` (pinned in build.gradle.kts; get exact strings from repo.papermc.io maven-metadata.xml when bumping).
- Paper 26.1+ requires **Java 25** — for BOTH compile and *run*. Gradle's toolchain provisions 25 for the build, but the **system default `java` is 21** (`/usr/lib/jvm/java-21-openjdk-amd64`), and Paper won't even load under it (`class file version 69.0 vs 65.0`). A stable system 25 is available: `sudo apt install -y openjdk-25-jdk`. Leave the *default* `java` at 21 (Gradle 9.6's daemon runs on it); `run-server.sh` auto-picks a Java ≥25 for the server.
- `plugin.yml` is still the manifest; paper-plugin.yml remains experimental.

## Build & test workflow

- `./gradlew build` — jar lands in `build/libs/`; runs JUnit tests (geometry core is pure Java, test it hard).
- **Run the dev server with `./run-server.sh`, NOT `./gradlew runServer`.** On this 7.6 GB box gradle's ~4 GB daemon on top of the server OOM-kills the machine (it has repeatedly crashed Claude Code and the server). `run-server.sh` launches Paper directly — no daemon, capped 2.6 GB heap — and auto-resolves the two things that are easy to get wrong: it picks a **Java ≥25** (default `java` is 21 → Paper fails to load) and runs the **paperclip bundler** jar (`~/.gradle/caches/run-task-jars/paper/jars/<mcver>/<build>.jar`, Main-Class `io.papermc.paperclip.Main`), NOT `run/versions/<ver>/paper-<ver>.jar` (the extracted server, which dies with `NoClassDefFoundError: joptsimple/OptionException`). MC version + Paper build are read from build.gradle.kts. One-time: prime the bundler cache with a single `./gradlew runServer` (Ctrl-C once it starts) if it's not cached yet.
- Detached (for driving via RCON): `tmux new-session -d -s cubeworld -x 200 -y 50` then `tmux send-keys -t cubeworld './run-server.sh 2>&1 | tee <scratchpad>/runserver.log; exec bash' Enter`. (No `--console=plain` needed — it's a plain `java`, not gradle.)
- Drive the server over RCON (enabled in `run/server.properties`, port 25575, password `cubeworld-dev`), e.g. `python3 <scratchpad>/rcon.py '<command>' ...` — responses come back inline. Chunks are unloaded without players: `forceload add x1 z1 x2 z2` before block probes, `forceload remove all` after. Bare `execute if block ...` returns "Test passed/failed". For bulk offline block inspection prefer reading the region files directly (Anvil+NBT) over slow per-block RCON — see `tools/anvil_reader.py`.
- To restart with a new build: RCON `stop` (or `tmux kill-session -t cubeworld`); wait for exit; `./gradlew build`; relaunch `./run-server.sh`. **Stop the server before building** (frees RAM for the 4 GB build; don't run a build and a server at once).
- The world generator is wired via `run/bukkit.yml` (`worlds.world.generator: CubeWorld`); plugin.yml needs `load: STARTUP` or the default world ignores the generator. Regenerating terrain requires deleting `run/world*`. On a **fresh world the first boot logs "Village anchor: no cubeworld structure sets found"** (the cities datapack is written during that boot) — restart once and the 30 cities anchor on the second boot.
- After implementing a feature: build, restart the dev server, and tell the user exactly what to type in-game to test it.
