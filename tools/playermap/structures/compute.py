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
from . import village_placement
from .placement import RandomSpread
from .javarandom import JavaRandom
from . import frequency

_DIR = os.path.dirname(__file__)
PLACEMENT_DATA = json.load(open(os.path.join(_DIR, "placement_data.json")))
STRUCTURE_BIOMES = json.load(open(os.path.join(_DIR, "structure_biomes.json")))
VILLAGE_POOLS = json.load(open(os.path.join(_DIR, "village_pools.json")))["variants"]

# biome -> village variant. The five has_structure/village_* tags are disjoint,
# so the biome alone decides which pool (and so which pool SIZE) is rolled.
_VILLAGE_VARIANT = {b: v for v, d in VILLAGE_POOLS.items() for b in d["biomes"]}

# Layers believed to be a SUPERSET of what the world contains, i.e. vanilla runs
# a check after the biome test that seed maths cannot replay.
#
#   woodland_mansions  REAL gate, read in the 26.2 sources:
#                      WoodlandMansionStructure.findGenerationPoint returns empty
#                      when getLowestYIn5by5BoxOffset7Blocks < y60. Measured 3
#                      predicted / 2 real. This one is genuine.
#
#   ancient_cities     UNVERIFIED. The stated mechanism -- "the jigsaw assembly
#                      declines to build" -- does not exist in the code: for a
#                      jigsaw with size > 0 the centre piece is always added, so
#                      StructureStart.isValid() cannot fail. The 21/30 precision
#                      is more likely the same sampling bug villages had:
#                      ancient_city sets start_jigsaw_name city_anchor, so its
#                      stub position is shifted by a randomly chosen named jigsaw
#                      block -- tens of blocks in X/Z and offset in Y -- while
#                      overworld_y-27.cwbr samples the chunk CORNER at exactly
#                      y=-27, the wrong cell on all three axes. Worth fixing the
#                      same way villages were before accepting it as a floor.
#
# Villages USED to be listed here with the note "the biome filter is not what
# fails". That was wrong, and it only ever measured the two layers above.
# Villages were failing precisely on the biome test -- at the wrong POSITION and
# then at the wrong RESOLUTION (see village_placement). They are now exact:
# P 1.000 / R 1.000 over all 2006 candidates against 349 real villages, and the
# model agrees with the engine field-by-field via `/cubeworld villagedebug`.
# They were never a superset -- they were wrong in both directions at roughly
# equal rates.
#
# The lesson, since this file misled a later session for hours: a confident
# comment here may be a previous run's hypothesis. Measure before trusting it.
SUPERSET_LAYERS = {"ancient_cities", "woodland_mansions"}

NETHER_SETS = {"nether_complexes", "nether_fossils"}
SKIP_SETS = {"end_cities", "mineshafts"}          # End dimension / too dense to map

# Derived overlays: not vanilla structure sets of their own, so they are not in
# placement_data.json, but they are things you actually want to find on a map.
DERIVED_SETS = ["zombie_villages", "end_portals"]

# Written by `/cubeworld strongholds`. Strongholds are the one overworld
# structure NOT placed by RandomSpread -- ours are folded onto the cube by
# StrongholdSphereHook, so no amount of seed maths here would find them. The
# plugin dumps the list the world actually uses instead.
STRONGHOLDS_JSON = os.path.join(_DIR, "..", "..", "..", "run", "plugins",
                                "CubeWorld", "strongholds.json")


def _village_biome_probe():
    """(biome_at(x,y,z), height_at(x,z)) from the Python port of the plugin's
    BiomeProvider, or (None, None) if its inputs are not on disk.

    Villages are the one layer whose answer needs a QUART-resolution biome: the
    town-centre centre lands wherever the rotation and template put it, and
    36.8% of chunks hold more than one biome across their 4x4 surface quart
    grid. Measured over all 2006 candidates on this cube against 349 real
    villages read out of the region files:

        chunk raster (biome_at_chunk)   355 predicted  P .941  R .957
        biomegen (this)                 349 predicted  P 1.000 R 1.000

    Every other layer keeps the raster: they test at the chunk the marker is
    drawn in, so chunk resolution is the right resolution for them.
    """
    try:
        from biomegen import biome_at as _ba      # sibling package, not a subpackage
        model = _ba._default()
    except (ImportError, OSError, ValueError):
        return None, None
    return (lambda s: (lambda x, y, z: model.biome_at(s, x, y, z))), \
           (lambda x, z: model.sampler.height_at(x + 0.5, z + 0.5))


def _end_portals():
    """One marker per stronghold: every stronghold contains exactly one end portal.

    Positions are deliberately DEGRADED to medium resolution here at the
    source — snapped to 128-block cell centers, carrying r=64 — so no exact
    End-portal coordinate ever reaches a browser (tooltips, marker JSON, or
    dev tools). The map narrows the search; the last 128 blocks are yours.
    """
    cell = 128
    try:
        with open(STRONGHOLDS_JSON, encoding="utf-8") as f:
            rows = json.load(f)
    except (OSError, ValueError):
        return []
    out = []
    for s in rows:
        try:
            qx = (s["x"] // cell) * cell + cell // 2
            qz = (s["z"] // cell) * cell + cell // 2
            fuv = cubegate.world_to_faceuv(qx, qz)
            if fuv is None:                    # cell center nudged off-net: keep
                fuv = (s["face"], s["u"], s["v"])   # the face, degrade less
            f2, u, v = fuv
            x, y, z = cubegate.cube_point(f2, u, v)
        except Exception:
            continue
        out.append({"face": f2, "u": u, "v": v, "p": [x, y, z],
                    "cx": qx >> 4, "cz": qz >> 4, "r": 64})
    return out


def is_zombie_village(seed, cx, cz, biome):
    """Would the village starting at this chunk be a zombie village?

    Vanilla decides this with the ordinary town-centre pick, not a special roll:
    each village pool holds ~2% zombie town centres by weight, and picking one
    swaps in the cobweb/mossy processor list for the whole settlement. So the
    answer is exactly reproducible from the seed [src, 26.2]:

        Structure.StructureStart.makeRandom: new WorldgenRandom(LegacyRandomSource(0))
                                             .setLargeFeatureSeed(seed, cx, cz)
        JigsawPlacement.addPieces:           Rotation.getRandom(random)   -> nextInt(4)
                                             pool.getRandomTemplate(random)
        StructureTemplatePool.getRandomTemplate: templates.get(random.nextInt(size))

    The rotation draw comes FIRST and must be consumed or every answer is wrong.
    Returns None where the biome hosts no village variant.
    """
    variant = _VILLAGE_VARIANT.get(biome)
    if variant is None:
        return None
    return is_zombie_variant(seed, cx, cz, variant)


def is_zombie_variant(seed, cx, cz, variant):
    """As `is_zombie_village`, but for a variant already resolved by
    village_placement (which knows which pool vanilla actually rolled)."""
    pool = VILLAGE_POOLS[variant]
    rnd = JavaRandom(0)
    rnd.set_large_feature_seed(seed, cx, cz)
    rnd.next_int(4)                                   # Rotation.getRandom
    return rnd.next_int(pool["total"]) >= pool["zombie_from"]

_rasters = {}     # dimension -> BiomeRaster
_cache = {}       # (seed, dimension) -> {set: [markers]}


def types_for(dimension):
    if dimension == "nether":
        # split nether_complexes -> fortresses/bastions; ruined_portals in the
        # nether is always ruined_portal_nether
        return ["bastions", "fortresses", "nether_fossils", "ruined_portals"]
    return sorted([n for n in PLACEMENT_DATA
                   if n not in NETHER_SETS and n not in SKIP_SETS] + DERIVED_SETS)


def _placement(name):
    d = PLACEMENT_DATA[name]
    return RandomSpread(d["spacing"], d["separation"], d["salt"], d["spread"])


# Structures whose biome test does NOT happen at the surface. Vanilla checks the
# biome at the structure's own start height (Structure.isValidBiome samples
# getNoiseBiome at QuartPos.fromBlock(startPos.getY())), and for these that is
# deep underground -- where the only biomes that exist are the cave ones. Against
# a surface raster deep_dark never appears at all, so the map reported zero
# ancient cities for a world that has them.
#
# ACCURACY, measured against `locate structure` on seed 7120356480978639026:
# recall 7/7 -- every real ancient city probed is in this set -- and precision
# 21/30. The markers are a SUPERSET. deep_dark was confirmed present at all nine
# misses, so the biome filter is not what fails: those chunks pass every check
# computable offline and then the jigsaw assembly itself declines to build. That
# cannot be reproduced without running worldgen, so it is a floor for any
# seed-maths map, not a bug to fix here.
DEEP_RASTERS = {"ancient_cities": -27}          # ancient_city start_height.absolute


def raster_path(dimension="overworld", at_y=None):
    base = "nether" if dimension == "nether" else "overworld"
    fname = base + ("" if at_y is None else f"_y{at_y}") + ".cwbr"
    return os.path.join(_DIR, "..", "..", "..", "run", "plugins", "CubeWorld",
                        "biomes", fname)


def _mtime(path):
    try:
        return os.path.getmtime(path)
    except OSError:
        return None


def raster(dimension="overworld", at_y=None):
    """Load a biome raster, RELOADING it if the file changed on disk.

    These used to be cached for the life of the process. Regenerating a raster
    then had no effect on a running map server, which is a silent wrong answer
    rather than a visible failure -- it served 208 villages against the 205 the
    world actually had, and the only clue was that the numbers disagreed with a
    fresh interpreter. Keyed on mtime so a new dump is picked up on the next
    request."""
    path = raster_path(dimension, at_y)
    mt = _mtime(path)
    hit = _rasters.get((dimension, at_y))
    if hit is None or hit[0] != mt:
        hit = (mt, load_raster(path))
        _rasters[(dimension, at_y)] = hit
    return hit[1]


def inputs_signature(dimension="overworld"):
    """Everything the overlays are computed FROM, as a comparable tuple: the
    surface raster, every depth raster, and the stronghold dump. Any of them
    changing must invalidate the cached markers."""
    sig = [_mtime(raster_path(dimension))]
    for at_y in sorted(set(DEEP_RASTERS.values())):
        sig.append(_mtime(raster_path(dimension, at_y)))
    sig.append(_mtime(STRONGHOLDS_JSON))
    return tuple(sig)


def _net_chunk_box(face=cubegate.FACE):
    cols = [c for c, _ in cubegate.GRID.values()]
    rows = [r for _, r in cubegate.GRID.values()]
    h = face / 2
    return (math.floor((min(cols) * face - h) / 16),
            math.floor((min(rows) * face - h) / 16),
            math.ceil((max(cols) * face + h) / 16),
            math.ceil((max(rows) * face + h) / 16))


def _nether_overlays(seed, r):
    """Nether structure markers on the 1:8 nether cube (face = 1280): fortresses
    vs bastions split from the vanilla nether_complexes set by its per-chunk
    weighted pick, ruined portals (always the nether variant here), and nether
    fossils (soul-sand-valley only)."""
    face = cubegate.NETHER_FACE
    cx0, cz0, cx1, cz1 = _net_chunk_box(face)
    out = {"bastions": [], "fortresses": [], "nether_fossils": [], "ruined_portals": []}

    # fortress (weight 2) + bastion (weight 3) share the nether_complexes set;
    # both match nether biomes, so vanilla's first weighted pick is kept:
    # WorldgenRandom.setLargeFeatureSeed(seed, cx, cz); nextInt(5) < 2 -> fortress.
    d = PLACEMENT_DATA["nether_complexes"]
    fb = RandomSpread(d["spacing"], d["separation"], d["salt"], d["spread"])
    for (cx, cz) in fb.candidates_in_chunk_box(seed, cx0, cz0, cx1, cz1):
        m = cubegate.marker(cx, cz, face)
        if m is None:
            continue
        rnd = JavaRandom(0)
        rnd.set_large_feature_seed(seed, cx, cz)
        out["fortresses" if rnd.next_int(5) < 2 else "bastions"].append(m)

    # ruined portals: the set runs in the nether too, and only ruined_portal_nether
    # matches nether biomes, so every candidate is a nether ruined portal.
    d = PLACEMENT_DATA["ruined_portals"]
    rp = RandomSpread(d["spacing"], d["separation"], d["salt"], d["spread"])
    for (cx, cz) in rp.candidates_in_chunk_box(seed, cx0, cz0, cx1, cz1):
        m = cubegate.marker(cx, cz, face)
        if m is not None:
            out["ruined_portals"].append(m)

    # nether fossils: soul-sand-valley only, tight spacing -> enumerate biome chunks
    d = PLACEMENT_DATA["nether_fossils"]
    nf = RandomSpread(d["spacing"], d["separation"], d["salt"], d["spread"])
    allowed = set(STRUCTURE_BIOMES.get("nether_fossils", ["minecraft:soul_sand_valley"]))
    sp = nf.spacing
    for (cx, cz) in r.chunks_with_biomes(allowed):
        if nf.target_chunk(seed, cx // sp, cz // sp) != (cx, cz):
            continue
        m = cubegate.marker(cx, cz, face)
        if m is not None:
            out["nether_fossils"].append(m)
    return out


def compute_overlays(seed, dimension="overworld", types=None):
    # The signature is part of the key, so a regenerated raster or stronghold
    # dump produces a cache MISS rather than a stale hit.
    key = (seed, dimension, inputs_signature(dimension))
    if key in _cache and types is None:
        return _cache[key]
    try:
        r = raster(dimension)
    except FileNotFoundError:
        return {}
    if dimension == "nether":
        out = _nether_overlays(seed, r)
        if types is None:
            _cache[key] = out
            while len(_cache) > 6:
                _cache.pop(next(iter(_cache)))
        return out
    cx0, cz0, cx1, cz1 = _net_chunk_box()
    want = set(types) if types else set(types_for(dimension))
    out = {}
    # zombie villages are a subset of the village candidates, so they ride along
    # on the village pass rather than re-enumerating the net.
    want_zombie = "zombie_villages" in want
    if want_zombie:
        want.add("villages")
        out["zombie_villages"] = []
    if "end_portals" in want:
        out["end_portals"] = _end_portals()
    village_biome, village_height = None, None
    if "villages" in want:
        make_biome, village_height = _village_biome_probe()
        village_biome = make_biome(seed) if make_biome else None
        if village_biome is None:                 # biomegen inputs absent
            village_biome = lambda x, y, z: r.biome_at_chunk(x >> 4, z >> 4)  # noqa: E731
    for name in types_for(dimension):
        if name not in want or name in DERIVED_SETS:
            continue
        d = PLACEMENT_DATA[name]
        placement = _placement(name)
        allowed = set(STRUCTURE_BIOMES.get(name, []))
        # biome-test raster: surface, unless this structure starts underground
        try:
            br = raster(dimension, DEEP_RASTERS[name]) if name in DEEP_RASTERS else r
        except FileNotFoundError:
            # deep raster not dumped yet -> skip rather than silently report zero
            continue
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
            candidates = ((cx, cz) for (cx, cz) in br.chunks_with_biomes(allowed)
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
            if name == "villages":
                # Vanilla does not test the biome at the chunk centre; it tests it
                # at the town-centre bounding-box centre, on the quart lattice, at
                # the height the generator reports there. See village_placement for
                # the whole chain (verified field-by-field against the engine via
                # `/cubeworld villagedebug`). Measured over all 2006 candidates
                # against 349 real villages: P 1.000 / R 1.000 with the biomegen
                # probe, P .941 / R .957 with the chunk raster.
                resolved = village_placement.resolve(seed, cx, cz, village_biome,
                                                     village_height)
                if resolved is None:
                    continue
            elif allowed and not biome_prechecked:
                b = br.biome_at_chunk(cx, cz)
                if b not in allowed:
                    continue
            if freq is not None and not frequency.keeps(seed, d["salt"], cx, cz,
                                                         freq, d["frequency_reduction_method"]):
                continue
            if excl_chunks is not None and frequency.excluded(cx, cz, excl_chunks,
                                                              excl["chunks"]):
                continue
            markers.append(m)
            if want_zombie and name == "villages":
                # the pool is the one `resolve` found vanilla roll, not the one the
                # chunk-centre biome would suggest
                if is_zombie_variant(seed, cx, cz, resolved[0]):
                    out["zombie_villages"].append(m)
        out[name] = markers
    if want_zombie and "villages" not in (set(types) if types else want):
        out.pop("villages", None)          # villages were only a carrier
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
