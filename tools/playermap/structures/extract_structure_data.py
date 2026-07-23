"""Build placement_data.json + structure_biomes.json from the vanilla server jar:
per structure_set, the RandomSpread placement (spacing/separation/salt/spread and
any frequency reduction), and the union of its member structures' valid biomes
(resolving the has_structure/* biome tags recursively).

Run: cd tools/playermap && python3 -m structures.extract_structure_data [--jar PATH]
Static output, checked in; re-run only when the server jar version changes."""

import glob
import json
import os
import sys
import zipfile

DEF_JAR = os.path.expanduser(
    "~/.gradle/caches/paperweight-userdev/v2/work/extractFromBundler_*/vanillaServer.jar")
OUT_DIR = os.path.dirname(__file__)


def _read(z, path):
    return json.loads(z.read(path))


def resolve_biome_tag(z, ref, seen=None):
    """A '#minecraft:has_structure/x' tag (or bare biome id) -> set of biome ids."""
    seen = seen or set()
    if not ref.startswith("#"):
        return {ref if ":" in ref else "minecraft:" + ref}
    if ref in seen:
        return set()
    seen.add(ref)
    ns, path = ref[1:].split(":", 1)
    try:
        tag = _read(z, f"data/{ns}/tags/worldgen/biome/{path}.json")
    except KeyError:
        return set()
    out = set()
    for e in tag.get("values", []):
        eid = e["id"] if isinstance(e, dict) else e
        out |= resolve_biome_tag(z, eid, seen)
    return out


def structure_biomes(z, structure_id):
    ns, name = structure_id.split(":", 1)
    try:
        s = _read(z, f"data/{ns}/worldgen/structure/{name}.json")
    except KeyError:
        return set()
    b = s.get("biomes")
    if b is None:
        return set()
    refs = b if isinstance(b, list) else [b]
    out = set()
    for r in refs:
        out |= resolve_biome_tag(z, r if isinstance(r, str) else r.get("id", ""))
    return out


def main(jar=None):
    jar = jar or (sorted(glob.glob(DEF_JAR))[-1] if glob.glob(DEF_JAR) else None)
    if not jar or not os.path.exists(jar):
        print("vanilla jar not found; pass --jar PATH", file=sys.stderr)
        sys.exit(1)
    placement, biomes = {}, {}
    with zipfile.ZipFile(jar) as z:
        sets = [n for n in z.namelist()
                if n.startswith("data/minecraft/worldgen/structure_set/") and n.endswith(".json")]
        for path in sets:
            name = os.path.basename(path)[:-5]
            data = _read(z, path)
            pl = data["placement"]
            ptype = pl["type"].split(":")[-1]
            if ptype != "random_spread":
                continue                                  # strongholds: concentric_rings (hook-placed)
            entry = {
                "spacing": pl["spacing"], "separation": pl["separation"],
                "salt": pl["salt"], "spread": pl.get("spread_type", "linear"),
            }
            fr = pl.get("frequency_reduction_method")
            if fr:
                entry["frequency"] = pl.get("frequency", 1.0)
                entry["frequency_reduction_method"] = fr
            if "exclusion_zone" in pl:
                ez = pl["exclusion_zone"]
                entry["exclusion"] = {"other": ez["other_set"].split(":")[-1],
                                      "chunks": ez["chunk_count"]}
            placement[name] = entry
            union = set()
            for m in data.get("structures", []):
                union |= structure_biomes(z, m["structure"])
            biomes[name] = sorted(union)
    with open(os.path.join(OUT_DIR, "placement_data.json"), "w") as f:
        json.dump(placement, f, indent=1, sort_keys=True)
    with open(os.path.join(OUT_DIR, "structure_biomes.json"), "w") as f:
        json.dump(biomes, f, indent=1, sort_keys=True)
    print(f"wrote {len(placement)} structure sets from {os.path.basename(jar)}")
    for n in sorted(placement):
        extra = ""
        if "frequency" in placement[n]:
            extra += f" freq={placement[n]['frequency']}"
        if "exclusion" in placement[n]:
            extra += f" excl={placement[n]['exclusion']['other']}/{placement[n]['exclusion']['chunks']}"
        print(f"  {n:20s} {placement[n]['spacing']:>3}/{placement[n]['separation']:<2} "
              f"salt={placement[n]['salt']:<10} {placement[n]['spread']:<10} "
              f"biomes={len(biomes[n]):<3}{extra}")


if __name__ == "__main__":
    arg = None
    if "--jar" in sys.argv:
        arg = sys.argv[sys.argv.index("--jar") + 1]
    main(arg)
