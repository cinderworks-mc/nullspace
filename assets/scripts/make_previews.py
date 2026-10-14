#!/usr/bin/env python3
"""Build assets/previews/contact_sheet.png and vs_tempad.png (nearest-neighbour, labelled).
Usage: python3 assets/scripts/make_previews.py  [--only-items file1 file2 ... for ad-hoc strips: use scratch arg]"""
import sys, glob, os
from PIL import Image, ImageDraw
R = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
T = R + "/src/main/resources/assets/nullspace/textures/"
TP = R + "/research/Tempad/src/main/resources/assets/tempad/textures/"
BG = (30, 34, 40, 255)

def sheet(entries, out, scale=8, cols=6, cell=None, bg=BG):
    cell = cell or (16*scale+24)
    rows = (len(entries)+cols-1)//cols
    im = Image.new("RGBA", (cols*cell, rows*(cell+16)), bg)
    d = ImageDraw.Draw(im)
    for i, (label, path, crop) in enumerate(entries):
        a = Image.open(path).convert("RGBA")
        if crop: a = a.crop(crop)
        w, h = a.size
        sc = scale if max(w, h) <= 32 else max(1, (16*scale)//max(w, h))
        a = a.resize((w*sc, h*sc), Image.NEAREST)
        x = (i % cols)*cell + (cell-a.width)//2
        y = (i//cols)*(cell+16) + 6
        im.paste(a, (x, y), a)
        d.text(((i % cols)*cell+4, (i//cols)*(cell+16)+cell-6), label, fill=(230, 225, 200, 255))
    im.save(out)

def native_row(paths, out, scale=1):
    pass

if __name__ == "__main__":
    ent = []
    for sub in ["item", "block", "gui/sprites"]:
        for f in sorted(glob.glob(T+sub+"/*.png")):
            ent.append((sub.split('/')[0]+"/"+os.path.basename(f)[:-4], f, None))
    sheet(ent, R+"/assets/previews/contact_sheet.png", scale=6, cols=7)
    mine = ["access_cartridge", "spatial_expander", "annex_module", "continuity_anchor", "surface_applicator"]
    tem = ["chronon_cell", "location_card", "time_twister", "screening_device", "chronon_battery"]
    ent = [("NS "+m, T+"item/"+m+"_model.png", None) for m in mine] + [("TP "+m, TP+"item/"+m+".png", None) for m in tem]
    sheet(ent, R+"/assets/previews/vs_tempad.png", scale=10, cols=6)

    fin = ["shell_ivory","shell_green","shell_charcoal","shell_wood","shell_copper","shell_amber"]
    tt = Image.new("RGBA", (3*5*16*3+40, 2*5*16*3+30), BG)
    for i, f in enumerate(fin):
        a = Image.open(T+"block/"+f+".png").convert("RGBA")
        t5 = Image.new("RGBA", (80, 80))
        for x in range(5):
            for y in range(5): t5.paste(a, (x*16, y*16))
        tt.paste(t5.resize((240, 240), Image.NEAREST), (10+(i%3)*250, 10+(i//3)*250))
    tt.save(R+"/assets/previews/tile_test.png")
