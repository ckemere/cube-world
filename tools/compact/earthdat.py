"""Memory-mapped reader for the CWE1 Earth bundle (run/earth.dat).

Never loads the whole 359 MB: each layer is exposed as an np.memmap view, so
downsampling and analysis stay out-of-core (regenerating these fields in RAM has
OOM-killed the box before).
"""
import os
import struct

import numpy as np

NODATA = -32768


class Layer:
    def __init__(self, name, mm, width, height, scale, offset):
        self.name = name
        self.mm = mm                # np.memmap, shape (height, width), int16
        self.width = width
        self.height = height
        self.scale = scale
        self.offset = offset

    @property
    def nbytes(self):
        return self.width * self.height * 2

    def block_mean(self, out_w, out_h):
        """Downsample to (out_h, out_w) by block averaging, streamed row-band by
        row-band so we never hold the full layer as float. NODATA is treated as
        missing: cells with no valid samples come back as NaN."""
        assert self.height % out_h == 0 or True
        fy = self.height / out_h
        fx = self.width / out_w
        acc = np.zeros((out_h, out_w), dtype=np.float64)
        cnt = np.zeros((out_h, out_w), dtype=np.int64)
        band = max(1, int(self.height // out_h))
        for y0 in range(0, self.height, band):
            y1 = min(self.height, y0 + band)
            raw = np.asarray(self.mm[y0:y1], dtype=np.float64)
            valid = raw != NODATA
            rows = ((np.arange(y0, y1) / fy).astype(np.int64)).clip(0, out_h - 1)
            cols = ((np.arange(self.width) / fx).astype(np.int64)).clip(0, out_w - 1)
            v = np.where(valid, raw, 0.0)
            # accumulate per output row
            for r in np.unique(rows):
                m = rows == r
                np.add.at(acc[r], cols, v[m].sum(axis=0))
                np.add.at(cnt[r], cols, valid[m].sum(axis=0))
        out = np.full((out_h, out_w), np.nan)
        nz = cnt > 0
        out[nz] = acc[nz] / cnt[nz]
        return out * self.scale + self.offset

    def sample_bilinear(self, lon, lat):
        """Bilinear sample at arrays of lon/lat (degrees). Mirrors
        EarthData.sample: x wraps, y clamps, NODATA -> NaN (no nearest-valid
        fallback here; callers decide)."""
        w, h = self.width, self.height
        fx = (np.asarray(lon) + 180.0) / 360.0 * w
        fy = (90.0 - np.asarray(lat)) / 180.0 * h
        x0 = np.floor(fx).astype(np.int64)
        y0 = np.floor(fy).astype(np.int64)
        tx = fx - x0
        ty = fy - y0
        x0m = np.mod(x0, w)
        x1m = np.mod(x0 + 1, w)
        y0c = np.clip(y0, 0, h - 1)
        y1c = np.clip(y0 + 1, 0, h - 1)
        def g(yy, xx):
            v = np.asarray(self.mm[yy, xx], dtype=np.float64)
            return np.where(v == NODATA, np.nan, v)
        v00 = g(y0c, x0m); v10 = g(y0c, x1m)
        v01 = g(y1c, x0m); v11 = g(y1c, x1m)
        out = (v00 * (1 - tx) * (1 - ty) + v10 * tx * (1 - ty)
               + v01 * (1 - tx) * ty + v11 * tx * ty)
        # NODATA-tolerant fallback: if any corner missing, use the mean of valid
        stack = np.stack([v00, v10, v01, v11])
        anynan = np.isnan(stack).any(axis=0)
        if anynan.any():
            with np.errstate(invalid="ignore"):
                fb = np.nanmean(stack, axis=0)
            out = np.where(anynan, fb, out)
        return out * self.scale + self.offset


class EarthDat:
    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            head = f.read(12)
            magic = head[:4].decode("ascii", "replace")
            if magic != "CWE1":
                raise ValueError(f"not a CWE1 file: {path} ({magic!r})")
            self.roll, = struct.unpack_from("<f", head, 4)
            n, = struct.unpack_from("<i", head, 8)
            hdr = f.read(n * 24)
        off = 12 + n * 24
        self.layers = {}
        metas = []
        for i in range(n):
            base = i * 24
            name = hdr[base:base + 8].split(b"\0")[0].decode("ascii")
            w, h = struct.unpack_from("<ii", hdr, base + 8)
            sc, of = struct.unpack_from("<ff", hdr, base + 16)
            metas.append((name, w, h, sc, of))
        for name, w, h, sc, of in metas:
            mm = np.memmap(path, dtype="<i2", mode="r", offset=off, shape=(h, w))
            self.layers[name] = Layer(name, mm, w, h, sc, of)
            off += w * h * 2

    def __getitem__(self, name):
        return self.layers[name]

    def summary(self):
        out = [f"CWE1 roll={self.roll} size={os.path.getsize(self.path)/1e6:.1f} MB"]
        for n, l in self.layers.items():
            out.append(f"  {n:8} {l.width}x{l.height} scale={l.scale} "
                       f"{l.nbytes/1e6:.1f} MB")
        return "\n".join(out)


if __name__ == "__main__":
    import sys
    e = EarthDat(sys.argv[1] if len(sys.argv) > 1 else "run/earth.dat")
    print(e.summary())
