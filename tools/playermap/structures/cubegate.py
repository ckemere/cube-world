"""Cube-net geometry + the placement gates CubeWorldChunkGenerator applies, so
computed structure candidates land only where the game would actually keep them.

Constants mirror tools/playermap/server.py (FACE, H, GRID) and the Java
CubeGeometry. A candidate chunk survives iff its centre block is on a real face
and at least EDGE_BUFFER chunks from every face edge (StructuresAllowedIn)."""

import math

FACE = 10240
H = FACE / 2                     # 5120
FACE_CHUNKS = FACE // 16         # 640
EDGE_BUFFER = 8                  # STRUCTURE_EDGE_BUFFER_CHUNKS

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


def world_to_faceuv(x, z):
    """(face, u, v) for a world block, u,v in [-1,1]; None off-net."""
    f = face_at(x, z)
    if f is None:
        return None
    gc, gr = GRID[f]
    return f, (x - gc * FACE) / H, (z - gr * FACE) / H


def chunk_center(cx, cz):
    return cx * 16 + 8, cz * 16 + 8


def valid_chunk(cx, cz):
    """The generator's structure gate: centre on a real face, and >= EDGE_BUFFER
    chunks from every face edge. Returns the face name if valid, else None."""
    bx, bz = chunk_center(cx, cz)
    f = face_at(bx, bz)
    if f is None:
        return None
    gc, gr = GRID[f]
    lx = cx - ((gc * FACE - H) // 16 + 0)        # face-local chunk index (x)
    lz = cz - ((gr * FACE - H) // 16 + 0)
    edge = min(lx, FACE_CHUNKS - 1 - lx, lz, FACE_CHUNKS - 1 - lz)
    return f if edge >= EDGE_BUFFER else None


def marker(cx, cz):
    """A render marker for a valid candidate chunk: {face,u,v,p:[x,y,z]}, or
    None if the chunk fails the cube gate."""
    f = valid_chunk(cx, cz)
    if f is None:
        return None
    bx, bz = chunk_center(cx, cz)
    gc, gr = GRID[f]
    u = (bx - gc * FACE) / H
    v = (bz - gr * FACE) / H
    x, y, z = cube_point(f, u, v)
    return {"face": f, "u": u, "v": v, "p": [x, y, z], "cx": cx, "cz": cz}
