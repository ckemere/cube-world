"""Reproduce Minecraft's RandomSpreadStructurePlacement: which chunk (if any) a
structure targets within each spacing x spacing region, from the world seed.

A chunk (cx,cz) is a placement chunk for a structure iff, with
regionX=floorDiv(cx,spacing), regionZ=floorDiv(cz,spacing), the region's
computed target equals (cx,cz). Biome validity and the cube gates are applied
by the caller."""

import math

from .javarandom import JavaRandom, large_feature_seed

LINEAR = "linear"
TRIANGULAR = "triangular"


class RandomSpread:
    __slots__ = ("spacing", "separation", "salt", "spread")

    def __init__(self, spacing, separation, salt, spread=LINEAR):
        self.spacing = spacing
        self.separation = separation
        self.salt = salt
        self.spread = spread

    def target_chunk(self, seed, region_x, region_z):
        """The chunk this region targets (the RandomSpread candidate)."""
        r = JavaRandom(large_feature_seed(seed, region_x, region_z, self.salt))
        m = self.spacing - self.separation
        if self.spread == TRIANGULAR:
            ox = (r.next_int(m) + r.next_int(m)) // 2
            oz = (r.next_int(m) + r.next_int(m)) // 2
        else:
            ox = r.next_int(m)
            oz = r.next_int(m)
        return region_x * self.spacing + ox, region_z * self.spacing + oz

    def candidates_in_chunk_box(self, seed, cx0, cz0, cx1, cz1):
        """All placement chunks whose target falls in the chunk box [cx0,cx1] x
        [cz0,cz1]. Iterates the regions overlapping the box."""
        rx0 = math.floor(cx0 / self.spacing)
        rx1 = math.floor(cx1 / self.spacing)
        rz0 = math.floor(cz0 / self.spacing)
        rz1 = math.floor(cz1 / self.spacing)
        out = []
        for rx in range(rx0, rx1 + 1):
            for rz in range(rz0, rz1 + 1):
                cx, cz = self.target_chunk(seed, rx, rz)
                if cx0 <= cx <= cx1 and cz0 <= cz <= cz1:
                    out.append((cx, cz))
        return out
