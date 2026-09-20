#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
STALKER Armor mod — asset pipeline.

Reads the original assets:
  ../armor MODEL/*.obj   (Blender-exported, per-body-part armor meshes, rig scale: 1 unit = 4 MC units)
  ../armor TEX/*.png     (512x512 armor textures)

Produces (inside ../src/main/resources + ../src/main/java):
  - converted geometry  assets/stalkerarmor/geo/armor/<family>/<part>.obj  (pivot-relative MC model units)
  - textures            assets/stalkerarmor/textures/armor/*.png
  - item icons          assets/stalkerarmor/textures/item/*.png           (isometric renders)
  - item models         assets/stalkerarmor/models/item/*.json
  - lang files          assets/stalkerarmor/lang/{en_us,ru_ru}.json
  - recipes             data/stalkerarmor/recipes/*.json
  - Java set table      ../src/main/java/com/stalkerarmor/StalkerArmorSets.java  (generated!)
  - preview sheet       ../docs/preview.png

Coordinate conversion (Blender file space -> Minecraft model space):
  file space: Y up, +Z front (visor/backpack evidence), +X = entity's left (assumed, cosmetically irrelevant)
  MC model space: +Y down, -Z front (face), +X entity's left; 1 unit = 1/16 block; root origin at the neck.
  mc = (4*x, -4*y, -4*z) + T_part, with T_part placing the rig bone at the vanilla pivot-relative anchor.
  Then an "inflate" (per-axis, about the mesh bbox center) lifts armor surfaces off the skin to avoid z-fighting.

Pivots (vanilla HumanoidModel 1.20.1): head (0,0,0) hat(0,0,0) body(0,0,0),
rightArm (-5,2,0) leftArm (5,2,0) rightLeg (-1.9,12,0) leftLeg (1.9,12,0).
Boxes: head (-4,-8,-4,8,8,8) body(-4,0,-2,8,12,4) arm(-3,-2,-2,4,12,4) leg(-2,0,-2,4,12,4).
"""
import os, sys, json, math, shutil
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
MODEL_DIR = os.path.join(REPO, "armor MODEL")
TEX_DIR = os.path.join(REPO, "armor TEX")
RES = os.path.join(REPO, "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", "stalkerarmor")
DATA = os.path.join(RES, "data", "stalkerarmor")
JAVA_DIR = os.path.join(REPO, "src", "main", "java", "com", "stalkerarmor")

# ---------------------------------------------------------------- geometry ---

# Part anchor offsets (MC model units, pivot-relative) and inflate amounts.
PART_T = {
    "head":  (0.0, -4.0, 0.0),
    "chest": (0.0,  4.6, 0.0),
    "arm":   (0.0,  4.0, 0.0),
    "leg":   (0.0,  6.0, 0.0),
    "boot":  (0.0,  9.1, -1.5),
}
PART_INFLATE = {"head": 0.75, "chest": 0.35, "arm": 0.30, "leg": 0.30, "boot": 0.35}
# vanilla pivots (root space) for assembling previews/icons
PIVOT = {
    "head": (0.0, 0.0, 0.0), "chest": (0.0, 0.0, 0.0),
    "armL": (5.0, 2.0, 0.0), "armR": (-5.0, 2.0, 0.0),
    "legL": (1.9, 12.0, 0.0), "legR": (-1.9, 12.0, 0.0),
    "bootL": (1.9, 12.0, 0.0), "bootR": (-1.9, 12.0, 0.0),
}

def parse_obj(path):
    vs, vts, vns, faces = [], [], [], []
    with open(path, "r", errors="replace") as f:
        for line in f:
            if line.startswith("v "):
                p = line.split(); vs.append([float(p[1]), float(p[2]), float(p[3])])
            elif line.startswith("vt "):
                p = line.split(); vts.append([float(p[1]), float(p[2])])
            elif line.startswith("vn "):
                p = line.split(); vns.append([float(p[1]), float(p[2]), float(p[3])])
            elif line.startswith("f "):
                idx = []
                for tok in line.split()[1:]:
                    parts = tok.split("/")
                    vi = int(parts[0])
                    ti = int(parts[1]) if len(parts) > 1 and parts[1] else 0
                    ni = int(parts[2]) if len(parts) > 2 and parts[2] else 0
                    idx.append((vi, ti, ni))
                for k in range(1, len(idx) - 1):  # fan triangulate
                    faces.append((idx[0], idx[k], idx[k + 1]))
    return vs, vts, vns, faces

class Mesh:
    """Converted mesh: pivot-relative MC model units; uv = (u, v_obj); normals converted."""
    def __init__(self, verts, uvs, norms, tris):
        self.v = verts; self.uv = uvs; self.n = norms; self.t = tris

def load_part(family, part):
    path = os.path.join(MODEL_DIR, f"arm_{family}_{part}.obj")
    if not os.path.exists(path):
        return None
    vs, vts, vns, faces = parse_obj(path)
    tx, ty, tz = PART_T[part]
    d = PART_INFLATE[part]
    # convert
    V = np.array(vs, dtype=np.float64)
    V[:, 0] *= 4.0; V[:, 1] *= -4.0; V[:, 2] *= -4.0
    V[:, 0] += tx;  V[:, 1] += ty;  V[:, 2] += tz
    # per-axis inflate about bbox center
    lo, hi = V.min(axis=0), V.max(axis=0)
    c = (lo + hi) / 2.0
    sz = np.maximum(hi - lo, 1e-6)
    V = c + (V - c) * ((sz + 2.0 * d) / sz)
    # normals: (nx, -ny, -nz), renormalized
    N = np.zeros((len(vns) if vns else len(vs), 3), dtype=np.float64)
    if vns:
        N = np.array(vns, dtype=np.float64)
        N[:, 1] *= -1.0; N[:, 2] *= -1.0
        ln = np.linalg.norm(N, axis=1, keepdims=True); ln[ln == 0] = 1
        N = N / ln
    # expand per-triangle corner data (vertex/uv/normal may be indexed differently)
    verts, uvs, norms, tris = [], [], [], []
    for (a, b, c2) in faces:
        for (vi, ti, ni) in (a, b, c2):
            verts.append(V[vi - 1])
            uvs.append((vts[ti - 1][0], vts[ti - 1][1]) if 0 < ti <= len(vts) else (0.0, 0.0))
            if 0 < ni <= len(N):
                norms.append(N[ni - 1])
            else:  # fallback: flat normal
                norms.append((0.0, -1.0, 0.0))
        tris.append((len(verts) - 3, len(verts) - 2, len(verts) - 1))
    # compute fallback normals where missing (marked above) using face normal
    verts = np.array(verts); uvs = np.array(uvs); norms = np.array(norms); tris = np.array(tris, dtype=int)
    for i, t in enumerate(tris):
        if np.allclose(norms[t[0]], (0.0, -1.0, 0.0)):
            n = np.cross(verts[t[1]] - verts[t[0]], verts[t[2]] - verts[t[0]])
            ln = np.linalg.norm(n)
            n = n / ln if ln > 1e-9 else np.array([0.0, -1.0, 0.0])
            for k in t:
                if np.allclose(norms[k], (0.0, -1.0, 0.0)):
                    norms[k] = n
    return Mesh(verts, uvs, norms, tris)

def write_obj(mesh, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write("# STALKER Armor converted geometry (MC model units, pivot-relative)\n")
        for v in mesh.v:
            f.write(f"v {v[0]:.5f} {v[1]:.5f} {v[2]:.5f}\n")
        for t in mesh.uv:
            f.write(f"vt {t[0]:.5f} {t[1]:.5f}\n")
        for n in mesh.n:
            f.write(f"vn {n[0]:.5f} {n[1]:.5f} {n[2]:.5f}\n")
        for t in mesh.t:
            # indices are corner-based (1-based here): each corner its own v/vt/vn
            f.write("f %d/%d/%d %d/%d/%d %d/%d/%d\n" % (
                t[0]+1, t[0]+1, t[0]+1, t[1]+1, t[1]+1, t[1]+1, t[2]+1, t[2]+1, t[2]+1))

# ------------------------------------------------------------------ sets ----

# tier -> (durability base, defense(helmet,chest,legs,boots), toughness, kb_res, ench_value, equip_sound, craft_item, repair_item)
TIERS = {
    "jacket":    (11, (1, 3, 2, 1), 0.0, 0.00, 15, "LEATHER", "minecraft:leather",     "minecraft:leather"),
    "cape":      (12, (1, 3, 2, 1), 0.0, 0.00, 15, "LEATHER", "minecraft:leather",     "minecraft:leather"),
    "kombez":    (18, (2, 5, 4, 2), 0.0, 0.00, 12, "IRON",    "minecraft:iron_ingot",  "minecraft:iron_ingot"),
    "scientist": (20, (2, 6, 5, 2), 0.5, 0.00, 12, "IRON",    "minecraft:iron_ingot",  "minecraft:iron_ingot"),
    "seva":      (20, (3, 6, 5, 2), 1.0, 0.00, 12, "IRON",    "minecraft:iron_ingot",  "minecraft:iron_ingot"),
    "zarya":     (21, (2, 6, 5, 2), 1.0, 0.00, 12, "IRON",    "minecraft:iron_ingot",  "minecraft:iron_ingot"),
    "berill":    (25, (3, 7, 5, 2), 2.0, 0.00, 10, "IRON",    "minecraft:iron_ingot",  "minecraft:iron_ingot"),
    "bulat":     (33, (3, 8, 6, 3), 2.0, 0.05, 10, "DIAMOND", "minecraft:diamond",     "minecraft:iron_ingot"),
    "heavy":     (33, (3, 8, 6, 3), 2.5, 0.10, 10, "DIAMOND", "minecraft:diamond",     "minecraft:iron_ingot"),
    "exo":       (37, (4, 8, 6, 3), 3.0, 0.15, 10, "DIAMOND", "minecraft:diamond",     "minecraft:diamond"),
}

# (set_id, family, texture_file, ru_name, en_name)
SETS = [
    # Берилл-5М
    ("berill_military", "berill", "arm_berill_military.png", "Берилл-5М (армейский)", "Berill-5M (Military)"),
    ("berill_freedom",  "berill", "arm_berill_freedom.png",  "Берилл-5М («Свобода»)",  "Berill-5M (Freedom)"),
    ("berill_worn",     "berill", "arm_berill_dead.png",     "Берилл-5М (изношенный)", "Berill-5M (Worn)"),
    # Булат
    ("bulat_military", "bulat", "arm_bulat_military.png", "Булат (армейский)",      "Bulat (Military)"),
    ("bulat_duty",     "bulat", "arm_bulat_duty.png",     "Булат («Долг»)",         "Bulat (Duty)"),
    ("bulat_freedom",  "bulat", "arm_bulat_freedom.png",  "Булат («Свобода»)",      "Bulat (Freedom)"),
    ("bulat_merc",     "bulat", "arm_bulat_merc.png",     "Булат (наёмник)",        "Bulat (Mercenary)"),
    ("bulat_worn",     "bulat", "arm_bulat_dead.png",     "Булат (изношенный)",     "Bulat (Worn)"),
    # Тяжёлая броня (СКАТ)
    ("heavy_merc_1",     "heavy", "arm_heavy_merc_1.png",     "Тяжёлая броня (наёмник)",       "Heavy Armor (Mercenary)"),
    ("heavy_merc_2",     "heavy", "arm_heavy_merc_2.png",     "Тяжёлая броня (наёмник, Mk.II)","Heavy Armor (Mercenary Mk.II)"),
    ("heavy_duty",       "heavy", "arm_heavy_duty.png",       "Тяжёлая броня («Долг»)",        "Heavy Armor (Duty)"),
    ("heavy_freedom",    "heavy", "arm_heavy_freedom.png",    "Тяжёлая броня («Свобода»)",     "Heavy Armor (Freedom)"),
    ("heavy_monolith",   "heavy", "arm_heavy_monolith_1.png", "Тяжёлая броня («Монолит»)",     "Heavy Armor (Monolith)"),
    ("heavy_monolith_2", "heavy", "arm_heavy_monolith_2.png", "Тяжёлая броня («Монолит», Mk.II)","Heavy Armor (Monolith Mk.II)"),
    ("heavy_stalker",    "heavy", "arm_heavy_stalker.png",    "Тяжёлая броня (сталкер)",       "Heavy Armor (Stalker)"),
    ("heavy_unknown",    "heavy", "arm_heavy_unknown.png",    "Тяжёлая броня (неизвестная)",   "Heavy Armor (Unknown)"),
    # Экзоскелет (та же текстура, рама поверх брони)
    ("exo_merc_1",     "exo", "arm_heavy_merc_1.png",     "Экзоскелет (наёмник)",        "Exoskeleton (Mercenary)"),
    ("exo_merc_2",     "exo", "arm_heavy_merc_2.png",     "Экзоскелет (наёмник, Mk.II)", "Exoskeleton (Mercenary Mk.II)"),
    ("exo_duty",       "exo", "arm_heavy_duty.png",       "Экзоскелет («Долг»)",         "Exoskeleton (Duty)"),
    ("exo_freedom",    "exo", "arm_heavy_freedom.png",    "Экзоскелет («Свобода»)",      "Exoskeleton (Freedom)"),
    ("exo_monolith",   "exo", "arm_heavy_monolith_1.png", "Экзоскелет («Монолит»)",      "Exoskeleton (Monolith)"),
    ("exo_monolith_2", "exo", "arm_heavy_monolith_2.png", "Экзоскелет («Монолит», Mk.II)","Exoskeleton (Monolith Mk.II)"),
    ("exo_stalker",    "exo", "arm_heavy_stalker.png",    "Экзоскелет (сталкер)",        "Exoskeleton (Stalker)"),
    ("exo_unknown",    "exo", "arm_heavy_unknown.png",    "Экзоскелет (неизвестный)",    "Exoskeleton (Unknown)"),
    # Куртки
    ("jacket",      "jacket", "arm_jacket.png",      "Кожаная куртка", "Leather Jacket"),
    ("jacket_black","jacket", "arm_black_jacket.png","Чёрная куртка",  "Black Jacket"),
    # Комбинезоны
    ("kombez_bandit", "kombez", "arm_bandit_kombez.png",  "Комбинезон бандита",        "Bandit Jumpsuit"),
    ("kombez_merc",   "kombez", "arm_merc_kombez_1.png",  "Комбинезон наёмника",       "Mercenary Jumpsuit"),
    ("kombez_merc_2", "kombez", "arm_merc_kombez_2.png",  "Комбинезон наёмника Mk.II", "Mercenary Jumpsuit Mk.II"),
    ("kombez_us",     "kombez", "arm_us_kombez_1.png",    "Комбинезон «US»",           "US Jumpsuit"),
    ("kombez_us_2",   "kombez", "arm_us_kombez_2.png",    "Комбинезон «US» Mk.II",     "US Jumpsuit Mk.II"),
    ("kombez_unknown","kombez", "arm_unknown_kombez_1.png","Неизвестный комбинезон",   "Unknown Jumpsuit"),
    ("kombez_unknown_2","kombez","arm_unknown_kombez_2.png","Неизвестный комбинезон Mk.II","Unknown Jumpsuit Mk.II"),
    # Научные костюмы
    ("scientist_1", "scientist", "arm_scientist_1.png", "Научный костюм (зелёный)",   "Scientific Suit (Green)"),
    ("scientist_2", "scientist", "arm_scientist_2.png", "Научный костюм (коричневый)","Scientific Suit (Brown)"),
    ("scientist_3", "scientist", "arm_scientist_3.png", "Научный костюм (синий)",     "Scientific Suit (Blue)"),
    ("scientist_4", "scientist", "arm_scientist_4.png", "Научный костюм (оливковый)", "Scientific Suit (Olive)"),
    # СЕВА
    ("seva_merc",     "seva", "arm_seva_merc.png",     "СЕВА (наёмник)",    "SEVA (Mercenary)"),
    ("seva_duty",     "seva", "arm_seva_duty.png",     "СЕВА («Долг»)",     "SEVA (Duty)"),
    ("seva_freedom",  "seva", "arm_seva_freedom.png",  "СЕВА («Свобода»)",  "SEVA (Freedom)"),
    ("seva_monolith", "seva", "arm_seva_monolith.png", "СЕВА («Монолит»)",  "SEVA (Monolith)"),
    ("seva_stalker",  "seva", "arm_seva_stalker.png",  "СЕВА (сталкер)",    "SEVA (Stalker)"),
    # Заря
    ("zarya_stalker",   "zarya", "arm_zarya_stalker.png",   "Заря (сталкер)",       "Zarya (Stalker)"),
    ("zarya_duty",      "zarya", "arm_zarya_duty.png",      "Заря («Долг»)",        "Zarya (Duty)"),
    ("zarya_freedom",   "zarya", "arm_zarya_freedom_1.png", "Заря («Свобода»)",     "Zarya (Freedom)"),
    ("zarya_freedom_2", "zarya", "arm_zarya_freedom_2.png", "Заря («Свобода» Mk.II)","Zarya (Freedom Mk.II)"),
    ("zarya_freedom_3", "zarya", "arm_zarya_freedom_3.png", "Заря («Свобода» Mk.III)","Zarya (Freedom Mk.III)"),
    ("zarya_monolith",  "zarya", "arm_zarya_monolith.png",  "Заря («Монолит»)",     "Zarya (Monolith)"),
    ("zarya_worn",      "zarya", "arm_zarya_dead_1.png",    "Заря (изношенная)",    "Zarya (Worn)"),
    ("zarya_worn_2",    "zarya", "arm_zarya_dead_2.png",    "Заря (изношенная Mk.II)","Zarya (Worn Mk.II)"),
    # Плащи
    ("cape",       "cape", "arm_cape.png",       "Плащ сталкера", "Stalker Mantle"),
    ("cape_black", "cape", "arm_black_cape.png", "Чёрный плащ",   "Black Mantle"),
]

PIECES = [
    # (id_suffix, java_type, ru_word, en_word)
    ("helmet",     "HELMET",     "Шлем",    "Helmet"),
    ("chestplate", "CHESTPLATE", "Броня",   "Chestplate"),
    ("leggings",   "LEGGINGS",   "Штаны",   "Leggings"),
    ("boots",      "BOOTS",      "Ботинки", "Boots"),
]
# which mesh parts must exist for a piece to be available
PIECE_PARTS = {"helmet": "head", "chestplate": "chest", "leggings": "leg", "boots": "boot"}

def family_parts(family):
    parts = []
    for part in ("head", "chest", "arm", "leg", "boot"):
        if os.path.exists(os.path.join(MODEL_DIR, f"arm_{family}_{part}.obj")):
            parts.append(part)
    return parts

# ---------------------------------------------------------------- renderer ---

def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], dtype=np.float64)

def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], dtype=np.float64)

def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], dtype=np.float64)

L1 = np.array([-0.35, -0.75, -0.55]); L1 /= np.linalg.norm(L1)
L2 = np.array([0.65, -0.15, 0.35]);  L2 /= np.linalg.norm(L2)

def shade_normals(nmask):
    lam = np.clip(nmask @ L1, 0, 1) * 0.60 + np.clip(nmask @ L2, 0, 1) * 0.25 + 0.35
    return np.clip(lam, 0, 1.15)

def sample_tex(tex, u, v):
    """tex: HxWx4 float; uv arrays; v in OBJ convention (0 bottom)."""
    H, W = tex.shape[0], tex.shape[1]
    x = np.clip(u * (W - 1), 0, W - 1)
    y = np.clip((1.0 - v) * (H - 1), 0, H - 1)
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int)
    x1 = np.minimum(x0 + 1, W - 1); y1 = np.minimum(y0 + 1, H - 1)
    fx = (x - x0)[:, None]; fy = (y - y0)[:, None]
    c00 = tex[y0, x0]; c10 = tex[y0, x1]; c01 = tex[y1, x0]; c11 = tex[y1, x1]
    return (c00 * (1 - fx) * (1 - fy) + c10 * fx * (1 - fy) +
            c01 * (1 - fx) * fy + c11 * fx * fy)

def rasterize(img, zbuf, P, uv, nrm, tris, tex=None, flat=None):
    H, W = img.shape[0], img.shape[1]
    for t in tris:
        p0, p1, p2 = P[t[0]], P[t[1]], P[t[2]]
        minx = max(int(math.floor(min(p0[0], p1[0], p2[0]))), 0)
        maxx = min(int(math.ceil(max(p0[0], p1[0], p2[0]))), W - 1)
        miny = max(int(math.floor(min(p0[1], p1[1], p2[1]))), 0)
        maxy = min(int(math.ceil(max(p0[1], p1[1], p2[1]))), H - 1)
        if minx > maxx or miny > maxy:
            continue
        xs, ys = np.meshgrid(np.arange(minx, maxx + 1), np.arange(miny, maxy + 1))
        x0, y0 = p0[0], p0[1]; x1, y1 = p1[0], p1[1]; x2, y2 = p2[0], p2[1]
        d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(d) < 1e-12:
            continue
        l0 = (((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / d).ravel()
        l1 = (((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / d).ravel()
        l2 = 1.0 - l0 - l1
        inside = (l0 >= -1e-6) & (l1 >= -1e-6) & (l2 >= -1e-6)
        if not inside.any():
            continue
        z = l0 * p0[2] + l1 * p1[2] + l2 * p2[2]
        zreg = zbuf[miny:maxy + 1, minx:maxx + 1]
        sel = inside & (z < zreg.ravel())
        if not sel.any():
            continue
        uu = l0 * uv[t[0], 0] + l1 * uv[t[1], 0] + l2 * uv[t[2], 0]
        vv = l0 * uv[t[0], 1] + l1 * uv[t[1], 1] + l2 * uv[t[2], 1]
        nn = (l0[:, None] * nrm[t[0]] + l1[:, None] * nrm[t[1]] + l2[:, None] * nrm[t[2]])
        ln = np.linalg.norm(nn, axis=1, keepdims=True); ln[ln == 0] = 1
        nn = nn / ln
        shade = shade_normals(nn)[:, None]
        if tex is not None:
            col = sample_tex(tex, uu, vv)
        else:
            col = np.tile(np.array(flat, dtype=np.float64), (len(uu), 1))
        m = sel & (col[:, 3] > 127)
        m2 = m.reshape(zreg.shape)
        zreg[m2] = z[m]
        region = img[miny:maxy + 1, minx:maxx + 1]
        region[m2] = col[m]

def mirror_mesh(mesh):
    V = mesh.v.copy(); V[:, 0] *= -1
    N = mesh.n.copy(); N[:, 0] *= -1
    return Mesh(V, mesh.uv.copy(), N, mesh.t.copy())

def pose_mesh(mesh, part_key, rot=None):
    V = mesh.v.copy(); N = mesh.n.copy()
    if rot is not None:
        V = V @ rot.T; N = N @ rot.T
    px, py, pz = PIVOT[part_key]
    V = V[:, 0] + px, V[:, 1] + py, V[:, 2] + pz
    V = np.stack(V, axis=1)
    return Mesh(V, mesh.uv.copy(), N, mesh.t.copy())

def assembly_for(fm, piece, for_icon=True):
    out = []
    arm_out = 0.5 if for_icon else 0.0
    leg_out = 0.12 if for_icon else 0.0
    if piece == "helmet":
        if "head" in fm:
            out.append(pose_mesh(fm["head"], "head"))
    elif piece == "chestplate":
        if "chest" in fm:
            out.append(pose_mesh(fm["chest"], "chest"))
        if "arm" in fm:
            out.append(pose_mesh(fm["arm"], "armL", rot_z(-arm_out)))
            out.append(pose_mesh(mirror_mesh(fm["arm"]), "armR", rot_z(arm_out)))
    elif piece == "leggings":
        if "leg" in fm:
            out.append(pose_mesh(fm["leg"], "legL", rot_z(-leg_out)))
            out.append(pose_mesh(mirror_mesh(fm["leg"]), "legR", rot_z(leg_out)))
    elif piece == "boots":
        if "boot" in fm:
            out.append(pose_mesh(fm["boot"], "bootL", rot_z(-leg_out)))
            out.append(pose_mesh(mirror_mesh(fm["boot"]), "bootR", rot_z(leg_out)))
    return out

def full_assembly(fm):
    out = []
    for key, part in (("head", "head"),):
        if part in fm: out.append(pose_mesh(fm[part], key))
    if "chest" in fm: out.append(pose_mesh(fm["chest"], "chest"))
    if "arm" in fm:
        out.append(pose_mesh(fm["arm"], "armL"))
        out.append(pose_mesh(mirror_mesh(fm["arm"]), "armR"))
    if "leg" in fm:
        out.append(pose_mesh(fm["leg"], "legL"))
        out.append(pose_mesh(mirror_mesh(fm["leg"]), "legR"))
    if "boot" in fm:
        out.append(pose_mesh(fm["boot"], "bootL"))
        out.append(pose_mesh(mirror_mesh(fm["boot"]), "bootR"))
    return out

VANILLA_BOXES = [
    ((0, 0, 0),    (-4, -8, -4, 8, 8, 8)),   # head
    ((0, 0, 0),    (-4, 0, -2, 8, 12, 4)),   # body
    ((5, 2, 0),    (-3, -2, -2, 4, 12, 4)),  # left arm
    ((-5, 2, 0),   (-3, -2, -2, 4, 12, 4)),  # right arm
    ((1.9, 12, 0), (-2, 0, -2, 4, 12, 4)),   # left leg
    ((-1.9, 12, 0),(-2, 0, -2, 4, 12, 4)),   # right leg
]

def render(assm, tex, size, yaw=0.0, pitch=0.0, margin=0.10, ss=4, silhouette=False, bg=(24, 26, 30, 255)):
    W = H = size * ss
    img = np.tile(np.array(bg, dtype=np.float64), (H, W, 1))
    zbuf = np.full((H, W), 1e30)
    cam_R = rot_x(pitch) @ rot_y(yaw)
    def proj(V):
        P = V @ cam_R.T
        return P
    meshes = assm
    allP, allUV, allN, allT = [], [], [], []
    base = 0
    for mesh in meshes:
        P = proj(mesh.v)
        allP.append(P); allUV.append(mesh.uv); allN.append(mesh.n)
        allT.append(mesh.t + base)
        base += len(P)
    P = np.concatenate(allP); UV = np.concatenate(allUV); N = np.concatenate(allN)
    T = np.concatenate(allT)
    lo = P.min(axis=0); hi = P.max(axis=0)
    span = max(hi[0] - lo[0], hi[1] - lo[1], 1e-6)
    scale = (W * (1 - 2 * margin)) / span
    cx, cy = (lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2
    def to_screen(Pr):
        Q = Pr.copy()
        Q[:, 0] = (Pr[:, 0] - cx) * scale + W / 2
        Q[:, 1] = (Pr[:, 1] - cy) * scale + H / 2
        Q[:, 2] = Pr[:, 2] * scale
        return Q
    Ps = to_screen(P)
    if silhouette:
        for piv, (x, y, z, w, h, d) in VANILLA_BOXES:
            corners = []
            for dx in (0, 1):
                for dy in (0, 1):
                    for dz in (0, 1):
                        corners.append([piv[0] + x + dx * w, piv[1] + y + dy * h, piv[2] + z + dz * d])
            corners = np.array(corners)
            Pc = to_screen(proj(corners))
            faces = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
            zuv = np.zeros((8, 2)); zn = np.zeros((8, 3))
            for f in faces:
                for tri in ((f[0], f[1], f[2]), (f[0], f[2], f[3])):
                    rasterize(img, zbuf, Pc, zuv, zn, [tri], tex=None, flat=(78, 78, 84, 255))
    rasterize(img, zbuf, Ps, UV, N, T, tex=tex)
    img = np.clip(img, 0, 255).astype(np.uint8)
    im = Image.fromarray(img, "RGBA")
    if ss > 1:
        im = im.resize((size, size), Image.LANCZOS)
    return im

# ------------------------------------------------------------------- main ---

def clean_dir(p):
    if os.path.exists(p):
        shutil.rmtree(p)
    os.makedirs(p, exist_ok=True)

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else "all"
    families = sorted({s[1] for s in SETS})
    fam_parts = {f: family_parts(f) for f in families}
    print("families:", {f: p for f, p in fam_parts.items()})

    # ---- 1. geometry conversion
    geo_dir = os.path.join(ASSETS, "geo", "armor")
    if only in ("all", "geo"):
        clean_dir(geo_dir)
        fm = {}
        for f in families:
            fm[f] = {}
            for part in fam_parts[f]:
                m = load_part(f, part)
                write_obj(m, os.path.join(geo_dir, f, part + ".obj"))
                fm[f][part] = m
        # ship the ORIGINAL obj files untouched as well (assets/stalkerarmor/geo/original/)
        orig_dir = os.path.join(ASSETS, "geo", "original")
        clean_dir(orig_dir)
        for f in families:
            for part in fam_parts[f]:
                shutil.copyfile(os.path.join(MODEL_DIR, f"arm_{f}_{part}.obj"),
                                os.path.join(orig_dir, f"arm_{f}_{part}.obj"))
        # numeric sanity report (root space)
        print("\n=== geometry sanity (root-space MC units; vanilla body: head y[-8,0], body y[0,12], "
              "arms y[-2,10]@x[3,7], legs y[12,24], ground=24) ===")
        for f in families:
            row = []
            for part in fam_parts[f]:
                m = fm[f][part]
                key = {"head": "head", "chest": "chest", "arm": "armL", "leg": "legL", "boot": "bootL"}[part]
                V = m.v.copy()
                V[:, 0] += PIVOT[key][0]; V[:, 1] += PIVOT[key][1]; V[:, 2] += PIVOT[key][2]
                lo, hi = V.min(axis=0), V.max(axis=0)
                row.append(f"{part}:x[{lo[0]:.1f},{hi[0]:.1f}] y[{lo[1]:.1f},{hi[1]:.1f}] z[{lo[2]:.1f},{hi[2]:.1f}]")
            print(f"{f:10s} " + " | ".join(row))
    else:
        fm = {}
        for f in families:
            fm[f] = {}
            for part in fam_parts[f]:
                fm[f][part] = load_part(f, part)

    # ---- 2. textures
    tex_dir = os.path.join(ASSETS, "textures", "armor")
    if only in ("all", "tex"):
        clean_dir(tex_dir)
        used = sorted({s[2] for s in SETS})
        for t in used:
            src = os.path.join(TEX_DIR, t)
            assert os.path.exists(src), f"missing texture {t}"
            shutil.copyfile(src, os.path.join(tex_dir, t))
        print(f"\ncopied {len(used)} textures")

    # ---- 3. icons + item models + recipes + lang
    item_dir = os.path.join(ASSETS, "textures", "item")
    model_dir = os.path.join(ASSETS, "models", "item")
    lang_dir = os.path.join(ASSETS, "lang")
    recipe_dir = os.path.join(DATA, "recipes")
    if only in ("all", "icons"):
        clean_dir(item_dir)
        os.makedirs(model_dir, exist_ok=True)
        for f in os.listdir(model_dir):
            os.remove(os.path.join(model_dir, f))
        clean_dir(recipe_dir)
        os.makedirs(lang_dir, exist_ok=True)
        en, ru = {"itemGroup.stalkerarmor": "STALKER Armor"}, {"itemGroup.stalkerarmor": "Броня сталкера"}
        PIECE_PATTERN = {
            "helmet": ["XXX", "X X"],
            "chestplate": ["X X", "XXX", "XXX"],
            "leggings": ["XXX", "X X", "X X"],
            "boots": ["X X", "X X"],
        }
        fam_tex_cache = {}
        n_items = 0
        for sid, family, texture, set_ru, set_en in SETS:
            if family not in fam_tex_cache:
                img = Image.open(os.path.join(TEX_DIR, texture)).convert("RGBA")
                fam_tex_cache[family] = np.array(img, dtype=np.float64)
            tex = fam_tex_cache[family]
            tier = TIERS[family]
            for piece, jtype, ru_word, en_word in PIECES:
                if PIECE_PARTS[piece] not in fm[family]:
                    continue
                item_id = f"{sid}_{piece}"
                n_items += 1
                assm = assembly_for(fm[family], piece, for_icon=True)
                icon = render(assm, tex, 64, yaw=-0.62, pitch=0.42, margin=0.13, ss=4, bg=(0, 0, 0, 0))
                icon.save(os.path.join(item_dir, item_id + ".png"))
                # item model json
                with open(os.path.join(model_dir, item_id + ".json"), "w") as fh:
                    json.dump({"parent": "minecraft:item/generated",
                               "textures": {"layer0": f"stalkerarmor:item/{item_id}"}}, fh, indent=2)
                # recipe
                with open(os.path.join(recipe_dir, item_id + ".json"), "w") as fh:
                    json.dump({
                        "type": "minecraft:crafting_shaped",
                        "category": "equipment",
                        "pattern": PIECE_PATTERN[piece],
                        "key": {"X": {"item": tier[6]}},
                        "result": {"item": f"stalkerarmor:{item_id}", "count": 1},
                    }, fh, indent=2)
                # lang
                ru[f"item.stalkerarmor.{item_id}"] = f"{ru_word} «{set_ru}»"
                en[f"item.stalkerarmor.{item_id}"] = f"{set_en} {en_word}"
        with open(os.path.join(lang_dir, "ru_ru.json"), "w", encoding="utf-8") as fh:
            json.dump(ru, fh, ensure_ascii=False, indent=2)
        with open(os.path.join(lang_dir, "en_us.json"), "w", encoding="utf-8") as fh:
            json.dump(en, fh, ensure_ascii=False, indent=2)
        print(f"generated {n_items} items (icons, models, recipes, lang)")

    # ---- 4. java set table
    if only in ("all", "java"):
        os.makedirs(JAVA_DIR, exist_ok=True)
        lines = []
        lines.append("package com.stalkerarmor;")
        lines.append("")
        lines.append("/* AUTO-GENERATED by tools/gen_assets.py — do not edit by hand. */")
        lines.append("public final class StalkerArmorSets {")
        lines.append("    public static final StalkerArmorSet[] ALL = new StalkerArmorSet[] {")
        for sid, family, texture, set_ru, set_en in SETS:
            parts = fam_parts[family]
            flags = ", ".join(str(p in parts).lower() for p in ("head", "chest", "leg", "boot"))
            lines.append(f"        new StalkerArmorSet(\"{sid}\", \"{family}\", \"{texture}\", "
                         f"StalkerArmorMaterials.{family.upper()}, {flags}),")
        lines.append("    };")
        lines.append("")
        lines.append("    private StalkerArmorSets() {}")
        lines.append("}")
        with open(os.path.join(JAVA_DIR, "StalkerArmorSets.java"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
        print("generated StalkerArmorSets.java")

    # ---- 5. preview sheet + logo
    if only in ("all", "preview"):
        docs = os.path.join(REPO, "docs")
        os.makedirs(docs, exist_ok=True)
        cols, cell, pad = 8, 116, 10
        rows = math.ceil(len(SETS) / cols)
        sheet = Image.new("RGBA", (cols * (cell + pad) + pad, rows * (cell + 22 + pad) + pad), (16, 17, 20, 255))
        d = ImageDraw.Draw(sheet)
        for i, (sid, family, texture, set_ru, set_en) in enumerate(SETS):
            img = Image.open(os.path.join(TEX_DIR, texture)).convert("RGBA")
            tex = np.array(img, dtype=np.float64)
            assm = full_assembly(fm[family])
            view = render(assm, tex, cell - 8, yaw=0.0, pitch=0.0, margin=0.07, ss=3,
                          silhouette=True, bg=(26, 28, 32, 255))
            x = pad + (i % cols) * (cell + pad)
            y = pad + (i // cols) * (cell + 22 + pad)
            sheet.paste(view, (x, y))
            d.text((x + 2, y + cell - 4), sid, fill=(200, 205, 210, 255))
        sheet.save(os.path.join(docs, "preview.png"))
        # logo
        img = Image.open(os.path.join(TEX_DIR, "arm_berill_military.png")).convert("RGBA")
        tex = np.array(img, dtype=np.float64)
        logo = render([pose_mesh(fm["berill"]["head"], "head")], tex, 128, yaw=-0.62, pitch=0.42,
                      margin=0.15, ss=3, bg=(0, 0, 0, 0))
        logo.save(os.path.join(RES, "stalkerarmor_logo.png"))
        print("generated docs/preview.png + logo")

    # ---- 6. scientist palette info (for naming)
    if only == "colors":
        for i in (1, 2, 3, 4):
            img = np.array(Image.open(os.path.join(TEX_DIR, f"arm_scientist_{i}.png")).convert("RGB"))
            print(f"scientist_{i}: mean RGB", img.reshape(-1, 3).mean(axis=0).round(1))

if __name__ == "__main__":
    main()
