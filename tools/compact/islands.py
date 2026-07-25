"""Do small islands survive the compact parameterisation?

Block-mean downsampling averages an island together with the deep ocean around
it, so a 500 m volcano in a 37 km cell surrounded by -5000 m seafloor can come
out below sea level and simply vanish. This scores that directly: named islands
plus a global land-area audit, for the full-resolution raster and for each
candidate reconstruction.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from earthdat import EarthDat        # noqa: E402
import model as M                    # noqa: E402

# (name, lat, lon, real max elevation m, kind)
ISLANDS = [
    ("Hawaii (Mauna Kea)", 19.82, -155.47, 4207, "high"),
    ("Maui", 20.71, -156.25, 3055, "high"),
    ("Oahu (Honolulu)", 21.47, -157.97, 1220, "high"),
    ("Tahiti", -17.62, -149.45, 2241, "high"),
    ("Samoa (Savai'i)", -13.61, -172.52, 1858, "high"),
    ("Fiji (Viti Levu)", -17.80, 178.05, 1324, "high"),
    ("Tonga (Kao)", -19.67, -175.03, 1030, "high"),
    ("Bora Bora", -16.50, -151.74, 727, "high"),
    ("Rarotonga", -21.23, -159.78, 658, "high"),
    ("Easter Island", -27.11, -109.37, 507, "high"),
    ("Galapagos (Isabela)", -0.43, -91.13, 1707, "high"),
    ("Guam", 13.44, 144.78, 406, "low"),
    ("Palau (Babeldaob)", 7.50, 134.57, 242, "low"),
    ("Nauru", -0.52, 166.93, 61, "low"),
    ("Tuvalu (Funafuti)", -8.52, 179.20, 5, "atoll"),
    ("Kiribati (Tarawa)", 1.35, 172.98, 3, "atoll"),
    ("Marshall Is (Majuro)", 7.09, 171.38, 3, "atoll"),
    ("Maldives (Male)", 4.18, 73.51, 2, "atoll"),
    # controls: large land that must never be lost
    ("New Zealand (S)", -43.53, 170.14, 3724, "control"),
    ("Iceland", 64.90, -19.00, 2110, "control"),
    ("Madagascar", -19.00, 46.80, 2876, "control"),
    ("Japan (Honshu)", 35.36, 138.73, 3776, "control"),
]


def _upsample_mask(layer, w, h):
    """Land mask at (h, w) taken from the FULL-RESOLUTION layer: a cell is land if
    ANY full-res sample inside it is land. That is what keeps small islands."""
    out = np.zeros((h, w), dtype=bool)
    band = max(1, layer.height // h)
    fy = layer.height / h
    fx = layer.width / w
    cols = np.minimum((np.arange(layer.width) / fx).astype(np.int64), w - 1)
    for y0 in range(0, layer.height, band):
        y1 = min(layer.height, y0 + band)
        raw = np.asarray(layer.mm[y0:y1], dtype=np.float64)
        land = (raw != -32768) & ((raw * layer.scale + layer.offset) >= 0)
        rows = np.minimum((np.arange(y0, y1) / fy).astype(np.int64), h - 1)
        for r in np.unique(rows):
            sel = land[rows == r]
            acc = np.zeros(w, dtype=bool)
            np.logical_or.at(acc, cols, sel.any(axis=0))
            out[r] |= acc
    return out


def _resize_nearest(mask, shape):
    h, w = shape
    ys = np.minimum((np.arange(h) * mask.shape[0] // h), mask.shape[0] - 1)
    xs = np.minimum((np.arange(w) * mask.shape[1] // w), mask.shape[1] - 1)
    return mask[np.ix_(ys, xs)]


def sample_grid(field, lat, lon):
    h, w = field.shape
    y = int((90.0 - lat) / 180.0 * h)
    x = int((lon + 180.0) / 360.0 * w) % w
    y = min(max(y, 0), h - 1)
    return float(field[y, x])


def sample_grid_max(field, lat, lon, rad=1):
    """Max in a small neighbourhood — an island may sit a pixel off."""
    h, w = field.shape
    y = int((90.0 - lat) / 180.0 * h)
    x = int((lon + 180.0) / 360.0 * w) % w
    ys = np.clip(np.arange(y - rad, y + rad + 1), 0, h - 1)
    xs = np.mod(np.arange(x - rad, x + rad + 1), w)
    return float(field[np.ix_(ys, xs)].max())


def land_area_frac(field, lat_rows):
    w = np.cos(np.radians(lat_rows))[:, None] * np.ones((1, field.shape[1]))
    return float(((field >= 0) * w).sum() / w.sum())


def main():
    e = EarthDat("run/earth.dat")
    hl = e["height"]

    print("=" * 78)
    print("FULL-RESOLUTION SOURCE (10800x5400, 3.7 km/px) — the ground truth")
    print("=" * 78)
    truth = {}
    for name, lat, lon, ele, kind in ISLANDS:
        v = hl.sample_bilinear(np.array([lon]), np.array([lat]))[0]
        truth[name] = v
        flag = "" if v >= 0 else "  <-- ALREADY BELOW SEA LEVEL"
        print(f"  {name:24} {kind:8} raster {v:8.1f} m (real {ele:5d} m){flag}")

    W, H = 2160, 1080
    print(f"\nbuilding reference {W}x{H} + candidates...")
    ref = np.nan_to_num(hl.block_mean(W, H), nan=0.0)
    lat_rows = 90.0 - (np.arange(H) + 0.5) / H * 180.0
    peaks_path = "src/main/resources/peaks6000.csv"
    from fit import load_peaks
    peaks = load_peaks(peaks_path)

    cands = [("ref 2160x1080", ref)]
    for cw in (540, 1080):
        c = M.Coarse(ref, cw, cw // 2, quant=4.0)
        rec = c.reconstruct()
        cands.append((f"coarse {cw}x{cw//2} ({c.nbytes/1e3:.0f} KB)", rec))

    # --- the fix under test: decouple the LAND/SEA MASK from the elevation field.
    # A binary mask needs resolution (islands and coastlines are small) but
    # compresses brilliantly, because it is spatially coherent; elevation can stay
    # coarse. Reconstruct = coarse elevation, with its SIGN forced to agree with
    # the mask, so any cell the mask calls land is land no matter how much ocean
    # the elevation average was diluted by.
    import zlib
    for mask_w, elev_w in ((2160, 1080), (4320, 1080)):
        mh = mask_w // 2
        if mask_w == ref.shape[1]:
            mask = ref >= 0
        else:
            mask = _upsample_mask(hl, mask_w, mh)
        packed = zlib.compress(np.packbits(mask.ravel()).tobytes(), 9)
        c = M.Coarse(ref, elev_w, elev_w // 2, quant=4.0)
        rec = c.reconstruct()
        m_at_ref = mask if mask.shape == rec.shape else _resize_nearest(mask, rec.shape)
        fixed = np.where(m_at_ref, np.maximum(rec, 12.0), np.minimum(rec, -12.0))
        total = c.nbytes + len(packed)
        cands.append((f"mask {mask_w} + elev {elev_w} ({total/1e3:.0f} KB)", fixed))
        print(f"  mask {mask_w}x{mh}: {len(packed)/1e3:.0f} KB compressed "
              f"(+ elev {c.nbytes/1e3:.0f} KB = {total/1e3:.0f} KB total)")

    print("\n" + "=" * 78)
    print("ISLAND SURVIVAL  (value at the island; 'sunk' = reconstructed below 0)")
    print("=" * 78)
    hdr = f"  {'island':24} {'kind':8}" + "".join(f"{n[:16]:>18}" for n, _ in cands)
    print(hdr)
    sunk = {n: [] for n, _ in cands}
    for name, lat, lon, ele, kind in ISLANDS:
        row = f"  {name:24} {kind:8}"
        for cname, field in cands:
            v = sample_grid_max(field, lat, lon)
            mark = "" if v >= 0 else "*"
            row += f"{v:>17.0f}{mark}"
            if v < 0 and truth[name] >= 0:
                sunk[cname].append(name)
        print(row)

    print("\n" + "=" * 78)
    print("SUMMARY")
    print("=" * 78)
    base_land = land_area_frac(ref, lat_rows)
    for cname, field in cands:
        la = land_area_frac(field, lat_rows)
        lost = sunk[cname]
        print(f"  {cname:34} land {la*100:5.2f}%  "
              f"(ref {base_land*100:.2f}%)  islands sunk: {len(lost)}")
        if lost:
            print(f"      {', '.join(lost)}")


if __name__ == "__main__":
    main()
