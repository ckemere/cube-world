#!/usr/bin/env python3
"""Minimal Anvil region reader — enough to pull the real generated surface
block of every column in every fully-generated chunk, so the live map can show
what the world actually made instead of the biome/elevation model.

Stdlib + numpy only. Modern (1.18+, no "Level" wrapper) chunk NBT: top-level
`sections` (block_states palette + packed YZX indices), `Heightmaps`
(WORLD_SURFACE, packed 9-bit per column), `Status` (only `minecraft:full`
chunks have complete terrain).
"""
from __future__ import annotations
import struct
import zlib
import gzip
import os
import numpy as np

MIN_Y = -64            # overworld floor
WORLD_HEIGHT = 384     # -64..319
_HM_BITS = (WORLD_HEIGHT).bit_length()   # 9 bits per heightmap entry


# ---------------------------------------------------------------- NBT reader
class _NBT:
    __slots__ = ("b", "p")

    def __init__(self, b):
        self.b = b
        self.p = 0

    def _rd(self, n):
        s = self.b[self.p:self.p + n]
        self.p += n
        return s

    def _u1(self):
        v = self.b[self.p]
        self.p += 1
        return v

    def _i2(self):
        return struct.unpack_from(">h", self.b, self._adv(2))[0]

    def _i4(self):
        return struct.unpack_from(">i", self.b, self._adv(4))[0]

    def _adv(self, n):
        p = self.p
        self.p += n
        return p

    def _str(self):
        n = struct.unpack_from(">H", self.b, self._adv(2))[0]
        return self._rd(n).decode("utf-8", "replace")

    def _payload(self, t):
        if t == 1:
            return self._u1()
        if t == 2:
            return self._i2()
        if t == 3:
            return self._i4()
        if t == 4:
            return struct.unpack_from(">q", self.b, self._adv(8))[0]
        if t == 5:
            return struct.unpack_from(">f", self.b, self._adv(4))[0]
        if t == 6:
            return struct.unpack_from(">d", self.b, self._adv(8))[0]
        if t == 7:                                   # byte array
            n = self._i4()
            return np.frombuffer(self._rd(n), dtype=np.int8)
        if t == 8:
            return self._str()
        if t == 9:                                   # list
            et = self._u1()
            n = self._i4()
            return [self._payload(et) for _ in range(n)]
        if t == 10:                                  # compound
            d = {}
            while True:
                it = self._u1()
                if it == 0:
                    break
                nm = self._str()
                d[nm] = self._payload(it)
            return d
        if t == 11:                                  # int array
            n = self._i4()
            return np.frombuffer(self._rd(4 * n), dtype=">i4").astype(np.int32)
        if t == 12:                                  # long array
            n = self._i4()
            return np.frombuffer(self._rd(8 * n), dtype=">i8").astype(np.int64)
        raise ValueError(f"bad tag {t}")

    def root(self):
        t = self._u1()
        self._str()          # root name
        return self._payload(t)


def _decompress(blob, comp):
    if comp == 1:
        return gzip.decompress(blob)
    if comp == 2:
        return zlib.decompress(blob)
    if comp == 3:
        return blob
    raise ValueError(f"unsupported chunk compression {comp}")


# ------------------------------------------------------- bit-packed unpackers
def _unpack(longs, bits, count):
    """Unpack `count` little-within-long values of `bits` bits each from a
    long array, MC 1.16+ style: entries never span a long boundary."""
    if longs is None or len(longs) == 0:
        return np.zeros(count, dtype=np.int64)
    per = 64 // bits
    vals = np.empty(len(longs) * per, dtype=np.int64)
    u = longs.astype(np.uint64)
    mask = np.uint64((1 << bits) - 1)
    for k in range(per):
        vals[k::per] = ((u >> np.uint64(k * bits)) & mask).astype(np.int64)
    return vals[:count]


def _section_blocks(sec):
    """Return a length-4096 array of palette-name strings for a section, or
    None if the section is empty air. Index order is YZX."""
    bs = sec.get("block_states")
    if not bs:
        return None
    palette = [p.get("Name", "minecraft:air") for p in bs.get("palette", [])]
    if not palette:
        return None
    if len(palette) == 1:
        return palette, np.zeros(4096, dtype=np.int64)
    bits = max(4, (len(palette) - 1).bit_length())
    idx = _unpack(bs.get("data"), bits, 4096)
    return palette, idx


# Chunk statuses at or after "biomes", i.e. the ones whose biome container has
# actually been filled from the biome source. Earlier statuses still serialise a
# biome container, but it holds the placeholder (all plains) and is not real.
BIOMES_DONE = frozenset("minecraft:" + s for s in (
    "biomes", "noise", "surface", "carvers", "features",
    "initialize_light", "light", "spawn", "full"))


def chunk_biomes(root):
    """{sectionY: (palette, idx64)} for every section that carries biomes.

    Biomes are stored on a 4x4x4 lattice: 64 entries per 16-block section,
    index order YZX with y/z/x each 0..3 (quart coordinates). `idx64` is a
    length-64 array of palette indices (all zero for a single-value palette).
    Returns None for a chunk whose biomes have not been generated yet.
    """
    if root.get("Status") not in BIOMES_DONE:
        return None
    out = {}
    for sec in root.get("sections") or []:
        bi = sec.get("biomes")
        if not bi:
            continue
        palette = list(bi.get("palette", []))
        if not palette:
            continue
        data = bi.get("data")
        if len(palette) == 1 or data is None or len(data) == 0:
            out[sec.get("Y")] = (palette, np.zeros(64, dtype=np.int64))
            continue
        # entries never span a long boundary, so the packed width follows from
        # how many longs were written; start at ceil(log2(size)) and grow.
        bits = max(1, (len(palette) - 1).bit_length())
        while bits <= 32 and -(-64 // (64 // bits)) != len(data):
            bits += 1
        out[sec.get("Y")] = (palette, _unpack(data, bits, 64))
    return out


def biome_at_quart(biomes, y, qz, qx):
    """Biome name at absolute block y and in-section quart indices qz, qx."""
    sy = y >> 4
    entry = biomes.get(sy)
    if entry is None:
        return None
    palette, idx = entry
    qy = (y & 15) >> 2
    return palette[int(idx[(qy * 4 + qz) * 4 + qx])]


def read_region_chunks(path):
    """Yield (cx, cz, root) for every chunk NBT in a region file."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 4096:
        return
    for slot in range(1024):
        off = (data[slot * 4] << 16) | (data[slot * 4 + 1] << 8) | data[slot * 4 + 2]
        if off == 0:
            continue
        start = off * 4096
        if start + 5 > len(data):
            continue
        ln = struct.unpack_from(">I", data, start)[0]
        if ln == 0 or start + 4 + ln > len(data):
            continue
        try:
            root = _NBT(_decompress(data[start + 5:start + 4 + ln], data[start + 4])).root()
        except Exception:
            continue
        yield root.get("xPos"), root.get("zPos"), root


def chunk_surface(root):
    """(cx, cz, names16x16) for a full chunk, using WORLD_SURFACE heights.
    `names` is a 16x16 (z, x) array of block-name strings (top visible block).
    Returns None if the chunk isn't fully generated or lacks a heightmap."""
    if root.get("Status") != "minecraft:full":
        return None
    hm = root.get("Heightmaps") or {}
    surf = hm.get("WORLD_SURFACE")
    if surf is None:
        return None
    cx, cz = root.get("xPos"), root.get("zPos")
    heights = _unpack(surf, _HM_BITS, 256).reshape(16, 16)   # (z, x); y = MIN_Y + h - 1

    # Decode only the sections that a surface column can land in.
    secs = {}
    for sec in root.get("sections") or []:
        y = sec.get("Y")
        blocks = _section_blocks(sec)
        if blocks is not None:
            secs[y] = blocks

    names = np.empty((16, 16), dtype=object)
    for lz in range(16):
        for lx in range(16):
            h = int(heights[lz, lx])
            top_y = MIN_Y + h - 1
            if h <= 0:
                names[lz, lx] = "minecraft:air"
                continue
            sy = top_y >> 4
            entry = secs.get(sy)
            if entry is None:
                names[lz, lx] = "minecraft:air"
                continue
            palette, idx = entry
            li = ((top_y & 15) * 256) + (lz * 16) + lx
            names[lz, lx] = palette[int(idx[li])]
    return cx, cz, names


def read_region(path):
    """Yield (cx, cz, names16x16) for every full chunk in a region file."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 4096:
        return
    for slot in range(1024):
        off = (data[slot * 4] << 16) | (data[slot * 4 + 1] << 8) | data[slot * 4 + 2]
        if off == 0:
            continue
        start = off * 4096
        if start + 5 > len(data):
            continue
        ln = struct.unpack_from(">I", data, start)[0]
        if ln == 0 or start + 4 + ln > len(data):
            continue
        comp = data[start + 4]
        try:
            raw = _decompress(data[start + 5:start + 4 + ln], comp)
            root = _NBT(raw).root()
            out = chunk_surface(root)
        except Exception:
            continue
        if out is not None:
            yield out


if __name__ == "__main__":
    import sys
    from collections import Counter
    p = sys.argv[1] if len(sys.argv) > 1 else (
        "../../run/world/dimensions/minecraft/overworld/region/r.16.-4.mca")
    p = os.path.join(os.path.dirname(__file__), p) if not os.path.isabs(p) else p
    tally = Counter()
    nfull = 0
    for cx, cz, names in read_region(p):
        nfull += 1
        for row in names:
            for nm in row:
                tally[nm] += 1
    print(f"{p}\n  full chunks: {nfull}")
    for nm, c in tally.most_common(20):
        print(f"  {c:8d}  {nm}")
