# Measuring the generator

We kept changing biome/terrain code and arguing about whether it got better. This
is the scoreboard that ended that. Everything below is reproducible from the
commands named.

## The commands

| command | what it does |
|---|---|
| `/cubeworld evaluate [full\|fast] [side] [stride]` | the scorecard: 14 tiles, biome + terrain metrics, one headline number |
| `/cubeworld axisstats vanilla\|earth [side] [stride]` | distribution of the six climate axes, ours vs vanilla's, same format |
| `/cubeworld routerprobe [world]` | walks the NoiseRouter, reports every node a visitor reaches and resolves noise leaves to registry keys |
| `/cubeworld drownprobe <x> <z> [side] [stride] [rows]` | per-column terms for columns that end up underwater |
| `/cubeworld surfacedump <x> <z> <side> <stride>` | `x z emulatedSurfaceY targetY`, for validating the emulator against real chunks |
| `/cubeworld biomehist <x> <z> [side] [stride]` | recomputed surface-biome histogram (unlike `biomeat`, never stale) |

A/B the generator itself with `./run-server.sh -Dcubeworld.legacyClimate=true`,
which restores the pre-calibration erosion table, wetland rule and temperature
knot exactly.

A full scorecard is ~1.2 s, so it is cheap enough to run after every change.

## What the scorecard measures

14 tiles at real places (`TerrainEval.TILES`), each a small grid of columns:
Sahara, Sahel, Amazon, Congo, Great Plains, Siberia, Canada boreal, Mesopotamia,
Black Forest, Indonesia, Everest, the known dripstone-at-surface site, Marianas,
mid-Atlantic.

- **biome%** — fraction of columns whose biome is in the expected set for that place.
- **rmseF** — generated surface vs the height the density path aims at, over
  FLAT ground only (relief < 150 m). Rugged columns are reported separately
  because deviation there is intended character, not error.
- **drown%** / **bad%** — columns the raster calls land that generate underwater;
  `bad%` restricts to elevation >= 60 m, where it cannot be excused as
  coastline-resolution ambiguity.
- **tgtdr%** — of those, how many were already doomed by the height curve rather
  than by the 3D noise. Separates "the curve is wrong" from "the noise is loose".
- **land%** — ocean columns that generate as land.
- **cave%** — surface columns assigned a cave biome (dripstone/lush/sulfur/deep dark).
- **erosion-band L1** — distance between our erosion distribution and vanilla's.

- **shore wall** — % of shoreline columns needing a >2 block climb, i.e. an
  unjumpable wall between sea and land, plus the lowland slope behind it.

`TOTAL = (100-biome) + shoreWall*0.1 + drown*2 + spurland*2 + cave*5 + rmse +
eDist*50`, lower is better. The weights are printed with the score so nothing
hides in them. Shoreline was added late, after a change that halved the wall
scored as a regression because the axis was not measured at all.

### The harness is validated, and it has a noise floor

The surface is located by evaluating the live folded `finalDensity` and
**emulating vanilla's 4x8x4 cell lattice** -- bilinear across the cell's XZ
corners, linear between cell-Y planes. Chunk generation never asks the density
function about most blocks, so a naive pointwise scan is not the same quantity.

Checked against real generated chunks read out of the region files (Sahara, 151
columns): **emulated minus real is mean +0.24, median +0.38, sd 1.90 blocks; 89%
agree within 1 block, 96% within 2.**

So: terrain RMSE has a ~2-block noise floor, drown% has a few-percent outlier
tail, and repeated runs of the whole scorecard vary by ~0.3 TOTAL. Differences
smaller than that are not real. **Biome metrics do not go through this path** and
carry no such caveat.

The first version of the emulator was pointwise and reported the Sahara surface
at y61 where the world actually has y65 -- a 4-block bias that manufactured a
2.5% drowning rate out of nothing. Validating the harness against ground truth
was the single most useful thing done to it.

## Results

**The honest number, measured like-for-like.** Everything below uses the *final*
metric; the only difference between the two rows is the generator, switched with
`./run-server.sh -Dcubeworld.legacyClimate=true`.

| generator | TOTAL | biome% | erosion L1 | shore wall | drown% |
|---|---|---|---|---|---|
| start of session | **64.4** | 87.0 | 0.630 | 73% | 2.4 |
| + climate fixes | 22.6 | 98.7 | 0.029 | 73% | 2.4 |
| + coastline fix (current default) | **22.1** | 98.7 | 0.029 | **42%** | 3.8 |

(TOTAL now includes the shoreline term, so these differ from the numbers quoted
while it was unscored; all three rows use the same final metric.)

Per-tile biome accuracy, same A/B:

| tile | before | after |
|---|---|---|
| Amazon | 5.6 | **89.6** |
| Siberia | 62.5 | **100.0** |
| dripstone site | 77.1 | **99.3** |
| Great Plains | 71.5 | **97.9** |
| Everest (massif) | 93.1 | **97.9** |
| Black Forest | 100.0 | 97.2 |
| everything else | 100.0 | 100.0 |

The A/B switch exists precisely because the raw score also moved for reasons that
had nothing to do with the generator. The sequence as it actually happened:

| step | TOTAL | what changed |
|---|---|---|
| first scorecard | 62.9 | -- |
| erosion quantile map + wetland coast rule | 39.9 | **generator** |
| erosion distribution sampled globally | 25.7 | *metric fix* |
| temperature knot at -8 C | 23.1 | **generator** |
| cell-lattice emulation | 21.4 | *metric fix* |
| per-tile scale; expectations corrected | 19.7 | *metric fix* |
| flat/rugged RMSE split | 15.4 | *metric fix* |

Four of the six steps were corrections to the measurement. Quoting 62.9 -> 15.4
as if it were all generator work would be wrong; **57.7 -> 15.4 is the claim that
survives**, because both were measured with the same ruler.

### Why the metric kept being wrong

Each metric fix was a case of measuring the wrong quantity:

- **erosion distribution** was computed over the 14 tiles (~85% land) and compared
  against a whole-world vanilla reference. Pure sampling bias: it read 0.312 when
  the true global figure was 0.029.
- **surface location** was sampled pointwise, but chunk generation only evaluates
  density at 4x8x4 cell corners and interpolates. Cross-checked against real
  chunks, the pointwise scan put the Sahara surface at y61 where the world has
  y65 -- a 4-block bias that invented a 2.5% drowning rate.
- **tile scale** was uniform, so the Everest tile spanned ~288 km of Tibet,
  Himalaya and the Nepal Terai and scored 64.6% for containing meadow and jungle,
  which are correct there. The massif itself measured 91.7% frozen_peaks.
- **RMSE** was scored over all columns including mountains, where `FACTOR_RUGGED`
  deliberately grants ~25 blocks of noise freedom. That rewarded flattening
  mountains; only flat-ground RMSE is scored now.

## The three generator changes, and why

### 1. Erosion was carrying almost no information

Measured: our erosion put **83.8%** of the world in one band, and vanilla's
mountain/peak/deep-dark bands (E < -0.2225) held **1.0%** against vanilla's
**29.7%**. That one fact explained three symptoms we had been treating
separately -- no peak biomes, deep dark unreachable anywhere, and
`windswept_savanna` over-firing.

The cause was a units bug. `erosion()` gated relief credit by
`clamp(blocksAboveSea / MOUNTAIN_FULL_BLOCKS, 0, 1)` with `MOUNTAIN_FULL_BLOCKS
= 45` **blocks**, but after the vertical compression in `EarthMapSpec` 45 blocks
is **2500 m** of real elevation. A 1000 m plateau sits 8.6 blocks above sea and
so kept 81% of the flat-lowland value. It also pinned the entire ocean, because
`blocksAboveSea` is negative at sea.

The gate is gone. Relief now maps to erosion through a **quantile map**: our
measured global relief distribution transported onto vanilla's measured erosion
distribution, so our erosion has vanilla's spread by construction while keeping
Earth's arrangement. Result: erosion band L1 distance 0.587 -> **0.029**.

Ocean erosion is safe to vary because vanilla registers every ocean biome with
erosion `FULL_RANGE` (`OverworldBiomeBuilder.addOffCoastBiomes`), so it cannot
change which ocean biome is picked -- it only reaches the underground bands,
which is what makes trench/ridge deep dark possible at all.

### 2. The Amazon was a mangrove swamp

Measured: 12.5% correct over the whole tile. The basin is flat, low (14-64 m) and
very wet (2300 mm), so the flat/low/wet product saturated and pushed erosion into
vanilla's swamp band. The old comment claimed the elevation term guarded against
this; it did not, because the lower Amazon really is that low.

Elevation cannot separate them -- the Amazon mouth and a real delta are the same
height. **Distance to the sea** can, and the split is physical: tropical wetlands
on Earth are overwhelmingly coastal (deltas, mangrove belts), while flat wet
tropical *interior* is rainforest. Boreal peatlands are the opposite -- inland,
because what waterlogs them is frozen ground rather than the sea -- so the coast
requirement is applied only above 10 C.

### 3. Siberia was ice spikes

`-8 C` mapped to exactly `-0.45`, which is vanilla's edge between the frozen row
(snowy_plains/ice_spikes) and the cold row (taiga). Siberian taiga has a mean
annual temperature of about -8 C, so it landed a hair inside the frozen row.
Worse, the boreal humidity correction is guarded by `baseTemp >= -0.45`, so the
one rule meant to make cold forests taiga was switched off exactly where taiga
belongs. Moving the knot to `-0.38` puts the frozen boundary near -10 C, which is
where real tundra starts.

## Gotcha: `biomeat` is stale, `evaluate` is live

`/cubeworld biomeat` calls `world.getBiome`, which reads the **stored** biome out
of an already-generated chunk. After a generator change it reports the old
answer. `/cubeworld evaluate` calls `CubeWorldBiomeProvider.surfaceBiome`, which
**recomputes**. To see a change in-world you need chunks that have never been
generated, or a regen.

Confirmed on fresh coordinates after change 2: Amazon reads `jungle` /
`bamboo_jungle` where the pre-existing chunk still reported `mangrove_swamp`.

## State of the world, measured

### Subsea surface

Faithful to bathymetry but locally SMOOTH.

| | Marianas | mid-Atlantic ridge |
|---|---|---|
| seabed y range over the tile | 15 .. 44 (29 blocks) | -3 .. 35 (39 blocks) |
| follows bathymetry, rms | 3.42 | 3.33 |
| step between samples 20 blocks apart | mean 7.5, max 14.9 | mean 1.6, max 4.5 |

So the large-scale structure is real -- the trench and the ridge are both there
with tens of blocks of relief -- but there are **no undersea cliffs, escarpments
or seamount spires**. `SphereDensity.landGate` zeroes `reliefNorm` for any column
below sea level, which pins `factor` at `FACTOR_FLAT` (9.0, ~3.6 blocks of
wobble) and `jaggedness` at exactly 0. Trenches are broad smooth ramps.

That gate is the blunt fix for a real failure (a shoal beside a deep drop read as
maximally rugged and punched rock spires through the sea surface). The
freeboard-gated version -- amplitude proportional to how much WATER is overhead,
mirroring what `FREEBOARD_TIGHTNESS` already does for air -- would give deep
water its character back without reintroducing the spires.

Note the erosion change *does* now vary underwater, so the biome axes have
undersea structure even though the terrain does not. That is what makes deep dark
under a trench possible at all.

### Caves, deep dark, sulfur

Vertical profile, measured with `/cubeworld biomecolumn`:

```
Everest        frozen_peaks -> dripstone y190..138 -> frozen_peaks y138..126 -> deep_dark y126 down
ordinary land  birch        -> dripstone y87..47   -> birch y47..27          -> deep_dark y27 down
```

Global census (`/cubeworld undergroundcensus`, void excluded):

| depth below surface | finding |
|---|---|
| 30 blocks | `dripstone_caves` 13.4%, `lush_caves` 5.2% (ocean columns keep their ocean biome) |
| 70 blocks | no cave biomes -- this is the depth~1.0 band where the SURFACE biome returns |
| 110 blocks | `deep_dark` 23.2% of columns still above bedrock |

- **Deep dark now exists.** Before the erosion fix it was unreachable anywhere,
  because erosion never went below -0.375. This is the most visible payoff of the
  quantile map.
- The band at ~70 blocks where the surface biome comes back is not a bug -- it is
  vanilla's `addSurfaceBiome` registering every biome at depth 1.0 as well as 0.0
  (see `vanilla-worldgen.md` section 2.4).
- **`sulfur_caves` -- FIXED.** They need weirdness <= -0.85 and ours reached only
  -0.67 at p01, so the biome was unreachable everywhere. Same remedy as erosion:
  transport our measured |weirdness| onto vanilla's, quantile for quantile,
  stretching only the tail so the deliberate deadband near 0 survives (W must
  never sit at 0, where vanilla picks the valley/river variants that fight our
  river layer). Weirdness p01 -0.67 -> **-0.79** against vanilla's -0.84, and
  sulfur caves now measure **1.07%** of columns 40 blocks down, alongside
  dripstone 13.6% and lush 4.8%.
- Cave biomes at the SURFACE remain 0.00% across the whole tile suite.

### Coastlines: every coast is a ~3-block wall  (attempted fix FAILED -- read this before retrying)

Now measured every scorecard run:

```
shoreline: step 3.8 blk, 73% unjumpable (>2)   lowland slope 0.39 blk per 6 blk
```

**Two fixes were tried and both reverted.** The measurements are here so the next
attempt does not repeat them.

**Attempt 1 -- drop `LAND_FREEBOARD_MIN` 3 -> 1, raise `FACTOR_PINNED` 64 -> 256
and `FREEBOARD_TIGHTNESS` 128 -> 256.** Drowning went from 2.4% to **31.9%**, and
this was confirmed in real generated chunks (21.9% real vs 21.2% emulated on a
fresh tile), so it was the generator, not the harness.

The cause is a coupling that is easy to miss and is the most useful thing found
in this round. `factor` does not only set surface tightness -- it also decides
where vanilla switches from near-surface to full-cave treatment, because that
switch is a threshold on `sloped_cheese` (`SURFACE_DENSITY_THRESHOLD = 1.5625`)
and `sloped_cheese ~ 4 * depth * factor`. The near-surface zone is therefore
`70 * 1.5625 / (4 * factor)` blocks deep:

| factor | near-surface zone |
|---|---|
| 9 | 3.04 blocks |
| 64 | 0.43 |
| 98 | 0.28 |
| 256 | 0.11 |

Past roughly factor 60 that zone is thinner than one block, so the full cave
subtraction applies immediately under the surface and carves voids straight
through it. The measured drops were 3 to 44 blocks -- far too large for surface
noise, unmistakably caves.

So: **you cannot pin the shoreline by raising `factor`.** Lowering the freeboard
floor needs a tighter surface; a tighter surface destroys the surface. The
cave-regime threshold has to be decoupled from `factor` first -- for instance by
substituting the `finalDensity` slot with one whose cave branch is chosen on a
factor-independent depth.

(A side effect of this investigation: `probeTerms` was printing only the RELIEF
term as "factor", not the freeboard-adjusted value actually used -- 8.9 where the
real figure was ~98. It now prints both, plus the near-surface zone depth.)

**Attempt 1b -- decouple the cave-regime switch from `factor`**
(`-Dcubeworld.caveDepthSwitch=true`). Vanilla picks near-surface vs full-cave
treatment with `rangeChoice(slopedCheese, -1e6, 1.5625, ...)`, so the switch
depth scales as `1/factor`. This replaces the input with a plain depth-in-blocks
proxy, giving a fixed 6-block near-surface zone. Score-neutral at default
settings (15.5 vs 15.4), so it is safe -- but it did NOT fix the drowning, which
means the coupling is real but was not the operative one.

**The operative one, found next.** Vanilla's near-surface branch is
`min(slopedCheese, 5 * entrances)` -- the two terms are meant to be COMPARABLE.
When `factor` is 40-250 (against vanilla's own ceiling of 6.3) `slopedCheese`
becomes enormous, the `min` always picks the entrances term, and cave entrances
carve air straight through the ground. Measured: 8-22 block drops at 800-975 m
Saharan columns.

**So `factor` is the one knob that cannot be used here** -- it tightens the
surface and breaks the caves with the same motion. The right lever is to
attenuate the 3D noise directly (`-Dcubeworld.noiseAttenuation=true`), which
shrinks the wobble while leaving `slopedCheese` in the range vanilla's caves
expect.

**And a piece of arithmetic that wasted a lot of time.** A freeboard of 1 can
NEVER work. The target height is where the density zero-crossing sits, so a
target of y=63 puts the topmost SOLID block at y=62 -- exactly the water surface.
Freeboard 1 is drowned by definition, which is why every low-lying tile read
93-100% drowned (Sahel 97.9%, Amazon 100%, Mesopotamia 93.1%) no matter what else
was changed. **The minimum that can ever work is 2.**

### The coastline trade-off, measured

With freeboard 2 and noise attenuation, the shoreline roughly halves its wall at
a cost in drowning:

| config | TOTAL | drown% | shore step | unjumpable | lowland slope |
|---|---|---|---|---|---|
| default (freeboard 3) | **15.4** | 2.4 | 3.8 | **73%** | 0.39 |
| freeboard 2, no attenuation | 35.9 | 12.5 | 2.8 | 61% | 0.43 |
| freeboard 2 + attenuation | 19.1 | 4.4 | 2.5 | **41%** | 0.29 |
| ... attenMin 0.05, full 32 | 18.5 | 4.0 | 2.5 | 40% | 0.25 |
| ... attenMin 0.03, full 48 | 18.0 | 3.8 | 2.7 | 42% | 0.23 |

Attenuation is clearly doing its job -- at freeboard 2 it cuts drowning from
12.5% to 3.8-4.4%. The residual ~3.8% does not respond to further attenuation, so
it is discretisation rather than noise.

**ADOPTED as the default** (noise attenuation on, freeboard 2, attenMin 0.03,
attenFull 48). Under the metric with shoreline scored it is a net win, 22.6 ->
22.1: the wall term drops 3.1 points and the drowning term costs 2.8.

Set `-Dcubeworld.noiseAttenuation=false -Dcubeworld.landFreeboard=3` to get the
old behaviour back.

**Attempt 2 -- steepen the low end of the elevation curve** so real elevation
rather than the floor sets the height. Knots
`{0,60,150,400,800,1500,2500,4000} -> {0,1.2,3.2,8,15,26,50,114}`. This did
improve the lowland: slope 0.39 -> **0.55** (+41%), shore step 3.8 -> 3.3. But
the unjumpable fraction barely moved, 73% -> **71%**, because the step is set by
the FLOOR and not by the curve. It cost rmse(flat) 4.3 -> 5.0 and TOTAL 15.4 ->
15.8, and it changes every land height in the world -- the exact change TODO item
7 trap 4 records as having deleted Antioch's village. Reverted as not worth it on
its own; worth revisiting once the floor can actually come down.

### The original finding

Measured on a real shoreline (west Africa), land columns having a water neighbour:

```
height above sea level: mean 2.7, median 2.6, min 2.1, max 3.6
fraction needing a >2 block climb (unjumpable): 100%
```

And inland is worse than it looks: 119 land columns across that tile have **sd
0.94 blocks** -- essentially a flat table.

The cause is `LAND_FREEBOARD_MIN = 3` interacting with the elevation curve. From
`EarthMapSpec`'s own constants, the curve gives 100 m only 0.5 blocks and 300 m
only 1.8, so **every land column below about 450 m is pinned to exactly y=65**:

| real elevation | curve | final y |
|---|---|---|
| 0 m | 0.00 blk | 65.00 (floored) |
| 100 m | 0.50 | 65.00 (floored) |
| 300 m | 1.80 | 65.00 (floored) |
| 430 m | 2.89 | 65.00 (floored) |
| 500 m | 3.48 | 65.48 |
| 1000 m | 8.57 | 70.57 |

So there are no gradual shores anywhere, and the entire 0-450 m band -- most of
the inhabited world -- is one flat plateau with a wall at its edge.

The freeboard floor exists for a documented reason: without it the curve gives a
floodplain like Mesopotamia ~0.3 blocks of clearance and the residual noise dunks
it. But that is a *noise* problem, and it now has a *noise* fix
(`FREEBOARD_TIGHTNESS`), so the floor is doing work it no longer needs to do. It
is the same species of defect as the other two found here: a constant expressed
in blocks that encodes a physical intent, and that stops meaning what it meant
once the vertical scale changed.

## The leaf hook (Option 1), first cut

Implemented behind `-Dcubeworld.leafHook=true`; **default off**.

Instead of fingerprinting vanilla's `factor`/`jaggedness` splines by value range
and replacing them, it replaces the three *leaves* they are built from --
`continentalness`, `erosion`, `ridge` -- matched by REGISTRY KEY, and lets
vanilla's own splines compute the terrain shape from our axes. The factor spline
is wrapped rather than replaced, so the freeboard floor still pins the waterline.

**Structurally verified.** `/cubeworld routerprobe` on the live folded router:
51 `EarthAxis` leaves, 3 `FreeboardGuard`-wrapped factor splines, vanilla's
jaggedness splines retained, `EarthDepth` untouched.

**What holds:** biome accuracy is unchanged at 98.6%, and Everest still measures
91.7% `frozen_peaks`. So feeding vanilla's splines our axes costs nothing on the
biome side.

**What does NOT hold.** The scorecard reported TOTAL 12.4 against the baseline's
15.4, driven by spurious land falling 1.9% -> 0.1% (Indonesia 26.4% -> 1.4%).
That was checked against real chunks and **it is a harness artifact**: the same
terrain regenerated under both configurations has **0 spurious land either way**.

The reason is that the emulator degrades badly under the leaf hook -- validated
on a fresh on-net tile, agreement within 1 block falls from **89% to 31%** (sd
1.90 -> 6.88), even though the median error stays near half a block. So the
terrain-side metrics (rmse, drown, spurland) cannot currently be trusted to
compare leaf-hook runs against baseline runs. Biome metrics do not go through
that path and remain valid.

**Why it probably degrades, and why that matters beyond the harness.** Vanilla's
splines assume SMOOTH inputs -- they are nested cubics over three Perlin fields.
Our axes are not smooth: `EarthClimate.weirdness` contains a `Math.signum`, i.e.
an outright sign discontinuity, and continentalness/erosion are piecewise-linear
interpolations with hard clamps. Feeding a discontinuous field into a cubic
spline produces a discontinuous `factor`, which is exactly the sort of thing that
would make cell-corner interpolation disagree with a pointwise emulator -- and
would also put real seams in the terrain.

So the next step for the leaf hook is not more hooking; it is **making the axes
smooth enough to be spline inputs**, starting with weirdness.

## Still open

- **Everest 64.6%** -- best tile improvement so far but the weakest land tile.
- **Canada boreal 90.3%**, down from 100 -- small regression from the erosion change.
- **rmse 6.5** is dominated by Everest's 19.5, which is largely *intended*
  jaggedness (factor ~1.3 gives ~25 blocks of noise freedom), so the metric is
  partly measuring a feature.
- The leaf replacement itself (`docs/vanilla-worldgen.md` section 7, Option 1) is
  **not started**. All three changes above are `EarthClimate`/`SphereDensity`
  edits; the structural rewrite that would let vanilla's own splines consume our
  axes is still ahead. The calibration work here is a prerequisite for it, not a
  substitute.
