"""Render a cube face's biomes to a PNG so we can eyeball the generator: crisp
per-chunk cells, Earth geography at the large scale, local variation (Sahara
with savanna patches) from the noise blend. Run:
  cd tools/playermap && python3 -m biomegen.render [face] [n] [seed]"""

import os
import sys

import numpy as np
from PIL import Image

from .biomegen import biome_grid
from .blobreader import Blob
from .partition import Partition

BCOL = {
    "plains": (141, 179, 96), "sunflower_plains": (181, 199, 86), "meadow": (96, 164, 84),
    "forest": (59, 130, 62), "flower_forest": (100, 158, 80), "birch_forest": (96, 151, 90),
    "old_growth_birch_forest": (88, 140, 82), "dark_forest": (64, 96, 50),
    "taiga": (49, 107, 80), "snowy_taiga": (108, 140, 140), "old_growth_pine_taiga": (64, 100, 72),
    "old_growth_spruce_taiga": (66, 102, 74), "grove": (188, 196, 196),
    "snowy_slopes": (220, 225, 230), "windswept_forest": (90, 120, 90),
    "windswept_hills": (120, 130, 110), "windswept_gravelly_hills": (130, 130, 120),
    "savanna": (189, 178, 95), "savanna_plateau": (179, 169, 90), "windswept_savanna": (169, 163, 90),
    "desert": (224, 205, 120), "badlands": (167, 94, 50), "wooded_badlands": (150, 110, 60),
    "eroded_badlands": (180, 110, 60), "jungle": (43, 144, 49), "sparse_jungle": (90, 150, 60),
    "bamboo_jungle": (110, 160, 50), "swamp": (80, 110, 80), "mangrove_swamp": (70, 100, 70),
    "snowy_plains": (232, 236, 240), "ice_spikes": (200, 220, 240), "snowy_beach": (230, 225, 200),
    "beach": (238, 224, 170), "stony_shore": (150, 150, 150), "mushroom_fields": (170, 110, 150),
    "jagged_peaks": (235, 240, 245), "frozen_peaks": (220, 230, 245), "stony_peaks": (150, 150, 140),
    "river": (60, 110, 200), "frozen_river": (150, 180, 220),
    "ocean": (46, 76, 160), "deep_ocean": (30, 54, 130), "cold_ocean": (56, 86, 152),
    "deep_cold_ocean": (40, 66, 132), "lukewarm_ocean": (58, 108, 172),
    "deep_lukewarm_ocean": (42, 88, 152), "warm_ocean": (66, 136, 184),
    "frozen_ocean": (150, 180, 210), "deep_frozen_ocean": (120, 150, 190),
    "cherry_grove": (230, 170, 200), "pale_garden": (150, 160, 150),
}


def color_for(biome_id):
    name = biome_id.split(":")[-1]
    if name in BCOL:
        return BCOL[name]
    h = abs(hash(name))
    return (80 + h % 150, 80 + (h // 150) % 150, 80 + (h // 22500) % 150)


def render(face, n, seed, blend, blob, part):
    idx, _ = biome_grid(blob, part, face, n, seed, blend=blend)
    lut = np.array([color_for(b) for b in part.biomes], dtype=np.uint8)
    img = lut[idx]
    return idx, Image.fromarray(img, "RGB")


def main():
    face = sys.argv[1] if len(sys.argv) > 1 else "EQ_PRIME"
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 640
    seed = int(sys.argv[3]) if len(sys.argv) > 3 else 20260712
    blob, part = Blob(), Partition()
    out = os.path.join(os.path.dirname(__file__), "..", "..", "cubemap", "out")
    os.makedirs(out, exist_ok=True)
    for blend in (False, True):
        idx, im = render(face, n, seed, blend, blob, part)
        tag = "blend" if blend else "earth"
        im.resize((n, n), Image.NEAREST).save(os.path.join(out, f"biome_{face}_{tag}.png"))
        names = [part.biomes[i].split(":")[-1] for i in np.unique(idx)]
        from collections import Counter
        c = Counter(part.biomes[i].split(":")[-1] for i in idx.ravel())
        top = ", ".join(f"{k} {100 * v // idx.size}%" for k, v in c.most_common(6))
        print(f"{face} {tag:5s}: {len(names)} biomes | {top}")


if __name__ == "__main__":
    main()
