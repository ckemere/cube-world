"""Frequency reducers + exclusion zones, ported bit-exactly from
StructurePlacement's FrequencyReductionMethod lambdas (verified against the
26.2 jar bytecode):

  legacy_type_1 (pillager_outpost): setLargeFeatureSeed(seed, x, z), no salt;
                                    keep if nextDouble() < frequency.
  legacy_type_2 (buried_treasure):  setLargeFeatureWithSalt(seed, x, z, 10387320)
                                    with a FIXED salt; keep if nextFloat() < frequency.
  legacy_type_3 (mineshaft):        setSeed((x>>4) ^ ((z>>4)<<4) ^ seed);
                                    nextInt() discarded; keep if nextInt(1/freq)==0.
  default:                          setLargeFeatureWithSalt(seed, salt, x, z);
                                    keep if nextFloat() < frequency.
"""

from .javarandom import JavaRandom, _to_int64, large_feature_seed

_BURIED_TREASURE_SALT = 10387320


def keeps(seed, salt, cx, cz, frequency, method):
    if method == "legacy_type_1":                     # pillager outpost: 1-in-N
        raw = ((cx >> 4) ^ ((cz >> 4) << 4)) & 0xFFFFFFFF
        t64 = (raw - 0x100000000) if (raw & 0x80000000) else raw   # i2l sign-extend
        r = JavaRandom((t64 ^ seed) & ((1 << 64) - 1))
        r.next(32)                                    # discarded nextInt()
        return r.next_int(int(round(1.0 / frequency))) == 0
    if method == "legacy_type_2":                     # buried treasure: fixed salt, nextFloat
        r = JavaRandom(large_feature_seed(seed, cx, cz, _BURIED_TREASURE_SALT))
        return r.next_float() < frequency
    if method == "legacy_type_3":                     # mineshaft: setLargeFeatureSeed, nextDouble
        r = JavaRandom(0)
        r.set_large_feature_seed(seed, cx, cz)
        return r.next_double() < frequency
    # default
    r = JavaRandom(large_feature_seed(seed, salt, cx, cz))
    return r.next_float() < frequency


def exclusion_chunks(seed, other_placement, cx0, cz0, cx1, cz1):
    """All RAW placement chunks of the other structure set (no biome/gate) — the
    exclusion zone is checked against potential placements, not real ones."""
    return set(other_placement.candidates_in_chunk_box(seed, cx0, cz0, cx1, cz1))


def excluded(cx, cz, other_chunks, radius):
    """Forbidden if another set's placement chunk is within Chebyshev `radius`."""
    for dx in range(-radius, radius + 1):
        for dz in range(-radius, radius + 1):
            if (cx + dx, cz + dz) in other_chunks:
                return True
    return False
