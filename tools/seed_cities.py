#!/usr/bin/env python3
"""Seed the 30 teleport city stations on a freshly (re)generated world.

City teleport stations build lazily when their chunk first loads, and the ring
network only offers destinations once stations are registered — so a brand-new
world shows "The network is still forming" until the cities are visited. This
script force-loads every city's chunk over RCON so all 30 stations build and the
two rings fill (30/30), then saves and clears the force-loads.

Prereqs: the server is running (./run-server.sh) AND the cities are ANCHORED —
on a fresh world that means the SECOND boot (the first boot writes the cities
datapack; watch for "Village anchor: 30 cities anchored" in the log).

Usage:  python3 tools/seed_cities.py
Env:    RCON_HOST/RCON_PORT/RCON_PW override 127.0.0.1/25575/cubeworld-dev.
"""
import os
import socket
import struct
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANCHORS = os.path.join(ROOT, "src", "main", "resources", "cities_anchor.csv")
STATIONS = os.path.join(ROOT, "run", "plugins", "CubeWorld", "stations.csv")
HOST = os.environ.get("RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("RCON_PORT", "25575"))
PW = os.environ.get("RCON_PW", "cubeworld-dev")


def _recvn(s, n):
    d = b""
    while len(d) < n:
        c = s.recv(n - len(d))
        if not c:
            raise EOFError("rcon socket closed")
        d += c
    return d


def _pkt(i, t, body):
    b = struct.pack("<ii", i, t) + body.encode() + b"\x00\x00"
    return struct.pack("<i", len(b)) + b            # NB: the length prefix is required


def rcon(cmds, timeout=60):
    """Run commands on one fresh authed connection; return their reply strings."""
    s = socket.create_connection((HOST, PORT), timeout=timeout)
    try:
        s.sendall(_pkt(1, 3, PW))
        _recvn(s, struct.unpack("<i", _recvn(s, 4))[0])       # auth reply
        out = []
        for c in cmds:
            s.sendall(_pkt(2, 2, c))
            out.append(_recvn(s, struct.unpack("<i", _recvn(s, 4))[0])[8:-2].decode(errors="replace"))
        return out
    finally:
        s.close()


def built_count():
    try:
        with open(STATIONS, encoding="utf-8") as f:
            return sum(1 for ln in f if ln.strip() and not ln.startswith("#"))
    except FileNotFoundError:
        return 0


def main():
    cities = []
    with open(ANCHORS, encoding="utf-8") as f:
        for ln in f:
            ln = ln.strip()
            if not ln or ln.startswith("#"):
                continue
            p = ln.split(",")
            cities.append((int(p[0]), int(p[1]), p[4]))
    print(f"seeding {len(cities)} city stations via RCON {HOST}:{PORT} ...")

    # Force-load each city's chunk (+ a small box so buildCityStation's house
    # search area is present). Small batches with retry survive the RCON drops
    # that mass chunk-gen lag causes.
    for i in range(0, len(cities), 5):
        grp = cities[i:i + 5]
        cmds = [f"forceload add {x - 32} {z - 32} {x + 32} {z + 32}" for x, z, _ in grp]
        for attempt in range(3):
            try:
                rcon(cmds, timeout=90)
                break
            except Exception as e:
                print(f"  batch {i // 5} retry ({e})")
                time.sleep(1)
        print(f"  force-loaded {min(i + 5, len(cities))}/{len(cities)}")

    # Wait for the stations to actually build (chunk-load events fire on ticks).
    deadline = time.time() + 300
    while time.time() < deadline:
        n = built_count()
        print(f"  stations built: {n}/{len(cities)}")
        if n >= len(cities):
            break
        time.sleep(6)

    rcon(["save-all", "forceload remove all"], timeout=60)
    n = built_count()
    print(f"done: {n}/{len(cities)} stations built, saved, force-loads cleared.")
    sys.exit(0 if n >= len(cities) else 1)


if __name__ == "__main__":
    main()
