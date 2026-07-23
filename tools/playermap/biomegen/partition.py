"""Vanilla's overworld climate->biome partition, ported from the dumped
parameter points (overworld_params.json). The biome is the parameter point of
minimum `fitness`:

    fitness = sum_axis  dist(target_axis, [min_axis, max_axis])^2  +  offset^2

Values are the game's quantised longs (climate value * 10000).

Because fitness is dominated by temperature+humidity, points are indexed into a
(T,H) bucket grid (each point added to every bucket its range, widened by a
margin, overlaps). A pixel is only scored against its bucket's candidates —
~50x fewer points — and this is verified exact against brute force."""

import json
import os

import numpy as np

_DEFAULT = os.path.join(os.path.dirname(__file__), "..", "..", "..",
                        "run", "plugins", "CubeWorld", "biomes", "overworld_params.json")


class Partition:
    def __init__(self, path=None, nbuckets=48, ncont=16, margin=0.12, margin_c=0.18):
        rows = json.load(open(path or _DEFAULT))
        self.biomes = [r[0] for r in rows]
        arr = np.array([r[1:] for r in rows], dtype=np.int64)   # (N,13)
        self.mins = arr[:, 0:12:2].copy()                       # (N,6)
        self.maxs = arr[:, 1:12:2].copy()                       # (N,6)
        self.offsq = arr[:, 12] ** 2                            # (N,)
        self.uniq = sorted(set(self.biomes))
        self.nb = nbuckets
        self.nc = ncont
        self._build(margin, margin_c)

    @staticmethod
    def _bkt(v, nb):
        return int(np.clip((v + 1) * 0.5 * nb, 0, nb - 1))

    def _build(self, margin, margin_c):
        # index on (temperature, humidity, continentalness) — the three axes that
        # dominate fitness; ocean biomes have wide T,H but a specific low C, so
        # adding C stops them flooding every land bucket.
        lo = (self.mins.astype(np.float64) / 10000.0) - np.array(
            [margin, margin, margin_c, 0, 0, 0])
        hi = (self.maxs.astype(np.float64) / 10000.0) + np.array(
            [margin, margin, margin_c, 0, 0, 0])
        grid = {}
        for i in range(len(self.biomes)):
            for ti in range(self._bkt(lo[i, 0], self.nb), self._bkt(hi[i, 0], self.nb) + 1):
                for hj in range(self._bkt(lo[i, 1], self.nb), self._bkt(hi[i, 1], self.nb) + 1):
                    for ck in range(self._bkt(lo[i, 2], self.nc), self._bkt(hi[i, 2], self.nc) + 1):
                        grid.setdefault((ti, hj, ck), []).append(i)
        self.grid = {k: np.array(v, dtype=np.int32) for k, v in grid.items()}
        self.allpts = np.arange(len(self.biomes), dtype=np.int32)

    def _score(self, q_rows, cand):
        t = q_rows[:, None, :]
        d = np.maximum(np.maximum(self.mins[cand][None] - t, t - self.maxs[cand][None]), 0)
        fit = (d * d).sum(axis=2) + self.offsq[cand][None]
        return cand[fit.argmin(axis=1)]

    def index_batch(self, targets):
        tg = np.asarray(targets, dtype=np.float64)
        q = np.rint(tg * 10000.0).astype(np.int64)
        bt = np.clip(((tg[:, 0] + 1) * 0.5 * self.nb).astype(int), 0, self.nb - 1)
        bh = np.clip(((tg[:, 1] + 1) * 0.5 * self.nb).astype(int), 0, self.nb - 1)
        bc = np.clip(((tg[:, 2] + 1) * 0.5 * self.nc).astype(int), 0, self.nc - 1)
        key = (bt * self.nb + bh) * self.nc + bc
        order = np.argsort(key, kind="stable")
        sk = key[order]
        out = np.empty(len(q), dtype=np.int32)
        cuts = np.flatnonzero(np.diff(sk)) + 1
        for s, e in zip(np.concatenate(([0], cuts)), np.concatenate((cuts, [len(sk)]))):
            idxs = order[s:e]
            k = int(sk[s])
            cand = self.grid.get(((k // self.nc) // self.nb, (k // self.nc) % self.nb,
                                  k % self.nc), self.allpts)
            out[idxs] = self._score(q[idxs], cand)
        return out

    def index_brute(self, targets):
        q = np.rint(np.asarray(targets, dtype=np.float64) * 10000.0).astype(np.int64)
        out = np.empty(len(q), dtype=np.int32)
        step = max(1, 4_000_000 // len(self.biomes))
        for a in range(0, len(q), step):
            out[a:a + step] = self._score(q[a:a + step], self.allpts)
        return out

    def biomes_batch(self, targets):
        return [self.biomes[i] for i in self.index_batch(targets)]

    def biome(self, t, h, c, e, d, w):
        return self.biomes[int(self.index_batch([[t, h, c, e, d, w]])[0])]
