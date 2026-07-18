"""Render orientation previews: equirectangular-with-overlay, and the cube net."""
from __future__ import annotations
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from .geometry import CubeProjection, FACES, GRID, FACE_LABEL

SEAM = (220, 40, 40)
PILLAR = (250, 210, 40)
PILLAR_LAND = (240, 90, 40)
BORDER = (210, 210, 210)


def _font(size):
    try:
        return ImageFont.truetype("DejaVuSans-Bold.ttf", size)
    except Exception:
        return ImageFont.load_default()


def _sample(base, lon, lat):
    """Nearest-neighbour sample of an equirect base at lon/lat arrays."""
    h, w, _ = base.shape
    px = ((lon + 180.0) / 360.0 * w).astype(int) % w
    py = np.clip(((90.0 - lat) / 180.0 * h).astype(int), 0, h - 1)
    return base[py, px]


def _polyline_px(pts, w, h):
    """lon/lat polyline -> list of pixel segments, split at the dateline."""
    segs, cur = [], []
    for lon, lat in pts:
        x = (lon + 180.0) / 360.0 * w
        y = (90.0 - lat) / 180.0 * h
        if cur and abs(x - cur[-1][0]) > w / 2:
            if len(cur) > 1:
                segs.append(cur)
            cur = []
        cur.append((x, y))
    if len(cur) > 1:
        segs.append(cur)
    return segs


def _is_land(base, lon, lat):
    c = _sample(base, np.array([lon]), np.array([lat]))[0]
    # land tints are greenish/tan; ocean tints are blue (B dominates)
    r, g, b = int(c[0]), int(c[1]), int(c[2])
    return not (b > g and b > r)


def overlay(base, proj: CubeProjection, label=True):
    h, w, _ = base.shape
    img = Image.fromarray(base.copy())
    d = ImageDraw.Draw(img)
    # equatorial seams
    for pts in proj.equatorial_seam_polylines():
        for seg in _polyline_px(pts, w, h):
            d.line(seg, fill=SEAM, width=max(2, w // 900))
    # polar face boundaries
    for face in ("NORTH_POLE", "SOUTH_POLE"):
        for edge in proj.polar_face_edges(face):
            for seg in _polyline_px(edge, w, h):
                d.line(seg, fill=SEAM, width=max(2, w // 900))
    # pillars
    r = max(4, w // 300)
    fnt = _font(max(11, w // 140))
    for i, (lon, lat) in enumerate(proj.pillars()):
        x = (lon + 180.0) / 360.0 * w
        y = (90.0 - lat) / 180.0 * h
        col = PILLAR_LAND if _is_land(base, lon, lat) else PILLAR
        d.ellipse([x - r, y - r, x + r, y + r], fill=col, outline=(0, 0, 0))
    if label:
        fnt2 = _font(max(12, w // 110))
        d.text((6, 6), f"roll {proj.roll_deg:g}  tilt {proj.tilt_deg:g}"
                       "   red=seams  yellow=ocean pillar  orange=land pillar",
               fill=(255, 255, 255), font=fnt2)
    return img


def net(base, proj: CubeProjection, face_px=360, label=True):
    h, w, _ = base.shape
    cols, rows = 3, 4
    canvas = np.full((rows * face_px, cols * face_px, 3), (20, 20, 24), dtype=np.uint8)
    for face in FACES:
        gc, gr = GRID[face]
        cx, cy = (gc + 1) * face_px, (gr + 1) * face_px
        lon, lat = proj.face_lonlat_grid(face, face_px)
        tile = _sample(base, lon, lat)
        canvas[cy:cy + face_px, cx:cx + face_px] = tile
    img = Image.fromarray(canvas)
    d = ImageDraw.Draw(img)
    fnt = _font(max(14, face_px // 16))
    for face in FACES:
        gc, gr = GRID[face]
        cx, cy = (gc + 1) * face_px, (gr + 1) * face_px
        d.rectangle([cx, cy, cx + face_px - 1, cy + face_px - 1], outline=BORDER, width=2)
        if label:
            d.text((cx + 6, cy + 6), FACE_LABEL[face], fill=(255, 255, 40), font=fnt)
    return img


def sweep(base, rolls, face_px=200, tilt=0.0, mode="net", thumb_w=760):
    """Contact sheet across a list of rolls.

    mode='net'     -> cube-net thumbnails (see continent splits)
    mode='overlay' -> equirect+seam/pillar thumbnails (see where pillars land)
    """
    tiles = []
    for roll in rolls:
        proj = CubeProjection(roll_deg=roll, tilt_deg=tilt)
        if mode == "overlay":
            im = overlay(base, proj, label=False).convert("RGB")
            h = int(im.height * thumb_w / im.width)
            im = im.resize((thumb_w, h))
        else:
            im = net(base, proj, face_px=face_px, label=False).convert("RGB")
        d = ImageDraw.Draw(im)
        d.text((6, 6), f"roll {roll:g}", fill=(255, 255, 40),
               font=_font(max(16, im.width // 24)))
        tiles.append(im)
    tw, th = tiles[0].size
    per_row = 2 if mode == "overlay" else min(4, len(tiles))
    nrows = (len(tiles) + per_row - 1) // per_row
    sheet = Image.new("RGB", (per_row * tw, nrows * th), (10, 10, 12))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % per_row) * tw, (i // per_row) * th))
    return sheet
