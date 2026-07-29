#!/usr/bin/env python3
"""Render the six cube faces coloured by the biome the world ACTUALLY generates.

The spinning globe in `cubemap/out/globe.html` is a Whittaker-style blend of
WorldClim temperature and precipitation computed in Python -- an artist's
impression of Earth. It never calls the plugin's biome provider, never touches
vanilla's parameter list, and so does not move when the generator changes. It
showed the Amazon as rainforest for a whole day while the server was generating
mangrove swamp there.

`overworld.cwbr` is the opposite: the plugin dumps it from the real provider, at
chunk resolution, for all six faces, and `seed_cities.py` refreshes it on every
reseed. It was already being loaded here -- but only to biome-FILTER the
structure overlay, never to draw anything. So the map carried two disagreeing
biome pictures and showed the wrong one.

This draws the right one. No world generation and no per-pixel biome lookup is
needed: the answer is already in the raster, so a face is a palette index into a
colour table.

    python3 tools/playermap/biomeglobe.py            # writes faces + biomes.html
"""
from __future__ import annotations
import base64, io, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image
from structures.biomeraster import load as load_raster

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "biomefaces")

# Vanilla-ish map colours, keyed by biome id. Anything unlisted falls back to a
# hash-derived colour so a NEW biome after a version bump shows up as an
# obviously-odd patch rather than silently blending in.
COLOURS = {
    "ocean": (56, 86, 140), "deep_ocean": (38, 62, 110),
    "cold_ocean": (62, 96, 150), "deep_cold_ocean": (44, 72, 122),
    "frozen_ocean": (130, 160, 190), "deep_frozen_ocean": (100, 132, 168),
    "lukewarm_ocean": (60, 108, 158), "deep_lukewarm_ocean": (44, 84, 132),
    "warm_ocean": (70, 132, 176), "river": (70, 120, 175),
    "frozen_river": (150, 180, 205), "beach": (222, 208, 160),
    "snowy_beach": (232, 236, 240), "stony_shore": (140, 138, 132),
    "plains": (140, 180, 96), "sunflower_plains": (160, 196, 100),
    "meadow": (128, 178, 120), "forest": (74, 130, 70),
    "flower_forest": (104, 156, 88), "birch_forest": (132, 164, 108),
    "old_growth_birch_forest": (146, 176, 120), "dark_forest": (48, 92, 52),
    "pale_garden": (120, 136, 118), "taiga": (58, 106, 92),
    "snowy_taiga": (120, 150, 146), "old_growth_pine_taiga": (62, 100, 74),
    "old_growth_spruce_taiga": (54, 92, 68), "grove": (150, 172, 164),
    "jungle": (44, 122, 46), "sparse_jungle": (76, 140, 62),
    "bamboo_jungle": (96, 150, 58), "savanna": (176, 174, 96),
    "savanna_plateau": (188, 186, 112), "windswept_savanna": (160, 164, 104),
    "desert": (226, 208, 140), "badlands": (186, 110, 62),
    "eroded_badlands": (200, 126, 74), "wooded_badlands": (168, 122, 74),
    "snowy_plains": (232, 238, 244), "ice_spikes": (212, 232, 244),
    "snowy_slopes": (204, 216, 226), "frozen_peaks": (196, 214, 230),
    "jagged_peaks": (176, 186, 196), "stony_peaks": (140, 134, 128),
    "windswept_hills": (110, 140, 108), "windswept_gravelly_hills": (128, 132, 124),
    "windswept_forest": (86, 122, 84), "swamp": (78, 108, 78),
    "mangrove_swamp": (72, 112, 84), "mushroom_fields": (180, 120, 160),
    "cherry_grove": (206, 150, 176), "the_void": (0, 0, 0),
    "dripstone_caves": (128, 106, 88), "lush_caves": (90, 140, 70),
    "sulfur_caves": (190, 180, 90), "deep_dark": (24, 28, 36),
}


def colour_of(biome_id: str):
    key = biome_id.split(":")[-1]
    if key in COLOURS:
        return COLOURS[key]
    h = abs(hash(key))
    return (255, (h >> 8) & 0xFF, (h >> 16) & 0xFF)      # loud magenta-ish


def render(path=None, out_dir=OUT):
    r = load_raster(path)
    os.makedirs(out_dir, exist_ok=True)
    lut = np.array([colour_of(b) for b in r.palette], dtype=np.uint8)
    written = []
    for name, _cmx, _cmz, grid in r.faces:
        g = grid.reshape(r.chunks, r.chunks)
        # grid is [x-index outer, z-index inner]; transpose so image rows are z
        rgb = lut[np.clip(g, 0, len(r.palette) - 1)].transpose(1, 0, 2)
        im = Image.fromarray(rgb, "RGB")
        p = os.path.join(out_dir, f"{name}.png")
        im.save(p)
        written.append((name, p, im.size))
    return r, written


def _data_uri(p):
    with open(p, "rb") as f:
        return "data:image/png;base64," + base64.b64encode(f.read()).decode()


def build_page(r, written, out_html):
    counts = {}
    total = 0
    for _n, _cmx, _cmz, grid in r.faces:
        idx, cnt = np.unique(grid, return_counts=True)
        for i, c in zip(idx.tolist(), cnt.tolist()):
            counts[r.palette[i]] = counts.get(r.palette[i], 0) + c
            total += c
    rows = sorted(counts.items(), key=lambda kv: -kv[1])
    legend = "".join(
        f'<li><i style="background:rgb{colour_of(b)}"></i>{b.split(":")[-1]}'
        f'<b>{100.0*c/total:.2f}%</b></li>'
        for b, c in rows if 100.0 * c / total >= 0.02)
    faces = "".join(
        f'<figure><img src="{_data_uri(p)}" alt="{n}"><figcaption>{n} '
        f'({sz[0]}&times;{sz[1]} chunks)</figcaption></figure>' for n, p, sz in written)
    html = f"""<!doctype html><meta charset=utf-8>
<title>CubeWorld — biomes as generated</title>
<style>
 body{{background:#11141a;color:#dfe4ea;font:14px/1.5 system-ui,sans-serif;margin:0;padding:24px}}
 h1{{font-size:18px;font-weight:600;margin:0 0 4px}}
 p.sub{{color:#8b93a1;margin:0 0 20px}}
 .faces{{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px}}
 figure{{margin:0}} img{{width:100%;image-rendering:pixelated;border-radius:6px;display:block}}
 figcaption{{color:#8b93a1;font-size:12px;padding-top:6px}}
 ul{{list-style:none;padding:0;margin:24px 0 0;columns:4;font-size:12px}}
 li{{break-inside:avoid;padding:2px 0}}
 li i{{display:inline-block;width:11px;height:11px;border-radius:2px;margin-right:7px;vertical-align:-1px}}
 li b{{color:#8b93a1;font-weight:400;float:right}}
</style>
<h1>Biomes as generated</h1>
<p class=sub>Read from <code>overworld.cwbr</code>, which the plugin dumps from the
real biome provider at chunk resolution &mdash; not the climate classification the
spinning globe uses. {r.chunks}&times;{r.chunks} chunks per face,
{len(r.palette)} biomes in the palette.</p>
<div class=faces>{faces}</div>
<ul>{legend}</ul>
"""
    with open(out_html, "w") as f:
        f.write(html)
    return rows, total


if __name__ == "__main__":
    r, written = render()
    rows, total = build_page(r, written, os.path.join(HERE, "biomes.html"))
    print(f"faces: {len(written)}  {r.chunks}x{r.chunks} chunks each, "
          f"{len(r.palette)} biomes in palette")
    for b, c in rows[:12]:
        print(f"   {b.split(':')[-1]:<28} {100.0*c/total:6.2f}%")
    print(f"wrote {os.path.join(HERE, 'biomes.html')}")
