"""A bit-exact port of java.util.Random, plus the worldgen salt seeding, so we
can reproduce Minecraft's structure placement outside the game.

Only the pieces structure placement needs: setSeed, next(bits), nextInt(bound),
and setLargeFeatureWithSalt. Verified against the running server's own
`locate structure` (see validate_placement.py)."""

_MASK48 = (1 << 48) - 1
_MUL = 0x5DEECE66D
_ADD = 0xB


def _to_int64(v):
    v &= (1 << 64) - 1
    return v - (1 << 64) if v >= (1 << 63) else v


class JavaRandom:
    __slots__ = ("s",)

    def __init__(self, seed):
        self.set_seed(seed)

    def set_seed(self, seed):
        self.s = (seed ^ _MUL) & _MASK48

    def next(self, bits):
        self.s = (self.s * _MUL + _ADD) & _MASK48
        # Java: (int)(seed >>> (48 - bits)). For bits <= 31 the result is a
        # non-negative value < 2**bits, which is all structure placement uses.
        return self.s >> (48 - bits)

    def next_float(self):
        return self.next(24) / float(1 << 24)

    def next_double(self):
        return ((self.next(26) << 27) + self.next(27)) / float(1 << 53)

    def _i32(self):
        v = self.next(32)
        return v - (1 << 32) if v >= (1 << 31) else v

    def next_long(self):
        return _to_int64((self._i32() << 32) + self._i32())

    def set_large_feature_seed(self, seed, x, z):
        """Mirror WorldgenRandom.setLargeFeatureSeed (draws two longs first)."""
        self.set_seed(seed)
        m = (1 << 64) - 1
        long_l = self.next_long()
        long_m = self.next_long()
        self.set_seed(((x * long_l) & m) ^ ((z * long_m) & m) ^ (seed & m))

    def next_int(self, bound):
        if bound <= 0:
            raise ValueError("bound must be positive")
        if (bound & (bound - 1)) == 0:            # power of two
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            val = bits % bound
            # Java's signed-overflow guard; for the small bounds used here it
            # always passes on the first try, but keep it faithful.
            if bits - val + (bound - 1) >= 0:
                return val


def large_feature_seed(world_seed, region_x, region_z, salt):
    """Mirror WorldgenRandom.setLargeFeatureWithSalt's seed derivation."""
    return _to_int64(region_x * 341873128712 + region_z * 132897987541
                     + world_seed + salt)


def large_feature_seed_no_salt(world_seed, x, z):
    """Mirror WorldgenRandom.setLargeFeatureSeed (no salt term)."""
    return _to_int64(x * 341873128712 + z * 132897987541 + world_seed)
