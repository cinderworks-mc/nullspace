#!/usr/bin/env python3
"""Mechanical audit of our atlas-textured JSON models (items and the Waypoint Hall blocks), the check behind the model review:
for every model: elements, faces, boxes that are not whole units, stretched faces (uv size in texels != face size in units), faces
whose uv region touches a transparent texel, painted texels no face samples, colours, and the atlas rows used.
Exit status 1 if anything fails.   python3 assets/scripts/model_audit.py [name ...]   (default: every *_model.png model)"""
import json, os, sys
from PIL import Image

R = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = R + "/src/main/resources/assets/nullspace/"


def face_size(d, f, t):
    dx, dy, dz = (t[i] - f[i] for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}[d]


def audit(kind, name):
    m = json.load(open(RES + "models/%s/%s.json" % (kind, name)))
    S = m["texture_size"][0]
    img = Image.open(RES + "textures/%s/%s_model.png" % (kind, name)).convert("RGBA")
    assert img.size == (S, S), "atlas size %s != texture_size %s" % (img.size, S)
    px = img.load()
    sampled = set(); faces = stretched = transparent = nonwhole = 0; overlap = 0
    for el in m["elements"]:
        box = el["from"] + el["to"]
        if any(abs(v - round(v)) > 1e-6 for v in box): nonwhole += 1
        for d, f in el["faces"].items():
            faces += 1
            u0, v0, u1, v1 = [c * S / 16 for c in f["uv"]]
            w, h = face_size(d, el["from"], el["to"])
            if abs(abs(u1 - u0) - round(w)) > 1e-6 or abs(abs(v1 - v0) - round(h)) > 1e-6: stretched += 1
            for y in range(int(round(min(v0, v1))), int(round(max(v0, v1)))):
                for x in range(int(round(min(u0, u1))), int(round(max(u0, u1)))):
                    if px[x, y][3] < 255: transparent += 1
                    if (x, y) in sampled: overlap += 1
                    sampled.add((x, y))
    painted = {(x, y) for y in range(S) for x in range(S) if px[x, y][3] > 0}
    unused = len(painted - sampled)
    rows = max((y for _, y in sampled), default=-1) + 1
    colours = len({px[x, y] for x, y in sampled})
    ok = nonwhole == 0 and stretched == 0 and transparent == 0 and unused == 0 and overlap == 0
    print("%-5s %-22s elements=%-3d faces=%-3d nonwhole=%d stretched=%d transparent=%d painted-unsampled=%d shared-texels=%d sampled=%-4d colours=%-2d rows=%d/%d %s" % (
        kind, name, len(m["elements"]), faces, nonwhole, stretched, transparent, unused, overlap, len(sampled), colours, rows, S, "OK" if ok else "FAIL"))
    return ok


if __name__ == "__main__":
    names = sys.argv[1:]
    todo = []
    for kind in ("item", "block"):
        for f in sorted(os.listdir(RES + "textures/" + kind)):
            if f.endswith("_model.png"):
                n = f[:-10]
                if not names or n in names: todo.append((kind, n))
    sys.exit(0 if all([audit(k, n) for k, n in todo]) else 1)
