"""Read the cube-face world blob (worldblob.cwb) and sample the climate fields
(elevation/temperature/precip) at face (u,v) in [-1,1]. Also exposes a coarse
local-relief field for erosion. Values match the plugin's Earth sampling at the
blob's (downsampled) resolution."""

import os
import struct

import numpy as np

FACES = ["NORTH_POLE", "EQ_PRIME", "EQ_EAST", "EQ_BACK", "EQ_WEST", "SOUTH_POLE"]
_DEFAULT = os.path.join(os.path.dirname(__file__), "..", "..", "cubemap", "out", "worldblob.cwb")


class Blob:
    def __init__(self, path=None):
        with open(path or _DEFAULT, "rb") as f:
            assert f.read(4) == b"CWB1", "not a CWB1 blob"
            self.roll = struct.unpack("<f", f.read(4))[0]
            self.lores = struct.unpack("<i", f.read(4))[0]
            nf = struct.unpack("<i", f.read(4))[0]
            self.fields = []
            for _ in range(nf):
                name = f.read(12).rstrip(b"\0").decode()
                scale = struct.unpack("<f", f.read(4))[0]
                self.fields.append((name, scale))
            n = self.lores
            self.data = {name: {} for name, _ in self.fields}
            for name, scale in self.fields:
                for face in FACES:
                    g = np.frombuffer(f.read(n * n * 2), dtype="<i2").astype(np.float32)
                    self.data[name][face] = g.reshape(n, n) * scale
        # coarse relief per face (gradient magnitude of elevation, in metres)
        self.relief = {}
        for face in FACES:
            e = self.data["elevation"][face]
            gy, gx = np.gradient(e)
            self.relief[face] = np.hypot(gx, gy)          # per-pixel; ~metres per pixel

    def _bilinear(self, grid, u, v):
        n = self.lores
        fx = (np.asarray(u) + 1) * 0.5 * (n - 1)          # column from u
        fy = (np.asarray(v) + 1) * 0.5 * (n - 1)          # row from v
        fx = np.clip(fx, 0, n - 1.001)
        fy = np.clip(fy, 0, n - 1.001)
        x0 = fx.astype(int)
        y0 = fy.astype(int)
        tx = fx - x0
        ty = fy - y0
        x1 = np.minimum(x0 + 1, n - 1)
        y1 = np.minimum(y0 + 1, n - 1)
        return (grid[y0, x0] * (1 - tx) * (1 - ty) + grid[y0, x1] * tx * (1 - ty)
                + grid[y1, x0] * (1 - tx) * ty + grid[y1, x1] * tx * ty)

    def sample(self, field, face, u, v):
        return self._bilinear(self.data[field][face], u, v)

    def relief_at(self, face, u, v):
        # gradient is per-pixel; a pixel spans faceSize/lores blocks. Return a
        # relief-in-metres proxy the erosion curve can use.
        return self._bilinear(self.relief[face], u, v)
