#!/usr/bin/env python3
"""Composite the world's REAL generated surface blocks onto the model globe.

Reads every fully-generated chunk from the overworld region files (see
`anvil.py`), maps each column to its pixel on the cube-face textures the globe
already uses, and paints the real top-block colour over the biome/elevation
model. Explored ground shows what the generator actually made; everything else
keeps the model. Results are cached and only recomputed when a region file
changes on disk.
"""
from __future__ import annotations
import base64
import io
import math
import os
import re
import numpy as np
from PIL import Image

from anvil import read_region

# ---- cube net geometry (mirrors server.world_to_cube / cubemap embedding) ----
FACE = int(os.environ.get("FACE_SIZE", "10240"))
H = FACE / 2.0
GRID = {"NORTH_POLE": (0, 0), "EQ_PRIME": (0, 1), "EQ_EAST": (1, 0),
        "EQ_BACK": (0, -1), "EQ_WEST": (-1, 0), "SOUTH_POLE": (0, 2)}
FACE_ORDER = ["NORTH_POLE", "SOUTH_POLE", "EQ_PRIME", "EQ_BACK", "EQ_EAST", "EQ_WEST"]
_CELL = {(c, r): f for f, (c, r) in GRID.items()}


# ------------------------------------------------------------- block palette
# Top-down colours for the blocks the Earth generator actually places. Anything
# not listed falls back to a family colour by name (see block_rgb).
BLOCK_RGB = {
    "minecraft:water": (58, 108, 165),
    "minecraft:grass_block": (98, 148, 66),
    "minecraft:short_grass": (98, 148, 66),
    "minecraft:tall_grass": (98, 148, 66),
    "minecraft:fern": (94, 140, 64),
    "minecraft:sand": (219, 206, 158),
    "minecraft:sandstone": (216, 203, 155),
    "minecraft:red_sand": (190, 102, 47),
    "minecraft:red_sandstone": (183, 98, 45),
    "minecraft:terracotta": (152, 94, 67),
    "minecraft:stone": (129, 129, 129),
    "minecraft:andesite": (136, 136, 138),
    "minecraft:diorite": (188, 188, 190),
    "minecraft:granite": (154, 111, 92),
    "minecraft:gravel": (131, 127, 124),
    "minecraft:dirt": (134, 96, 67),
    "minecraft:coarse_dirt": (120, 87, 61),
    "minecraft:podzol": (89, 63, 33),
    "minecraft:snow_block": (245, 250, 252),
    "minecraft:snow": (245, 250, 252),
    "minecraft:powder_snow": (238, 244, 248),
    "minecraft:ice": (150, 183, 226),
    "minecraft:packed_ice": (141, 176, 222),
    "minecraft:blue_ice": (116, 158, 214),
    "minecraft:clay": (159, 166, 179),
    "minecraft:mud": (60, 53, 50),
    "minecraft:moss_block": (89, 109, 45),
    "minecraft:leaf_litter": (129, 123, 84),
    "minecraft:pale_moss_block": (129, 138, 118),
    "minecraft:dead_bush": (129, 104, 62),
    "minecraft:calcite": (223, 224, 220),
    "minecraft:tuff": (108, 109, 102),
    "minecraft:deepslate": (77, 77, 82),
    "minecraft:cobblestone": (122, 122, 122),
    "minecraft:bedrock": (84, 84, 84),
    "minecraft:lava": (216, 96, 32),
    "minecraft:magma_block": (140, 66, 34),
    "minecraft:oak_leaves": (61, 110, 40),
    "minecraft:spruce_leaves": (58, 86, 56),
    "minecraft:birch_leaves": (110, 140, 66),
    "minecraft:jungle_leaves": (52, 120, 32),
    "minecraft:acacia_leaves": (104, 128, 43),
    "minecraft:dark_oak_leaves": (52, 90, 34),
    "minecraft:mangrove_leaves": (70, 116, 48),
    "minecraft:azalea_leaves": (78, 120, 52),
}

_FAMILY = [
    ("water", (58, 108, 165)),
    ("lava", (216, 96, 32)),
    ("leaves", (60, 106, 42)),
    ("planks", (166, 133, 86)), ("_log", (104, 82, 52)), ("_wood", (104, 82, 52)),
    ("_stem", (104, 82, 52)), ("_fence", (150, 120, 78)), ("_stairs", (150, 120, 78)),
    ("_slab", (150, 120, 78)), ("_door", (150, 120, 78)), ("hay_block", (196, 168, 40)),
    ("dirt_path", (150, 120, 72)), ("bricks", (150, 90, 74)), ("amethyst", (176, 128, 214)),
    ("bell", (196, 168, 40)), ("cobblestone", (122, 122, 122)),
    ("red_sand", (190, 102, 47)),
    ("sand", (219, 206, 158)),
    ("snow", (245, 250, 252)),
    ("ice", (146, 180, 224)),
    ("terracotta", (152, 94, 67)),
    ("deepslate", (77, 77, 82)),
    ("podzol", (89, 63, 33)),
    ("grass", (98, 148, 66)), ("moss", (89, 109, 45)), ("fern", (94, 140, 64)),
    ("dirt", (134, 96, 67)), ("mud", (60, 53, 50)), ("clay", (159, 166, 179)),
    ("gravel", (131, 127, 124)),
    ("stone", (129, 129, 129)), ("andesite", (136, 136, 138)),
    ("granite", (154, 111, 92)), ("diorite", (188, 188, 190)),
    ("cobble", (122, 122, 122)), ("tuff", (108, 109, 102)),
]
# Small plants/flowers can be the top block over grass; from above they read as
# the grassland they dot, so colour them green rather than picking out each one.
_PLANTS = ("flower", "poppy", "dandelion", "bluet", "daisy", "cornflower",
           "tulip", "orchid", "allium", "lily", "vine", "bush", "bamboo",
           "sugar_cane", "sapling", "sprouts", "lotus", "roots", "grass",
           "fern", "pitcher", "petals", "mushroom")
_GRASS = (98, 148, 66)
_UNKNOWN = (150, 120, 150)
_rgb_cache = {}


def block_rgb(name):
    c = _rgb_cache.get(name)
    if c is not None:
        return c
    c = BLOCK_RGB.get(name)
    if c is None:
        if name.endswith("_ore"):
            c = (132, 131, 128)                  # ore embedded in stone
        elif any(k in name for k in _PLANTS):
            c = _GRASS
        else:
            c = _UNKNOWN
            for key, col in _FAMILY:
                if key in name:
                    c = col
                    break
    _rgb_cache[name] = c
    return c


def _names_to_rgb(names):
    """16x16 (z,x) name array -> (16,16,3) float rgb."""
    out = np.empty((16, 16, 3), dtype=np.float64)
    for lz in range(16):
        for lx in range(16):
            out[lz, lx] = block_rgb(names[lz, lx])
    return out


def _region_files(world_region_dir):
    if not os.path.isdir(world_region_dir):
        return {}
    return {os.path.join(world_region_dir, f): os.path.getmtime(
                os.path.join(world_region_dir, f))
            for f in os.listdir(world_region_dir) if f.endswith(".mca")}


def _accumulate(world_region_dir, size):
    """Paint every full chunk into per-face colour accumulators. Returns
    {face: (acc HxWx3, cnt HxW)} at texture resolution `size`."""
    # float32 keeps the higher-resolution accumulators light (sums of 0-255 over
    # <=256 columns stay well within float32 range).
    acc = {f: np.zeros((size, size, 3), dtype=np.float32) for f in GRID}
    cnt = {f: np.zeros((size, size), dtype=np.float32) for f in GRID}
    lz, lx = np.meshgrid(np.arange(16), np.arange(16), indexing="ij")  # (z,x)
    for path in _region_files(world_region_dir):
        for cx, cz, names in read_region(path):
            wx = cx * 16 + lx + 0.5
            wz = cz * 16 + lz + 0.5
            # face from the chunk centre; keep only columns that land on it
            col = int((cx * 16 + 8 + H) // FACE)
            row = int((cz * 16 + 8 + H) // FACE)
            face = _CELL.get((col, row))
            if face is None:
                continue
            gc, gr = GRID[face]
            u = (wx - (gc * FACE - H)) / H - 1.0
            v = (wz - (gr * FACE - H)) / H - 1.0
            on = (np.abs(u) <= 1.0) & (np.abs(v) <= 1.0)
            if not on.any():
                continue
            i = np.clip(((u + 1) * 0.5 * (size - 1)).astype(np.int64), 0, size - 1)
            j = np.clip(((v + 1) * 0.5 * (size - 1)).astype(np.int64), 0, size - 1)
            rgb = _names_to_rgb(names)
            sel = on
            np.add.at(acc[face], (j[sel], i[sel]), rgb[sel])
            np.add.at(cnt[face], (j[sel], i[sel]), 1.0)
    return acc, cnt


def _dilate_mask(mask, rgb, iters=1):
    """Grow painted pixels by `iters` rings so ~1.6px-per-chunk coverage tiles
    without single-pixel gaps. Unpainted pixels take the mean of their painted
    4-neighbours; already-painted pixels are untouched."""
    for _ in range(iters):
        acc = np.zeros(rgb.shape, dtype=np.float64)
        w = np.zeros(mask.shape, dtype=np.float64)
        for dj, di in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            sm = np.roll(mask, (dj, di), axis=(0, 1))
            srgb = np.roll(rgb, (dj, di), axis=(0, 1))
            acc[sm] += srgb[sm]
            w[sm] += 1.0
        grow = (~mask) & (w > 0)
        rgb = rgb.copy()
        rgb[grow] = (acc[grow] / w[grow, None]).astype(rgb.dtype)
        mask = mask | grow
    return mask, rgb


# ------------------------------------------------------------- HTML rewriting
_URIS_RE = re.compile(r"(const FACE_URIS=\[)(.*?)(\];)", re.S)


def _decode_face(uri, size):
    b64 = uri.split(",", 1)[1]
    im = Image.open(io.BytesIO(base64.b64decode(b64))).convert("RGB")
    if im.size != (size, size):
        im = im.resize((size, size), Image.LANCZOS)
    return np.asarray(im).astype(np.uint8)


def _encode_pil(img):
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=88, subsampling=0)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode("ascii")


def _encode_face(arr):
    return _encode_pil(Image.fromarray(arr, "RGB"))


CITY_MAXPOP = 1_500_000
CITY_FILL = (255, 178, 44)
CITY_EDGE = (60, 36, 0)


def _paint_cities(img, city_list, size):
    """Draw amber, population-sized dots for the cities on this face, at the
    same (u,v)->pixel mapping the face texture uses (so they sit on terrain)."""
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    scale = size / 1024.0
    for u, v, pop in city_list:
        px = (u + 1) * 0.5 * (size - 1)
        py = (v + 1) * 0.5 * (size - 1)
        r = (1.6 + 8.5 * math.sqrt(max(pop, 0) / CITY_MAXPOP)) * scale
        d.ellipse([px - r, py - r, px + r, py + r], fill=CITY_FILL,
                  outline=CITY_EDGE, width=max(1, int(1.2 * scale)))


SH_RING = (170, 120, 255)      # violet hollow ring — distinct from amber city dots
SH_EDGE = (24, 10, 44)


def _paint_strongholds(img, sh_list, size):
    """Draw small hollow violet rings for strongholds (fixed size — a location
    marker, not a magnitude)."""
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    scale = size / 1024.0
    r = 3.4 * scale
    w = max(1, int(1.6 * scale))
    for u, v in sh_list:
        px = (u + 1) * 0.5 * (size - 1)
        py = (v + 1) * 0.5 * (size - 1)
        d.ellipse([px - r - w, py - r - w, px + r + w, py + r + w], outline=SH_EDGE, width=w)
        d.ellipse([px - r, py - r, px + r, py + r], outline=SH_RING, width=w)


TP_FILL = (54, 214, 205)       # teal diamond — teleport station
TP_EDGE = (10, 46, 44)


def _paint_stations(img, st_list, size):
    """Draw teal diamonds for teleport stations (the travel network)."""
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    scale = size / 1024.0
    r = 3.2 * scale
    for u, v in st_list:
        px = (u + 1) * 0.5 * (size - 1)
        py = (v + 1) * 0.5 * (size - 1)
        d.polygon([(px, py - r), (px + r, py), (px, py + r), (px - r, py)],
                  fill=TP_FILL, outline=TP_EDGE)


def face_uris_from_html(html):
    """Pull the six base face data-URIs out of a built globe.html (in
    FACE_ORDER), or None if the page doesn't have the expected array."""
    m = _URIS_RE.search(html)
    if not m:
        return None
    uris = re.findall(r'"([^"]+)"', m.group(2))
    return uris if len(uris) == 6 else None


def composite_uris(base_uris, world_region_dir, cities=None, strongholds=None, stations=None):
    """Overpaint the six base face textures with real generated blocks, and
    (optionally) paint historical-city dots, stronghold rings, and teleport
    stations on top. `cities` {face:[(u,v,pop)]}, `strongholds`/`stations`
    {face:[(u,v)]}. Returns (new_uris, painted_texels)."""
    if not base_uris or len(base_uris) != 6:
        return base_uris, 0
    size = Image.open(io.BytesIO(base64.b64decode(base_uris[0].split(",", 1)[1]))).size[0]
    acc, cnt = _accumulate(world_region_dir, size)
    painted = 0
    out = []
    for k, face in enumerate(FACE_ORDER):
        base = _decode_face(base_uris[k], size)
        c = cnt[face]
        mask = c > 0
        if mask.any():
            real = np.zeros_like(base)
            real[mask] = (acc[face][mask] / c[mask, None]).astype(np.uint8)
            mask, real = _dilate_mask(mask, real, iters=1)
            base = base.copy()
            base[mask] = real[mask]
            painted += int(mask.sum())
        img = Image.fromarray(base, "RGB")
        if strongholds and strongholds.get(face):
            _paint_strongholds(img, strongholds[face], size)
        if cities and cities.get(face):
            _paint_cities(img, cities[face], size)
        if stations and stations.get(face):
            _paint_stations(img, stations[face], size)
        out.append(_encode_pil(img))
    return out, painted


def composite_html(html, world_region_dir):
    """Return `html` with the six face textures overpainted by real blocks,
    plus the painted-texel count. Unchanged html on empty/error."""
    m = _URIS_RE.search(html)
    base = face_uris_from_html(html)
    if base is None:
        return html, 0
    new_uris, painted = composite_uris(base, world_region_dir)
    joined = ",\n".join(f'"{u}"' for u in new_uris)
    return html[:m.start()] + m.group(1) + "\n" + joined + "\n" + m.group(3) + html[m.end():], painted


def replace_uris(html, uris):
    """Splice a fresh set of six face data-URIs into a built globe.html."""
    m = _URIS_RE.search(html)
    if not m or not uris or len(uris) != 6:
        return html
    joined = ",\n".join(f'"{u}"' for u in uris)
    return html[:m.start()] + m.group(1) + "\n" + joined + "\n" + m.group(3) + html[m.end():]


def region_signature(world_region_dir):
    """A cheap fingerprint of the region set + mtimes, for cache invalidation."""
    return tuple(sorted(_region_files(world_region_dir).items()))


if __name__ == "__main__":
    import sys
    reg = sys.argv[1] if len(sys.argv) > 1 else \
        os.path.join(os.path.dirname(__file__),
                     "../../run/world/dimensions/minecraft/overworld/region")
    acc, cnt = _accumulate(reg, 1024)
    for f in FACE_ORDER:
        n = int((cnt[f] > 0).sum())
        if n:
            print(f"{f}: {n} texels painted")
