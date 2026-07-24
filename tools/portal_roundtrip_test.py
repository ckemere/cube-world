#!/usr/bin/env python3
"""Portal round-trip regression test, driven through a live azalea bot.

Chases the "nether->overworld makes a NEW portal" bug: builds+lights an overworld
nether portal beside the bot, walks the bot through to the nether, then back, and
checks that the return REUSES the original overworld portal instead of creating a
duplicate. Passes iff (a) the bot lands back on the original portal and (b) the
overworld portal-block count in the original footprint is unchanged.

Prereqs:
  - Dev server up with RCON on 25575 (password cubeworld-dev).
  - The azalea bot online as player PortalTester, in the overworld
    (cd bot && PATH="$HOME/.cargo/bin:$PATH" cargo run).

Why tp instead of pathfinding: a server-side `tp` into a portal block triggers the
exact same PlayerPortalEvent -> PortalLinkListener code path as walking in, and is
far more deterministic. Portal travel needs a fresh out->in entry transition after
each dimension change (continuous occupancy alone won't re-fire), so every leg
stages the bot just outside the portal, then steps it in.
"""
import socket
import struct
import sys
import time

HOST, PORT, PW = "127.0.0.1", 25575, "cubeworld-dev"
BOT = "PortalTester"


class Rcon:
    def __init__(self):
        self.s = socket.create_connection((HOST, PORT))
        self._send(1, 3, PW)
        self._recv()

    def _send(self, pid, ptype, body):
        d = struct.pack("<ii", pid, ptype) + body.encode() + b"\x00\x00"
        self.s.sendall(struct.pack("<i", len(d)) + d)

    def _recv(self):
        ln = struct.unpack("<i", self.s.recv(4))[0]
        d = b""
        while len(d) < ln:
            d += self.s.recv(ln - len(d))
        return d[8:-2].decode("utf8", "ignore")

    def cmd(self, c):
        self._send(2, 2, c)
        return self._recv()


def pos(r, dim=None):
    c = f"execute in minecraft:{dim} run " if dim else ""
    out = r.cmd(c + f"data get entity {BOT} Pos")
    nums = out[out.find("[") + 1: out.find("]")].split(",")
    return tuple(float(n.strip().rstrip("d")) for n in nums)


def dimension(r):
    out = r.cmd(f"data get entity {BOT} Dimension")
    i = out.find('"')
    return out[i + 1: out.find('"', i + 1)]


def poll_dim(r, want, timeout=30):
    for _ in range(timeout):
        if dimension(r) == want:
            return True
        time.sleep(1)
    return False


def portal_blocks(r, dim, x0, y0, z0, x1, y1, z1):
    hits = []
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0, y1 + 1):
                if "passed" in r.cmd(
                        f"execute in minecraft:{dim} if block {x} {y} {z} minecraft:nether_portal"):
                    hits.append((x, y, z))
    return hits


def find_portal(r, dim, cx, cy, cz, rad=7):
    return portal_blocks(r, dim, cx - rad, cy - rad, cz - rad, cx + rad, cy + rad, cz + rad)


def build_overworld_portal(r, px, py, pz):
    """A 2x3 portal in the X-Y plane at Z=pz, interior columns px..px+1, rows py..py+2."""
    r.cmd(f"forceload add {px-14} {pz-6} {px+16} {pz+6}")
    r.cmd(f"fill {px-2} {py-2} {pz-3} {px+3} {py+5} {pz+3} air")
    r.cmd(f"fill {px-2} {py-2} {pz-3} {px+3} {py-2} {pz+3} obsidian")          # floor
    r.cmd(f"fill {px-1} {py-1} {pz} {px+2} {py-1} {pz} obsidian")              # frame bottom
    r.cmd(f"fill {px-1} {py+3} {pz} {px+2} {py+3} {pz} obsidian")              # frame top
    r.cmd(f"fill {px-1} {py} {pz} {px-1} {py+2} {pz} obsidian")                # left
    r.cmd(f"fill {px+2} {py} {pz} {px+2} {py+2} {pz} obsidian")               # right
    r.cmd(f"setblock {px} {py-1} {pz} fire")
    r.cmd(f"setblock {px+1} {py-1} {pz} fire")


def step_through(r, dim, in_x, in_y, in_z, out_dz, want_dim, timeout):
    """Stage just outside the portal (in_z + out_dz), then step in; poll for the
    dimension change. out->in transition is what re-fires the portal after a prior
    dimension change/cooldown."""
    r.cmd(f"execute in minecraft:{dim} run tp {BOT} {in_x} {in_y} {in_z + out_dz}")
    time.sleep(2)
    r.cmd(f"execute in minecraft:{dim} run tp {BOT} {in_x} {in_y} {in_z}")
    return poll_dim(r, want_dim, timeout)


def obliterate(r, dim, x0, y0, z0, x1, y1, z1):
    """Clear an obsidian/portal box back to air so cases don't contaminate later
    ones — a leftover portal near another case's return point would steal the link
    and produce a false FAIL."""
    r.cmd(f"execute in minecraft:{dim} run fill {x0} {y0} {z0} {x1} {y1} {z1} air")


def main():
    keep = "--keep" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    r = Rcon()
    if BOT not in r.cmd("list"):
        sys.exit(f"FAIL: {BOT} is not online — start the azalea bot first.")
    if dimension(r) != "minecraft:overworld":
        sys.exit("FAIL: bot must start in the overworld.")
    r.cmd(f"gamemode survival {BOT}")

    # Portal location. With `X Y Z` args, build there (self-contained obsidian
    # box + platform, so no natural terrain is needed) — lets us sweep seams and
    # other faces. Otherwise build beside wherever the bot currently stands.
    if len(args) >= 3:
        px, py, pz = (int(a) for a in args[:3])
    else:
        bx, by, bz = (round(v) for v in pos(r))
        px, py, pz = bx + 4, by, bz
    cx, cy, cz = px + 0.5, py, pz + 0.5      # a portal-block center to stand in

    cleanup = []   # (dim, box) regions to clear in finally, so runs don't interfere
    verdict_pass = False
    try:
        build_overworld_portal(r, px, py, pz)
        cleanup.append(("overworld", (px - 2, py - 2, pz - 3, px + 3, py + 5, pz + 3)))
        if not find_portal(r, "overworld", px, py, pz, rad=3):
            sys.exit("FAIL: overworld portal did not light.")

        base = portal_blocks(r, "overworld", px - 2, py - 1, pz - 1, px + 3, py + 4, pz + 1)
        print(f"[test] overworld portal lit at ({px},{py},{pz}); baseline {len(base)} portal blocks")

        # --- forward leg ---
        if not step_through(r, "overworld", cx, py, cz, out_dz=-3, want_dim="minecraft:the_nether", timeout=20):
            sys.exit("FAIL: bot did not travel overworld -> nether.")
        nx, ny, nz = pos(r, "the_nether")
        print(f"[test] reached nether at ({nx:.1f}, {ny:.1f}, {nz:.1f})")

        np = find_portal(r, "the_nether", round(nx), round(ny), round(nz), rad=6)
        if not np:
            sys.exit("FAIL: no nether portal found at landing.")
        npx = min(h[0] for h in np); npz = min(h[2] for h in np); npy = min(h[1] for h in np)
        r.cmd(f"execute in minecraft:the_nether run fill {npx-4} {npy-1} {npz-4} {npx+5} {npy-1} {npz+4} obsidian")
        cleanup.append(("the_nether", (npx - 4, npy - 1, npz - 4, npx + 5, npy + 4, npz + 4)))
        print(f"[test] nether portal at x~{npx} y~{npy} z~{npz}")

        # --- return leg ---
        if not step_through(r, "the_nether", npx + 0.5, npy, npz + 0.5, out_dz=3.0,
                            want_dim="minecraft:overworld", timeout=20):
            sys.exit("FAIL: bot did not travel nether -> overworld.")
        rx, ry, rz = pos(r, "overworld")
        print(f"[test] returned to overworld at ({rx:.1f}, {ry:.1f}, {rz:.1f})")
        # any duplicate portal is created at the return point, so clear there too
        cleanup.append(("overworld", (round(rx) - 3, round(ry) - 2, round(rz) - 3,
                                       round(rx) + 3, round(ry) + 5, round(rz) + 3)))

        # --- verdict ---
        after = portal_blocks(r, "overworld", px - 2, py - 1, pz - 1, px + 3, py + 4, pz + 1)
        off = ((rx - cx) ** 2 + (rz - cz) ** 2) ** 0.5     # how far the return landed
        reused = abs(rx - cx) <= 2 and abs(rz - cz) <= 2
        no_dupe = len(after) == len(base)
        print(f"[test] return offset from original: {off:.1f} blocks (search radius 160)")
        print(f"[test] return-on-original={reused}  portal-count {len(base)}->{len(after)}")
        verdict_pass = reused and no_dupe
    finally:
        if not keep:
            for dim, box in cleanup:
                obliterate(r, dim, *box)
        r.cmd("forceload remove all")
        r.cmd("execute in minecraft:the_nether run forceload remove all")

    if verdict_pass:
        print("PASS: nether->overworld reused the original portal; no duplicate created.")
    else:
        print("FAIL: return did not reuse the original portal (duplicate likely created).")
        sys.exit(1)


if __name__ == "__main__":
    main()
