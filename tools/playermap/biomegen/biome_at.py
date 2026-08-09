"""Pure-Python port of the plugin's overworld biome function.

``biome_at(seed, x, y, z)`` reproduces
``CubeWorldBiomeProvider.getBiome(worldInfo, x, y, z)`` without a running
server: it needs only ``run/earth.dat``, ``run/coast.dat``,
``src/main/resources/peaks6000.csv`` and vanilla's dumped climate parameter
table (``run/plugins/CubeWorld/biomes/overworld_params.json``).

Java sources this is a line-by-line port of (all under
``src/main/java/com/ckemere/cubeworld/``):

===========================================================  ================
Java                                                          here
===========================================================  ================
generation/CubeWorldBiomeProvider.getBiome        (L49-67)    biome_at
generation/CubeWorldBiomeProvider.earthBiome      (L115-169)  _earth_biome
generation/CubeWorldBiomeProvider.oceanBiome      (L173-189)  _ocean_biome
generation/CubeWorldBiomeProvider.classifyRiver   (L201-209)  _classify_river
generation/CubeWorldBiomeProvider.surfaceBiome    (L78-82)    surface_biome_at
generation/EarthClimate.params                    (L587-813)  EarthClimate.params
generation/EarthClimate.* (curves/knots)                      EarthClimate
generation/EarthData                              (whole)     EarthData
generation/MapSampler                             (whole)     MapSampler
generation/EarthMapSpec.elevationToBlockY         (L136-175)  elevation_to_block_y
generation/ClimateNoise                           (whole)     _fbm
generation/PeakField                              (whole)     PeakField
generation/VanillaBiomeMapper.biome               (L43-49)    VanillaBiomeMapper
geometry/CubeGeometry, CubeSurface, CubeTopology,
geometry/EdgeTransform                                        CubeGeometry etc.
===========================================================  ================

Seed dependence
---------------
The *terrain and climate rasters are seed independent* (``EarthMapSpec`` reads
Earth data only; ``MapService.mapFor`` passes the seed to the demo spec, which
is unused when earth.dat is present).  The seed enters in exactly one place --
``EarthClimate.params`` L656-661, the five ``ClimateNoise.fbm`` fields that
wobble humidity / temperature / continentalness / erosion / weirdness -- via
``ns = (int) seed``.  So ``biome_at(other_seed, ...)`` is meaningful for any
64-bit seed without that world ever having been generated; only the low 32 bits
of the seed matter.
"""

from __future__ import annotations

import json
import math
import os
import struct
from functools import lru_cache

import numpy as np

# --------------------------------------------------------------------------
# paths
# --------------------------------------------------------------------------

_HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(_HERE, "..", "..", ".."))

EARTH_DAT = os.path.join(REPO, "run", "earth.dat")
COAST_DAT = os.path.join(REPO, "run", "coast.dat")
PEAKS_CSV = os.path.join(REPO, "src", "main", "resources", "peaks6000.csv")
PARAMS_JSON = os.path.join(
    REPO, "run", "plugins", "CubeWorld", "biomes", "overworld_params.json")

NAN = float("nan")

# --------------------------------------------------------------------------
# CubeWorldPlugin constants (CubeWorldPlugin.java L33, L46)
# --------------------------------------------------------------------------

FACE_SIZE = 640 * 16          # 10240
MARGIN_BLOCKS = 6 * 16        # 96
SEA_LEVEL = 62                # SphericalDemoSpec.SEA_LEVEL


# ==========================================================================
# geometry/CubeGeometry.java, CubeFace.java
# ==========================================================================

# CubeFace: name -> (gridCol, gridRow)
FACES = {
    "NORTH_POLE": (0, -1),
    "EQ_PRIME": (0, 0),
    "EQ_EAST": (1, 0),
    "EQ_BACK": (2, 0),
    "EQ_WEST": (-1, 0),
    "SOUTH_POLE": (0, 1),
}
_FACE_ORDER = list(FACES)                       # CubeFace.values() order
_BY_GRID = {v: k for k, v in FACES.items()}


class CubeGeometry:
    """geometry/CubeGeometry.java"""

    def __init__(self, face_size=FACE_SIZE):
        self.face_size = face_size

    def face_at(self, x, z):
        """CubeGeometry.faceAt(int, int) -- x/z are BLOCK ints."""
        col = (x + self.face_size // 2) // self.face_size
        row = (z + self.face_size // 2) // self.face_size
        return _BY_GRID.get((col, row))

    def face_min_x(self, face):
        return FACES[face][0] * self.face_size - self.face_size // 2

    def face_min_z(self, face):
        return FACES[face][1] * self.face_size - self.face_size // 2


# ==========================================================================
# geometry/CubeSurface.java
# ==========================================================================

def cube_surface_point(geom, face, world_x, world_z):
    """CubeSurface.point(face, worldX, worldZ) -> (x, y, z) on the unit cube."""
    h = geom.face_size / 2.0
    lx = (world_x - geom.face_min_x(face)) / h - 1.0
    lz = (world_z - geom.face_min_z(face)) / h - 1.0
    if face == "NORTH_POLE":
        return (lx, 1.0, lz)
    if face == "EQ_PRIME":
        return (lx, -lz, 1.0)
    if face == "SOUTH_POLE":
        return (lx, -1.0, -lz)
    if face == "EQ_EAST":
        return (1.0, -lz, -lx)
    if face == "EQ_BACK":
        return (-lx, -lz, -1.0)
    if face == "EQ_WEST":
        return (-1.0, -lz, lx)
    raise ValueError(face)


# ==========================================================================
# geometry/EdgeTransform.java + CubeTopology.java
# ==========================================================================

def _signum(v):
    return 0.0 if v == 0 else (1.0 if v > 0 else -1.0)


class EdgeTransform:
    """geometry/EdgeTransform.java"""

    def __init__(self, quarter_turns, ax, az, bx, bz):
        self.k = quarter_turns % 4
        self.ax, self.az, self.bx, self.bz = ax, az, bx, bz

    def apply_vector(self, dx, dz):
        x, z = dx, dz
        for _ in range(self.k):
            x, z = z, -x
        return x, z

    def apply_point(self, px, pz):
        rx, rz = self.apply_vector(px - self.ax, pz - self.az)
        return rx + self.bx, rz + self.bz

    def inverse(self):
        return EdgeTransform(4 - self.k, self.bx, self.bz, self.ax, self.az)


def _direction_index(dx, dz):
    """CubeTopology.directionIndex"""
    if dx > 0 and dz == 0:
        return 0
    if dx == 0 and dz < 0:
        return 1
    if dx < 0 and dz == 0:
        return 2
    if dx == 0 and dz > 0:
        return 3
    raise ValueError("segment is not axis-aligned")


class CubeTopology:
    """geometry/CubeTopology.java -- only the parts biome selection needs."""

    def __init__(self, geometry):
        self.geometry = geometry
        h = geometry.face_size / 2.0
        raw = [
            # faceA, a0, a1, faceB, b0, b1   (CubeTopology constructor L45-58)
            ("NORTH_POLE", (-h, -3 * h), (h, -3 * h), "EQ_BACK", (5 * h, -h), (3 * h, -h)),
            ("NORTH_POLE", (h, -3 * h), (h, -h), "EQ_EAST", (3 * h, -h), (h, -h)),
            ("NORTH_POLE", (-h, -3 * h), (-h, -h), "EQ_WEST", (-3 * h, -h), (-h, -h)),
            ("SOUTH_POLE", (h, 3 * h), (-h, 3 * h), "EQ_BACK", (3 * h, h), (5 * h, h)),
            ("SOUTH_POLE", (h, h), (h, 3 * h), "EQ_EAST", (h, h), (3 * h, h)),
            ("SOUTH_POLE", (-h, h), (-h, 3 * h), "EQ_WEST", (-h, h), (-3 * h, h)),
            ("EQ_BACK", (5 * h, -h), (5 * h, h), "EQ_WEST", (-3 * h, -h), (-3 * h, h)),
        ]
        self.links = []
        for fa, a0, a1, fb, b0, b1 in raw:
            ia = _direction_index(a1[0] - a0[0], a1[1] - a0[1])
            ib = _direction_index(b1[0] - b0[0], b1[1] - b0[1])
            turns = (ib - ia) % 4
            t = EdgeTransform(turns, a0[0], a0[1], b0[0], b0[1])
            self.links.append((fa, a0, a1, fb, b0, b1, t))
        # CubeTopology.directedEdges(): (face, s0, s1, outward) for A then B
        self.directed = []
        for fa, a0, a1, fb, b0, b1, t in self.links:
            self.directed.append((fa, a0, a1, t))
            self.directed.append((fb, b0, b1, t.inverse()))

    def _outward_distance(self, d, x, z):
        """CubeTopology.outwardDistance -- NaN when off the segment's span."""
        face, s0, s1, _ = d
        length = self.geometry.face_size
        ux = _signum(s1[0] - s0[0])
        uz = _signum(s1[1] - s0[1])
        t = (x - s0[0]) * ux + (z - s0[1]) * uz
        if t < 0 or t > length:
            return NAN
        cx = self.geometry.face_min_x(face) + length / 2.0
        cz = self.geometry.face_min_z(face) + length / 2.0
        if ux == 0:
            nx, nz = _signum(s0[0] - cx), 0.0
        else:
            nx, nz = 0.0, _signum(s0[1] - cz)
        return (x - s0[0]) * nx + (z - s0[1]) * nz

    def margin_source(self, x, z, margin):
        """CubeTopology.marginSource -> (source_x, source_z) or None."""
        for d in self.directed:
            out = self._outward_distance(d, x, z)
            if not math.isnan(out) and 0 < out <= margin:
                return d[3].apply_point(x, z)
        return None


# ==========================================================================
# generation/EarthData.java
# ==========================================================================

NODATA = -32768


class _Layer:
    __slots__ = ("data", "width", "height", "scale", "offset")

    def __init__(self, data, width, height, scale, offset):
        self.data = data
        self.width = width
        self.height = height
        self.scale = scale
        self.offset = offset


class EarthData:
    """generation/EarthData.java -- CWE1 reader + bilinear sampler.

    Layers are numpy memmaps, so loading earth.dat costs no resident memory.
    """

    def __init__(self, path):
        self.layers = {}
        self.roll = self._read(path)
        self.cos_roll = math.cos(math.radians(self.roll))
        self.sin_roll = math.sin(math.radians(self.roll))

    def _read(self, path):
        with open(path, "rb") as fh:
            head = fh.read(12)
            if head[:4] != b"CWE1":
                raise IOError("not a CWE1 file: %s" % path)
            roll = float(struct.unpack_from("<f", head, 4)[0])
            n = struct.unpack_from("<i", head, 8)[0]
            hdr = fh.read(n * 24)
        names, dims = [], []
        off = 0
        for _ in range(n):
            nm = hdr[off:off + 8].split(b"\0")[0].decode("ascii")
            off += 8
            w, h = struct.unpack_from("<ii", hdr, off)
            off += 8
            # scale/offset are float32 in the file and read with getFloat();
            # keep the float32 value exactly (0.1f != 0.1).
            s, o = struct.unpack_from("<ff", hdr, off)
            off += 8
            names.append(nm)
            dims.append((w, h, float(s), float(o)))
        base = 12 + n * 24
        for nm, (w, h, s, o) in zip(names, dims):
            arr = np.memmap(path, dtype="<i2", mode="r", offset=base, shape=(h, w))
            self.layers[nm] = _Layer(arr, w, h, s, o)
            base += w * h * 2
        return roll

    def merge(self, path):
        """EarthData.merge -- putIfAbsent semantics."""
        other = EarthData(path)
        for k, v in other.layers.items():
            self.layers.setdefault(k, v)

    def has_layer(self, name):
        return name in self.layers

    def to_lon_lat(self, p):
        """EarthData.toLonLat(Vec3)."""
        x, y, z = p
        n = math.sqrt(x * x + y * y + z * z)
        x, y, z = x / n, y / n, z / n
        gx = self.cos_roll * x + self.sin_roll * z
        gz = -self.sin_roll * x + self.cos_roll * z
        lat = math.degrees(math.asin(max(-1.0, min(1.0, y))))
        lon = math.degrees(math.atan2(gx, gz))
        return lon, lat

    def sample(self, layer, lon, lat):
        """EarthData.sample -- bilinear with nearest-valid nodata fallback."""
        l = self.layers.get(layer)
        if l is None:
            return NAN
        fx = (lon + 180.0) / 360.0 * l.width
        fy = (90.0 - lat) / 180.0 * l.height
        if fx < 0:
            fx += l.width
        x0 = math.floor(fx)
        y0 = math.floor(fy)
        tx = fx - x0
        ty = fy - y0
        x0 = x0 % l.width
        x1 = (x0 + 1) % l.width
        y0 = max(0, min(l.height - 1, y0))
        y1 = min(l.height - 1, y0 + 1)
        d = l.data
        r00 = int(d[y0, x0])
        r10 = int(d[y0, x1])
        r01 = int(d[y1, x0])
        r11 = int(d[y1, x1])
        v00 = NAN if r00 == NODATA else float(r00)
        v10 = NAN if r10 == NODATA else float(r10)
        v01 = NAN if r01 == NODATA else float(r01)
        v11 = NAN if r11 == NODATA else float(r11)
        if math.isnan(v00) or math.isnan(v10) or math.isnan(v01) or math.isnan(v11):
            best = NAN
            for v in (v00, v10, v01, v11):
                if not math.isnan(v):
                    best = v
                    break
            return best * l.scale + l.offset
        return (v00 * (1 - tx) * (1 - ty) + v10 * tx * (1 - ty)
                + v01 * (1 - tx) * ty + v11 * tx * ty) * l.scale + l.offset


# ==========================================================================
# generation/PeakField.java
# ==========================================================================

class PeakField:
    """generation/PeakField.java"""

    RADIUS_KM = 28.0
    EARTH_R_KM = 6371.0

    def __init__(self, path=PEAKS_CSV):
        self.bins = {}
        try:
            with open(path, "r", encoding="utf-8") as fh:
                fh.readline()                       # header
                for line in fh:
                    f = line.rstrip("\n").split(",", 3)
                    if len(f) < 3:
                        continue
                    try:
                        lat = float(f[0])
                        lon = float(f[1])
                        ele = float(f[2])
                    except ValueError:
                        continue
                    self.bins.setdefault(
                        (math.floor(lon), math.floor(lat)), []).append((lon, lat, ele))
        except OSError:
            pass

    def cone_elevation(self, lon, lat):
        lb = math.floor(lon)
        tb = math.floor(lat)
        best = 0.0
        for dl in (-1, 0, 1):
            for dt in (-1, 0, 1):
                lst = self.bins.get((lb + dl, tb + dt))
                if not lst:
                    continue
                for plon, plat, pele in lst:
                    d = self._haversine_km(lon, lat, plon, plat)
                    if d < self.RADIUS_KM:
                        cone = pele * (1.0 - d / self.RADIUS_KM)
                        if cone > best:
                            best = cone
        return best

    @classmethod
    def _haversine_km(cls, lon1, lat1, lon2, lat2):
        p1 = math.radians(lat1)
        p2 = math.radians(lat2)
        dp = math.radians(lat2 - lat1)
        dl = math.radians(lon2 - lon1)
        a = (math.sin(dp / 2) ** 2
             + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2)
        return 2 * cls.EARTH_R_KM * math.asin(min(1.0, math.sqrt(a)))


# ==========================================================================
# generation/ClimateNoise.java
# ==========================================================================

_M32 = 0xFFFFFFFF


def _hash01(ix, iz, seed):
    h = (ix * 374761393 + iz * 668265263 + seed * 0x9E3779B1) & _M32
    h = (h ^ (h >> 13)) & _M32
    h = (h * 1274126177) & _M32
    h = (h ^ (h >> 16)) & _M32
    return (h & 0xFFFF) / 65536.0


def _vnoise(x, z, seed, wavelength):
    fx = x / wavelength
    fz = z / wavelength
    ix = math.floor(fx)
    iz = math.floor(fz)
    tx = fx - ix
    tz = fz - iz
    sx = tx * tx * (3 - 2 * tx)
    sz = tz * tz * (3 - 2 * tz)
    v00 = _hash01(ix, iz, seed)
    v10 = _hash01(ix + 1, iz, seed)
    v01 = _hash01(ix, iz + 1, seed)
    v11 = _hash01(ix + 1, iz + 1, seed)
    return (v00 * (1 - sx) + v10 * sx) * (1 - sz) + (v01 * (1 - sx) + v11 * sx) * sz


def _fbm(x, z, seed, wavelength, octaves):
    """ClimateNoise.fbm -- 32-bit integer hash, matches the Java bit for bit."""
    total = 0.0
    amp = 1.0
    norm = 0.0
    wl = wavelength
    for o in range(octaves):
        total += amp * (_vnoise(x, z, (seed + o * 1013) & _M32, wl) * 2.0 - 1.0)
        norm += amp
        amp *= 0.5
        wl *= 0.5
    return total / norm


# ==========================================================================
# generation/EarthMapSpec.java  (heights)
# ==========================================================================

LAND_EXAGGERATION = 0.0285
HIGH_BREAK = 4000.0
HIGH_EXAGGERATION = 0.0146
OCEAN_SHELF = 0.020
OCEAN_BREAK = 1500.0
OCEAN_DEEP = 0.004
OCEAN_FLOOR = 60.0
LAND_CAP = 253.0
LAND_FREEBOARD_MIN = 2.0        # -Dcubeworld.landFreeboard default
TERRAIN_CEIL = 250.0
LOW_M = [0, 100, 300, 800, 1500, 2500, 4000]
LOW_B = [0, 0.5, 1.8, 6.0, 15.0, 45.0, 114.0]


def _spec_interp(x, xs, ys):
    """EarthMapSpec.interp (L103-114). NaN falls through to ys[-1], as in Java."""
    if x <= xs[0]:
        return ys[0]
    for i in range(1, len(xs)):
        if x <= xs[i]:
            t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
            return ys[i - 1] + t * (ys[i] - ys[i - 1])
    return ys[-1]


def elevation_to_block_y(meters):
    """EarthMapSpec.elevationToBlockY (L136-175)."""
    if meters >= 0:
        if meters <= HIGH_BREAK:
            blocks = _spec_interp(meters, LOW_M, LOW_B)
        else:
            blocks = (HIGH_BREAK * LAND_EXAGGERATION
                      + (meters - HIGH_BREAK) * HIGH_EXAGGERATION)
        y = SEA_LEVEL + max(LAND_FREEBOARD_MIN, min(blocks, LAND_CAP))
    else:
        depth = -meters
        if depth <= OCEAN_BREAK:
            blocks = depth * OCEAN_SHELF
        else:
            blocks = OCEAN_BREAK * OCEAN_SHELF + (depth - OCEAN_BREAK) * OCEAN_DEEP
        y = SEA_LEVEL - min(blocks, OCEAN_FLOOR)
    return max(-60.0, min(TERRAIN_CEIL, y))


# ==========================================================================
# generation/MapSampler.java  (+ EarthMapSpec.heightAt)
# ==========================================================================

class MapSampler:
    """generation/MapSampler.java over an EarthMapSpec."""

    def __init__(self, topology, earth):
        self.topology = topology
        self.geometry = topology.geometry
        self.earth = earth
        self.cells = self.geometry.face_size // 16
        self._cell_cache = {}

    # --- EarthMapSpec ---------------------------------------------------
    def _cell_lon_lat(self, face, cell_x, cell_z):
        wx = self.geometry.face_min_x(face) + cell_x * 16 + 8
        wz = self.geometry.face_min_z(face) + cell_z * 16 + 8
        return self.earth.to_lon_lat(cube_surface_point(self.geometry, face, wx, wz))

    def spec_height_at(self, face, cell_x, cell_z):
        """EarthMapSpec.heightAt(face, cellX, cellZ)."""
        key = (face, cell_x, cell_z)
        v = self._cell_cache.get(key)
        if v is None:
            lon, lat = self._cell_lon_lat(face, cell_x, cell_z)
            v = elevation_to_block_y(self.earth.sample("height", lon, lat))
            if len(self._cell_cache) > 400000:
                self._cell_cache.clear()
            self._cell_cache[key] = v
        return v

    # --- MapSampler -----------------------------------------------------
    def resolve(self, world_x, world_z):
        """MapSampler.resolve -> (face, x, z) or None."""
        face = self.geometry.face_at(math.floor(world_x), math.floor(world_z))
        if face is not None:
            return face, world_x, world_z
        src = self.topology.margin_source(world_x, world_z, 16 * 8)
        if src is None:
            return None
        sx, sz = src
        sf = self.geometry.face_at(math.floor(sx), math.floor(sz))
        return None if sf is None else (sf, sx, sz)

    def cube_point_at(self, world_x, world_z):
        r = self.resolve(world_x, world_z)
        if r is None:
            return None
        return cube_surface_point(self.geometry, r[0], r[1], r[2])

    def _clamp_cell(self, idx):
        return max(0, min(self.cells - 1, idx))

    def _cell_height(self, face, cell_x, cell_z):
        """MapSampler.cellHeight (L112-138)."""
        if 0 <= cell_x < self.cells and 0 <= cell_z < self.cells:
            return self.spec_height_at(face, cell_x, cell_z)
        g = self.geometry
        center_x = g.face_min_x(face) + cell_x * 16 + 8
        center_z = g.face_min_z(face) + cell_z * 16 + 8
        direct = g.face_at(math.floor(center_x), math.floor(center_z))
        if direct is not None:
            return self.spec_height_at(
                direct,
                self._clamp_cell(math.floor((center_x - g.face_min_x(direct)) / 16.0)),
                self._clamp_cell(math.floor((center_z - g.face_min_z(direct)) / 16.0)))
        src = self.topology.margin_source(center_x, center_z, 32)
        if src is not None:
            mx, mz = src
            partner = g.face_at(math.floor(mx), math.floor(mz))
            if partner is not None:
                return self.spec_height_at(
                    partner,
                    self._clamp_cell(math.floor((mx - g.face_min_x(partner)) / 16.0)),
                    self._clamp_cell(math.floor((mz - g.face_min_z(partner)) / 16.0)))
        return self.spec_height_at(face, self._clamp_cell(cell_x), self._clamp_cell(cell_z))

    def height_at(self, world_x, world_z):
        """MapSampler.heightAt (L74-95)."""
        r = self.resolve(world_x, world_z)
        if r is None:
            return float(SEA_LEVEL)
        face, wx, wz = r
        g = self.geometry
        fx = (wx - g.face_min_x(face) - 8.0) / 16.0
        fz = (wz - g.face_min_z(face) - 8.0) / 16.0
        ix = math.floor(fx)
        iz = math.floor(fz)
        tx = fx - ix
        tz = fz - iz
        h00 = self._cell_height(face, ix, iz)
        h10 = self._cell_height(face, ix + 1, iz)
        h01 = self._cell_height(face, ix, iz + 1)
        h11 = self._cell_height(face, ix + 1, iz + 1)
        return ((1 - tx) * (1 - tz) * h00 + tx * (1 - tz) * h10
                + (1 - tx) * tz * h01 + tx * tz * h11)


# ==========================================================================
# generation/EarthClimate.java
# ==========================================================================

def _clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


def _interp(x, xs, ys):
    """EarthClimate.interp (L926-937). NaN falls through to ys[last], as in Java."""
    if x <= xs[0]:
        return ys[0]
    for i in range(len(xs) - 1):
        if x < xs[i + 1]:
            t = (x - xs[i]) / (xs[i + 1] - xs[i])
            return ys[i] + (ys[i + 1] - ys[i]) * t
    return ys[-1]


class EarthClimate:
    """generation/EarthClimate.java -- constants keep their Java line numbers."""

    TE = [-25, -8, 0, 8, 18, 24, 30, 40]                       # L50
    TT = [-1.0, -0.38, -0.22, -0.05, 0.18, 0.45, 0.70, 1.0]    # L159
    SE = [-2.0, 0.0, 9.0, 18.0, 25.0, 31.0]                    # L69
    SA = [-1.0, -0.45, -0.15, 0.20, 0.55, 1.00]                # L70
    BOREAL_MIN_PRECIP = 250.0                                  # L81
    COAST_BAND_TOP = -0.11                                     # L85
    BEACH_ROW_MAX = 0.50                                       # L89
    RIVER_CLEARINGS = True                                     # L92 (default)
    CLEARING_MAX_C = 18.0                                      # L97
    CLEARING_H = -0.25                                         # L103
    CLEARING_H_MAX = 0.30                                      # L109
    CLEARING_DEG = 0.45                                        # L116
    SST = True                                                 # L153 (default)
    CE = [-6000, -1000, -200, 0, 200, 800, 2000, 4000, 8000]   # L185
    CC = [-1.0, -0.6, -0.3, -0.08, 0.0, 0.2, 0.45, 0.75, 1.0]  # L186
    DK = [0, 40, 60, 200, 600, 1400, 2600]                     # L211
    DC = [-0.19, -0.09, 0.06, 0.22, 0.42, 0.70, 1.00]          # L212
    RM = [0, 2.18, 7.75, 15.07, 42.40, 107.84, 251.51,         # L294
          491.32, 709.20, 1306.20, 3000]
    RE = [1.00, 0.70, 0.45, 0.33, 0.15, -0.06, -0.27,          # L296
          -0.46, -0.56, -0.76, -1.00]
    WETLAND_EROSION = 0.68                                     # L411
    EROSION_DRY_CAP = 0.38                                     # L454
    ANTARCTIC_LAT = -62.0                                      # L458
    ANTARCTIC_COOL_PER_DEG = 0.6                               # L463
    CHERRY_MAX_EROSION = 0.10                                  # L468
    CHERRY_NATIVE_ONLY = True                                  # L473 (default)
    CHERRY_LON_MIN, CHERRY_LON_MAX = 95.0, 150.0               # L479-480
    CHERRY_LAT_MIN, CHERRY_LAT_MAX = 25.0, 50.0                # L481-482
    WE = [0, 300, 1500, 3000, 6000]                            # L490
    WM = [0.20, 0.28, 0.42, 0.58, 0.70]                        # L491
    DEEP_PLATEAU = 1.25                                        # L514
    DEPTH_MODE = "proportional"                                # L556 (default)
    WORLD_FLOOR = -64.0                                        # L560
    WMAG_IN = [0.00, 0.05, 0.25, 0.41, 0.50, 0.58, 0.67, 1.00]   # L830
    WMAG_OUT = [0.05, 0.10, 0.25, 0.46, 0.59, 0.72, 0.86, 1.00]  # L831
    NOISE_WL = 340.0                                           # L838
    NOISE_OCT = 3                                              # L839
    AMP_HUM = 0.34                                             # L840
    AMP_TEMP_C = 3.2                                           # L841
    AMP_CONT = 0.05                                            # L842
    AMP_EROS = 0.16                                            # L843
    RIVER_THRESHOLD = 0.7                                      # L886

    def __init__(self, earth, peaks):
        self.earth = earth
        self.peaks = peaks

    # --- small curves ---------------------------------------------------
    def peak_cone(self, lon, lat):                             # L898
        return self.peaks.cone_elevation(lon, lat)

    def sea_temperature(self, sst_c):                          # L156
        return _clamp(_interp(sst_c, self.SE, self.SA), -1, 1)

    def temperature(self, temp_c, humidity_param, land):       # L161
        base = _interp(temp_c, self.TE, self.TT)
        if land and base > 0.55 and humidity_param >= -0.65:
            base = 0.35
        elif land and humidity_param < -0.6 and temp_c > 15:
            base = max(base, 0.72)
        return _clamp(base, -1, 1)

    @staticmethod
    def humidity(precip_mm):                                   # L179
        return _clamp((math.log10(max(precip_mm, 1.0)) - 2.85) / 0.7, -1, 1)

    def continentalness(self, elev_m):                         # L188
        return _interp(elev_m, self.CE, self.CC)

    def continentalness_from_coast(self, coast_km, elev_m):    # L221
        c = _interp(coast_km, self.DK, self.DC)
        lift = _clamp(elev_m / 6000.0, 0.0, 1.0) * 0.25
        c += lift
        if elev_m > 50.0:
            t = _clamp((elev_m - 50.0) / 350.0, 0.0, 1.0)
            c += t * (max(c, -0.05) - c)
        return _clamp(c, -1, 1)

    def erosion(self, rugged_meters):                          # L331
        return _clamp(_interp(rugged_meters, self.RM, self.RE), -1, 1)

    @staticmethod
    def wetland(elev_m, rugged_meters, precip_mm, temp_c, coast_km):   # L371
        if elev_m < 0:
            return 0.0
        flat = _clamp(1.0 - rugged_meters / 100.0, 0.0, 1.0)
        low = _clamp(1.0 - elev_m / 150.0, 0.0, 1.0)
        pet = max(250.0, 300.0 + 45.0 * temp_c)
        wet = _clamp((precip_mm / pet - 0.70) / 0.60, 0.0, 1.0)
        s = flat * low * wet
        t = _clamp((s - 0.10) / (0.35 - 0.10), 0.0, 1.0)
        score = t * t * (3 - 2 * t)
        if score > 0 and temp_c > 10.0 and not math.isnan(coast_km):
            warm = _clamp((temp_c - 10.0) / 8.0, 0.0, 1.0)
            near = 1.0 - _clamp((coast_km - 120.0) / 280.0, 0.0, 1.0)
            score *= (1.0 - warm) + warm * near
        return score

    def continentalness_at(self, lon, lat, elev_m):            # L415
        if elev_m >= 0 and self.earth.has_layer("coast"):
            km = self.earth.sample("coast", lon, lat)
            if not math.isnan(km):
                return self.continentalness_from_coast(km, elev_m)
        return self.continentalness(elev_m)

    def erosion_at(self, lon, lat, elev_m, rugged_meters, precip_mm, temp_c):  # L427
        e = self.erosion(rugged_meters)
        coast_km = NAN
        if elev_m >= 0 and self.earth.has_layer("coast"):
            coast_km = self.earth.sample("coast", lon, lat)
        w = self.wetland(elev_m, rugged_meters, precip_mm, temp_c, coast_km)
        dry = min(e, self.EROSION_DRY_CAP)
        return dry + (self.WETLAND_EROSION - dry) * _clamp(w, 0.0, 1.0)

    def river_near(self, lon, lat):                            # L129
        earth = self.earth
        if earth is None or not earth.has_layer("river"):
            return 0.0
        best = earth.sample("river", lon, lat)
        if math.isnan(best):
            best = 0.0
        coslat = max(0.2, math.cos(math.radians(lat)))
        for ring in (1, 2):
            rad = self.CLEARING_DEG * ring / 2.0
            for i in range(8):
                a = i * (math.pi / 4.0)
                v = earth.sample("river", lon + math.cos(a) * rad / coslat,
                                 lat + math.sin(a) * rad)
                if not math.isnan(v) and v > best:
                    best = v
        return _clamp(best, 0.0, 1.0)

    def ruggedness(self, lon, lat, elev):                      # L910
        d = 0.08
        mx = 0.0
        for ox, oy in ((d, 0), (-d, 0), (0, d), (0, -d)):
            hh = self.earth.sample("height", lon + ox, lat + oy)
            if not math.isnan(hh):
                nc = self.peak_cone(lon + ox, lat + oy)
                if nc > 0:
                    hh = max(hh, nc)
                mx = max(mx, abs(hh - elev))
        return mx

    def weird_magnitude(self, abs_raw):                        # L833
        return _clamp(_interp(abs_raw, self.WMAG_IN, self.WMAG_OUT), 0.0, 1.0)

    def depth(self, surface_y, y):                             # L562
        if self.DEPTH_MODE == "blocks128":
            return _clamp((surface_y - y) / 128.0, -0.1, self.DEEP_PLATEAU)
        if self.DEPTH_MODE == "proportional":
            span = max(8.0, surface_y - self.WORLD_FLOOR)
            return _clamp(1.5 * (surface_y - y) / span, -0.1, 1.5)
        return _clamp((surface_y - y) / 70.0, -0.1, self.DEEP_PLATEAU)

    def river_strength(self, sampler, wx, wz):                 # L853
        earth = self.earth
        if earth is None or not earth.has_layer("river"):
            return 0.0
        p = sampler.cube_point_at(wx, wz)
        if p is None:
            return 0.0
        lon, lat = earth.to_lon_lat(p)
        r = earth.sample("river", lon, lat)
        return 0.0 if math.isnan(r) else r

    # --- the entry point ------------------------------------------------
    def params(self, sampler, wx, wz, y, seed):
        """EarthClimate.params (L587-813). Returns the 10-element array or None."""
        earth = self.earth
        p = sampler.cube_point_at(wx, wz)
        if p is None:
            return None
        lon, lat = earth.to_lon_lat(p)
        raster = earth.sample("height", lon, lat)
        cone = self.peak_cone(lon, lat)
        elev = max(raster, cone) if cone > 0 else raster
        temp = earth.sample("temp", lon, lat)
        precip = earth.sample("precip", lon, lat)
        land = elev >= 0
        sea = False
        if math.isnan(temp):
            if self.SST and not land:
                temp = earth.sample("sst", lon, lat)
                sea = not math.isnan(temp)
            if math.isnan(temp):
                temp = (27.0 - abs(lat) * 0.55 - 6.5 * max(0.0, elev) / 1000.0
                        if land else 27.0 - abs(lat) * 0.45)
        if math.isnan(precip):
            precip = _clamp(700.0 * math.exp((temp - 10.0) / 12.0), 40.0, 900.0)
        rugged = self.ruggedness(lon, lat, elev)
        surface_y = sampler.height_at(wx, wz)

        ns = _to_int32(seed)
        nh = _fbm(wx, wz, (ns + 11) & _M32, self.NOISE_WL, self.NOISE_OCT) * self.AMP_HUM
        nt = _fbm(wx, wz, (ns + 23) & _M32, self.NOISE_WL, self.NOISE_OCT) * self.AMP_TEMP_C
        nc = _fbm(wx, wz, (ns + 41) & _M32, self.NOISE_WL, self.NOISE_OCT) * self.AMP_CONT
        ne = _fbm(wx, wz, (ns + 57) & _M32, self.NOISE_WL, self.NOISE_OCT) * self.AMP_EROS
        raw = _fbm(wx, wz, (ns + 83) & _M32, self.NOISE_WL * 1.7, self.NOISE_OCT)

        mag_elev = _interp(elev, self.WE, self.WM)
        weird = _java_signum(raw) * max(mag_elev, self.weird_magnitude(abs(raw)))

        tc = temp + nt
        h = _clamp(self.humidity(precip) + nh, -1, 1)
        axis_t = _interp(tc, self.TE, self.TT)

        if land and lat < self.ANTARCTIC_LAT:                  # L704
            h = min(h, -0.35)
            tc -= (self.ANTARCTIC_LAT - lat) * self.ANTARCTIC_COOL_PER_DEG
            axis_t = _interp(tc, self.TE, self.TT)

        if (self.CHERRY_NATIVE_ONLY and weird > 0                # L722
                and not (self.CHERRY_LON_MIN <= lon <= self.CHERRY_LON_MAX
                         and self.CHERRY_LAT_MIN <= lat <= self.CHERRY_LAT_MAX)):
            if (-0.45 <= axis_t < 0.20 and h < -0.10
                    and self.erosion(rugged) < self.CHERRY_MAX_EROSION):
                weird = -weird

        if (self.RIVER_CLEARINGS and land and tc <= self.CLEARING_MAX_C     # L764
                and h > self.CLEARING_H and h < self.CLEARING_H_MAX):
            r = _clamp(self.river_near(lon, lat) * 1.8, 0.0, 1.0)
            if r > 0.0:
                h = h + r * (self.CLEARING_H - h)

        base_temp = _interp(tc, self.TE, self.TT)              # L776
        if (land and base_temp >= -0.45 and base_temp < -0.05
                and precip >= self.BOREAL_MIN_PRECIP):
            h = max(h, 0.12)

        cont = _clamp(self.continentalness_at(lon, lat, elev) + nc, -1, 1)
        t_param = self.sea_temperature(tc) if sea else self.temperature(tc, h, land)
        if land and cont <= self.COAST_BAND_TOP and t_param > self.BEACH_ROW_MAX:
            t_param = self.BEACH_ROW_MAX

        eros = _clamp(self.erosion_at(lon, lat, elev, rugged, precip, temp) + ne, -1, 1)
        return [t_param, h, cont, eros, self.depth(surface_y, y), weird,
                elev, temp, precip, rugged]


def _java_signum(v):
    if v != v:
        return v
    return 0.0 if v == 0 else (1.0 if v > 0 else -1.0)


def _to_int32(v):
    """Java's (int) cast of a long."""
    return ((int(v) & 0xFFFFFFFF) ^ 0x80000000) - 0x80000000


# ==========================================================================
# generation/VanillaBiomeMapper.java  +  net.minecraft...Climate
# ==========================================================================

def _trunc_div2(v):
    """Java's ``(a + b) / 2L`` -- integer division truncating toward zero."""
    return -((-v) >> 1) if v < 0 else v >> 1


class _Node:
    """Climate.RTree.Node: a 7-dim bounding box plus children (None = leaf)."""

    __slots__ = ("lo", "hi", "children", "index")

    def __init__(self, lo, hi, children, index):
        self.lo = lo          # tuple of 7
        self.hi = hi          # tuple of 7
        self.children = children
        self.index = index    # leaf: row in the parameter table

    def distance(self, target):
        """Climate.RTree.Node.distance (L430-438)."""
        total = 0
        lo, hi = self.lo, self.hi
        for i in range(7):
            a = target[i] - hi[i]
            b = lo[i] - target[i]
            d = a if a > 0 else (b if b > 0 else 0)
            total += d * d
        return total

    def search(self, target, candidate):
        """Climate.RTree.SubTree.search (L459-475) / Leaf.search (L411-417)."""
        if self.children is None:
            return self
        min_distance = None if candidate is None else candidate.distance(target)
        closest = candidate
        for child in self.children:
            cd = child.distance(target)
            if min_distance is None or min_distance > cd:
                leaf = child.search(target, closest)
                ld = cd if child is leaf else leaf.distance(target)
                if min_distance is None or min_distance > ld:
                    min_distance = ld
                    closest = leaf
        return closest


def _span(nodes):
    """Climate.RTree.buildParameterSpace (L378-393)."""
    lo = [min(n.lo[d] for n in nodes) for d in range(7)]
    hi = [max(n.hi[d] for n in nodes) for d in range(7)]
    return tuple(lo), tuple(hi)


def _subtree(children):
    lo, hi = _span(children)
    return _Node(lo, hi, list(children), None)


def _sort_key(dimension, absolute):
    def key(n):
        out = []
        for d in range(7):
            i = (dimension + d) % 7
            c = _trunc_div2(n.lo[i] + n.hi[i])
            out.append(abs(c) if absolute else c)
        return tuple(out)
    return key


def _bucketize(nodes):
    """Climate.RTree.bucketize (L342-361)."""
    expected = int(math.pow(6.0, math.floor(math.log(len(nodes) - 0.01) / math.log(6.0))))
    buckets = []
    cur = []
    for child in nodes:
        cur.append(child)
        if len(cur) >= expected:
            buckets.append(_subtree(cur))
            cur = []
    if cur:
        buckets.append(_subtree(cur))
    return buckets


def _cost(node):
    return sum(abs(node.hi[d] - node.lo[d]) for d in range(7))


def _build(children):
    """Climate.RTree.build (L280-322). Java's List.sort is stable; so is Python's."""
    if len(children) == 1:
        return children[0]
    if len(children) <= 6:
        children = sorted(children, key=lambda n: sum(
            abs(_trunc_div2(n.lo[d] + n.hi[d])) for d in range(7)))
        return _subtree(children)
    min_cost = None
    min_buckets = None
    min_dimension = -1
    for d in range(7):
        children = sorted(children, key=_sort_key(d, False))
        buckets = _bucketize(children)
        total = sum(_cost(b) for b in buckets)
        if min_cost is None or min_cost > total:
            min_cost = total
            min_dimension = d
            min_buckets = buckets
    min_buckets = sorted(min_buckets, key=_sort_key(min_dimension, True))
    return _subtree([_build(b.children) for b in min_buckets])


class VanillaBiomeMapper:
    """Vanilla's nearest-neighbour climate->biome search, offline.

    ``Climate.ParameterPoint.fitness`` (Climate.java L231-239) is
    ``sum_i square(param_i.distance(target_i))`` over the six axes plus
    ``square(offset)``, in units quantised by ``Climate.quantizeCoord`` (L77):
    ``(long)(coord * 10000.0F)`` -- a FLOAT multiply, then truncation toward
    zero.

    Fast path: a vectorised brute force over the dumped table gives the minimum
    fitness.  When a single biome attains it that IS vanilla's answer, since the
    RTree (L459-475) is an exact branch-and-bound over the same metric.  When
    two biomes tie -- about 0.16% of cells, measured -- the answer depends on
    the traversal, so the tie is resolved by running the real RTree.

    Residual unfaithfulness: ``RTree.search`` (L395-399) seeds the search with
    ``lastResult``, a THREAD-LOCAL holding the previous query's leaf, and the
    comparisons are strict (``minDistance > childDistance``).  So on an exact
    tie vanilla returns whatever that generation thread happened to return last
    -- which depends on chunk-fill order and thread interleaving and cannot be
    reproduced from a seed.  ``biome_candidates()`` exposes the tie set so a
    caller can see when the answer is ambiguous.
    """

    def __init__(self, path=PARAMS_JSON):
        with open(path) as fh:
            entries = json.load(fh)
        self.names = [e[0] for e in entries]
        arr = np.array([e[1:] for e in entries], dtype=np.int64)
        self.lo = np.ascontiguousarray(arr[:, 0:12:2])     # 6 mins
        self.hi = np.ascontiguousarray(arr[:, 1:12:2])     # 6 maxes
        self.off = arr[:, 12].astype(np.int64)
        self.off2 = self.off ** 2
        self._root = None

    @property
    def root(self):
        """The RTree, built lazily (~1 s for the 7594-entry overworld list)."""
        if self._root is None:
            leaves = []
            for i in range(len(self.names)):
                lo = tuple(int(v) for v in self.lo[i]) + (int(self.off[i]),)
                hi = tuple(int(v) for v in self.hi[i]) + (int(self.off[i]),)
                leaves.append(_Node(lo, hi, None, i))
            self._root = _build(leaves)
        return self._root

    @staticmethod
    def quantize(coord):
        """Climate.quantizeCoord(float)."""
        f = np.float32(coord) * np.float32(10000.0)
        return int(np.trunc(np.float64(f)))

    def _target(self, temperature, humidity, continentalness, erosion, depth, weirdness):
        return np.array([self.quantize(temperature), self.quantize(humidity),
                         self.quantize(continentalness), self.quantize(erosion),
                         self.quantize(depth), self.quantize(weirdness)],
                        dtype=np.int64)

    def _minima(self, t):
        above = t - self.hi
        below = self.lo - t
        d = np.where(above > 0, above, np.maximum(below, 0))
        fit = (d * d).sum(axis=1) + self.off2
        return fit, np.flatnonzero(fit == fit.min())

    def biome(self, temperature, humidity, continentalness, erosion, depth, weirdness):
        t = self._target(temperature, humidity, continentalness, erosion, depth, weirdness)
        _, idx = self._minima(t)
        names = {self.names[int(i)] for i in idx}
        if len(names) == 1:
            return self.names[int(idx[0])]
        target = [int(v) for v in t] + [0]
        return self.names[self.root.search(target, None).index]

    def biome_candidates(self, temperature, humidity, continentalness, erosion,
                         depth, weirdness):
        """(chosen, sorted list of every biome tied for the minimum fitness)."""
        t = self._target(temperature, humidity, continentalness, erosion, depth, weirdness)
        _, idx = self._minima(t)
        names = sorted({self.names[int(i)] for i in idx})
        if len(names) == 1:
            return names[0], names
        target = [int(v) for v in t] + [0]
        return self.names[self.root.search(target, None).index], names


# ==========================================================================
# generation/CubeWorldBiomeProvider.java
# ==========================================================================

THE_VOID = "minecraft:the_void"


class CubeWorldBiomes:
    """CubeWorldBiomeProvider, offline. One instance holds the rasters."""

    def __init__(self, earth_dat=EARTH_DAT, coast_dat=COAST_DAT,
                 peaks_csv=PEAKS_CSV, params_json=PARAMS_JSON,
                 face_size=FACE_SIZE, margin_blocks=MARGIN_BLOCKS):
        self.geometry = CubeGeometry(face_size)
        self.topology = CubeTopology(self.geometry)
        self.margin_blocks = margin_blocks
        self.earth = EarthData(earth_dat)
        if coast_dat and os.path.exists(coast_dat):
            self.earth.merge(coast_dat)
        self.peaks = PeakField(peaks_csv)
        self.climate = EarthClimate(self.earth, self.peaks)
        self.sampler = MapSampler(self.topology, self.earth)
        self.mapper = VanillaBiomeMapper(params_json)

    # --- CubeWorldBiomeProvider.oceanBiome (L173-189) -------------------
    @staticmethod
    def _ocean_biome(c):
        t = c[0]
        deep = c[6] < -1000
        if t < -0.45:
            return "minecraft:deep_frozen_ocean" if deep else "minecraft:frozen_ocean"
        if t < -0.15:
            return "minecraft:deep_cold_ocean" if deep else "minecraft:cold_ocean"
        if t < 0.2:
            return "minecraft:deep_ocean" if deep else "minecraft:ocean"
        if t < 0.45:
            return "minecraft:deep_lukewarm_ocean" if deep else "minecraft:lukewarm_ocean"
        return "minecraft:warm_ocean"

    # --- CubeWorldBiomeProvider.classifyRiver (L201-209) ----------------
    def _classify_river(self, wx, wz, c):
        if c[6] < 0:
            return 0
        if self.climate.river_strength(self.sampler, wx, wz) > EarthClimate.RIVER_THRESHOLD:
            return 2 if c[7] < -2 else 1
        return 0

    def _vanilla_biome(self, c, depth):
        return self.mapper.biome(c[0], c[1], c[2], c[3], depth, c[5])

    # --- CubeWorldBiomeProvider.getBiome (L49-67) + earthBiome (L115-169)
    def biome_at(self, seed, x, y, z):
        on_face = self.geometry.face_at(x, z) is not None
        if not on_face and self.topology.margin_source(
                x + 0.5, z + 0.5, self.margin_blocks) is None:
            return THE_VOID
        wx = x + 0.5
        wz = z + 0.5
        c = self.climate.params(self.sampler, wx, wz, y, seed)
        if c is None:
            return THE_VOID
        surface_y = self.sampler.height_at(wx, wz)
        river = self._classify_river(wx, wz, c)
        depth = self.climate.depth(surface_y, y)
        if depth < 0.15 and river != 0 and c[6] >= 0:
            return "minecraft:frozen_river" if river == 2 else "minecraft:river"
        if c[6] < 0 and depth < 0.15:
            return self._ocean_biome(c)
        if depth >= EarthClimate.DEEP_PLATEAU:
            return self._vanilla_biome(c, EarthClimate.DEEP_PLATEAU)
        if depth <= -0.1:
            return self._vanilla_biome(c, -0.1)
        return self._vanilla_biome(c, depth)

    # --- CubeWorldBiomeProvider.surfaceBiome (L78-82) -------------------
    def surface_biome_at(self, seed, x, z):
        y = _java_round(self.sampler.height_at(x + 0.5, z + 0.5))
        return self.biome_at(seed, x, y, z)

    def climate_at(self, seed, x, z, y=None):
        """The 10-element params array -- for debugging a disagreement."""
        wx, wz = x + 0.5, z + 0.5
        if y is None:
            y = _java_round(self.sampler.height_at(wx, wz))
        return self.climate.params(self.sampler, wx, wz, y, seed)

    def biome_candidates_at(self, seed, x, y, z):
        """(biome, tied) -- `tied` lists every biome sharing the minimum fitness.

        More than one entry means vanilla's own answer is decided by the RTree's
        thread-local search history and is therefore not a function of the seed;
        see VanillaBiomeMapper. Empty for the river/ocean/void short-circuits,
        which never consult the vanilla table.
        """
        on_face = self.geometry.face_at(x, z) is not None
        if not on_face and self.topology.margin_source(
                x + 0.5, z + 0.5, self.margin_blocks) is None:
            return THE_VOID, []
        wx, wz = x + 0.5, z + 0.5
        c = self.climate.params(self.sampler, wx, wz, y, seed)
        if c is None:
            return THE_VOID, []
        depth = self.climate.depth(self.sampler.height_at(wx, wz), y)
        river = self._classify_river(wx, wz, c)
        if depth < 0.15 and river != 0 and c[6] >= 0:
            return ("minecraft:frozen_river" if river == 2 else "minecraft:river"), []
        if c[6] < 0 and depth < 0.15:
            return self._ocean_biome(c), []
        depth = min(max(depth, -0.1), EarthClimate.DEEP_PLATEAU)
        return self.mapper.biome_candidates(c[0], c[1], c[2], c[3], depth, c[5])


def _java_round(v):
    """Math.round(double) -> floor(v + 0.5)."""
    return int(math.floor(v + 0.5))


# --------------------------------------------------------------------------
# module-level convenience API
# --------------------------------------------------------------------------

@lru_cache(maxsize=2)
def _default(earth_dat=EARTH_DAT, coast_dat=COAST_DAT):
    return CubeWorldBiomes(earth_dat, coast_dat)


def biome_at(seed, x, y, z):
    """The biome the plugin's BiomeProvider would return at block (x, y, z)."""
    return _default().biome_at(seed, x, y, z)


def surface_biome_at(seed, x, z):
    """The biome at the top of the column at (x, z)."""
    return _default().surface_biome_at(seed, x, z)


def server_biome_at(seed, x, y, z):
    """What ``world.getBiome(x, y, z)`` returns.

    CraftRegionAccessor.getBiome -> getNoiseBiome(x>>2, y>>2, z>>2), and
    CustomWorldChunkManager.getNoiseBiome calls the plugin provider at
    QuartPos.toBlock of those, i.e. the biome lattice is 4x4x4 and only the
    quart-aligned corner is ever asked.
    """
    return biome_at(seed, (x >> 2) << 2, (y >> 2) << 2, (z >> 2) << 2)


if __name__ == "__main__":
    import sys
    sd = int(sys.argv[1])
    bx, bz = int(sys.argv[2]), int(sys.argv[3])
    b = _default()
    if len(sys.argv) > 4:
        by = int(sys.argv[4])
    else:
        by = _java_round(b.sampler.height_at(bx + 0.5, bz + 0.5))
    print("surfaceY", b.sampler.height_at(bx + 0.5, bz + 0.5))
    print("params  ", b.climate_at(sd, bx, bz, by))
    print("biome   ", b.biome_at(sd, bx, by, bz))
