"""Re-anchor the 30 historical cities after the cube net changed from the
cross to the equatorial band. Each city's true lon/lat is recovered from its
OLD (face, u, v) via the OLD fold, then re-projected through the NEW geometry
to fresh (face, u, v, x, z). Rewrites cities_globe.json (map markers) and
src/main/resources/cities_anchor.csv (village/station anchors)."""
import json
import os
import numpy as np

from cubemap.geometry import CubeProjection, GRID

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
GLOBE = os.path.join(ROOT, "tools", "playermap", "cities_globe.json")
ANCHOR = os.path.join(ROOT, "src", "main", "resources", "cities_anchor.csv")
FACE = 10240
H = FACE / 2
proj = CubeProjection()


def old_cube_point(face, u, v):
    """The pre-change (cross) fold — u=lx, v=lz."""
    return {
        "NORTH_POLE": (u, 1.0, v), "EQ_PRIME": (u, -v, 1.0),
        "SOUTH_POLE": (u, -1.0, -v), "EQ_BACK": (u, v, -1.0),
        "EQ_EAST": (1.0, -u, v), "EQ_WEST": (-1.0, u, v),
    }[face]


def old_uv_to_lonlat(face, u, v):
    x, y, z = old_cube_point(face, u, v)
    lon, lat = proj.dir_to_lonlat(np.array([x]), np.array([y]), np.array([z]))
    return float(lon[0]), float(lat[0])


# name -> (pool, size) from the existing anchor csv (size isn't in the globe file)
size_by_name = {}
with open(ANCHOR, encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        p = line.split(",")
        size_by_name[p[4].strip()] = (p[2].strip(), p[3].strip())

cities = json.load(open(GLOBE, encoding="utf-8"))
anchor_rows = []
for c in cities:
    lon, lat = old_uv_to_lonlat(c["face"], c["u"], c["v"])
    face, u, v = proj.lonlat_to_face_uv(lon, lat)
    gc, gr = GRID[face]
    x = int(round(gc * FACE + u * H))
    z = int(round(gr * FACE + v * H))
    c["face"], c["u"], c["v"] = face, round(u, 6), round(v, 6)
    c["x"], c["z"] = x, z
    c["lon"], c["lat"] = round(lon, 4), round(lat, 4)   # keep for future re-projections
    pool, size = size_by_name.get(c["name"], (c.get("pool", "plains"), "huge"))
    anchor_rows.append((x, z, pool, size, c["name"]))

json.dump(cities, open(GLOBE, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

with open(ANCHOR, "w", encoding="utf-8") as f:
    f.write("# worldX,worldZ,pool,size,name  (anchored villages + preloaded teleport stations)\n")
    for x, z, pool, size, name in anchor_rows:
        f.write(f"{x},{z},{pool},{size},{name}\n")

print(f"Re-anchored {len(cities)} cities. Sample:")
for c in cities[:6]:
    print(f"  {c['name']:12s} lon={c['lon']:7.2f} lat={c['lat']:6.2f} "
          f"face={c['face']:10s} x={c['x']:6d} z={c['z']:6d}")
