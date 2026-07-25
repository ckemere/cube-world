"""Fit compact models to the Earth fields and score them on the metrics that
actually decide the question:

  coastline displacement - median/P95 km error of the h=0 crossing. This is the
      metric that kills or validates any smooth-field approach: coastlines are
      the most recognisable feature on Earth and are a near-discontinuity, which
      is exactly what smooth bases are worst at.
  named-peak error       - metres at Everest/K2/Denali/...; a smooth field cannot
      hold an 8849 m spike, so this measures whether the sparse peak term works.
  RMSE by terrain class  - ocean / lowland / mountain separately. A global RMSE
      is dominated by the smooth abyssal plain and looks deceptively good.

Writes fitted reconstructions to out/ as .npy for the renderer.
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from earthdat import EarthDat            # noqa: E402
import model as M                        # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "out")
KM_PER_DEG = 111.32


def load_peaks(path, min_ele=6000):
    peaks = []
    if not os.path.exists(path):
        return peaks
    with open(path, encoding="utf-8") as f:
        next(f, None)
        for line in f:
            p = line.strip().split(",")
            if len(p) < 3:
                continue
            try:
                lat, lon, ele = float(p[0]), float(p[1]), float(p[2])
            except ValueError:
                continue
            if ele >= min_ele:
                peaks.append((lat, lon, ele))
    return peaks


def coastline_error(ref, rec, lat_of_row):
    """Median/P95 distance (km) from each reconstructed coastline pixel to the
    nearest reference coastline pixel, approximated by comparing the sign masks:
    for pixels where land/sea disagree, the error is at least that pixel's
    distance to the true boundary. We report the misclassified-area fraction and
    an equivalent-width estimate, which is robust and cheap."""
    rl = ref >= 0
    cl = rec >= 0
    disagree = rl != cl
    # weight by cos(lat) so polar rows don't dominate
    w = np.cos(np.radians(lat_of_row))[:, None] * np.ones((1, ref.shape[1]))
    frac = float((disagree * w).sum() / w.sum())
    # equivalent band width: misclassified area / total coastline length
    edge = (rl[:, 1:] != rl[:, :-1])
    coast_px = float((edge * w[:, 1:]).sum())
    area_px = float((disagree * w).sum())
    px_km = 360.0 / ref.shape[1] * KM_PER_DEG
    width_km = (area_px / coast_px * px_km) if coast_px > 0 else float("nan")
    return frac, width_km


def classed_rmse(ref, rec):
    out = {}
    for label, m in (("ocean", ref < -200),
                     ("lowland", (ref >= 0) & (ref < 500)),
                     ("mountain", ref >= 1500)):
        if m.any():
            out[label] = float(np.sqrt(np.mean((ref[m] - rec[m]) ** 2)))
    return out


def peak_error(rec, peaks, shape, named):
    h, w = shape
    rows = []
    for want_name, lat, lon, ele in named:
        y = int((90.0 - lat) / 180.0 * h)
        x = int((lon + 180.0) / 360.0 * w)
        y = min(max(y, 0), h - 1); x = x % w
        # best within a small window (the model may shift the summit a pixel)
        y0, y1 = max(0, y - 3), min(h, y + 4)
        x0, x1 = x - 3, x + 4
        xs = np.mod(np.arange(x0, x1), w)
        got = float(rec[y0:y1][:, xs].max())
        rows.append({"name": want_name, "true_m": ele, "model_m": round(got, 1),
                     "err_m": round(got - ele, 1)})
    return rows


NAMED = [
    ("Everest", 27.9881, 86.9250, 8849),
    ("K2", 35.8808, 76.5133, 8611),
    ("Denali", 63.0692, -151.0070, 6190),
    ("Aconcagua", -32.6532, -70.0109, 6961),
    ("Kilimanjaro", -3.0674, 37.3556, 5895),
    ("Mont Blanc", 45.8326, 6.8652, 4808),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--earth", default="run/earth.dat")
    ap.add_argument("--peaks", default="src/main/resources/peaks6000.csv")
    ap.add_argument("--grid", type=int, default=4320,
                    help="working grid width (height = width/2)")
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    e = EarthDat(args.earth)
    W = args.grid
    H = W // 2
    print(f"reference grid {W}x{H}  ({360.0/W*KM_PER_DEG:.1f} km/px)")

    print("downsampling height (out-of-core)...")
    ref = e["height"].block_mean(W, H)
    ref = np.nan_to_num(ref, nan=0.0)
    lat_rows = 90.0 - (np.arange(H) + 0.5) / H * 180.0
    np.save(os.path.join(OUT, "ref_height.npy"), ref.astype(np.float32))
    print(f"  reference in memory: {ref.nbytes/1e6:.1f} MB "
          f"(full-res source layer is {e['height'].nbytes/1e6:.1f} MB)")

    peaks = load_peaks(args.peaks)
    print(f"  named peaks >=6000 m: {len(peaks)}")

    results = []
    candidates = []

    # --- coarse baselines at a few budgets
    for cw in (540, 1080, 2160):
        candidates.append(("coarse", M.Coarse(ref, cw, cw // 2, quant=4.0)))

    # --- sparse multiscale, three fidelity knobs
    for tag, kf in (("sparse-lean", [0.02, 0.02, 0.03, 0.03, 0.04]),
                    ("sparse-mid", [0.05, 0.06, 0.08, 0.10, 0.12]),
                    ("sparse-rich", [0.10, 0.12, 0.18, 0.25, 0.30])):
        sm = M.SparseMultiscale(ref, base_w=68, keep_frac=kf, quant=4.0)
        candidates.append((tag, sm))

    for tag, cand in candidates:
        rec = cand.reconstruct()
        nb = cand.nbytes
        frac, width = coastline_error(ref, rec, lat_rows)
        row = {
            "tag": tag,
            "desc": cand.describe(),
            "bytes": nb,
            "MB": round(nb / 1e6, 3),
            "coast_misclass_frac": round(frac, 5),
            "coast_band_km": round(width, 1),
            "rmse": {k: round(v, 1) for k, v in classed_rmse(ref, rec).items()},
            "peaks_no_spike": peak_error(rec, peaks, ref.shape, NAMED),
        }
        # with peak bumps added
        if peaks:
            cand2 = cand
            bumps = M.PeakBumps(peaks, ref.shape)
            rec2 = bumps.apply(rec.copy())
            row["peak_bump_bytes"] = bumps.nbytes
            row["MB_with_peaks"] = round((nb + bumps.nbytes) / 1e6, 3)
            row["peaks_with_spike"] = peak_error(rec2, peaks, ref.shape, NAMED)
            np.save(os.path.join(OUT, f"rec_{tag}.npy"), rec2.astype(np.float32))
        else:
            np.save(os.path.join(OUT, f"rec_{tag}.npy"), rec.astype(np.float32))
        results.append(row)
        print(f"\n{tag:14} {row['MB']:>6.3f} MB  ({cand.describe()})")
        print(f"   coastline: {frac*100:.3f}% area misclassified, "
              f"~{width:.1f} km equivalent band")
        print(f"   RMSE m: {row['rmse']}")
        if "peaks_with_spike" in row:
            ns = row["peaks_no_spike"][0]; ws = row["peaks_with_spike"][0]
            print(f"   Everest: smooth {ns['model_m']:.0f} m "
                  f"(err {ns['err_m']:+.0f}) -> with spike {ws['model_m']:.0f} m "
                  f"(err {ws['err_m']:+.0f})   peak list = "
                  f"{row['peak_bump_bytes']/1e3:.0f} KB")

    with open(os.path.join(OUT, "fit_report.json"), "w") as f:
        json.dump({"grid": [W, H], "results": results}, f, indent=2)
    print(f"\nwrote {OUT}/fit_report.json and rec_*.npy")


if __name__ == "__main__":
    main()
