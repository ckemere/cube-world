"""Global distribution of local relief and in-game altitude, to CALIBRATE the
erosion curve instead of guessing at it.

Why this exists: `EarthClimate.erosion = clamp(0.45 - rugged/500, -1, 0.5)` pins to
its -1.00 floor for anything past 725 m of relief. That turned out to include
ordinary hill country (a 13 C Mediterranean coastal slope measured -1.00, the same
as K2), which drops those columns into vanilla's mountain/slope biome family and
produces snowy `grove` at y=65.

The deeper problem is that vanilla couples "jagged" to "tall" — its splines raise
`offset` wherever erosion is low — whereas we take erosion from real relief and
altitude from real elevation compressed ~35x vertically. So the fix needs the joint
distribution of (relief, in-game altitude), not relief alone.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from earthdat import EarthDat  # noqa: E402

SEA_LEVEL = 62
LAND_EXAG = 0.0285
HIGH_BREAK = 4000.0
HIGH_EXAG = 0.0146
LAND_CAP = 253.0


def block_y(m):
    """EarthMapSpec.elevationToBlockY for land (metres -> block y)."""
    b = np.where(m <= HIGH_BREAK, m * LAND_EXAG,
                 HIGH_BREAK * LAND_EXAG + (m - HIGH_BREAK) * HIGH_EXAG)
    return SEA_LEVEL + np.minimum(b, LAND_CAP)


def main(grid_w=2160):
    e = EarthDat("run/earth.dat")
    hl = e["height"]
    H = grid_w // 2
    print(f"sampling {grid_w}x{H} columns from the {hl.width}x{hl.height} raster...")
    lon = (np.arange(grid_w) + 0.5) / grid_w * 360.0 - 180.0
    lat = 90.0 - (np.arange(H) + 0.5) / H * 180.0
    LON, LAT = np.meshgrid(lon, lat)
    elev = hl.sample_bilinear(LON, LAT)
    # ruggedness: max |dh| to four neighbours 0.08 deg away (EarthClimate.ruggedness)
    d = 0.08
    rug = np.zeros_like(elev)
    for dx, dy in ((d, 0), (-d, 0), (0, d), (0, -d)):
        nb = hl.sample_bilinear(LON + dx, LAT + dy)
        rug = np.maximum(rug, np.abs(np.nan_to_num(nb) - np.nan_to_num(elev)))
    land = np.nan_to_num(elev) >= 0
    w = np.cos(np.radians(LAT))
    y = block_y(np.clip(np.nan_to_num(elev), 0, None))
    above = y - SEA_LEVEL                       # blocks above sea level, in game

    def pct(a, ws, qs):
        i = np.argsort(a)
        a, ws = a[i], ws[i]
        c = np.cumsum(ws) / ws.sum()
        return [float(a[np.searchsorted(c, q / 100.0)]) for q in qs]

    rl, wl, al = rug[land], w[land], above[land]
    qs = [50, 75, 90, 95, 99, 99.9]
    print("\nLAND relief (m over ~9 km), area-weighted percentiles:")
    for q, v in zip(qs, pct(rl, wl, qs)):
        print(f"   p{q:<5} {v:8.0f} m   -> current erosion "
              f"{max(-1.0, min(0.5, 0.45 - v / 500)):+.2f}")
    frac = float((wl[(rl > 725)].sum() / wl.sum()) * 100)
    print(f"\n   land already SATURATED at erosion -1.00 (relief > 725 m): {frac:.1f}%")

    print("\nin-game altitude (blocks above sea) percentiles:")
    for q, v in zip(qs, pct(al, wl, qs)):
        print(f"   p{q:<5} {v:8.1f} blocks")

    print("\njoint: mean/p90 relief by in-game altitude band")
    print(f"   {'blocks above sea':>18}{'share':>8}{'mean relief':>13}{'p90':>8}")
    bands = [(0, 5), (5, 15), (15, 30), (30, 60), (60, 120), (120, 999)]
    for lo, hi in bands:
        m = (al >= lo) & (al < hi)
        if not m.any():
            continue
        share = wl[m].sum() / wl.sum() * 100
        mean = float(np.average(rl[m], weights=wl[m]))
        p90 = pct(rl[m], wl[m], [90])[0]
        print(f"   {f'{lo}-{hi}':>18}{share:>7.1f}%{mean:>13.0f}{p90:>8.0f}")


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 2160)
