"""Look at the terrain a city was built on: roughness, slope under buildings, and
structures left standing in the air.

Anchored city villages are jigsaw structures placed on whatever terrain the
density field produced. Rough ground makes the jigsaw stilt or float buildings
(the "houses built in the air" problem), so this reports the terrain statistics
AND renders a top-down relief image with the village footprint highlighted.

Usage: city_terrain.py <x> <z> [radius_chunks] [out.png]
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, "/home/dev/projects/cube-world/tools/playermap")
import anvil  # noqa: E402
from cave_audit import VILLAGE, ANY_AIR, CAVE_AIR, load_chunk, column_blocks  # noqa: E402


def survey(cx0, cz0, rad):
    n = (2 * rad + 1) * 16
    hgt = np.full((n, n), np.nan)
    vil = np.zeros((n, n), bool)
    float_air = np.zeros((n, n), np.int16)
    for cx in range(cx0 - rad, cx0 + rad + 1):
        for cz in range(cz0 - rad, cz0 + rad + 1):
            root = load_chunk(cx, cz)
            if root is None:
                continue
            got = column_blocks(root)
            if got is None:
                continue
            h, name_at = got
            ox = (cx - (cx0 - rad)) * 16
            oz = (cz - (cz0 - rad)) * 16
            for lz in range(16):
                for lx in range(16):
                    top = anvil.MIN_Y + int(h[lz, lx]) - 1
                    if top < anvil.MIN_Y + 1:
                        continue
                    hgt[oz + lz, ox + lx] = top
                    surf = name_at(lx, top, lz)
                    if surf in VILLAGE:
                        vil[oz + lz, ox + lx] = True
                        # plain-air run directly beneath a village block = stilted
                        run = 0
                        for dy in range(1, 13):
                            nm = name_at(lx, top - dy, lz)
                            if nm in ANY_AIR and nm not in CAVE_AIR:
                                run += 1
                            else:
                                break
                        float_air[oz + lz, ox + lx] = run
    return hgt, vil, float_air


def render(hgt, vil, path):
    h = np.where(np.isnan(hgt), np.nanmin(hgt), hgt)
    lo, hi = np.nanpercentile(h, 1), np.nanpercentile(h, 99)
    t = np.clip((h - lo) / max(1e-6, hi - lo), 0, 1)
    # terrain: dark blue low -> green -> tan -> white high
    stops = [(0.0, (30, 55, 95)), (0.18, (60, 110, 90)), (0.4, (95, 140, 80)),
             (0.65, (160, 145, 100)), (0.85, (200, 190, 170)), (1.0, (250, 250, 250))]
    xs = np.array([s[0] for s in stops]); cs = np.array([s[1] for s in stops], float)
    rgb = np.stack([np.interp(t, xs, cs[:, i]) for i in range(3)], -1)
    # hillshade for relief
    gy, gx = np.gradient(h)
    sl = np.arctan(2.2 * np.hypot(gx, gy)); asp = np.arctan2(-gx, gy)
    sh = np.clip(np.sin(np.radians(45)) * np.cos(sl)
                 + np.cos(np.radians(45)) * np.sin(sl) * np.cos(np.radians(315) - asp), 0, 1)
    rgb *= (0.45 + 0.75 * sh)[..., None]
    rgb[vil] = rgb[vil] * 0.25 + np.array([255, 70, 70]) * 0.75      # village in red
    img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")
    img = img.resize((img.width * 3, img.height * 3), Image.NEAREST)
    img.save(path)
    return path


if __name__ == "__main__":
    x, z = int(sys.argv[1]), int(sys.argv[2])
    rad = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    out = sys.argv[4] if len(sys.argv) > 4 else "/tmp/city_terrain.png"
    hgt, vil, fa = survey(x >> 4, z >> 4, rad)
    ok = np.isfinite(hgt)
    gy, gx = np.gradient(np.where(ok, hgt, np.nanmean(hgt)))
    slope = np.hypot(gx, gy)
    print(f"terrain around ({x},{z}), {(2*rad+1)**2} chunks:")
    print(f"  surface y        : min {np.nanmin(hgt):.0f}  mean {np.nanmean(hgt):.1f}"
          f"  max {np.nanmax(hgt):.0f}  (relief {np.nanmax(hgt)-np.nanmin(hgt):.0f})")
    print(f"  mean |slope|     : {slope[ok].mean():.2f} blocks/block")
    print(f"  village columns  : {int(vil.sum())}")
    if vil.any():
        print(f"  slope UNDER village: {slope[vil].mean():.2f}"
              f"   (vs {slope[ok & ~vil].mean():.2f} elsewhere)")
        st = fa[vil]
        print(f"  stilted/floating village columns (plain air directly beneath):")
        for k in (1, 2, 3, 6):
            c = int((st >= k).sum())
            print(f"     >={k} air below: {c:5d}  ({c/max(1,vil.sum())*100:5.1f}% of village)")
        print(f"     worst gap: {int(st.max())} blocks")
    print(f"  wrote {render(hgt, vil, out)}")
