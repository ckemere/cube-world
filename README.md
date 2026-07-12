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

The core topology works end to end on a flat test world (six 800-block faces, one biome per face so seams are obvious):

- **Geometry core** (`geometry/`, pure Java, unit-tested): the cross layout, the 7 stitched cube edges with their crossing transforms, margin/mirror mappings, and the 14 cube-vertex pillar sites.
- **Seam teleportation**: players and all other entities crossing a stitched edge teleport with rotated position, view, and velocity. Walking east around the equator or south over the pole loops seamlessly.
- **Mirrored margins**: 96 blocks of terrain beyond every stitched edge generate as a live view of the far side (matching biomes); player edits near seams propagate into mirrors, and edits *in* mirrors forward to the real blocks (block states rotated appropriately).
- **Corner pillars**: full-height immutable bedrock cylinders at every net image of the 8 cube vertices, where consistent rendering is geometrically impossible.
- **Virtual entities v1**: real entities near seams get AI-less clone puppets in the margin, synced every tick; damage to a clone forwards to its source. Player clones not yet implemented.

Next up: player clones across seams, sound/effect mirroring, and the Earth-map chunk generator (quadrilateralized spherical cube projection over real elevation/biome data).

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
| `/cubeworld face` | Which cube face you're on, with face-local coordinates. |
| `/cubeworld tp <face>` | Teleport to a face center (`north_pole`, `eq_prime`, `eq_east`, `eq_back`, `eq_west`, `south_pole`). |
| `/cubeworld simulate <fromX> <fromZ> <toX> <toZ> <yaw>` | Dry-run the seam crossing logic from the console. |
| `/cubeworld mirrorpush / marginbreak / marginplace` | Debug hooks for the mirror-sync paths (console/RCON testing). |

## License

[GPL-3.0](LICENSE)
