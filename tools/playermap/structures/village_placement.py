"""Where vanilla 26.2 *actually* samples the biome when deciding a village.

The overlay used to test the biome at the chunk CENTRE. Vanilla tests it at the
centre of the town-centre piece's bounding box, which sits at the chunk CORNER
plus a rotation-dependent offset — so the two are never the same quart cell, and
for 75% of candidates not even the same chunk. That single error made the
`villages` layer wrong in both directions (measured: precision 11/14, recall
11/14 — not the superset the old comment in compute.py claimed).

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
  Structure.isValidBiome                getNoiseBiome(centerX>>2, centerY>>2, centerZ>>2)

Each variant attempt builds its OWN WorldgenRandom seeded identically, so the
rotation draw repeats per variant while the template draw differs with pool size.

Villages have no terrain gate: `dimension_padding` is zero, and for a jigsaw with
size > 0 the centre piece is always added, so StructureStart.isValid() cannot
fail. The biome test is therefore the ONLY non-seed gate — which is why this is
fully computable from a seed with no generated world.
"""
from __future__ import annotations

import json
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


def _idiv(a, b):
    """Java integer division: truncates toward zero, unlike Python's floor."""
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b >= 0) else -q


def _bbox_center_offset(rotation, size):
    """Offset from the chunk corner to the town-centre bbox centre.

    Derived from StructureTemplate.getBoundingBox + transform (pivot BlockPos.ZERO)
    with dx = sizeX-1, dz = sizeZ-1.
    """
    dx, dz = size[0] - 1, size[2] - 1
    if rotation == 0:                       # NONE
        return _idiv(dx, 2), _idiv(dz, 2)
    if rotation == 1:                       # CLOCKWISE_90
        return _idiv(-dz, 2), _idiv(dx, 2)
    if rotation == 2:                       # CLOCKWISE_180
        return _idiv(-dx, 2), _idiv(-dz, 2)
    return _idiv(dz, 2), _idiv(-dx, 2)      # COUNTERCLOCKWISE_90


def sample_xz(seed, cx, cz, variant):
    """The (x, z) vanilla biome-tests at for `variant` in chunk (cx, cz)."""
    pool = TEMPLATES[variant]
    r = JavaRandom(0)
    r.set_large_feature_seed(seed, cx, cz)
    rotation = r.next_int(4)
    size = pool[r.next_int(len(pool))]
    ox, oz = _bbox_center_offset(rotation, size)
    return cx * 16 + ox, cz * 16 + oz


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


def resolve(seed, cx, cz, biome_at):
    """The village variant that generates at (cx, cz), or None.

    `biome_at(x, z)` must return the biome id at that block — ideally at quart
    resolution, since 36.8% of chunks hold more than one biome across their 4x4
    surface quart grid and a chunk-resolution raster cannot express the answer.
    """
    for variant in variant_order(seed, cx, cz):
        x, z = sample_xz(seed, cx, cz, variant)
        if biome_at(x, z) in TAGS[variant]:
            return variant, x, z
    return None
