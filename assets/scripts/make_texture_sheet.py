#!/usr/bin/env python3
"""Builds the all-textures contact sheet from the mod's own texture files (nothing else is read).

Usage: python3 assets/scripts/make_texture_sheet.py [output.png]
Default output: assets/previews/readme/textures_all.png (nearest-neighbour upscaling, labelled, grouped by folder).
Run from anywhere; paths are resolved from this file's location.
"""
import glob
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEX = os.path.join(ROOT, "src/main/resources/assets/nullspace/textures")
BG = (30, 34, 40, 255)
GROUPS = [("item", "items"), ("block", "blocks"), ("gui/sprites", "gui sprites"), ("gui/sprites/upgrade", "gui upgrade"),
          ("environment", "sky"), ("environment/void", "void sky layers")]
COLS, CELL, LABEL_H, TARGET = 8, 112, 14, 96


def main(out):
    entries = []
    for sub, title in GROUPS:
        for f in sorted(glob.glob(os.path.join(TEX, sub, "*.png"))):
            entries.append((title, os.path.basename(f)[:-4], f))
    rows = (len(entries) + COLS - 1) // COLS
    im = Image.new("RGBA", (COLS * CELL, rows * (CELL + LABEL_H)), BG)
    d = ImageDraw.Draw(im)
    for i, (title, name, path) in enumerate(entries):
        a = Image.open(path).convert("RGBA")
        w, h = a.size
        scale = max(1, TARGET // max(w, h)) if max(w, h) <= TARGET else 1
        a = a.resize((w * scale, h * scale), Image.NEAREST)
        if max(a.size) > TARGET:  # big images (sky) are shrunk to fit the cell
            a.thumbnail((TARGET, TARGET), Image.LANCZOS)
        x0, y0 = (i % COLS) * CELL, (i // COLS) * (CELL + LABEL_H)
        im.paste(a, (x0 + (CELL - a.width) // 2, y0 + (CELL - a.height) // 2), a)
        d.text((x0 + 3, y0 + CELL), name[:19], fill=(230, 225, 200, 255))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    im.convert("RGB").save(out, optimize=True)
    print(len(entries), "textures ->", out, im.size)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "assets/previews/readme/textures_all.png"))
