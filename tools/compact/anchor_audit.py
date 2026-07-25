"""Audit every city anchor for placement quality.

Commit 33dd64c snapped two coastal anchors (Alexandria, Basra) inland, case by
case. Antioch turned out to be a third: 3.5% of its village columns sit over
water and 15% are stilted. Fixing these one at a time clearly misses cases, so
this scores ALL of them from the generated region files:

  water%   village columns with water directly beneath (built out over the sea)
  sub-sea% village columns at or below sea level
  stilt%   village columns with >=3 blocks of plain air beneath
  slope    mean |gradient| under the village (terrain roughness it was placed on)

Usage: anchor_audit.py [radius_chunks]
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, "/home/dev/projects/cube-world/tools/playermap")
import anvil  # noqa: E402
from cave_audit import VILLAGE, ANY_AIR, CAVE_AIR, load_chunk, column_blocks  # noqa: E402

STATIONS = "/home/dev/projects/cube-world/run/plugins/CubeWorld/stations.csv"
SEA = 62


def cities():
    out = []
    with open(STATIONS) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            p = line.rstrip("\n").split(",")
            if len(p) >= 9 and p[4] == "true":
                out.append((p[-1].strip(), int(p[1]), int(p[3])))
    return sorted(out)


def score(x, z, rad):
    cx0, cz0 = x >> 4, z >> 4
    n = (2 * rad + 1) * 16
    hgt = np.full((n, n), np.nan)
    vil = np.zeros((n, n), bool)
    water = stilt = 0
    subsea = 0
    for cx in range(cx0 - rad, cx0 + rad + 1):
        for cz in range(cz0 - rad, cz0 + rad + 1):
            root = load_chunk(cx, cz)
            if root is None:
                continue
            got = column_blocks(root)
            if got is None:
                continue
            h, name_at = got
            ox, oz = (cx - (cx0 - rad)) * 16, (cz - (cz0 - rad)) * 16
            for lz in range(16):
                for lx in range(16):
                    top = anvil.MIN_Y + int(h[lz, lx]) - 1
                    if top < anvil.MIN_Y + 1:
                        continue
                    hgt[oz + lz, ox + lx] = top
                    if name_at(lx, top, lz) not in VILLAGE:
                        continue
                    vil[oz + lz, ox + lx] = True
                    if top <= SEA + 1:
                        subsea += 1
                    gap = 0
                    for dy in range(1, 13):
                        nm = name_at(lx, top - dy, lz)
                        if nm == "minecraft:water":
                            water += 1
                            break
                        if nm in ANY_AIR and nm not in CAVE_AIR:
                            gap += 1
                        else:
                            break
                    if gap >= 3:
                        stilt += 1
    v = int(vil.sum())
    if v == 0:
        return None
    ok = np.isfinite(hgt)
    gy, gx = np.gradient(np.where(ok, hgt, np.nanmean(hgt)))
    slope = float(np.hypot(gx, gy)[vil].mean())
    return dict(vil=v, water=water / v * 100, subsea=subsea / v * 100,
                stilt=stilt / v * 100, slope=slope,
                relief=float(np.nanmax(hgt) - np.nanmin(hgt)))


if __name__ == "__main__":
    rad = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    rows = []
    for name, x, z in cities():
        s = score(x, z, rad)
        if s:
            rows.append((name, x, z, s))
    rows.sort(key=lambda r: -(r[3]["water"] * 3 + r[3]["stilt"]))
    print(f"{'city':16}{'x':>8}{'z':>8}{'cols':>6}{'water%':>8}{'subsea%':>9}"
          f"{'stilt%':>8}{'slope':>7}{'relief':>8}  verdict")
    bad = []
    for name, x, z, s in rows:
        v = ("OVER WATER" if s["water"] >= 1.0
             else "stilted" if s["stilt"] >= 10 else "ok")
        if v != "ok":
            bad.append(name)
        print(f"{name:16}{x:>8}{z:>8}{s['vil']:>6}{s['water']:>8.1f}{s['subsea']:>9.1f}"
              f"{s['stilt']:>8.1f}{s['slope']:>7.2f}{s['relief']:>8.0f}  {v}")
    print(f"\n  {len(bad)}/{len(rows)} need attention: {', '.join(bad) if bad else 'none'}")
