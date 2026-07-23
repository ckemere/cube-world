"""Validate the placement port against the running server's own `locate
structure`: every village/temple the game reports must be one of our computed
candidate chunks (our candidates are the un-biome-filtered superset, so real
structures MUST be a subset). Run: cd tools/playermap && python3 -m
structures.validate_placement"""

import os
import re
import socket
import struct

from .placement import RandomSpread, LINEAR

RCON_HOST = os.environ.get("RCON_HOST", "127.0.0.1")
RCON_PORT = int(os.environ.get("RCON_PORT", "25575"))
RCON_PW = os.environ.get("RCON_PW", "cubeworld-dev")


def _recvn(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            break
        b += c
    return b


def rcon(cmds):
    with socket.create_connection((RCON_HOST, RCON_PORT), timeout=15) as s:
        def send(t, body):
            pkt = struct.pack("<ii", 7, t) + body.encode() + b"\x00\x00"
            s.sendall(struct.pack("<i", len(pkt)) + pkt)

        def recv():
            ln = struct.unpack("<i", _recvn(s, 4))[0]
            return _recvn(s, ln)[8:-2].decode(errors="replace")

        send(3, RCON_PW)
        recv()
        out = []
        for c in cmds:
            send(2, c)
            out.append(recv())
        return out


def read_seed():
    p = os.path.join(os.path.dirname(__file__), "..", "..", "..", "run", "server.properties")
    try:
        for line in open(p):
            if line.startswith("level-seed="):
                return int(line.split("=", 1)[1].strip())
    except Exception:
        pass
    return int(rcon(["seed"])[0].split("[")[1].split("]")[0])


LOCATE = re.compile(r"is at \[(-?\d+), ~?, (-?\d+)\]")


def locate(struct_id, x, z):
    r = rcon([f"execute in minecraft:overworld positioned {x} 0 {z} "
              f"run locate structure {struct_id}"])[0]
    m = LOCATE.search(r)
    return (int(m.group(1)), int(m.group(2))) if m else None


def check(name, struct_id, placement, seed, probes):
    found = set()
    for (px, pz) in probes:
        loc = locate(struct_id, px, pz)
        if loc:
            found.add((loc[0] >> 4, loc[1] >> 4))
    if not found:
        print(f"  {name}: no structures located near probes (skip)")
        return
    # candidate box covering all found chunks, padded
    cxs = [c[0] for c in found]
    czs = [c[1] for c in found]
    cand = set(placement.candidates_in_chunk_box(
        seed, min(cxs) - 2, min(czs) - 2, max(cxs) + 2, max(czs) + 2))
    ok = sum(1 for c in found if c in cand)
    bad = [c for c in found if c not in cand]
    status = "OK" if not bad else "MISMATCH"
    print(f"  {name}: {ok}/{len(found)} located structures matched candidates  [{status}]")
    for c in bad[:5]:
        print(f"      located chunk {c} NOT in candidate set")


def main():
    seed = read_seed()
    print(f"seed = {seed}")
    # a grid of probe positions across the generated area near spawn (Ethiopia)
    probes = [(x, z) for x in range(6000, 11000, 800) for z in range(-4000, 1200, 800)]
    print(f"probing {len(probes)} positions...")
    check("village", "minecraft:village", RandomSpread(34, 8, 10387312, LINEAR), seed, probes)
    check("desert_pyramid", "minecraft:desert_pyramid",
          RandomSpread(32, 8, 14357617, LINEAR), seed, probes)
    check("pillager_outpost", "minecraft:pillager_outpost",
          RandomSpread(32, 8, 165745296, LINEAR), seed, probes)


if __name__ == "__main__":
    main()
