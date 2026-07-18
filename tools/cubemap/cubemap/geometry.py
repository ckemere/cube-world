"""Cube-surface projection: the single source of truth for how the CubeWorld
net folds onto the sphere, mirrored from the Java ``CubeSurface`` /
``CubeFace`` classes. Keep the embedding formulas identical to
``src/main/java/com/ckemere/cubeworld/geometry/CubeSurface.java``.

Orientation is a rigid rotation applied to the cube-frame direction before it
is read as geographic lon/lat, parameterised as (roll about the polar axis,
tilt of the polar axis). Poles pinned to the geographic poles = tilt 0, which
is the recommended configuration (ice caps become the top/bottom faces).
"""
from __future__ import annotations
import math
import numpy as np

# Net layout (grid col, row), from CubeFace.java. Columns increase east (+X),
# rows increase south (+Z). North pole is the centre of the cross.
FACES = ["NORTH_POLE", "EQ_PRIME", "EQ_EAST", "EQ_BACK", "EQ_WEST", "SOUTH_POLE"]
GRID = {
    "NORTH_POLE": (0, 0),
    "EQ_PRIME": (0, 1),
    "EQ_EAST": (1, 0),
    "EQ_BACK": (0, -1),
    "EQ_WEST": (-1, 0),
    "SOUTH_POLE": (0, 2),
}
FACE_LABEL = {
    "NORTH_POLE": "N POLE",
    "EQ_PRIME": "EQ 0",
    "EQ_EAST": "EQ 90E",
    "EQ_BACK": "EQ 180",
    "EQ_WEST": "EQ 90W",
    "SOUTH_POLE": "S POLE",
}


def cube_point(face, u, v):
    """Face-local (u, v) in [-1, 1] -> point on the unit cube [-1, 1]^3.

    Accepts scalars or numpy arrays. Mirrors CubeSurface.point (with
    u = lx, v = lz after normalising face-local coords to [-1, 1]).
    """
    one = np.ones_like(u) if isinstance(u, np.ndarray) else 1.0
    if face == "NORTH_POLE":
        return u, one, v
    if face == "EQ_PRIME":
        return u, -v, one
    if face == "SOUTH_POLE":
        return u, -one, -v
    if face == "EQ_BACK":
        return u, v, -one
    if face == "EQ_EAST":
        return one, -u, v
    if face == "EQ_WEST":
        return -one, u, v
    raise ValueError(face)


def rotation_matrix(roll_deg, tilt_deg):
    """Roll about the polar (Y) axis, then tilt about X. Applied to the
    cube-frame direction to produce the geographic-frame direction."""
    r = math.radians(roll_deg)
    t = math.radians(tilt_deg)
    ry = np.array([[math.cos(r), 0, math.sin(r)],
                   [0, 1, 0],
                   [-math.sin(r), 0, math.cos(r)]])
    rx = np.array([[1, 0, 0],
                   [0, math.cos(t), -math.sin(t)],
                   [0, math.sin(t), math.cos(t)]])
    return rx @ ry


# Locked Earth-map orientation (chosen 2026-07-18): roll -70 about the polar
# axis, poles pinned to geographic poles. Best balance found over the full
# 90-degree period — 2 land pillars (US Southwest, Iran), clean Atlantic +
# Pacific seams, lowest seam-over-land (7%). See tools/cubemap analysis.
EARTH_ROLL_DEG = -70.0
EARTH_TILT_DEG = 0.0


class CubeProjection:
    def __init__(self, roll_deg=EARTH_ROLL_DEG, tilt_deg=EARTH_TILT_DEG):
        self.roll_deg = roll_deg
        self.tilt_deg = tilt_deg
        self.R = rotation_matrix(roll_deg, tilt_deg)

    def dir_to_lonlat(self, x, y, z):
        """Cube-frame direction (arrays ok) -> (lon_deg, lat_deg)."""
        n = np.sqrt(x * x + y * y + z * z)
        x, y, z = x / n, y / n, z / n
        gx = self.R[0, 0] * x + self.R[0, 1] * y + self.R[0, 2] * z
        gy = self.R[1, 0] * x + self.R[1, 1] * y + self.R[1, 2] * z
        gz = self.R[2, 0] * x + self.R[2, 1] * y + self.R[2, 2] * z
        lat = np.degrees(np.arcsin(np.clip(gy, -1.0, 1.0)))
        lon = np.degrees(np.arctan2(gx, gz))
        return lon, lat

    def face_lonlat_grid(self, face, n):
        """n x n grid of (lon, lat) for a face, u/v spanning [-1, 1]."""
        t = np.linspace(-1.0, 1.0, n)
        u, v = np.meshgrid(t, t)  # u varies along columns (east), v along rows
        x, y, z = cube_point(face, u, v)
        return self.dir_to_lonlat(x, y, z)

    def lonlat_to_face_uv(self, lon_deg, lat_deg):
        """Inverse of the face reprojection: geographic (lon, lat) -> the face
        it lands on and its (u, v) in [-1, 1]. Used to draw vector features
        (rivers, lakes) onto the face textures."""
        la = math.radians(lat_deg)
        lo = math.radians(lon_deg)
        g = np.array([math.cos(la) * math.sin(lo), math.sin(la), math.cos(la) * math.cos(lo)])
        d = self.R.T @ g  # inverse rotation (R orthonormal)
        ax, ay, az = abs(d[0]), abs(d[1]), abs(d[2])
        if ay >= ax and ay >= az:
            p = d / ay
            return ("NORTH_POLE", p[0], p[2]) if d[1] > 0 else ("SOUTH_POLE", p[0], -p[2])
        if az >= ax:
            p = d / az
            return ("EQ_PRIME", p[0], -p[1]) if d[2] > 0 else ("EQ_BACK", p[0], p[1])
        p = d / ax
        return ("EQ_EAST", -p[1], p[2]) if d[0] > 0 else ("EQ_WEST", p[1], p[2])

    def pillars(self):
        """The 8 cube vertices as (lon, lat)."""
        out = []
        for sx in (1, -1):
            for sy in (1, -1):
                for sz in (1, -1):
                    lon, lat = self.dir_to_lonlat(
                        np.array([float(sx)]), np.array([float(sy)]), np.array([float(sz)]))
                    out.append((float(lon[0]), float(lat[0])))
        return out

    def polar_face_edges(self, face, samples=240):
        """Boundary of a pole face as a list of (lon, lat) polylines (4 edges)."""
        polylines = []
        for var, fix in (("u", 1.0), ("u", -1.0), ("v", 1.0), ("v", -1.0)):
            t = np.linspace(-1.0, 1.0, samples)
            if var == "u":
                u, v = t, np.full_like(t, fix)
            else:
                u, v = np.full_like(t, fix), t
            x, y, z = cube_point(face, u, v)
            lon, lat = self.dir_to_lonlat(x, y, z)
            polylines.append(list(zip(lon.tolist(), lat.tolist())))
        return polylines

    def equatorial_seam_polylines(self, samples=240):
        """The 4 vertical seams as sampled (lon, lat) polylines. Each vertical
        seam is where two equatorial faces meet: a fixed (x, z) in {±1} with
        y in [-1, 1]. Sampling keeps it correct even under a nonzero tilt."""
        signs = [(1, 1), (1, -1), (-1, -1), (-1, 1)]
        out = []
        for sx, sz in signs:
            t = np.linspace(-1.0, 1.0, samples)
            x = np.full_like(t, float(sx))
            z = np.full_like(t, float(sz))
            lon, lat = self.dir_to_lonlat(x, t, z)
            out.append(list(zip(lon.tolist(), lat.tolist())))
        return out
