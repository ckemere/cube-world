"""Render the six overworld cube faces as a biome map for the globe: the
portable generator (strong Earth constraint + seed noise) coloured per biome,
with the river/lake vectors drawn crisply on top. Faces come out power-of-two
(so WebGL can mipmap them) in FACE_ORDER, as PNG data URIs (lossless, so the
per-chunk biome cells stay sharp). Cached per seed."""

import base64
import io
import json
import os

import numpy as np
from PIL import Image, ImageDraw

from .biomegen import biome_grid
from .blobreader import Blob
from .partition import Partition
from .render import color_for

FACE_ORDER = ["NORTH_POLE", "SOUTH_POLE", "EQ_PRIME", "EQ_BACK", "EQ_EAST", "EQ_WEST"]
RIVER_COL = (58, 108, 196)
LAKE_COL = (52, 98, 188)

_VEC = os.path.join(os.path.dirname(__file__), "..", "..", "cubemap", "out", "worldblob_vectors.json")
_state = {"blob": None, "part": None, "lut": None, "vec": None}
_cache = {}


def _ready():
    # Build everything into locals first and publish "blob" (the guard key) LAST,
    # so a concurrent caller never sees blob set while part is still None (that
    # raced two warm-up requests into an AttributeError on part.index_batch).
    if _state["blob"] is None:
        part = Partition()
        _state["part"] = part
        _state["lut"] = np.array([color_for(b) for b in part.biomes], dtype=np.uint8)
        _state["vec"] = json.load(open(_VEC)) if os.path.exists(_VEC) else {"rivers": {}, "lakes": {}}
        _state["blob"] = Blob()
    return _state


def biome_face_uris(seed, n=384, out=1024):
    key = (int(seed), n, out)
    if key in _cache:
        return _cache[key]
    s = _ready()
    uris = []
    for face in FACE_ORDER:
        idx, _ = biome_grid(s["blob"], s["part"], face, n, seed, blend=True)
        img = Image.fromarray(s["lut"][idx], "RGB").resize((out, out), Image.NEAREST)
        d = ImageDraw.Draw(img)
        w = max(1, out // 512)
        for run in s["vec"].get("lakes", {}).get(face, []):
            pts = [(((u + 1) * 0.5 * (out - 1)), ((v + 1) * 0.5 * (out - 1))) for u, v in run]
            if len(pts) >= 3:
                d.polygon(pts, fill=LAKE_COL)
        for run in s["vec"].get("rivers", {}).get(face, []):
            pts = [(((u + 1) * 0.5 * (out - 1)), ((v + 1) * 0.5 * (out - 1))) for u, v in run]
            if len(pts) >= 2:
                d.line(pts, fill=RIVER_COL, width=w)
        buf = io.BytesIO()
        img.save(buf, format="PNG", optimize=False)
        uris.append("data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("ascii"))
    _cache[key] = uris
    if len(_cache) > 4:
        _cache.pop(next(iter(_cache)))
    return uris
