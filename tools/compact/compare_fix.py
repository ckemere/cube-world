"""Head-to-head: land MASK vs sparse FEATURE LIST for saving small islands.

They are not equivalent, and the test shows why:
  mask         - fixes islands AND coastline crispness, but any cell containing
                 land becomes fully land, so land area inflates.
  feature list - carries each island's true elevation and radius, unified with the
                 mountain peaks, and does not inflate coastlines; but it only
                 patches islands, so the smooth field's coastline halo remains.
"""
import csv
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from earthdat import EarthDat                          # noqa: E402
import model as M                                      # noqa: E402
from islands import (ISLANDS, _upsample_mask, _resize_nearest,     # noqa: E402
                     sample_grid_max, land_area_frac)
import zlib                                            # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "out")


def apply_features(field, feats, shape):
    """Gaussian bumps with a per-entry radius (islands vary in size; peaks don't)."""
    h, w = shape
    out = field
    for lat, lon, ele, rad_km in feats:
        s = max(rad_km / 111.32, 0.03)                  # radius -> sigma in degrees
        y = (90.0 - lat) / 180.0 * h
        x = (lon + 180.0) / 360.0 * w
        ry = max(1, int(3 * s / 180.0 * h))
        rx = max(1, int(3 * s / 360.0 * w))
        ys = np.clip(np.arange(int(y) - ry, int(y) + ry + 1), 0, h - 1)
        xs = np.mod(np.arange(int(x) - rx, int(x) + rx + 1), w)
        dlat = (90.0 - (ys + 0.5) / h * 180.0) - lat
        dlon = ((xs + 0.5) / w * 360.0 - 180.0) - lon
        dlon = (dlon + 180) % 360 - 180
        g = np.exp(-((dlat[:, None] ** 2)
                     + (dlon[None, :] * np.cos(np.radians(lat))) ** 2) / (2 * s * s))
        patch = out[np.ix_(ys, xs)]
        out[np.ix_(ys, xs)] = np.where(g > 0.05, np.maximum(patch, ele * g), patch)
    return out


def main():
    e = EarthDat("run/earth.dat")
    hl = e["height"]
    W, H = 2160, 1080
    ref = np.nan_to_num(hl.block_mean(W, H), nan=0.0)
    lat_rows = 90.0 - (np.arange(H) + 0.5) / H * 180.0

    coarse = M.Coarse(ref, 1080, 540, quant=4.0)
    base = coarse.reconstruct()
    print(f"coarse elevation 1080x540 = {coarse.nbytes/1e3:.0f} KB")

    mask = _upsample_mask(hl, 4320, 2160)
    mask_bytes = len(zlib.compress(np.packbits(mask.ravel()).tobytes(), 9))
    m = _resize_nearest(mask, base.shape)
    v_mask = np.where(m, np.maximum(base, 12.0), np.minimum(base, -12.0))

    feats = []
    fp = os.path.join(OUT, "features.csv")
    with open(fp) as f:
        for row in csv.DictReader(f):
            feats.append((float(row["lat"]), float(row["lon"]),
                          float(row["ele_m"]), float(row["radius_km"])))
    feat_bytes = len(feats) * 8
    v_feat = apply_features(base.copy(), feats, base.shape)
    v_both = apply_features(v_mask.copy(), feats, base.shape)

    cands = [
        ("reference 2160x1080", ref, 0),
        ("coarse only", base, coarse.nbytes),
        (f"+ land mask ({mask_bytes/1e3:.0f} KB)", v_mask, coarse.nbytes + mask_bytes),
        (f"+ feature list ({feat_bytes/1e3:.0f} KB)", v_feat, coarse.nbytes + feat_bytes),
        ("+ both", v_both, coarse.nbytes + mask_bytes + feat_bytes),
    ]

    print(f"\n{'variant':30}{'total KB':>10}{'sunk/18':>9}{'land %':>9}  lost")
    ref_land = land_area_frac(ref, lat_rows)
    for name, field, nb in cands:
        sunk = [n for n, la, lo, ele, kind in ISLANDS
                if sample_grid_max(field, la, lo) < 0
                and hl.sample_bilinear(np.array([lo]), np.array([la]))[0] >= 0]
        la_pct = land_area_frac(field, lat_rows) * 100
        print(f"  {name:28}{nb/1e3:>10.0f}{len(sunk):>9}{la_pct:>9.2f}  "
              f"{', '.join(s.split(' (')[0] for s in sunk[:5])}")
    print(f"  {'(reference land %)':28}{'':>10}{'':>9}{ref_land*100:>9.2f}")


if __name__ == "__main__":
    main()
