# playermap — live player positions on the globe

A tiny stdlib-only HTTP server that overlays online players onto the CubeWorld
globe. It polls the Minecraft server over RCON, folds each world (x, z) to a
cube-surface point (the globe's embedding), and the page projects a marker
onto the spinning globe every frame.

## Run
```bash
python3 -m cubemap globe          # first, generate the globe the map embeds
cd ../playermap && python3 server.py
```
Serves on :8080 (PORT overrides). Env: RCON_HOST/RCON_PORT/RCON_PW, FACE_SIZE.
Open http://<host>:8080/ — drag to spin, players appear as red pins with names.
Needs the MC server's RCON enabled (run/server.properties).
