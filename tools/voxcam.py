#!/usr/bin/env python3
"""Point a camera anywhere in the world and get a PNG. No client, no server.

An arbitrary-camera voxel raycaster straight over the region files, reusing
tools/playermap/anvil.py for NBT and realmap.py for the block->RGB palette.
Amanatides-Woo DDA vectorised over every pixel with numpy; face-normal lambert
shading plus distance fog. Perspective and isometric both work.

    python3 tools/voxcam.py 11731 -3902 --yaw 45 --pitch 42 -r 200 -o shot.png
    python3 tools/voxcam.py 11731 -3902 --ortho --scale 0.6 -o iso.png

-y defaults to ground+6. Roughly 2-13 s a frame and ~130 MB, so it runs
happily beside the dev server -- unlike anything JVM-based on this box.

Why it exists: terrain bugs were being chased through block counts and
cross-sections, which is slow and misleading. Three regen cycles went into
"cliffs" at Antioch that turned out to be a village generating in the sea --
a single aerial frame settled it immediately.

Limits worth knowing before trusting an image: no textures, no transparency
(water reads flat opaque), and block SHAPES are ignored, so stairs, slabs,
fences and torches all render as full cubes. The palette comes from
realmap.py, which was tuned for a top-down terrain map, so built and interior
blocks that a perspective camera can see (glass, wool, obsidian, netherrack)
fall through to magenta. Magenta means "unknown block", not a world bug.
"""
import sys, os, time, struct
import numpy as np

TOOLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "playermap")
sys.path.insert(0, TOOLS)
import anvil                      # noqa: E402
from realmap import block_rgb     # noqa: E402

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGION = os.path.join(_ROOT, "run/world/dimensions/minecraft/overworld/region")
MIN_Y = -64


# ---------------------------------------------------------------- world load
def load_box(x0, z0, x1, z1, y0, y1):
    """Dense uint16 palette-id volume for the block box [x0,x1) x [y0,y1) x [z0,z1)."""
    nx, ny, nz = x1 - x0, y1 - y0, z1 - z0
    vol = np.zeros((nx, ny, nz), dtype=np.uint16)   # 0 = air
    names = ["minecraft:air"]
    name_id = {"minecraft:air": 0}

    cx0, cx1 = x0 >> 4, (x1 - 1) >> 4
    cz0, cz1 = z0 >> 4, (z1 - 1) >> 4
    regions = {}
    for cx in range(cx0, cx1 + 1):
        for cz in range(cz0, cz1 + 1):
            regions.setdefault((cx >> 5, cz >> 5), []).append((cx, cz))

    nchunks = 0
    for (rx, rz), chunks in regions.items():
        path = os.path.join(REGION, f"r.{rx}.{rz}.mca")
        if not os.path.exists(path):
            continue
        data = open(path, "rb").read()
        for (cx, cz) in chunks:
            slot = ((cx & 31) + (cz & 31) * 32)
            off = (data[slot * 4] << 16) | (data[slot * 4 + 1] << 8) | data[slot * 4 + 2]
            if off == 0:
                continue
            st = off * 4096
            if st + 5 > len(data):
                continue
            ln = struct.unpack_from(">I", data, st)[0]
            if ln == 0 or st + 4 + ln > len(data):
                continue
            try:
                raw = anvil._decompress(data[st + 5:st + 4 + ln], data[st + 4])
                root = anvil._NBT(raw).root()
            except Exception:
                continue
            if root.get("Status") != "minecraft:full":
                continue
            nchunks += 1
            bx, bz = cx * 16, cz * 16
            for sec in root.get("sections") or []:
                sy = sec.get("Y")
                by = sy * 16
                if by + 16 <= y0 or by >= y1:
                    continue
                got = anvil._section_blocks(sec)
                if got is None:
                    continue
                palette, idx = got
                # map this section's palette into the global id space
                lut = np.empty(len(palette), dtype=np.uint16)
                for i, nm in enumerate(palette):
                    gid = name_id.get(nm)
                    if gid is None:
                        gid = len(names)
                        name_id[nm] = gid
                        names.append(nm)
                    lut[i] = gid
                block = lut[idx.reshape(16, 16, 16)]        # (y, z, x)
                block = np.transpose(block, (2, 0, 1))      # (x, y, z)
                # destination window
                dx0, dy0, dz0 = bx - x0, by - y0, bz - z0
                sx0, sxe = max(0, -dx0), min(16, nx - dx0)
                sy0, sye = max(0, -dy0), min(16, ny - dy0)
                sz0, sze = max(0, -dz0), min(16, nz - dz0)
                if sx0 >= sxe or sy0 >= sye or sz0 >= sze:
                    continue
                vol[dx0 + sx0:dx0 + sxe, dy0 + sy0:dy0 + sye, dz0 + sz0:dz0 + sze] = \
                    block[sx0:sxe, sy0:sye, sz0:sze]
    return vol, names, nchunks


# ---------------------------------------------------------------- raycaster
def render(vol, names, origin, yaw_deg, pitch_deg, fov_deg=70,
           W=480, H=320, max_steps=900, ortho=False, ortho_scale=0.35):
    nx, ny, nz = vol.shape

    # palette -> rgb + "is air"
    pal = np.zeros((len(names), 3), dtype=np.float32)
    for i, nm in enumerate(names):
        pal[i] = block_rgb(nm)
    solid = np.ones(len(names), dtype=bool)
    for i, nm in enumerate(names):
        if nm in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
            solid[i] = False

    yaw, pitch = np.radians(yaw_deg), np.radians(pitch_deg)
    # forward / right / up basis (MC: yaw 0 = +Z, yaw -90 = +X)
    fwd = np.array([-np.sin(yaw) * np.cos(pitch), -np.sin(pitch), np.cos(yaw) * np.cos(pitch)])
    right = np.array([np.cos(yaw), 0.0, np.sin(yaw)])
    up = np.cross(fwd, right)

    aspect = W / H
    t = np.tan(np.radians(fov_deg) / 2)
    px = (np.arange(W) + 0.5) / W * 2 - 1
    py = 1 - (np.arange(H) + 0.5) / H * 2
    gx, gy = np.meshgrid(px, py)

    if ortho:
        dirs = np.broadcast_to(fwd, (H, W, 3)).copy()
        offs = (gx[..., None] * right * (W * ortho_scale / 2)
                + gy[..., None] * up * (H * ortho_scale / 2))
        pos = origin + offs
    else:
        dirs = fwd + gx[..., None] * right * t * aspect + gy[..., None] * up * t
        dirs /= np.linalg.norm(dirs, axis=-1, keepdims=True)
        pos = np.broadcast_to(np.asarray(origin, dtype=np.float64), (H, W, 3)).copy()

    dirs = dirs.reshape(-1, 3)
    pos = pos.reshape(-1, 3).astype(np.float64)
    N = dirs.shape[0]

    # Amanatides-Woo DDA
    d = np.where(np.abs(dirs) < 1e-9, 1e-9, dirs)
    step = np.sign(d).astype(np.int32)
    cell = np.floor(pos).astype(np.int32)
    tdelta = np.abs(1.0 / d)
    nxt = cell + (step > 0)
    tmax = (nxt - pos) / d

    hit_id = np.zeros(N, dtype=np.uint16)
    hit_axis = np.zeros(N, dtype=np.int8)
    hit_dist = np.full(N, np.inf)
    alive = np.ones(N, dtype=bool)

    cross_axis = np.zeros(N, dtype=np.int8)   # face last crossed = surface normal axis
    travelled = np.zeros(N)

    for _ in range(max_steps):
        idx = np.flatnonzero(alive)
        if idx.size == 0:
            break
        c = cell[idx]
        inb = ((c[:, 0] >= 0) & (c[:, 0] < nx) & (c[:, 1] >= 0)
               & (c[:, 1] < ny) & (c[:, 2] >= 0) & (c[:, 2] < nz))

        # kill rays that are outside the box and heading further out
        outi = idx[~inb]
        if outi.size:
            alive[outi[_escaped(cell[outi], nx, ny, nz, step[outi])]] = False

        ini = idx[inb]
        if ini.size:
            ci = cell[ini]
            v = vol[ci[:, 0], ci[:, 1], ci[:, 2]]
            hits = solid[v]
            h = ini[hits]
            if h.size:
                hit_id[h] = v[hits]
                hit_axis[h] = cross_axis[h]
                hit_dist[h] = travelled[h]
                alive[h] = False

        idx = np.flatnonzero(alive)
        if idx.size == 0:
            break
        ax = np.argmin(tmax[idx], axis=1)
        travelled[idx] = tmax[idx, ax]
        cross_axis[idx] = ax
        tmax[idx, ax] += tdelta[idx, ax]
        cell[idx, ax] += step[idx, ax]

    # ------- shade
    img = np.zeros((N, 3), dtype=np.float32)
    hitm = hit_id != 0
    base = pal[hit_id[hitm]]
    # lambert from the crossed face normal + sun
    sun = np.array([0.42, 0.80, 0.43]); sun /= np.linalg.norm(sun)
    ax = hit_axis[hitm]
    nrm = np.zeros((ax.size, 3), dtype=np.float32)
    nrm[np.arange(ax.size), ax] = -np.sign(dirs[hitm][np.arange(ax.size), ax])
    lam = np.clip((nrm * sun).sum(axis=1), 0, 1) * 0.65 + 0.35
    shaded = base * lam[:, None]
    # distance fog
    dist = np.clip(hit_dist[hitm] / 400.0, 0, 1)[:, None]
    skyc = np.array([150, 180, 220], dtype=np.float32)
    img[hitm] = shaded * (1 - dist) + skyc * dist
    img[~hitm] = skyc
    return np.clip(img.reshape(H, W, 3), 0, 255).astype(np.uint8)


def _escaped(c, nx, ny, nz, step):
    # a ray outside the box moving further out will never come back
    out = np.zeros(c.shape[0], dtype=bool)
    for a, n in ((0, nx), (1, ny), (2, nz)):
        out |= (c[:, a] < 0) & (step[:, a] <= 0)
        out |= (c[:, a] >= n) & (step[:, a] >= 0)
    return out


def snap(wx, wy, wz, yaw, pitch, out, fov=75, w=800, h=500, radius=192,
         ymin=-64, ymax=320, ortho=False, ortho_scale=0.5, region_dir=None):
    """Put a camera at world (wx,wy,wz) looking at heading `yaw`/`pitch`; write a PNG."""
    from PIL import Image
    global REGION
    if region_dir:
        REGION = region_dir
    y0, y1 = max(MIN_Y, ymin), min(320, ymax)
    vol, names, nch = load_box(wx - radius, wz - radius, wx + radius, wz + radius, y0, y1)
    if wy is None:                                   # auto: 6 blocks over the ground
        col = np.flatnonzero(vol[radius, :, radius] != 0)
        wy = y0 + (int(col[-1]) + 6 if col.size else 100)
    origin = (radius + 0.5, wy - y0, radius + 0.5)
    img = render(vol, names, origin, yaw_deg=yaw, pitch_deg=pitch, fov_deg=fov,
                 W=w, H=h, ortho=ortho, ortho_scale=ortho_scale)
    Image.fromarray(img).save(out)
    return out, nch, wy


if __name__ == "__main__":
    import argparse, time
    ap = argparse.ArgumentParser(description="Render a MC world from an arbitrary camera.")
    ap.add_argument("x", type=int); ap.add_argument("z", type=int)
    ap.add_argument("-y", type=int, default=None, help="eye Y (default: ground+6)")
    ap.add_argument("--yaw", type=float, default=45.0, help="heading deg (0=+Z, 90=-X)")
    ap.add_argument("--pitch", type=float, default=15.0, help="deg, positive = look down")
    ap.add_argument("--fov", type=float, default=75.0)
    ap.add_argument("-o", "--out", default="shot.png")
    ap.add_argument("-w", type=int, default=800); ap.add_argument("--height", type=int, default=500)
    ap.add_argument("-r", "--radius", type=int, default=192)
    ap.add_argument("--ortho", action="store_true", help="isometric/parallel projection")
    ap.add_argument("--scale", type=float, default=0.5, help="blocks per pixel when --ortho")
    ap.add_argument("--region", default=None)
    a = ap.parse_args()
    t = time.time()
    out, nch, wy = snap(a.x, a.y, a.z, a.yaw, a.pitch, a.out, fov=a.fov, w=a.w, h=a.height,
                        radius=a.radius, ortho=a.ortho, ortho_scale=a.scale, region_dir=a.region)
    print(f"{out}  ({nch} chunks, eye y={wy}, {time.time()-t:.1f}s)")
