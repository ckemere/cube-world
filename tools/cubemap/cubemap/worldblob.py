"""Convert the high-res spherical Earth source data into a compact CUBE-FACE
world blob for the portable biome/map generator.

Pipeline (as designed): project the high-res spherical source onto the six cube
faces (using the locked orientation), then DOWNSAMPLE each face to a small
climate raster — this area-averages correctly and is scale-independent (faces
are normalised u,v in [-1,1], so the blob works at any block magnification).
Sharp features the downsample would smear away are kept as VECTORS: river
centre-lines, lake outlines, and mountain peaks, all in face (u,v) coords.

Outputs (into out/):
  worldblob.cwb          binary: per-field per-face int16 climate rasters
  worldblob_vectors.json rivers / lakes / peaks as face-local vectors
  worldblob.json         manifest (dims, scales, roll, sizes)

The strong Earth constraint is the rasters (elevation, temperature, precip);
local biome variation is added later by the generator as seed-based noise on top.

Run: cd tools/cubemap && python3 -m cubemap.worldblob [--lores 256] [--hires 1024]
"""
from __future__ import annotations

import argparse
import json
import os
import struct

import numpy as np

from .geometry import CubeProjection, EARTH_ROLL_DEG, FACES
from .raster import EquirectRaster

DATA = os.path.join(os.path.dirname(__file__), "..", "data")
OUT = os.path.join(os.path.dirname(__file__), "..", "out")

# int16 encodings: (name, scale, ocean-fill). value = raw*scale.
FIELDS = [("elevation", 1.0), ("temperature", 0.1), ("precip", 1.0)]


def _project(proj, face, hires, etopo, temp, precip):
    """High-res per-face fields, ocean gaps in the climate filled with a proxy."""
    lon, lat = proj.face_lonlat_grid(face, hires)
    elev = etopo.sample(lon, lat)
    t = temp.sample(lon, lat)
    p = precip.sample(lon, lat)
    proxy = 27.0 - np.abs(lat) * 0.45            # WorldClim is land-only; sea proxy
    t = np.where(np.isnan(t), proxy, t)
    p = np.where(np.isnan(p), 0.0, p)
    return elev.astype(np.float32), t.astype(np.float32), p.astype(np.float32)


def _downsample(a, lores):
    """Area-average an (h,h) field down to (lores,lores)."""
    h = a.shape[0]
    f = h // lores
    return a[:lores * f, :lores * f].reshape(lores, f, lores, f).mean(axis=(1, 3))


def _enc(a, scale):
    return np.clip(np.round(a / scale), -32767, 32767).astype("<i2")


def _local_max_mask(a, thresh):
    """Pixels >= all 8 neighbours and above `thresh` (edge-padded)."""
    h, w = a.shape
    pad = np.pad(a, 1, mode="edge")
    m = a > thresh
    for di in (-1, 0, 1):
        for dj in (-1, 0, 1):
            if di == 0 and dj == 0:
                continue
            m &= a >= pad[1 + di:1 + di + h, 1 + dj:1 + dj + w]
    return m


def _peaks(elev_hi, tvals, thresh, cell, cap):
    """Prominent local maxima as [u, v, elev_m], thinned to one per `cell` block
    and capped — so the sharp mountains the raster loses survive as points."""
    ii, jj = np.nonzero(_local_max_mask(elev_hi, thresh))
    best = {}
    for i, j in zip(ii.tolist(), jj.tolist()):
        key = (i // cell, j // cell)
        e = float(elev_hi[i, j])
        if key not in best or e > best[key][2]:
            best[key] = (i, j, e)
    top = sorted(best.values(), key=lambda t: -t[2])[:cap]
    return [[round(float(tvals[j]), 4), round(float(tvals[i]), 4), int(e)] for i, j, e in top]


def _project_polyline(proj, coords):
    """Split a lon/lat polyline into per-face (u,v) runs (broken at face edges)."""
    segs = []
    cur_face, cur = None, []
    for lon, lat in coords:
        f, u, v = proj.lonlat_to_face_uv(lon, lat)
        pt = [round(float(u), 4), round(float(v), 4)]
        if f != cur_face:
            if len(cur) > 1:
                segs.append((cur_face, cur))
            cur_face, cur = f, [pt]
        else:
            cur.append(pt)
    if len(cur) > 1:
        segs.append((cur_face, cur))
    return segs


def _iter_lines(geojson_path):
    if not os.path.exists(geojson_path):
        return
    for feat in json.load(open(geojson_path))["features"]:
        g = feat.get("geometry") or {}
        t = g.get("type")
        if t == "LineString":
            yield g["coordinates"]
        elif t == "MultiLineString":
            yield from g["coordinates"]
        elif t == "Polygon":
            yield from g["coordinates"]                 # each ring
        elif t == "MultiPolygon":
            for poly in g["coordinates"]:
                yield from poly


def _vectors(proj, path, per_face):
    for coords in _iter_lines(path):
        for face, run in _project_polyline(proj, coords):
            per_face.setdefault(face, []).append(run)


def build(data_dir, out_dir, lores, hires, roll, peak_thresh=1400, peak_cell=6, peak_cap=1200):
    proj = CubeProjection(roll_deg=roll, tilt_deg=0.0)
    etopo = EquirectRaster.load_etopo(os.path.join(data_dir, "etopo_60s.nc"))
    temp = EquirectRaster.load_geotiff(os.path.join(data_dir, "wc2.1_10m_bio_1.tif"))
    precip = EquirectRaster.load_geotiff(os.path.join(data_dir, "wc2.1_10m_bio_12.tif"))
    tvals = np.linspace(-1.0, 1.0, hires)

    rasters = {name: [] for name, _ in FIELDS}       # field -> [face grids] in FACE order
    peaks = {}
    for face in FACES:
        elev_hi, t_hi, p_hi = _project(proj, face, hires, etopo, temp, precip)
        rasters["elevation"].append(_downsample(elev_hi, lores))
        rasters["temperature"].append(_downsample(t_hi, lores))
        rasters["precip"].append(_downsample(p_hi, lores))
        peaks[face] = _peaks(elev_hi, tvals, peak_thresh, peak_cell, peak_cap)

    rivers, lakes = {}, {}
    _vectors(proj, os.path.join(data_dir, "ne_10m_rivers_lake_centerlines.geojson"), rivers)
    _vectors(proj, os.path.join(data_dir, "ne_10m_lakes.geojson"), lakes)

    os.makedirs(out_dir, exist_ok=True)
    # binary climate blob
    cwb = os.path.join(out_dir, "worldblob.cwb")
    with open(cwb, "wb") as f:
        f.write(b"CWB1")
        f.write(struct.pack("<f", float(roll)))
        f.write(struct.pack("<i", lores))
        f.write(struct.pack("<i", len(FIELDS)))
        for name, scale in FIELDS:
            f.write(name.encode("ascii")[:12].ljust(12, b"\0"))
            f.write(struct.pack("<f", scale))
        for name, scale in FIELDS:
            for grid in rasters[name]:
                f.write(_enc(grid, scale).tobytes())
    # vectors
    vecs = {"roll": roll, "faces": FACES, "rivers": rivers, "lakes": lakes, "peaks": peaks}
    vpath = os.path.join(out_dir, "worldblob_vectors.json")
    json.dump(vecs, open(vpath, "w"), separators=(",", ":"))
    # manifest
    manifest = {
        "roll": roll, "lores": lores, "hires": hires, "faces": FACES,
        "fields": [{"name": n, "scale": s} for n, s in FIELDS],
        "counts": {
            "peaks": {f: len(peaks[f]) for f in FACES},
            "river_runs": {f: len(rivers.get(f, [])) for f in FACES},
            "lake_runs": {f: len(lakes.get(f, [])) for f in FACES},
        },
        "bytes": {"cwb": os.path.getsize(cwb), "vectors": os.path.getsize(vpath)},
    }
    json.dump(manifest, open(os.path.join(out_dir, "worldblob.json"), "w"), indent=1)
    return manifest


def main(argv=None):
    p = argparse.ArgumentParser(prog="worldblob")
    p.add_argument("--data", default=DATA)
    p.add_argument("--out", default=OUT)
    p.add_argument("--lores", type=int, default=256, help="climate raster size per face")
    p.add_argument("--hires", type=int, default=1024, help="projection size before downsample")
    p.add_argument("--roll", type=float, default=EARTH_ROLL_DEG)
    a = p.parse_args(argv)
    m = build(a.data, a.out, a.lores, a.hires, a.roll)
    print(f"worldblob: {a.lores}^2 x6 x{len(FIELDS)} climate  "
          f"({m['bytes']['cwb']/1e6:.2f} MB) + vectors ({m['bytes']['vectors']/1e6:.2f} MB)")
    print("  peaks/face:", m["counts"]["peaks"])
    print("  river runs/face:", m["counts"]["river_runs"])


if __name__ == "__main__":
    main()
