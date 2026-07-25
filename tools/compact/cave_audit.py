"""Audit cave intrusion around a city.

Vanilla's noise caves (cheese/spaghetti/noodle) and carvers run everywhere,
including under the anchored city villages, so a jigsaw village can end up
undermined or breached by a cavern. This reads the region files directly and
reports, for the chunks around a point:

  breach   - columns where the terrain surface itself is a cave opening
             (a void reaching within a few blocks of the top solid block)
  undermined - columns whose surface is village build material and which have a
             sizeable void directly beneath
  void      - overall fraction of subsurface blocks that are cave air

Usage: cave_audit.py <x> <z> [radius_chunks]
"""
import os
import struct
import sys

import numpy as np

sys.path.insert(0, "/home/dev/projects/cube-world/tools/playermap")
import anvil  # noqa: E402

REGION = ("/home/dev/projects/cube-world/run/world/dimensions/"
          "minecraft/overworld/region")

VILLAGE = {
    "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:spruce_planks",
    "minecraft:dirt_path", "minecraft:oak_log", "minecraft:spruce_log",
    "minecraft:cobblestone_stairs", "minecraft:oak_stairs", "minecraft:hay_block",
    "minecraft:oak_fence", "minecraft:spruce_fence", "minecraft:stripped_oak_log",
    "minecraft:bookshelf", "minecraft:smooth_stone", "minecraft:mossy_cobblestone",
    "minecraft:white_terracotta", "minecraft:sandstone", "minecraft:cut_sandstone",
    "minecraft:acacia_planks", "minecraft:acacia_log", "minecraft:terracotta",
}
# CRITICAL distinction: vanilla's noise caves/carvers fill with cave_air, while a
# building interior is plain air. Counting both conflates "undermined by a cavern"
# with "has a room under the roof".
CAVE_AIR = {"minecraft:cave_air"}
ANY_AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def load_chunk(cx, cz):
    rx, rz = cx >> 5, cz >> 5
    path = os.path.join(REGION, f"r.{rx}.{rz}.mca")
    if not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        blob = f.read()
    slot = (cx & 31) + (cz & 31) * 32
    off = int.from_bytes(blob[slot * 4:slot * 4 + 3], "big")
    if off == 0:
        return None
    start = off * 4096
    ln = struct.unpack_from(">I", blob, start)[0]
    comp = blob[start + 4]
    try:
        root = anvil._NBT(anvil._decompress(blob[start + 5:start + 4 + ln], comp)).root()
    except Exception:
        return None
    if root.get("Status") != "minecraft:full":
        return None
    return root


def column_blocks(root):
    """{(lx,lz): (surface_y, surface_name, [(y, name) ...] descending)} for a chunk."""
    secs = {}
    for sec in root.get("sections") or []:
        got = anvil._section_blocks(sec)
        if got is not None:
            secs[sec.get("Y")] = got
    hm = (root.get("Heightmaps") or {}).get("MOTION_BLOCKING_NO_LEAVES")
    if hm is None:
        return None
    h = anvil._unpack(hm, anvil._HM_BITS, 256).reshape(16, 16)

    def name_at(lx, y, lz):
        sy = y >> 4
        got = secs.get(sy)
        if got is None:
            return "minecraft:air"
        pal, idx = got
        i = ((y & 15) << 8) | ((lz & 15) << 4) | (lx & 15)
        return pal[idx[i]]

    return h, name_at


def audit(cx0, cz0, rad):
    breach = under = cols = 0
    void = solid = 0
    examples = []
    for cx in range(cx0 - rad, cx0 + rad + 1):
        for cz in range(cz0 - rad, cz0 + rad + 1):
            root = load_chunk(cx, cz)
            if root is None:
                continue
            got = column_blocks(root)
            if got is None:
                continue
            h, name_at = got
            for lz in range(16):
                for lx in range(16):
                    top = anvil.MIN_Y + int(h[lz, lx]) - 1
                    if top < anvil.MIN_Y + 1:
                        continue
                    cols += 1
                    surf = name_at(lx, top, lz)
                    # scan 24 blocks below the surface for cave air
                    run = 0
                    best = 0
                    first_void = None
                    for dy in range(1, 25):
                        y = top - dy
                        n = name_at(lx, y, lz)
                        if n in CAVE_AIR:
                            void += 1
                            run += 1
                            best = max(best, run)
                            if first_void is None:
                                first_void = dy
                        elif n in ANY_AIR:
                            run = 0                       # building interior: ignore
                        else:
                            if n != "minecraft:water":
                                solid += 1
                            run = 0
                    if first_void is not None and first_void <= 3:
                        breach += 1
                    if best >= 4 and surf in VILLAGE:
                        under += 1
                        if len(examples) < 6:
                            examples.append((cx * 16 + lx, top, cz * 16 + lz,
                                             surf.split(":")[1], first_void, best))
    return dict(cols=cols, breach=breach, under=under,
                voidfrac=void / max(1, void + solid), examples=examples)


if __name__ == "__main__":
    x, z = int(sys.argv[1]), int(sys.argv[2])
    rad = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    r = audit(x >> 4, z >> 4, rad)
    n = max(1, r["cols"])
    print(f"around ({x},{z}), {(2*rad+1)**2} chunks, {r['cols']} columns:")
    print(f"  surface breached by a void within 3 blocks : {r['breach']:5d}"
          f"  ({r['breach']/n*100:5.2f}%)")
    print(f"  village-surface columns undermined (>=4 air): {r['under']:5d}"
          f"  ({r['under']/n*100:5.2f}%)")
    print(f"  cave-air fraction of the 24 blocks below     : {r['voidfrac']*100:5.2f}%")
    if r["examples"]:
        print("  examples of undermined village columns (x, topY, z, block, gapStart, gapLen):")
        for e in r["examples"]:
            print(f"    {e}")
