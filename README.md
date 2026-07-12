# CubeWorld

A Paper plugin that makes a Minecraft world behave like the surface of a planet: walk west across the international date line and you arrive in the far east of the map; walk over a pole and you come down the other side. No more invisible walls or endless procedurally-generated frontier — one finite, seamless, wrap-around world.

## How it works

A sphere can't be tiled with square blocks, but a cube can — and a cube is topologically a sphere. CubeWorld structures the map as the six faces of a cube laid out in a single Minecraft world. Each face is a square region of normal terrain; the faces are logically stitched together along their twelve shared edges.

Crossing an edge is handled with two cooperating mechanisms, both entirely server-side:

- **Teleportation** — when a player walks across the boundary of one face, the server teleports them to the corresponding position on the adjacent face, rotating their orientation and velocity to match how the two faces meet on the cube. Done at the right moment, the transition is imperceptible.
- **Virtual blocks** — teleportation alone would still let you *see* the edge of the world. To make the seam invisible, the server sends packet-level "virtual" copies of the terrain from the adjacent face into the visible margin beyond each edge, transformed to line up with the local face's coordinates. The blocks the client renders past a boundary are a live view of the neighboring face; they exist only on the wire, never in the world save.

Because everything is done with standard teleports and block packets, **a stock vanilla client works unmodified** — no client mod, no resource pack.

## Geometry notes

- Each cube edge joins two faces with a specific rotation (0°, 90°, 180°, or 270°); positions, look angles, and velocities are remapped accordingly on crossing.
- The eight cube corners are the degenerate points (analogous to the poles of a map projection): three faces meet, and the wrap-around margin there needs special handling.
- "Wraps at the international date line and poles" falls out naturally: travel in any fixed direction eventually returns you to your starting point, just as on a globe.

## Status

Early scaffolding. What exists today:

- Build pipeline (Paper API for Minecraft 26.1.2, Gradle Kotlin DSL, Java 25 toolchain).
- A `/cubeworld ping` command that replies in chat, proving the plugin loads end to end.

The face layout, edge-crossing teleportation, and virtual-block rendering described above are the roadmap, not yet implemented.

## Building and running

```sh
./gradlew build          # plugin jar in build/libs/
./gradlew runServer      # boot a dev Paper server (26.1.2) with the plugin loaded
```

The dev server lives in `run/`; accept the EULA in `run/eula.txt` on first launch. Requires Java 25 (the Gradle toolchain will locate or download one automatically).

## Commands

| Command | Description |
| --- | --- |
| `/cubeworld ping` | Replies "CubeWorld: pong!" — pipeline smoke test. |

## License

[GPL-3.0](LICENSE)
