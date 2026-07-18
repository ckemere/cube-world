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

RCON_HOST = os.environ.get("RCON_HOST", "127.0.0.1")
RCON_PORT = int(os.environ.get("RCON_PORT", "25575"))
RCON_PW = os.environ.get("RCON_PW", "cubeworld-dev")

FACE = int(os.environ.get("FACE_SIZE", "10240"))
H = FACE / 2
GRID = {"NORTH_POLE": (0, 0), "EQ_PRIME": (0, 1), "EQ_EAST": (1, 0),
        "EQ_BACK": (0, -1), "EQ_WEST": (-1, 0), "SOUTH_POLE": (0, 2)}


def face_at(x, z):
    col = math.floor((x + H) / FACE)
    row = math.floor((z + H) / FACE)
    for f, (c, r) in GRID.items():
        if c == col and r == row:
            return f
    return None


def cube_point(f, u, v):
    return {"NORTH_POLE": (u, 1.0, v), "EQ_PRIME": (u, -v, 1.0),
            "SOUTH_POLE": (u, -1.0, -v), "EQ_BACK": (u, v, -1.0),
            "EQ_EAST": (1.0, -u, v), "EQ_WEST": (-1.0, u, v)}[f]


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
  function loop(){
    // reuse the globe's own matrices + view state (shared script scope)
    var model=mul(rotX(pitch),rotY(yaw));
    var asp=cv.width/cv.height;
    var mvp=mul(persp(1.1,asp,0.1,100),mul(trans(-dist),model));
    var seen={};
    for(var k=0;k<players.length;k++){
      var pl=players[k], p=pl.p, n=Math.hypot(p[0],p[1],p[2]);
      var sph=[p[0]/n,p[1]/n,p[2]/n];
      var pos=[p[0]+(sph[0]-p[0])*morph,p[1]+(sph[1]-p[1])*morph,p[2]+(sph[2]-p[2])*morph];
      var out=1.04, pp=[pos[0]*out,pos[1]*out,pos[2]*out];
      var c=mvv(mvp,[pp[0],pp[1],pp[2],1]);
      var vz=model[2]*sph[0]+model[6]*sph[1]+model[10]*sph[2]; // near-side test
      var el=els[pl.name];
      if(!el){el=document.createElement('div');el.className='pmk';
        el.innerHTML='<div class="dot"></div><div class="lbl"></div>';
        cont.appendChild(el);els[pl.name]=el;
        el.querySelector('.lbl').textContent=pl.name;}
      seen[pl.name]=1;
      if(c[3]>0 && vz>-0.12){
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


def load_page():
    globe = os.path.join(os.path.dirname(__file__), "..", "cubemap", "out", "globe.html")
    with open(globe, encoding="utf-8") as f:
        return f.read() + MARKER_OVERLAY


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, body, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/players"):
            self._send(json.dumps(get_players()).encode(), "application/json")
        else:
            self._send(load_page().encode("utf-8"), "text/html; charset=utf-8")


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("0.0.0.0", port), Handler) as srv:
        print(f"player map serving on http://0.0.0.0:{port}  (RCON {RCON_HOST}:{RCON_PORT})")
        srv.serve_forever()
