#!/usr/bin/env python3
"""Flat single-face map pages, and the marker feed they (and the globe) read.

The spinning globe is good for orientation and useless for the one thing people
actually ask it: *where am I looking, in world coordinates*. This module adds

  /face?f=EQ_EAST        a flat, window-filling map of one cube face, with a
                         marker overlay and a click->world-(x,z) readout
  /faceimage?f=EQ_EAST   the face texture as image bytes (terrain or biomes)
  /markers               every structure/city as {x,z,face,u,v,type,confidence}
  /cities                the 30 historical cities as a real overlay layer

Geometry mirrors server.py / realmap.py / structures/cubegate.py: the cube net
is six FACE-sized squares laid out by GRID, a face carries face-local
coordinates u,v in [-1,1], and

    world_x = u * (FACE/2) + grid_col * FACE
    world_z = v * (FACE/2) + grid_row * FACE

which is exactly the inverse of cubegate.world_to_faceuv. Everything on the flat
page -- markers, the grid, the click readout -- goes through that one pair of
functions, so a marker and a click on the same pixel report the same coordinate.
"""
from __future__ import annotations

import base64
import json
import os

FACE = int(os.environ.get("FACE_SIZE", "10240"))
NETHER_FACE = FACE // 8

GRID = {"NORTH_POLE": (0, -1), "EQ_PRIME": (0, 0), "EQ_EAST": (1, 0),
        "EQ_BACK": (2, 0), "EQ_WEST": (-1, 0), "SOUTH_POLE": (0, 1)}
FACE_ORDER = ["NORTH_POLE", "SOUTH_POLE", "EQ_PRIME", "EQ_BACK", "EQ_EAST", "EQ_WEST"]

CITIES_JSON = os.environ.get("CITIES_JSON",
                             os.path.join(os.path.dirname(__file__), "cities_globe.json"))
FIXTURE_DIR = os.path.join(os.path.dirname(__file__), "fixtures")

# Layers vanilla places by seed maths alone are "exact"; these two are knowingly
# a superset (see structures/compute.SUPERSET_LAYERS) and are drawn hollow.
FALLBACK_SUPERSET = {"ancient_cities", "woodland_mansions"}

# layer name -> the structure id the game knows it by (best effort; villages do
# not resolve to their biome variant here -- the layer feed does not carry it).
TYPE_ID = {
    "villages": "minecraft:village", "zombie_villages": "minecraft:village",
    "desert_pyramids": "minecraft:desert_pyramid",
    "jungle_temples": "minecraft:jungle_pyramid", "igloos": "minecraft:igloo",
    "ocean_monuments": "minecraft:monument", "ocean_ruins": "minecraft:ocean_ruin",
    "shipwrecks": "minecraft:shipwreck", "ruined_portals": "minecraft:ruined_portal",
    "pillager_outposts": "minecraft:pillager_outpost",
    "trail_ruins": "minecraft:trail_ruins", "trial_chambers": "minecraft:trial_chambers",
    "woodland_mansions": "minecraft:mansion", "buried_treasures": "minecraft:buried_treasure",
    "swamp_huts": "minecraft:swamp_hut", "ancient_cities": "minecraft:ancient_city",
    "end_portals": "minecraft:stronghold", "fortresses": "minecraft:fortress",
    "bastions": "minecraft:bastion_remnant", "nether_fossils": "minecraft:nether_fossil",
    "cities": "cubeworld:city",
}

# One palette for the flat page and the globe's city layer.
LAYER_COLORS = {
    "cities": "#ffb22c", "villages": "#ffd166", "desert_pyramids": "#f4a259",
    "jungle_temples": "#43aa8b", "igloos": "#a8e6ff", "ocean_monuments": "#00b4d8",
    "ocean_ruins": "#5fa8d3", "shipwrecks": "#c9ada7", "ruined_portals": "#f15bb5",
    "pillager_outposts": "#ef476f", "trail_ruins": "#c58c4f",
    "trial_chambers": "#9b5de5", "woodland_mansions": "#7161a8",
    "buried_treasures": "#ffea00", "swamp_huts": "#588157",
    "ancient_cities": "#4361ee", "end_portals": "#b8f2e6",
    "zombie_villages": "#6a994e", "fortresses": "#8d0801", "bastions": "#5a189a",
    "nether_fossils": "#e9ecef",
}
# Dense sets start hidden so a face isn't a wall of dots (mirrors the globe).
LAYERS_OFF_BY_DEFAULT = ["ocean_ruins", "shipwrecks", "buried_treasures",
                         "ruined_portals", "trial_chambers", "nether_fossils"]


# ------------------------------------------------------------------ geometry
def face_size(dim="overworld"):
    return NETHER_FACE if dim == "nether" else FACE


def uv_to_world(face, u, v, dim="overworld"):
    """Face-local (u,v) in [-1,1] -> world (x, z). Inverse of world_to_faceuv."""
    fs = face_size(dim)
    gc, gr = GRID[face]
    return u * (fs / 2.0) + gc * fs, v * (fs / 2.0) + gr * fs


def world_to_uv(x, z, dim="overworld"):
    """World (x, z) -> (face, u, v), or None off the cube net."""
    import math
    fs = face_size(dim)
    col = math.floor((x + fs / 2) / fs)
    row = math.floor((z + fs / 2) / fs)
    for f, (c, r) in GRID.items():
        if (c, r) == (col, row):
            return f, (x - c * fs) / (fs / 2.0), (z - r * fs) / (fs / 2.0)
    return None


def world_bounds(face, dim="overworld"):
    fs = face_size(dim)
    gc, gr = GRID[face]
    h = fs / 2
    return (int(gc * fs - h), int(gr * fs - h), int(gc * fs + h), int(gr * fs + h))


def valid_face(name):
    return name if name in GRID else None


# ------------------------------------------------------------- marker feed
def load_cities():
    try:
        with open(CITIES_JSON, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return []


def city_entries(face=None):
    """The 30 historical cities in the marker contract's shape (+ pop/country).

    x,z wins over the stored u,v where the two disagree: cities_globe.json has a
    couple of rows whose u,v drifted from the world position (Alexandria by ~105
    blocks), and x,z is the one that matches the teleport station the plugin
    actually built there (stations.csv). Positioning from x,z keeps the dot and
    the coordinate readout under it telling the same story."""
    out = []
    for c in load_cities():
        f = c.get("face")
        u, v = c.get("u"), c.get("v")
        x, z = c.get("x"), c.get("z")
        if x is not None and z is not None:
            fuv = world_to_uv(x, z)
            if fuv is not None:
                f, u, v = fuv
        if f not in GRID or u is None or (face and f != face):
            continue
        if x is None or z is None:
            x, z = (round(t) for t in uv_to_world(f, u, v))
        out.append({"x": x, "z": z, "face": f,
                    "u": round(u, 6), "v": round(v, 6), "name": c.get("name", "?"),
                    "type": TYPE_ID["cities"], "confidence": "exact",
                    "pop": c.get("pop", 0), "country": c.get("country", ""),
                    "year": c.get("year")})
    return out


def cities_globe():
    """Cities for the globe overlay: marker contract + the cube point to project."""
    out = []
    for c in city_entries():
        u, v, f = c["u"], c["v"], c["face"]
        x, y, z = {"NORTH_POLE": (u, 1.0, v), "EQ_PRIME": (u, -v, 1.0),
                   "SOUTH_POLE": (u, -1.0, -v), "EQ_EAST": (1.0, -v, -u),
                   "EQ_BACK": (-u, -v, -1.0), "EQ_WEST": (-1.0, -v, u)}[f]
        d = dict(c)
        d["p"] = [round(x, 5), round(y, 5), round(z, 5)]
        out.append(d)
    return out


def markers_payload(seed, dim, overlays, face=None, superset=None):
    """Adapt today's overlay markers to the published marker contract:

        {seed, faceSize, dim, layers:{name:[{x,z,face,u,v,type,confidence}]}}

    `overlays` is structures.compute.compute_overlays() output ({name:[{face,u,v,
    p,cx,cz}]}) -- x,z are NOT in it, so they are derived from u,v (the exact
    inverse of the forward mapping, so a marker's x,z is the block its u,v names).
    `confidence` is likewise new: "superset" for the layers compute.py documents
    as over-reporting, "exact" for the rest.
    """
    sup = FALLBACK_SUPERSET if superset is None else superset
    layers = {}
    for name, ms in sorted((overlays or {}).items()):
        arr = []
        for m in ms:
            f = m.get("face")
            if f not in GRID or (face and f != face):
                continue
            u, v = m["u"], m["v"]
            x, z = uv_to_world(f, u, v, dim)
            entry = {"x": round(x), "z": round(z), "face": f,
                     "u": round(u, 6), "v": round(v, 6),
                     "type": TYPE_ID.get(name, "minecraft:" + name.rstrip("s")),
                     "confidence": "superset" if name in sup else "exact"}
            if m.get("r"):
                entry["r"] = m["r"]            # deliberately degraded position
            arr.append(entry)
        layers[name] = arr
    if dim != "nether":
        layers["cities"] = city_entries(face)
    return {"seed": seed, "faceSize": face_size(dim), "dim": dim,
            "face": face, "layers": layers}


def fixture_path(name):
    """Sanitised path inside fixtures/, or None."""
    base = os.path.basename(name)
    if not base.endswith(".json"):
        return None
    p = os.path.join(FIXTURE_DIR, base)
    return p if os.path.isfile(p) else None


# ------------------------------------------------------------- face imagery
def face_image(uris, face):
    """(bytes, content-type) for one face out of a FACE_ORDER list of data URIs."""
    if not uris or len(uris) != 6 or face not in FACE_ORDER:
        return None, None
    uri = uris[FACE_ORDER.index(face)]
    head, _, b64 = uri.partition(",")
    ctype = head[5:].split(";")[0] or "image/jpeg"
    return base64.b64decode(b64), ctype


# ------------------------------------------------------------ the flat page
FACE_PAGE = r"""<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>CubeWorld · __FACE__</title>
<style>
  :root{--panel:rgba(12,16,24,.74);--edge:rgba(120,150,190,.22);--ink:#dbe4f0;
        --muted:#8a97ab;--accent:#5fb0c4;--mono:ui-monospace,"SF Mono",Menlo,Consolas,monospace;
        --sans:system-ui,-apple-system,sans-serif}
  *{box-sizing:border-box}
  html,body{margin:0;height:100%;overflow:hidden;background:#070a10;color:var(--ink);
    font-family:var(--sans)}
  #cv{display:block;width:100vw;height:100vh;cursor:crosshair;touch-action:none}
  .panel{position:fixed;background:var(--panel);border:1px solid var(--edge);
    border-radius:11px;backdrop-filter:blur(7px);-webkit-backdrop-filter:blur(7px)}
  #top{top:14px;left:14px;padding:10px 12px;display:flex;gap:10px;align-items:center}
  #top a.back{font:12px/1 var(--mono);letter-spacing:.08em;text-transform:uppercase;
    color:var(--ink);text-decoration:none;border:1px solid var(--edge);border-radius:8px;
    padding:8px 10px}
  #top a.back:hover{border-color:var(--accent);color:var(--accent)}
  #top .fname{font:600 14px/1.2 var(--mono);letter-spacing:.04em}
  #top .frange{font:11px/1.3 var(--mono);color:var(--muted)}
  #top select{background:rgba(0,0,0,.35);border:1px solid var(--edge);color:var(--ink);
    border-radius:8px;padding:6px 7px;font:12px var(--sans);cursor:pointer}
  #lay{top:96px;left:14px;padding:10px 12px;width:226px;max-height:calc(100vh - 210px);
    overflow:auto;font:12px/1.35 var(--sans)}
  #lay h3{margin:0 0 8px;font:600 12px/1 var(--sans);letter-spacing:.06em;
    text-transform:uppercase;color:var(--muted)}
  #lay label{display:flex;align-items:center;gap:7px;padding:2px 0;cursor:pointer;
    white-space:nowrap}
  #lay .sw{width:11px;height:11px;border-radius:3px;flex:none;
    box-shadow:0 0 0 1px rgba(0,0,0,.45)}
  #lay .sw.hollow{background:transparent!important;border:2px solid currentColor}
  #lay .ct{margin-left:auto;color:var(--muted);font-variant-numeric:tabular-nums}
  #lay .btns{display:flex;gap:6px;margin:2px 0 8px}
  #lay .btns button{flex:1;background:rgba(80,120,200,.28);border:1px solid var(--edge);
    color:var(--ink);border-radius:7px;padding:4px;cursor:pointer;font:11px var(--sans)}
  #lay .btns button:hover{background:rgba(90,140,230,.45)}
  #lay .note{margin-top:8px;color:var(--muted);font-size:11px;line-height:1.4}
  #read{bottom:14px;left:14px;padding:10px 12px;min-width:270px;font:12px/1.5 var(--mono)}
  #read .big{font-size:16px;letter-spacing:.02em}
  #read .lbl{color:var(--muted);font-size:10px;letter-spacing:.14em;text-transform:uppercase}
  #read .pin{color:var(--accent)}
  #read .row{display:flex;gap:8px;align-items:center;margin-top:6px}
  #read button{background:rgba(80,120,200,.28);border:1px solid var(--edge);color:var(--ink);
    border-radius:7px;padding:5px 8px;cursor:pointer;font:11px var(--sans)}
  #read button:hover{background:rgba(90,140,230,.45)}
  #hint{bottom:14px;right:14px;padding:8px 11px;font:11px/1.5 var(--mono);color:var(--muted)}
  #tip{position:fixed;pointer-events:none;display:none;z-index:20;padding:6px 9px;
    border-radius:8px;background:rgba(8,11,17,.92);border:1px solid var(--edge);
    font:11px/1.45 var(--mono);color:var(--ink);white-space:nowrap;
    box-shadow:0 4px 18px rgba(0,0,0,.5)}
  #tip b{color:var(--accent);font-weight:600}
  #busy{position:fixed;top:14px;right:14px;padding:8px 11px;font:11px var(--mono);
    color:var(--muted)}
  #laybtn{display:none;position:fixed;bottom:14px;right:14px;z-index:30;
    background:var(--panel);border:1px solid var(--edge);color:var(--ink);
    border-radius:10px;padding:10px 14px;cursor:pointer;backdrop-filter:blur(7px);
    -webkit-backdrop-filter:blur(7px);font:600 12px var(--sans);
    letter-spacing:.06em;text-transform:uppercase}
  /* phones: the layers panel collapses into #laybtn and opens as a bottom
     sheet; the fixed panels shrink so the map keeps the screen. Desktop
     (>700px) is untouched. */
  @media (max-width:700px){
    #laybtn{display:block}
    #lay{display:none}
    #lay.open{display:block;top:auto;left:0;right:0;bottom:0;width:100%;
      max-height:55vh;border-radius:14px 14px 0 0;z-index:29;
      padding:12px 16px 58px}
    #top{top:10px;left:10px;right:10px;padding:8px 10px;gap:8px;flex-wrap:wrap}
    #top .fname{font-size:12px}
    #top .frange{font-size:9px}
    #busy{top:70px;right:10px}
    #read{bottom:10px;left:10px;min-width:0;max-width:calc(100vw - 110px);
      padding:8px 10px;font:11px/1.45 var(--mono)}
    #read .big{font-size:13px}
    #hint{display:none}
  }
</style>
<canvas id="cv"></canvas>
<div id="top" class="panel">
  <a class="back" id="back" href="/">&larr; globe</a>
  <div><div class="fname" id="fname">__FACE__</div>
       <div class="frange" id="frange"></div></div>
  <select id="bg"><option value="terrain">terrain</option>
    <option value="biomes">biomes</option>
    <option value="nether">nether</option></select>
</div>
<div id="lay" class="panel">
  <h3>Layers</h3>
  <div class="btns"><button id="allon">all</button><button id="alloff">none</button>
    <button id="grid">grid</button></div>
  <div id="laylist"></div>
  <div class="note" id="laynote">loading markers…</div>
</div>
<div id="read" class="panel">
  <div class="lbl">cursor</div>
  <div class="big" id="cur">x —, z —</div>
  <div class="lbl" style="margin-top:6px">clicked</div>
  <div class="big pin" id="pin">click the map</div>
  <div class="row"><button id="copyxz">copy x z</button>
    <button id="copytp">copy /tp</button><span id="copied"></span></div>
</div>
<div id="hint" class="panel">drag · pan &nbsp; scroll · zoom &nbsp; dbl-click · fit</div>
<button id="laybtn">layers</button>
<div id="busy" class="panel">loading…</div>
<div id="tip"></div>
<script>
var CFG=__CFG__;
var FS=CFG.faceSize, HALF=FS/2, GC=CFG.gc, GR=CFG.gr;
function uToX(u){return u*HALF+GC*FS;}
function vToZ(v){return v*HALF+GR*FS;}
function xToU(x){return (x-GC*FS)/HALF;}
function zToV(z){return (z-GR*FS)/HALF;}

var cv=document.getElementById('cv'), ctx=cv.getContext('2d');
var tip=document.getElementById('tip'), busy=document.getElementById('busy');
var W=0,H=0,dpr=Math.max(1,window.devicePixelRatio||1);
// view: uv point at screen centre + pixels per uv unit
var cu=0, cvv=0, k=1, showGrid=true;   // uv at screen centre, px per uv unit
var img=new Image(), imgOK=false;
var data={}, on={}, order=[], pin=(CFG.u!==null&&CFG.u!==undefined)?{u:CFG.u,v:CFG.v}:null;

function resize(){
  W=window.innerWidth; H=window.innerHeight;
  cv.width=W*dpr; cv.height=H*dpr; cv.style.width=W+'px'; cv.style.height=H+'px';
  ctx.setTransform(dpr,0,0,dpr,0,0);
  if(k<=1) fit(); else draw();
}
function fit(){ k=Math.min(W,H)*0.94/2; cu=0; cvv=0; draw(); }
function sx(u){return W/2+(u-cu)*k;}
function sy(v){return H/2+(v-cvv)*k;}
function ux(px){return cu+(px-W/2)/k;}
function vy(py){return cvv+(py-H/2)/k;}

var raf=0;
function draw(){ if(raf)return; raf=requestAnimationFrame(function(){raf=0;render();}); }

function niceStep(){                       // world-block grid step, ~>=90px apart
  var steps=[10000,5000,2000,1000,500,200,100,50,20,10];
  for(var i=0;i<steps.length;i++){ if(steps[i]/HALF*k>=90) return steps[i]; }
  return 10;
}
function drawGrid(){
  // clipped to the face square: off the face these coordinates belong to a
  // different face, so drawing the graticule there would be a lie.
  var st=niceStep(), b=CFG.bounds;
  var x0=Math.max(uToX(ux(0)),b[0]), x1=Math.min(uToX(ux(W)),b[2]);
  var z0=Math.max(vToZ(vy(0)),b[1]), z1=Math.min(vToZ(vy(H)),b[3]);
  var top=Math.max(sy(-1),0), bot=Math.min(sy(1),H);
  var lft=Math.max(sx(-1),0), rgt=Math.min(sx(1),W);
  ctx.lineWidth=1; ctx.font='10px ui-monospace,monospace'; ctx.textBaseline='top';
  for(var x=Math.ceil(x0/st)*st; x<=x1; x+=st){
    var px=Math.round(sx(xToU(x)))+0.5;
    ctx.strokeStyle=(x===0)?'rgba(255,255,255,.42)':'rgba(255,255,255,.15)';
    ctx.beginPath();ctx.moveTo(px,top);ctx.lineTo(px,bot);ctx.stroke();
    ctx.fillStyle='rgba(220,230,245,.62)'; ctx.fillText('x '+x, px+3, top+3);
  }
  ctx.textBaseline='alphabetic';
  for(var z=Math.ceil(z0/st)*st; z<=z1; z+=st){
    var py=Math.round(sy(zToV(z)))+0.5;
    ctx.strokeStyle=(z===0)?'rgba(255,255,255,.42)':'rgba(255,255,255,.15)';
    ctx.beginPath();ctx.moveTo(lft,py);ctx.lineTo(rgt,py);ctx.stroke();
    ctx.fillStyle='rgba(220,230,245,.62)'; ctx.fillText('z '+z, lft+4, py-4);
  }
}
function drawMarkers(){
  for(var li=0; li<order.length; li++){
    var name=order[li]; if(!on[name]) continue;
    var arr=data[name]||[], col=CFG.colors[name]||'#9aa7bd';
    var city=(name==='cities');
    ctx.strokeStyle='rgba(0,0,0,.75)'; ctx.fillStyle=col;
    for(var i=0;i<arr.length;i++){
      var m=arr[i], X=sx(m.u), Y=sy(m.v);
      if(X<-20||Y<-20||X>W+20||Y>H+20) continue;
      if(m.confidence==='superset'){          // hollow: may not exist in-world
        ctx.beginPath();ctx.arc(X,Y,4.2,0,6.2832);
        ctx.lineWidth=2.6;ctx.strokeStyle='rgba(0,0,0,.6)';ctx.stroke();
        ctx.lineWidth=1.6;ctx.strokeStyle=col;ctx.stroke();
      } else if(city){
        ctx.beginPath();ctx.arc(X,Y,5.2,0,6.2832);
        ctx.fillStyle=col;ctx.fill();
        ctx.lineWidth=1.6;ctx.strokeStyle='rgba(20,14,0,.85)';ctx.stroke();
        ctx.fillStyle='#fff';ctx.font='600 11px system-ui,sans-serif';
        ctx.strokeStyle='rgba(0,0,0,.85)';ctx.lineWidth=3;
        ctx.strokeText(m.name,X+8,Y+4);ctx.fillText(m.name,X+8,Y+4);
      } else {
        if(m.r){                              // degraded position: honest circle
          var rpx=m.r/HALF*k;
          if(rpx>4){
            ctx.beginPath();ctx.arc(X,Y,rpx,0,6.2832);
            ctx.save();ctx.globalAlpha=.13;ctx.fillStyle=col;ctx.fill();ctx.restore();
            ctx.lineWidth=1.3;ctx.strokeStyle=col;ctx.stroke();
          }
        }
        ctx.beginPath();ctx.arc(X,Y,3.4,0,6.2832);
        ctx.fillStyle=col;ctx.fill();
        ctx.lineWidth=1.2;ctx.strokeStyle='rgba(0,0,0,.7)';ctx.stroke();
      }
    }
  }
}
function drawPin(){
  if(!pin) return;
  var X=sx(pin.u), Y=sy(pin.v);
  ctx.strokeStyle='#ff4136'; ctx.lineWidth=1.6;
  ctx.beginPath();ctx.moveTo(X-11,Y);ctx.lineTo(X-4,Y);ctx.moveTo(X+4,Y);ctx.lineTo(X+11,Y);
  ctx.moveTo(X,Y-11);ctx.lineTo(X,Y-4);ctx.moveTo(X,Y+4);ctx.lineTo(X,Y+11);ctx.stroke();
  ctx.beginPath();ctx.arc(X,Y,10,0,6.2832);ctx.strokeStyle='rgba(255,65,54,.55)';ctx.stroke();
}
function render(){
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,W,H);
  var x0=sx(-1), y0=sy(-1), side=2*k;
  ctx.fillStyle='#0b0f16'; ctx.fillRect(x0,y0,side,side);
  if(imgOK){
    ctx.imageSmoothingEnabled=(k<Math.max(W,H));
    ctx.drawImage(img,x0,y0,side,side);
  }
  ctx.strokeStyle='rgba(150,180,220,.45)';ctx.lineWidth=1;
  ctx.strokeRect(x0-0.5,y0-0.5,side+1,side+1);
  if(showGrid) drawGrid();
  drawMarkers();
  drawPlayers();
  drawPin();
}

// ---------------------------------------------------------------- players
// Live players on this face, from the same privacy-filtered /players feed the
// globe uses. r is the uncertainty radius in blocks (0 = exact): imprecise
// players draw as an honest circle of that REAL radius at map scale, so a
// ±512 marker covers the whole area the player might be in.
var players=[];
function pollPlayers(){
  fetch('/players',{cache:'no-store'}).then(function(r){return r.json();})
    .then(function(j){ players=j||[]; draw(); })
    .catch(function(){})
    .finally(function(){ setTimeout(pollPlayers,2000); });
}
pollPlayers();
function drawPlayers(){
  for(var i=0;i<players.length;i++){
    var pl=players[i];
    if(pl.face!==CFG.face) continue;
    var X=sx(xToU(pl.x)), Y=sy(zToV(pl.z));
    var rpx=(pl.r||0)/HALF*k;
    if(X<-rpx-30||Y<-rpx-30||X>W+rpx+30||Y>H+rpx+30) continue;
    if(rpx>4){                              // uncertainty disc, true to scale
      ctx.beginPath();ctx.arc(X,Y,rpx,0,6.2832);
      ctx.fillStyle='rgba(255,65,54,.14)';ctx.fill();
      ctx.lineWidth=1.4;ctx.strokeStyle='rgba(255,65,54,.75)';ctx.stroke();
    }
    ctx.beginPath();ctx.arc(X,Y,(pl.r||0)>0?4.5:5.5,0,6.2832);
    ctx.fillStyle=(pl.r||0)>0?'rgba(255,65,54,.65)':'#ff4136';ctx.fill();
    ctx.lineWidth=1.8;ctx.strokeStyle='#fff';ctx.stroke();
    var lbl=pl.name+((pl.r||0)>0?' ±'+pl.r:'');
    ctx.font='600 12px system-ui,sans-serif';
    ctx.strokeStyle='rgba(0,0,0,.85)';ctx.lineWidth=3;
    ctx.strokeText(lbl,X+9,Y+4);ctx.fillStyle='#fff';ctx.fillText(lbl,X+9,Y+4);
  }
}

// ---------------------------------------------------------------- imagery
function loadImage(){
  busy.style.display='block'; busy.textContent='loading face…';
  var u='/faceimage?f='+encodeURIComponent(CFG.face)+
        '&bg='+encodeURIComponent(document.getElementById('bg').value)+
        '&t='+Date.now();
  var n=new Image();
  n.onload=function(){img=n;imgOK=true;busy.style.display='none';draw();};
  n.onerror=function(){busy.textContent='face image failed';};
  n.src=u;
}
// The nether cube is 1:8, so its faces are a different coordinate system
// entirely (faceSize 1280): switching to it reloads the page in that dimension
// rather than pasting nether imagery under overworld coordinates.
var bgSel=document.getElementById('bg');
bgSel.value=(CFG.dim==='nether')?'nether':'terrain';
bgSel.onchange=function(){
  var want=bgSel.value, isNether=(CFG.dim==='nether');
  if((want==='nether')!==isNether){
    location.href='/face?f='+encodeURIComponent(CFG.face)+
      (want==='nether'?'&dim=nether':'');
    return;
  }
  loadImage();
};
setInterval(loadImage, 30000);          // terrain fills in as the world is explored

// ---------------------------------------------------------------- markers
function layerRows(){
  var list=document.getElementById('laylist'); list.innerHTML='';
  order.forEach(function(name){
    var arr=data[name]||[]; var col=CFG.colors[name]||'#9aa7bd';
    var sup=arr.length&&arr[0].confidence==='superset';
    var lab=document.createElement('label');
    lab.innerHTML='<span class="sw'+(sup?' hollow':'')+'" style="'+
      (sup?('color:'+col):('background:'+col))+'"></span>'+
      '<input type="checkbox"'+(on[name]?' checked':'')+'>'+name.replace(/_/g,' ')+
      '<span class="ct">'+arr.length.toLocaleString()+'</span>';
    lab.querySelector('input').onchange=function(e){on[name]=e.target.checked;draw();};
    list.appendChild(lab);
  });
}
function loadMarkers(){
  var src=CFG.src||('/markers?face='+encodeURIComponent(CFG.face)+
                    '&dim='+encodeURIComponent(CFG.dim)+
                    (CFG.seed!==null?('&seed='+CFG.seed):''));
  fetch(src,{cache:'no-store'}).then(function(r){return r.json();}).then(function(j){
    var L=j.layers||{}; data={}; order=[];
    Object.keys(L).sort().forEach(function(n){
      var arr=L[n].filter(function(m){return !m.face||m.face===CFG.face;});
      if(!arr.length && n!=='cities') return;
      data[n]=arr; order.push(n);
      if(!(n in on)) on[n]=(CFG.off.indexOf(n)<0);
    });
    var tot=0; order.forEach(function(n){tot+=data[n].length;});
    document.getElementById('laynote').textContent=
      tot.toLocaleString()+' markers on this face · hollow = superset (may not exist)';
    layerRows(); draw();
  }).catch(function(e){
    document.getElementById('laynote').textContent='markers unavailable: '+e;
  });
}
document.getElementById('allon').onclick=function(){order.forEach(function(n){on[n]=true;});
  layerRows();draw();};
document.getElementById('alloff').onclick=function(){order.forEach(function(n){on[n]=false;});
  layerRows();draw();};
document.getElementById('grid').onclick=function(){showGrid=!showGrid;draw();};
// phones: the panel is collapsed into this button (see the media query);
// on desktop the button is display:none and the panel is always open.
var laybtn=document.getElementById('laybtn'), layEl=document.getElementById('lay');
laybtn.onclick=function(){
  var open=layEl.classList.toggle('open');
  laybtn.textContent=open?'close':'layers';
};

// ------------------------------------------------------------ interaction
function hitTest(px,py){
  var best=null,bd=12*12;
  for(var li=order.length-1; li>=0; li--){
    var name=order[li]; if(!on[name])continue;
    var arr=data[name]||[];
    for(var i=0;i<arr.length;i++){
      var m=arr[i], dx=sx(m.u)-px, dy=sy(m.v)-py, d=dx*dx+dy*dy;
      if(d<bd){bd=d;best={m:m,layer:name};}
    }
  }
  return best;
}
function fmt(x,z){return 'x '+x+', z '+z;}
function setCursor(px,py){
  var u=ux(px), v=vy(py);
  var inside=(u>=-1&&u<=1&&v>=-1&&v<=1);
  document.getElementById('cur').textContent=
    inside?fmt(Math.round(uToX(u)),Math.round(vToZ(v))):'off this face';
}
// One Pointer-Events path serves mouse and touch alike: one pointer down and
// dragged = pan, two touch pointers = pinch-zoom about their midpoint (and
// moving the midpoint pans), a pointer that never travelled = click/tap.
// Mouse keeps its hover tooltip; touch has no hover, so a tap on a marker
// shows the tooltip instead and a tap elsewhere dismisses it.
var ptrs={}, moved=0, pinched=false, lx=0, ly=0, pm0=null, pd0=0;
var lastTap=0, lastTapXY=null;
function pIds(){return Object.keys(ptrs);}
function clampK(nk){
  return Math.max(Math.min(W,H)*0.2, Math.min(nk, Math.min(W,H)*260));
}
function showTip(h,px,py){
  var m=h.m;
  tip.innerHTML='<b>'+(m.name||h.layer.replace(/_/g,' '))+'</b><br>'+
    (m.type||'')+(m.confidence==='superset'?' · superset':'')+'<br>'+
    (m.r?('somewhere within ±'+m.r):fmt(m.x,m.z))+
    (m.pop?('<br>pop '+m.pop.toLocaleString()+(m.country?(' · '+m.country):'')):'');
  tip.style.display='block';
  tip.style.left=Math.min(px+14,window.innerWidth-tip.offsetWidth-8)+'px';
  tip.style.top=Math.min(py+16,window.innerHeight-tip.offsetHeight-8)+'px';
}
function doClick(px,py,isTouch){
  setCursor(px,py);
  var u=ux(px), v=vy(py);
  pin={u:u,v:v};
  var h=hitTest(px,py);
  if(h){ pin={u:h.m.u,v:h.m.v}; }
  var x=Math.round(uToX(pin.u)), z=Math.round(vToZ(pin.v));
  window.lastXZ=[x,z];
  document.getElementById('pin').textContent=fmt(x,z)+
    (h?('  ·  '+(h.m.name||h.layer.replace(/_/g,' '))):'');
  document.getElementById('copied').textContent='';
  if(isTouch){ if(h) showTip(h,px,py); else tip.style.display='none'; }
  draw();
}
cv.addEventListener('pointerdown',function(e){
  if(e.pointerType==='mouse'&&e.button!==0)return;
  ptrs[e.pointerId]=[e.clientX,e.clientY];
  var ids=pIds();
  if(ids.length===1){moved=0;pinched=false;lx=e.clientX;ly=e.clientY;}
  else if(ids.length===2){pinched=true;
    var a=ptrs[ids[0]], b=ptrs[ids[1]];
    pm0=[(a[0]+b[0])/2,(a[1]+b[1])/2]; pd0=Math.hypot(a[0]-b[0],a[1]-b[1]);}
  // capture: a pan that strays over a floating panel keeps panning.
  try{cv.setPointerCapture(e.pointerId);}catch(err){}
  // touch only: keep the tap from becoming a compatibility mouse event
  // (desktop mousedown must stay untouched for text-select etc.)
  if(e.pointerType!=='mouse')e.preventDefault();
});
cv.addEventListener('pointermove',function(e){
  if(!(e.pointerId in ptrs))return;
  ptrs[e.pointerId]=[e.clientX,e.clientY];
  var ids=pIds();
  if(ids.length>=2){                        // pinch: zoom about the midpoint,
    var a=ptrs[ids[0]], b=ptrs[ids[1]];     // midpoint motion pans
    var mx=(a[0]+b[0])/2, my=(a[1]+b[1])/2, d=Math.hypot(a[0]-b[0],a[1]-b[1]);
    var u=ux(pm0[0]), v=vy(pm0[1]);         // world point under the old midpoint
    if(pd0>0) k=clampK(k*d/pd0);
    cu=u-(mx-W/2)/k; cvv=v-(my-H/2)/k;      // ...stays under the new midpoint
    pm0=[mx,my]; pd0=d; moved+=999; draw();
  } else {
    var ddx=e.clientX-lx, ddy=e.clientY-ly;
    moved+=Math.abs(ddx)+Math.abs(ddy);
    cu-=ddx/k; cvv-=ddy/k; lx=e.clientX; ly=e.clientY; draw();
  }
  if(moved>4)tip.style.display='none';
});
function endPtr(e){
  if(!(e.pointerId in ptrs))return;
  delete ptrs[e.pointerId];
  var ids=pIds();
  if(ids.length===1){lx=ptrs[ids[0]][0];ly=ptrs[ids[0]][1];}  // pinch -> pan, no jump
  if(ids.length)return;
  var wasPinch=pinched; pinched=false;
  if(e.type!=='pointerup'||wasPinch)return;
  var slop=(e.pointerType==='mouse')?4:10;  // fingers wobble more than mice
  if(moved>slop)return;
  if(e.pointerType!=='mouse'){              // double-tap = fit (mirrors dbl-click)
    var now=Date.now();
    if(lastTapXY&&now-lastTap<350&&
       Math.abs(e.clientX-lastTapXY[0])+Math.abs(e.clientY-lastTapXY[1])<40){
      lastTap=0;lastTapXY=null;fit();return;
    }
    lastTap=now;lastTapXY=[e.clientX,e.clientY];
  }
  doClick(e.clientX,e.clientY,e.pointerType!=='mouse');
}
cv.addEventListener('pointerup',endPtr);
cv.addEventListener('pointercancel',endPtr);
// hover (mouse/pen only, no button down) on the window, not the canvas, so
// leaving the canvas over a floating panel still dismisses the tooltip.
addEventListener('pointermove',function(e){
  if(e.pointerType!=='mouse'||pIds().length)return;
  if(e.target!==cv){tip.style.display='none';return;}
  setCursor(e.clientX,e.clientY);
  var h=hitTest(e.clientX,e.clientY);
  if(h){showTip(h,e.clientX,e.clientY);cv.style.cursor='pointer';}
  else{tip.style.display='none';cv.style.cursor='crosshair';}
});
cv.addEventListener('mouseleave',function(){tip.style.display='none';});
cv.addEventListener('dblclick',function(){fit();});
cv.addEventListener('wheel',function(e){
  var f=Math.exp(-e.deltaY*0.0015);
  var nk=clampK(k*f);
  var u=ux(e.clientX), v=vy(e.clientY);
  k=nk;
  cu=u-(e.clientX-W/2)/k; cvv=v-(e.clientY-H/2)/k;
  draw(); e.preventDefault();
},{passive:false});

function copy(txt){
  var done=function(){document.getElementById('copied').textContent='copied';
    setTimeout(function(){document.getElementById('copied').textContent='';},1400);};
  if(navigator.clipboard&&navigator.clipboard.writeText){
    navigator.clipboard.writeText(txt).then(done,function(){fallback(txt,done);});
  } else fallback(txt,done);
}
function fallback(txt,done){
  var t=document.createElement('textarea');t.value=txt;document.body.appendChild(t);
  t.select();try{document.execCommand('copy');done();}catch(e){}t.remove();
}
document.getElementById('copyxz').onclick=function(){
  if(window.lastXZ) copy(window.lastXZ[0]+' '+window.lastXZ[1]);};
document.getElementById('copytp').onclick=function(){
  if(window.lastXZ) copy('/tp @s '+window.lastXZ[0]+' ~ '+window.lastXZ[1]);};
document.getElementById('back').onclick=function(e){
  if(history.length>1&&document.referrer){e.preventDefault();history.back();}};

var b=CFG.bounds;
document.getElementById('frange').textContent=
  'x '+b[0]+'…'+b[2]+'   z '+b[1]+'…'+b[3];
addEventListener('resize',resize);
resize(); fit();
if(CFG.u!==null&&CFG.u!==undefined){
  var px=Math.round(uToX(CFG.u)), pz=Math.round(vToZ(CFG.v));
  window.lastXZ=[px,pz];
  document.getElementById('pin').textContent=fmt(px,pz)+'  ·  from the globe';
}
loadImage(); loadMarkers();
</script>
"""


def face_page(face, dim="overworld", seed=None, u=None, v=None, src=None):
    cfg = {
        "face": face, "dim": dim, "seed": seed,
        "faceSize": face_size(dim), "gc": GRID[face][0], "gr": GRID[face][1],
        "bounds": list(world_bounds(face, dim)),
        "u": u, "v": v, "src": src,
        "colors": LAYER_COLORS, "off": LAYERS_OFF_BY_DEFAULT,
    }
    return (FACE_PAGE.replace("__CFG__", json.dumps(cfg))
                     .replace("__FACE__", face))


def face_index_page():
    """Fallback when /face is asked for without a face."""
    rows = "".join(
        '<li><a href="/face?f=%s">%s</a> <span>x %d…%d, z %d…%d</span></li>'
        % ((f, f) + world_bounds(f)) for f in FACE_ORDER)
    return ("<!doctype html><meta charset=utf-8>"
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            "<title>CubeWorld faces</title>"
            "<style>body{background:#070a10;color:#dbe4f0;font:14px/1.7 system-ui;"
            "padding:32px}a{color:#5fb0c4}span{color:#8a97ab;font-family:monospace;"
            "font-size:12px}li{margin:2px 0}</style>"
            "<h2>Flat face maps</h2><ul>" + rows + "</ul>"
            '<p><a href="/">&larr; globe</a></p>')


# --------------------------------------------------- globe-page attachments
# Injected after the globe's own script, so it shares that script's top-level
# lexical scope (cv, yaw, pitch, dist, panX, panY, morph, mul, rotX, rotY).
GLOBE_ADDON = r"""
<style>
  #cityov{position:fixed;inset:0;pointer-events:none;z-index:8}
  #citytip{position:fixed;display:none;z-index:14;pointer-events:none;padding:6px 9px;
    border-radius:8px;background:rgba(8,11,17,.92);border:1px solid rgba(120,150,190,.22);
    font:11px/1.45 ui-monospace,Menlo,Consolas,monospace;color:#dbe4f0;white-space:nowrap}
  #citytip b{color:#ffb22c}
  #cityown{position:fixed;top:16px;left:16px;z-index:11;font:12px/1.35 system-ui,sans-serif;
    color:#cdd6e4;background:rgba(12,16,24,.72);padding:10px 12px;border-radius:11px;
    border:1px solid rgba(120,150,190,.2);backdrop-filter:blur(8px)}
  #pscities{display:flex;align-items:center;gap:7px;padding:3px 0 7px;cursor:pointer;
    white-space:nowrap;border-bottom:1px solid rgba(120,150,190,.16);margin-bottom:7px}
  #pscities .sw{width:11px;height:11px;border-radius:3px;flex:none;background:#ffb22c;
    box-shadow:0 0 0 1px rgba(0,0,0,.4)}
  #pscities .ct{margin-left:auto;color:#8a97ab;font-variant-numeric:tabular-nums}
  #faceflash{position:fixed;left:50%;top:22px;transform:translateX(-50%);z-index:15;
    display:none;padding:8px 13px;border-radius:10px;background:rgba(12,16,24,.82);
    border:1px solid rgba(120,150,190,.25);color:#dbe4f0;
    font:12px ui-monospace,Menlo,Consolas,monospace}
  /* phones: the globe's HUD carries mouse-only instructions and the elevation
     legend crowds the corner the buttons need -- both go; desktop unchanged. */
  @media (max-width:700px){
    #hud{display:none}
    #legend{display:none}
    #faceflash{top:60px;max-width:92vw;white-space:nowrap;overflow:hidden;
      text-overflow:ellipsis}
  }
</style>
<canvas id="cityov"></canvas>
<div id="citytip"></div>
<div id="faceflash"></div>
<script>
(function(){
  // ---- cities as a real, toggleable layer (they used to be anonymous dots
  // baked into the face textures by realmap.composite_uris, so they could be
  // neither switched off nor identified).
  var cities=[], show=true;
  var cvs=document.getElementById('cityov'), ctx=cvs.getContext('2d');
  var tip=document.getElementById('citytip'), flash=document.getElementById('faceflash');
  var dpr=Math.max(1,window.devicePixelRatio||1), mouse=null, pts=[];
  var FN={NORTH_POLE:[0,1,0],SOUTH_POLE:[0,-1,0],EQ_PRIME:[0,0,1],
          EQ_BACK:[0,0,-1],EQ_EAST:[1,0,0],EQ_WEST:[-1,0,0]};
  function mvv(m,v){var o=[0,0,0,0];for(var r=0;r<4;r++){var s=0;
    for(var c=0;c<4;c++)s+=m[c*4+r]*v[c];o[r]=s;}return o;}

  var row=document.createElement('label');
  row.id='pscities';
  row.innerHTML='<span class="sw"></span><input type="checkbox" checked>cities'+
                '<span class="ct" id="cityct">0</span>';
  var host=document.getElementById('psctl');
  if(host){ host.insertBefore(row, document.getElementById('pstog')); }
  else { var own=document.createElement('div'); own.id='cityown';
         own.appendChild(row); document.body.appendChild(own); }
  row.querySelector('input').onchange=function(e){show=e.target.checked;};

  fetch('/cities',{cache:'no-store'}).then(function(r){return r.json();})
    .then(function(j){cities=j||[];
      var el=document.getElementById('cityct'); if(el)el.textContent=cities.length;})
    .catch(function(){});

  // only over the globe canvas itself: the control panels float on top of it,
  // and a hover label for a city dot hidden behind a panel is a lie.
  addEventListener('mousemove',function(e){
    mouse=(e.target===cv)?[e.clientX,e.clientY]:null;});

  function loop(){
    var Wp=cv.clientWidth, Hp=cv.clientHeight;
    if(cvs.width!==Wp*dpr||cvs.height!==Hp*dpr){cvs.width=Wp*dpr;cvs.height=Hp*dpr;
      cvs.style.width=Wp+'px';cvs.style.height=Hp+'px';}
    ctx.setTransform(dpr,0,0,dpr,0,0); ctx.clearRect(0,0,Wp,Hp);
    pts=[];
    if(show&&cities.length){
      var model=mul(rotX(pitch),rotY(yaw));
      var asp=cv.width/cv.height;
      var mvp=mul(persp(1.1,asp,0.1,100),mul(trans3(panX,panY,-dist),model));
      for(var i=0;i<cities.length;i++){
        var c=cities[i], p=c.p, n=Math.hypot(p[0],p[1],p[2]);
        var s0=[p[0]/n,p[1]/n,p[2]/n];
        var q=[p[0]+(s0[0]-p[0])*morph,p[1]+(s0[1]-p[1])*morph,p[2]+(s0[2]-p[2])*morph];
        var cc=mvv(mvp,[q[0],q[1],q[2],1]); if(cc[3]<=0)continue;
        var fn=FN[c.face]||s0;
        var cn=[fn[0]+(s0[0]-fn[0])*morph,fn[1]+(s0[1]-fn[1])*morph,
                fn[2]+(s0[2]-fn[2])*morph];
        if(model[2]*cn[0]+model[6]*cn[1]+model[10]*cn[2]<=0.15)continue;
        var X=(cc[0]/cc[3]*0.5+0.5)*Wp, Y=(1-(cc[1]/cc[3]*0.5+0.5))*Hp;
        var r=2.2+3.6*Math.sqrt(Math.max(c.pop,0)/1500000);
        ctx.beginPath();ctx.arc(X,Y,r,0,6.2832);
        ctx.fillStyle='#ffb22c';ctx.fill();
        ctx.lineWidth=1.3;ctx.strokeStyle='rgba(40,24,0,.85)';ctx.stroke();
        pts.push([X,Y,c]);
      }
    }
    var hit=null;
    if(mouse&&pts.length){
      var bd=14*14;
      for(var j=0;j<pts.length;j++){
        var dx=pts[j][0]-mouse[0], dy=pts[j][1]-mouse[1], d=dx*dx+dy*dy;
        if(d<bd){bd=d;hit=pts[j];}
      }
    }
    if(hit){
      var ct=hit[2];
      tip.innerHTML='<b>'+ct.name+'</b>'+(ct.country?(' · '+ct.country):'')+
        '<br>x '+ct.x+', z '+ct.z+'<br>pop '+ct.pop.toLocaleString()+
        (ct.year!=null?(' · '+(ct.year<0?(-ct.year+' BC'):(ct.year+' AD'))):'');
      tip.style.display='block';
      tip.style.left=Math.min(hit[0]+13,window.innerWidth-tip.offsetWidth-8)+'px';
      tip.style.top=Math.min(hit[1]+14,window.innerHeight-tip.offsetHeight-8)+'px';
    } else tip.style.display='none';
    // which face a click would open, refreshed every frame: the globe spins, so
    // a banner only updated on mousemove would name a face that has moved on.
    if(flash){
      var dragging=(typeof drag!=='undefined')&&drag;   // the globe's own flag
      var f=(mouse&&!dragging&&!hit)?pick(mouse[0],mouse[1]):null;
      if(f){flash.style.display='block';flash.textContent='click → flat map of '+f.face;}
      else flash.style.display='none';
    }
    requestAnimationFrame(loop);
  }

  // ---- click a face -> open its flat map -----------------------------------
  // Screen ray -> model space (model is a pure rotation, so its inverse is its
  // transpose), intersect the cube (or the sphere when morphed), and read the
  // face + face-local u,v off the hit point.
  function mtv(m,v){return [m[0]*v[0]+m[1]*v[1]+m[2]*v[2],
                            m[4]*v[0]+m[5]*v[1]+m[6]*v[2],
                            m[8]*v[0]+m[9]*v[1]+m[10]*v[2]];}
  function pick(px,py){
    var Wp=cv.clientWidth, Hp=cv.clientHeight, asp=cv.width/cv.height;
    var ndx=(px/Wp)*2-1, ndy=1-(py/Hp)*2, tf=1/Math.tan(1.1/2);
    var de=[ndx*asp/tf, ndy/tf, -1];
    var model=mul(rotX(pitch),rotY(yaw));
    var o=mtv(model,[-panX,-panY,dist]);        // -R^T t, t=(panX,panY,-dist)
    var d=mtv(model,de);
    var hp=null;
    if(morph<0.5){                              // cube slab test on [-1,1]^3
      var tmin=-1e9,tmax=1e9;
      for(var a=0;a<3;a++){
        if(Math.abs(d[a])<1e-9){ if(o[a]<-1||o[a]>1) return null; continue; }
        var t1=(-1-o[a])/d[a], t2=(1-o[a])/d[a];
        if(t1>t2){var s=t1;t1=t2;t2=s;}
        if(t1>tmin)tmin=t1; if(t2<tmax)tmax=t2;
        if(tmin>tmax) return null;
      }
      var t=tmin>0?tmin:tmax; if(t<=0) return null;
      hp=[o[0]+d[0]*t,o[1]+d[1]*t,o[2]+d[2]*t];
    } else {                                    // unit sphere
      var b=2*(o[0]*d[0]+o[1]*d[1]+o[2]*d[2]);
      var aa=d[0]*d[0]+d[1]*d[1]+d[2]*d[2];
      var cq=o[0]*o[0]+o[1]*o[1]+o[2]*o[2]-1;
      var disc=b*b-4*aa*cq; if(disc<0) return null;
      var sq=Math.sqrt(disc), t0=(-b-sq)/(2*aa), t1b=(-b+sq)/(2*aa);
      var ts=t0>0?t0:t1b; if(ts<=0) return null;
      hp=[o[0]+d[0]*ts,o[1]+d[1]*ts,o[2]+d[2]*ts];
    }
    var ax=Math.abs(hp[0]),ay=Math.abs(hp[1]),az=Math.abs(hp[2]);
    var mx=Math.max(ax,ay,az); if(mx<1e-6) return null;
    var q=[hp[0]/mx,hp[1]/mx,hp[2]/mx];         // project onto the cube
    var f,u,v;
    if(ay>=ax&&ay>=az){ if(q[1]>0){f='NORTH_POLE';u=q[0];v=q[2];}
                        else {f='SOUTH_POLE';u=q[0];v=-q[2];} }
    else if(az>=ax){ if(q[2]>0){f='EQ_PRIME';u=q[0];v=-q[1];}
                     else {f='EQ_BACK';u=-q[0];v=-q[1];} }
    else { if(q[0]>0){f='EQ_EAST';u=-q[2];v=-q[1];}
           else {f='EQ_WEST';u=q[2];v=-q[1];} }
    return {face:f,u:Math.max(-1,Math.min(1,u)),v:Math.max(-1,Math.min(1,v))};
  }
  var down=null, navTimer=0;
  cv.addEventListener('mousedown',function(e){down=[e.clientX,e.clientY,Date.now()];});
  cv.addEventListener('click',function(e){
    if(!down) return;
    if(Math.abs(e.clientX-down[0])+Math.abs(e.clientY-down[1])>5) return;  // drag
    if(e.shiftKey||e.button!==0) return;
    var h=pick(e.clientX,e.clientY); if(!h) return;
    // deferred, so the globe's dbl-click-to-recentre still works: a second
    // click inside the double-click window cancels the navigation.
    clearTimeout(navTimer);
    navTimer=setTimeout(function(){
      location.href='/face?f='+h.face+'&u='+h.u.toFixed(5)+'&v='+h.v.toFixed(5);
    },240);
  });
  cv.addEventListener('dblclick',function(){clearTimeout(navTimer);});
  cv.addEventListener('mouseleave',function(){mouse=null;flash.style.display='none';});

  // ---- touch input (Pointer Events) ---------------------------------------
  // One finger rotates the globe, two fingers pinch-zoom (dist) and pan via
  // the midpoint (panX/panY, like shift-drag), a tap on a city dot shows its
  // tooltip (no hover on touch), a tap elsewhere dismisses it or -- when no
  // tooltip is up -- opens the tapped face's flat map like a mouse click.
  // server.py renames the stock globe's own touch* handlers so they cannot
  // double-apply the rotation; mouse pointers are left to the existing
  // mouse handlers (preventDefault below also suppresses the compatibility
  // mouse events a tap would otherwise synthesize).
  var tptrs={}, tmoved=0, tpinch=false, tlx=0, tly=0, tm0=null, td0=0;
  var tLast=0, tLastXY=null;
  function tids(){return Object.keys(tptrs);}
  cv.addEventListener('pointerdown',function(e){
    if(e.pointerType==='mouse')return;
    e.preventDefault();
    tptrs[e.pointerId]=[e.clientX,e.clientY];
    var ids=tids();
    if(ids.length===1){tmoved=0;tpinch=false;tlx=e.clientX;tly=e.clientY;
      spinning=false;
      try{spinBtn.textContent='resume';}catch(err){}}
    else if(ids.length===2){tpinch=true;
      var a=tptrs[ids[0]], b=tptrs[ids[1]];
      tm0=[(a[0]+b[0])/2,(a[1]+b[1])/2]; td0=Math.hypot(a[0]-b[0],a[1]-b[1]);}
    try{cv.setPointerCapture(e.pointerId);}catch(err){}
  });
  cv.addEventListener('pointermove',function(e){
    if(!(e.pointerId in tptrs))return;
    tptrs[e.pointerId]=[e.clientX,e.clientY];
    var ids=tids();
    if(ids.length>=2){
      var a=tptrs[ids[0]], b=tptrs[ids[1]];
      var mx=(a[0]+b[0])/2, my=(a[1]+b[1])/2, d=Math.hypot(a[0]-b[0],a[1]-b[1]);
      if(td0>0)dist=Math.max(1.08,Math.min(14,dist*td0/d));
      var pk=panK(); panX+=(mx-tm0[0])*pk; panY-=(my-tm0[1])*pk;
      tm0=[mx,my]; td0=d; tmoved+=999;
    } else {
      var dx=e.clientX-tlx, dy=e.clientY-tly;
      tmoved+=Math.abs(dx)+Math.abs(dy);
      var kk=dragK(); yaw+=dx*kk; pitch+=dy*kk;
      pitch=Math.max(-1.5,Math.min(1.5,pitch));
      tlx=e.clientX; tly=e.clientY;
    }
    if(tmoved>6){mouse=null;tip.style.display='none';}
  });
  function tEnd(e){
    if(!(e.pointerId in tptrs))return;
    delete tptrs[e.pointerId];
    var ids=tids();
    if(ids.length===1){tlx=tptrs[ids[0]][0];tly=tptrs[ids[0]][1];}
    if(ids.length)return;
    var was=tpinch; tpinch=false;
    if(e.type!=='pointerup'||was||tmoved>10)return;
    var now=Date.now();
    if(tLastXY&&now-tLast<350&&
       Math.abs(e.clientX-tLastXY[0])+Math.abs(e.clientY-tLastXY[1])<40){
      tLast=0;tLastXY=null;panX=0;panY=0;return;   // double-tap = recenter
    }
    tLast=now; tLastXY=[e.clientX,e.clientY];
    var hit=null,bd=20*20;                          // finger-sized hit circle
    for(var j=0;j<pts.length;j++){
      var dx=pts[j][0]-e.clientX, dy=pts[j][1]-e.clientY, d=dx*dx+dy*dy;
      if(d<bd){bd=d;hit=pts[j];}
    }
    if(hit){mouse=[e.clientX,e.clientY];return;}    // loop() shows the tooltip
    if(mouse){mouse=null;tip.style.display='none';return;}   // tap-away: dismiss
    var h=pick(e.clientX,e.clientY); if(!h)return;
    location.href='/face?f='+h.face+'&u='+h.u.toFixed(5)+'&v='+h.v.toFixed(5);
  }
  cv.addEventListener('pointerup',tEnd);
  cv.addEventListener('pointercancel',tEnd);

  var hint=document.getElementById('hint');
  if(hint) hint.textContent=hint.textContent+' — click a face · flat map';
  loop();
})();
</script>
"""
