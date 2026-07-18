"""CLI for cube-map orientation previews.

Examples:
  python -m cubemap orient --roll -75
  python -m cubemap net --roll -75
  python -m cubemap sweep --from -90 --to -60 --step 5
  python -m cubemap all --roll -75          # base + overlay + net in one go
"""
from __future__ import annotations
import argparse
import os

from .geometry import CubeProjection
from .earthdata import build_equirect
from . import render

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(HERE, "data")
OUT = os.path.join(HERE, "out")

_BASE_CACHE = {}


def get_base(width):
    if width not in _BASE_CACHE:
        _BASE_CACHE[width] = build_equirect(DATA, width=width)
    return _BASE_CACHE[width]


def main(argv=None):
    p = argparse.ArgumentParser(prog="cubemap", description="CubeWorld orientation previews")
    p.add_argument("--width", type=int, default=2880, help="equirect base width (px)")
    p.add_argument("--out", default=OUT, help="output directory")
    sub = p.add_subparsers(dest="cmd", required=True)

    po = sub.add_parser("orient", help="equirectangular map with seam/pillar overlay")
    po.add_argument("--roll", type=float, default=-75.0)
    po.add_argument("--tilt", type=float, default=0.0)

    pn = sub.add_parser("net", help="cube-net reprojection")
    pn.add_argument("--roll", type=float, default=-75.0)
    pn.add_argument("--tilt", type=float, default=0.0)
    pn.add_argument("--face-px", type=int, default=360)

    pa = sub.add_parser("all", help="base + overlay + net")
    pa.add_argument("--roll", type=float, default=-75.0)
    pa.add_argument("--tilt", type=float, default=0.0)
    pa.add_argument("--face-px", type=int, default=360)

    ps = sub.add_parser("sweep", help="contact sheet of nets across rolls")
    ps.add_argument("--from", dest="lo", type=float, required=True)
    ps.add_argument("--to", dest="hi", type=float, required=True)
    ps.add_argument("--step", type=float, default=15.0)
    ps.add_argument("--tilt", type=float, default=0.0)
    ps.add_argument("--face-px", type=int, default=200)

    a = p.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    base = get_base(a.width)

    if a.cmd in ("orient", "all"):
        proj = CubeProjection(a.roll, a.tilt)
        f = os.path.join(a.out, f"orient_roll{a.roll:g}_tilt{a.tilt:g}.png")
        render.overlay(base, proj).save(f)
        print("wrote", f)
    if a.cmd in ("net", "all"):
        proj = CubeProjection(a.roll, a.tilt)
        f = os.path.join(a.out, f"net_roll{a.roll:g}_tilt{a.tilt:g}.png")
        render.net(base, proj, face_px=a.face_px).save(f)
        print("wrote", f)
    if a.cmd == "all":
        f = os.path.join(a.out, "earth_equirect.png")
        from PIL import Image
        Image.fromarray(base).save(f)
        print("wrote", f)
    if a.cmd == "sweep":
        rolls = []
        x = a.lo
        while x <= a.hi + 1e-6:
            rolls.append(round(x, 3))
            x += a.step
        f = os.path.join(a.out, f"sweep_{a.lo:g}_{a.hi:g}_{a.step:g}.png")
        render.sweep(base, rolls, face_px=a.face_px, tilt=a.tilt).save(f)
        print("wrote", f)


if __name__ == "__main__":
    main()
