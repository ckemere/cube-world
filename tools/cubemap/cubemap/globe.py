"""Render real-elevation textures for the six cube faces and assemble a
self-contained, spinnable WebGL globe (no external libraries — artifact CSP
blocks CDN scripts)."""
from __future__ import annotations
import base64
import io
import json
import os
import numpy as np
from PIL import Image, ImageDraw

from .geometry import CubeProjection, FACES
from .raster import hypsometric_rgb, biome_rgb, EquirectRaster

RIVER_COL = (70, 120, 175)
LAKE_COL = (52, 118, 150)


def _load_lines(path):
    d = json.load(open(path))
    out = []
    for feat in d["features"]:
        g = feat.get("geometry")
        if not g:
            continue
        if g["type"] == "LineString":
            out.append(g["coordinates"])
        elif g["type"] == "MultiLineString":
            out.extend(g["coordinates"])
    return out


def _load_rings(path):
    d = json.load(open(path))
    out = []
    for feat in d["features"]:
        g = feat.get("geometry")
        if not g:
            continue
        if g["type"] == "Polygon":
            out.append(g["coordinates"][0])
        elif g["type"] == "MultiPolygon":
            for poly in g["coordinates"]:
                out.append(poly[0])
    return out


def _project_runs(proj, verts, face, size):
    """Project a lon/lat vertex list to pixel coords on `face`, split into
    runs that stay on this face (segments crossing to another face break)."""
    runs, cur = [], []
    for lon, lat in verts:
        f, u, v = proj.lonlat_to_face_uv(lon, lat)
        if f == face:
            cur.append(((u + 1) / 2 * (size - 1), (v + 1) / 2 * (size - 1)))
        elif cur:
            runs.append(cur); cur = []
    if cur:
        runs.append(cur)
    return runs

# Cube net order used by the shader/JS. Each face gets its own texture.
FACE_ORDER = ["NORTH_POLE", "SOUTH_POLE", "EQ_PRIME", "EQ_BACK", "EQ_EAST", "EQ_WEST"]


def render_face(proj: CubeProjection, raster, face, size=1024, rivers=None, lakes=None,
                climate=None):
    lon, lat = proj.face_lonlat_grid(face, size)
    elev = raster.sample(lon, lat)
    if climate is not None:
        temp = climate[0].sample(lon, lat)
        precip = climate[1].sample(lon, lat)
        rgb = biome_rgb(elev, temp, precip, lat)
    else:
        rgb = hypsometric_rgb(elev, lat)
    if rivers or lakes:
        im = Image.fromarray(rgb)
        d = ImageDraw.Draw(im)
        for ring in (lakes or []):
            for run in _project_runs(proj, ring, face, size):
                if len(run) >= 3:
                    d.polygon(run, fill=LAKE_COL)
        w = max(1, size // 700)
        for line in (rivers or []):
            for run in _project_runs(proj, line, face, size):
                if len(run) >= 2:
                    d.line(run, fill=RIVER_COL, width=w, joint="curve")
        rgb = np.array(im)
    return rgb, elev


def render_equirect(proj_unused, raster, w=2048):
    h = w // 2
    lon = (np.linspace(0, w - 1, w) + 0.5) / w * 360.0 - 180.0
    lat = 90.0 - (np.linspace(0, h - 1, h) + 0.5) / h * 180.0
    lon2, lat2 = np.meshgrid(lon, lat)
    elev = raster.sample(lon2, lat2)
    return hypsometric_rgb(elev, lat2)


def _img_data_uri(rgb, quality=88):
    # JPEG: hypsometric maps are smooth, so this is ~6x smaller than PNG with
    # no visible artifacts, keeping the self-contained artifact light.
    im = Image.fromarray(rgb)
    buf = io.BytesIO()
    im.save(buf, format="JPEG", quality=quality, subsampling=0)
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    return "data:image/jpeg;base64," + b64


def build_html(proj: CubeProjection, raster, face_size=1024, title="CubeWorld — Earth",
               data_dir=None):
    rivers = lakes = climate = None
    if data_dir:
        rp = os.path.join(data_dir, "ne_10m_rivers_lake_centerlines.geojson")
        lp = os.path.join(data_dir, "ne_10m_lakes.geojson")
        rivers = _load_lines(rp) if os.path.exists(rp) else None
        lakes = _load_rings(lp) if os.path.exists(lp) else None
        tp = os.path.join(data_dir, "wc2.1_10m_bio_1.tif")
        pp = os.path.join(data_dir, "wc2.1_10m_bio_12.tif")
        if os.path.exists(tp) and os.path.exists(pp):
            climate = (EquirectRaster.load_geotiff(tp), EquirectRaster.load_geotiff(pp))
    faces = {f: _img_data_uri(
                 render_face(proj, raster, f, face_size, rivers, lakes, climate)[0])
             for f in FACES}
    # order the six data URIs to match the JS FACE_ORDER
    uris = [faces[f] for f in FACE_ORDER]
    uris_js = ",\n".join(f'"{u}"' for u in uris)
    return _HTML_TEMPLATE.replace("/*FACE_URIS*/", uris_js).replace("__TITLE__", title) \
                         .replace("__ROLL__", f"{proj.roll_deg:g}")


_HTML_TEMPLATE = r"""<title>__TITLE__</title>
<style>
  :root{
    --ground:#070a10; --ground2:#0c121c;
    --panel:rgba(14,19,28,.62); --edge:rgba(120,150,190,.18);
    --ink:#c3cdda; --muted:#6b7a90; --accent:#5fb0c4;
    --mono:ui-monospace,"SF Mono",Menlo,Consolas,monospace;
    --sans:system-ui,-apple-system,sans-serif;
  }
  :root[data-theme="light"]{
    --ground:#dbe2ec; --ground2:#c9d3e0;
    --panel:rgba(255,255,255,.72); --edge:rgba(40,70,110,.16);
    --ink:#26313f; --muted:#5a6b80; --accent:#2b7d90;
  }
  @media (prefers-color-scheme: light){
    :root:not([data-theme="dark"]){
      --ground:#dbe2ec; --ground2:#c9d3e0;
      --panel:rgba(255,255,255,.72); --edge:rgba(40,70,110,.16);
      --ink:#26313f; --muted:#5a6b80; --accent:#2b7d90;
    }
  }
  *{box-sizing:border-box}
  html,body{margin:0;height:100%;overflow:hidden;color:var(--ink);
    font-family:var(--sans);
    background:radial-gradient(120% 120% at 50% 40%,var(--ground2),var(--ground) 70%)}
  #c{width:100vw;height:100vh;display:block;cursor:grab;touch-action:none}
  #c:active{cursor:grabbing}
  .panel{position:fixed;background:var(--panel);border:1px solid var(--edge);
    border-radius:10px;backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px)}
  #hud{top:16px;left:16px;padding:11px 14px;pointer-events:none}
  #hud .k{font-family:var(--mono);font-size:11px;letter-spacing:.14em;
    text-transform:uppercase;color:var(--muted)}
  #hud .t{font-family:var(--mono);font-size:15px;margin-top:2px;letter-spacing:.02em}
  #hud .t b{color:var(--accent);font-weight:600}
  #hint{font-family:var(--mono);font-size:11px;color:var(--muted);margin-top:7px;
    letter-spacing:.06em}
  .btn{bottom:18px;left:16px;display:flex;gap:8px;padding:0;background:none;border:none}
  .btn button{font-family:var(--mono);font-size:12px;letter-spacing:.08em;
    text-transform:uppercase;color:var(--ink);background:var(--panel);
    border:1px solid var(--edge);border-radius:9px;padding:9px 13px;cursor:pointer;
    backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);transition:border-color .15s,color .15s}
  .btn button:hover{border-color:var(--accent);color:var(--accent)}
  .btn button:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
  #legend{bottom:18px;right:16px;padding:10px 12px;display:flex;gap:10px;align-items:stretch}
  #legend .bar{width:11px;border-radius:3px;
    background:linear-gradient(#ffffff,#96826f 22%,#cdb279 40%,#7eab5c 58%,#3a84a5 60%,#1a3e69 82%,#091838)}
  #legend .lab{display:flex;flex-direction:column;justify-content:space-between;
    font-family:var(--mono);font-size:10px;letter-spacing:.08em;color:var(--muted)}
</style>
<canvas id="c"></canvas>
<div id="hud" class="panel">
  <div class="k">CubeWorld · planet</div>
  <div class="t">orientation roll <b>__ROLL__°</b></div>
  <div id="hint">drag · spin — scroll · zoom</div>
</div>
<div class="btn panel" style="background:none;border:none;backdrop-filter:none">
  <button id="morph">cube ⇄ sphere</button>
  <button id="spin">pause</button>
</div>
<div id="legend" class="panel">
  <div class="bar"></div>
  <div class="lab"><span>8&nbsp;km</span><span>0</span><span>−11&nbsp;km</span></div>
</div>
<script>
const FACE_URIS=[
/*FACE_URIS*/
];
const cv=document.getElementById("c");
const gl=cv.getContext("webgl",{antialias:true});
function rz(){cv.width=innerWidth*devicePixelRatio;cv.height=innerHeight*devicePixelRatio;
  gl.viewport(0,0,cv.width,cv.height);}
addEventListener("resize",rz);rz();

// ---- build a subdivided cube; each face's verts carry a face index + uv ----
const N=64; // subdivisions per face edge (relief-ready)
// face basis: origin corner + edge vectors, matching CubeSurface embedding.
// order must match FACE_URIS: N_POLE,S_POLE,EQ_PRIME,EQ_BACK,EQ_EAST,EQ_WEST
const F=[
 {o:[-1, 1,-1],u:[2,0,0],v:[0,0,2]}, // NORTH_POLE  (x, +1, z)
 {o:[-1,-1, 1],u:[2,0,0],v:[0,0,-2]},// SOUTH_POLE  (x, -1, -z)
 {o:[-1, 1, 1],u:[2,0,0],v:[0,-2,0]},// EQ_PRIME    (x, -z-> from +1..-1, +1)
 {o:[-1,-1,-1],u:[2,0,0],v:[0,2,0]}, // EQ_BACK     (x, z, -1)
 {o:[ 1, 1,-1],u:[0,-2,0],v:[0,0,2]},// EQ_EAST     (1, -u, v)
 {o:[-1,-1,-1],u:[0,2,0],v:[0,0,2]}, // EQ_WEST     (-1, u, v)
];
let positions=[],uvs=[],faceIdx=[],indices=[],vcount=0;
for(let f=0;f<6;f++){
  const {o,u,v}=F[f];const base=vcount;
  for(let j=0;j<=N;j++)for(let i=0;i<=N;i++){
    const s=i/N,t=j/N;
    positions.push(o[0]+u[0]*s+v[0]*t,o[1]+u[1]*s+v[1]*t,o[2]+u[2]*s+v[2]*t);
    uvs.push(s,t);faceIdx.push(f);vcount++;
  }
  for(let j=0;j<N;j++)for(let i=0;i<N;i++){
    const a=base+j*(N+1)+i,b=a+1,c=a+(N+1),d=c+1;
    indices.push(a,c,b, b,c,d);
  }
}
function buf(arr,type){const b=gl.createBuffer();gl.bindBuffer(type,b);
  gl.bufferData(type,arr,gl.STATIC_DRAW);return b;}
const pB=buf(new Float32Array(positions),gl.ARRAY_BUFFER);
const uB=buf(new Float32Array(uvs),gl.ARRAY_BUFFER);
const fB=buf(new Float32Array(faceIdx),gl.ARRAY_BUFFER);
// 6*(65*65)=25350 verts < 65536, so 16-bit indices are safe (no extension needed)
const iB=buf(new Uint16Array(indices),gl.ELEMENT_ARRAY_BUFFER);

const vs=`attribute vec3 p;attribute vec2 uv;attribute float fi;
uniform mat4 mvp;uniform mat4 uModel;uniform float morph;
varying vec2 vUv;varying float vFi;varying vec3 vN;
void main(){vUv=uv;vFi=fi;
  vec3 sph=normalize(p);           // cube -> sphere direction
  vec3 pos=mix(p,sph,morph);       // morph cube<->sphere
  vN=normalize((uModel*vec4(sph,0.0)).xyz);   // view-space normal (sun sweeps as it spins)
  gl_Position=mvp*vec4(pos,1.0);}`;
const fs=`precision mediump float;varying vec2 vUv;varying float vFi;varying vec3 vN;
uniform sampler2D tex[6];
vec4 samp(int i,vec2 uv){
  if(i==0)return texture2D(tex[0],uv);if(i==1)return texture2D(tex[1],uv);
  if(i==2)return texture2D(tex[2],uv);if(i==3)return texture2D(tex[3],uv);
  if(i==4)return texture2D(tex[4],uv);return texture2D(tex[5],uv);}
void main(){int i=int(vFi+0.5);vec4 c=samp(i,vUv);
  vec3 L=normalize(vec3(0.45,0.55,0.72));
  float d=max(dot(normalize(vN),L),0.0);
  float lit=0.38+0.72*d;                 // ambient + lambert terminator
  gl_FragColor=vec4(c.rgb*lit,1.0);}`;
function sh(t,s){const o=gl.createShader(t);gl.shaderSource(o,s);gl.compileShader(o);
  if(!gl.getShaderParameter(o,gl.COMPILE_STATUS))console.error(gl.getShaderInfoLog(o));return o;}
const prog=gl.createProgram();gl.attachShader(prog,sh(gl.VERTEX_SHADER,vs));
gl.attachShader(prog,sh(gl.FRAGMENT_SHADER,fs));gl.linkProgram(prog);gl.useProgram(prog);
const aP=gl.getAttribLocation(prog,"p"),aU=gl.getAttribLocation(prog,"uv"),aF=gl.getAttribLocation(prog,"fi");
const uMVP=gl.getUniformLocation(prog,"mvp"),uMorph=gl.getUniformLocation(prog,"morph"),
      uModel=gl.getUniformLocation(prog,"uModel");

const texs=[];
FACE_URIS.forEach((uri,k)=>{const t=gl.createTexture();texs.push(t);
  const img=new Image();img.onload=()=>{gl.bindTexture(gl.TEXTURE_2D,t);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,false);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGB,gl.RGB,gl.UNSIGNED_BYTE,img);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);};
  img.src=uri;});
for(let k=0;k<6;k++)gl.uniform1i(gl.getUniformLocation(prog,"tex["+k+"]"),k);

function mul(a,b){const o=new Float32Array(16);for(let r=0;r<4;r++)for(let c=0;c<4;c++){
  let s=0;for(let k=0;k<4;k++)s+=a[k*4+r]*b[c*4+k];o[c*4+r]=s;}return o;}
function persp(f,asp,n,fr){const t=1/Math.tan(f/2);return new Float32Array(
  [t/asp,0,0,0, 0,t,0,0, 0,0,(fr+n)/(n-fr),-1, 0,0,2*fr*n/(n-fr),0]);}
function rotY(a){const c=Math.cos(a),s=Math.sin(a);return new Float32Array(
  [c,0,-s,0, 0,1,0,0, s,0,c,0, 0,0,0,1]);}
function rotX(a){const c=Math.cos(a),s=Math.sin(a);return new Float32Array(
  [1,0,0,0, 0,c,s,0, 0,-s,c,0, 0,0,0,1]);}
function trans(z){const m=new Float32Array(16);m[0]=m[5]=m[10]=m[15]=1;m[14]=z;return m;}

let yaw=0.6,pitch=-0.35,dist=4.2,morph=0,targetMorph=0;
let spinning=!matchMedia("(prefers-reduced-motion: reduce)").matches;
let drag=false,lx=0,ly=0;
cv.addEventListener("mousedown",e=>{drag=true;lx=e.clientX;ly=e.clientY;spinning=false;});
addEventListener("mouseup",()=>drag=false);
addEventListener("mousemove",e=>{if(!drag)return;yaw+=(e.clientX-lx)*0.01;
  pitch+=(e.clientY-ly)*0.01;pitch=Math.max(-1.5,Math.min(1.5,pitch));lx=e.clientX;ly=e.clientY;});
cv.addEventListener("wheel",e=>{dist=Math.max(2.2,Math.min(8,dist+e.deltaY*0.003));e.preventDefault();},{passive:false});
cv.addEventListener("touchstart",e=>{drag=true;lx=e.touches[0].clientX;ly=e.touches[0].clientY;spinning=false;},{passive:true});
cv.addEventListener("touchmove",e=>{if(!drag)return;yaw+=(e.touches[0].clientX-lx)*0.01;
  pitch+=(e.touches[0].clientY-ly)*0.01;lx=e.touches[0].clientX;ly=e.touches[0].clientY;},{passive:true});
addEventListener("touchend",()=>drag=false);
document.getElementById("morph").onclick=()=>targetMorph=targetMorph<0.5?1:0;
const spinBtn=document.getElementById("spin");
spinBtn.textContent=spinning?"pause":"resume";
spinBtn.onclick=()=>{spinning=!spinning;spinBtn.textContent=spinning?"pause":"resume";};

gl.enable(gl.DEPTH_TEST);gl.clearColor(0,0,0,0);
function frame(){
  if(spinning)yaw+=0.0025;
  morph+=(targetMorph-morph)*0.08;
  gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
  const asp=cv.width/cv.height;
  const model=mul(rotX(pitch),rotY(yaw));
  const m=mul(persp(1.1,asp,0.1,100),mul(trans(-dist),model));
  gl.uniformMatrix4fv(uMVP,false,m);gl.uniformMatrix4fv(uModel,false,model);
  gl.uniform1f(uMorph,morph);
  gl.bindBuffer(gl.ARRAY_BUFFER,pB);gl.enableVertexAttribArray(aP);gl.vertexAttribPointer(aP,3,gl.FLOAT,false,0,0);
  gl.bindBuffer(gl.ARRAY_BUFFER,uB);gl.enableVertexAttribArray(aU);gl.vertexAttribPointer(aU,2,gl.FLOAT,false,0,0);
  gl.bindBuffer(gl.ARRAY_BUFFER,fB);gl.enableVertexAttribArray(aF);gl.vertexAttribPointer(aF,1,gl.FLOAT,false,0,0);
  for(let k=0;k<6;k++){gl.activeTexture(gl.TEXTURE0+k);gl.bindTexture(gl.TEXTURE_2D,texs[k]);}
  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,iB);
  gl.drawElements(gl.TRIANGLES,indices.length,gl.UNSIGNED_SHORT,0);
  requestAnimationFrame(frame);
}
frame();
</script>
"""
