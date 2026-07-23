"""Render the whole world as a flat map in the NEW equatorial-band net: the
four equatorial faces in a west-to-east row (each north-up, east-right — a
correct compass), the poles hung above and below the prime meridian. Biomes +
rivers/lakes + the 30 historical cities. Run:
  cd tools/playermap && python3 -m biomegen.flatmap [n] [seed]"""
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from .biomegen import biome_grid
from .blobreader import Blob
from .partition import Partition
from .render import color_for

# Tile position (col, row) of each face in the band net; col→east, row→south.
TILES = {
    "NORTH_POLE": (1, 0),
    "EQ_WEST": (0, 1), "EQ_PRIME": (1, 1), "EQ_EAST": (2, 1), "EQ_BACK": (3, 1),
    "SOUTH_POLE": (1, 2),
}
LABEL = {"NORTH_POLE": "NORTH POLE", "SOUTH_POLE": "SOUTH POLE", "EQ_PRIME": "0° (PRIME)",
         "EQ_EAST": "90°E", "EQ_BACK": "180° (DATE LINE)", "EQ_WEST": "90°W"}
RIVER_COL = (60, 110, 200)
LAKE_COL = (60, 110, 200)
GAP = 6           # black gutter between face tiles
HERE = os.path.dirname(__file__)
VEC = os.path.join(HERE, "..", "..", "cubemap", "out", "worldblob_vectors.json")
CITIES = os.path.join(HERE, "..", "cities_globe.json")


def _font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def render(n=512, seed=20260712):
    blob, part = Blob(), Partition()
    lut = np.array([color_for(b) for b in part.biomes], dtype=np.uint8)
    vec = json.load(open(VEC))
    cols = max(c for c, _ in TILES.values()) + 1
    rows = max(r for _, r in TILES.values()) + 1
    W = cols * n + (cols + 1) * GAP
    H = rows * n + (rows + 1) * GAP
    canvas = Image.new("RGB", (W, H), (12, 14, 20))
    draw = ImageDraw.Draw(canvas)

    def to_px(u, v, ox, oy):
        return ox + (u + 1) * 0.5 * (n - 1), oy + (v + 1) * 0.5 * (n - 1)

    for face, (tc, tr) in TILES.items():
        ox = GAP + tc * (n + GAP)
        oy = GAP + tr * (n + GAP)
        idx, _ = biome_grid(blob, part, face, n, seed, blend=True)
        tile = Image.fromarray(lut[idx], "RGB")
        td = ImageDraw.Draw(tile)
        for run in vec.get("lakes", {}).get(face, []):
            pts = [((u + 1) * 0.5 * (n - 1), (v + 1) * 0.5 * (n - 1)) for u, v in run]
            if len(pts) >= 3:
                td.polygon(pts, fill=LAKE_COL)
        for run in vec.get("rivers", {}).get(face, []):
            pts = [((u + 1) * 0.5 * (n - 1), (v + 1) * 0.5 * (n - 1)) for u, v in run]
            if len(pts) >= 2:
                td.line(pts, fill=RIVER_COL, width=max(1, n // 400))
        canvas.paste(tile, (ox, oy))
        # face label + a small compass arrow (north = up)
        fnt = _font(max(12, n // 26))
        draw.text((ox + 6, oy + 4), LABEL[face], fill=(255, 255, 255), font=fnt)
        if face.startswith("EQ"):
            cxp = ox + n - 22
            draw.line([(cxp, oy + 30), (cxp, oy + 8)], fill=(255, 80, 80), width=3)
            draw.polygon([(cxp - 4, oy + 14), (cxp + 4, oy + 14), (cxp, oy + 6)], fill=(255, 80, 80))
            draw.text((cxp - 4, oy + 32), "N", fill=(255, 80, 80), font=_font(max(10, n // 34)))

    # city markers
    cfnt = _font(max(11, n // 30))
    for c in json.load(open(CITIES, encoding="utf-8")):
        tc, tr = TILES[c["face"]]
        ox = GAP + tc * (n + GAP)
        oy = GAP + tr * (n + GAP)
        x, y = to_px(c["u"], c["v"], ox, oy)
        r = 4
        draw.ellipse([x - r, y - r, x + r, y + r], fill=(255, 220, 40), outline=(0, 0, 0))
        draw.text((x + 6, y - 6), c["name"], fill=(255, 255, 210), font=cfnt)

    out = os.path.join(HERE, "..", "..", "cubemap", "out", "flatmap_band.png")
    canvas.save(out)
    print("wrote", os.path.abspath(out), canvas.size)
    return out


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 512
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 20260712
    render(n, seed)
