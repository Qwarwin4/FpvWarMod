"""
KamAZ launch truck, 30 mm gun mount, Kornet, Javelin and the ATGMs: box models with a proper UV unwrap and
textures painted by tools/uvbake.py (camouflage paint, dust, mud, chipped edges, decals), plus their item icons
rendered from the same models with tools/preview.py. Called from gen_assets.py; can also run on its own from
the project root.

Positions match the Java side: LaunchTruckEntity (wheels, rack pivot), AirDefenseGunEntity (PIVOT, TRUNNION),
KornetEntity (PIVOT, TRUNNION).
"""
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402
from PIL import Image, ImageFilter  # noqa: E402

from preview import Scene, T, R  # noqa: E402
from uvbake import (Bake, material, mat_paint, mat_flat, mat_era, mat_wheel, mat_metal, mat_rubber, mat_grille,  # noqa: E402
                    mat_glass, mat_light, mat_glow, mat_canvas, mat_missile, mat_drum, save_json)

MODID = "fpvdrones"
RES = os.environ.get("FPV_RES", "src/main/resources")
ASSETS = os.path.join(RES, "assets", MODID)
HERE = os.path.dirname(os.path.abspath(__file__))
TMP = os.path.join(HERE, "_build")

GREEN = {"base": "#4b5631", "dark": "#353d22", "light": "#5f6b3f", "camo": None,
         "dust": "#9a8f6e", "mud": "#4a3b28", "mud_line": 1.0}
CAMO = dict(GREEN, base="#55603a", camo=["#2f3526", "#80744f"])

material("armor", mat_paint("base"))
material("armor_dark", mat_paint("dark"))
material("armor_light", mat_paint("light"))
material("armor_plain", mat_paint("base", camo=False))
material("era", mat_era("base"))
material("wheel", mat_wheel("dark", tyre=0.42))
material("steel", mat_metal("#7b8086"))
material("gunmetal", mat_metal("#3d4136", brushed=False))
material("rubber", mat_rubber())
material("grille", mat_grille("dark"))
material("glass", mat_glass())
material("periscope", mat_glass("#3a5a6a"))
material("lens_amber", mat_glass("#6a4a18"))
material("light_white", mat_light("#fff2c6"))
material("light_red", mat_light("#d42020"))
material("light_amber", mat_light("#ffb030"))
material("canvas", mat_canvas())
material("tarp", mat_canvas("#4f5934"))
material("black", mat_flat("#1c1d1a", worn=False))
material("rust", mat_flat("#5a3a24", 0.15, 0.1, worn=False))
material("barrel_jacket", mat_canvas("#3e4630"))
material("muzzle", mat_metal("#2e3128", brushed=False))
material("drum", mat_drum("dark"))
material("tan", mat_flat("#8c8156", 0.06, 0.04))
material("tan_dark", mat_flat("#6c6440", 0.06, 0.04))
material("strap", mat_flat("#2f3226", 0.05, 0.05, worn=False))
material("hazard", mat_flat("#c9a521", 0.05, 0.04))
material("m_kornet", mat_missile("#3d5a2e", band="#c9b23f", band_at=0.8))
material("m_javelin", mat_missile("#6b6f4e"))
material("m_nose", mat_flat("#2c302a", 0.04, 0.03, worn=False))
material("m_dome", mat_glass("#5a5f63"))
material("m_fin", mat_flat("#2e3329", 0.04, 0.03, worn=False))
material("plume", mat_glow("#ff9a3a"))
material("tracer", mat_glow("#ffb347"))
material("tracer_core", mat_glow("#fff2c0"))

SCALES = {}


def part(b, name, scale, center_y=False, offset=(0, 0, 0)):
    p = b.part(name)
    SCALES[name] = (scale, center_y, offset)
    return p


def finish(b, folder="entity", display=None):
    size = b.bake(os.path.join(ASSETS, "textures", "entity", b.name + ".png"))
    for p in b.parts:
        scale, cy, off = SCALES[p.name]
        m = b.model_json(p, scale, cy, offset=off)
        if display is not None and p.name in display:
            m["display"] = display[p.name]
            save_json(m, os.path.join(ASSETS, "models", "item", p.name + ".json"))
        else:
            save_json(m, os.path.join(ASSETS, "models", folder, p.name + ".json"))
    print(f"  {b.name}: {size}x{size}")


# ---------------------------------------------------------------------------
# KamAZ-5350 launch truck (scale 3)

TRUCK_WHEELS = [(-1.0, 2.35), (1.0, 2.35), (-1.0, -1.0), (1.0, -1.0), (-1.0, -2.3), (1.0, -2.3)]
TRUCK_WHEEL_R = 0.62
TRUCK_RACK_PIVOT = (0, 1.2, 0.35)


def build_truck():
    b = Bake("truck", MODID, dict(GREEN, base="#4a5530", mud_line=1.2), density=36)
    h = part(b, "truck", 3)
    # frame and the big front bumper with lights and the tow hook
    h.both(0.38, 0.78, -3.7, 0.62, 1.02, 3.2, "gunmetal")
    h.B(-1.25, 0.55, 3.2, 1.25, 1.02, 3.48, {"all": "armor_dark", "up": "black",
                                            "south": ("armor_dark", [("bolts", 0.35, 0.15)])})
    h.both(0.82, 0.66, 3.48, 1.12, 0.9, 3.53, "light_white")
    h.both(0.5, 0.7, 3.48, 0.66, 0.82, 3.52, "light_amber")
    h.B(-0.2, 0.5, 3.3, 0.2, 0.62, 3.62, "gunmetal")
    # cab-over: lower grille, big two-piece windscreen, roof deflector
    h.B(-1.2, 1.02, 1.8, 1.2, 1.72, 3.22, {"all": "armor", "south": ("grille", [("rect", 0.0, 0.0, 1.0, 0.12, (52, 60, 36))])})
    h.B(-1.2, 1.72, 1.8, 1.2, 2.95, 3.12, {
        "all": "armor",
        "south": ("armor", [("rect", 0.05, 0.06, 0.495, 0.6, (34, 46, 54)), ("rect", 0.505, 0.06, 0.95, 0.6, (34, 46, 54)),
                            ("rect", 0.1, 0.1, 0.4, 0.14, (120, 140, 150))]),
        "east": ("armor", [("rect", 0.08, 0.06, 0.62, 0.5, (34, 46, 54)), ("vent", 0.7, 0.6, 0.95, 0.9, 3)]),
        "west": ("armor", [("rect", 0.38, 0.06, 0.92, 0.5, (34, 46, 54)), ("vent", 0.05, 0.6, 0.3, 0.9, 3)])})
    h.B(-1.05, 2.95, 1.9, 1.05, 3.08, 3.0, {"all": "armor_dark", "up": ("armor_dark", [("hatch", 0.5, 0.5, 0.3)])})
    h.B(-1.15, 2.88, 3.12, 1.15, 2.95, 3.32, "armor_dark")
    # mirrors, steps, front wheel arches
    h.both(1.2, 2.1, 2.9, 1.28, 2.15, 3.15, "black")
    h.both(1.28, 1.75, 3.1, 1.34, 2.35, 3.2, {"all": "black", "south": "glass"})
    h.both(1.2, 0.72, 2.75, 1.32, 0.78, 3.15, "gunmetal")
    h.both(1.2, 1.0, 2.75, 1.32, 1.06, 3.15, "gunmetal")
    h.both(0.7, 1.02, 1.6, 1.32, 1.12, 3.1, "armor_dark")
    # dashboard and steering wheel, seen from the driver's seat
    h.B(-1.1, 1.72, 2.85, 1.1, 2.0, 3.05, "black")
    h.B(0.32, 1.9, 2.55, 0.78, 2.3, 2.6, "black", rot=((0.55, 2.1, 2.57), "x", 22.5))
    # air intake snorkel behind the cab
    h.B(-1.2, 1.4, 1.62, -1.02, 3.15, 1.8, "armor_dark")
    h.B(-1.2, 3.0, 1.62, -0.85, 3.18, 1.8, "armor_dark")
    # fuel tanks and battery box between the axles
    h.both(0.65, 0.48, 0.15, 1.15, 1.0, 1.4, {"all": "drum", "east": "armor_dark", "west": "armor_dark"})
    # equipment module under a tarp behind the cab
    h.B(-1.25, 1.02, 0.55, 1.25, 1.2, 1.55, "armor")
    h.B(-1.25, 1.2, 0.6, 1.25, 2.85, 1.55, {"all": "tarp", "north": "tarp"})
    h.B(-1.27, 1.2, 0.6, 1.27, 1.32, 1.57, "armor_dark")
    for z in (0.75, 1.15):
        h.both(1.25, 1.32, z, 1.29, 2.75, z + 0.05, "armor_dark")
    # flat bed under the rack, side boards, rear panel with hazard stripes
    h.B(-1.25, 1.02, -3.7, 1.25, 1.2, 0.55, {"all": "armor", "up": ("armor", [("bolts", 0.45, 0.04)])})
    h.both(1.18, 1.2, -3.7, 1.25, 1.42, 0.55, "armor")
    h.B(-1.25, 1.2, -3.72, 1.25, 1.42, -3.65, {"all": "armor", "north": ("hazard", [("vstripe", 0.0, 0.08, (24, 24, 22)),
                                                                                   ("vstripe", 0.2, 0.28, (24, 24, 22)),
                                                                                   ("vstripe", 0.4, 0.48, (24, 24, 22)),
                                                                                   ("vstripe", 0.6, 0.68, (24, 24, 22)),
                                                                                   ("vstripe", 0.8, 0.88, (24, 24, 22))])})
    h.both(0.9, 1.2, -3.5, 1.2, 1.3, -0.4, "black")
    # rear mudflaps, lights, number plate, hitch, mudguards over the tandem
    h.both(0.85, 0.35, -3.72, 1.25, 1.02, -3.68, "rubber")
    h.both(0.85, 0.8, -3.73, 1.12, 0.95, -3.7, "light_red")
    h.B(-0.32, 0.65, -3.73, 0.32, 0.88, -3.7, ("steel", [("text", "А 077 ВС", 0.5, 0.5, 0.18, (30, 30, 30))]))
    h.B(-0.15, 0.6, -3.85, 0.15, 0.78, -3.65, "gunmetal")
    h.both(0.72, 1.02, -2.95, 1.32, 1.1, -0.35, "armor_dark")

    w = part(b, "truck_wheel", 3, center_y=True)
    r, wd = TRUCK_WHEEL_R, 0.42
    for rot in (None, ((0, 0, 0), "x", 45)):
        w.B(-wd / 2, -r, -r * 0.42, wd / 2, r, r * 0.42, {"all": "rubber", "east": "wheel", "west": "wheel"}, rot=rot)
        w.B(-wd / 2, -r * 0.42, -r, wd / 2, r * 0.42, r, {"all": "rubber", "east": "wheel", "west": "wheel"}, rot=rot)

    # launch rack: pivot at its front bottom, extends backwards (the renderer puts model z = 24 on the pivot)
    rk = part(b, "truck_rack", 3, offset=(0, 0, 3.0))
    for x1, x2 in ((-1.2, -1.0), (1.0, 1.2)):
        rk.B(x1, 0.0, -3.45, x2, 0.15, 0.0, "gunmetal")
        for z in (-3.45, -1.6, -0.19):
            rk.B(x1, 0.0, z, x2 - 0.05 if x1 > 0 else x2, 2.7, z + 0.19, "steel")
    for i in range(5):
        y = 0.15 + i * 0.5
        rk.B(-0.15, y, -3.45, 0.15, y + 0.075, 0.0, "steel")
        rk.B(-1.2, y, -0.19, 1.2, y + 0.075, 0.0, "hazard" if i == 0 else "gunmetal")
        rk.B(-1.2, y, -3.45, 1.2, y + 0.075, -3.3, "gunmetal")
    rk.B(-1.2, 2.62, -3.45, 1.2, 2.7, 0.0, {"all": "gunmetal", "up": "tarp"})
    finish(b)


# ---------------------------------------------------------------------------
# 30 mm remote weapon station on a pedestal (scale 2)

def build_adgun():
    b = Bake("adgun", MODID, dict(CAMO, mud_line=0.3), density=64)
    base = part(b, "adgun_base", 2)
    base.B(-0.68, 0, -0.68, 0.68, 0.08, 0.68, "armor_dark")
    base.B(-0.55, 0.08, -0.55, 0.55, 1.05, 0.55, {"all": "armor", "east": ("armor", [("rect", 0.2, 0.15, 0.8, 0.75, (60, 68, 44)),
                                                                                   ("bolts", 0.2, 0.1)]),
                                                  "up": "armor", "down": "armor_dark"})
    base.B(-0.6, 1.05, -0.6, 0.6, 1.12, 0.6, "armor_dark")
    base.B(-0.42, 1.12, -0.42, 0.42, 1.15, 0.42, "black")
    base.both(0.55, 0.45, -0.1, 0.59, 0.5, 0.1, "black")
    base.B(-0.1, 0.45, 0.55, 0.1, 0.5, 0.59, "black")
    for x in (-0.62, 0.58):
        base.B(x, 0.0, -0.62, x + 0.04, 0.12, -0.58, "steel")
        base.B(x, 0.0, 0.58, x + 0.04, 0.12, 0.62, "steel")

    tur = part(b, "adgun_turret", 2)
    tur.both(0.3, 0.02, -0.52, 0.76, 0.6, 0.32, {"all": "armor", "east": ("armor", [("bolts", 0.12, 0.08)]),
                                                 "west": ("armor", [("bolts", 0.12, 0.08)]), "down": "armor_dark"})
    tur.both(0.3, 0.06, 0.28, 0.66, 0.52, 0.48, "armor", rot=((0.48, 0.3, 0.38), "y", 22.5))
    tur.both(0.76, 0.12, -0.42, 0.8, 0.5, 0.2, ("armor_dark", [("rect", 0.1, 0.1, 0.9, 0.9, (44, 50, 34))]))
    tur.B(-0.3, 0.02, -0.52, 0.3, 0.2, 0.3, "armor_dark")
    tur.B(-0.3, 0.2, -0.52, 0.3, 0.62, -0.3, {"all": "armor", "north": ("armor", [("vent", 0.15, 0.2, 0.85, 0.8, 4)])})
    tur.B(-0.32, 0.02, -0.55, 0.32, 0.08, 0.35, "black")
    # optics block on a short mast, top left; laser/IR box on the right
    tur.B(0.46, 0.6, -0.05, 0.58, 0.72, 0.12, "gunmetal")
    tur.B(0.4, 0.72, -0.12, 0.66, 0.94, 0.22, {"all": "armor_dark", "south": ("periscope", [("rect", 0, 0.5, 0.5, 1, (30, 34, 28))]),
                                               "up": "armor_dark"})
    tur.B(0.42, 0.94, -0.05, 0.64, 0.97, 0.15, "black")
    tur.B(-0.62, 0.6, -0.2, -0.42, 0.72, 0.05, {"all": "armor_dark", "south": "lens_amber"})
    # ammo box hanging on the left side
    tur.B(0.8, 0.05, -0.35, 0.98, 0.42, 0.1, {"all": "armor_light", "east": ("armor_light", [("text", "30", 0.5, 0.5, 0.12, (30, 30, 24))])})

    gun = part(b, "adgun_gun", 2)
    gun.B(-0.17, -0.15, -0.5, 0.17, 0.15, 0.55, {"all": "armor", "south": "armor_dark",
                                                 "up": ("armor", [("bolts", 0.12, 0.15)])})
    gun.B(-0.12, 0.15, -0.35, 0.12, 0.22, 0.5, "armor_dark")
    gun.B(-0.2, -0.05, -0.1, -0.17, 0.08, 0.3, "black")
    gun.cyl(0, 0, 0.55, 0.088, 1.5, "z", "barrel_jacket")
    gun.cyl(0, 0, 2.05, 0.05, 0.3, "z", "gunmetal")
    gun.cyl(0, 0, 2.35, 0.085, 0.25, "z", {"all": "muzzle", "south": "black"})
    gun.B(-0.1, -0.1, 0.55, 0.1, 0.1, 0.68, "armor_dark")
    finish(b)


# ---------------------------------------------------------------------------
# Kornet on its tripod (scale 1)

def build_kornet():
    b = Bake("kornet", MODID, dict(GREEN, mud_line=0.15), density=128)
    tri = part(b, "kornet_tripod", 1)
    tri.B(-0.06, 0.45, -0.06, 0.06, 0.78, 0.06, "gunmetal")
    tri.B(-0.1, 0.62, -0.1, 0.1, 0.7, 0.1, "armor_dark")
    tri.B(-0.03, 0.0, 0.05, 0.03, 0.72, 0.11, "armor_dark", rot=((0, 0.7, 0.08), "x", -22.5))
    tri.B(0.05, 0.0, -0.03, 0.11, 0.72, 0.03, "armor_dark", rot=((0.08, 0.7, 0), "z", 22.5))
    tri.B(-0.11, 0.0, -0.03, -0.05, 0.72, 0.03, "armor_dark", rot=((-0.08, 0.7, 0), "z", -22.5))
    for x, z in ((0, 0.36), (0.36, 0), (-0.36, 0)):
        tri.B(x - 0.05, 0, z - 0.05, x + 0.05, 0.03, z + 0.05, "gunmetal")

    head = part(b, "kornet_head", 1)
    head.B(-0.1, 0, -0.12, 0.1, 0.06, 0.12, "armor_dark")
    head.both(0.08, 0.02, -0.04, 0.11, 0.17, 0.04, "armor_dark")

    sight = part(b, "kornet_sight", 1)
    sight.B(-0.07, -0.15, -0.3, 0.07, -0.09, 0.42, "gunmetal")
    sight.B(0.11, -0.1, -0.32, 0.33, 0.12, 0.16, {"all": "armor", "south": ("periscope", [("rect", 0, 0, 0.45, 1, (30, 34, 28))]),
                                                  "up": ("armor", [("bolts", 0.08, 0.15)]),
                                                  "east": ("armor", [("text", "1П45", 0.5, 0.5, 0.06, (220, 220, 210))])})
    sight.B(0.18, -0.03, -0.44, 0.27, 0.06, -0.32, "black")
    sight.B(0.15, 0.12, -0.2, 0.29, 0.16, 0.05, "armor_dark")
    sight.B(0.07, -0.08, -0.05, 0.11, 0.0, 0.05, "gunmetal")

    tube = part(b, "kornet_tube", 1)
    tube.cyl(0, 0, -0.45, 0.09, 1.2, "z", {"all": "armor_light", "south": ("armor_dark", [("ring", 0.5, 0.5, 0.06, (20, 20, 18), 0.02)]),
                                           "north": ("armor_dark", [("ring", 0.5, 0.5, 0.06, (20, 20, 18), 0.02)])})
    tube.B(-0.1, -0.1, 0.62, 0.1, 0.1, 0.68, "armor_dark")
    tube.B(-0.1, -0.1, -0.4, 0.1, 0.1, -0.34, "armor_dark")
    tube.B(-0.02, 0.09, 0.05, 0.02, 0.12, 0.3, "strap")
    finish(b)


# ---------------------------------------------------------------------------
# missiles in flight, tracer and the Javelin as held item

def build_atgm():
    b = Bake("atgm", MODID, dict(GREEN, mud_line=-5, dustiness=0.1), density=128)

    def missile(name, rad, length, body, nose, mid_fins=False, dome=False):
        p = part(b, name + "_flight", 1, center_y=True)
        half = length / 2
        p.cyl(0, 0, -half, rad, length - 0.15, "z", {"all": body, "north": "plume"})
        p.cyl(0, 0, half - 0.15, rad * 0.75, 0.15, "z", {"all": nose, "south": "m_dome" if dome else nose})
        span = rad * 2.4
        p.B(-span, -0.008, -half, span, 0.008, -half + 0.16, "m_fin")
        p.B(-0.008, -span, -half, 0.008, span, -half + 0.16, "m_fin")
        if mid_fins:
            p.B(-span * 0.8, -0.008, -0.05, span * 0.8, 0.008, 0.12, "m_fin")
            p.B(-0.008, -span * 0.8, -0.05, 0.008, span * 0.8, 0.12, "m_fin")
        else:
            p.B(-rad * 2, -0.006, half - 0.42, rad * 2, 0.006, half - 0.36, "m_fin")
        # rocket plume behind it
        p.B(-rad * 0.5, -rad * 0.5, -half - 0.35, rad * 0.5, rad * 0.5, -half, "plume")

    missile("kornet_missile", 0.075, 1.1, "m_kornet", "m_nose")
    missile("javelin_missile", 0.07, 1.0, "m_javelin", "m_nose", mid_fins=True, dome=True)

    t = part(b, "tracer", 1, center_y=True)
    t.B(-0.0375, -0.0375, -1.0, 0.0375, 0.0375, 1.0, "tracer")
    t.B(-0.02, -0.02, 0.25, 0.02, 0.02, 1.03, "tracer_core")

    jav = part(b, "javelin", 1, center_y=True)
    jav.cyl(0, 0, -0.62, 0.11, 1.24, "z", {"all": "tan", "south": "black", "north": "black"})
    jav.cyl(0, 0, 0.5, 0.13, 0.12, "z", "tan_dark")
    jav.cyl(0, 0, -0.62, 0.13, 0.12, "z", "tan_dark")
    jav.B(-0.3, -0.1, -0.05, -0.1, 0.12, 0.3, {"all": "armor_dark", "south": ("periscope", [("rect", 0, 0, 0.5, 1, (30, 32, 26))]),
                                               "north": ("black", [("rect", 0.15, 0.2, 0.85, 0.7, (60, 90, 70))])})
    jav.B(-0.26, 0.12, 0.0, -0.14, 0.16, 0.25, "gunmetal")
    jav.B(-0.02, -0.26, 0.02, 0.04, -0.11, 0.1, "black")
    jav.B(-0.02, 0.11, -0.4, 0.02, 0.13, 0.4, "strap")
    finish(b, display={"javelin": {
        "gui": {"rotation": [25, 225, 0], "translation": [0, 0, 0], "scale": [0.75] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5] * 3},
        "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.7] * 3},
        "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.7] * 3},
        "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 6, 2], "scale": [0.8] * 3},
        "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 6, 2], "scale": [0.8] * 3},
        "firstperson_righthand": {"rotation": [0, 0, 0], "translation": [2, 2, -2], "scale": [0.8] * 3},
        "firstperson_lefthand": {"rotation": [0, 0, 0], "translation": [-2, 2, -2], "scale": [0.8] * 3},
    }})


# ---------------------------------------------------------------------------
# item icons rendered from the models

ICON = 64


def finish_icon(src, dst):
    im = Image.open(src).convert("RGBA").resize((ICON, ICON), Image.LANCZOS)
    a = np.asarray(im).astype(np.float64)
    alpha = (a[..., 3] > 90).astype(np.float64)
    rgb = np.asarray(im.convert("RGB").filter(ImageFilter.UnsharpMask(1.2, 60, 2))).astype(np.float64)
    grown = alpha.copy()
    grown[1:] = np.maximum(grown[1:], alpha[:-1])
    grown[:-1] = np.maximum(grown[:-1], alpha[1:])
    grown[:, 1:] = np.maximum(grown[:, 1:], alpha[:, :-1])
    grown[:, :-1] = np.maximum(grown[:, :-1], alpha[:, 1:])
    edge = (grown > 0) & (alpha == 0)
    out = np.zeros((ICON, ICON, 4))
    out[..., :3] = rgb
    out[..., 3] = alpha * 255
    out[edge] = (24, 26, 22, 255)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    Image.fromarray(out.astype(np.uint8), "RGBA").save(dst)


def icon(name, sc, yaw, pitch):
    os.makedirs(TMP, exist_ok=True)
    tmp = os.path.join(TMP, name + ".png")
    sc.render(tmp, yaw=yaw, pitch=pitch, scale=None, size=(ICON * 4, ICON * 4), ground=False, transparent=True)
    finish_icon(tmp, os.path.join(ASSETS, "textures", "item", name + ".png"))
    save_json({"parent": "item/generated", "textures": {"layer0": f"{MODID}:item/{name}"}},
              os.path.join(ASSETS, "models", "item", name + ".json"))


def ref(name):
    return MODID + ":entity/" + name


def icons():
    sc = Scene(RES)
    sc.part(ref("truck"))
    for x, z in TRUCK_WHEELS:
        sc.part(ref("truck_wheel"), T(x, TRUCK_WHEEL_R, z))
    sc.part(ref("truck_rack"), T(*TRUCK_RACK_PIVOT) @ R("x", 22.5))
    icon("launch_truck", sc, 40, 28)

    sc = Scene(RES)
    sc.part(ref("adgun_base"))
    Mt = T(0, 1.15, 0) @ R("y", -20)
    sc.part(ref("adgun_turret"), Mt)
    sc.part(ref("adgun_gun"), Mt @ T(-0.05, 0.42, 0.15) @ R("x", -25))
    icon("air_defense_gun", sc, 35, 22)

    sc = Scene(RES)
    sc.part(ref("kornet_tripod"))
    Mh = T(0, 0.78, 0)
    sc.part(ref("kornet_head"), Mh)
    sc.part(ref("kornet_sight"), Mh @ T(0, 0.14, 0) @ R("x", -8))
    sc.part(ref("kornet_tube"), Mh @ T(0, 0.14, 0) @ R("x", -8))
    icon("kornet", sc, 35, 22)

    for name in ("kornet_missile", "javelin_missile"):
        sc = Scene(RES)
        sc.part(ref(name + "_flight"), R("x", -40))
        icon(name, sc, 90, 10)
    shutil.rmtree(TMP, ignore_errors=True)


def main():
    build_truck()
    build_adgun()
    build_kornet()
    build_atgm()
    icons()


if __name__ == "__main__":
    main()
