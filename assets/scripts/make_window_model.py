#!/usr/bin/env python3
"""Generates the connected window frame: blockstates/shell_window.json, models/block/shell_window.json (a standalone window, used by the item) and
models/block/shell_window/w_<TBLR>_<posts>.json, one model per reachable shape of a window (47 of them).

A window looks along z in the models; the blockstate rotates them for axis x (y 90) and axis y (x 90) (see dev.nullspace.core.WindowPlane for the
side names and the rotation table). The shape of a window is: which of its four sides (top, bottom, left, right) are *connected* to another window
looking the same way (no frame bar there), and which inside corners need a post. Every shape is rasterised on the 16x16 grid of texels of the
block's plane: a cell is part of the frame when it is within `width` texels of an open side (or in the post at a corner), its distance from that side is
its layer, and every layer has its own depth range. Cells are merged into as few boxes as possible. Faces on the block boundary carry cullfaces (so
they vanish against shell blocks, and WindowBlock.skipRendering hides them between two windows).

Texture: textures/block/shell_window.png is a plain 16x16 tile; the model samples single texels of its ring:
copperL (4,0) copperM (15,0) copperD (4,15) steelL (4,1) steelM (14,1) steelD (4,14).

Usage: python3 assets/scripts/make_window_model.py [variant]   (variants below; the shipped one is DEFAULT)
"""
import itertools, json, os, re, shutil, sys

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
RES = os.path.join(root, "src/main/resources/assets/nullspace")

# width: texels of frame per side; zr: depth range per layer (texel units 0..16); mull: depth range of a 1 texel mullion on connected top/right edges
# Compared in a real client (assets/previews/windows/window_variants.png): A connected 4 px deep, B A plus a 1 texel mullion on every block edge (a cage again),
# C a frame on every block (a cage), D a 1 texel full-depth liner (black tunnel), E A with a stepped bevel, F connected, 2 texel wide, 2 texel deep,
# G F with a 1 texel copper liner the full depth of the block (reads as a casing from the side) and the steel ring at mid depth (shipped).
VARIANTS = {
    "A": dict(connected=True, width=2, zr=[(6, 10), (6, 10)], mull=None),
    "B": dict(connected=True, width=2, zr=[(6, 10), (6, 10)], mull=(7, 9)),
    "C": dict(connected=False, width=2, zr=[(6, 10), (7, 9)], mull=None),
    "D": dict(connected=True, width=1, zr=[(0, 16)], mull=None),
    "E": dict(connected=True, width=2, zr=[(6, 10), (7, 9)], mull=None),
    "F": dict(connected=True, width=2, zr=[(7, 9), (7, 9)], mull=None),
    "G": dict(connected=True, width=2, zr=[(0, 16), (7, 9)], mull=None),
}
DEFAULT = "G"
GLOW = 8  # block light given to the frame faces

TEXEL = {"copperL": (4, 0), "copperM": (15, 0), "copperD": (4, 15), "steelL": (4, 1), "steelM": (14, 1), "steelD": (4, 14)}
SIDES = ["top", "bottom", "left", "right"]
CORNERS = ["tl", "tr", "bl", "br"]
CORNER_SIDES = {"tl": ("top", "left"), "tr": ("top", "right"), "bl": ("bottom", "left"), "br": ("bottom", "right")}
CORNER_CELL = {"tl": (0, 0), "tr": (15, 0), "bl": (0, 15), "br": (15, 15)}
LIGHT, DARK = {"top", "left"}, {"bottom", "right"}


def raster(v, connected, posts):
    """connected: set of connected sides; posts: set of corner names. Returns {(x, y): (layer, kind, zrange)} with y = row from the top."""
    W = v["width"]
    cells = {}
    for y in range(16):
        for x in range(16):
            best, sides = 99, set()
            for s, d in (("top", y), ("bottom", 15 - y), ("left", x), ("right", 15 - x)):
                if s in connected:
                    continue
                if d < best:
                    best, sides = d, {s}
                elif d == best:
                    sides.add(s)
            if best < W:
                cells[(x, y)] = (best, shade(best, sides), v["zr"][best])
    for c in posts:
        cx, cy = CORNER_CELL[c]
        sx, sy = (1 if cx == 0 else -1), (1 if cy == 0 else -1)
        for dx in range(W):
            for dy in range(W):
                p = (cx + sx * dx, cy + sy * dy)
                layer = max(dx, dy)
                if p not in cells or cells[p][0] > layer:
                    cells[p] = (layer, "M", v["zr"][layer])
    if v["mull"]:
        for s in ("top", "right"):
            if s not in connected:
                continue
            for i in range(16):
                p = (i, 0) if s == "top" else (15, i)
                if p not in cells:
                    cells[p] = (-1, "M", v["mull"])
    out = {}
    for p, (layer, sh, z) in cells.items():
        base = "steel" if layer != 0 else "copper"
        out[p] = (layer, base + sh, z)
    return out


def shade(layer, sides):
    if sides <= LIGHT:
        return "L"
    if sides <= DARK:
        return "D"
    return "M"


def merge(cells):
    """Greedy rectangles of equal (kind, zrange): list of (x0, y0, x1, y1, kind, zrange) in cells (x1, y1 exclusive)."""
    used, rects = set(), []
    key = lambda p: (cells[p][1], cells[p][2])
    for y in range(16):
        for x in range(16):
            p = (x, y)
            if p not in cells or p in used:
                continue
            k = key(p)
            x1 = x + 1
            while (x1, y) in cells and (x1, y) not in used and key((x1, y)) == k:
                x1 += 1
            y1 = y + 1
            while all((xx, y1) in cells and (xx, y1) not in used and key((xx, y1)) == k for xx in range(x, x1)):
                y1 += 1
            for yy in range(y, y1):
                for xx in range(x, x1):
                    used.add((xx, yy))
            rects.append((x, y, x1, y1, k[0], k[1]))
    return rects


def uv(kind):
    x, y = TEXEL[kind]
    return [x, y, x + 1, y + 1]


def elements(v, connected, posts):
    out = []
    for (x0, y0, x1, y1, kind, (z0, z1)) in merge(raster(v, connected, posts)):
        top, bottom = 16 - y0, 16 - y1
        # the frame sits in a cell that lets no light through, so its faces would sample a light level of 0: lift them
        lit = {"neoforge_data": {"block_light": GLOW}}
        side = {"uv": uv("copperM" if kind.startswith("copper") else "steelM"), "texture": "#frame", **lit}
        front = {"uv": uv(kind), "texture": "#frame", **lit}
        faces = {
            "north": dict(front, **({"cullface": "north"} if z0 == 0 else {})),
            "south": dict(front, **({"cullface": "south"} if z1 == 16 else {})),
            "west": dict(side, **({"cullface": "west"} if x0 == 0 else {})),
            "east": dict(side, **({"cullface": "east"} if x1 == 16 else {})),
            "up": dict(side, **({"cullface": "up"} if y0 == 0 else {})),
            "down": dict(side, **({"cullface": "down"} if y1 == 16 else {})),
        }
        out.append({"from": [x0, bottom, z0], "to": [x1, top, z1], "faces": faces})
    return out


def model(els):
    return {"render_type": "minecraft:cutout", "ambientocclusion": False, "textures": {"frame": "nullspace:block/shell_window", "particle": "nullspace:block/shell_window"}, "elements": els}


def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    text = json.dumps(obj, indent=2)
    text = re.sub(r"\[\s+(-?\d+(?:,\s+-?\d+)*)\s+\]", lambda m: "[" + re.sub(r",\s+", ", ", m.group(1)) + "]", text)
    with open(path, "w") as fh:
        fh.write(text + "\n")


def name(connected, posts):
    return "w_%s_%s" % ("".join("1" if s in connected else "0" for s in SIDES), "".join("1" if c in posts else "0" for c in CORNERS))


def main(variant):
    v = VARIANTS[variant]
    mdir = os.path.join(RES, "models/block/shell_window")
    shutil.rmtree(mdir, ignore_errors=True)
    dump(os.path.join(RES, "models/block/shell_window.json"), {"parent": "minecraft:block/block", **model(elements(v, set(), set()))})
    made = set()
    variants = {}
    rot = {"z": {}, "x": {"y": 90}, "y": {"x": 90}}
    for axis in "xyz":
        for bits in itertools.product([False, True], repeat=4):
            conn = {s for s, b in zip(SIDES, bits) if b}
            for pbits in itertools.product([False, True], repeat=4):
                posts = {c for c, b in zip(CORNERS, pbits) if b}
                real = {c for c in posts if all(s in conn for s in CORNER_SIDES[c])} if v["connected"] else set()
                rconn = conn if v["connected"] else set()
                n = name(rconn, real)
                if n not in made:
                    made.add(n)
                    dump(os.path.join(mdir, n + ".json"), {"parent": "minecraft:block/block", **model(elements(v, rconn, real))})
                key = ",".join(["axis=" + axis] + ["%s=%s" % (s, str(s in conn).lower()) for s in SIDES] + ["post_%s=%s" % (c, str(c in posts).lower()) for c in CORNERS])
                variants[key] = dict(model="nullspace:block/shell_window/" + n, **rot[axis])
    dump(os.path.join(RES, "blockstates/shell_window.json"), {"variants": variants})
    print("variant", variant, "wrote", len(made), "models,", len(variants), "blockstate variants")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT)
