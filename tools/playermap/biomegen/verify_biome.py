#!/usr/bin/env python3
"""Acceptance test for biome_at.py: the offline Python port vs. the server.

Two sources of ground truth, both of which are "what the server says":

``--source region`` (default, and the safe one)
    Read the biomes the server actually wrote into ``run/world/.../region/*.mca``.
    Biomes live on a 4x4x4 lattice inside each section, and each lattice cell is
    exactly one call to the Bukkit ``BiomeProvider`` (see below), so a region
    file is a free, arbitrarily large sample of the same function ``--source
    rcon`` queries one chunk at a time.  Costs the server nothing.

``--source rcon``
    ``/cubeworld biomeat <x> <z> [y]``, which calls ``world.getBiome`` and
    therefore LOADS AND GENERATES CHUNKS.  Each query drags in a whole
    generation neighbourhood; a few hundred of them can exhaust the server heap.
    Keep ``--n`` small and the batches slow.

Why the quart rounding matters either way: ``CraftRegionAccessor.getBiome``
does ``getNoiseBiome(x>>2, y>>2, z>>2)`` and ``CustomWorldChunkManager``
forwards ``QuartPos.toBlock`` of those to the plugin, so the only coordinates
the provider is ever asked about are multiples of 4.  Both modes compare at
those coordinates.

    python3 tools/playermap/biomegen/verify_biome.py --n 20000
    python3 tools/playermap/biomegen/verify_biome.py --source rcon --n 120
"""

from __future__ import annotations

import argparse
import glob
import os
import random
import sys
import time
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import anvil  # noqa: E402
from biomegen.biome_at import (  # noqa: E402
    FACES, FACE_SIZE, REPO, _default, _java_round, server_biome_at)

REGION_DIR = os.path.join(
    REPO, "run", "world", "dimensions", "minecraft", "overworld", "region")

DEFAULT_RCON = os.environ.get(
    "CUBEWORLD_RCON",
    "/tmp/claude-1000/-home-cubecraft-projects-cube-world/"
    "9d1f180f-32dd-4ba3-aba4-7a95cebe8caf/scratchpad/rcon.py")


def _load_rcon(path):
    import importlib.util
    spec = importlib.util.spec_from_file_location("rcon_mod", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.rcon


def face_of(x, z):
    col = (x + FACE_SIZE // 2) // FACE_SIZE
    row = (z + FACE_SIZE // 2) // FACE_SIZE
    for name, grid in FACES.items():
        if grid == (col, row):
            return name
    return "OFF_NET"


# --------------------------------------------------------------------------
# region-file mode
# --------------------------------------------------------------------------

def verify_region(b, seed, n, rng, region_dir, surface_only, verbose_max):
    files = sorted(glob.glob(os.path.join(region_dir, "*.mca")))
    if not files:
        raise SystemExit("no region files under %s" % region_dir)
    rng.shuffle(files)
    agree = 0
    per_face = Counter()
    per_face_bad = Counter()
    seen = Counter()
    ties = 0
    tie_bad = 0
    disagree = []
    checked = 0
    for path in files:
        if checked >= n:
            break
        for cx, cz, root in anvil.read_region_chunks(path):
            if checked >= n:
                break
            biomes = anvil.chunk_biomes(root)
            if not biomes:
                continue
            # a handful of lattice cells per chunk, so the sample stays spread
            for _ in range(4):
                qx, qz = rng.randrange(4), rng.randrange(4)
                x = cx * 16 + qx * 4
                z = cz * 16 + qz * 4
                sy = _java_round(b.sampler.height_at(x + 0.5, z + 0.5))
                if surface_only:
                    y = (sy >> 2) << 2
                else:
                    y = ((sy - rng.choice([0, 0, 0, 8, 24, 60, 110])) >> 2) << 2
                    y = max(-64, y)
                got = anvil.biome_at_quart(biomes, y, qz, qx)
                if got is None:
                    continue
                mine, tied = b.biome_candidates_at(seed, x, y, z)
                face = face_of(x, z)
                per_face[face] += 1
                seen[got] += 1
                checked += 1
                if len(tied) > 1:
                    ties += 1
                if mine == got:
                    agree += 1
                else:
                    per_face_bad[face] += 1
                    tie_bad += len(tied) > 1
                    if len(disagree) < verbose_max:
                        disagree.append((face, x, y, z, got, mine, tied))
    print("vanilla-table ties: %d of %d cells (%.3f%%); %d of the %d disagreements"
          " are ties" % (ties, checked, 100.0 * ties / max(checked, 1),
                         tie_bad, sum(per_face_bad.values())))
    print("distinct biomes in the sample: %d   most common: %s"
          % (len(seen), ", ".join("%s x%d" % (k.split(":")[1], v)
                                  for k, v in seen.most_common(8))))
    return agree, checked, disagree, per_face, per_face_bad


# --------------------------------------------------------------------------
# rcon mode
# --------------------------------------------------------------------------

def verify_rcon(b, seed, n, rng, args):
    rcon = _load_rcon(args.rcon)
    print("server: %s" % rcon(["list"])[0].strip())
    pts = []
    per = max(1, (n - args.off_net) // len(FACES))
    for face, (col, row) in FACES.items():
        x0 = col * FACE_SIZE - FACE_SIZE // 2
        z0 = row * FACE_SIZE - FACE_SIZE // 2
        for _ in range(per):
            pts.append((x0 + rng.randrange(FACE_SIZE), z0 + rng.randrange(FACE_SIZE)))
    for _ in range(args.off_net):
        pts.append((rng.randrange(-25000, -15500), rng.randrange(-25000, -15500)))
    rng.shuffle(pts)
    pts = pts[:n]

    jobs = []
    for x, z in pts:
        sy = _java_round(b.sampler.height_at(x + 0.5, z + 0.5))
        if rng.random() < args.deep_frac:
            y = max(-60, sy - rng.choice([20, 45, 90, 150]))
            jobs.append((x, y, z, "cubeworld biomeat %d %d %d" % (x, z, y)))
        else:
            jobs.append((x, sy, z, "cubeworld biomeat %d %d" % (x, z)))

    agree = 0
    per_face = Counter()
    per_face_bad = Counter()
    disagree = []
    checked = 0
    for i in range(0, len(jobs), args.batch):
        batch = jobs[i:i + args.batch]
        try:
            replies = rcon([j[3] for j in batch])
        except Exception as e:                       # keep partial results
            print("!! rcon failed after %d queries: %s" % (checked, e))
            break
        for (x, y, z, cmd), rep in zip(batch, replies):
            rep = rep.replace("\xa7b", "").replace("\xa7c", "").strip()
            if "): " not in rep:
                print("!! unparseable reply for %r: %r" % (cmd, rep))
                continue
            got = rep.split("): ", 1)[1].strip()
            mine = server_biome_at(seed, x, y, z)
            face = face_of(x, z)
            per_face[face] += 1
            checked += 1
            if mine == got:
                agree += 1
            else:
                per_face_bad[face] += 1
                disagree.append((face, (x >> 2) << 2, (y >> 2) << 2,
                                 (z >> 2) << 2, got, mine, []))
        print("  %d/%d  agree=%d" % (checked, len(jobs), agree), flush=True)
        time.sleep(args.sleep)
    return agree, checked, disagree, per_face, per_face_bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", choices=["region", "rcon"], default="region")
    ap.add_argument("--n", type=int, default=20000, help="number of comparisons")
    ap.add_argument("--seed-rng", type=int, default=1)
    ap.add_argument("--world-seed", type=int, default=None,
                    help="world seed (default: read run/world/level.dat, else ask rcon)")
    ap.add_argument("--region-dir", default=REGION_DIR)
    ap.add_argument("--surface-only", action="store_true",
                    help="region mode: only sample the surface lattice cell")
    ap.add_argument("--verbose-max", type=int, default=40,
                    help="how many individual disagreements to print")
    # rcon-only knobs
    ap.add_argument("--batch", type=int, default=25)
    ap.add_argument("--sleep", type=float, default=1.5)
    ap.add_argument("--deep-frac", type=float, default=0.2)
    ap.add_argument("--off-net", type=int, default=4)
    ap.add_argument("--rcon", default=DEFAULT_RCON)
    args = ap.parse_args()

    if args.world_seed is None:
        args.world_seed = _load_rcon(args.rcon)(["seed"])[0].split("[")[1].split("]")[0]
        args.world_seed = int(args.world_seed)
    print("world seed: %d" % args.world_seed)

    b = _default()
    rng = random.Random(args.seed_rng)
    t0 = time.time()
    if args.source == "region":
        agree, checked, disagree, per_face, per_face_bad = verify_region(
            b, args.world_seed, args.n, rng, args.region_dir,
            args.surface_only, args.verbose_max)
    else:
        agree, checked, disagree, per_face, per_face_bad = verify_rcon(
            b, args.world_seed, args.n, rng, args)

    print()
    print("=" * 74)
    print("source=%s   compared %d points in %.1fs" % (args.source, checked, time.time() - t0))
    print("AGREEMENT: %d/%d = %.3f%%" % (agree, checked, 100.0 * agree / max(checked, 1)))
    print()
    print("per face:")
    for face in list(FACES) + ["OFF_NET"]:
        if per_face[face]:
            bad = per_face_bad[face]
            print("  %-11s %6d  disagree %5d  (%.3f%% agree)"
                  % (face, per_face[face], bad,
                     100.0 * (per_face[face] - bad) / per_face[face]))
    if disagree:
        print("\ndisagreements (up to %d shown):" % args.verbose_max)
        pairs = Counter()
        for face, x, y, z, got, mine, tied in disagree:
            pairs[(got, mine)] += 1
            c = b.climate_at(args.world_seed, x, z, y)
            print("  %-11s x=%-8d y=%-5d z=%-8d server=%-30s python=%s"
                  % (face, x, y, z, got, mine))
            if c:
                print("      T=%+.4f H=%+.4f C=%+.4f E=%+.4f D=%+.4f W=%+.4f "
                      "| elev=%.0f temp=%.1f precip=%.0f relief=%.0f"
                      % (c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9]))
            if len(tied) > 1:
                print("      EXACT TIE in vanilla's metric between: %s"
                      % ", ".join(tied))
        print("\nmost common (server -> python) pairs among shown disagreements:")
        for (got, mine), k in pairs.most_common(15):
            print("  %5d  %-32s -> %s" % (k, got, mine))
    return 0 if not disagree else 1


if __name__ == "__main__":
    sys.exit(main())
