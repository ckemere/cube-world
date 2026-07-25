"""Build a sea-surface-temperature field that is TOTAL: defined at every cell, so
water can never lack a temperature.

WOA23 is ocean-only on a 1 deg grid, but our land/sea mask is 9.3 km. Any 1 deg cell
that is mostly land reads NODATA while at 9.3 km there really is water there —
coastal bays, the Mediterranean and Red Sea near their shores, the Baltic, the
Black Sea, fjords. Rather than add runtime fallbacks, the field is inpainted to
full global coverage at BUILD time and asserted hole-free, so every lookup is
guaranteed to return a number.

Priority: real WOA value -> nearest-valid inpaint (short distances, so enclosed
seas keep their own character) -> latitude formula as a final backstop that should
never be reached.
"""
import os
import re
import sys
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from earthdat import EarthDat                                  # noqa: E402
from islands import _upsample_mask, _resize_nearest            # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "out")
ASCII = os.path.join(OUT, "woa_sst.ascii")
FILL = 9.9e35
WOA_URL = ("https://www.ncei.noaa.gov/thredds-ocean/dodsC/woa23/DATA/temperature/"
           "netcdf/decav/1.00/woa23_decav_t00_01.nc.ascii"
           "?t_an%5B0%5D%5B0%5D%5B0:179%5D%5B0:359%5D")


def parse_woa(path=ASCII):
    """WOA23 surface annual mean -> (180, 360) float array, north-first, NaN holes."""
    rows = {}
    with open(path) as f:
        for line in f:
            m = re.match(r"\[0\]\[0\]\[(\d+)\], (.*)", line.strip())
            if m:
                rows[int(m.group(1))] = np.array(
                    [float(v) for v in m.group(2).split(",")])
    H, W = len(rows), len(rows[0])
    a = np.full((H, W), np.nan)
    for r, v in rows.items():
        a[r] = np.where(v > FILL, np.nan, v)
    return a[::-1]                      # WOA row 0 is 89.5 S -> flip to north-first


def inpaint_total(a, lat_fallback=True):
    """Fill every NaN by iteratively averaging valid neighbours (wrapping in
    longitude). Guarantees a hole-free result."""
    out = np.array(a, dtype=np.float64)
    known = np.isfinite(out).astype(np.float64)
    out[known == 0] = 0.0
    filled = 0
    while not known.all():
        def rs(m):
            return (np.roll(m, 1, 0) + np.roll(m, -1, 0)
                    + np.roll(m, 1, 1) + np.roll(m, -1, 1))
        s = rs(out * known)
        c = rs(known)
        new = (c > 0) & (known == 0)
        if not new.any():
            break
        out[new] = s[new] / c[new]
        known[new] = 1.0
        filled += int(new.sum())
    if not known.all() and lat_fallback:      # unreachable region: latitude curve
        H = out.shape[0]
        lat = 90.0 - (np.arange(H) + 0.5) / H * 180.0
        fb = (27.0 - np.abs(lat) * 0.45)[:, None] * np.ones((1, out.shape[1]))
        out[known == 0] = fb[known == 0]
    return out, filled


def main():
    if not os.path.exists(ASCII):
        sys.exit(f"missing {ASCII}\n  fetch with:\n  curl -s '{WOA_URL}' -o {ASCII}")
    raw = parse_woa()
    H, W = raw.shape
    have = np.isfinite(raw)
    print(f"WOA23 surface annual mean: {W}x{H}, {have.sum():,} cells with data "
          f"({have.mean()*100:.0f}%), {np.nanmin(raw):.1f}..{np.nanmax(raw):.1f} C")

    # --- how much water does our 9.3 km mask see that WOA (1 deg) does not?
    e = EarthDat("run/earth.dat")
    mask = _upsample_mask(e["height"], 4320, 2160)         # True = land
    water = ~mask
    woa_have_at_mask = _resize_nearest(have, water.shape)
    gap = water & ~woa_have_at_mask
    latw = np.cos(np.radians(90.0 - (np.arange(water.shape[0]) + 0.5)
                             / water.shape[0] * 180.0))[:, None]
    wsum = (water * latw).sum()
    print(f"\nwater cells (9.3 km) with NO WOA value: {gap.sum():,} "
          f"= {(gap*latw).sum()/wsum*100:.2f}% of all water")
    print("  -> these are exactly the coastal/enclosed-sea cells a 1 deg ocean grid "
          "drops.\n     Without inpainting they would have no temperature at all.")

    sst, filled = inpaint_total(raw)
    assert np.isfinite(sst).all(), "still holes after inpaint"
    print(f"\ninpainted {filled:,} cells -> field is now TOTAL "
          f"(finite everywhere: {np.isfinite(sst).all()})")

    q = np.round(sst / 0.5).astype(np.int16)
    nb = len(zlib.compress(q.tobytes(), 9))
    print(f"360x180 int16 @0.5 C, zlib = {nb/1e3:.0f} KB")

    print("\n=== spot check (incl. the enclosed seas a 1 deg grid struggles with) ===")
    def at(lat, lon):
        y = int(round((90.0 - lat) / 180.0 * H - 0.5))
        x = int(round((lon + 180.0) / 360.0 * W - 0.5)) % W
        return sst[min(max(y, 0), H - 1), x], have[min(max(y, 0), H - 1), x]
    checks = [("Mediterranean (Sicily)", 38, 15, 19), ("Black Sea", 43, 34, 15),
              ("Baltic", 58, 20, 8), ("Red Sea", 20, 38, 29),
              ("Persian Gulf", 26, 52, 28), ("Hudson Bay", 60, -85, 2),
              ("Norway coast", 68, 10, 8), ("Peru upwelling", -12, -78, 18),
              ("Caribbean", 15, -75, 28), ("Arctic", 85, 0, -1.7)]
    print(f"  {'place':24}{'SST':>8}{'real':>7}  source")
    for n, la, lo, real in checks:
        v, src = at(la, lo)
        print(f"  {n:24}{v:>8.1f}{real:>7}  {'WOA' if src else 'inpainted'}")

    np.save(os.path.join(OUT, "sst_total.npy"), sst.astype(np.float32))
    print(f"\nsaved {OUT}/sst_total.npy")


if __name__ == "__main__":
    main()
