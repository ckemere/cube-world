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
import os
import struct
import numpy as np

NODATA = -32768


def _encode(a, scale):
    r = np.round(np.asarray(a, dtype=np.float64) / scale)
    r = np.clip(r, -32767, 32767)                 # clip valid range FIRST
    r = np.where(np.isnan(a), NODATA, r)           # ...so NODATA (-32768) survives
    return r.astype("<i2")


def _distance_ramp(mask_bool, radius):
    """A smooth 1->0 falloff over `radius` pixels from a binary mask, via
    successive 4-neighbour dilations (an integer distance transform without
    scipy). The centreline stays 1 and the value decreases outward, so the
    plugin can threshold to a NARROW yet CONTINUOUS river: a plain 1px line
    sampled bilinearly is dotty and, once thresholded low enough to be
    continuous, far too wide. A ramp is high all along the connected line
    (never dotty) and a high threshold keeps it thin. Lake interiors are 1, so
    lakes stay their true width."""
    val = mask_bool.astype(np.float32)          # 1 on the river/lake
    cur = mask_bool.copy()
    for k in range(1, radius + 1):
        grown = cur.copy()
        grown[1:, :] |= cur[:-1, :]
        grown[:-1, :] |= cur[1:, :]
        grown[:, 1:] |= cur[:, :-1]
        grown[:, :-1] |= cur[:, 1:]
        newly = grown & ~cur
        val[newly] = 1.0 - k / (radius + 1.0)
        cur = grown
    return val


def _river_mask(w, h, data_dir, radius=3):
    """Rasterize Natural Earth 10m rivers + lakes into an equirect river field
    sized (h, w): a smooth 1->0 distance ramp (see `_distance_ramp`) around the
    river centrelines and filled lakes, so the plugin gets thin, continuous
    watercourses instead of a fuzzy 1px line."""
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
    return _distance_ramp(np.asarray(mask, dtype=bool), radius)


def _dilate_min(a, radius):
    """Spread valid (non-NaN) values of `a` outward by `radius` 4-neighbour
    steps, keeping the MINIMUM where fronts collide. Used to widen the river
    water-surface (river_y) so it covers the whole distance-ramp footprint the
    river MASK carves, not just the 1px centre-line — otherwise off-centreline
    carved columns have no downhill surface and fall back to noisy local
    levelling (measured: only 46% of carved pixels had a river_y before this).
    MIN keeps the surface downhill-consistent where two branches meet."""
    out = a.copy()
    h, w = out.shape
    for _ in range(radius):
        cur = out
        cand = np.full_like(cur, np.nan)
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            sh = np.full_like(cur, np.nan)
            ys = slice(max(0, dy), h + min(0, dy))
            yd = slice(max(0, -dy), h + min(0, -dy))
            xs = slice(max(0, dx), w + min(0, dx))
            xd = slice(max(0, -dx), w + min(0, -dx))
            sh[yd, xd] = cur[ys, xs]
            cand = np.fmin(cand, sh)
        fill = np.isnan(out) & ~np.isnan(cand)
        out[fill] = cand[fill]
    return out


def _river_water_y(w, h, data_dir, height, dilate=3):
    """A per-cell DOWNHILL water-surface elevation (metres) along the river
    centre-lines + lakes; NaN off-river. For each centre-line we sample the DEM,
    orient it source(high)->mouth(low), and take the RUNNING MINIMUM from the
    source — a surface that is monotonically non-increasing by construction, so
    the carved river can never flow uphill (it pools flat where terrain rises,
    descends where it falls). Where lines cross we keep the lower value."""
    import json
    import os
    wy = np.full((h, w), np.nan, dtype=np.float32)

    def to_px(lon, lat):
        return (lon + 180.0) / 360.0 * w, (90.0 - lat) / 180.0 * h

    def hsample(lon, lat):
        x = int((lon + 180.0) / 360.0 * w) % w
        y = min(h - 1, max(0, int((90.0 - lat) / 180.0 * h)))
        return float(height[y, x])

    def draw_seg(x0, y0, v0, x1, y1, v1):
        n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
        xi = np.round(np.linspace(x0, x1, n)).astype(int) % w
        yi = np.clip(np.round(np.linspace(y0, y1, n)).astype(int), 0, h - 1)
        vs = np.linspace(v0, v1, n).astype(np.float32)
        cur = wy[yi, xi]
        wy[yi, xi] = np.where(np.isnan(cur), vs, np.minimum(cur, vs))

    rp = os.path.join(data_dir, "ne_10m_rivers_lake_centerlines.geojson")
    if os.path.exists(rp):
        for feat in json.load(open(rp))["features"]:
            g = feat.get("geometry")
            if not g:
                continue
            lines = g["coordinates"] if g["type"] == "MultiLineString" else [g["coordinates"]]
            for ln in lines:
                if len(ln) < 2:
                    continue
                elevs = [hsample(lo, la) for lo, la in ln]
                pts = ln
                if elevs[0] < elevs[-1]:                     # source = the higher end
                    pts, elevs = ln[::-1], elevs[::-1]
                wmin, m = [], elevs[0]
                for e in elevs:
                    m = min(m, e)
                    wmin.append(m)
                for i in range(len(pts) - 1):
                    x0, y0 = to_px(*pts[i])
                    x1, y1 = to_px(*pts[i + 1])
                    if abs(x1 - x0) > w / 2:                 # dateline wrap
                        continue
                    draw_seg(x0, y0, wmin[i], x1, y1, wmin[i + 1])

    lp = os.path.join(data_dir, "ne_10m_lakes.geojson")
    if os.path.exists(lp):
        from PIL import Image, ImageDraw
        for feat in json.load(open(lp))["features"]:
            g = feat.get("geometry")
            if not g:
                continue
            polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
            for poly in polys:
                ring = poly[0]
                lake_y = float(np.min([hsample(lo, la) for lo, la in ring]))   # flat surface
                m = Image.new("1", (w, h), 0)
                ImageDraw.Draw(m).polygon([to_px(*p) for p in ring], fill=1)
                mk = np.asarray(m, dtype=bool)
                wy[mk] = np.where(np.isnan(wy[mk]), lake_y, np.minimum(wy[mk], lake_y))
    # Widen the water surface to the mask's distance-ramp footprint so every
    # carved column has a downhill surface (see _dilate_min).
    if dilate:
        wy = _dilate_min(wy, dilate)
    return wy


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
        river = _river_mask(hw, hh, data_dir)                   # 0..1 ramp
        layers.append(("river", np.round(river * 1000.0).astype("<i2"),
                       0.001, 0.0))  # raw 0..1000, value = raw/1000
        ry = _river_water_y(hw, hh, data_dir, height)           # downhill water surface (m)
        ry_enc = np.where(np.isnan(ry), NODATA,
                          np.clip(np.round(ry), -32767, 32767)).astype("<i2")
        layers.append(("river_y", ry_enc, 1.0, 0.0))            # metres, NODATA off-river
    # Sea-surface temperature (WOA23, inpainted hole-free by tools/compact/sst.py).
    # Optional: without it the plugin falls back to a latitude proxy that gets
    # 64% of ocean area into the wrong vanilla temperature band.
    sst_npy = os.path.join(os.path.dirname(os.path.dirname(
        os.path.dirname(os.path.abspath(__file__)))), "compact", "out", "sst_total.npy")
    if os.path.exists(sst_npy):
        sst = np.load(sst_npy)
        layers.append(("sst", np.round(sst / 0.01).astype("<i2"), 0.01, 0.0))
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
