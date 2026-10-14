#!/usr/bin/env python3
"""Offline preview of our JSON block/item models (own pixels only): an orthographic, z-buffered, nearest-neighbour render of the
elements with vanilla's per-face shading, so a model can be judged (and audited by eye) before it goes into the game.
The real client is still the judge; this only makes iteration quick.

  python3 assets/scripts/model_preview.py out.png block/destination_pad [block/lift_pad_down ...] [--yaw 225 --pitch 30 --scale 14]

A model argument is  <block|item>/<name>[@rotY][+dx,dy,dz]  (rotY in degrees about the cell centre, offset in blocks)."""
import json, math, os, sys
import numpy as np
from PIL import Image

R = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = R + "/src/main/resources/assets/nullspace/"
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}


def face_quad(d, f, t):
    x0, y0, z0 = f; x1, y1, z1 = t
    return {
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
    }[d]


def rot3(p, axis, ang, origin):
    a = math.radians(ang); c, s = math.cos(a), math.sin(a)
    x, y, z = (p[i] - origin[i] for i in range(3))
    if axis == "x": y, z = y * c - z * s, y * s + z * c
    elif axis == "y": x, z = x * c + z * s, -x * s + z * c
    else: x, y = x * c - y * s, x * s + y * c
    return (x + origin[0], y + origin[1], z + origin[2])


def load(spec):
    head, _, o = spec.partition("+")
    off = tuple(float(v) for v in o.split(",")) if o else (0, 0, 0)
    kind_name, _, r = head.partition("@")
    roty = float(r) if r else 0
    kind, name = kind_name.split("/")
    m = json.load(open(RES + "models/%s/%s.json" % (kind, name)))
    tex = None
    for k, v in m["textures"].items():
        if k == "0": tex = Image.open(RES + "textures/" + v.split(":")[1] + ".png").convert("RGBA")
    return m, np.array(tex), roty, off


def render(specs, yaw=225, pitch=30, scale=14, bg=(58, 60, 70, 255), size=None, ground=True):
    models = [load(s) for s in specs]
    W, H = size or (int(scale * 16 * (len(models) ** 0.5 + 1.2) * 1.5), int(scale * 16 * (len(models) ** 0.5 + 1.2) * 1.3))
    img = np.zeros((H, W, 4), np.uint8); img[:] = bg
    zb = np.full((H, W), 1e9)
    cy, cp = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cx, sx = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def proj(p):
        x, y, z = (p[0] - 8) / 16, (p[1] - 8) / 16, (p[2] - 8) / 16
        x, z = x * cy + z * cp, -x * cp + z * cy
        y, z = y * cx - z * sx, y * sx + z * cx
        return (W / 2 + x * scale * 16, H / 2 - y * scale * 16 + scale * 4, -z)

    for m, tex, roty, off in models:
        th, tw = tex.shape[:2]
        ts = m.get("texture_size", [16, 16])
        for el in m["elements"]:
            for d, face in el["faces"].items():
                q = face_quad(d, el["from"], el["to"])
                if "rotation" in el:
                    r = el["rotation"]; q = [rot3(p, r["axis"], r["angle"], r["origin"]) for p in q]
                q = [rot3(p, "y", roty, (8, 8, 8)) for p in q]
                q = [(p[0] + off[0] * 16, p[1] + off[1] * 16, p[2] + off[2] * 16) for p in q]
                uv = face["uv"]; u0, v0, u1, v1 = [c * tw / 16 if i % 2 == 0 else c * th / 16 for i, c in enumerate(uv)]
                uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
                P = [proj(p) for p in q]
                # back-face cull
                ax, ay = P[1][0] - P[0][0], P[1][1] - P[0][1]; bx, by = P[3][0] - P[0][0], P[3][1] - P[0][1]
                if ax * by - ay * bx < 0: continue
                shade = 1.0 if (el.get("shade") is False) else SHADE[d]
                for tri in ((0, 1, 2), (0, 2, 3)):
                    (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = [P[i] for i in tri]
                    minx, maxx = int(max(0, math.floor(min(x0, x1, x2)))), int(min(W - 1, math.ceil(max(x0, x1, x2))))
                    miny, maxy = int(max(0, math.floor(min(y0, y1, y2)))), int(min(H - 1, math.ceil(max(y0, y1, y2))))
                    if maxx < minx or maxy < miny: continue
                    xs, ys = np.meshgrid(np.arange(minx, maxx + 1) + 0.5, np.arange(miny, maxy + 1) + 0.5)
                    den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
                    if abs(den) < 1e-9: continue
                    l0 = ((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / den
                    l1 = ((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / den
                    l2 = 1 - l0 - l1
                    inside = (l0 >= -1e-6) & (l1 >= -1e-6) & (l2 >= -1e-6)
                    z = l0 * z0 + l1 * z1 + l2 * z2
                    uu = l0 * uvs[tri[0]][0] + l1 * uvs[tri[1]][0] + l2 * uvs[tri[2]][0]
                    vv = l0 * uvs[tri[0]][1] + l1 * uvs[tri[1]][1] + l2 * uvs[tri[2]][1]
                    tx = np.clip(np.floor(uu - 1e-6 * np.sign(u1 - u0)).astype(int), 0, tw - 1)
                    ty = np.clip(np.floor(vv - 1e-6 * np.sign(v1 - v0)).astype(int), 0, th - 1)
                    texel = tex[ty, tx]
                    sub = zb[miny:maxy + 1, minx:maxx + 1]
                    ok = inside & (z < sub - 1e-4) & (texel[..., 3] > 0)
                    if not ok.any(): continue
                    col = texel.copy().astype(float); col[..., :3] *= shade
                    region = img[miny:maxy + 1, minx:maxx + 1]
                    region[ok] = col[ok].astype(np.uint8)
                    sub[ok] = z[ok]
    return Image.fromarray(img)


if __name__ == "__main__":
    a = sys.argv[1:]
    yaw, pitch, scale = 225, 30, 14
    out = a[0]; specs = []
    i = 1
    while i < len(a):
        if a[i] == "--yaw": yaw = float(a[i + 1]); i += 2
        elif a[i] == "--pitch": pitch = float(a[i + 1]); i += 2
        elif a[i] == "--scale": scale = float(a[i + 1]); i += 2
        else: specs.append(a[i]); i += 1
    render(specs, yaw, pitch, scale).save(out)
