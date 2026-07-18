"""Export the ingested Earth rasters into a compact binary the Java plugin
loads (CWE1 format). The plugin samples these through the same CubeSurface
embedding + roll to drive terrain height and biome climate.

CWE1 layout (little-endian):
  magic   4s   b"CWE1"
  roll    f32  orientation roll (degrees) baked into the export note; the
               plugin applies the rotation itself, this is just provenance
  nlayers i32
  per layer header (repeated nlayers times):
    name  8s   ascii, null-padded ("height","temp","precip")
    w     i32
    h     i32
    scale f32  value = raw*scale + offset
    offset f32
  then nlayers int16 data blocks in header order, row-major, north-up,
  lon -180..180 across. raw == -32768 is nodata.
"""
from __future__ import annotations
import struct
import numpy as np

NODATA = -32768


def _encode(a, scale):
    r = np.round(np.asarray(a, dtype=np.float64) / scale)
    r = np.clip(r, -32767, 32767)                 # clip valid range FIRST
    r = np.where(np.isnan(a), NODATA, r)           # ...so NODATA (-32768) survives
    return r.astype("<i2")


def _river_mask(w, h, data_dir):
    """Rasterize Natural Earth 10m rivers + lakes into a 0/1 equirect mask
    sized (h, w). Rivers are drawn as lines, lakes filled."""
    import json
    import os
    from PIL import Image, ImageDraw
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)

    def to_px(coords):
        return [((lo + 180.0) / 360.0 * w, (90.0 - la) / 180.0 * h) for lo, la in coords]

    rp = os.path.join(data_dir, "ne_10m_rivers_lake_centerlines.geojson")
    if os.path.exists(rp):
        for feat in json.load(open(rp))["features"]:
            g = feat.get("geometry")
            if not g:
                continue
            lines = g["coordinates"] if g["type"] == "MultiLineString" else [g["coordinates"]]
            for ln in lines:
                pts = to_px(ln)
                seg = [pts[0]]
                for p in pts[1:]:
                    if abs(p[0] - seg[-1][0]) > w / 2:
                        if len(seg) > 1:
                            d.line(seg, fill=1, width=1)
                        seg = [p]
                    else:
                        seg.append(p)
                if len(seg) > 1:
                    d.line(seg, fill=1, width=1)
    lp = os.path.join(data_dir, "ne_10m_lakes.geojson")
    if os.path.exists(lp):
        for feat in json.load(open(lp))["features"]:
            g = feat.get("geometry")
            if not g:
                continue
            polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
            for poly in polys:
                d.polygon(to_px(poly[0]), fill=1)
    return np.asarray(mask, dtype=np.float32)


def export_earth(out_path, etopo, temp_raster, precip_raster, roll,
                 height_step=2, data_dir=None):
    """height_step downsamples ETOPO (native 1'): 2 -> 2 arc-min (~3.7 km)."""
    height = etopo.grid[::height_step, ::height_step]
    hh, hw = height.shape
    layers = [
        ("height", _encode(height, 1.0), 1.0, 0.0),        # metres
        ("temp", _encode(temp_raster.grid, 0.1), 0.1, 0.0),  # deg C (raw = C*10)
        ("precip", _encode(precip_raster.grid, 1.0), 1.0, 0.0),  # mm
    ]
    if data_dir:
        river = _river_mask(hw, hh, data_dir)
        layers.append(("river", river.astype("<i2"), 1.0, 0.0))  # 0/1
    with open(out_path, "wb") as f:
        f.write(b"CWE1")
        f.write(struct.pack("<f", float(roll)))
        f.write(struct.pack("<i", len(layers)))
        for name, arr, scale, offset in layers:
            h, w = arr.shape
            f.write(name.encode("ascii")[:8].ljust(8, b"\0"))
            f.write(struct.pack("<iiff", w, h, scale, offset))
        for _, arr, _, _ in layers:
            arr.tofile(f)
    total = sum(arr.nbytes for _, arr, _, _ in layers)
    return {"layers": [(n, a.shape) for n, a, _, _ in layers], "bytes": total}
