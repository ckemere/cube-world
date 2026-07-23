"""Render the six nether cube faces from the nether biome raster, so the map can
swap the Earth globe for a nether globe (crimson/warped forests, soul-sand
valleys, basalt deltas, nether wastes) when you flip the dimension toggle.
Faces come out in FACE_ORDER — the same order the globe's texture array uses."""

import base64
import io
import os

import numpy as np
from PIL import Image

from .biomeraster import load

FACE_ORDER = ["NORTH_POLE", "SOUTH_POLE", "EQ_PRIME", "EQ_BACK", "EQ_EAST", "EQ_WEST"]

# nether biome -> RGB
NETHER_COL = {
    "minecraft:nether_wastes": (96, 36, 32),
    "minecraft:crimson_forest": (140, 40, 48),
    "minecraft:warped_forest": (28, 104, 100),
    "minecraft:soul_sand_valley": (88, 76, 64),
    "minecraft:basalt_deltas": (64, 62, 70),
}
_VOID = (12, 10, 12)

_NETHER_PATH = os.path.join(os.path.dirname(__file__), "..", "..", "..",
                            "run", "plugins", "CubeWorld", "biomes", "nether.cwbr")
_cache = {}


def nether_face_uris():
    key = os.path.getmtime(_NETHER_PATH) if os.path.exists(_NETHER_PATH) else 0
    if _cache.get("k") == key:
        return _cache["uris"]
    r = load(_NETHER_PATH)
    palette = np.array([NETHER_COL.get(b, _VOID) for b in r.palette], dtype=np.uint8)
    by_name = {f[0]: f for f in r.faces}
    uris = []
    for name in FACE_ORDER:
        _n, _cmx, _cmz, grid = by_name[name]
        arr = np.array(grid, dtype=np.int32).reshape(r.chunks, r.chunks)   # [i(x), j(z)]
        img = palette[arr.T]                                              # row=v(j), col=u(i)
        # The globe faces are power-of-two (2048); WebGL can't mipmap an NPOT
        # texture, so upscale the 640-chunk biome grid to 1024 (NEAREST keeps the
        # biome edges crisp) or the swapped nether globe renders black.
        im = Image.fromarray(img, "RGB").resize((1024, 1024), Image.NEAREST)
        buf = io.BytesIO()
        im.save(buf, format="JPEG", quality=85, subsampling=0)
        uris.append("data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode("ascii"))
    _cache.update(k=key, uris=uris)
    return uris


if __name__ == "__main__":
    u = nether_face_uris()
    print(f"rendered {len(u)} nether faces, first uri {len(u[0])} bytes")
