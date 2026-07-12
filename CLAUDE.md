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

- `./gradlew build` — jar lands in `build/libs/`.
- Dev server runs in tmux session `cubeworld` (`./gradlew runServer`, Paper 26.1.2, world/config in `run/`).
- To restart with a new build: `tmux send-keys -t cubeworld 'stop' Enter`, wait for the Gradle task to exit, rebuild, then relaunch `./gradlew runServer` in the tmux session.
- Server console: `tmux send-keys -t cubeworld '<command>' Enter`; log is visible via `tmux capture-pane -pt cubeworld`.
- After implementing a feature: build, restart the dev server, and tell the user exactly what to type in-game to test it.
