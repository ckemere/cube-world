"""Where vanilla 26.2 *actually* samples the biome when deciding a village.

The overlay used to test the biome at the chunk CENTRE. Vanilla tests it at the
centre of the town-centre piece's bounding box, which sits at the chunk CORNER
plus a rotation-dependent offset — so the two are never the same quart cell, and
for 75% of candidates not even the same chunk.

Scored against the villages the world actually generated (region files, seed
8675309), over all 2006 RandomSpread candidates on the cube — the exhaustive
set, since a village can only exist at a candidate — and 349 real villages:

    chunk centre                          P .895  R .901
    bbox centre, surface biome            P .980  R .971   (17 wrong)
    + absolute-sum centre, quart, real y  P 1.000 R 1.000   (0 wrong)

Every field of the last row was then checked against the engine itself with
`/cubeworld villagedebug <cx> <cz>`: variant try-order, rotation, town-centre
template size, bounding-box centre, quart cell, biome and verdict agree on
557/557 chunks probed (the 17 formerly disputed + 540 sampled).

The chain, all read from the 26.2 dev-bundle sources rather than recalled:

  ChunkGenerator.createStructures       weighted pick among the 5 village variants,
                                        from a WorldgenRandom seeded
                                        setLargeFeatureSeed(seed, cx, cz)
  JigsawStructure.findGenerationPoint   startHeight = ConstantHeight 0 (draws no RNG);
                                        startPos = (chunkMinX, 0, chunkMinZ) -- the CORNER
  JigsawPlacement.addPieces             rotation = Rotation.getRandom(random)   -> nextInt(4)
                                        element  = pool.getRandomTemplate(random)
                                                   -> nextInt(len(weight-expanded list))
                                        box      = element bbox at startPos, rotation
                                        centerX  = (box.minX + box.maxX) / 2   [Java idiv]
                                        centerZ  = (box.minZ + box.maxZ) / 2
                                        centerY  = chunkGenerator.getFirstFreeHeight(
                                                       centerX, centerZ, WORLD_SURFACE_WG)
  Structure.isValidBiome                getNoiseBiome(centerX>>2, centerY>>2, centerZ>>2)

Each variant attempt builds its OWN WorldgenRandom seeded identically, so the
rotation draw repeats per variant while the template draw differs with pool size.

Three details that each cost a handful of chunks when they were missing:

  * The centre is a Java integer division of the ABSOLUTE corner sum, not the
    chunk corner plus a locally-divided offset. `(2*p + r) / 2` truncates toward
    zero on the whole sum, so for p > 0 and r < 0 it is p + floor(r/2), one
    block away from p + trunc(r/2).
  * The biome question is `getNoiseBiome(x>>2, y>>2, z>>2)`, and
    CustomWorldChunkManager answers it by asking our BiomeProvider at
    `QuartPos.toBlock` of those — i.e. the sample is snapped DOWN to the 4x4x4
    quart lattice. Sampling the unsnapped centre lands in a neighbouring cell
    often enough to matter.
  * The y is not the surface: CustomChunkGenerator.getBaseHeight delegates to
    CubeWorldChunkGenerator.getBaseHeight, which is
    `round(max(heightAt(x+0.5, z+0.5), SEA_LEVEL)) + 1`. Below sea level that is
    64, not the sea floor, so ocean-adjacent candidates test the wrong cell if
    the surface height is used.

Villages have no terrain gate: `dimension_padding` is zero, and for a jigsaw with
size > 0 the centre piece is always added, so StructureStart.isValid() cannot
fail. The biome test is therefore the ONLY non-seed gate — which is why this is
fully computable from a seed with no generated world.
"""
from __future__ import annotations

import json
import math
import os

from .javarandom import JavaRandom

_DIR = os.path.dirname(os.path.abspath(__file__))
_DATA = json.load(open(os.path.join(_DIR, "village_templates.json"), encoding="utf-8"))

VARIANTS = _DATA["variant_order"]
TEMPLATES = {v: [tuple(s) for s in _DATA["templates"][v]] for v in VARIANTS}

# Repo datapack that widens village_plains; read rather than baked so the model
# follows the datapack instead of drifting from it.
_DATAPACK = os.path.join(
    _DIR, "..", "..", "..", "src", "main", "resources", "anchor_datapack",
    "data", "minecraft", "tags", "worldgen", "biome", "has_structure")


def _tags():
    out = {v: set(_DATA["vanilla_tags"][v]) for v in VARIANTS}
    for v in VARIANTS:
        p = os.path.join(_DATAPACK, f"village_{v}.json")
        if not os.path.exists(p):
            continue
        d = json.load(open(p, encoding="utf-8"))
        if d.get("replace"):
            out[v] = set(d.get("values", []))
        else:
            out[v] |= set(d.get("values", []))
    return out


TAGS = _tags()


# CubeWorldChunkGenerator.SEA_LEVEL (= SphericalDemoSpec.SEA_LEVEL).
SEA_LEVEL = 62


def _idiv(a, b):
    """Java integer division: truncates toward zero, unlike Python's floor."""
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b >= 0) else -q


def _rotated_delta(rotation, size):
    """StructureTemplate.transform(size-1, NO_MIRROR, rotation, pivot ZERO), xz only."""
    dx, dz = size[0] - 1, size[2] - 1
    if rotation == 0:                       # NONE
        return dx, dz
    if rotation == 1:                       # CLOCKWISE_90        (x,z) -> (-z, x)
        return -dz, dx
    if rotation == 2:                       # CLOCKWISE_180
        return -dx, -dz
    return dz, -dx                          # COUNTERCLOCKWISE_90 (x,z) -> (z, -x)


def draw(seed, cx, cz, variant):
    """(rotation, template_index, size) drawn for `variant` in chunk (cx, cz)."""
    pool = TEMPLATES[variant]
    r = JavaRandom(0)
    r.set_large_feature_seed(seed, cx, cz)
    rotation = r.next_int(4)
    index = r.next_int(len(pool))
    return rotation, index, pool[index]


def sample_xz(seed, cx, cz, variant):
    """The town-centre bounding-box centre (x, z) for `variant` in (cx, cz).

    The raw centre, before the quart snap that `sample_pos` applies. The other
    corner of the box is the chunk corner itself, so minX + maxX is
    `2*chunkMinX + rotated_dx` and vanilla truncates THAT toward zero.
    """
    rotation, _, size = draw(seed, cx, cz, variant)
    rx, rz = _rotated_delta(rotation, size)
    return _idiv(2 * (cx * 16) + rx, 2), _idiv(2 * (cz * 16) + rz, 2)


def sample_pos(seed, cx, cz, variant, height_at=None):
    """The quart-lattice block position vanilla biome-tests for `variant`.

    `height_at(x, z)` is the generator's surface height (a float — the caller is
    expected to sample at the block centre, as CubeWorldChunkGenerator does).
    Pass None only for a y-agnostic (e.g. chunk-raster) biome lookup, in which
    case y comes back None.
    """
    x, z = sample_xz(seed, cx, cz, variant)
    if height_at is None:
        y = None
    else:
        y = ((int(math.floor(max(height_at(x, z), SEA_LEVEL) + 0.5)) + 1) >> 2) << 2
    return (x >> 2) << 2, y, (z >> 2) << 2


def variant_order(seed, cx, cz):
    """The order createStructures tries the five variants (equal weights)."""
    opts, weights = list(VARIANTS), [1] * len(VARIANTS)
    r = JavaRandom(0)
    r.set_large_feature_seed(seed, cx, cz)
    order = []
    while opts:
        choice = r.next_int(sum(weights))
        idx = 0
        for w in weights:
            choice -= w
            if choice < 0:
                break
            idx += 1
        order.append(opts[idx])
        del opts[idx], weights[idx]
    return order


def resolve(seed, cx, cz, biome_at, height_at=None):
    """The village variant that generates at (cx, cz), or None.

    `biome_at(x, y, z)` must return the biome id the world's BiomeProvider gives
    at that block; the position handed to it is already snapped to the quart
    lattice, which is the only lattice getNoiseBiome can ask about. Omitting
    `height_at` passes y=None and so answers with whatever the callback does for
    a y-agnostic lookup (a chunk raster); that costs accuracy, because 36.8% of
    chunks hold more than one biome across their 4x4 surface quart grid.
    """
    for variant in variant_order(seed, cx, cz):
        x, y, z = sample_pos(seed, cx, cz, variant, height_at)
        if biome_at(x, y, z) in TAGS[variant]:
            return variant, x, y, z
    return None
