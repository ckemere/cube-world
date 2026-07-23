"""A small, fully portable value-noise (integer-hash grid + smoothstep + fbm).
Deliberately simple integer math (32-bit masked) so the identical function can
run in the Java plugin and in browser JS — the seed-based local variation must
match between the generated world and the map. Vectorised over numpy arrays."""

import numpy as np

_M = 0xFFFFFFFF


def _hash01(ix, iz, seed):
    h = (ix * 374761393 + iz * 668265263 + seed * 2654435761) & _M
    h = (h ^ (h >> 13)) & _M
    h = (h * 1274126177) & _M
    h = (h ^ (h >> 16)) & _M
    return (h & 0xFFFF).astype(np.float64) / 65536.0


def vnoise(x, z, seed, wavelength):
    fx = np.asarray(x, dtype=np.float64) / wavelength
    fz = np.asarray(z, dtype=np.float64) / wavelength
    ix = np.floor(fx).astype(np.int64)
    iz = np.floor(fz).astype(np.int64)
    tx = fx - ix
    tz = fz - iz
    sx = tx * tx * (3 - 2 * tx)
    sz = tz * tz * (3 - 2 * tz)
    v00 = _hash01(ix, iz, seed)
    v10 = _hash01(ix + 1, iz, seed)
    v01 = _hash01(ix, iz + 1, seed)
    v11 = _hash01(ix + 1, iz + 1, seed)
    return (v00 * (1 - sx) + v10 * sx) * (1 - sz) + (v01 * (1 - sx) + v11 * sx) * sz


def fbm(x, z, seed, wavelength, octaves=3):
    """Fractal value noise in ~[-1, 1]."""
    total = np.zeros(np.broadcast(x, z).shape, dtype=np.float64)
    amp, norm, wl = 1.0, 0.0, float(wavelength)
    for o in range(octaves):
        total += amp * (vnoise(x, z, (seed + o * 1013) & _M, wl) * 2.0 - 1.0)
        norm += amp
        amp *= 0.5
        wl *= 0.5
    return total / norm
