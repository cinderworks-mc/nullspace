#!/usr/bin/env python3
"""Turns raw dev-client screenshots into the README gallery images (assets/previews/readme/).

The raw shots are screenshots taken in a development client at 1280x720 with a gui scale of 2. Usage: make_readme_gallery.py RAW_DIR
where RAW_DIR holds the sub-folders gui/, w3/, ha/, glass/, art/ with the raw PNGs. Only own pixels and Minecraft's own textures appear in the output.
Images are cropped, resized to at most 1400 px wide and palette-quantised to keep the set small.
"""
import os
import sys

from PIL import Image

RAW = sys.argv[1]
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "assets/previews/readme")
GUI_BOX = (930, 440, 1630, 990)  # the terminal dialog in a 2560x1440 shot at GUI scale 2


def load(sub, name):
    return Image.open(os.path.join(RAW, sub, name)).convert("RGB")


def save(im, name, width=1400):
    if im.width > width:
        im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    path = os.path.join(OUT, name)
    im.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(path, optimize=True)
    print(name, im.size, os.path.getsize(path) // 1024, "KB")


def gui(name, box=GUI_BOX):
    return load("gui", "gui_1280x720_s2_%s.png" % name).crop(box)


def side_by_side(a, b, gap=16):
    w = Image.new("RGB", (a.width + gap + b.width, max(a.height, b.height)), (24, 20, 16))
    w.paste(a, (0, 0))
    w.paste(b, (a.width + gap, 0))
    return w


def main():
    os.makedirs(OUT, exist_ok=True)
    only_gui = os.environ.get("NS_GALLERY_ONLY") == "gui"  # re-make only the three terminal images (needs just RAW_DIR/gui)
    if not only_gui:
        other()
    save(side_by_side(gui("rooms_hall"), gui("settings_recall_on")), "terminal_rooms_settings.png")
    save(side_by_side(gui("team_on"), gui("cards_mixed")), "terminal_guests_cards.png")
    pal = load("gui", "gui_1280x720_s2_palette_window.png")
    save(side_by_side(gui("dest_5"), pal.crop((1020, 480, 1530, 950))), "terminal_destinations_palette.png")


def other():
    save(load("w3", "hall_00_new_far.png"), "starter_room.png")
    save(load("w3", "hall_17_w21_angle.png"), "grand_hall.png")
    save(load("glass", "sky_glass_medium_north_up.png"), "glass_cube.png")
    save(load("ha", "art_01_overview_ne.png"), "waypoint_hall.png")
    save(load("ha", "art_17_pad_pair_bound_unbound.png"), "destination_pads.png")
    art = load("art", "art_09_third_front_cartridge.png")
    save(art.crop((700, 300, 2300, 1400)), "held_cartridge.png", 1000)


main()
