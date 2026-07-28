# How vanilla 26.2 actually generates terrain

Research notes, read out of the decompiled 26.2 sources — not from memory. Written
to be the shared mental model before we change `EarthClimate` or the density hooks
again.

**Provenance.** Everything below was read from the paperweight-userdev dev bundle,
`~/.gradle/caches/paperweight-userdev/v2/work/applyDevBundlePatches_*/output.jar`
(6,667 decompiled `.java` files, Paper 26.2 build 60). Claims are tagged:

- **[src]** — read directly in the decompiled source, file and symbol named.
- **[derived]** — arithmetic I did from constants in the source; the constants are
  [src], the conclusion is mine.
- **[measured]** — reproduced on the running dev server or offline against
  `overworld_params.json`.

Where I did *not* verify something, it says so.

---

## 1. The pipeline, and where each stage is covered

Per chunk, from `NoiseBasedChunkGenerator` **[src]**. **Sections 2–5 below are these
stages, in this order.**

| # | stage | what it does | section |
|---|---|---|---|
| 1 | `createBiomes` | picks the biome for every 4×4×4 cell | **§2** |
| 2 | `fillFromNoise` → `doFill` | density field → stone / water / lava / air, plus ore veins | **§3** |
| 3 | `buildSurface` | surface rules rewrite the top blocks of each column | **§4** |
| 4 | `applyCarvers` | cave and canyon carvers cut into finished stone | **§5** |
| 5 | features / structures | trees, ores, villages | *not covered here* |

Then §6 is a vertical reference card, §7 is the analysis this whole document exists
for (where vanilla's axis correlations come from, and what our generator breaks), and
§8 is what I'd want settled before proposing a design.

**If you came here asking "what decides a given block":**

- a **surface** block (grass, sand, the top of a beach) — stage 3, **§4**;
- a **subsurface** block (stone vs. deepslate, the dirt layer's thickness) — stage 3
  again, **§4**, which handles both via stone-depth conditions;
- whether a block is **solid at all** — stage 2, **§3.1–§3.8**;
- what fills the **open space** (air, water table, lava) — stage 2, **§3.9**;
- **ore and its host rock** (granite/tuff blobs) — stage 2, **§3.10**;
- a **cave** — two independent systems: density caves **§3.5**, carvers **§5**.

**One thing to internalise before reading on.** Biomes are decided in stage 1, *before
a single block exists*. Biome selection therefore cannot consult the generated
surface — it can only consult density *functions*. Everything about how biome and
terrain agree in vanilla has to happen inside the density graph, and that is the whole
story of §7.

---

## 2. Stage 1 — biomes (`createBiomes`)

### 2.1 `Climate.Sampler` — what produces the six numbers

`Climate.Sampler` produces the six climate values at a point. It does no selection
**[src, `Climate.java`]**:

```java
public record Sampler(DensityFunction temperature, humidity, continentalness,
                      erosion, depth, weirdness, List<ParameterPoint> spawnTarget) {
    public TargetPoint sample(int quartX, int quartY, int quartZ) {
        var ctx = new SinglePointContext(QuartPos.toBlock(quartX), ...);
        return Climate.target((float) temperature.compute(ctx), ...);   // quantized ×10000
    }
}
```

Sampler = coordinates; `ParameterList.findValue` (§2.2) = lookup.

**Resolution.** The arguments are *quart* coordinates, and one quart is **4 blocks**
in every direction (`QuartPos.SIZE = 4`; `toBlock(q) = q << 2`) **[src]**. Biomes are
stored one per quart in the chunk section — `LevelChunkSection.fillBiomesFromNoise` is
a 4×4×4 loop per 16-block section **[src]** — so every biome boundary lands on a
4-block step, vertically as well as horizontally, and `sample()` reads the *bottom*
block of the quart (`QuartPos.toBlock(quartY)`).

This is a **different, finer lattice than the density cells** of §3.8, which are
4 × 8 × 4. See the callout there.

**Two instances, same maths.** `RandomState.sampler` is built through a
`noiseFlattener` that unwraps every `HolderHolder` and `Marker` — stripping the
`flatCache` / `cache2d` / `interpolated` wrappers — so it is uncached and safe to call
outside chunk generation (spawn finding, `/locate biome`) **[src, `RandomState`]**.
During generation, `NoiseChunk.cachedClimateSampler` rebuilds the same six functions
through `this::wrap`, adding chunk-local caches **[src]**. This is the field
`SphereRouterHook` rebinds for the nether and *not* for the overworld.

**Five of the six axes ignore Y completely.** `shiftedNoise2d` constructs
`ShiftedNoise(shiftX, zero(), shiftZ, xzScale, 0.0, noise)` — `yScale = 0.0`,
`shiftY = zero()` — and `ShiftedNoise.compute` does **[src]**:

```java
double y = context.blockY() * this.yScale + this.shiftY.compute(context);   // ≡ 0
```

So temperature, humidity, continentalness, erosion and ridges sample their noise at
y = 0 whatever Y you ask for; `flatCache` on continents/erosion/ridges makes that
explicit. **The entire vertical dimension of biome selection is carried by `depth`
alone**, and `depth` is linear in Y with slope −1/128 (§3.3).

### 2.2 Selection: nearest-neighbour, not containment

`Climate.java` **[src]**:

- Every axis is quantized: `quantizeCoord(f) = (long)(f × 10000)`,
  `QUANTIZATION_FACTOR = 10000.0F`.
- A biome entry is a `ParameterPoint` — six `Parameter(min, max)` boxes plus a scalar
  `offset` (a flat penalty, used as a tie-break/rarity knob).
- `Parameter.distance(target)`:
  ```java
  above = target − max;  below = min − target;
  return above > 0 ? above : max(below, 0);
  ```
  — zero inside the box, otherwise the distance to the nearest edge.
- `ParameterPoint.fitness(target)` = **sum of the six squared axis distances, plus
  `offset²`**.
- `ParameterList.findValue` walks an **R-tree** (`RTree`, 6 children per node) that
  returns the **lowest-fitness entry** — with a brute-force equivalent
  (`findValueBruteForce`) kept in the source for testing.

**There is no "no match" case.** The closest entry always wins, however far away it
is. TODO.md §7 trap 1 is exactly right, and this is the source of most surprises.

**[measured]** I reproduced this offline against the live server. At our spawn
(11973, −856) the server reports `T=0.17 H=0.15 C=0.60 E=−0.25 D=0.01 W=0.18` and
`old_growth_birch_forest`; the Python fitness over `overworld_params.json` (7,594
entries, 55 distinct biomes) ranks:

```
     10000  old_growth_birch_forest     ← winner
    100000  sparse_jungle
    260000  forest
    350000  plains
```

and the winning entry's own box **does not contain the point** on the depth axis
(point 100, box [0,0]). Its entire fitness of 10000 *is* that 100² depth miss. That is
nearest-neighbour selection in one screenshot.

### 2.3 What the parameter list contains

`OverworldBiomeBuilder` **[src]**. Band edges:

```
temperature  −1.0 | −0.45 | −0.15 | 0.2 | 0.55 | 1.0        (5 rows)
humidity     −1.0 | −0.35 | −0.1  | 0.1 | 0.3  | 1.0        (5 columns)
erosion      −1.0 | −0.78 | −0.375 | −0.2225 | 0.05 | 0.45 | 0.55 | 1.0   (7 bands)
continentalness  mushroom −1.2..−1.05 | deep ocean −1.05..−0.455 |
                 ocean −0.455..−0.19 | coast −0.19..−0.11 |
                 near-inland −0.11..0.03 | mid-inland 0.03..0.3 |
                 far-inland 0.3..1.0
```

Weirdness is cut into **12 slices** by `addInlandBiomes` **[src]**, alternating
mid / high / peak / high / mid / low / **valley** / low / mid / high / peak / high
across W ∈ [−1, 1]. The valley slice is W ∈ [−0.05, 0.05] — which is why our
`EarthClimate.weirdness` deliberately keeps |W| away from 0.

The 5×5 biome tables (`MIDDLE_BIOMES`, `PLATEAU_BIOMES`, `SHATTERED_BIOMES`, and the
`_VARIANT` tables selected when `weirdness.max() >= 0`) are indexed
`[temperatureIndex][humidityIndex]`.

### 2.4 The depth axis: how biomes are registered, and a sweep

**[src]**, three helpers:

```java
addSurfaceBiome    → registers the SAME biome TWICE: depth = point(0.0) AND point(1.0)
addUndergroundBiome→ depth = span(0.2, 0.9)
addBottomBiome     → depth = point(1.1)          // only deep_dark
```

Underground biomes registered in 26.2 **[src]** — note the argument order is
`(temperature, humidity, continentalness, erosion, weirdness, offset, biome)`, which
is easy to misread:

| biome | constrained axis | other axes |
|---|---|---|
| `dripstone_caves` | **continentalness 0.8 … 1.0** (far inland) | full range |
| `lush_caves` | **humidity 0.7 … 1.0** | full range |
| `sulfur_caves` | weirdness −1.1 … −0.85, erosion bands 5–6, C coast…inland | T, H full |
| `deep_dark` | erosion bands 0–1 (E < −0.375), via `addBottomBiome` | T, H, C, W full |

("Humidity" is the `Climate` axis name; the noise feeding it is called `vegetation`.
Same thing, two names.)

Because a column's five other axes are fixed (§2.1) and only depth ramps, the whole
subsurface is predictable: sweep depth and see which entries win. Against the real
7,594-entry list, three columns differing only in C / H / E **[measured]**:

```
 depth      y   far-inland C=0.90   very humid H=0.80   least-eroded E=-0.60
 +0.00     64   forest              dark_forest         forest
 +0.10     51   forest              dark_forest         forest
 +0.20     38   dripstone_caves     lush_caves          forest
 +0.50     -0   dripstone_caves     lush_caves          forest
 +0.90    -52   dripstone_caves     lush_caves          forest
 +1.00    -64   forest              dark_forest         forest
 +1.05    -71   forest              dark_forest         forest
 +1.10    -77   forest              dark_forest         deep_dark
 +1.50   -128   forest              dark_forest         deep_dark
```

(y column assumes offset = −0.50375, i.e. a sea-level column; see §3.3.)

Three consequences worth keeping in mind:

1. **Cave biomes are mid-depth, not deep.** They occupy only 0.2–0.9 — and since
   1 depth unit = 128 blocks (§3.3), that is **26 to 115 blocks below the nominal
   surface**. They win there only if their *other* axis qualifies. In a middling
   column no cave biome ever wins and the surface biome runs the whole way down
   (third column above).

2. **Below depth 1.0 the surface biome returns.** Because of the `point(1.0)`
   re-registration, past the cave window the surface biome is again at distance 0
   while `dripstone` is 0.1 away. `deep_dark` only takes over past **depth ≈ 1.05** —
   the crossover between distance-to-1.0 and distance-to-1.1, visible between the
   +1.05 and +1.10 rows — and only where erosion < −0.375.

3. **How deep a column can go depends on its own offset.** `depth(bedrock) = 1.5 +
   offset` **[derived]**. A sea-level column (offset ≈ −0.5) only reaches ≈ 1.0 at
   y = −64, so it can *never* host deep dark. A mountain column (offset ≈ 0) crosses
   depth 1.0 around y = 0 and reaches 1.5 at bedrock. That is why deep dark and
   ancient cities sit under high, uneroded terrain in vanilla with no rule anywhere
   saying so — it falls out of the arithmetic.

**For us:** our `EarthClimate.depth` uses **70 blocks/unit against a table authored at
128**, which compresses the cave window from 26–115 blocks down to 14–63 blocks down
and moves the 1.0 / 1.05 crossovers relative to real terrain. That is TODO.md §7's
"cave biomes reach the surface", stated as a unit mismatch rather than a tuning
problem. And because the other five axes ignore Y *by construction*, any per-Y work
`EarthClimate` does in T/H/C/E/W is discarded — only the value at the sampled quart
survives.

Reproduce the sweep with:

```python
import json
E = json.load(open('run/plugins/CubeWorld/biomes/overworld_params.json'))
def d(v, lo, hi):
    a, b = v - hi, lo - v
    return a if a > 0 else max(b, 0)
def fit(e, pt):
    _, *b, off = e
    return sum(d(v, b[2*i], b[2*i+1])**2 for i, v in enumerate(pt)) + off*off
def win(pt): return min(E, key=lambda e: fit(e, pt))[0]
for dq in (0, 2000, 5000, 9000, 10000, 10500, 11000, 15000):
    print(dq/10000, win((0, 0, 9000, 1000, dq, 3000)))   # far-inland column
```

---

## 3. Stage 2 — density becomes blocks (`fillFromNoise` → `doFill`)

This stage does two separable things. **§3.1–§3.7 build a number** (the density field);
**§3.8 says where that number is actually evaluated**; **§3.9–§3.10 turn it into a
block**.

### 3.1 The router

`NoiseRouter` is a 15-slot record **[src]**. The overworld build is
`NoiseRouterData.overworld(...)` **[src]**:

| slot | what it is |
|---|---|
| `barrierNoise`, `fluidLevelFloodednessNoise`, `fluidLevelSpreadNoise`, `lavaNoise` | aquifer inputs (§3.9) |
| `temperature`, `vegetation` | biome axes T and H — **shifted 2-D noise, nothing else reads them** |
| `continents`, `erosion` | biome axes C and E — *and* spline inputs for terrain |
| `depth` | biome axis, *and* the vertical term of terrain density |
| `ridges` | biome axis W (weirdness) |
| `preliminarySurfaceLevel` | a cheap surface estimate, for the aquifer |
| `finalDensity` | the thing that decides solid vs. not |
| `veinToggle`, `veinRidged`, `veinGap` | ore veins (§3.10) |

### 3.2 Three splines: offset, factor, jaggedness

All three come from `TerrainProvider` **[src]** and all three are `CubicSpline`s
nested in the same order: **continentalness → erosion → ridges/weirdness**.

- **`offset`** (`overworldOffset`) — the *nominal height* of the terrain. Built as
  `constant(-0.50375) + spline(...)`. `GLOBAL_OFFSET = -0.50375F` **[src]**.
- **`factor`** (`overworldFactor`) — how *tightly* the surface is pinned to that
  height. Measured range on 26.2: **[0.625, 6.3]**.
- **`jaggedness`** (`overworldJaggedness`) — amplitude of a high-frequency ridge
  noise added on top, range **[0, 0.63]**.

`jaggedness` is multiplied by `jaggedNoise.halfNegative()`, where `jaggedNoise` is
`noise(Noises.JAGGED, 1500.0, 0.0)` — a 16-octave noise **[src]**, i.e. very spiky.
`halfNegative` halves negative values **[src]**, so jaggedness pushes *up* twice as
hard as it pulls down: it builds spires, it doesn't dig pits.

### 3.3 `depth` — and the number that ties everything together

```java
offsetToDepth(offset) = add(yClampedGradient(-64, 320, 1.5, -1.5), offset)
```
**[src]**

`yClampedGradient` is `Mth.clampedMap` **[src]**, so:

```
g(y) = 1.5 − (y + 64)/128
depth(y) = 1.5 − (y + 64)/128 + offset
```

Solving `depth = 0` **[derived]**:

```
y  =  128 + 128 × offset
   =  128 + 128 × (−0.50375 + spline)
   =  63.52 + 128 × spline
```

Two consequences worth internalising:

1. **One unit of depth is exactly 128 blocks of Y.** That is where our
   `SphereDensity.DEPTH_SLOPE = 128.0` comes from, and it is why
   `EarthClimate.depth`'s 70-blocks-per-unit is a *different scale from vanilla's* —
   see §2.4 and §7.
2. **`GLOBAL_OFFSET` is a sea-level calibration.** With the terrain spline at 0,
   `depth = 0` lands at **y ≈ 63.5** — sea level. So vanilla's `depth` axis reads
   "how far below the local nominal surface am I, in units of 128 blocks", and the
   biome table's `depth = 0` entries mean "at the surface" *by construction*.

### 3.4 sloped_cheese — the terrain proper

```java
initialDensity = 4.0 × quarterNegative( (depth + jaggedness·jaggedNoise) × factor )
sloped_cheese  = initialDensity + base_3d_noise_overworld
```
**[src]** (`registerTerrainNoises`, `noiseGradientDensity`)

- `base_3d_noise_overworld` = `BlendedNoise.createUnseeded(0.25, 0.125, 80.0, 160.0, 8.0)`
  **[src]** — the 3-D wobble that makes overhangs and cliffs.
- `quarterNegative` scales negative values by 0.25 **[src]**. Above the nominal
  surface (`depth < 0`) the gradient is a quarter as steep as below it — so vanilla
  resists carving *into* the ground much harder than it resists leaving air above it.
- **`factor` is the exchange rate between noise and blocks.** The surface sits where
  `sloped_cheese ≈ 0`. With unit 3-D noise, the surface moves by roughly
  `128 / (4 × factor)` = **32 / factor blocks**. At `factor = 6.3` that is ~5 blocks
  of wobble; at `factor = 0.625` it is ~51 blocks — cliffs, shattered terrain,
  floating overhangs.

That single relation is the whole reason `factor` exists, and it is what our
`EarthFactor` is exploiting.

### 3.5 Caves, in the density field

Before any carver runs, `finalDensity` already contains caves **[src]**:

```java
caves = rangeChoice(slopedCheese, −1e6, 1.5625,
                    min(slopedCheese, 5.0 × entrances),     // near the surface
                    underground(...))                        // deep
```

`SURFACE_DENSITY_THRESHOLD = 1.5625` **[src]** — where `sloped_cheese` is below it
(near the surface) only cave *entrances* are cut; deeper, the full underground
subtraction applies. `underground(...)` is **[src]**:

- `cheese` — `Noises.CAVE_CHEESE` at 2/3 scale, the big rooms;
- `layerizedCaverns` — `4 × layerNoise²`, horizontal cavern layers;
- `entrances` — big surface openings, min'd with spaghetti-3D;
- `spaghetti2D` / `spaghetti3D` — the long tube caves, with a *quantized rarity*
  selector (`QuantizedSpaghettiRarity`) that picks one of 4–5 noise scales by
  interval **[src]**;
- `pillars` — added back with `max`, so pillars survive the subtraction;
- `noodle` — thin worm caves, applied last via `min` on the full noise.

So "caves" in Minecraft are **two independent systems**: this density-level one, and
the carver stage (§5). They look different and are configured in completely different
places.

### 3.6 The slides

```java
slideOverworld = slide(caves, −64, 384, 80, 64, −0.078125, 0, 24, 0.1171875)
```
**[src]**, with `lerp(factor, first, second) = first + factor·(second − first)` **[src]**.

Working it through **[derived]**:

- **Top slide**: `topFactor = yClampedGradient(240, 256, 1.0, 0.0)`. Below y=240 the
  density is untouched; above y=256 it is forced to **−0.078125** (negative = air).
  Between, it lerps. This is what stops terrain reaching the build limit.
- **Bottom slide**: `bottomFactor = yClampedGradient(−64, −40, 0.0, 1.0)`. Above
  y=−40 untouched; at y=−64 forced to **+0.1171875** (positive = solid). This is what
  guarantees a floor under the world.

### 3.7 Post-processing

```java
postProcess(slide) = interpolated( blendDensity(slide) × 0.64 ).squeeze()
```
**[src]**, and `squeeze(x) = c/2 − c³/24` for `c = clamp(x, −1, 1)` **[src]** — a soft
saturation that keeps the field from having huge gradients at cell boundaries.

Then `fullNoise = min(postProcess(...), NOODLE)` **[src]**.

### 3.8 Where the density is evaluated — and the two lattices

`NoiseSettings.OVERWORLD_NOISE_SETTINGS = create(−64, 384, 1, 2)` **[src]** →
`cellWidth = 4`, `cellHeight = 8` **[src]** (`QuartPos.toBlock`).

Vanilla evaluates `finalDensity` only at **cell corners** — a lattice of
**4 × 8 × 4 blocks** (x, y, z) — and **trilinearly interpolates** every block in
between (`NoiseChunk.NoiseInterpolator`, `doFill`'s `updateForY/X/Z` with
`factorY/X/Z`) **[src]**. This is a huge deal for anyone pinning terrain to a raster:
*the density function is never asked about most blocks*. Fine detail below 4 blocks
horizontally or 8 vertically cannot survive.

> **Two different lattices — don't conflate them.** Worldgen has *two* coarse grids
> and they are not the same size:
>
> | lattice | cell size (x, y, z) | what it quantizes |
> |---|---|---|
> | **density cell** | **4 × 8 × 4** | where `finalDensity` is evaluated (this section) |
> | **quart / biome cell** | **4 × 4 × 4** | where the biome is stored (§2.1) |
>
> Both come from `QuartPos.SIZE = 4` **[src]**, but the density cell's *height* is
> `QuartPos.toBlock(noiseSizeVertical)` with `noiseSizeVertical = 2` **[src,
> `OVERWORLD_NOISE_SETTINGS = create(-64, 384, 1, 2)`]**, i.e. 8 blocks, while biomes
> are stored one per quart, i.e. 4 blocks **[src,
> `LevelChunkSection.fillBiomesFromNoise`, a 4×4×4 loop per section]**.
>
> They line up horizontally and differ vertically: **each density cell spans two
> biome layers**. So terrain shape is quantized to 8 blocks of Y, but the biome can
> change every 4.

Once the value exists, the block comes from `MaterialRuleList` **[src]**:

```java
builder.add(ctx -> aquifer.computeSubstance(ctx, fullNoiseValue.compute(ctx)));
if (settings.oreVeinsEnabled()) builder.add(OreVeinifier.create(...));
```

so: **aquifer first** (§3.9), **ore veins second** (§3.10, they overwrite stone). If
both return null the generator falls back to `settings.defaultBlock()` **[src]**.

### 3.9 Aquifers — what fills the open space

`Aquifer.NoiseBasedAquifer` **[src]**. This is the single most misunderstood part of
the pipeline, and it is what we *disabled* in `SphereRouterHook.disableAquifers`.

**Structure.** Aquifer centres sit on a jittered grid, spacing **16 × 12 × 16**
blocks (`X_SPACING`, `Y_SPACING`, `Z_SPACING`) **[src]**. Each centre has a
`FluidStatus(fluidLevel, fluidType)` — a water-table height and a fluid.

**`computeSubstance(ctx, density)`** **[src]**:
- `density > 0` → return null (caller places stone). Aquifers only fill *open* space.
- Above `skipSamplingAboveY` → use the global fluid picker (this is why open ocean is
  cheap; it never touches the grid).
- Otherwise: find the nearest 3–4 aquifer centres, and where two *disagree*, compute
  a **barrier pressure** from `barrierNoise` and add it to the density. If
  `density + barrier > 0` the block becomes stone instead — that is the rock wall
  vanilla puts between two water tables at different heights.

**Where the water table sits** — `computeSurfaceLevel` **[src]**:
- `floodednessNoise` against two thresholds derived from how far below the
  preliminary surface we are (`clampedMap(distanceBelowSurface, 0..64, 1..0)`);
- fully flooded → `globalFluid.fluidLevel` (sea level);
- partially → a randomised level;
- otherwise → `WAY_BELOW_MIN_Y`, i.e. **dry cave**.

**Lava** — `computeFluidType` **[src]**: below y = −10, a separate `lavaNoise`
sampled on 64 × 40 × 64 cells; `|noise| > 0.3` → the aquifer is lava, not water.

**A real terrain↔biome feedback lives here.** `computeSurfaceLevel` opens with:

```java
if (OverworldBiomeBuilder.isDeepDarkRegion(this.erosion, this.depth, context)) {
    partiallyFloodedness = −1.0;  fullyFloodidness = −1.0;   // always dry
}
```
**[src]**, and:
```java
isDeepDarkRegion = erosion.compute(ctx) < −0.225F && depth.compute(ctx) > 0.9F
```
**[src]**. The *aquifer* reads two of the six biome axes directly, so that deep-dark
regions come out dry. This is the clearest proof that vanilla does not treat the
biome axes as a separate "labelling" layer — they are physical inputs to block
placement.

### 3.10 Ore veins

`OreVeinifier` **[src]**. Two bands only:

| vein | ore | raw block | filler | y range |
|---|---|---|---|---|
| COPPER | copper_ore | raw_copper_block | granite | 0 … 50 |
| IRON | deepslate_iron_ore | raw_iron_block | tuff | −60 … −8 |

Gating **[src]**: `|veinToggle|` must clear `VEININESS_THRESHOLD = 0.4` (with a
20-block roundoff at the band edges); then a 70% random rejection
(`VEIN_SOLIDNESS = 0.7`); then `veinRidged < 0`; then richness
`clampedMap(|toggle|, 0.4..0.6, 0.1..0.3)` decides ore vs. filler, and `veinGap > −0.3`.
2% of ore blocks are the raw block. The sign of `veinToggle` alone picks copper vs.
iron — they are the same noise.

Note the *filler*: granite and tuff blobs underground are not decoration, they are
ore veins that failed the richness roll.

---

## 4. Stage 3 — surface rules (`buildSurface`)

This stage decides **both the surface skin and the shallow subsurface** — grass vs.
sand on top, and how thick the dirt under it is — so it answers most "why is this
block that block" questions.

`SurfaceSystem.buildSurface` **[src]** walks each column **downward** from
`WORLD_SURFACE_WG + 1` and tracks:

- `stoneAboveDepth` — blocks of stone since the last air/fluid **above** (reset to 0
  on air);
- `stoneBelowDepth` — distance to the next non-stone **below** (a lookahead);
- `waterHeight` — the surface of any fluid column above.

Those feed `SurfaceRules.Context`, and the rule conditions are **[src]**:

| condition | meaning |
|---|---|
| `ON_FLOOR` | `stoneDepthCheck(0, false, FLOOR)` — the top stone block |
| `UNDER_FLOOR` | same, `addSurfaceDepth = true` — within the noisy surface depth |
| `DEEP_UNDER_FLOOR` | + 6 blocks |
| `VERY_DEEP_UNDER_FLOOR` | + 30 blocks (this is why deserts have deep sandstone) |
| `ON_CEILING` / `UNDER_CEILING` | same from below — cave roofs |

The "surface depth" is itself noise **[src]**:

```java
getSurfaceDepth(x, z) = surfaceNoise(x, 0, z) × 2.75 + 3.0 + rand × 0.25
```

so the dirt layer is ~3 blocks thick, varying with noise. That is the entire reason
grass/dirt boundaries look organic rather than like a contour map.

**The `abovePreliminarySurface` gate** **[src]**: the main biome-dependent rule block
is wrapped in `ifTrue(abovePreliminarySurface(), mainRuleCloseToSurface)`. Below the
preliminary surface estimate, the biome-flavoured surface rules **do not run at all** —
which is why cave walls are plain stone, not grass. Two rules sit *outside* that gate
**[src]**: sulfur cave banding, and the deepslate gradient.

**Deepslate and bedrock** **[src]**:

```java
verticalGradient("deepslate", absolute(0), absolute(8))   → DEEPSLATE
verticalGradient("bedrock_floor", bottom(), aboveBottom(5)) → BEDROCK
```

`verticalGradient` is a *probabilistic* ramp, which is why the stone→deepslate
transition around y 0–8 is speckled rather than a flat plane, and why bedrock is a
rough 5-block layer rather than one slab.

---

## 5. Stage 4 — carvers (`applyCarvers`)

The second, entirely separate cave system (the first is §3.5).

`applyCarvers` **[src]** runs *after* `buildSurface`, over a **17 × 17 chunk**
neighbourhood (`dx, dz ∈ [−8, 8]`), so a cave started 8 chunks away can reach in.

Critically, the carvers **share the chunk's aquifer**:

```java
BlockState state = aquifer.computeSubstance(new SinglePointContext(x, y, z), 0.0);
```
**[src]** — passing density `0.0`, i.e. "this block is now open, what fluid belongs
here?". Below `configuration.lavaLevel` the carver places lava directly **[src]**.
This is how carved caves come out flooded where the water table is high and dry where
it isn't, consistently with the density caves.

Carvers are selected **per biome** (`BiomeGenerationSettings.getCarvers()`), using
`biomeSource.getNoiseBiome(..., randomState.sampler())` **[src]**.

---

## 6. Reference: the vertical structure

```
 y = 320   ── depth axis floor (−1.5)
 y = 256   ── top slide fully applied: forced air above          (§3.6)
 y = 240   ── top slide begins                                   (§3.6)
 y ≈ 190   ── practical peak ceiling (offset spline max + jaggedness)
 y ≈ 63.5  ── depth = 0 when the terrain spline is 0             (§3.3)
 y = 63    ── sea level proper (surface rules' aboveOverworldSeaLevel)
 y = 50    ── copper ore-vein band top                           (§3.10)
 y = 8     ── deepslate fully replaces stone                     (§4)
 y = 0     ── deepslate gradient begins                          (§4)
 y = −8    ── iron ore-vein band top                             (§3.10)
 y = −40   ── bottom slide begins                                (§3.6)
 y = −60   ── iron ore-vein band bottom                          (§3.10)
 y = −64   ── bottom slide fully applied: forced solid; bedrock over 5 blocks
```

All values **[src]** except the y≈63.5 line, which is **[derived]** in §3.3, and the
y≈190 line, which is an estimate.

---

## 7. Where vanilla's correlations come from

This is the part that matters for us. Vanilla's six axes are **not** six independent
knobs, and the biome table was authored assuming they aren't.

**(a) Terrain is a pure function of three of the six biome axes.**
`offset`, `factor` and `jaggedness` are `CubicSpline`s over
`continents → erosion → ridges/weirdness` **[src]** — nothing else. So (C, E, W)
*determines* the height, the steepness and the spikiness of the ground. Choose a
biome's C/E/W box and you have implicitly chosen its landform. There is no way to get
"peak biome on flat ground" because the same erosion value that selects the peak
biome also drives the offset spline's `veryLowErosionMountains` branch.

Concretely **[src]**: `addPeaks` registers peak biomes only at `erosions[0]` and
`erosions[1]` (E < −0.375), and `buildErosionOffsetSpline` puts its mountain splines
at erosion −0.85 / −0.7 / −0.4. Same band. Likewise swamp lives at E ≥ 0.55 and the
offset spline's `swamps` branch starts at erosion 0.7.

**(b) `depth` is derived from `offset`.** `depth = yGradient + offset` **[src]**, so
the depth axis is measured from the terrain's own nominal height (§3.3). "Depth 0"
means "at the surface" automatically, wherever the surface is. The biome table's
`point(0.0)` entries are correct at every altitude for free.

**(c) T and H are genuinely independent — and they touch nothing.**
`temperature` and `vegetation` are plain `shiftedNoise2d` **[src]** and appear in *no*
terrain spline. Vanilla's only truly free axes are the two that cannot move a block.
That is a design choice, not an accident: it is why a snowy desert plateau is
coherent, and why there is no such thing as a "temperature that builds mountains".

**(d) Shared domain warp.** `continents`, `erosion`, `ridges`, `temperature` and
`vegetation` are all `shiftedNoise2d(shiftX, shiftZ, 0.25, ...)` **[src]**, where
`shiftX/shiftZ` come from **one** `Noises.SHIFT` (`DEFAULT_SHIFT`, firstOctave −3)
**[src]**. Their *values* are independent, but their spatial distortion is identical,
so features across all five axes bend together instead of cutting across each other.

**(e) `ridges` is a deterministic fold of weirdness.**
`peaksAndValleys(w) = −(|‖w| − 0.6667| − 0.3333) × 3` **[src]**. W and R are one axis
in two forms; the splines use both.

**(f) The amplitudes are chosen, per axis.** `NoiseData.registerBiomeNoises` **[src]**:
continentalness gets 9 octaves weighted `1,1,2,2,2,1,1,1,1` (broad continents with
detail), erosion 5 octaves `1,1,0,1,1`, temperature 6 octaves `1.5,0,1,0,0,0` (very
large-scale — climate bands, not patches), vegetation `1,1,0,0,0,0`. The *shape* of
each axis's distribution is tuned, not uniform.

**(g) And feedback in the other direction.** The aquifer reads `erosion` and `depth`
directly (§3.9). The preliminary surface level is built from `offset` and `factor`
**[src]**.

### What our generator does to all of this

`SphereDensity` **[src, ours]** replaces, by value-range fingerprinting:

- `depth` → `EarthDepth` (surface from the raster, slope 1/128 — matches vanilla);
- `factor` → `EarthFactor` (from real relief + a freeboard term);
- `jaggedness` → `EarthJagged` (relief²);
- `preliminarySurfaceLevel` → `EarthSurfaceLevel` (substituted outright via the record).

Since vanilla's `offset` is consumed *only* by `depth` and `preliminarySurfaceLevel`
**[src]**, replacing both does correctly bypass it. That part is sound.

What is left broken, and is worth being explicit about:

1. **The biome layer and the terrain layer compute their axes separately.**
   `CubeWorldBiomeProvider`/`EarthClimate` produce C, E, W from coast distance, local
   relief and a sine field. `SphereDensity` produces the *terrain* from relief and
   raster elevation. Nothing forces the two to agree the way a shared spline does in
   vanilla. Correlation (a) is gone, and it was doing most of the work.

2. **`EarthClimate.depth` uses 70 blocks/unit, vanilla's table assumes 128.**
   Every depth-keyed entry in the parameter list — cave biomes at 0.2–0.9, deep dark
   at 1.1, surface biomes at 1.0 — is authored in vanilla's units. §2.4.

3. **The aquifer's `isDeepDarkRegion` reads `router.erosion()`, which is still
   vanilla's erosion noise** (we replace `factor`/`jaggedness`/`depth`, not the
   `erosion` slot), while `router.depth()` *is* `EarthDepth`. So that test mixes two
   unrelated coordinate systems. Moot today because we disable aquifers on the Earth
   path — but it becomes live the moment we want underground water. **[src, ours +
   derived]** — I have not measured its effect.

4. **`CustomWorldChunkManager` still calls `noise.sample(x, y, z)` on every biome
   query** to build the `BiomeParameterPoint` handed to our Bukkit provider **[src]**,
   using the overworld `RandomState.sampler` — which `SphereRouterHook` rebinds only
   for the *nether*. Our provider ignores that value, so it is correctness-neutral but
   not free. **[src]**

   (Verified in the same pass: when a Bukkit `BiomeProvider` is present, `ServerLevel`
   installs `CustomWorldChunkManager` as the generator's `BiomeSource` **[src]** — so
   the carver biome lookup and `createBiomes` *do* both route through
   `CubeWorldBiomeProvider`. Good; that is one thing we don't have to worry about.)

5. **The 4 × 8 × 4 density lattice caps our resolution** (§3.8). Any attempt to pin
   terrain to the raster more tightly than 4 blocks horizontally or 8 vertically is
   fighting the interpolator.

---

## 8. Two things I'd want settled before proposing a design

Not a proposal — flagging the questions the next step turns on.

- **Do we keep using vanilla's biome table at all?** Its entries encode vanilla's
  couplings (§7a). Feeding it synthetic, uncorrelated axes will always be an
  impedance mismatch, and every fix so far has been a corrective term for that. The
  alternative — our own parameter list authored against *our* axes — is more work but
  removes the whole class of problem.

- **Should terrain and biome share one derivation?** Vanilla's real trick is not the
  spline shapes; it is that there is exactly one source of truth (C, E, W) and both
  height and biome are read off it. We currently have two derivations from the same
  rasters. Making one the input to the other is closer to vanilla's structure than
  matching any particular curve.

---

## Appendix: reproducing anything here

```bash
# decompiled sources
JAR=~/.gradle/caches/paperweight-userdev/v2/work/applyDevBundlePatches_*/output.jar
unzip -o "$JAR" 'net/minecraft/world/level/levelgen/*' 'net/minecraft/world/level/biome/*' \
                'net/minecraft/data/worldgen/*'

# offline biome choice (params ×10000: T, H, C, E, depth, W)
python3 - <<'PY'
import json
E = json.load(open('run/plugins/CubeWorld/biomes/overworld_params.json'))
def d(v, lo, hi):
    a = v - hi; b = lo - v
    return a if a > 0 else max(b, 0)
def fit(e, pt):
    _, *b, off = e
    return sum(d(v, b[2*i], b[2*i+1])**2 for i, v in enumerate(pt)) + off*off
print(sorted((fit(e, (1700,1500,6000,-2500,100,1800)), e[0]) for e in E)[:5])
PY
```

Setup gotcha found while rebuilding the rasters on a fresh box:
`dev-server-setup.md` §9 listed only `netCDF4` and `tifffile`, but the WorldClim
GeoTIFFs are **LZW-compressed**, so
`tifffile` also needs **`imagecodecs`** or the export dies with
`<COMPRESSION.LZW: 5> requires the 'imagecodecs' package`.
