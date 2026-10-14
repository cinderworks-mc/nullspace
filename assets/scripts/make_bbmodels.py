#!/usr/bin/env python3
"""Turn assets/source/aseprite/<item>.layout.json (the item layout files) into Blockbench assets/source/<item>.bbmodel."""
import json, base64, glob, os, uuid
R = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEX = R + "/src/main/resources/assets/nullspace/textures/item/"
for f in glob.glob(R + "/assets/source/aseprite/*.layout.json"):
    d = json.load(open(f)); name = os.path.basename(f)[:-12]; S = d["sheet"]
    ns = uuid.UUID("6f1d3c2e-0000-4000-8000-000000000000")
    els, ids = [], []
    for i, e in enumerate(d["elements"]):
        u = str(uuid.uuid5(ns, name + str(i))); ids.append(u)
        el = {"name": e["name"], "from": e["from"], "to": e["to"], "faces": e["faces"], "uuid": u, "type": "cube", "box_uv": False}
        for k in ("origin", "rotation"):
            if k in e: el[k] = e[k]
        els.append(el)
    tp = TEX + name + "_model.png"
    if not os.path.exists(tp): tp = TEX.replace("/item/", "/block/") + name + "_model.png"   # hall blocks
    tex = base64.b64encode(open(tp, "rb").read()).decode()
    bb = {"meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False}, "name": d["name"],
          "resolution": {"width": S, "height": S}, "elements": els, "outliner": ids,
          "textures": [{"path": "", "name": name + "_model.png", "folder": "item", "namespace": "nullspace", "id": "0",
                        "width": S, "height": S, "uv_width": S, "uv_height": S, "uuid": str(uuid.uuid5(ns, name)),
                        "source": "data:image/png;base64," + tex}], "display": d["display"]}
    json.dump(bb, open(R + "/assets/source/%s.bbmodel" % name, "w"), indent=1)
    print(name, len(els), "elements")
