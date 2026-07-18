"""Build an equirectangular base map from Natural Earth vector data.

For now this is a schematic reference — coastlines from Natural Earth plus a
latitude-band tint so the reprojection reads as a biome-ish map. The real
generator will consume WorldClim/GEBCO rasters; when we want a data-accurate
preview, add a ``from_raster`` builder here with the same output contract
(an H x W x 3 uint8 numpy array, lon -180..180 across, lat 90..-90 down).
"""
from __future__ import annotations
import json
import os
import urllib.request
import numpy as np
from PIL import Image, ImageDraw

NE_BASE = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson"
LAYERS = {
    "land": "ne_110m_land.geojson",
    "lakes": "ne_110m_lakes.geojson",
    "rivers": "ne_110m_rivers_lake_centerlines.geojson",
}


def ensure_data(data_dir):
    os.makedirs(data_dir, exist_ok=True)
    for key, fn in LAYERS.items():
        path = os.path.join(data_dir, fn)
        if not os.path.exists(path):
            url = f"{NE_BASE}/{fn}"
            print(f"fetching {fn} ...")
            urllib.request.urlretrieve(url, path)
    return data_dir


def _polys(data_dir, fn):
    d = json.load(open(os.path.join(data_dir, fn)))
    for feat in d["features"]:
        g = feat.get("geometry")
        if not g:
            continue
        if g["type"] == "Polygon":
            yield g["coordinates"]
        elif g["type"] == "MultiPolygon":
            for p in g["coordinates"]:
                yield p


def _lines(data_dir, fn):
    d = json.load(open(os.path.join(data_dir, fn)))
    for feat in d["features"]:
        g = feat.get("geometry")
        if not g:
            continue
        if g["type"] == "LineString":
            yield g["coordinates"]
        elif g["type"] == "MultiLineString":
            for ln in g["coordinates"]:
                yield ln


def _lonlat_to_px(coords, w, h):
    a = np.asarray(coords, dtype=float)
    x = (a[:, 0] + 180.0) / 360.0 * w
    y = (90.0 - a[:, 1]) / 180.0 * h
    return list(zip(x.tolist(), y.tolist()))


def _land_tint(lat):
    al = abs(lat)
    if al > 68:  return (238, 240, 244)
    if al > 55:  return (70, 104, 78)
    if al > 43:  return (86, 132, 78)
    if al > 32:  return (150, 158, 96)
    if al > 18:  return (196, 178, 120)
    return (74, 132, 74)


def _ocean_tint(lat):
    al = abs(lat)
    if al > 78:  return (232, 238, 245)
    if al > 55:  return (44, 74, 105)
    if al > 30:  return (36, 92, 132)
    return (30, 110, 150)


def build_equirect(data_dir, width=2880):
    """Return an (H, W, 3) uint8 equirectangular base map."""
    ensure_data(data_dir)
    w = width
    h = width // 2

    # ocean background by latitude
    lat_rows = 90.0 - (np.arange(h) + 0.5) / h * 180.0
    img = np.zeros((h, w, 3), dtype=np.uint8)
    for y in range(h):
        img[y, :] = _ocean_tint(lat_rows[y])

    # land + lakes via a 1-bit mask rendered with Pillow (fast, antialias off)
    mask = Image.new("1", (w, h), 0)
    md = ImageDraw.Draw(mask)
    for poly in _polys(data_dir, LAYERS["land"]):
        for ring in poly:
            md.polygon(_lonlat_to_px(ring, w, h), fill=1)
    for poly in _polys(data_dir, LAYERS["lakes"]):
        for ring in poly:
            md.polygon(_lonlat_to_px(ring, w, h), fill=0)
    land = np.array(mask, dtype=bool)

    # apply latitude land tint where mask is set
    for y in range(h):
        row = land[y]
        if row.any():
            img[y][row] = _land_tint(lat_rows[y])

    # rivers on top
    over = Image.fromarray(img)
    od = ImageDraw.Draw(over)
    for ln in _lines(data_dir, LAYERS["rivers"]):
        pts = _lonlat_to_px(ln, w, h)
        # split at dateline wrap
        seg = [pts[0]]
        for p in pts[1:]:
            if abs(p[0] - seg[-1][0]) > w / 2:
                if len(seg) > 1:
                    od.line(seg, fill=(60, 120, 170), width=1)
                seg = [p]
            else:
                seg.append(p)
        if len(seg) > 1:
            od.line(seg, fill=(60, 120, 170), width=1)
    return np.array(over)
