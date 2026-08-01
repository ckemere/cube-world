#!/usr/bin/env python3
"""Regenerate the 30 city villages so the current generator (esp. the improved
VillageGroundFixer) applies to them, WITHOUT destroying their teleporters.

The fixer runs at chunk generation, so existing cities keep their old dirt-walled
plinths and floating trees until their chunks are regenerated. A teleporter is a
runtime placement (3x3 amethyst pad + lodestone core) that is NOT part of
worldgen, so a naive region delete would wipe it and leave the registry pointing
at empty terrain (the Basra bug). This protects every chunk the pad touches.

Two phases:
  surgery  (server DOWN): zero the village-box chunk entries in the .mca files,
           skipping any chunk within +/-2 blocks of a station core.
  regen    (server UP):   force-load each box so the zeroed chunks regenerate and
           the ground fixer runs on them; then verify every core is still a
           lodestone.

    ./gradlew build && stop server
    python3 tools/regen_cities.py surgery
    ./run-server.sh   (then, once up)
    python3 tools/regen_cities.py regen
"""
from __future__ import annotations
import os, socket, struct, sys, time
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.path.join(ROOT, "run/world/dimensions/minecraft/overworld")
STATIONS = os.path.join(ROOT, "run/plugins/CubeWorld/stations.csv")
BOX = 88            # blocks each side of the station to regenerate (~12 chunks -> <256)
PAD_GUARD = 2       # blocks around the core to protect (covers the 3x3 pad + buffer)


def load_stations():
    out = []
    for ln in open(STATIONS, encoding="utf-8"):
        ln = ln.strip()
        if not ln or ln.startswith("#"):
            continue
        p = ln.split(",")
        if len(p) < 4 or p[0] != "world":
            continue
        out.append((int(p[1]), int(p[2]), int(p[3]), p[8] if len(p) > 8 else "?"))
    return out


def rcon(cmds, timeout=120):
    s = socket.create_connection(("127.0.0.1", 25575), timeout=timeout)

    def pkt(rid, typ, body):
        p = struct.pack("<ii", rid, typ) + body.encode() + b"\x00\x00"
        return struct.pack("<i", len(p)) + p

    def read():
        n = struct.unpack("<i", s.recv(4))[0]
        d = b""
        while len(d) < n:
            d += s.recv(n - len(d))
        return d[8:-2].decode("utf8", "replace")

    s.sendall(pkt(1, 3, "cubeworld-dev"))
    read()
    out = []
    for i, c in enumerate(cmds):
        s.sendall(pkt(10 + i, 2, c))
        out.append(read())
    s.close()
    return out


def protected_chunks(stations):
    prot = set()
    for x, _y, z, _n in stations:
        for bx in range(x - PAD_GUARD, x + PAD_GUARD + 1):
            for bz in range(z - PAD_GUARD, z + PAD_GUARD + 1):
                prot.add((bx >> 4, bz >> 4))
    return prot


def surgery():
    stations = load_stations()
    prot = protected_chunks(stations)
    # every chunk in any city box, minus protected chunks, grouped by region file
    by_region = defaultdict(set)
    for x, _y, z, _n in stations:
        for cx in range((x - BOX) >> 4, ((x + BOX) >> 4) + 1):
            for cz in range((z - BOX) >> 4, ((z + BOX) >> 4) + 1):
                if (cx, cz) in prot:
                    continue
                by_region[(cx >> 5, cz >> 5)].add((cx, cz))
    total = 0
    for (rx, rz), chunks in sorted(by_region.items()):
        for sub in ("region", "entities", "poi"):
            p = f"{BASE}/{sub}/r.{rx}.{rz}.mca"
            if not os.path.exists(p):
                continue
            with open(p, "r+b") as f:
                hdr = bytearray(f.read(4096))
                for cx, cz in chunks:
                    slot = (cx & 31) + (cz & 31) * 32
                    if hdr[slot * 4:slot * 4 + 3] != b"\x00\x00\x00":
                        hdr[slot * 4:slot * 4 + 4] = b"\x00\x00\x00\x00"
                        if sub == "region":
                            total += 1
                f.seek(0)
                f.write(hdr)
    print(f"surgery: zeroed {total} terrain chunks across {len(by_region)} regions, "
          f"protected {len(prot)} pad chunks -- {len(stations)} teleporters preserved")


def regen():
    stations = load_stations()
    for i, (x, _y, z, name) in enumerate(stations, 1):
        name = name.split("#")[0].strip()
        for attempt in range(3):
            try:
                rcon([f"forceload add {x - BOX} {z - BOX} {x + BOX} {z + BOX}"], timeout=90)
                break
            except Exception as e:
                print(f"  {name}: forceload retry ({e})")
                time.sleep(2)
        time.sleep(33)                       # chunk gen + ground-fixer drain
        try:
            rcon(["save-all", "forceload remove all"], timeout=90)
        except Exception:
            pass
        print(f"  [{i}/{len(stations)}] regenerated {name}")
        time.sleep(1)
    # verify every teleporter core survived
    bad = []
    for x, y, z, name in stations:
        name = name.split("#")[0].strip()
        rcon([f"forceload add {x - 8} {z - 8} {x + 8} {z + 8}"])
        time.sleep(0.6)
        b = rcon([f"cubeworld blockat {x} {y} {z}"])[0]
        rcon(["forceload remove all"])
        if "lodestone" not in b:
            bad.append((name, x, y, z, b.replace("§b", "")))
    if bad:
        print("  BROKEN teleporters (core not lodestone):")
        for n, x, y, z, b in bad:
            print(f"    {n} @ {x},{y},{z}: {b}")
    else:
        print(f"  all {len(stations)} teleporter cores intact (lodestone)")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "surgery":
        surgery()
    elif mode == "regen":
        regen()
    else:
        sys.exit("usage: regen_cities.py surgery|regen")
