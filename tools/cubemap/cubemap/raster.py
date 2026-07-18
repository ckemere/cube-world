"""Real elevation raster (ETOPO 2022) -> sampling + hypsometric coloring, and
per-cube-face reprojection. This is the ingest preview path: the same
cube-point -> lon/lat mapping the generator will use, but colored for display
rather than turned into blocks.
"""
from __future__ import annotations
import numpy as np


class EquirectRaster:
    """An equirectangular grid, lon -180..180 across, lat +90..-90 down."""

    def __init__(self, grid):
        self.grid = grid  # (H, W) float
        self.h, self.w = grid.shape

    @classmethod
    def load_etopo(cls, path, step=1):
        import netCDF4
        d = netCDF4.Dataset(path)
        z = d.variables["z"]
        g = np.asarray(z[::step, ::step], dtype=np.float32)
        # ETOPO lat runs south->north (row 0 = -90); flip to north-at-top
        lat = np.asarray(d.variables["lat"][::step])
        if lat[0] < lat[-1]:
            g = g[::-1]
        return cls(g)

    def sample(self, lon, lat):
        """Bilinear sample at lon/lat arrays (degrees). Returns float array."""
        fx = (lon + 180.0) / 360.0 * self.w
        fy = (90.0 - lat) / 180.0 * self.h
        fx = np.clip(fx, 0, self.w - 1.001)
        fy = np.clip(fy, 0, self.h - 1.001)
        x0 = fx.astype(int); y0 = fy.astype(int)
        x1 = (x0 + 1) % self.w; y1 = np.minimum(y0 + 1, self.h - 1)
        tx = fx - x0; ty = fy - y0
        g = self.grid
        v = (g[y0, x0] * (1 - tx) * (1 - ty) + g[y0, x1] * tx * (1 - ty)
             + g[y1, x0] * (1 - tx) * ty + g[y1, x1] * tx * ty)
        return v


# hypsometric palette: (elevation_m, (r,g,b)). Land above 0, bathymetry below.
_LAND = [
    (0,    (96, 150, 82)),
    (200,  (126, 171, 92)),
    (600,  (183, 191, 110)),
    (1200, (205, 178, 121)),
    (2200, (174, 139, 102)),
    (3200, (150, 130, 120)),
    (4200, (225, 225, 228)),
    (6000, (255, 255, 255)),
]
_SEA = [
    (0,     (58, 132, 165)),
    (-200,  (46, 110, 150)),
    (-1000, (36, 88, 132)),
    (-3000, (26, 62, 105)),
    (-6000, (16, 40, 78)),
    (-11000,(9, 24, 56)),
]


def _ramp(val, table):
    xs = [t[0] for t in table]
    cols = np.array([t[1] for t in table], dtype=np.float32)
    idx = np.clip(np.searchsorted(xs, val) - 1, 0, len(xs) - 2) if False else None
    # manual piecewise for scalar or array
    val = np.asarray(val)
    out = np.zeros(val.shape + (3,), dtype=np.float32)
    for i in range(len(table) - 1):
        x0, c0 = table[i]; x1, c1 = table[i + 1]
        lo, hi = (min(x0, x1), max(x0, x1))
        m = (val >= lo) & (val < hi)
        t = ((val - x0) / (x1 - x0))
        for k in range(3):
            out[..., k] = np.where(m, c0[k] + (c1[k] - c0[k]) * t, out[..., k])
    # clamp ends
    below = val < min(table[0][0], table[-1][0])
    above = val >= max(table[0][0], table[-1][0])
    end_lo = table[0][1] if table[0][0] < table[-1][0] else table[-1][1]
    end_hi = table[-1][1] if table[-1][0] > table[0][0] else table[0][1]
    for k in range(3):
        out[..., k] = np.where(below, end_lo[k], out[..., k])
        out[..., k] = np.where(above, end_hi[k], out[..., k])
    return out


def hypsometric_rgb(elev, lat):
    """Color elevation (m) with a latitude-aware snow line. elev/lat: arrays."""
    elev = np.asarray(elev, dtype=np.float32)
    lat = np.asarray(lat, dtype=np.float32)
    land = elev >= 0
    col = np.where(land[..., None], _ramp(elev, _LAND), _ramp(elev, _SEA))
    # latitude snow: above a latitude-dependent line, whiten land
    snow_line = 3200.0 - (np.abs(lat) - 30.0) * 90.0   # drops ~90m per deg past 30
    snow_line = np.clip(snow_line, -50.0, 4000.0)
    snow = land & (elev > snow_line)
    white = np.array([248, 249, 252], dtype=np.float32)
    frac = np.clip((elev - snow_line) / 600.0, 0, 1)[..., None]
    col = np.where(snow[..., None], col * (1 - frac) + white * frac, col)
    # polar sea ice
    ice = (~land) & (np.abs(lat) > 70)
    col = np.where(ice[..., None], np.array([228, 236, 244], dtype=np.float32), col)
    return col.astype(np.uint8)
