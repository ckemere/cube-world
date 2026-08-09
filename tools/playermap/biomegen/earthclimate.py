"""Port of the plugin's EarthClimate: real Earth (elevation/temperature/precip
+ local relief) -> vanilla's six climate parameters. Vectorised. This is the
'strong Earth constraint'; biomegen adds seed-based noise on top for the local
variation.

NOT A FAITHFUL PORT -- it is the OLD, simplified curve set that the web map's
flat/coarse preview uses, and it has drifted well behind EarthClimate.java:
TT[1] is -0.45 here against -0.38 in Java, `erosion` is the retired linear
`0.45 - rugged/500` rather than the measured quantile map, and there is no
coast-distance continentalness, wetland/swamp lift, SST, Antarctic, cherry,
river-clearing or beach-row rule at all. Do not use it to predict what the
generator will do.

For that use `biome_at.py` in this package: a line-by-line port of
EarthClimate + MapSampler + EarthData + CubeWorldBiomeProvider plus vanilla's
own Climate RTree, measured at 99.994% agreement with the server's generated
biomes over 48,742 lattice cells.
"""

import numpy as np

# temp(C) -> temperature param (knots match the plugin exactly)
TE = [-25, -8, 0, 8, 18, 24, 30, 40]
TT = [-1.0, -0.45, -0.22, -0.05, 0.18, 0.45, 0.70, 1.0]
# elevation(m) -> continentalness
CE = [-6000, -1000, -200, 0, 200, 800, 2000, 4000, 8000]
CC = [-1.0, -0.6, -0.3, -0.08, 0.0, 0.2, 0.45, 0.75, 1.0]


def humidity(precip_mm):
    return np.clip((np.log10(np.maximum(precip_mm, 1.0)) - 2.85) / 0.7, -1, 1)


def temperature(temp_c, h, land):
    base = np.interp(temp_c, TE, TT)
    hot = land & (base > 0.55) & (h >= -0.65)                 # hot & wet -> jungle row
    base = np.where(hot, 0.35, base)
    arid = land & (~hot) & (h < -0.6) & (temp_c > 15)         # warm & arid -> desert row
    base = np.where(arid, np.maximum(base, 0.72), base)
    return np.clip(base, -1, 1)


def continentalness(elev_m):
    return np.interp(elev_m, CE, CC)


def erosion(rugged_m):
    return np.clip(0.45 - rugged_m / 500.0, -1, 0.5)


def boreal_moisture_floor(h, temp_c, land):
    """Cold forests grow on modest rain (low evaporation); floor the moisture in
    the cold band so boreal reads as taiga, not steppe."""
    base = np.interp(temp_c, TE, TT)
    cold = land & (base >= -0.45) & (base < -0.05)
    return np.where(cold, np.maximum(h, 0.12), h)
