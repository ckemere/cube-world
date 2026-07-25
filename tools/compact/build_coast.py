"""Build a distance-to-coast raster and ship it as a small CWE1 sidecar.

Vanilla's continentalness means "how far from the ocean am I", and its biome table
spreads land across coast / near-inland / mid-inland / far-inland bands from -0.11
to +1.0. We proxy it with ELEVATION, which is why measured land continentalness is
pinned near 0: most land is 0-800 m, so almost everything reads as coast or
near-inland and whole bands of vanilla's table are never used.

This computes true distance to the nearest ocean cell with a two-pass chamfer
transform (no scipy needed) and writes it as a one-layer CWE1 file, so the runtime
can load it beside earth.dat without rewriting the 359 MB bundle.
"""
import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from earthdat import EarthDat  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "run", "coast.dat")


def chamfer_distance(mask, km_per_px_x, km_per_px_y):
    """Approximate euclidean distance (km) from every cell to the nearest False
    cell, via a forward/backward chamfer sweep. Longitude wraps."""
    h, w = mask.shape
    BIG = 1e9
    d = np.where(mask, BIG, 0.0)
    dx = km_per_px_x                      # varies with latitude -> passed per row
    dy = km_per_px_y
    # forward pass
    for y in range(h):
        step = dx[y]
        row = d[y]
        if y > 0:
            row = np.minimum(row, d[y - 1] + dy)
        # left-to-right with wrap
        for _ in range(2):
            prev = np.roll(row, 1)
            row = np.minimum(row, prev + step)
        d[y] = row
    # backward pass
    for y in range(h - 1, -1, -1):
        step = dx[y]
        row = d[y]
        if y < h - 1:
            row = np.minimum(row, d[y + 1] + dy)
        for _ in range(2):
            nxt = np.roll(row, -1)
            row = np.minimum(row, nxt + step)
        d[y] = row
    return d


def main(w=2160):
    h = w // 2
    e = EarthDat("run/earth.dat")
    print(f"building land mask at {w}x{h} ...")
    elev = e["height"].block_mean(w, h)
    land = np.nan_to_num(elev, nan=-1.0) >= 0
    lat = 90.0 - (np.arange(h) + 0.5) / h * 180.0
    km_y = 180.0 / h * 111.32
    km_x = (360.0 / w * 111.32) * np.cos(np.radians(lat))
    km_x = np.maximum(km_x, 1e-3)
    print("chamfer distance-to-ocean (land cells) ...")
    dist = chamfer_distance(land, km_x, km_y)
    dist[~land] = 0.0
    q = np.clip(np.round(dist), 0, 32000).astype(np.int16)
    print(f"  land distance-to-coast km: p50 {np.percentile(dist[land],50):.0f}  "
          f"p90 {np.percentile(dist[land],90):.0f}  max {dist[land].max():.0f}")

    with open(OUT, "wb") as f:
        f.write(b"CWE1")
        f.write(struct.pack("<f", float(e.roll)))
        f.write(struct.pack("<i", 1))
        name = b"coast" + b"\0" * 3
        f.write(name[:8])
        f.write(struct.pack("<ii", w, h))
        f.write(struct.pack("<ff", 1.0, 0.0))
        f.write(q.tobytes())
    print(f"  wrote {OUT} ({os.path.getsize(OUT)/1e6:.2f} MB)")


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 2160)
