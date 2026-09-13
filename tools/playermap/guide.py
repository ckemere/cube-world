"""The player guide page (/guide): what makes CubeWorld different, for the
curious. Explains the systems; teases the secrets — mechanics players are
meant to DISCOVER (what a misprinted ticket does, who sells strange
instruments) get rumors, not spoilers. Self-contained, dark like the map,
phone-friendly."""

PAGE = """<!doctype html><html lang=en><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>CubeWorld — a guide</title>
<style>
:root{--bg:#0b0f16;--card:#101722;--ink:#dfe5ec;--muted:#8b95a3;--edge:rgba(120,150,190,.18);
 --aqua:#5aa3d8;--gold:#ffb22c;--red:#ff6b61;--green:#37d67a;--purple:#b48ce0}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);
 font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:760px;margin:0 auto;padding:32px 20px 80px}
h1{font-size:1.9rem;margin:.2em 0 0;letter-spacing:-.01em}
.tag{color:var(--muted);margin:0 0 26px}
h2{font-size:1.2rem;margin:2.2em 0 .4em;color:var(--gold)}
p{margin:.5em 0;max-width:66ch}
.card{background:var(--card);border:1px solid var(--edge);border-radius:12px;
 padding:16px 20px;margin:12px 0}
.k{color:var(--aqua);font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.92em}
.hint{color:var(--muted);font-style:italic}
.hearts b{color:var(--red)}
a{color:var(--aqua)}
.top{display:flex;gap:10px;align-items:center;margin-bottom:6px}
.top a{font:600 13px system-ui;background:var(--card);border:1px solid var(--edge);
 border-radius:9px;padding:7px 12px;text-decoration:none;color:var(--ink)}
table{border-collapse:collapse;font-size:.92em;margin:.4em 0}
td,th{padding:4px 12px 4px 0;text-align:left;border-bottom:1px solid var(--edge);color:var(--ink)}
th{color:var(--muted);font-weight:600}
</style>
<main>
<div class=top><a href="/">← the living map</a></div>
<h1>CubeWorld</h1>
<p class=tag>A Minecraft server shaped like the actual planet.</p>

<p>The world is Earth — real elevation, real coastlines, real climate — at
about one kilometre per block. Walk west across the Pacific date line and you
arrive in the far east. Cross a pole and come down the other side. There is no
world border and no endless procedural frontier: one finite, wrap-around
planet, and everything below works on a completely stock Minecraft client.</p>

<h2>Thirty historical cities</h2>
<div class=card>
<p>From Rome and Baghdad to Teotihuacan and Kilwa, villages stand where great
cities actually stood — sized by their importance in history. Spawn is a quiet
bay on the Turquoise Coast; Ephesus lies a short walk west, Antioch east.</p>
<p>Try <span class=k>/locate structure cubeworld:rome</span> — every city
answers to its name.</p>
</div>

<h2>The teleport network</h2>
<div class=card>
<p>Every city hosts a <b>teleport station</b> — a dark lodestone on an amethyst
pad. Right-click one to travel: each station offers two ring routes, so the
whole network is a few hops from anywhere. Travel costs <b>lapis</b>, priced by
distance (capped at a stack). Stations sell paper <b>tickets</b> naming a
four-word code — write those words in any book and you hold a ticket too.
Nether stations exist for those bold enough to build them: nether hops cost at
most 12 lapis, and crossing between dimensions is a flat 12 — the deep roads
are the cheap roads.</p>
<p>You can raise your own station: earn a <b>Teleporter Core</b> and place it
on a 3×3 amethyst pad. <span class=hint>Misprinted tickets are rumored to take
travelers somewhere — the same somewhere, every time.</span></p>
</div>

<h2>Hearts, and where it is safe to lose them</h2>
<div class="card hearts">
<p>Near any teleport station lies a <b>sanctuary</b> — a green clover in your
HUD says you're inside one, a gray clover says you're in <b>the wilds</b>.
Die in sanctuary and death is ordinary. Die in the wilds and you lose a heart
— it <b>spills where you fell</b> as a glowing star anyone may take. Eat a
heart to grow stronger, up to twenty; you can fall no lower than three.</p>
<p>Every spilled heart is engraved: a <b>Traveler's</b> heart was taken by
another player, an <b>Explorer's</b> by the world itself, a <b>Wanderer's</b>
by the creatures of the night — each carrying the words of its death. Hearts
can also be <b>forged</b> (a Totem of Undying sunk in redstone makes a
fragment; four fragments, diamond, and netherite make a heart) — but a forged
heart bears no epitaph, so a true trophy cannot be faked.</p>
<p>The <b>Cardiograph</b> — a spyglass crafted around a Heart of the Sea —
reads any traveler's heart from afar: raise it, and see what they are made of.</p>
</div>

<h2>Riches follow real geology</h2>
<div class=card>
<p>Ores are enriched where Earth actually holds them: diamonds under southern
Africa, lapis in the mountains of Afghanistan, gold along real gold belts.
Knowing geography is knowing where to dig. <span class=hint>Certain traders
are said to carry strange instruments that tingle near a mother lode.</span></p>
</div>

<h2>Great journeys are rewarded</h2>
<div class=card>
<p>The server adds its own advancements: stand at both poles, summit the Seven
Summits — Everest to Kosciuszko, at their true heights in real places — or
circumnavigate the planet on foot and sail (teleporters don't count). Each
expedition completed earns a <b>Teleporter Core</b>: reach a far place the
hard way once, and you've earned the means to open a shortcut.</p>
</div>

<h2>The living map</h2>
<div class=card>
<p><a href="/">The map you came from</a> shows the planet live — spinning cube
or flat faces, biomes, structures, cities, and online players. Your privacy is
yours: <span class=k>/cubeworld mapprecision low</span> blurs your position to
±256 blocks so nobody finds your base, and sneaking, invisibility, or wearing
any mob head hides you from the map entirely.</p>
</div>

<h2>Commands</h2>
<div class=card>
<table>
<tr><th>Command</th><th>What it does</th></tr>
<tr><td><span class=k>/cubeworld hearts</span></td>
    <td>Your heart count, its making, and the nearest sanctuary.</td></tr>
<tr><td><span class=k>/cubeworld mapprecision high|medium|low</span></td>
    <td>How precisely this map shows you — low blurs you to ±256 blocks.</td></tr>
<tr><td><span class=k>/cubeworld findlatlon &lt;lat&gt; &lt;lon&gt;</span></td>
    <td>Where a real-world latitude/longitude lies in the world.</td></tr>
<tr><td><span class=k>/cubeworld oreprobe</span></td>
    <td>Faint readings of mineral wealth where you stand — sample several
        spots to triangulate a province.</td></tr>
<tr><td><span class=k>/cubeworld face</span></td>
    <td>Which face of the cube you are on.</td></tr>
<tr><td><span class=k>/locate structure cubeworld:&lt;city&gt;</span></td>
    <td>Point toward any of the thirty cities by name.</td></tr>
</table>
</div>

<p class=hint style="margin-top:2.5em">Everything else — what the traders
carry, what lies at the bottom of the sea, what a wrong ticket truly does —
is yours to find out.</p>
</main></html>
"""
