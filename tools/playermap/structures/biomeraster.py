"""Read the per-chunk surface-biome raster the plugin exports
(plugins/CubeWorld/biomes/overworld.cwbr) and answer "what biome is chunk
(cx,cz)?". Seed-independent, so it's loaded once and reused for every seed."""

import os
import struct

import numpy as np


def _read_utf(f):
    (n,) = struct.unpack(">H", f.read(2))
    return f.read(n).decode("utf-8")


class BiomeRaster:
    def __init__(self, path):
        with open(path, "rb") as f:
            assert f.read(4) == b"CWBR", "not a CWBR file"
            (self.version, self.face_size, self.chunks, npal) = struct.unpack(">iiii", f.read(16))
            self.palette = [_read_utf(f) for _ in range(npal)]
            self.faces = []       # (name, chunk_min_x, chunk_min_z, grid[i*chunks+j])
            n = self.chunks * self.chunks
            for _ in range(6):
                name = _read_utf(f)
                (min_x, min_z) = struct.unpack(">ii", f.read(8))
                grid = np.frombuffer(f.read(2 * n), dtype=">i2").astype(np.int16)
                self.faces.append((name, min_x >> 4, min_z >> 4, grid))

    def biome_at_chunk(self, cx, cz):
        """Biome id ('minecraft:plains') at chunk (cx,cz), or None off-net."""
        for _name, cmx, cmz, grid in self.faces:
            i = cx - cmx
            j = cz - cmz
            if 0 <= i < self.chunks and 0 <= j < self.chunks:
                return self.palette[grid[i * self.chunks + j]]
        return None

    def chunks_with_biomes(self, allowed):
        """Every on-net chunk (cx,cz) whose biome is in `allowed` — vectorised, so
        spacing-1 structures (buried treasure) don't enumerate the whole net."""
        idxs = [i for i, b in enumerate(self.palette) if b in allowed]
        if not idxs:
            return []
        want = np.array(idxs, dtype=np.int16)
        out = []
        for _name, cmx, cmz, grid in self.faces:
            mask = np.isin(grid, want).reshape(self.chunks, self.chunks)
            ii, jj = np.nonzero(mask)
            out.extend(zip((ii + cmx).tolist(), (jj + cmz).tolist()))
        return out


_DEFAULT = os.path.join(os.path.dirname(__file__), "..", "..", "..",
                        "run", "plugins", "CubeWorld", "biomes", "overworld.cwbr")


def load(path=None):
    return BiomeRaster(path or _DEFAULT)


if __name__ == "__main__":
    r = load()
    print("version", r.version, "faceSize", r.face_size, "chunks", r.chunks,
          "biomes", len(r.palette))
    # spawn is Ethiopia (9381,-1737) -> chunk (586,-109)
    print("biome at spawn chunk (586,-109):", r.biome_at_chunk(586, -109))
    print("faces:", [f[0] for f in r.faces])
    # a quick tally of the most common biomes
    from collections import Counter
    c = Counter()
    for _n, _x, _z, grid in r.faces:
        c.update(r.palette[i] for i in grid)
    for b, n in c.most_common(8):
        print(f"  {b:34s} {n}")
