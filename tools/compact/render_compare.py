"""Render a side-by-side cube-net comparison: the CURRENT world fields
(sampled straight from the full-resolution earth.dat) versus a PROPOSED compact
parameterisation, in three layers - height, climate, height+climate.

Emits a self-contained HTML page (PNGs inlined) so it can be opened directly or
served by the playermap server.
"""
import argparse
import base64
import io
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "cubemap"))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "playermap"))

from earthdat import EarthDat                     # noqa: E402
import model as M                                 # noqa: E402
from cubemap.geometry import CubeProjection, FACES, GRID, FACE_LABEL   # noqa: E402
from biomegen import earthclimate as EC           # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "out")


# ------------------------------------------------------------------ colour maps
def hypsometric(h):
    """Elevation -> RGB. Ocean by depth, land greens->browns->snow."""
    stops = [
        (-8000, (8, 20, 60)), (-4000, (12, 40, 100)), (-1000, (20, 70, 150)),
        (-200, (48, 110, 190)), (-10, (110, 170, 220)), (0, (200, 220, 200)),
        (150, (70, 130, 70)), (600, (120, 160, 80)), (1500, (170, 150, 100)),
        (3000, (150, 120, 95)), (4500, (200, 195, 190)), (8849, (255, 255, 255)),
    ]
    xs = np.array([s[0] for s in stops], dtype=np.float64)
    cs = np.array([s[1] for s in stops], dtype=np.float64)
    hh = np.clip(h, xs[0], xs[-1])
    out = np.empty(hh.shape + (3,))
    for c in range(3):
        out[..., c] = np.interp(hh, xs, cs[:, c])
    return out.astype(np.uint8)


def climate_rgb(temp_c, precip_mm, land):
    """Climate -> RGB via the same T/H params the generator uses: hue from
    temperature, saturation/lightness from humidity. Ocean is flat blue-grey so
    the land signal is what you compare."""
    t = np.clip((temp_c + 25.0) / 65.0, 0, 1)             # -25..40 C
    hnorm = np.clip((np.log10(np.maximum(precip_mm, 1.0)) - 1.6) / 2.0, 0, 1)
    # cold=blue/white, temperate=green, hot+dry=tan, hot+wet=deep green
    cold = np.stack([0.75 + 0.25 * (1 - hnorm), 0.85 * np.ones_like(t), np.ones_like(t)], -1)
    warm_wet = np.stack([0.05 + 0.10 * (1 - hnorm), 0.45 + 0.25 * hnorm,
                         0.10 + 0.10 * hnorm], -1)
    hot_dry = np.stack([0.85, 0.78, 0.55], -1) * np.ones(t.shape + (3,))
    a = t[..., None]
    base = cold * (1 - a) + warm_wet * a
    dry = np.clip(1.0 - hnorm * 2.0, 0, 1)[..., None]
    base = base * (1 - dry) + hot_dry * dry
    rgb = (np.clip(base, 0, 1) * 255).astype(np.uint8)
    ocean = np.array([40, 60, 90], dtype=np.uint8)
    return np.where(land[..., None], rgb, ocean)


def temp_rgb(temp_c, land):
    """Temperature alone -> diverging cold(blue)/mild(cream)/hot(red), land only.
    Banded every 5 C so a smoothed field's isotherm displacement is visible."""
    t = np.clip((temp_c + 25.0) / 65.0, 0, 1)      # -25..40 C
    stops = [(0.00, (30, 50, 130)), (0.20, (70, 130, 200)), (0.38, (170, 210, 230)),
             (0.55, (245, 240, 200)), (0.72, (240, 170, 90)), (0.88, (215, 80, 50)),
             (1.00, (140, 20, 30))]
    xs = np.array([s[0] for s in stops]); cs = np.array([s[1] for s in stops], float)
    rgb = np.empty(t.shape + (3,))
    for c in range(3):
        rgb[..., c] = np.interp(t, xs, cs[:, c])
    band = (np.floor((temp_c + 25.0) / 5.0) % 2)[..., None]
    rgb *= (0.93 + 0.07 * band)
    ocean = np.array([32, 38, 50], float)
    return np.clip(np.where(land[..., None], rgb, ocean), 0, 255).astype(np.uint8)


def humid_rgb(precip_mm, land):
    """Humidity alone, on the same log scale the generator uses for H:
    arid(tan) -> semi-arid(khaki) -> temperate(green) -> wet(teal/blue)."""
    hn = np.clip((np.log10(np.maximum(precip_mm, 1.0)) - 1.6) / 2.0, 0, 1)
    stops = [(0.00, (215, 195, 150)), (0.25, (200, 190, 120)), (0.45, (150, 180, 100)),
             (0.65, (70, 150, 90)), (0.85, (30, 120, 120)), (1.00, (20, 70, 130))]
    xs = np.array([s[0] for s in stops]); cs = np.array([s[1] for s in stops], float)
    rgb = np.empty(hn.shape + (3,))
    for c in range(3):
        rgb[..., c] = np.interp(hn, xs, cs[:, c])
    band = (np.floor(hn * 10.0) % 2)[..., None]
    rgb *= (0.93 + 0.07 * band)
    ocean = np.array([32, 38, 50], float)
    return np.clip(np.where(land[..., None], rgb, ocean), 0, 255).astype(np.uint8)


def _inpaint(a, iters=64):
    """Fill NaN (WorldClim's ocean NODATA) by iteratively averaging valid
    neighbours. Required before downsampling: block-averaging a coastal cell
    together with a sentinel value drags every shoreline toward garbage, which
    renders as a spurious cold rim around every continent."""
    out = np.array(a, dtype=np.float64)
    nan = np.isnan(out)
    if not nan.any():
        return out
    out[nan] = 0.0
    known = (~nan).astype(np.float64)
    for _ in range(iters):
        if known.all():
            break
        def roll_sum(m):
            return (np.roll(m, 1, 0) + np.roll(m, -1, 0)
                    + np.roll(m, 1, 1) + np.roll(m, -1, 1))
        s = roll_sum(out * known)
        c = roll_sum(known)
        fill = (c > 0) & (known == 0)
        out[fill] = s[fill] / c[fill]
        known[fill] = 1.0
    if not known.all():                    # nothing reachable: global mean
        out[known == 0] = out[known == 1].mean() if (known == 1).any() else 0.0
    return out


def hillshade(h, km_per_px, z=6.0):
    gy, gx = np.gradient(h, km_per_px * 1000.0)
    slope = np.arctan(z * np.hypot(gx, gy))
    aspect = np.arctan2(-gx, gy)
    az = np.radians(315.0); alt = np.radians(45.0)
    s = (np.sin(alt) * np.cos(slope)
         + np.cos(alt) * np.sin(slope) * np.cos(az - aspect))
    return np.clip(s, 0, 1)


# ----------------------------------------------------------------- field source
class FullRes:
    """The current approach: sample the full-resolution rasters directly."""
    label = "CURRENT — full-resolution rasters"

    def __init__(self, e):
        self.e = e
        self.bytes = sum(l.nbytes for l in e.layers.values())
        self.detail = (f"height {e['height'].width}x{e['height'].height} + "
                       f"temp/precip {e['temp'].width}x{e['temp'].height} + "
                       f"river x2 — {self.bytes/1e6:.0f} MB resident")

    def height(self, lon, lat):
        return self.e["height"].sample_bilinear(lon, lat)

    def climate(self, lon, lat):
        return (self.e["temp"].sample_bilinear(lon, lat),
                self.e["precip"].sample_bilinear(lon, lat))


class Compact:
    """The proposed approach: coarse smooth fields + sparse named-peak bumps."""
    label = "PROPOSED — compact parameters"

    def __init__(self, e, hgrid_w, cgrid_w, peaks, quant=4.0, mask_w=4320):
        import zlib
        from islands import _upsample_mask, _resize_nearest
        W, H = 2160, 1080
        ref = np.nan_to_num(e["height"].block_mean(W, H), nan=0.0)
        self.h_model = M.Coarse(ref, hgrid_w, hgrid_w // 2, quant=quant)
        hrec = self.h_model.reconstruct()
        # Land/sea mask, kept SEPARATE from the elevation field. Block-averaging
        # elevation drowns small islands (a 10 km volcano diluted by 37 km of
        # -5000 m ocean goes negative); a binary mask is spatially coherent so it
        # costs almost nothing and restores them. Elevation sets how high, the
        # mask decides land vs sea.
        self.mask = _upsample_mask(e["height"], mask_w, mask_w // 2)
        self.mask_bytes = len(zlib.compress(np.packbits(self.mask.ravel()).tobytes(), 9))
        # NOTE: do NOT bake the mask's sign into hfield here. hfield is sampled
        # bilinearly, and interpolating between a +12 m island cell and its
        # -3000 m ocean neighbour crosses zero after 0.4% of a cell, so a
        # one-cell island would be land over ~70 m and vanish. The mask is kept at
        # its own (higher) resolution and applied AFTER interpolation, in
        # height() - which is also the whole point of storing it separately.
        self.bumps = M.PeakBumps(peaks, hrec.shape)
        self.hfield = self.bumps.apply(hrec)
        # WorldClim is land-only, so ocean cells are NODATA. They must be INPAINTED
        # before downsampling: averaging a coastal cell together with a sentinel
        # (or with 0) drags every shoreline toward garbage and shows up as a cold
        # rim around every continent.
        t = _inpaint(e["temp"].block_mean(W, H))
        p = _inpaint(e["precip"].block_mean(W, H))
        # climate is genuinely smooth -> very coarse grids suffice
        self.t_model = M.Coarse(t, cgrid_w, cgrid_w // 2, quant=0.5)
        self.p_model = M.Coarse(p, cgrid_w, cgrid_w // 2, quant=20.0)
        self.tfield = self.t_model.reconstruct()
        self.pfield = self.p_model.reconstruct()
        self.bytes = (self.h_model.nbytes + self.mask_bytes + self.bumps.nbytes
                      + self.t_model.nbytes + self.p_model.nbytes)
        self.detail = (f"height {hgrid_w}x{hgrid_w//2} ({self.h_model.nbytes/1e3:.0f} KB) "
                       f"+ land mask {mask_w}x{mask_w//2} ({self.mask_bytes/1e3:.0f} KB) "
                       f"+ {len(peaks)} peak bumps ({self.bumps.nbytes/1e3:.0f} KB) "
                       f"+ temp {cgrid_w}x{cgrid_w//2} ({self.t_model.nbytes/1e3:.0f} KB) "
                       f"+ humidity {cgrid_w}x{cgrid_w//2} ({self.p_model.nbytes/1e3:.0f} KB) "
                       f"— {self.bytes/1e6:.2f} MB total")

    @staticmethod
    def _samp(field, lon, lat):
        h, w = field.shape
        fx = (np.asarray(lon) + 180.0) / 360.0 * w
        fy = (90.0 - np.asarray(lat)) / 180.0 * h
        x0 = np.floor(fx).astype(int); y0 = np.floor(fy).astype(int)
        tx = fx - x0; ty = fy - y0
        x0m = np.mod(x0, w); x1m = np.mod(x0 + 1, w)
        y0c = np.clip(y0, 0, h - 1); y1c = np.clip(y0 + 1, 0, h - 1)
        return (field[y0c, x0m] * (1 - tx) * (1 - ty) + field[y0c, x1m] * tx * (1 - ty)
                + field[y1c, x0m] * (1 - tx) * ty + field[y1c, x1m] * tx * ty)

    def height(self, lon, lat):
        h = self._samp(self.hfield, lon, lat)
        # Land/sea comes from the mask at ITS resolution, applied after the
        # elevation interpolation. Elevation says how high; the mask says whether
        # there is land at all.
        mh, mw = self.mask.shape
        x = np.mod(((np.asarray(lon) + 180.0) / 360.0 * mw).astype(int), mw)
        y = np.clip(((90.0 - np.asarray(lat)) / 180.0 * mh).astype(int), 0, mh - 1)
        m = self.mask[y, x]
        return np.where(m, np.maximum(h, 15.0), np.minimum(h, -15.0))

    def climate(self, lon, lat):
        return self._samp(self.tfield, lon, lat), self._samp(self.pfield, lon, lat)


# --------------------------------------------------------------------- rendering
def render_face(src, geom, face, n, layer, km_per_px):
    lon, lat = geom.face_lonlat_grid(face, n)
    h = src.height(lon, lat)
    if layer == "height":
        rgb = hypsometric(h).astype(np.float64)
    else:
        t, p = src.climate(lon, lat)
        # WorldClim is land-only; ocean uses the generator's own proxies so the
        # panels match what the world actually does.
        t = np.where(np.isnan(t), 27.0 - np.abs(lat) * 0.45, t)
        p = np.where(np.isnan(p), 700.0, p)
        land = h >= 0
        if layer == "temp":
            rgb = temp_rgb(t, land).astype(np.float64)
        elif layer == "humid":
            rgb = humid_rgb(p, land).astype(np.float64)
        else:
            rgb = climate_rgb(t, p, land).astype(np.float64)
    if layer == "both":
        sh = hillshade(h, km_per_px)[..., None]
        rgb = rgb * (0.45 + 0.75 * sh)
    return np.clip(rgb, 0, 255).astype(np.uint8)


def png_uri(arr):
    buf = io.BytesIO()
    Image.fromarray(arr, "RGB").save(buf, format="PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--earth", default="run/earth.dat")
    ap.add_argument("--peaks", default="src/main/resources/peaks6000.csv")
    ap.add_argument("--size", type=int, default=384, help="pixels per cube face")
    ap.add_argument("--hgrid", type=int, default=1080)
    ap.add_argument("--cgrid", type=int, default=360)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    from fit import load_peaks
    e = EarthDat(args.earth)
    peaks = load_peaks(args.peaks)
    geom = CubeProjection()
    km_per_px = (40075.0 / 4) / args.size      # one face spans ~90 deg of arc

    print("building compact model...")
    sources = [FullRes(e), Compact(e, args.hgrid, args.cgrid, peaks)]
    for s in sources:
        print(f"  {s.label}: {s.detail}")

    data = {}
    for si, src in enumerate(sources):
        for layer in ("height", "temp", "humid", "climate", "both"):
            for face in FACES:
                print(f"  render {src.__class__.__name__:8} {layer:7} {face}")
                arr = render_face(src, geom, face, args.size, layer, km_per_px)
                data[f"{si}|{layer}|{face}"] = png_uri(arr)

    meta = [{"label": s.label, "detail": s.detail, "bytes": s.bytes} for s in sources]
    html = PAGE.replace("__DATA__", json.dumps(data)) \
               .replace("__META__", json.dumps(meta)) \
               .replace("__GRID__", json.dumps(GRID)) \
               .replace("__LABEL__", json.dumps(FACE_LABEL)) \
               .replace("__SIZE__", str(args.size))
    path = args.out or os.path.join(OUT, "compare.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"\nwrote {path} ({os.path.getsize(path)/1e6:.1f} MB)")


PAGE = """<!doctype html>
<meta charset="utf-8"><title>CubeWorld: current vs compact parameters</title>
<style>
 :root{--bg:#11131a;--fg:#e8eaf0;--dim:#9aa3b2;--line:#2a2f3d}
 body{margin:0;background:var(--bg);color:var(--fg);
      font:14px/1.5 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
 header{padding:16px 20px;border-bottom:1px solid var(--line)}
 h1{margin:0 0 4px;font-size:18px;font-weight:600}
 .sub{color:var(--dim);font-size:13px}
 .tabs{display:flex;gap:8px;padding:12px 20px}
 .tabs button{background:#1a1e28;color:var(--fg);border:1px solid var(--line);
   padding:7px 14px;border-radius:7px;cursor:pointer;font:inherit}
 .tabs button[aria-pressed=true]{background:#2d6cdf;border-color:#2d6cdf}
 .wrap{display:grid;grid-template-columns:1fr 1fr;gap:20px;padding:0 20px 28px}
 @media(max-width:1100px){.wrap{grid-template-columns:1fr}}
 .panel{border:1px solid var(--line);border-radius:10px;overflow:hidden;background:#151822}
 .phead{padding:10px 14px;border-bottom:1px solid var(--line)}
 .ptitle{font-weight:600}
 .pdetail{color:var(--dim);font-size:12px;margin-top:2px}
 .net{position:relative;margin:14px auto;}
 .net img{position:absolute;image-rendering:auto;border:1px solid #000}
 .net .lab{position:absolute;font-size:10px;color:#fff;text-shadow:0 0 3px #000;
   padding:2px 4px;pointer-events:none}
 footer{color:var(--dim);font-size:12px;padding:0 20px 24px;max-width:1100px}
 code{background:#1a1e28;padding:1px 5px;border-radius:4px}
</style>
<header>
  <h1>Height &amp; climate: current rasters vs compact parameters</h1>
  <div class="sub">Cube net, all six faces. Same projection and roll (&minus;70&deg;)
  as the game. Left is what the world uses today; right is reconstructed from the
  compact parameter set.</div>
</header>
<div class="tabs" id="tabs">
  <button data-layer="height" aria-pressed="true">Height</button>
  <button data-layer="temp" aria-pressed="false">Temperature</button>
  <button data-layer="humid" aria-pressed="false">Humidity</button>
  <button data-layer="climate" aria-pressed="false">Temp + humidity</button>
  <button data-layer="both" aria-pressed="false">Height + climate</button>
</div>
<div class="wrap" id="wrap"></div>
<footer>
  <b>How to read this.</b> Height uses a hypsometric tint (blues by depth, greens
  &rarr; browns &rarr; snow). Climate colours land by temperature (hue) and
  precipitation (saturation); ocean is flat so only the land signal competes.
  Height+climate multiplies the climate colour by a hillshade computed from that
  panel's own height field &mdash; so a smoother height field visibly loses relief.
  Look hardest at <b>coastlines</b> (islands, fjords, peninsulas) and at
  <b>mountain sharpness</b>.
</footer>
<script>
const DATA=__DATA__, META=__META__, GRID=__GRID__, LABEL=__LABEL__, S=__SIZE__;
const FACES=Object.keys(GRID);
let layer="height";
function build(){
  const wrap=document.getElementById("wrap"); wrap.innerHTML="";
  META.forEach((m,si)=>{
    const p=document.createElement("div"); p.className="panel";
    const cols=[-1,0,1,2], rows=[-1,0,1];
    const w=cols.length*S, h=rows.length*S;
    let inner=`<div class="phead"><div class="ptitle">${m.label}</div>`+
              `<div class="pdetail">${m.detail}</div></div>`+
              `<div class="net" style="width:${w}px;height:${h}px">`;
    FACES.forEach(f=>{
      const [c,r]=GRID[f];
      const x=(c-cols[0])*S, y=(r-rows[0])*S;
      inner+=`<img src="${DATA[si+"|"+layer+"|"+f]}" width="${S}" height="${S}" `+
             `style="left:${x}px;top:${y}px">`+
             `<div class="lab" style="left:${x+3}px;top:${y+2}px">${LABEL[f]}</div>`;
    });
    inner+="</div>";
    p.innerHTML=inner; wrap.appendChild(p);
  });
  const sc=Math.min(1,(window.innerWidth/2-60)/(4*S));
  document.querySelectorAll(".net").forEach(n=>{
    n.style.transform=`scale(${sc})`; n.style.transformOrigin="top left";
    n.style.height=(3*S*sc)+"px";
  });
}
document.getElementById("tabs").addEventListener("click",e=>{
  const b=e.target.closest("button"); if(!b)return;
  layer=b.dataset.layer;
  [...e.currentTarget.children].forEach(x=>x.setAttribute("aria-pressed",x===b));
  build();
});
addEventListener("resize",build); build();
</script>
"""


if __name__ == "__main__":
    main()
