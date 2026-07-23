"""Turn a world seed into structure-overlay markers, data-driven from
placement_data.json + structure_biomes.json (extracted from the vanilla jar):
enumerate RandomSpread candidates across the cube net, drop those the
generator's gate rejects, biome-filter with the exported raster, apply any
frequency/exclusion reduction, and emit a marker per survivor. Cached per seed."""

import json
import math
import os

from . import cubegate
from .biomeraster import load as load_raster
from .placement import RandomSpread
from . import frequency

_DIR = os.path.dirname(__file__)
PLACEMENT_DATA = json.load(open(os.path.join(_DIR, "placement_data.json")))
STRUCTURE_BIOMES = json.load(open(os.path.join(_DIR, "structure_biomes.json")))

NETHER_SETS = {"nether_complexes", "nether_fossils"}
SKIP_SETS = {"end_cities", "mineshafts"}          # End dimension / too dense to map

_rasters = {}     # dimension -> BiomeRaster
_cache = {}       # (seed, dimension) -> {set: [markers]}


def types_for(dimension):
    if dimension == "nether":
        return sorted(NETHER_SETS & set(PLACEMENT_DATA))
    return sorted(n for n in PLACEMENT_DATA if n not in NETHER_SETS and n not in SKIP_SETS)


def _placement(name):
    d = PLACEMENT_DATA[name]
    return RandomSpread(d["spacing"], d["separation"], d["salt"], d["spread"])


def raster(dimension="overworld"):
    if dimension not in _rasters:
        fname = "nether.cwbr" if dimension == "nether" else "overworld.cwbr"
        path = os.path.join(_DIR, "..", "..", "..", "run", "plugins", "CubeWorld",
                            "biomes", fname)
        _rasters[dimension] = load_raster(path)
    return _rasters[dimension]


def _net_chunk_box():
    cols = [c for c, _ in cubegate.GRID.values()]
    rows = [r for _, r in cubegate.GRID.values()]
    return (math.floor((min(cols) * cubegate.FACE - cubegate.H) / 16),
            math.floor((min(rows) * cubegate.FACE - cubegate.H) / 16),
            math.ceil((max(cols) * cubegate.FACE + cubegate.H) / 16),
            math.ceil((max(rows) * cubegate.FACE + cubegate.H) / 16))


def compute_overlays(seed, dimension="overworld", types=None):
    key = (seed, dimension)
    if key in _cache and types is None:
        return _cache[key]
    try:
        r = raster(dimension)
    except FileNotFoundError:
        return {}
    cx0, cz0, cx1, cz1 = _net_chunk_box()
    want = set(types) if types else set(types_for(dimension))
    out = {}
    for name in types_for(dimension):
        if name not in want:
            continue
        d = PLACEMENT_DATA[name]
        placement = _placement(name)
        allowed = set(STRUCTURE_BIOMES.get(name, []))
        freq = d.get("frequency")
        excl = d.get("exclusion")
        excl_chunks = None
        if excl:
            excl_chunks = frequency.exclusion_chunks(seed, _placement(excl["other"]),
                                                     cx0, cz0, cx1, cz1)
        # Tight spacing => most/every chunk is a candidate; enumerate the (often
        # sparse) biome-matching chunks instead of the whole net (buried treasure
        # on beaches, nether fossils in soul-sand valleys).
        if placement.spacing <= 2 and allowed:
            sp = placement.spacing
            candidates = ((cx, cz) for (cx, cz) in r.chunks_with_biomes(allowed)
                          if placement.target_chunk(seed, cx // sp, cz // sp) == (cx, cz))
            biome_prechecked = True
        else:
            candidates = placement.candidates_in_chunk_box(seed, cx0, cz0, cx1, cz1)
            biome_prechecked = False
        markers = []
        for (cx, cz) in candidates:
            m = cubegate.marker(cx, cz)
            if m is None:
                continue
            if allowed and not biome_prechecked:
                b = r.biome_at_chunk(cx, cz)
                if b not in allowed:
                    continue
            if freq is not None and not frequency.keeps(seed, d["salt"], cx, cz,
                                                         freq, d["frequency_reduction_method"]):
                continue
            if excl_chunks is not None and frequency.excluded(cx, cz, excl_chunks,
                                                              excl["chunks"]):
                continue
            markers.append(m)
        out[name] = markers
    if types is None:
        _cache[key] = out
        while len(_cache) > 6:                     # bound memory across many seeds
            _cache.pop(next(iter(_cache)))
    return out


def precompute(seed, dimension="overworld"):
    """Warm the cache (call at server startup so the first page load is instant)."""
    try:
        compute_overlays(seed, dimension)
    except Exception:
        pass


if __name__ == "__main__":
    seed = 20260712
    try:
        for line in open(os.path.join(_DIR, "..", "..", "..", "run", "server.properties")):
            if line.startswith("level-seed="):
                seed = int(line.split("=", 1)[1].strip())
    except Exception:
        pass
    for dim in ("overworld",):
        ov = compute_overlays(seed, dim)
        print(f"seed {seed} / {dim}:")
        for name in sorted(ov):
            print(f"  {name:20s} {len(ov[name])}")
