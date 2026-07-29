#!/usr/bin/env python3
"""Append the `sst` layer to an existing run/earth.dat, in place, by streaming.

Rebuilding earth.dat from source is a long pipeline that needs ETOPO and
WorldClim on disk. Adding one small layer does not justify that: the CWE1 format
is a header followed by int16 blocks in header order, so a new layer is a header
rewrite plus a copy. The copy is streamed in 32 MB chunks because the file is
359 MB and this box has OOM-killed itself loading less.

Run `sst.py` first (it needs out/woa_sst.ascii) to produce out/sst_total.npy.

    python3 tools/compact/add_sst.py [run/earth.dat]
"""
from __future__ import annotations
import os
import shutil
import struct
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
NPY = os.path.join(HERE, "out", "sst_total.npy")
SCALE = 0.01                    # int16 @ 0.01 C spans +/-327 C; SST is -1.8..30.3
CHUNK = 32 << 20


def read_header(path):
    with open(path, "rb") as f:
        head = f.read(12)
        if head[:4] != b"CWE1":
            sys.exit(f"not a CWE1 file: {path}")
        roll, = struct.unpack_from("<f", head, 4)
        n, = struct.unpack_from("<i", head, 8)
        hdr = f.read(n * 24)
    metas = []
    for i in range(n):
        b = i * 24
        name = hdr[b:b + 8].split(b"\0")[0].decode("ascii")
        w, h = struct.unpack_from("<ii", hdr, b + 8)
        sc, of = struct.unpack_from("<ff", hdr, b + 16)
        metas.append((name, w, h, sc, of))
    return roll, metas, 12 + n * 24


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "run/earth.dat"
    if not os.path.exists(NPY):
        sys.exit(f"missing {NPY} -- run: python3 tools/compact/sst.py")
    roll, metas, data_off = read_header(path)
    if any(m[0] == "sst" for m in metas):
        sys.exit("earth.dat already has an `sst` layer; nothing to do")

    sst = np.load(NPY)
    assert np.isfinite(sst).all(), "sst_total.npy has holes; it must be TOTAL"
    h, w = sst.shape
    q = np.round(sst / SCALE).astype("<i2")
    lo, hi = int(q.min()), int(q.max())
    assert -32767 <= lo and hi <= 32767, f"sst out of int16 range: {lo}..{hi}"

    tmp = path + ".new"
    with open(path, "rb") as src, open(tmp, "wb") as dst:
        dst.write(b"CWE1")
        dst.write(struct.pack("<f", roll))
        dst.write(struct.pack("<i", len(metas) + 1))
        for name, lw, lh, sc, of in metas:
            dst.write(name.encode("ascii").ljust(8, b"\0"))
            dst.write(struct.pack("<iiff", lw, lh, sc, of))
        dst.write(b"sst".ljust(8, b"\0"))
        dst.write(struct.pack("<iiff", w, h, SCALE, 0.0))
        src.seek(data_off)
        while True:
            buf = src.read(CHUNK)
            if not buf:
                break
            dst.write(buf)
        dst.write(q.tobytes())

    shutil.move(tmp, path)
    print(f"appended `sst` {w}x{h} @{SCALE} C  (+{q.nbytes/1e3:.0f} KB), "
          f"range {lo*SCALE:.1f}..{hi*SCALE:.1f} C")
    print(f"{path} is now {os.path.getsize(path)/1e6:.1f} MB with "
          f"{len(metas)+1} layers")


if __name__ == "__main__":
    main()
