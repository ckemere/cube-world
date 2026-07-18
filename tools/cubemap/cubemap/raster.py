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

    @classmethod
    def load_geotiff(cls, path):
        """Load a north-up global equirectangular GeoTIFF (e.g. WorldClim),
        masking the -3.4e38 nodata to NaN."""
        import tifffile
        g = tifffile.imread(path).astype(np.float32)
        g = np.where(g < -1e30, np.nan, g)
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


# Whittaker biome color field: rows = temperature anchors (cold->hot),
# cols = precipitation anchors (dry->wet). Bilinear-interpolated for smoothness.
_TEMP_ANCHORS = np.array([-15.0, 0.0, 10.0, 22.0, 30.0])
_PREC_ANCHORS = np.array([0.0, 250.0, 600.0, 1200.0, 2500.0])
_BIOME_GRID = np.array([
    # arid        semi-arid      moderate       wet            very wet
    [[214,216,222],[168,172,158],[150,158,140],[236,240,246],[236,240,246]],  # -15 arctic/tundra
    [[150,150,120],[96,120,86],  [70,102,72],   [56,92,64],    [50,86,60]],    #   0 boreal/taiga
    [[178,172,112],[156,166,102],[100,140,82],  [82,128,74],   [60,110,70]],   #  10 temperate
    [[216,194,150],[192,180,112],[150,162,88],  [98,148,74],   [60,124,66]],   #  22 warm/subtropical
    [[222,200,150],[204,186,120],[178,174,92],  [92,148,70],   [46,114,58]],   #  30 hot/tropical
], dtype=np.float32)


def _bilerp_grid(temp, precip):
    t = np.clip(temp, _TEMP_ANCHORS[0], _TEMP_ANCHORS[-1])
    p = np.clip(precip, _PREC_ANCHORS[0], _PREC_ANCHORS[-1])
    ti = np.clip(np.searchsorted(_TEMP_ANCHORS, t) - 1, 0, len(_TEMP_ANCHORS) - 2)
    pi = np.clip(np.searchsorted(_PREC_ANCHORS, p) - 1, 0, len(_PREC_ANCHORS) - 2)
    tf = (t - _TEMP_ANCHORS[ti]) / (_TEMP_ANCHORS[ti + 1] - _TEMP_ANCHORS[ti])
    pf = (p - _PREC_ANCHORS[pi]) / (_PREC_ANCHORS[pi + 1] - _PREC_ANCHORS[pi])
    tf = tf[..., None]; pf = pf[..., None]
    c00 = _BIOME_GRID[ti, pi]; c01 = _BIOME_GRID[ti, pi + 1]
    c10 = _BIOME_GRID[ti + 1, pi]; c11 = _BIOME_GRID[ti + 1, pi + 1]
    return (c00 * (1 - tf) * (1 - pf) + c01 * (1 - tf) * pf
            + c10 * tf * (1 - pf) + c11 * tf * pf)


def biome_rgb(elev, temp, precip, lat):
    """Realistic land color from climate (temp/precip) + elevation relief +
    latitude snow line; bathymetry for ocean. Arrays throughout."""
    elev = np.asarray(elev, dtype=np.float32)
    lat = np.asarray(lat, dtype=np.float32)
    temp = np.asarray(temp, dtype=np.float32)
    precip = np.asarray(precip, dtype=np.float32)
    # climate nodata on coastal land: fall back to a latitude temp proxy + mild precip
    temp = np.where(np.isnan(temp), 27.0 - np.abs(lat) * 0.65, temp)
    precip = np.where(np.isnan(precip), 700.0, precip)

    land = elev >= 0
    sea = _ramp(elev, _SEA)
    col = _bilerp_grid(temp, precip)

    # rock browning on high slopes
    rock = np.clip((elev - 2400.0) / 1600.0, 0, 1)[..., None]
    col = col * (1 - rock) + np.array([150, 134, 118], np.float32) * rock
    # Snow/ice from CLIMATE, not latitude: WorldClim temp already bakes in the
    # elevation lapse, so permanent ice = very cold annual temp (Greenland,
    # Antarctica), and alpine snow = above a temp-set snow line (peaks only).
    # This keeps cold-but-forested Siberia/Canada as taiga, not ice.
    ice_f = np.clip((-14.0 - temp) / 6.0, 0, 1)
    snowline = 3200.0 + temp * 70.0
    snow_f = np.clip((elev - snowline) / 500.0, 0, 1)
    white_f = np.maximum(ice_f, snow_f)[..., None]
    col = col * (1 - white_f) + np.array([248, 249, 252], np.float32) * white_f

    # Sea ice: no SST/sea-ice dataset yet, so a latitude proxy — but ramp it
    # (68->80) instead of a hard cut, or the constant-latitude circles on the
    # polar faces render as a crisp artificial disk.
    sea_ice = np.clip((np.abs(lat) - 68.0) / 12.0, 0, 1)[..., None]
    sea = sea * (1 - sea_ice) + np.array([228, 236, 244], np.float32) * sea_ice
    out = np.where(land[..., None], col, sea)
    return out.astype(np.uint8)


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
    # polar sea ice, ramped (68->80) so polar faces don't show a hard disk
    sea_ice = np.clip((np.abs(lat) - 68.0) / 12.0, 0, 1)[..., None]
    seaice_col = np.array([228, 236, 244], dtype=np.float32)
    col = np.where(land[..., None], col, col * (1 - sea_ice) + seaice_col * sea_ice)
    return col.astype(np.uint8)
