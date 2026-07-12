---
description: Implement a CubeWorld feature end to end (build, restart dev server, give in-game test steps)
---

Implement the following as a Paper plugin feature: $ARGUMENTS

Follow the hard constraints and API-accuracy rules in CLAUDE.md: server-side only, stock vanilla client, no resource pack; item data components for custom items, display entities for pseudo-geometry, inventory GUIs for custom UI. Check docs.papermc.io for current API rather than recalling pre-2026 Bukkit/Spigot patterns.

When the implementation is done: run `./gradlew build` and fix any failures, restart the dev server with the new jar (see CLAUDE.md workflow), then tell me exactly what to type in-game to test the feature.
