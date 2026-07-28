#!/usr/bin/env python3
"""Score terrain from REAL generated chunks, not from the density emulator.

The in-game `/cubeworld evaluate` harness locates the surface by evaluating
`finalDensity` and emulating vanilla's 4x8x4 cell lattice. That is accurate
enough to compare two runs of the SAME generator configuration (median +0.38
blocks, 96% within 2), but it degrades badly when the configuration itself
changes the shape of the density field -- under the leaf hook, agreement with
real chunks falls to 31% within 1 block. So it cannot be used to decide whether
the leaf hook is an improvement.

This does the slow, honest thing: wipe the region, generate real chunks under
each configuration, and read the heightmaps back off disk. `target` still comes
from the plugin (MapSampler height, no density involved), so the comparison is
between what the Earth data asked for and what the world actually contains.

    python3 tools/realscore.py --label baseline
    python3 tools/realscore.py --label leafhook -- -Dcubeworld.leafHook=true
"""
from __future__ import annotations
import argparse, os, socket, struct, subprocess, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "playermap"))
import anvil  # noqa: E402
import numpy as np  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGION = os.path.join(ROOT, "run/world/dimensions/minecraft/overworld/region")
SEA = 62

# (name, centre x, centre z) -- one coast, one lowland, one mountain.
AREAS = [
    ("Indonesia", 21199, 181),
    ("Sahel", 8576, -1342),
    ("Everest", 18299, -2957),
]
HALF = 64          # blocks either side of centre to generate
DUMP_SIDE = 20     # surfacedump grid
DUMP_STRIDE = 6


def rcon(cmds, timeout=120):
    s = socket.create_connection(("127.0.0.1", 25575), timeout=timeout)

    def pkt(rid, typ, body):
        p = struct.pack("<ii", rid, typ) + body.encode() + b"\x00\x00"
        return struct.pack("<i", len(p)) + p

    def read():
        raw = s.recv(4)
        n = struct.unpack("<i", raw)[0]
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


def server_up():
    try:
        rcon(["list"], timeout=5)
        return True
    except Exception:
        return False


def stop_server():
    if server_up():
        try:
            rcon(["stop"])
        except Exception:
            pass
    for _ in range(60):
        if not server_up():
            return
        time.sleep(2)


def start_server(flags):
    subprocess.run(["tmux", "kill-session", "-t", "cubeworld"],
                   capture_output=True)
    subprocess.run(["tmux", "new-session", "-d", "-s", "cubeworld",
                    "-x", "200", "-y", "50"], check=True)
    cmd = f"cd {ROOT} && ./run-server.sh {' '.join(flags)} 2>&1 | tee /tmp/realscore.log; exec bash"
    subprocess.run(["tmux", "send-keys", "-t", "cubeworld", cmd, "Enter"], check=True)
    for _ in range(90):
        time.sleep(2)
        if server_up():
            time.sleep(4)
            return
    raise SystemExit("server did not come up")


def wipe_regions():
    """Remove the region files covering the test areas so chunks regenerate."""
    if not os.path.isdir(REGION):
        return
    want = set()
    for _, cx, cz in AREAS:
        for dx in (-HALF, HALF):
            for dz in (-HALF, HALF):
                want.add(((cx + dx) >> 9, (cz + dz) >> 9))
    for rx, rz in want:
        for sub in ("region", "entities", "poi"):
            p = os.path.join(ROOT, "run/world/dimensions/minecraft/overworld",
                             sub, f"r.{rx}.{rz}.mca")
            if os.path.exists(p):
                os.remove(p)


def read_heights():
    hm = {}
    for f in os.listdir(REGION):
        if not f.endswith(".mca"):
            continue
        data = open(os.path.join(REGION, f), "rb").read()
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
            surf = (root.get("Heightmaps") or {}).get("OCEAN_FLOOR")
            if surf is None:
                continue
            hm[(root.get("xPos"), root.get("zPos"))] = (
                anvil.MIN_Y + anvil._unpack(surf, anvil._HM_BITS, 256) - 1).reshape(16, 16)
    return hm


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("flags", nargs="*", help="JVM flags after --")
    a = ap.parse_args()

    stop_server()
    wipe_regions()
    start_server(a.flags)

    # generate
    for _, cx, cz in AREAS:
        rcon([f"forceload add {cx-HALF} {cz-HALF} {cx+HALF} {cz+HALF}"])
    time.sleep(55)
    rcon(["save-all", "forceload remove all"])
    time.sleep(5)

    # target heights, straight from MapSampler (no density involved)
    targets = {}
    for name, cx, cz in AREAS:
        out = rcon([f"cubeworld surfacedump {cx} {cz} {DUMP_SIDE} {DUMP_STRIDE}"])[0]
        for line in out.split("\n"):
            p = line.replace("§f", "").split()
            if len(p) == 5 and p[0] == "P":
                targets[(int(p[1]), int(p[2]))] = (name, float(p[4]))

    hm = read_heights()
    per = {}
    for (x, z), (name, tgt) in targets.items():
        c = hm.get((x >> 4, z >> 4))
        if c is None:
            continue
        real = int(c[z & 15, x & 15])
        per.setdefault(name, []).append((real, tgt))

    print(f"\n===== REAL-CHUNK SCORE: {a.label}  flags={' '.join(a.flags) or '(none)'} =====")
    allrows = []
    for name, rows in per.items():
        r = np.array([v[0] for v in rows], float)
        t = np.array([v[1] for v in rows], float)
        allrows += rows
        land = t >= SEA + 1
        drown = np.mean((land) & (r < SEA + 1)) * 100 if land.any() else 0
        spur = np.mean((~land) & (r >= SEA + 1)) * 100 if (~land).any() else 0
        print(f"  {name:<11} n={len(r):4d}  rmse {np.sqrt(((r-t)**2).mean()):6.2f}  "
              f"bias {(r-t).mean():+6.2f}  drown {drown:5.1f}%  spurland {spur:5.1f}%")
    r = np.array([v[0] for v in allrows], float)
    t = np.array([v[1] for v in allrows], float)
    land = t >= SEA + 1
    print(f"  {'TOTAL':<11} n={len(r):4d}  rmse {np.sqrt(((r-t)**2).mean()):6.2f}  "
          f"bias {(r-t).mean():+6.2f}  "
          f"drown {np.mean(land & (r < SEA+1))*100:5.1f}%  "
          f"spurland {np.mean((~land) & (r >= SEA+1))*100:5.1f}%")


if __name__ == "__main__":
    main()
