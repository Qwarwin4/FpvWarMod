"""Air defence: the P1-SUN-style interceptor, the search radar (mast and the array that turns on it) and the
radar console. Built from primitives with gen_ew's Kit, in blocks around the entity origin; the textures are
gen_citadel's atlas plus two regions painted here (a radar scope for the monitors, a near-black for the drone)."""
import os

import numpy as np
from PIL import Image, ImageDraw

import gen_citadel as gc
from gen_ew import Kit

STEEL, DARK, CAMO, PANEL, RUBBER, GLASS, LIGHT = gc.STEEL, gc.DARK, gc.CAMO, gc.PANEL, gc.RUBBER, gc.GLASS, gc.LIGHT
SCREEN = (48, 32, 16, 16)
BLACK = (32, 48, 16, 16)
OLIVE = (48, 48, 16, 16)


def atlas(path):
    gc.atlas(path)
    im = Image.open(path).convert("RGBA")
    d = ImageDraw.Draw(im)
    x, y, w, h = SCREEN
    d.rectangle([x, y, x + w - 1, y + h - 1], fill=(8, 22, 12, 255))
    cx, cy = x + 7.5, y + 7.5
    for r in (2.5, 5, 7):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(40, 120, 60, 255))
    d.line([cx, cy, x + 13, y + 3], fill=(110, 230, 130, 255))
    for px, py in ((x + 4, y + 5), (x + 11, y + 10), (x + 9, y + 3)):
        d.point((px, py), fill=(230, 80, 60, 255))
    x, y, w, h = BLACK
    rng = np.random.default_rng(7)
    a = np.asarray(im).copy()
    a[y:y + h, x:x + w, :3] = (np.array([24, 25, 27]) + rng.integers(-2, 3, (h, w))[..., None]).clip(0, 255)
    x, y, w, h = OLIVE
    a[y:y + h, x:x + w, :3] = (np.array([82, 88, 60]) + rng.integers(-3, 4, (h, w))[..., None]).clip(0, 255)
    Image.fromarray(a).save(path)


def interceptor():
    """fuselage along z (nose forward), centre 0.11 over the ground; four arms in an X with motors on top"""
    k = Kit()
    cy = 0.11
    k.cyl_b("z", (0.0, cy), 0.055, 0.055, -0.24, 0.16, BLACK, 12)
    k.cyl_b("z", (0.0, cy), 0.055, 0.012, 0.16, 0.3, OLIVE, 12)          # warhead nose
    k.cyl_b("z", (0.0, cy), 0.05, 0.03, -0.3, -0.24, DARK, 12)          # tail cap
    k.box_b((-0.03, cy + 0.045, -0.12), (0.03, cy + 0.075, 0.02), DARK)  # battery hatch / VTX
    for sx in (1, -1):
        for sz in (1, -1):
            k.beam((0.0, cy + 0.02, 0.0), (0.19 * sx, cy + 0.02, 0.13 * sz), 0.03, BLACK)
            k.cyl_b("y", (0.19 * sx, 0.13 * sz), 0.03, 0.03, cy, cy + 0.07, DARK, 10)
            # skid under each motor
            k.cyl_b("y", (0.19 * sx, 0.13 * sz), 0.008, 0.008, 0.0, cy, RUBBER, 5, caps=False)
    k.cyl_b("y", (0.0, -0.05), 0.004, 0.004, cy + 0.05, cy + 0.2, RUBBER, 4, caps=False)  # antenna
    return k


def radar():
    """two-wheel trailer with outriggers, an equipment cabinet and the mast up to the rotary joint (4.15)"""
    k = Kit()
    k.box_b((-0.7, 0.45, -1.1), (0.7, 0.62, 1.0), CAMO)                 # bed
    k.beam((0.0, 0.52, 1.0), (0.0, 0.42, 1.9), 0.09, DARK)               # drawbar
    for sx in (1, -1):
        a0, a1 = sorted((0.72 * sx, 0.94 * sx))
        k.cyl_b("x", (0.38, -0.2), 0.38, 0.38, a0, a1, RUBBER, 14)
        x0, x1 = sorted((0.66 * sx, 0.98 * sx))
        k.box_b((x0, 0.78, -0.65), (x1, 0.84, 0.25), CAMO)                # mudguard
    for sx in (1, -1):
        for sz in (1, -1):
            # outrigger jacks
            foot = (1.05 * sx, 0.0, 1.0 * sz)
            k.beam((0.6 * sx, 0.5, 0.85 * sz), (foot[0], 0.12, foot[2]), 0.07, DARK)
            k.box_b((foot[0] - 0.13, 0.0, foot[2] - 0.13), (foot[0] + 0.13, 0.06, foot[2] + 0.13), STEEL)
    k.box_b((-0.6, 0.62, -1.05), (0.15, 1.45, -0.45), CAMO)              # cabinet
    k.box_b((-0.55, 0.9, -0.45), (0.1, 1.3, -0.43), DARK)                # cabinet door
    k.box_b((0.3, 0.62, -1.0), (0.65, 1.0, -0.55), DARK)                 # generator
    k.cyl_b("y", (0.0, 0.0), 0.16, 0.13, 0.62, 3.0, CAMO, 12)            # mast, two sections
    k.cyl_b("y", (0.0, 0.0), 0.11, 0.1, 3.0, 4.05, STEEL, 12)
    k.cyl_b("y", (0.0, 0.0), 0.2, 0.2, 4.05, 4.15, DARK, 14)            # rotary joint base
    for a in (0, 120, 240):
        r = np.radians(a)
        k.beam((np.sin(r) * 0.6, 0.62, np.cos(r) * 0.6), (0.0, 2.2, 0.0), 0.04, STEEL)
    return k


def radar_antenna():
    """turns about the mast: origin on the rotary joint (the renderer moves it to 4.15 over the mast)"""
    k = Kit()
    z0 = 0.0
    k.cyl_b("y", (0.0, z0), 0.14, 0.14, 0.0, 0.3, DARK, 14)
    k.box_b((-0.12, 0.25, z0 - 0.12), (0.12, 0.45, z0 + 0.12), CAMO)
    # the planar array, leaning back a little, with its face forward
    k.panel((0.0, z0 + 0.1), 0, 2.3, 1.05, 0.12, 0.35)
    k.beam((0.0, 0.4, z0), (0.0, 1.2, z0 + 0.1), 0.06, STEEL)
    for sx in (1, -1):
        k.beam((0.0, 0.4, z0), (1.0 * sx, 0.45, z0 + 0.1), 0.05, STEEL)
    # IFF strip on top
    k.box_b((-1.1, 1.4, z0 + 0.08), (1.1, 1.52, z0 + 0.2), DARK)
    k.cyl_b("y", (0.9, z0 + 0.14), 0.03, 0.03, 1.52, 1.62, LIGHT, 6)
    return k


def radar_console():
    """field table with two monitors showing the scope, a keyboard, a radio and a folding stool"""
    k = Kit()
    top = 0.78
    k.box_b((-0.75, top - 0.05, -0.35), (0.75, top, 0.35), OLIVE)
    for sx in (1, -1):
        for sz in (1, -1):
            k.box_b((0.68 * sx - 0.03, 0.0, 0.28 * sz - 0.03), (0.68 * sx + 0.03, top - 0.05, 0.28 * sz + 0.03), STEEL)
    for x0 in (-0.62, 0.02):
        # monitor on its foot, facing the operator at +z
        k.box_b((x0 + 0.22, top, -0.2), (x0 + 0.38, top + 0.03, -0.08), DARK)
        k.box_b((x0 + 0.27, top + 0.03, -0.16), (x0 + 0.33, top + 0.18, -0.12), DARK)
        k.box_b((x0, top + 0.12, -0.2), (x0 + 0.6, top + 0.55, -0.14), BLACK)
        q = [(x0 + 0.04, top + 0.16, -0.139), (x0 + 0.56, top + 0.16, -0.139),
             (x0 + 0.56, top + 0.51, -0.139), (x0 + 0.04, top + 0.51, -0.139)]
        k.quad([k.px(p) for p in q], SCREEN, n=np.array([0, 0, 1.0]), scale=1.0)
    k.box_b((-0.3, top, 0.05), (0.3, top + 0.025, 0.22), DARK)            # keyboard
    k.box_b((0.4, top, 0.0), (0.7, top + 0.14, 0.25), CAMO)               # radio set
    k.cyl_b("y", (0.62, 0.05), 0.008, 0.008, top + 0.14, top + 0.6, RUBBER, 4, caps=False)
    k.cyl_b("y", (0.0, 0.75), 0.2, 0.2, 0.42, 0.47, OLIVE, 12)           # stool
    for a in (45, 135, 225, 315):
        r = np.radians(a)
        k.beam((np.sin(r) * 0.18, 0.0, 0.75 + np.cos(r) * 0.18), (0.0, 0.43, 0.75), 0.025, STEEL)
    return k


def build(assets, modid, gm):
    tex_dir = os.path.join(assets, "textures", "entity", "mesh", "air")
    os.makedirs(tex_dir, exist_ok=True)
    atlas(os.path.join(tex_dir, "atlas.png"))
    ref = f"{modid}:entity/mesh/air/atlas"
    out = []
    for name, fn in (("interceptor", interceptor), ("radar", radar), ("radar_antenna", radar_antenna),
                     ("radar_console", radar_console)):
        fn().write(os.path.join(assets, "models", "entity", name + ".mesh"), ref)
        out.append(name)
    return out
