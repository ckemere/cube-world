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
- Paper 26.1+ requires Java 25 (toolchain is pinned; Foojay resolver provisions it).
- `plugin.yml` is still the manifest; paper-plugin.yml remains experimental.

## Build & test workflow

- `./gradlew build` — jar lands in `build/libs/`; runs JUnit tests (geometry core is pure Java, test it hard).
- Dev server runs in tmux session `cubeworld`: `./gradlew runServer --console=plain 2>&1 | tee <scratchpad>/runserver.log; exec bash`. Plain console + tee is required — Gradle's rich console silently swallows stdin, so console commands typed via tmux never reach the server.
- Preferred way to drive the server: RCON (enabled in `run/server.properties`, port 25575, password `cubeworld-dev`), e.g. `python3 <scratchpad>/rcon.py '<command>' ...` — responses come back inline. Chunks are unloaded without players: `forceload add x1 z1 x2 z2` before block probes, `forceload remove all` after. Bare `execute if block ...` returns "Test passed/failed".
- To restart with a new build: `tmux send-keys -t cubeworld 'stop' Enter` (works under plain console) or RCON `stop`; wait for exit, rebuild, relaunch runServer in the session.
- The world generator is wired via `run/bukkit.yml` (`worlds.world.generator: CubeWorld`); plugin.yml needs `load: STARTUP` or the default world ignores the generator. Regenerating terrain requires deleting `run/world*`.
- After implementing a feature: build, restart the dev server, and tell the user exactly what to type in-game to test it.
