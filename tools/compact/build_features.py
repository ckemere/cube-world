"""Build ONE sparse feature list that carries both named summits and islands.

The islands worth listing are exactly the ones the smooth field loses: cells the
land mask calls land but where the coarse elevation reconstruction comes out
below sea level. So instead of detecting "islands" in the abstract, we detect the
model's own failures and patch precisely those - which keeps the list minimal.

Each entry is (lat, lon, elevation_m, radius_km) and is applied by the same
Gaussian-bump mechanism as the mountain peaks, so peaks and islands become one
mechanism rather than two.

Byte layout (8 bytes/entry):
    lat  int16  1/100 deg   (+-9000)
    lon  int16  1/100 deg   (+-18000)
    ele  int16  metres
    rad  uint16 1/100 km
"""
import os
import sys
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from earthdat import EarthDat            # noqa: E402
import model as M                        # noqa: E402
from islands import _upsample_mask, _resize_nearest   # noqa: E402
from fit import load_peaks               # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "out")
BYTES_PER_ENTRY = 8


def cell_max_elevation(layer, mask_shape, rows, cols):
    """True max elevation inside each mask cell, read from the full-res layer."""
    mh, mw = mask_shape
    sy = layer.height / mh
    sx = layer.width / mw
    out = np.zeros(len(rows))
    for i, (r, c) in enumerate(zip(rows, cols)):
        y0 = int(r * sy); y1 = max(y0 + 1, int((r + 1) * sy))
        x0 = int(c * sx); x1 = max(x0 + 1, int((c + 1) * sx))
        blk = np.asarray(layer.mm[y0:y1, x0:x1], dtype=np.float64)
        blk = blk[blk != -32768]
        out[i] = (blk.max() * layer.scale + layer.offset) if blk.size else 0.0
    return out


def build(earth="run/earth.dat", peaks_csv="src/main/resources/peaks6000.csv",
          mask_w=4320, elev_w=1080, cluster=True):
    e = EarthDat(earth)
    hl = e["height"]
    W, H = 2160, 1080
    ref = np.nan_to_num(hl.block_mean(W, H), nan=0.0)

    print(f"land mask {mask_w}x{mask_w//2} from full resolution...")
    mask = _upsample_mask(hl, mask_w, mask_w // 2)
    mask_bytes = len(zlib.compress(np.packbits(mask.ravel()).tobytes(), 9))

    coarse = M.Coarse(ref, elev_w, elev_w // 2, quant=4.0)
    rec = coarse.reconstruct()                       # 2160x1080
    rec_at_mask = _resize_nearest_f(rec, mask.shape)

    lost = mask & (rec_at_mask < 0)                  # land the smooth field drowned
    rows, cols = np.nonzero(lost)
    print(f"  cells where mask=land but smooth field=ocean: {len(rows):,}")

    mh, mw = mask.shape
    lat = 90.0 - (rows + 0.5) / mh * 180.0
    lon = (cols + 0.5) / mw * 360.0 - 180.0

    if cluster:
        rows, cols, lat, lon, reps = _cluster(rows, cols, lat, lon, mask.shape)
        print(f"  after clustering adjacent cells into islands: {len(rows):,}")
    else:
        reps = np.ones(len(rows))

    ele = cell_max_elevation(hl, mask.shape, rows, cols)
    ele = np.maximum(ele, 20.0)          # a listed island is at least walkable
    px_km = 360.0 / mw * 111.32
    rad_km = np.maximum(px_km * 0.75, px_km * np.sqrt(reps) * 0.6)

    peaks = load_peaks(peaks_csv)
    print(f"  named summits >=6000 m: {len(peaks):,}")

    n_total = len(rows) + len(peaks)
    feat_bytes = n_total * BYTES_PER_ENTRY
    print(f"\n  islands   {len(rows):>6,} entries")
    print(f"  summits   {len(peaks):>6,} entries")
    print(f"  TOTAL     {n_total:>6,} entries x {BYTES_PER_ENTRY} B = "
          f"{feat_bytes/1e3:.0f} KB   (compare: land mask = {mask_bytes/1e3:.0f} KB)")

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "features.csv")
    with open(path, "w") as f:
        f.write("kind,lat,lon,ele_m,radius_km\n")
        for la, lo, el, rk in zip(lat, lon, ele, rad_km):
            f.write(f"island,{la:.4f},{lo:.4f},{el:.0f},{rk:.2f}\n")
        for la, lo, el in peaks:
            f.write(f"summit,{la:.4f},{lo:.4f},{el:.0f},13.00\n")
    print(f"  wrote {path}")
    return dict(mask_bytes=mask_bytes, feat_bytes=feat_bytes,
                n_islands=len(rows), n_summits=len(peaks),
                lat=lat, lon=lon, ele=ele, rad_km=rad_km, coarse=coarse, ref=ref)


def _resize_nearest_f(a, shape):
    h, w = shape
    ys = np.minimum((np.arange(h) * a.shape[0] // h), a.shape[0] - 1)
    xs = np.minimum((np.arange(w) * a.shape[1] // w), a.shape[1] - 1)
    return a[np.ix_(ys, xs)]


def _cluster(rows, cols, lat, lon, shape):
    """Merge 8-connected runs of lost-land cells into one entry each (centroid,
    with a cell count so the bump radius can match the island's size)."""
    key = {}
    for i, (r, c) in enumerate(zip(rows, cols)):
        key[(int(r), int(c))] = i
    seen = set()
    out_r, out_c, out_lat, out_lon, out_n = [], [], [], [], []
    for (r, c), i in key.items():
        if (r, c) in seen:
            continue
        stack = [(r, c)]
        seen.add((r, c))
        comp = []
        while stack:
            rr, cc = stack.pop()
            comp.append((rr, cc))
            for dr in (-1, 0, 1):
                for dc in (-1, 0, 1):
                    nb = (rr + dr, (cc + dc) % shape[1])
                    if nb in key and nb not in seen:
                        seen.add(nb)
                        stack.append(nb)
        arr = np.array(comp)
        rc = arr[:, 0].mean(); cc_ = arr[:, 1].mean()
        out_r.append(int(round(rc))); out_c.append(int(round(cc_)))
        out_lat.append(90.0 - (rc + 0.5) / shape[0] * 180.0)
        out_lon.append((cc_ + 0.5) / shape[1] * 360.0 - 180.0)
        out_n.append(len(comp))
    return (np.array(out_r), np.array(out_c), np.array(out_lat),
            np.array(out_lon), np.array(out_n))


if __name__ == "__main__":
    build()
