#!/usr/bin/env python3
"""Score a predicted structure layer against what the world actually generated.

The overlay is pure seed maths, so it can be wrong in two directions and counting
markers alone hides both. This reads the region files, takes every chunk where
vanilla has actually RUN structure placement, and reports precision and recall.

Two traps this exists to avoid, both of which produced wrong answers before:

  * Filtering chunks on Status == "full" undercounts badly. createStructures runs
    at the `structure_starts` stage, and this world has 30150 chunks there versus
    9580 full -- filtering on full found 3 villages where there are 14.
  * A structure's start is recorded in the chunks it OVERLAPS as well as the one
    that owns it, so entries must be filtered to ChunkX/ChunkZ == this chunk.

Usage:
    python3 -m structures.validate [layer]        # default: villages
"""
from __future__ import annotations

import collections
import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import anvil                                                     # noqa: E402

REGION = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "..", "..", "..", "run", "world",
                      "dimensions", "minecraft", "overworld", "region")

# Chunk statuses at or past which vanilla has decided structure starts.
DECIDED = {"structure_starts", "biomes", "carvers", "initialize_light",
           "features", "light", "spawn", "full"}


def scan(region_dir=REGION, prefix="minecraft:village"):
    """(decided_chunks, {(cx,cz): structure_id}) for starts owned by that chunk."""
    decided, found = set(), {}
    statuses = collections.Counter()
    for fn in sorted(os.listdir(region_dir)):
        if not fn.endswith(".mca"):
            continue
        data = open(os.path.join(region_dir, fn), "rb").read()
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
                root = anvil._NBT(anvil._decompress(
                    data[start + 5:start + 4 + ln], data[start + 4])).root()
            except Exception:
                continue
            cx, cz = root.get("xPos"), root.get("zPos")
            if cx is None:
                continue
            status = (root.get("Status") or "").replace("minecraft:", "")
            statuses[status] += 1
            if status not in DECIDED:
                continue
            decided.add((cx, cz))
            for key, val in ((root.get("structures") or {}).get("starts") or {}).items():
                if not val or not key.startswith(prefix):
                    continue
                if val.get("ChunkX") == cx and val.get("ChunkZ") == cz:
                    found[(cx, cz)] = key
    return decided, found, statuses


def score(predicted, actual, decided):
    """precision/recall over chunks where vanilla has actually decided."""
    pred = {c for c in predicted if c in decided}
    real = {c for c in actual if c in decided}
    tp = pred & real
    return {
        "predicted": len(pred), "real": len(real),
        "tp": len(tp), "fp": len(pred - real), "fn": len(real - pred),
        "precision": len(tp) / len(pred) if pred else float("nan"),
        "recall": len(tp) / len(real) if real else float("nan"),
        "false_positives": sorted(pred - real),
        "false_negatives": sorted(real - pred),
    }


def main():
    decided, actual, statuses = scan()
    print(f"chunk statuses: {dict(statuses)}")
    print(f"decided chunks: {len(decided):,}   real villages: {len(actual)}")
    for (cx, cz), kind in sorted(actual.items()):
        print(f"   {kind:28s} chunk ({cx},{cz})  world ({cx*16},{cz*16})")
    return decided, actual


if __name__ == "__main__":
    main()
