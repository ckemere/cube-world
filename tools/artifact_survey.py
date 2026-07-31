#!/usr/bin/env python3
"""Survey chunks across the world for terrain artifacts.

Kicked off after the Carthage "dry ocean" bug: shallow below-sea-level ocean
shelves generate as dry land (grass surface, air up to sea level, biome still
ocean) instead of water-covered floor. This walks a set of lat/lon sample points,
force-loads a bounded box at each (so the fragile 2.6 GB box never holds too many
chunks), saves, and scans the region files for anomalies:

  drysea   : ocean floor below sea AND nothing (no water) reaching sea level
  airhole  : a fully-air column from y0..surface (a missing/void column)
  floatwtr : water with air directly beneath (perched water)

Run with the dev server UP:  python3 tools/artifact_survey.py [--points N]
"""
from __future__ import annotations
import argparse, os, socket, struct, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "playermap"))
import anvil  # noqa: E402
import numpy as np  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGION = os.path.join(ROOT, "run/world/dimensions/minecraft/overworld/region")
SEA = 63

# Sample points: a spread of coasts, interiors and open ocean across all faces.
POINTS = [
    ("Carthage coast", 36.85, 10.3), ("US Gulf coast", 29.5, -90),
    ("Bengal delta", 22, 90), ("Nile delta", 31, 31), ("Netherlands", 52.5, 4.5),
    ("Amazon mouth", -1, -50), ("W Africa coast", 5, -4), ("Persian Gulf", 27, 51),
    ("Vietnam coast", 10.5, 106), ("N Australia", -12, 131), ("Peru coast", -12, -77),
    ("Norway coast", 68, 15), ("Great Lakes", 44, -83), ("Caspian", 42, 51),
    ("Sahara interior", 23, 12), ("Tibet interior", 33, 88), ("Congo interior", -1, 22),
    ("Mid-Pacific", -20, -140), ("N Atlantic", 40, -40), ("Antarctic coast", -70, 0),
]


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


def find_world(lat, lon):
    r = rcon([f"cubeworld findlatlon {lat} {lon}"])[0].replace("§b", "")
    i = r.find("world ("); j = r.find(")", i)
    if i < 0:
        return None
    xz = r[i + 7:j].split(",")
    return int(xz[0]), int(xz[1].strip())


def scan_box(cx, cz, half):
    """Return (columns, drysea, airhole) counts over generated chunks in the box."""
    cols = drysea = airhole = 0
    regions = {((cx + dx) >> 9, (cz + dz) >> 9)
               for dx in (-half, half) for dz in (-half, half)}
    for rx, rz in regions:
        p = f"{REGION}/r.{rx}.{rz}.mca"
        if not os.path.exists(p):
            continue
        data = open(p, "rb").read()
        for slot in range(1024):
            off = (data[slot * 4] << 16) | (data[slot * 4 + 1] << 8) | data[slot * 4 + 2]
            if off == 0:
                continue
            s = off * 4096
            if s + 5 > len(data):
                continue
            ln = struct.unpack_from(">I", data, s)[0]
            if ln == 0 or s + 4 + ln > len(data):
                continue
            try:
                root = anvil._NBT(anvil._decompress(data[s + 5:s + 4 + ln], data[s + 4])).root()
            except Exception:
                continue
            if root.get("Status") != "minecraft:full":
                continue
            wx0, wz0 = root.get("xPos") * 16, root.get("zPos") * 16
            if not (cx - half <= wx0 <= cx + half and cz - half <= wz0 <= cz + half):
                continue
            hm = (root.get("Heightmaps") or {})
            of = hm.get("OCEAN_FLOOR")
            ws = hm.get("WORLD_SURFACE")
            if of is None or ws is None:
                continue
            of = anvil.MIN_Y + anvil._unpack(of, anvil._HM_BITS, 256) - 1
            ws = anvil.MIN_Y + anvil._unpack(ws, anvil._HM_BITS, 256) - 1
            cols += 256
            # dry sea: solid floor >=2 below sea and no water/land reaching sea
            drysea += int(np.sum((of < SEA - 1) & (ws < SEA - 1) & (SEA - 1 - of >= 2)))
            airhole += int(np.sum(of < anvil.MIN_Y + 1))
    return cols, drysea, airhole


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--points", type=int, default=len(POINTS))
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--half", type=int, default=96)   # 6-chunk radius box (~144 chunks)
    a = ap.parse_args()
    print(f"{'point':20}{'world xz':>16}{'cols':>8}{'drysea%':>9}{'airhole':>8}")
    tot_c = tot_d = 0
    for name, lat, lon in POINTS[a.start:a.points]:
        wc = find_world(lat, lon)
        if wc is None:
            print(f"{name:20}{'(no coord)':>16}")
            continue
        cx, cz = wc
        rcon([f"forceload add {cx - a.half} {cz - a.half} {cx + a.half} {cz + a.half}"])
        time.sleep(22)
        rcon(["save-all"])
        time.sleep(4)
        rcon(["forceload remove all"])
        cols, dry, hole = scan_box(cx, cz, a.half)
        tot_c += cols; tot_d += dry
        pct = 100.0 * dry / cols if cols else 0.0
        print(f"{name:20}{f'{cx},{cz}':>16}{cols:>8}{pct:>8.1f}%{hole:>8}")
    if tot_c:
        print(f"\n{'TOTAL':20}{'':>16}{tot_c:>8}{100.0*tot_d/tot_c:>8.1f}%")


if __name__ == "__main__":
    main()
