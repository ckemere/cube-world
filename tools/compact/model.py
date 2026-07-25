"""Compact parameterisations of the Earth fields, with exact byte accounting.

Two candidates, both reconstructing a full-resolution field from few parameters:

  coarse   - a low-resolution grid, bicubic-upsampled. The dumb baseline; any
             fancier scheme has to beat it per byte or it isn't worth the code.

  sparse   - a sparse multiscale Gaussian mixture, implemented as a thresholded
             Laplacian pyramid. Each retained coefficient is a smooth bump whose
             width is set by its pyramid level, so this IS a mixture of
             Gaussians on a dyadic grid: coarse levels carry the broad shape,
             fine levels are the "spikes" (ridges, summits, coastal steps).
             Thresholding per level trades bytes for fidelity on one knob.

Named summits are added back as explicit narrow bumps from peaks6000.csv, since
no smooth field resolves an 8849 m spike that is ~28 blocks wide.
"""
import numpy as np


# --------------------------------------------------------------------- helpers
def _down2(a):
    """Binomial-smoothed 2x decimation, wrapping in x (global field), clamped y."""
    k = np.array([1, 4, 6, 4, 1], dtype=np.float64) / 16.0
    b = np.apply_along_axis(lambda m: np.convolve(m, k, mode="same"), 1,
                            np.concatenate([a[:, -2:], a, a[:, :2]], axis=1))[:, 2:-2]
    b = np.apply_along_axis(lambda m: np.convolve(m, k, mode="same"), 0, b)
    return b[::2, ::2].copy()


def _up2(a, shape):
    """Bilinear 2x upsample to an explicit shape (handles odd sizes)."""
    h, w = shape
    ys = (np.arange(h) + 0.5) * a.shape[0] / h - 0.5
    xs = (np.arange(w) + 0.5) * a.shape[1] / w - 0.5
    y0 = np.floor(ys).astype(int); x0 = np.floor(xs).astype(int)
    ty = (ys - y0)[:, None]; tx = (xs - x0)[None, :]
    y0c = np.clip(y0, 0, a.shape[0] - 1); y1c = np.clip(y0 + 1, 0, a.shape[0] - 1)
    x0m = np.mod(x0, a.shape[1]); x1m = np.mod(x0 + 1, a.shape[1])
    return (a[np.ix_(y0c, x0m)] * (1 - ty) * (1 - tx)
            + a[np.ix_(y0c, x1m)] * (1 - ty) * tx
            + a[np.ix_(y1c, x0m)] * ty * (1 - tx)
            + a[np.ix_(y1c, x1m)] * ty * tx)


def bicubic_like(a, shape):
    """Good-enough smooth upsample (two bilinear passes ~ smoother kernel)."""
    mid = _up2(a, (min(shape[0], a.shape[0] * 2), min(shape[1], a.shape[1] * 2)))
    return _up2(mid, shape)


# ------------------------------------------------------------------ candidates
class Coarse:
    """Low-resolution grid, smooth-upsampled. Bytes = w*h*2 (int16), and we also
    report the zlib-compressed size since that is what would ship."""

    name = "coarse"

    def __init__(self, field, out_w, out_h, quant=1.0):
        self.shape = field.shape
        self.quant = quant
        small = _resize_mean(field, out_w, out_h)
        self.grid = np.round(small / quant).astype(np.int16)

    @property
    def nbytes(self):
        import zlib
        raw = self.grid.tobytes()
        return len(zlib.compress(raw, 9))

    @property
    def raw_bytes(self):
        return self.grid.size * 2

    def reconstruct(self):
        return bicubic_like(self.grid.astype(np.float64) * self.quant, self.shape)

    def describe(self):
        return f"coarse {self.grid.shape[1]}x{self.grid.shape[0]} q={self.quant}"


class SparseMultiscale:
    """Thresholded Laplacian pyramid = sparse multiscale Gaussian mixture.

    levels[0] is the coarsest grid (kept dense - it is tiny). Each finer level
    stores only coefficients whose magnitude exceeds that level's threshold, as
    (index, value) pairs. Bytes are counted honestly: dense base + per level a
    zlib-compressed bitmask of kept positions plus int16 values.
    """

    name = "sparse"

    def __init__(self, field, levels=6, base_w=64, keep_frac=None, thresh=None,
                 quant=1.0):
        self.shape = field.shape
        self.quant = quant
        # build pyramid down to ~base_w wide
        pyr = [field]
        while pyr[-1].shape[1] > base_w:
            pyr.append(_down2(pyr[-1]))
        pyr = pyr[::-1]                      # coarse -> fine
        self.base = np.round(pyr[0] / quant).astype(np.int16)
        self.det = []                        # per level: (shape, mask, values)
        cur = pyr[0].astype(np.float64)
        for lv in range(1, len(pyr)):
            target = pyr[lv].astype(np.float64)
            pred = _up2(cur, target.shape)
            resid = target - pred
            if thresh is not None:
                t = thresh[min(lv - 1, len(thresh) - 1)]
            else:
                kf = keep_frac[min(lv - 1, len(keep_frac) - 1)] if keep_frac else 0.05
                t = np.quantile(np.abs(resid), 1.0 - kf) if kf < 1.0 else 0.0
            mask = np.abs(resid) > t
            vals = np.round(resid[mask] / quant).astype(np.int16)
            self.det.append((target.shape, mask, vals))
            keep = np.zeros_like(resid)
            keep[mask] = vals.astype(np.float64) * quant
            cur = pred + keep
        self.peaks = None

    @property
    def nbytes(self):
        import zlib
        n = len(zlib.compress(self.base.tobytes(), 9))
        for shape, mask, vals in self.det:
            n += len(zlib.compress(np.packbits(mask.ravel()).tobytes(), 9))
            n += len(zlib.compress(vals.tobytes(), 9))
        if self.peaks is not None:
            n += self.peaks.nbytes
        return n

    def kept(self):
        return [int(m.sum()) for _, m, _ in self.det]

    def reconstruct(self):
        cur = self.base.astype(np.float64) * self.quant
        for shape, mask, vals in self.det:
            pred = _up2(cur, shape)
            add = np.zeros(shape)
            add[mask] = vals.astype(np.float64) * self.quant
            cur = pred + add
        out = bicubic_like(cur, self.shape) if cur.shape != self.shape else cur
        if self.peaks is not None:
            out = self.peaks.apply(out)
        return out

    def describe(self):
        k = self.kept()
        return (f"sparse base {self.base.shape[1]}x{self.base.shape[0]} "
                f"+ levels{k} q={self.quant}")


class PeakBumps:
    """Named summits as narrow Gaussian bumps on a lon/lat grid.

    A Gaussian (not the current linear cone) so the field stays C-infinity and
    analytic gradients - which is what erosion/jaggedness would be derived from -
    have no kink at the summit or a ring at the cone rim.
    """

    def __init__(self, peaks, shape, sigma_deg=0.12):
        self.peaks = peaks              # list of (lat, lon, ele_m)
        self.shape = shape
        self.sigma = sigma_deg

    @property
    def nbytes(self):
        # lat, lon as int16 (1/100 deg) + ele as int16 metres = 6 bytes each
        return len(self.peaks) * 6

    def apply(self, field):
        h, w = self.shape
        out = field
        s = self.sigma
        for lat, lon, ele in self.peaks:
            # bounding box a few sigma around the peak
            y = (90.0 - lat) / 180.0 * h
            x = (lon + 180.0) / 360.0 * w
            ry = max(1, int(3 * s / 180.0 * h))
            rx = max(1, int(3 * s / 360.0 * w))
            y0, y1 = int(y) - ry, int(y) + ry + 1
            x0, x1 = int(x) - rx, int(x) + rx + 1
            ys = np.clip(np.arange(y0, y1), 0, h - 1)
            xs = np.mod(np.arange(x0, x1), w)
            dlat = (90.0 - (ys + 0.5) / h * 180.0) - lat
            dlon = ((xs + 0.5) / w * 360.0 - 180.0) - lon
            dlon = (dlon + 180) % 360 - 180
            g = np.exp(-((dlat[:, None] ** 2) + (dlon[None, :] * np.cos(np.radians(lat))) ** 2)
                       / (2 * s * s))
            patch = out[np.ix_(ys, xs)]
            out[np.ix_(ys, xs)] = np.maximum(patch, np.where(g > 0.01, ele * g, patch))
        return out


def _resize_mean(a, out_w, out_h):
    """Area-average resize (handles non-integer ratios)."""
    h, w = a.shape
    ys = (np.arange(h) * out_h // h)
    xs = (np.arange(w) * out_w // w)
    acc = np.zeros((out_h, out_w)); cnt = np.zeros((out_h, out_w))
    np.add.at(acc, (ys[:, None], xs[None, :]), a)
    np.add.at(cnt, (ys[:, None], xs[None, :]), 1)
    cnt[cnt == 0] = 1
    return acc / cnt
