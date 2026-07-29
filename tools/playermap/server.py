#!/usr/bin/env python3
"""Live player map: serves the CubeWorld globe with player markers overlaid.

Queries the Minecraft server over RCON for online players and their positions,
folds each world (x, z) to a cube-surface point (the same embedding the globe
renders), and the page projects a marker onto the spinning globe every frame.

Run: python3 server.py        (PORT env overrides the default 8080)
Reads the globe HTML from ../cubemap/out/globe.html (run `python -m cubemap
globe` first). No external dependencies — stdlib only.
"""
import http.server
import json
import math
import os
import socket
import socketserver
import struct
import sys
import threading
import time

import realmap

try:
    from structures import compute as scompute
    from structures.netherfaces import nether_face_uris
except Exception:                       # structures data missing -> overlay disabled
    scompute = None
    nether_face_uris = None
try:
    from biomegen.biomefaces import biome_face_uris
except Exception:                       # world-blob missing -> biome view disabled
    biome_face_uris = None

RCON_HOST = os.environ.get("RCON_HOST", "127.0.0.1")
RCON_PORT = int(os.environ.get("RCON_PORT", "25575"))
RCON_PW = os.environ.get("RCON_PW", "cubeworld-dev")

FACE = int(os.environ.get("FACE_SIZE", "10240"))
H = FACE / 2

# Overworld region files hold the real generated blocks. Default to the dev
# server's world relative to this tool; override with WORLD_REGION_DIR.
REGION_DIR = os.environ.get("WORLD_REGION_DIR", os.path.join(
    os.path.dirname(__file__), "..", "..", "run", "world",
    "dimensions", "minecraft", "overworld", "region"))
SAVE_EVERY = float(os.environ.get("SAVE_EVERY", "20"))   # min seconds between save-all

# Temporary historical-city overlay: precomputed cube points from cities_globe.json
# (delete the file to remove the overlay). See precompute in the commit message.
CITIES_JSON = os.environ.get("CITIES_JSON",
                             os.path.join(os.path.dirname(__file__), "cities_globe.json"))


def load_cities():
    try:
        with open(CITIES_JSON, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return []


def cities_by_face():
    """Group cities into {face: [(u, v, pop)]} for painting onto face textures."""
    d = {}
    for c in load_cities():
        d.setdefault(c["face"], []).append((c["u"], c["v"], c["pop"]))
    return d


def _cities_sig():
    try:
        return os.path.getmtime(CITIES_JSON)
    except OSError:
        return None


# Strongholds used to be baked into the face textures from a hand-maintained
# strongholds_globe.json. They are now the selectable `end_portals` overlay,
# computed from run/plugins/CubeWorld/strongholds.json which `/cubeworld
# strongholds` writes straight out of the generator state.


# Live teleport-station registry the plugin writes (world,x,y,z,city,name).
STATIONS_CSV = os.environ.get("STATIONS_CSV", os.path.join(
    os.path.dirname(__file__), "..", "..", "run", "plugins", "CubeWorld", "stations.csv"))


def stations_by_face():
    """Group teleport stations into {face: [(u, v)]} from the plugin's csv."""
    d = {}
    try:
        with open(STATIONS_CSV, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                p = line.split(",", 5)
                if len(p) < 6:
                    continue
                x, z = int(p[1]), int(p[3])
                face = face_at(x, z)
                if face is None:
                    continue
                gc, gr = GRID[face]
                d.setdefault(face, []).append(((x - gc * FACE) / H, (z - gr * FACE) / H))
    except Exception:
        return {}
    return d


def _stations_sig():
    try:
        return os.path.getmtime(STATIONS_CSV)
    except OSError:
        return None


GRID = {"NORTH_POLE": (0, -1), "EQ_PRIME": (0, 0), "EQ_EAST": (1, 0),
        "EQ_BACK": (2, 0), "EQ_WEST": (-1, 0), "SOUTH_POLE": (0, 1)}


def face_at(x, z):
    col = math.floor((x + H) / FACE)
    row = math.floor((z + H) / FACE)
    for f, (c, r) in GRID.items():
        if c == col and r == row:
            return f
    return None


def cube_point(f, u, v):
    return {"NORTH_POLE": (u, 1.0, v), "EQ_PRIME": (u, -v, 1.0),
            "SOUTH_POLE": (u, -1.0, -v), "EQ_EAST": (1.0, -v, -u),
            "EQ_BACK": (-u, -v, -1.0), "EQ_WEST": (-1.0, -v, u)}[f]


def world_to_cube(x, z):
    f = face_at(x, z)
    if f is None:
        return None
    gc, gr = GRID[f]
    u = (x - (gc * FACE - H)) / H - 1
    v = (z - (gr * FACE - H)) / H - 1
    return cube_point(f, u, v), f


def _recvn(s, n):
    data = b""
    while len(data) < n:
        chunk = s.recv(n - len(data))
        if not chunk:
            break
        data += chunk
    return data


def rcon(cmds):
    with socket.create_connection((RCON_HOST, RCON_PORT), timeout=5) as s:
        def send(t, body):
            pkt = struct.pack("<ii", 7, t) + body.encode() + b"\x00\x00"
            s.sendall(struct.pack("<i", len(pkt)) + pkt)

        def recv():
            ln = struct.unpack("<i", _recvn(s, 4))[0]
            return _recvn(s, ln)[8:-2].decode(errors="replace")

        send(3, RCON_PW)
        recv()
        out = []
        for c in cmds:
            send(2, c)
            out.append(recv())
        return out


def get_players():
    try:
        lst = rcon(["list"])[0]
    except Exception:
        return []
    if ":" not in lst:
        return []
    names = [n.strip() for n in lst.split(":", 1)[1].split(",") if n.strip()]
    if not names:
        return []
    try:
        res = rcon([f"data get entity {n} Pos" for n in names])
    except Exception:
        return []
    out = []
    for name, r in zip(names, res):
        i, j = r.find("["), r.find("]")
        if i < 0 or j < 0:
            continue
        try:
            x, y, z = (float(t.strip().rstrip("d")) for t in r[i + 1:j].split(","))
        except Exception:
            continue
        cp = world_to_cube(x, z)
        if cp is None:
            continue
        p, face = cp
        out.append({"name": name, "p": [p[0], p[1], p[2]], "face": face,
                    "x": round(x), "y": round(y), "z": round(z)})
    return out


MARKER_OVERLAY = r"""
<style>
  #pm{position:fixed;inset:0;pointer-events:none;z-index:10}
  .pmk{position:absolute;transform:translate(-50%,-100%);will-change:left,top}
  .pmk .dot{width:13px;height:13px;border-radius:50%;background:#ff4136;
    border:2px solid #fff;box-shadow:0 0 8px rgba(0,0,0,.7);margin:0 auto}
  .pmk .lbl{margin-top:3px;font:600 12px/1 system-ui,sans-serif;color:#fff;
    text-shadow:0 1px 3px #000,0 0 2px #000;text-align:center;white-space:nowrap}
  #pmcount{position:fixed;top:16px;right:16px;font:600 13px system-ui;color:#cdd6e4;
    background:rgba(12,16,24,.6);padding:8px 12px;border-radius:10px;
    border:1px solid rgba(120,150,190,.18);backdrop-filter:blur(6px)}
</style>
<div id="pm"></div>
<div id="pmcount">players: 0</div>
<script>
(function(){
  var cont=document.getElementById('pm'), countEl=document.getElementById('pmcount');
  var players=[], els={};
  function mvv(m,v){var o=[0,0,0,0];for(var r=0;r<4;r++){var s=0;
    for(var c=0;c<4;c++)s+=m[c*4+r]*v[c];o[r]=s;}return o;}
  async function poll(){
    try{var r=await fetch('/players',{cache:'no-store'});players=await r.json();}
    catch(e){}
    setTimeout(poll,1500);
  }
  // outward normal of each cube face (globe coords); the marker shows only
  // when the face the player stands on is turned toward the viewer.
  var FACE_N={NORTH_POLE:[0,1,0],SOUTH_POLE:[0,-1,0],EQ_PRIME:[0,0,1],
              EQ_BACK:[0,0,-1],EQ_EAST:[1,0,0],EQ_WEST:[-1,0,0]};
  function loop(){
    // reuse the globe's own matrices + view state (shared script scope)
    var model=mul(rotX(pitch),rotY(yaw));
    var asp=cv.width/cv.height;
    var mvp=mul(persp(1.1,asp,0.1,100),mul(trans3(panX,panY,-dist),model));
    var seen={};
    for(var k=0;k<players.length;k++){
      var pl=players[k], p=pl.p, n=Math.hypot(p[0],p[1],p[2]);
      var sph=[p[0]/n,p[1]/n,p[2]/n];
      var pos=[p[0]+(sph[0]-p[0])*morph,p[1]+(sph[1]-p[1])*morph,p[2]+(sph[2]-p[2])*morph];
      // sit the pin flush on the surface (was 1.04, which parallaxed the pin
      // outward onto adjacent geography near the globe's limb). The marker is
      // an HTML overlay drawn on top, so it is never occluded by the globe.
      var out=1.0, pp=[pos[0]*out,pos[1]*out,pos[2]*out];
      var c=mvv(mvp,[pp[0],pp[1],pp[2],1]);
      // cull by the player's FACE normal (cube), easing to the position normal
      // as the cube morphs to a sphere, so the pin only appears on that face.
      var fn=FACE_N[pl.face]||sph;
      var cn=[fn[0]+(sph[0]-fn[0])*morph,fn[1]+(sph[1]-fn[1])*morph,fn[2]+(sph[2]-fn[2])*morph];
      var vz=model[2]*cn[0]+model[6]*cn[1]+model[10]*cn[2]; // face-toward-camera test
      var el=els[pl.name];
      if(!el){el=document.createElement('div');el.className='pmk';
        el.innerHTML='<div class="dot"></div><div class="lbl"></div>';
        cont.appendChild(el);els[pl.name]=el;
        el.querySelector('.lbl').textContent=pl.name;}
      seen[pl.name]=1;
      if(c[3]>0 && vz>0.15){
        var sx=(c[0]/c[3]*0.5+0.5)*cv.clientWidth;
        var sy=(1-(c[1]/c[3]*0.5+0.5))*cv.clientHeight;
        el.style.display='block';el.style.left=sx+'px';el.style.top=sy+'px';
      } else el.style.display='none';
    }
    for(var name in els){if(!seen[name]){els[name].remove();delete els[name];}}
    countEl.textContent='players: '+players.length;
    requestAnimationFrame(loop);
  }
  poll(); loop();
})();
</script>
"""


FACES_REFRESH = r"""
<script>
// Live-refresh the six face textures with real generated blocks. The globe's
// gl context and texture list live in the shared top-level lexical scope, so
// we can re-upload straight into them as more of the world is explored.
(function(){
  function upload(k,uri){var img=new Image();img.onload=function(){
    gl.bindTexture(gl.TEXTURE_2D,texs[k]);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,false);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGB,gl.RGB,gl.UNSIGNED_BYTE,img);
    gl.generateMipmap(gl.TEXTURE_2D);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR_MIPMAP_LINEAR);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);};
    img.src=uri;}
  window.pmUpload=upload;                 // let the dimension toggle reuse it
  async function poll(){
    try{ if((window.pmDim||'overworld')==='overworld'){
      var r=await fetch('/faces',{cache:'no-store'});var u=await r.json();
      if(u&&u.length===6)for(var k=0;k<6;k++)upload(k,u[k]);} }catch(e){}
    setTimeout(poll,8000);
  }
  setTimeout(poll,8000);
})();
</script>
"""

# --- real generated blocks composited onto the model globe (mtime-cached) ---
_faces_lock = threading.Lock()
_cache = {"sig": None, "uris": None, "painted": 0}
_base_uris = [None]      # lazily-parsed base face URIs from globe.html
_last_save = [0.0]


def _globe_path():
    return os.path.join(os.path.dirname(__file__), "..", "cubemap", "out", "globe.html")


def _base_face_uris():
    if _base_uris[0] is None:
        with open(_globe_path(), encoding="utf-8") as f:
            _base_uris[0] = realmap.face_uris_from_html(f.read()) or []
    return _base_uris[0]


def _maybe_save():
    """Throttled `save-all flush` so freshly explored chunks reach the region
    files (in-memory chunks aren't otherwise visible to us)."""
    now = time.time()
    if now - _last_save[0] < SAVE_EVERY:
        return
    _last_save[0] = now
    try:
        rcon(["save-all flush"])
    except Exception:
        pass


def composited_uris():
    """The current composited faces — served instantly. The heavy recompute runs
    in a background worker (_faces_worker), so requests never block on it; until
    the first composite is ready we serve the base globe so the page still loads."""
    with _faces_lock:
        if _cache["uris"] is not None:
            return _cache["uris"]
    return _base_face_uris()


def _recompute_faces():
    """Recompute the composite if any input changed (region blocks / overlays)."""
    _maybe_save()
    sig = (realmap.region_signature(REGION_DIR), _cities_sig(), _stations_sig())
    with _faces_lock:
        if _cache["sig"] == sig and _cache["uris"] is not None:
            return
    base = _base_face_uris()
    if not base:
        return
    try:
        uris, painted = realmap.composite_uris(base, REGION_DIR, cities_by_face(),
                                               stations_by_face())
    except Exception as e:
        print("faces composite error:", e)
        return
    with _faces_lock:
        _cache.update(sig=sig, uris=uris, painted=painted)


def _faces_worker():
    while True:
        try:
            _recompute_faces()
        except Exception as e:
            print("faces worker error:", e)
        time.sleep(8)



STRUCTURE_OVERLAY = r"""
<style>
  #psov{position:fixed;inset:0;pointer-events:none;z-index:9}
  #psctl{position:fixed;top:16px;left:16px;z-index:11;max-height:84vh;overflow:auto;
    font:12px/1.35 system-ui,sans-serif;color:#cdd6e4;background:rgba(12,16,24,.72);
    padding:12px 14px;border-radius:12px;border:1px solid rgba(120,150,190,.2);
    backdrop-filter:blur(8px);width:214px}
  #psctl h3{margin:0 0 9px;font-size:13px;color:#eaf1fb;letter-spacing:.02em}
  #psctl label{display:flex;align-items:center;gap:7px;padding:2px 0;cursor:pointer;white-space:nowrap}
  #psctl .sw{width:11px;height:11px;border-radius:3px;flex:none;box-shadow:0 0 0 1px rgba(0,0,0,.4)}
  #psctl .ct{margin-left:auto;color:#8a97ab;font-variant-numeric:tabular-nums}
  .psrow{display:flex;gap:6px;margin:0 0 8px}
  #psseed{flex:1;min-width:0;background:rgba(0,0,0,.35);border:1px solid rgba(120,150,190,.3);
    color:#eaf1fb;border-radius:7px;padding:5px 7px;font:12px system-ui}
  #psctl button,#psctl select{background:rgba(80,120,200,.32);border:1px solid rgba(120,150,190,.3);
    color:#eaf1fb;border-radius:7px;padding:5px 8px;cursor:pointer;font:12px system-ui}
  #psctl button:hover{background:rgba(90,140,230,.5)}
  #psctl .mut{color:#8a97ab;font-size:11px;margin-top:8px}
  #pstog{display:flex;gap:6px;margin-bottom:6px}
  #pstog button{flex:1;padding:3px}
  #bkey{position:fixed;top:16px;right:16px;z-index:11;max-height:88vh;overflow:auto;
    display:none;font:12px/1.35 system-ui,sans-serif;color:#cdd6e4;
    background:rgba(12,16,24,.72);padding:12px 14px;border-radius:12px;
    border:1px solid rgba(120,150,190,.2);backdrop-filter:blur(8px);width:190px}
  #bkey h3{margin:0 0 9px;font-size:13px;color:#eaf1fb;letter-spacing:.02em}
  #bkey .row{display:flex;align-items:center;gap:7px;padding:2px 0}
  #bkey .sw{width:11px;height:11px;border-radius:3px;flex:none;box-shadow:0 0 0 1px rgba(0,0,0,.4)}
  #bkey .nm{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  #bkey .ct{margin-left:auto;color:#8a97ab;font-variant-numeric:tabular-nums;padding-left:6px}
</style>
<canvas id="psov"></canvas>
<div id="bkey"><h3>Biomes</h3><div id="bkeylist"></div></div>
<div id="psctl">
  <h3>Structures</h3>
  <div class="psrow"><select id="psdim"><option value="overworld">Overworld (terrain)</option>
    <option value="biomes">Overworld (biomes)</option>
    <option value="nether">Nether</option></select></div>
  <div class="psrow"><input id="psseed" value="__SEED__" spellcheck="false" title="world seed">
    <button id="psgo">Go</button></div>
  <div id="pstog"><button id="psall">all</button><button id="psnone">none</button></div>
  <div id="pslist"></div>
  <div class="mut" id="psstatus">loading…</div>
</div>
<script>
(function(){
  var COL={villages:'#ffd166',desert_pyramids:'#f4a259',jungle_temples:'#43aa8b',
    igloos:'#a8e6ff',ocean_monuments:'#00b4d8',ocean_ruins:'#5fa8d3',shipwrecks:'#c9ada7',
    ruined_portals:'#f15bb5',pillager_outposts:'#ef476f',trail_ruins:'#c58c4f',
    trial_chambers:'#9b5de5',woodland_mansions:'#7161a8',buried_treasures:'#ffea00',
    swamp_huts:'#588157',ancient_cities:'#4361ee',
    end_portals:'#b8f2e6',zombie_villages:'#6a994e',
    fortresses:'#8d0801',bastions:'#5a189a',nether_fossils:'#e9ecef'};
  // dense sets start hidden so the map isn't a wall of dots
  var OFF={ocean_ruins:1,shipwrecks:1,buried_treasures:1,ruined_portals:1,
           trial_chambers:1,nether_fossils:1};
  function col(t){return COL[t]||'#9aa7bd';}
  var cvs=document.getElementById('psov'), ctx=cvs.getContext('2d');
  var listEl=document.getElementById('pslist'), statusEl=document.getElementById('psstatus');
  var data={}, on={}, dpr=Math.max(1,window.devicePixelRatio||1);
  function mvv(m,v){var o=[0,0,0,0];for(var r=0;r<4;r++){var s=0;
    for(var c=0;c<4;c++)s+=m[c*4+r]*v[c];o[r]=s;}return o;}
  var FN={NORTH_POLE:[0,1,0],SOUTH_POLE:[0,-1,0],EQ_PRIME:[0,0,1],
          EQ_BACK:[0,0,-1],EQ_EAST:[1,0,0],EQ_WEST:[-1,0,0]};
  function buildList(){
    listEl.innerHTML='';
    Object.keys(data).sort().forEach(function(t){
      var lab=document.createElement('label');
      lab.innerHTML='<span class="sw" style="background:'+col(t)+'"></span>'+
        '<input type="checkbox"'+(on[t]?' checked':'')+'>'+t.replace(/_/g,' ')+
        '<span class="ct">'+data[t].length.toLocaleString()+'</span>';
      lab.querySelector('input').onchange=function(e){on[t]=e.target.checked;};
      listEl.appendChild(lab);
    });
  }
  function fetchStructures(){
    var seed=document.getElementById('psseed').value.trim();
    var dim=document.getElementById('psdim').value;
    var sdim=(dim==='biomes')?'overworld':dim;      // biome view shows overworld structures
    statusEl.textContent='computing…';
    fetch('/structures?seed='+encodeURIComponent(seed)+'&dim='+sdim,{cache:'no-store'})
      .then(function(r){return r.json();}).then(function(j){
        data=j; var n=0;
        Object.keys(j).forEach(function(t){ n+=j[t].length;
          if(!(t in on)) on[t]=(j[t].length>0 && !OFF[t]); });
        buildList();
        statusEl.textContent=n.toLocaleString()+' placements  ·  seed '+seed;
      }).catch(function(e){statusEl.textContent='error: '+e;});
  }
  function updateBiomeKey(dim){
    var box=document.getElementById('bkey');
    if(dim!=='biomes'){box.style.display='none';return;}
    fetch('/biomelegend',{cache:'no-store'})
      .then(function(r){return r.json();}).then(function(rows){
        var h='';
        for(var i=0;i<rows.length;i++)
          h+='<div class="row"><span class="sw" style="background:'+rows[i].color+
             '"></span><span class="nm">'+rows[i].name.replace(/_/g,' ')+
             '</span><span class="ct">'+rows[i].pct.toFixed(1)+'%</span></div>';
        document.getElementById('bkeylist').innerHTML=h;
        box.style.display='block';
      }).catch(function(){box.style.display='none';});
  }
  function swapFaces(dim){
    window.pmDim=dim;
    updateBiomeKey(dim);
    var seed=encodeURIComponent(document.getElementById('psseed').value.trim());
    var url=dim==='nether'?'/netherfaces':
            dim==='biomes'?('/biomefaces?seed='+seed):'/faces';
    statusEl.textContent=(dim==='biomes')?'rendering biomes…':statusEl.textContent;
    fetch(url,{cache:'no-store'})
      .then(function(r){return r.json();}).then(function(u){
        if(window.pmUpload&&u&&u.length===6)for(var k=0;k<6;k++)window.pmUpload(k,u[k]);})
      .catch(function(){});
  }
  document.getElementById('psgo').onclick=fetchStructures;
  document.getElementById('psseed').addEventListener('keydown',function(e){
    if(e.key==='Enter')fetchStructures();});
  document.getElementById('psdim').onchange=function(){
    swapFaces(document.getElementById('psdim').value); fetchStructures();};
  document.getElementById('psall').onclick=function(){
    Object.keys(data).forEach(function(t){on[t]=true;});buildList();};
  document.getElementById('psnone').onclick=function(){
    Object.keys(data).forEach(function(t){on[t]=false;});buildList();};
  function draw(){
    var W=cv.clientWidth, Hh=cv.clientHeight;
    if(cvs.width!==W*dpr||cvs.height!==Hh*dpr){cvs.width=W*dpr;cvs.height=Hh*dpr;
      cvs.style.width=W+'px';cvs.style.height=Hh+'px';}
    ctx.setTransform(dpr,0,0,dpr,0,0); ctx.clearRect(0,0,W,Hh);
    var model=mul(rotX(pitch),rotY(yaw));
    var asp=cv.width/cv.height;
    var mvp=mul(persp(1.1,asp,0.1,100),mul(trans3(panX,panY,-dist),model));
    Object.keys(data).forEach(function(t){
      if(!on[t])return;
      ctx.fillStyle=col(t);
      var arr=data[t];
      for(var i=0;i<arr.length;i++){
        var m=arr[i], px=m[0],py=m[1],pz=m[2];
        var nn=Math.hypot(px,py,pz), sx0=px/nn,sy0=py/nn,sz0=pz/nn;
        var qx=px+(sx0-px)*morph, qy=py+(sy0-py)*morph, qz=pz+(sz0-pz)*morph;
        var c=mvv(mvp,[qx,qy,qz,1]); if(c[3]<=0)continue;
        var fn=FN[m[3]]||[sx0,sy0,sz0];
        var cnx=fn[0]+(sx0-fn[0])*morph, cny=fn[1]+(sy0-fn[1])*morph, cnz=fn[2]+(sz0-fn[2])*morph;
        // hide markers whose face is turned away (matches the player-pin cull)
        if(model[2]*cnx+model[6]*cny+model[10]*cnz<=0.15)continue;
        var X=(c[0]/c[3]*0.5+0.5)*W, Y=(1-(c[1]/c[3]*0.5+0.5))*Hh;
        ctx.fillRect(X-2.3,Y-2.3,4.6,4.6);
      }
    });
    requestAnimationFrame(draw);
  }
  fetchStructures(); draw();
})();
</script>
"""


def default_seed():
    p = os.path.join(os.path.dirname(__file__), "..", "..", "run", "server.properties")
    try:
        for line in open(p):
            if line.startswith("level-seed="):
                return int(line.split("=", 1)[1].strip())
    except Exception:
        pass
    try:
        return int(rcon(["seed"])[0].split("[")[1].split("]")[0])
    except Exception:
        return 0


def structures_json(seed, dim):
    if scompute is None:
        return {}
    ov = scompute.compute_overlays(seed, dim)
    out = {}
    for name, ms in ov.items():
        out[name] = [[round(m["p"][0], 4), round(m["p"][1], 4), round(m["p"][2], 4), m["face"]]
                     for m in ms]
    return out


_biomelegend_cache = {"sig": None, "rows": None}


def biome_legend_rows():
    """[{name,color,pct}] for the biomes on the globe, area-weighted, sharing the
    exact colours /biomefaces paints so the key matches the map. Cached on the
    raster mtime."""
    import biomeglobe
    import numpy as np
    path = scompute.raster_path("overworld")
    sig = os.path.getmtime(path)
    if _biomelegend_cache["sig"] == sig and _biomelegend_cache["rows"] is not None:
        return _biomelegend_cache["rows"]
    r = scompute.raster("overworld")
    counts = {}
    total = 0
    for _n, _cmx, _cmz, grid in r.faces:
        idx, cnt = np.unique(grid, return_counts=True)
        for i, c in zip(idx.tolist(), cnt.tolist()):
            counts[r.palette[i]] = counts.get(r.palette[i], 0) + c
            total += c
    rows = []
    for b, c in sorted(counts.items(), key=lambda kv: -kv[1]):
        pct = 100.0 * c / total
        if pct < 0.05:                       # keep the key legible
            continue
        rgb = biomeglobe.colour_of(b)
        rows.append({"name": b.split(":")[-1],
                     "color": "rgb(%d,%d,%d)" % rgb, "pct": round(pct, 2)})
    _biomelegend_cache["sig"] = sig
    _biomelegend_cache["rows"] = rows
    return rows


_biomefaces_cache = {"sig": None, "uris": None}


def biome_face_uris_from_raster():
    """Globe face textures coloured by the biomes the world ACTUALLY generates.

    This used to come from `biomegen`, which re-derives biomes in Python from
    the raw climate rasters -- an approximation that does not move when the
    generator changes, and which needed a worldblob.cwb build artefact that was
    not present, so the route raised FileNotFoundError and the browser got a
    dropped connection rather than an error. Same source as /biomes now: the
    plugin's own dump of the real biome provider.

    Faces are resized to a power of two because WebGL wants that for mipmaps,
    with NEAREST so the per-chunk cells stay crisp, and are returned in
    realmap.FACE_ORDER -- the raster stores them in the plugin's CubeFace order,
    which is NOT the same, so they are matched by name.
    """
    import base64
    import io
    import biomeglobe
    import numpy as np
    from PIL import Image

    path = scompute.raster_path("overworld")
    sig = os.path.getmtime(path)
    if _biomefaces_cache["sig"] == sig and _biomefaces_cache["uris"]:
        return _biomefaces_cache["uris"]
    r = scompute.raster("overworld")
    lut = np.array([biomeglobe.colour_of(b) for b in r.palette], dtype=np.uint8)
    by_name = {}
    for name, _cmx, _cmz, grid in r.faces:
        g = grid.reshape(r.chunks, r.chunks)
        rgb = lut[np.clip(g, 0, len(r.palette) - 1)].transpose(1, 0, 2)
        im = Image.fromarray(rgb, "RGB").resize((1024, 1024), Image.NEAREST)
        buf = io.BytesIO()
        im.save(buf, "PNG")
        by_name[name] = ("data:image/png;base64,"
                         + base64.b64encode(buf.getvalue()).decode())
    uris = [by_name[f] for f in realmap.FACE_ORDER]
    _biomefaces_cache["sig"] = sig
    _biomefaces_cache["uris"] = uris
    return uris


_biomes_cache = {"sig": None, "html": None}


def biomes_page():
    """The biome page, rebuilt whenever overworld.cwbr changes underneath it."""
    import biomeglobe
    here = os.path.dirname(__file__)
    out = os.path.join(here, "biomes.html")
    try:
        sig = os.path.getmtime(scompute.raster_path("overworld"))
    except Exception:
        sig = None
    if sig is None:
        return (b"<h1>No biome raster</h1><p>Run <code>/cubeworld biomeraster "
                b"overworld</code> on the server, then reload.</p>")
    if _biomes_cache["sig"] == sig and _biomes_cache["html"] is not None:
        return _biomes_cache["html"]
    try:
        r, written = biomeglobe.render(scompute.raster_path("overworld"))
        biomeglobe.build_page(r, written, out)
        with open(out, "rb") as f:
            html = f.read()
    except Exception as e:
        return f"<h1>Biome page failed</h1><pre>{e}</pre>".encode()
    _biomes_cache["sig"] = sig
    _biomes_cache["html"] = html
    return html


def load_page():
    with open(_globe_path(), encoding="utf-8") as f:
        html = f.read()
    uris = composited_uris()
    if uris:
        html = realmap.replace_uris(html, uris)
    # City dots are painted onto the face textures themselves (composited_uris),
    # so they sit on each face and occlude correctly — no screen-space overlay.
    page = html + MARKER_OVERLAY + FACES_REFRESH
    if scompute is not None:
        page += STRUCTURE_OVERLAY.replace("__SEED__", str(default_seed()))
    return page


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, body, ctype):
        try:
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass          # client went away mid-response — never fatal

    def do_GET(self):
        try:
            if self.path.startswith("/compare"):
                # Side-by-side current-vs-compact parameter study, generated by
                # tools/compact/render_compare.py (self-contained HTML).
                p = os.path.join(os.path.dirname(__file__), "..", "compact",
                                 "out", "compare.html")
                if os.path.exists(p):
                    with open(p, "rb") as f:
                        self._send(f.read(), "text/html; charset=utf-8")
                else:
                    self._send(b"<h1>No comparison built</h1><p>Run "
                               b"<code>python3 tools/compact/render_compare.py</code>.</p>",
                               "text/html; charset=utf-8")
            elif self.path.startswith("/biomes"):
                # The biomes the world ACTUALLY generates, read from
                # overworld.cwbr (the plugin's own dump from the real biome
                # provider). The spinning globe on /  is a climate
                # classification computed from the raw rasters and does not move
                # when the generator changes -- it showed the Amazon as
                # rainforest while the server was generating mangrove swamp.
                #
                # Rebuilt HERE rather than by hand. It used to be a static file
                # refreshed by running biomeglobe.py, which meant the one page
                # whose whole purpose is "what does the generator do now" was the
                # most likely thing on the map to be hours out of date -- and it
                # said so nowhere.
                self._send(biomes_page(), "text/html; charset=utf-8")
            elif self.path.startswith("/players"):
                self._send(json.dumps(get_players()).encode(), "application/json")
            elif self.path.startswith("/faces"):
                self._send(json.dumps(composited_uris() or []).encode(), "application/json")
            elif self.path.startswith("/structures"):
                from urllib.parse import urlparse, parse_qs
                q = parse_qs(urlparse(self.path).query)
                try:
                    seed = int(q.get("seed", [str(default_seed())])[0])
                except ValueError:
                    seed = default_seed()
                dim = q.get("dim", ["overworld"])[0]
                self._send(json.dumps(structures_json(seed, dim)).encode(), "application/json")
            elif self.path.startswith("/netherfaces"):
                uris = nether_face_uris() if nether_face_uris else []
                self._send(json.dumps(uris).encode(), "application/json")
            elif self.path.startswith("/biomelegend"):
                try:
                    rows = biome_legend_rows()
                except Exception as e:
                    print("biomelegend error:", e)
                    rows = []
                self._send(json.dumps(rows).encode(), "application/json")
            elif self.path.startswith("/biomefaces"):
                # never let a missing/!broken input drop the connection -- the
                # page just gets an empty list and keeps the terrain faces
                try:
                    uris = biome_face_uris_from_raster()
                except Exception as e:
                    print("biomefaces error:", e)
                    uris = []
                self._send(json.dumps(uris).encode(), "application/json")
            else:
                self._send(load_page().encode("utf-8"), "text/html; charset=utf-8")
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


class MapServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def handle_error(self, request, client_address):
        e = sys.exc_info()[1]
        if isinstance(e, (BrokenPipeError, ConnectionResetError, ConnectionAbortedError)):
            return    # a disconnecting client must never take the server down
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    threading.Thread(target=_faces_worker, daemon=True).start()   # warm + keep the composite fresh
    _sseed = default_seed()
    if scompute is not None:                                       # warm structure overlays
        threading.Thread(target=lambda: (scompute.precompute(_sseed, "overworld"),
                                         scompute.precompute(_sseed, "nether")),
                         daemon=True).start()
    if biome_face_uris is not None:                               # warm the biome faces
        def _warm_biomes():
            try:
                biome_face_uris(_sseed)
            except Exception as e:
                print("biome warm failed:", e)
        threading.Thread(target=_warm_biomes, daemon=True).start()
    with MapServer(("0.0.0.0", port), Handler) as srv:
        print(f"player map serving on http://0.0.0.0:{port}  (RCON {RCON_HOST}:{RCON_PORT})")
        srv.serve_forever()
