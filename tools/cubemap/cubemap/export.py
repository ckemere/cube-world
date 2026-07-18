"""Export the ingested Earth rasters into a compact binary the Java plugin
loads (CWE1 format). The plugin samples these through the same CubeSurface
embedding + roll to drive terrain height and biome climate.

CWE1 layout (little-endian):
  magic   4s   b"CWE1"
  roll    f32  orientation roll (degrees) baked into the export note; the
               plugin applies the rotation itself, this is just provenance
  nlayers i32
  per layer header (repeated nlayers times):
    name  8s   ascii, null-padded ("height","temp","precip")
    w     i32
    h     i32
    scale f32  value = raw*scale + offset
    offset f32
  then nlayers int16 data blocks in header order, row-major, north-up,
  lon -180..180 across. raw == -32768 is nodata.
"""
from __future__ import annotations
import struct
import numpy as np

NODATA = -32768


def _encode(a, scale):
    r = np.round(np.asarray(a, dtype=np.float64) / scale)
    r = np.where(np.isnan(a), NODATA, r)
    r = np.clip(r, -32767, 32767)
    return r.astype("<i2")


def export_earth(out_path, etopo, temp_raster, precip_raster, roll,
                 height_step=2):
    """height_step downsamples ETOPO (native 1'): 2 -> 2 arc-min (~3.7 km)."""
    height = etopo.grid[::height_step, ::height_step]
    layers = [
        ("height", _encode(height, 1.0), 1.0, 0.0),        # metres
        ("temp", _encode(temp_raster.grid, 0.1), 0.1, 0.0),  # deg C (raw = C*10)
        ("precip", _encode(precip_raster.grid, 1.0), 1.0, 0.0),  # mm
    ]
    with open(out_path, "wb") as f:
        f.write(b"CWE1")
        f.write(struct.pack("<f", float(roll)))
        f.write(struct.pack("<i", len(layers)))
        for name, arr, scale, offset in layers:
            h, w = arr.shape
            f.write(name.encode("ascii")[:8].ljust(8, b"\0"))
            f.write(struct.pack("<iiff", w, h, scale, offset))
        for _, arr, _, _ in layers:
            arr.tofile(f)
    total = sum(arr.nbytes for _, arr, _, _ in layers)
    return {"layers": [(n, a.shape) for n, a, _, _ in layers], "bytes": total}
