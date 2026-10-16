#!/usr/bin/env python3
"""gives the window blockstate a frame property: one set of child models per finish, and a variant per frame value.

the 49 shape models in models/block/shell_window/w_*.json stay as they are. for every finish this writes
models/block/shell_window/<finish>/w_*.json, a four line child that only swaps the frame texture to that finish's own
tile, then rewrites blockstates/shell_window.json so each variant also carries frame=<finish>. safe to run again.

    python3 assets/scripts/make_window_frames.py
"""
import json
import os
import re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "main", "resources", "assets", "nullspace")
FINISHES = ["ivory", "green", "charcoal", "wood", "copper", "amber"]
STATE = os.path.join(ROOT, "blockstates", "shell_window.json")
MODELS = os.path.join(ROOT, "models", "block", "shell_window")


def base_variants():
    # a second run starts from the plain variants, so strip any frame the file already has
    seen = {}
    for key, value in json.load(open(STATE))["variants"].items():
        plain = re.sub(r",?frame=\w+", "", key)
        entry = value[0] if isinstance(value, list) else value
        entry = dict(entry, model=re.sub(r"shell_window/(?:\w+/)?(w_\d{4}_\d{4})$", r"shell_window/\1", entry["model"]))
        seen[plain] = entry
    return seen


def main():
    base = base_variants()
    shapes = sorted({v["model"].rsplit("/", 1)[1] for v in base.values()})
    for f in FINISHES:
        os.makedirs(os.path.join(MODELS, f), exist_ok=True)
        for shape in shapes:
            child = {"parent": f"nullspace:block/shell_window/{shape}",
                     "textures": {"frame": f"nullspace:block/shell_{f}", "particle": f"nullspace:block/shell_{f}"}}
            with open(os.path.join(MODELS, f, shape + ".json"), "w") as out:
                json.dump(child, out, indent=2)
                out.write("\n")
    variants = {}
    for plain, entry in base.items():
        shape = entry["model"].rsplit("/", 1)[1]
        for f in FINISHES:
            variants[f"{plain},frame={f}"] = dict(entry, model=f"nullspace:block/shell_window/{f}/{shape}")
    with open(STATE, "w") as out:
        json.dump({"variants": variants}, out, indent=1)
        out.write("\n")
    print(f"{len(shapes)} shapes x {len(FINISHES)} finishes = {len(shapes) * len(FINISHES)} models, {len(variants)} variants")


if __name__ == "__main__":
    main()
