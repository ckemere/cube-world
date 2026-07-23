"""The portable biome generator: strong Earth constraint (from the blob) + local
variation (seed-based noise), mapped through the vanilla partition.

    biome(x,z) = partition( EarthClimate(blob) + seed_noise )

Noise is added to humidity/temperature (so e.g. the Sahara gets savanna patches)
and drives weirdness (biome variants), at a few-hundred-block wavelength. The
Earth fields dominate the large scale; the noise breaks up the monotony."""

import numpy as np

from . import earthclimate as ec
from . import noise as nz
from .blobreader import Blob, FACES
from .partition import Partition

# overworld cube geometry (blocks)
FACE = 10240
H = FACE / 2
GRID = {"NORTH_POLE": (0, -1), "EQ_PRIME": (0, 0), "EQ_EAST": (1, 0),
        "EQ_BACK": (2, 0), "EQ_WEST": (-1, 0), "SOUTH_POLE": (0, 1)}

# --- noise blend (tunable): strong Earth, moderate local variation ---
WAVELENGTH = 340         # feature scale in blocks
OCTAVES = 3
AMP_HUM = 0.34           # humidity wobble -> desert<->savanna<->forest patches
AMP_TEMP_C = 3.2         # temperature wobble (deg C, before the curve)
AMP_CONT = 0.05          # small continentalness wobble
AMP_EROS = 0.16          # erosion wobble -> hills/windswept patches
AMP_WEIRD = 0.62         # weirdness from noise -> vanilla variant selection


def face_world_xz(face, u, v):
    gc, gr = GRID[face]
    return gc * FACE + u * H, gr * FACE + v * H


def biome_grid(blob, partition, face, n, seed, blend=True):
    """(n,n) array of biome ids for a face at u,v resolution n."""
    t = np.linspace(-1.0, 1.0, n)
    u, v = np.meshgrid(t, t)                       # u=cols, v=rows (blob orientation)
    x, z = face_world_xz(face, u, v)
    elev = blob.sample("elevation", face, u, v)
    temp = blob.sample("temperature", face, u, v)
    precip = blob.sample("precip", face, u, v)
    rugged = blob.relief_at(face, u, v)
    land = elev >= 0

    if blend:
        nh = nz.fbm(x, z, seed + 11, WAVELENGTH, OCTAVES) * AMP_HUM
        nt = nz.fbm(x, z, seed + 23, WAVELENGTH, OCTAVES) * AMP_TEMP_C
        nc = nz.fbm(x, z, seed + 41, WAVELENGTH, OCTAVES) * AMP_CONT
        ne = nz.fbm(x, z, seed + 57, WAVELENGTH, OCTAVES) * AMP_EROS
        # weirdness picks vanilla variants; keep |w| away from 0 so we don't
        # scatter river/valley biomes everywhere (rivers come from the vectors).
        raw = nz.fbm(x, z, seed + 83, WAVELENGTH * 1.7, OCTAVES)
        weird = np.sign(raw) * (0.10 + AMP_WEIRD * np.abs(raw))
    else:
        nh = nt = nc = ne = 0.0
        weird = np.zeros_like(elev)

    hum = np.clip(ec.humidity(precip) + nh, -1, 1)
    hum = ec.boreal_moisture_floor(hum, temp + nt, land)
    T = ec.temperature(temp + nt, hum, land)
    C = np.clip(ec.continentalness(elev) + nc, -1, 1)
    E = np.clip(ec.erosion(rugged) + ne, -1, 1)
    D = np.zeros_like(elev)                        # surface map

    targets = np.stack([T, hum, C, E, D, weird], axis=-1).reshape(-1, 6)
    idx = partition.index_batch(targets).reshape(n, n)
    return idx, partition
