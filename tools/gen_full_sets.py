#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generates:
  1. steve.obj (+ steve.mtl) at the repo root — the player mannequin in the SAME
     coordinate convention as the "armor MODEL" files:
       * 1 unit = 4 Minecraft pixels (1/4 block), Y up, -Z is the FRONT (face)
       * feet at y=0, total height 8 units (32 px = 2 blocks)
       * objects: o head, o chest, o armL, o armR, o legL, o legR
       * UVs laid out like a vanilla 64x64 skin (preview purposes)
     Model your armor over this mannequin, keep the object names, export OBJ.
  2. assets/stalkerarmor/geo/sets/<id>.obj — sample full-body armor sets
     assembled from the original per-part models (proof of the format).
  3. Icons / item models / recipes / lang entries / StalkerFullSets.java for the
     one-slot full-body armor items.

The mod converts mannequin space -> player model space at runtime using the
same per-part anchors (BONE + T) that were verified against every original model.
"""
import os, sys, json, math, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
os.chdir(REPO)

import gen_assets as ga

# ---------------------------------------------------------------- mannequin --
# Bone positions in mannequin space (must match the Java loader exactly!)
BONES = {
    "head":  (0.0,   7.0,  0.0),
    "chest": (0.0,   4.85, 0.0),
    "armL":  (1.25,  4.5,  0.0),
    "armR":  (-1.25, 4.5,  0.0),
    "legL":  (0.475, 1.5,  0.0),
    "legR":  (-0.475,1.5,  0.0),
}

# Vanilla skin UV (64x64) origins per part: (u0, v0)
SKIN_UV = {
    "head":  (0, 0),
    "chest": (16, 16),
    "armL":  (32, 48),   # left arm  (64x64 layout)
    "armR":  (40, 16),   # right arm
    "legL":  (16, 48),   # left leg
    "legR":  (0, 16),    # right leg
}

# part -> (size w,h,d) in px (vanilla proportions)
PART_SIZE = {
    "head":  (8, 8, 8),
    "chest": (8, 12, 4),
    "armL":  (4, 12, 4),
    "armR":  (4, 12, 4),
    "legL":  (4, 12, 4),
    "legR":  (4, 12, 4),
}

def part_box(part):
    """Axis-aligned box of the part in mannequin space (vanilla proportions)."""
    w, h, d = PART_SIZE[part]
    bx, by, bz = BONES[part]
    if part == "head":
        return (bx - 1, by - 1, bz - 1, bx + 1, by + 1, bz + 1)
    if part == "chest":
        # torso spans y_pr[0,12] -> mannequin y[3,6] (top at neck y=6)
        return (bx - 1, 3.0, bz - 0.5, bx + 1, 6.0, bz + 0.5)
    if part == "armL":
        # vanilla left arm box (mirrored) is x_pr[-1,3] — hangs OUTWARD from the bone
        return (bx - 0.25, 3.0, bz - 0.5, bx + 0.75, 6.0, bz + 0.5)
    if part == "armR":
        # vanilla right arm box is x_pr[-3,1]
        return (bx - 0.75, 3.0, bz - 0.5, bx + 0.25, 6.0, bz + 0.5)
    if part == "legL":
        # leg box y_pr[0,12] -> mannequin y[0,3] (feet on the ground)
        return (bx - 0.5, 0.0, bz - 0.5, bx + 0.5, 3.0, bz + 0.5)
    if part == "legR":
        return (bx - 0.5, 0.0, bz - 0.5, bx + 0.5, 3.0, bz + 0.5)
    raise ValueError(part)

def face_uv(part, face, w, h, d):
    """UV rectangle (u1,v1,u2,v2) in pixels for a box face (vanilla layout)."""
    u0, v0 = SKIN_UV[part]
    if face == "up":    return (u0 + d, v0, u0 + d + w, v0 + d)
    if face == "down":  return (u0 + d + w, v0, u0 + d + 2 * w, v0 + d)
    if face == "east":  return (u0, v0 + d, u0 + d, v0 + d + h)          # -X side
    if face == "north": return (u0 + d, v0 + d, u0 + d + w, v0 + d + h)  # front (-Z)
    if face == "west":  return (u0 + d + w, v0 + d, u0 + d + w + d, v0 + d + h)  # +X
    if face == "south": return (u0 + d + w + d, v0 + d, u0 + 2 * d + 2 * w, v0 + d + h)  # back
    raise ValueError(face)

def gen_steve():
    parts = ["head", "chest", "armL", "armR", "legL", "legR"]
    lines_v, lines_vt, lines_vn, lines_f = [], [], [], []
    nv = nvt = nvn = 0
    for part in parts:
        x1, y1, z1, x2, y2, z2 = part_box(part)
        w, h, d = PART_SIZE[part]
        # 8 corners
        corners = {
            "a": (x1, y2, z2), "b": (x2, y2, z2), "c": (x2, y2, z1), "d": (x1, y2, z1),
            "e": (x1, y1, z2), "f": (x2, y1, z2), "g": (x2, y1, z1), "h": (x1, y1, z1),
        }
        for p in corners.values():
            lines_v.append("v %.5f %.5f %.5f" % p); nv += 1
        idx = {k: nv - 8 + i + 1 for i, k in enumerate("abcdefgh")}  # OBJ is 1-based
        # face normals
        norms = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1),
                 "south": (0, 0, 1), "west": (1, 0, 0), "east": (-1, 0, 0)}
        for n in norms.values():
            lines_vn.append("vn %d %d %d" % n); nvn += 1
        nbase = nvn - 6
        faces = {
            "up":    ("a", "b", "f", "e"),
            "down":  ("e", "f", "g", "h"),
            "north": ("d", "c", "g", "h"),   # front (-Z)
            "south": ("b", "a", "e", "f"),   # back (+Z)
            "west":  ("c", "b", "f", "g"),   # +X
            "east":  ("a", "d", "h", "e"),   # -X
        }
        for face, quad in faces.items():
            u1, v1, u2, v2 = face_uv(part, face, w, h, d)
            uvpx = [(u1, v2), (u2, v2), (u2, v1), (u1, v1)]  # CCW from bottom-left
            for (u, v) in uvpx:
                lines_vt.append("vt %.6f %.6f" % (u / 64.0, 1.0 - v / 64.0)); nvt += 1
            ni = nbase + list(norms).index(face) + 1  # 1-based
            lines_f.append("f %d/%d/%d %d/%d/%d %d/%d/%d %d/%d/%d" % (
                idx[quad[0]], nvt - 3, ni, idx[quad[1]], nvt - 2, ni,
                idx[quad[2]], nvt - 1, ni, idx[quad[3]], nvt, ni))
    header = [
        "# Steve mannequin for STALKER Armor modeling",
        "# Coordinates: SAME rig as armor MODEL/*.obj — 1 unit = 4 MC pixels, Y up, -Z = front (face), feet at y=0",
        "# Model your armor over this mannequin. Keep objects named: head, chest, armL, armR, legL, legR",
        "# (or just arm / leg for mirrored pairs). Export OBJ -> it becomes one-slot full-body armor.",
        "# UVs follow the vanilla 64x64 skin layout (apply a skin texture to preview).",
        "mtllib steve.mtl",
    ]
    with open("steve.obj", "w") as f:
        f.write("\n".join(header) + "\n")
        for pi, part in enumerate(parts):
            f.write("o %s\n" % part)
            f.write("\n".join(lines_v[pi * 8:(pi + 1) * 8]) + "\n")
            f.write("\n".join(lines_vt[pi * 24:(pi + 1) * 24]) + "\n")
            f.write("\n".join(lines_vn[pi * 6:(pi + 1) * 6]) + "\n")
            f.write("usemtl steve\n")
            f.write("\n".join(lines_f[pi * 6:(pi + 1) * 6]) + "\n")
    with open("steve.mtl", "w") as f:
        f.write("newmtl steve\nKd 0.8 0.8 0.8\nd 1.0\nillum 2\n")
    print("steve.obj written")

# ------------------------------------------------------------- full sets -----

FULL_SETS = [
    ("full_berill",     "berill",     "arm_berill_military.png", "Берилл-5М (полный комплект)",    "Berill-5M (full suit)"),
    ("full_bulat",      "bulat",      "arm_bulat_military.png",  "Булат (полный комплект)",        "Bulat (full suit)"),
    ("full_heavy",      "heavy",      "arm_heavy_merc_1.png",    "Тяжёлая броня (полный комплект)","Heavy Armor (full suit)"),
    ("full_exo",        "exo",        "arm_heavy_merc_1.png",    "Экзоскелет (полный комплект)",   "Exoskeleton (full suit)"),
    ("full_jacket",     "jacket",     "arm_jacket.png",          "Куртка (полный комплект)",       "Jacket (full suit)"),
    ("full_kombez",     "kombez",     "arm_merc_kombez_1.png",   "Комбинезон (полный комплект)",   "Jumpsuit (full suit)"),
    ("full_scientist",  "scientist",  "arm_scientist_1.png",     "Научный костюм (полный комплект)","Scientific Suit (full suit)"),
    ("full_seva",       "seva",       "arm_seva_merc.png",       "СЕВА (полный комплект)",         "SEVA (full suit)"),
    ("full_zarya",      "zarya",      "arm_zarya_stalker.png",   "Заря (полный комплект)",         "Zarya (full suit)"),
    ("full_cape",       "cape",       "arm_cape.png",            "Плащ сталкера (полный комплект)","Stalker Mantle (full suit)"),
]

def parse_obj_local(path):
    vs, vts, faces = [], [], []
    for line in open(path, errors="replace"):
        if line.startswith("v "):
            p = line.split(); vs.append((float(p[1]), float(p[2]), float(p[3])))
        elif line.startswith("vt "):
            p = line.split(); vts.append((float(p[1]), float(p[2])))
        elif line.startswith("f "):
            idx = [(int(t.split("/")[0]), int(t.split("/")[1]) if len(t.split("/")) > 1 and t.split("/")[1] else 0)
                   for t in line.split()[1:]]
            faces.append(idx)
    return vs, vts, faces

def gen_full_set_obj():
    """Assemble per-part models into single full-body OBJs in mannequin space."""
    sets_dir = os.path.join(ga.ASSETS, "geo", "sets")
    if os.path.exists(sets_dir):
        shutil.rmtree(sets_dir)
    os.makedirs(sets_dir)
    for sid, family, texture, *_ in FULL_SETS:
        blocks = []          # each block: ["o name", "v ...", ..., "vt ...", ..., "f ...", ...]
        voff = toff = 1

        def emit(group_name, part_file, mirror=False, dy=0.0, dz=0.0):
            nonlocal voff, toff
            path = os.path.join(ga.MODEL_DIR, part_file)
            if not os.path.exists(path):
                return
            vs, vts, faces = parse_obj_local(path)
            bx, by, bz = BONES[group_name]
            block = ["o %s" % group_name]
            for (x, y, z) in vs:
                if mirror:
                    x = -x
                block.append("v %.5f %.5f %.5f" % (x + bx, y + by + dy, z + bz + dz))
            for (u, v) in vts:
                block.append("vt %.5f %.5f" % (u, v))
            for face in faces:
                corners = ["%d/%d" % (vi - 1 + voff, (ti - 1 + toff) if ti else toff)
                           for (vi, ti) in face]
                block.append("f " + " ".join(corners))
            voff += len(vs); toff += len(vts)
            blocks.append(block)

        # pre-baked boot offsets (T_boot - T_leg)/4 with sign flip on Y
        boot_dy, boot_dz = -0.775, 0.375
        emit("head", f"arm_{family}_head.obj")
        emit("chest", f"arm_{family}_chest.obj")
        emit("armL", f"arm_{family}_arm.obj", mirror=False)
        emit("armR", f"arm_{family}_arm.obj", mirror=True)
        emit("legL", f"arm_{family}_leg.obj", mirror=False)
        emit("legL", f"arm_{family}_boot.obj", mirror=False, dy=boot_dy, dz=boot_dz)
        emit("legR", f"arm_{family}_leg.obj", mirror=True)
        emit("legR", f"arm_{family}_boot.obj", mirror=True, dy=boot_dy, dz=boot_dz)

        with open(os.path.join(sets_dir, sid + ".obj"), "w") as f:
            f.write("# STALKER Armor full-body set (mannequin space, see steve.obj)\n")
            for block in blocks:
                f.write("\n".join(block) + "\n")
    print("full set OBJs:", len(FULL_SETS))

def gen_full_items():
    """Icons, item models, recipes, lang for full-set items + StalkerFullSets.java."""
    item_dir = os.path.join(ga.ASSETS, "textures", "item")
    model_dir = os.path.join(ga.ASSETS, "models", "item")
    recipe_dir = os.path.join(ga.DATA, "recipes")
    lang_dir = os.path.join(ga.ASSETS, "lang")
    os.makedirs(item_dir, exist_ok=True); os.makedirs(model_dir, exist_ok=True)
    os.makedirs(recipe_dir, exist_ok=True); os.makedirs(lang_dir, exist_ok=True)

    ru = json.load(open(os.path.join(lang_dir, "ru_ru.json"), encoding="utf-8"))
    en = json.load(open(os.path.join(lang_dir, "en_us.json"), encoding="utf-8"))

    for sid, family, texture, set_ru, set_en in FULL_SETS:
        # icon: full assembly of the family, isometric
        img = ga.Image.open(os.path.join(ga.TEX_DIR, texture)).convert("RGBA")
        tex = ga.np.array(img, dtype=ga.np.float64)
        fm = {p: ga.load_part(family, p) for p in ga.family_parts(family)}
        assm = ga.full_assembly(fm)
        icon = ga.render(assm, tex, 64, yaw=-0.62, pitch=0.42, margin=0.10, ss=4, bg=(0, 0, 0, 0))
        icon.save(os.path.join(item_dir, sid + ".png"))
        with open(os.path.join(model_dir, sid + ".json"), "w") as fh:
            json.dump({"parent": "minecraft:item/generated",
                       "textures": {"layer0": f"stalkerarmor:item/{sid}"}}, fh, indent=2)
        tier = ga.TIERS[family]
        with open(os.path.join(recipe_dir, sid + ".json"), "w") as fh:
            json.dump({
                "type": "minecraft:crafting_shaped",
                "category": "equipment",
                "pattern": ["X X", "XXX", "XXX"],
                "key": {"X": {"item": tier[6]}},
                "result": {"item": f"stalkerarmor:{sid}", "count": 1},
            }, fh, indent=2)
        ru[f"item.stalkerarmor.{sid}"] = set_ru
        en[f"item.stalkerarmor.{sid}"] = set_en

    json.dump(ru, open(os.path.join(lang_dir, "ru_ru.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    json.dump(en, open(os.path.join(lang_dir, "en_us.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    lines = ["package com.stalkerarmor;", "",
             "/* AUTO-GENERATED by tools/gen_full_sets.py — do not edit by hand. */",
             "public record StalkerFullSet(String id, String family, String texture) {",
             "    public static final StalkerFullSet[] ALL = new StalkerFullSet[] {"]
    for sid, family, texture, *_ in FULL_SETS:
        lines.append(f"        new StalkerFullSet(\"{sid}\", \"{family}\", \"{texture}\"),")
    lines += ["    };", "}", ""]
    with open(os.path.join(ga.JAVA_DIR, "StalkerFullSet.java"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print("full items generated:", len(FULL_SETS))

def gen_fallback_layer_textures():
    """Vanilla-default armor texture paths (belt & suspenders): if the Forge
    getArmorTexture hook ever fails, the layer falls back to
    textures/models/armor/<material>_layer_1/2.png — make sure those exist."""
    src_dir = os.path.join(ga.ASSETS, "textures", "armor")
    dst_dir = os.path.join(ga.ASSETS, "textures", "models", "armor")
    os.makedirs(dst_dir, exist_ok=True)
    family_tex = {sid.split("_", 1)[1] if False else f: t for f, t in {
        "jacket": "arm_jacket.png", "cape": "arm_cape.png",
        "kombez": "arm_merc_kombez_1.png", "scientist": "arm_scientist_1.png",
        "seva": "arm_seva_merc.png", "zarya": "arm_zarya_stalker.png",
        "berill": "arm_berill_military.png", "bulat": "arm_bulat_military.png",
        "heavy": "arm_heavy_merc_1.png", "exo": "arm_heavy_merc_1.png",
    }.items() if f in ga.TIERS}
    for fam, tex in family_tex.items():
        for layer in ("_layer_1", "_layer_2"):
            shutil.copyfile(os.path.join(src_dir, tex), os.path.join(dst_dir, fam + layer + ".png"))
    print("fallback layer textures:", len(family_tex) * 2)

if __name__ == "__main__":
    gen_steve()
    gen_full_set_obj()
    gen_full_items()
    gen_fallback_layer_textures()
