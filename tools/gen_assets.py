#!/usr/bin/env python3
# Generates models, textures, sounds, lang and recipes for the mod.
# usage: python3 tools/gen_assets.py src/main/resources
# sounds need numpy + ffmpeg with libvorbis, everything else only Pillow
import json
import math
import os
import random
import shutil
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw

MODID = "fpvdrones"
RES = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources"
ASSETS = os.path.join(RES, "assets", MODID)
DATA = os.path.join(RES, "data", MODID)


def path(*p):
    full = os.path.join(*p)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    return full


def save_json(obj, *p):
    with open(path(*p), "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)


def hexc(h, a=255):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a


def shade(c, k):
    return (max(0, min(255, int(c[0] * k))), max(0, min(255, int(c[1] * k))),
            max(0, min(255, int(c[2] * k))), c[3])


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4))


# ---------------------------------------------------------------------------
# swatch atlases: every face of an element maps onto one 16x16 swatch

CELL = 16
GRID = 8
UV_CELL = 16.0 / GRID


class Atlas:
    def __init__(self, name):
        self.name = name
        self.img = Image.new("RGBA", (CELL * GRID, CELL * GRID), (0, 0, 0, 0))
        self.cells = {}
        self.rng = random.Random(sum(map(ord, name)))

    def add(self, key, painter):
        idx = len(self.cells)
        assert idx < GRID * GRID, self.name
        cx, cy = idx % GRID, idx // GRID
        tile = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
        painter(tile, self.rng)
        self.img.paste(tile, (cx * CELL, cy * CELL))
        self.cells[key] = (cx, cy)

    def uv(self, key):
        cx, cy = self.cells[key]
        e = 0.06
        return [round(cx * UV_CELL + e, 3), round(cy * UV_CELL + e, 3),
                round((cx + 1) * UV_CELL - e, 3), round((cy + 1) * UV_CELL - e, 3)]

    def ref(self):
        return f"{MODID}:entity/{self.name}"

    def save(self):
        self.img.save(path(ASSETS, "textures", "entity", self.name + ".png"))


def p_flat(base, noise=7, border=0.72, light=1.10, alpha=255):
    base = hexc(base, alpha)

    def paint(t, rng):
        px = t.load()
        for y in range(CELL):
            for x in range(CELL):
                n = rng.randint(-noise, noise)
                c = (base[0] + n, base[1] + n, base[2] + n, base[3])
                c = shade(c, 1.0 + (light - 1.0) * (1 - y / (CELL - 1)))
                if x in (0, CELL - 1) or y in (0, CELL - 1):
                    c = shade(c, border)
                elif x == 1 or y == 1:
                    c = shade(c, 1.08)
                px[x, y] = c
    return paint


def p_carbon(c1="#16171a", c2="#222328", edge=0.55):
    a, b = hexc(c1), hexc(c2)

    def paint(t, rng):
        px = t.load()
        for y in range(CELL):
            for x in range(CELL):
                c = a if ((x // 2) + (y // 2)) % 2 else b
                if (x + y) % 4 == 0:
                    c = shade(c, 1.12)
                if abs((x - y) - 3) < 2:
                    c = shade(c, 1.2)
                if x in (0, CELL - 1) or y in (0, CELL - 1):
                    c = shade(c, edge)
                px[x, y] = c
    return paint


def p_carbon_cut():
    """top plate with the long weight-saving slots"""
    def paint(t, rng):
        p_carbon()(t, rng)
        d = ImageDraw.Draw(t)
        d.rectangle([3, 3, 6, 12], fill=hexc("#060607"))
        d.rectangle([9, 3, 12, 12], fill=hexc("#060607"))
        d.line([3, 3, 6, 3], fill=hexc("#3a3c42"))
        d.line([9, 3, 12, 3], fill=hexc("#3a3c42"))
    return paint


def p_lens(rim="#151515", glass="#2a2f7a", glint="#a9d4ff", bg="#1a1b1e"):
    rim, glass, glint, bg = hexc(rim), hexc(glass), hexc(glint), hexc(bg)

    def paint(t, rng):
        px = t.load()
        c0 = (CELL - 1) / 2
        for y in range(CELL):
            for x in range(CELL):
                r = math.hypot(x - c0, y - c0)
                if r > 7.2:
                    c = bg
                elif r > 5.6:
                    c = rim
                elif r > 4.6:
                    c = shade(rim, 1.6)
                else:
                    c = mix(shade(glass, 1.5), shade(glass, 0.55), r / 4.6)
                    if 1 < r < 2.4 and x < c0 and y < c0:
                        c = glint
                if x in (0, CELL - 1) or y in (0, CELL - 1):
                    c = shade(bg, 0.7)
                px[x, y] = c
    return paint


def p_stripes(c1, c2, width=2, vertical=False, border=0.7):
    a, b = hexc(c1), hexc(c2)

    def paint(t, rng):
        px = t.load()
        for y in range(CELL):
            for x in range(CELL):
                k = x if vertical else y
                c = a if (k // width) % 2 == 0 else b
                n = rng.randint(-5, 5)
                c = tuple(max(0, min(255, v + n)) for v in c[:3]) + (255,)
                if x in (0, CELL - 1) or y in (0, CELL - 1):
                    c = shade(c, border)
                px[x, y] = c
    return paint


def p_hazard(c1="#f2c200", c2="#1b1b1b"):
    a, b = hexc(c1), hexc(c2)

    def paint(t, rng):
        px = t.load()
        for y in range(CELL):
            for x in range(CELL):
                c = a if ((x + y) // 4) % 2 == 0 else b
                if x in (0, CELL - 1) or y in (0, CELL - 1):
                    c = shade(c, 0.7)
                px[x, y] = c
    return paint


def p_pcb(base="#141815"):
    def paint(t, rng):
        d = ImageDraw.Draw(t)
        d.rectangle([0, 0, 15, 15], fill=hexc(base))
        for _ in range(8):
            x, y = rng.randint(1, 14), rng.randint(1, 14)
            d.line([x, y, x + rng.randint(-4, 4), y], fill=hexc("#2b332d"))
        d.rectangle([5, 5, 10, 10], fill=hexc("#0b0b0b"))
        for i in range(2, 14, 3):
            d.point([i, 2], fill=hexc("#b89a42"))
            d.point([i, 13], fill=hexc("#b89a42"))
        d.point([12, 6], fill=hexc("#ff4040"))
        d.point([3, 9], fill=hexc("#3b8bff"))
    return paint


def p_vent(base, slot):
    slot_c = hexc(slot)

    def paint(t, rng):
        p_flat(base, 4)(t, rng)
        d = ImageDraw.Draw(t)
        for y in range(3, 13, 2):
            d.line([3, y, 12, y], fill=slot_c)
    return paint


def p_motor_top(bell, cap="#9aa0a8"):
    an = hexc(bell)

    def paint(t, rng):
        px = t.load()
        c0 = 7.5
        for y in range(CELL):
            for x in range(CELL):
                r = math.hypot(x - c0, y - c0)
                c = shade(an, 1.0 + 0.25 * math.cos(math.atan2(y - c0, x - c0) * 3))
                if r < 2.2:
                    c = hexc(cap)
                elif r < 3.0:
                    c = hexc("#111111")
                if r > 7.3:
                    c = shade(an, 0.5)
                px[x, y] = c
    return paint


def p_motor_side(bell="#1c1c1f", ring="#4a4c52"):
    def paint(t, rng):
        d = ImageDraw.Draw(t)
        d.rectangle([0, 0, 15, 15], fill=hexc(bell))
        for x in range(1, 15, 2):
            d.line([x, 6, x, 11], fill=hexc("#2a2b2f"))
        d.rectangle([0, 0, 15, 2], fill=hexc(ring))
        d.text((3, 3), "2812", fill=hexc("#6a6d74"))
        d.rectangle([0, 0, 15, 15], outline=hexc("#0a0a0b"))
    return paint


def p_battery():
    def paint(t, rng):
        d = ImageDraw.Draw(t)
        d.rectangle([0, 0, 15, 15], fill=hexc("#17181b"))
        d.rectangle([0, 6, 15, 10], fill=hexc("#e6b800"))
        d.text((2, 5), "6S", fill=hexc("#17181b"))
        d.rectangle([0, 0, 15, 15], outline=hexc("#08080a"))
    return paint


def p_label(base, band, mark):
    def paint(t, rng):
        p_flat(base, 5)(t, rng)
        d = ImageDraw.Draw(t)
        d.rectangle([0, 6, 15, 9], fill=hexc(band))
        d.line([3, 11, 12, 11], fill=hexc(mark))
        d.line([3, 13, 9, 13], fill=hexc(mark))
    return paint


def p_panel_lines(base, line):
    def paint(t, rng):
        p_flat(base, 5, border=0.6)(t, rng)
        d = ImageDraw.Draw(t)
        d.line([0, 5, 15, 5], fill=hexc(line))
        d.line([8, 5, 8, 15], fill=hexc(line))
        for p in [(2, 2), (13, 2), (2, 12), (13, 12)]:
            d.point(p, fill=shade(hexc(base), 1.4))
    return paint


def p_sensor(base):
    def paint(t, rng):
        p_flat(base, 3)(t, rng)
        d = ImageDraw.Draw(t)
        d.ellipse([2, 5, 6, 9], fill=hexc("#0b0b10"), outline=hexc("#3a3a46"))
        d.ellipse([9, 5, 13, 9], fill=hexc("#0b0b10"), outline=hexc("#3a3a46"))
        d.point([3, 6], fill=hexc("#7f8dff"))
        d.point([10, 6], fill=hexc("#7f8dff"))
    return paint


def p_led(color, base="#2a2b2e"):
    def paint(t, rng):
        p_flat(base, 3)(t, rng)
        d = ImageDraw.Draw(t)
        d.rectangle([4, 4, 11, 11], fill=hexc(color))
        d.rectangle([6, 6, 9, 9], fill=shade(hexc(color), 1.5))
    return paint


def p_tape():
    def paint(t, rng):
        px = t.load()
        for y in range(CELL):
            for x in range(CELL):
                g = 150 + rng.randint(-14, 14) + (12 if (x * 3 + y) % 7 == 0 else 0)
                px[x, y] = (g, g + 2, g + 6, 255)
        d = ImageDraw.Draw(t)
        d.line([0, 0, 15, 0], fill=(95, 98, 104, 255))
        d.line([0, 15, 15, 15], fill=(95, 98, 104, 255))
    return paint


def p_print(color, layer=0.85):
    def paint(t, rng):
        c = hexc(color)
        px = t.load()
        for y in range(CELL):
            for x in range(CELL):
                k = 1.08 if y % 2 == 0 else layer
                px[x, y] = shade(c, k + rng.uniform(-0.03, 0.03))
        ImageDraw.Draw(t).rectangle([0, 0, 15, 15], outline=shade(c, 0.6))
    return paint


def p_glass(base="#1a2630"):
    def paint(t, rng):
        px = t.load()
        b = hexc(base)
        for y in range(CELL):
            for x in range(CELL):
                c = shade(b, 1.0 + 0.4 * (1 - y / 15))
                if abs(x - y - 4) < 2 or abs(x - y + 3) < 1:
                    c = shade(c, 1.6)
                px[x, y] = c
        ImageDraw.Draw(t).rectangle([0, 0, 15, 15], outline=hexc("#0c0f12"))
    return paint


def p_window_split(body, glass="#1d2a33", glass_rows=8):
    """upper part glass, lower part painted body"""
    def paint(t, rng):
        p_flat(body, 6)(t, rng)
        d = ImageDraw.Draw(t)
        d.rectangle([1, 1, 14, glass_rows], fill=hexc(glass))
        d.line([2, 2, 6, 2], fill=hexc("#4e6574"))
        d.line([2, 3, 4, 3], fill=hexc("#4e6574"))
        d.rectangle([1, 1, 14, glass_rows], outline=hexc("#121212"))
    return paint


def p_cab_front(body):
    def paint(t, rng):
        p_window_split(body, glass_rows=7)(t, rng)
        d = ImageDraw.Draw(t)
        d.line([7, 1, 8, 7], fill=hexc("#121212"))
        for y in range(10, 15, 2):
            d.line([3, y, 12, y], fill=hexc("#22261a"))
    return paint


def p_tire():
    def paint(t, rng):
        px = t.load()
        for y in range(CELL):
            for x in range(CELL):
                g = 26 + rng.randint(-4, 4)
                if (x + (y // 3) * 2) % 6 < 2:
                    g += 14
                px[x, y] = (g, g, g + 2, 255)
    return paint


def p_hub(base):
    def paint(t, rng):
        p_flat(base, 5)(t, rng)
        d = ImageDraw.Draw(t)
        d.ellipse([3, 3, 12, 12], outline=shade(hexc(base), 0.6))
        for a in range(0, 360, 60):
            x = 7.5 + 3 * math.cos(math.radians(a))
            y = 7.5 + 3 * math.sin(math.radians(a))
            d.point((round(x), round(y)), fill=hexc("#a8a89a"))
        d.rectangle([6, 6, 9, 9], fill=shade(hexc(base), 0.7))
    return paint


def p_camo(c1, c2, c3):
    cols = [hexc(c1), hexc(c2), hexc(c3)]

    def paint(t, rng):
        px = t.load()
        seeds = [(rng.randint(0, 15), rng.randint(0, 15), rng.randint(1, 2)) for _ in range(5)]
        for y in range(CELL):
            for x in range(CELL):
                c = cols[0]
                for i, (sx, sy, ci) in enumerate(seeds):
                    if (x - sx) ** 2 * 0.6 + (y - sy) ** 2 < 10 + i * 3:
                        c = cols[ci]
                n = rng.randint(-5, 5)
                c = (c[0] + n, c[1] + n, c[2] + n, 255)
                if x in (0, 15) or y in (0, 15):
                    c = shade(c, 0.75)
                px[x, y] = tuple(max(0, min(255, v)) for v in c)
    return paint


# ---------------------------------------------------------------------------
# element models

DIRS = ["north", "south", "east", "west", "up", "down"]


class Model:
    def __init__(self, atlas, key="t"):
        self.atlas = atlas
        self.key = key
        self.elements = []
        self.extra_textures = {}

    def box(self, fr, to, faces, rot=None, atlas=None, key=None):
        atlas = atlas or self.atlas
        key = key or self.key
        if isinstance(faces, str):
            faces = {"all": faces}
        for v in fr + to:
            assert -16 <= v <= 32, (fr, to)
        e = {"from": [round(v, 3) for v in fr], "to": [round(v, 3) for v in to], "faces": {}}
        for d in DIRS:
            sw = faces.get(d, faces.get("all"))
            if sw is not None:
                e["faces"][d] = {"uv": atlas.uv(sw), "texture": "#" + key}
        if rot is not None:
            origin, axis, angle = rot
            assert angle in (-45, -22.5, 0, 22.5, 45)
            e["rotation"] = {"origin": [round(v, 3) for v in origin], "axis": axis, "angle": angle}
        self.elements.append(e)
        return self

    def json(self, display=None):
        textures = {self.key: self.atlas.ref(), "particle": self.atlas.ref()}
        textures.update(self.extra_textures)
        obj = {"ambientocclusion": False, "textures": textures, "elements": self.elements}
        if display:
            obj["display"] = display
        return obj

    def save(self, name, display=None, folder="entity"):
        save_json(self.json(display), ASSETS, "models", folder, name + ".json")


def display(gui, gui_rot=(30, 225, 0), ground=0.4, hand=0.45, fixed=0.6, gui_tr=(0, 0, 0), hand_tr=(0, 2.5, 0)):
    return {
        "gui": {"rotation": list(gui_rot), "translation": list(gui_tr), "scale": [gui] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [ground] * 3},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [fixed] * 3},
        "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [fixed] * 3},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": list(hand_tr), "scale": [hand] * 3},
        "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": list(hand_tr), "scale": [hand] * 3},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [hand] * 3},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [hand] * 3},
    }


# ---------------------------------------------------------------------------
# propeller

def build_props():
    a = Atlas("propeller")
    a.add("blade", p_flat("#141518", 3, border=0.5, light=1.25))
    a.add("hub", p_flat("#2b2d31", 4))
    a.add("nut", p_flat("#9aa0a8", 6))
    a.save()
    m = Model(a)
    # two blades with a slight pitch, tips bent back like on the photo
    m.box([1.5, 7.85, 7.4], [7, 8.15, 8.6], "blade", rot=([8, 8, 8], "x", 22.5))
    m.box([9, 7.85, 7.4], [14.5, 8.15, 8.6], "blade", rot=([8, 8, 8], "x", -22.5))
    m.box([0.3, 7.9, 7.0], [1.8, 8.1, 8.3], "blade")
    m.box([14.2, 7.9, 7.7], [15.7, 8.1, 9.0], "blade")
    m.box([7, 7.6, 7], [9, 8.6, 9], "hub")
    m.box([7.6, 8.6, 7.6], [8.4, 9.0, 8.4], "nut")
    m.save("propeller")

    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    c = (size - 1) / 2
    for y in range(size):
        for x in range(size):
            r = math.hypot(x - c, y - c) / c
            if r <= 1.0:
                alpha = int(70 * (1 - r ** 3)) + (25 if r > 0.93 else 0)
                g = 30 + int(25 * r)
                px[x, y] = (g, g, g + 4, alpha)
    img.save(path(ASSETS, "textures", "entity", "prop_blur.png"))
    save_json({
        "ambientocclusion": False,
        "textures": {"t": f"{MODID}:entity/prop_blur", "particle": f"{MODID}:entity/prop_blur"},
        "elements": [{"from": [0, 8, 0], "to": [16, 8, 16], "shade": False, "faces": {
            "up": {"uv": [0, 0, 16, 16], "texture": "#t"},
            "down": {"uv": [0, 0, 16, 16], "texture": "#t"}}}]
    }, ASSETS, "models", "entity", "prop_blur.json")
    return a


def add_static_props(model, prop_atlas, motors, scale, height):
    """copies of the propeller for item icons (entities draw spinning ones)"""
    model.extra_textures["p"] = prop_atlas.ref()
    for i, (x, z) in enumerate(motors):
        half = 7.5 * scale
        ang = 45 if i % 2 == 0 else -45
        model.box([x - half, height, z - 0.55 * scale * 2], [x + half, height + 0.3, z + 0.55 * scale * 2],
                  "blade", rot=([x, height, z], "y", ang), atlas=prop_atlas, key="p")


# ---------------------------------------------------------------------------
# FPV: 7" stretched-X, all black, tall VTX antenna at the back

FPV_MOTORS = [(2, 2), (14, 2), (2, 14), (14, 14)]
FPV_FRAME_BOTTOM = 6.5


def build_fpv(prop_atlas):
    a = Atlas("fpv_drone")
    a.add("carbon", p_carbon())
    a.add("carbon_cut", p_carbon_cut())
    a.add("carbon_edge", p_carbon("#0f1012", "#18191c", 0.4))
    a.add("motor_top", p_motor_top("#1b1c1f", "#5c6068"))
    a.add("motor_side", p_motor_side())
    a.add("stack", p_pcb())
    a.add("standoff", p_flat("#0f0f10", 3))
    a.add("cam", p_flat("#141416", 3))
    a.add("lens", p_lens(bg="#141416"))
    a.add("antenna", p_flat("#111113", 2))
    a.add("antenna_head", p_flat("#1e1f23", 4, light=1.3))
    a.add("red", p_flat("#c4161c", 10, light=1.25))
    a.add("xt60", p_flat("#d6a816", 8))
    a.add("nut", p_flat("#8d939b", 6))
    a.save()

    m = Model(a)
    b = FPV_FRAME_BOTTOM
    m.box([6.4, b, 2.6], [9.6, b + 0.5, 13.6], {"all": "carbon_edge", "up": "carbon", "down": "carbon"})
    for ang in (45, -45):
        m.box([-0.5, b + 0.05, 7.15], [16.5, b + 0.65, 8.85], {"all": "carbon_edge", "up": "carbon", "down": "carbon"},
              rot=([8, b + 0.35, 8], "y", ang))
    for x, z in FPV_MOTORS:
        m.box([x - 1.2, b + 0.05, z - 1.2], [x + 1.2, b + 0.65, z + 1.2], {"all": "carbon_edge", "up": "carbon"})
        m.box([x - 1.05, b + 0.65, z - 1.05], [x + 1.05, b + 2.05, z + 1.05], {"all": "motor_side", "up": "motor_top"})
        m.box([x - 0.3, b + 2.05, z - 0.3], [x + 0.3, b + 2.5, z + 0.3], "nut")
    for x in (6.6, 9.0):
        for z in (4.0, 8.0):
            m.box([x, b + 0.5, z], [x + 0.4, b + 2.9, z + 0.4], "standoff")
    m.box([6.9, b + 0.5, 5.4], [9.1, b + 2.0, 10.4], "stack")
    m.box([6.4, b + 2.9, 3.0], [9.6, b + 3.3, 7.6], {"all": "carbon_edge", "up": "carbon_cut", "down": "carbon"})
    m.box([6.4, b + 2.9, 8.2], [9.6, b + 3.3, 12.4], {"all": "carbon_edge", "up": "carbon_cut", "down": "carbon"})
    m.box([6.25, b + 0.5, 10.6], [6.65, b + 3.1, 14.2], {"all": "carbon_edge", "east": "carbon", "west": "carbon"})
    m.box([9.35, b + 0.5, 10.6], [9.75, b + 3.1, 14.2], {"all": "carbon_edge", "east": "carbon", "west": "carbon"})
    m.box([6.7, b + 0.9, 11.6], [9.3, b + 2.7, 13.9], {"all": "cam", "south": "lens"},
          rot=([8, b + 1.8, 12.75], "x", -22.5))
    # power lead looping over the top plate
    m.box([7.55, b + 3.3, 3.4], [8.45, b + 4.0, 6.8], "red")
    m.box([7.55, b + 3.3, 6.8], [8.45, b + 3.7, 9.5], "red")
    m.box([7.3, b + 3.3, 2.4], [8.7, b + 4.2, 3.4], "xt60")
    # VTX antenna: thin mast tilted back with the square patch on top
    r = ([8, b + 3.3, 3.6], "x", -22.5)
    m.box([7.8, b + 3.3, 3.35], [8.2, b + 10.6, 3.85], "antenna", rot=r)
    m.box([7.35, b + 10.6, 3.15], [8.65, b + 12.2, 4.05], {"all": "antenna", "south": "antenna_head",
                                                          "north": "antenna_head"}, rot=r)
    m.save("fpv_drone")

    icon = Model(a)
    icon.elements = list(m.elements)
    add_static_props(icon, prop_atlas, FPV_MOTORS, 0.5, b + 2.6)
    icon.save("fpv_drone", display(0.85, ground=0.5, hand=0.5, fixed=0.85), folder="item")


# ---------------------------------------------------------------------------
# Mavic 3

MAVIC_MOTORS = [(2.4, 12.85), (13.6, 12.85), (2.4, 3.36), (13.6, 3.36)]


def build_mavic(prop_atlas):
    a = Atlas("mavic3")
    a.add("grey", p_flat("#8d9196", 5, light=1.12))
    a.add("grey_top", p_panel_lines("#9a9ea4", "#6f7378"))
    a.add("dark", p_flat("#3a3c40", 4))
    a.add("darker", p_flat("#232427", 3))
    a.add("sensor", p_sensor("#2b2c30"))
    a.add("lens_big", p_lens(rim="#101012", glass="#3b2d6e", glint="#d7c2ff", bg="#2b2c30"))
    a.add("vent", p_vent("#7d8186", "#4a4d51"))
    a.add("motor_top", p_motor_top("#6f7277"))
    a.add("motor_side", p_flat("#2a2b2f", 4))
    a.add("led_r", p_led("#ff2b2b"))
    a.add("led_g", p_led("#21ff5a"))
    a.add("dropper", p_print("#e8e4da"))
    a.add("servo", p_flat("#1d4fb0", 6))
    a.save()

    m = Model(a)
    m.box([6, 7, 3.5], [10, 9, 12.5], {"all": "grey", "east": "vent", "west": "vent"})
    m.box([6.5, 9, 4.5], [9.5, 9.6, 11], {"all": "grey", "up": "grey_top"})
    m.box([6.5, 7.3, 12.5], [9.5, 8.8, 13.3], {"all": "dark", "south": "sensor"})
    m.box([6.3, 7.3, 2.8], [9.7, 8.9, 3.5], {"all": "dark", "north": "sensor"})
    m.box([7.6, 6.9, 12.2], [8.4, 7.4, 12.9], "darker")
    m.box([7, 6.0, 12.6], [9, 7.2, 14.2], {"all": "dark", "south": "lens_big"})
    arms = [((4.4, 8.4, 12.0), 22.5, 0.6), ((11.6, 8.4, 12.0), -22.5, 0.6),
            ((4.4, 7.65, 4.2), -22.5, 0.5), ((11.6, 7.65, 4.2), 22.5, 0.5)]
    for (cx, cy, cz), ang, th in arms:
        m.box([cx - 2.2, cy - th / 2, cz - 0.45], [cx + 2.2, cy + th / 2, cz + 0.45], "grey",
              rot=([cx, cy, cz], "y", ang))
    motor_y = [8.0, 8.0, 7.3, 7.3]
    for (x, z), y in zip(MAVIC_MOTORS, motor_y):
        m.box([x - 0.8, y, z - 0.8], [x + 0.8, y + 1.2, z + 0.8], {"all": "motor_side", "up": "motor_top"})
    for (x, z) in MAVIC_MOTORS[:2]:
        m.box([x - 0.25, 6.4, z - 0.25], [x + 0.25, 8.0, z + 0.25], "dark")
        m.box([x - 0.35, 7.6, z + 0.6], [x + 0.35, 7.9, z + 0.85], "led_r" if x < 8 else "led_g")
    # printed drop system, one hook per side
    m.box([6.6, 6.75, 6.2], [9.4, 7.0, 9.8], "dropper")
    m.box([7.6, 6.5, 7.4], [8.4, 6.75, 8.6], "servo")
    for x in (6.1, 8.9):
        m.box([x, 6.3, 7.3], [x + 1.0, 6.75, 8.7], "dropper")
    m.save("mavic3")

    icon = Model(a)
    icon.elements = list(m.elements)
    add_static_props(icon, prop_atlas, MAVIC_MOTORS, 0.32, 9.3)
    icon.save("mavic3", display(1.0, ground=0.55, hand=0.6, fixed=1.0), folder="item")


# ---------------------------------------------------------------------------
# Shahed-136 / Geran-2 share the airframe, Geran-5 is the jet one

SHAHED_PALETTES = {
    "shahed136": dict(hull="#3a3d42", top=("#40444a", "#26282b"), wing=("#44474d", "#2a2c30"),
                      under="#2a2b2e", band=("#bfa34a", "#9c8436"), mark="#cfcfcf"),
    "geran2": dict(hull="#17181a", top=("#1c1d20", "#0c0c0d"), wing=("#1f2023", "#0e0e10"),
                   under="#141416", band=("#d8d8d8", "#b4b4b4"), mark="#e6e6e6"),
}


def build_shahed(name, pal):
    a = Atlas(name)
    a.add("hull", p_flat(pal["hull"], 6, light=1.15))
    a.add("hull_top", p_panel_lines(*pal["top"]))
    a.add("wing", p_panel_lines(*pal["wing"]))
    a.add("wing_under", p_flat(pal["under"], 5))
    a.add("nose", p_flat("#232427", 4))
    a.add("tip", p_flat("#5a5d61", 6))
    a.add("band", p_stripes(*pal["band"], 2))
    a.add("engine", p_vent("#4b4e53", "#26282b"))
    a.add("exhaust", p_flat("#5b3a26", 12))
    a.add("hub", p_flat("#1b1c1e", 3))
    a.add("gnss", p_flat("#d9d9d4", 6))
    a.add("winglet", p_flat(pal["under"], 5))
    a.add("stencil", p_label(pal["hull"], pal["hull"], pal["mark"]))
    a.save()

    m = Model(a)
    m.box([6.25, 6.5, -6], [9.75, 10, 26], {"all": "hull", "up": "hull_top", "east": "stencil", "west": "stencil"})
    m.box([6.15, 6.4, 20], [9.85, 10.1, 20.6], "band")
    m.box([6.75, 7.0, 26], [9.25, 9.5, 28], "nose")
    m.box([7.4, 7.6, 28], [8.6, 8.9, 29.2], "tip")
    for z1, z2, x1, x2 in [(-6, -1, -8, 24), (-1, 4, -5, 21), (4, 9, -1.5, 17.5), (9, 13, 2, 14), (13, 16.5, 5, 11)]:
        m.box([x1, 7.4, z1], [x2, 8.0, z2], {"all": "wing", "down": "wing_under"})
    m.box([-8.6, 5, -6], [-7.8, 11, -0.5], "winglet")
    m.box([23.8, 5, -6], [24.6, 11, -0.5], "winglet")
    m.box([6.5, 10, -6], [9.5, 11.2, 1], "engine")
    m.box([9.75, 8, -4], [10.5, 8.8, -0.5], "exhaust")
    m.box([5.5, 8, -4], [6.25, 8.8, -0.5], "exhaust")
    m.box([7.25, 7.5, -7.5], [8.75, 9, -6], "hub")
    m.box([7.5, 10, 16], [8.5, 10.5, 19], "gnss")
    m.save(name)
    return m


def build_geran5():
    a = Atlas("geran5")
    a.add("hull", p_flat("#2c2f33", 6, light=1.18))
    a.add("hull_top", p_panel_lines("#33363b", "#1b1d20"))
    a.add("wing", p_panel_lines("#30333a", "#1c1e22"))
    a.add("wing_under", p_flat("#24262a", 4))
    a.add("nose", p_flat("#1e2023", 4, light=1.3))
    a.add("intake", p_flat("#08090a", 2))
    a.add("nacelle", p_vent("#3a3d43", "#1c1e21"))
    a.add("nozzle", p_flat("#4a4038", 10))
    a.add("glow", p_flat("#ff8a2a", 20, border=0.9, light=1.4))
    a.add("band", p_stripes("#d0d0d0", "#a8a8a8", 2))
    a.add("stencil", p_label("#2c2f33", "#2c2f33", "#dcdcdc"))
    a.save()

    m = Model(a)
    m.box([6.3, 6.5, -9], [9.7, 9.9, 24], {"all": "hull", "up": "hull_top", "east": "stencil", "west": "stencil"})
    m.box([6.8, 7.0, 24], [9.2, 9.4, 27], "nose")
    m.box([7.4, 7.6, 27], [8.6, 8.8, 28.6], "nose")
    m.box([6.2, 6.4, 18], [9.8, 10.0, 18.6], "band")
    # swept main wing, stepped
    for z1, z2, x1, x2 in [(2, 6, -9, 25), (6, 9, -5, 21), (9, 12, -1, 17), (12, 14, 3, 13)]:
        m.box([x1, 7.3, z1], [x2, 7.9, z2], {"all": "wing", "down": "wing_under"})
    # V-tail
    m.box([8.6, 9.6, -9], [13.4, 10.1, -5], "wing", rot=([9.0, 9.8, -7], "z", -45))
    m.box([2.6, 9.6, -9], [7.4, 10.1, -5], "wing", rot=([7.0, 9.8, -7], "z", 45))
    # dorsal turbojet
    m.box([6.6, 9.9, -10], [9.4, 12.4, 4], {"all": "nacelle", "south": "intake"})
    m.box([6.9, 10.2, -11.2], [9.1, 12.1, -10], {"all": "nozzle", "north": "glow"})
    m.box([7.6, 9.9, 4], [8.4, 10.6, 8], "hull")
    m.save("geran5")
    return m


# ---------------------------------------------------------------------------
# warheads and drops

def build_payloads():
    a = Atlas("payloads")
    a.add("steel", p_flat("#7c8288", 6, light=1.2))
    a.add("steel_dark", p_flat("#4d5156", 5))
    a.add("fin", p_flat("#3e4247", 4))
    a.add("pg_body", p_flat("#2f3328", 6))
    a.add("pg_band", p_stripes("#c9b23f", "#a8932f", 2))
    a.add("pg_cone", p_flat("#262a21", 5, light=1.25))
    a.add("brass", p_flat("#b8963a", 10, light=1.3))
    a.add("og_body", p_flat("#4f5a2e", 7))
    a.add("og_mark", p_label("#4f5a2e", "#151515", "#e8e8e8"))
    a.add("black_band", p_flat("#141414", 3))
    a.add("can_white", p_flat("#d8d4c8", 8, light=1.08))
    a.add("can_label", p_label("#d8d4c8", "#c01e1e", "#202020"))
    a.add("can_cap", p_flat("#2153b3", 8))
    a.add("tape", p_tape())
    a.add("det", p_flat("#1a1a1a", 3))
    a.add("wire", p_stripes("#d11c1c", "#1c1c1c", 2, vertical=True))
    a.add("rgd_body", p_panel_lines("#55603a", "#3f4829"))
    a.add("vog_body", p_flat("#39432c", 6))
    a.add("copper", p_flat("#b9733a", 10, light=1.3))
    a.add("alu", p_flat("#a9adb2", 6, light=1.2))
    a.add("print", p_print("#ff7a1a"))
    a.save()

    pg = Model(a)
    pg.box([7.3, 7.3, 0.5], [8.7, 8.7, 5], "steel")
    pg.box([6.2, 7.85, 0.5], [9.8, 8.15, 2.6], "fin")
    pg.box([7.85, 6.2, 0.5], [8.15, 9.8, 2.6], "fin")
    pg.box([6.8, 6.8, 5], [9.2, 9.2, 6.5], "pg_cone")
    pg.box([6.3, 6.3, 6.5], [9.7, 9.7, 11], "pg_body")
    pg.box([6.25, 6.25, 9], [9.75, 9.75, 9.6], "pg_band")
    pg.box([6.7, 6.7, 11], [9.3, 9.3, 12.8], "pg_cone")
    pg.box([7.2, 7.2, 12.8], [8.8, 8.8, 14.3], "pg_cone")
    pg.box([7.6, 7.6, 14.3], [8.4, 8.4, 15.5], "brass")
    pg.save("pg7vs")

    og = Model(a)
    og.box([7.35, 7.35, 0.5], [8.65, 8.65, 4.5], "steel_dark")
    og.box([6.4, 7.85, 0.5], [9.6, 8.15, 2.2], "fin")
    og.box([7.85, 6.4, 0.5], [8.15, 9.6, 2.2], "fin")
    og.box([6.6, 6.6, 4.5], [9.4, 9.4, 12.5], {"all": "og_body", "east": "og_mark", "west": "og_mark"})
    og.box([6.55, 6.55, 10.5], [9.45, 9.45, 11], "black_band")
    og.box([7, 7, 12.5], [9, 9, 14.2], "og_body")
    og.box([7.5, 7.5, 14.2], [8.5, 8.5, 15.5], "alu")
    og.save("og7")

    can = Model(a)
    can.box([4.5, 2, 5.5], [11.5, 13, 10.5], {"all": "can_white", "south": "can_label", "north": "can_label"})
    can.box([5, 13, 6], [11, 14, 10], "can_white")
    can.box([9, 14, 7], [10.5, 15.4, 8.5], "can_cap")
    can.box([5.5, 14, 7.5], [8, 15.4, 8.5], "can_white")
    can.box([4.4, 5, 5.4], [11.6, 6.2, 10.6], "tape")
    can.box([4.4, 9.5, 5.4], [11.6, 10.7, 10.6], "tape")
    can.box([6.5, 10.8, 10.5], [9, 12.3, 11.3], "det")
    can.box([7.5, 12.3, 10.6], [8, 14.8, 11], "wire")
    can.save("explosive_canister")

    rgd = Model(a)
    rgd.box([6, 4.5, 6], [10, 9.5, 10], "rgd_body")
    rgd.box([6.4, 4, 6.4], [9.6, 4.5, 9.6], "rgd_body")
    rgd.box([6.5, 9.5, 6.5], [9.5, 10.3, 9.5], "rgd_body")
    rgd.box([7.2, 10.3, 7.2], [8.8, 11.5, 8.8], "steel")
    rgd.box([10, 6, 7.6], [10.4, 11.2, 8.4], "steel")
    rgd.box([8.4, 11.2, 7.6], [10.4, 11.6, 8.4], "steel")
    rgd.box([10.4, 10.2, 7.2], [11.8, 11.4, 7.45], "steel_dark")
    rgd.save("rgd5")

    vog = Model(a)
    vog.box([5.5, 1.5, 7.8], [10.5, 5, 8.2], "print")
    vog.box([7.8, 1.5, 5.5], [8.2, 5, 10.5], "print")
    vog.box([6.8, 1.5, 6.8], [9.2, 2.2, 9.2], "print")
    vog.box([6.9, 5, 6.9], [9.1, 10, 9.1], "vog_body")
    vog.box([6.8, 5.6, 6.8], [9.2, 6.2, 9.2], "copper")
    vog.box([7.2, 10, 7.2], [8.8, 11.5, 8.8], "alu")
    vog.box([7.6, 11.5, 7.6], [8.4, 12.2, 8.4], "alu")
    vog.save("vog17m")


# ---------------------------------------------------------------------------
# truck. Model unit = 3/16 block (rendered with scale 3), ground at y=0.
# local block coords = ((x-8)*3/16, y*3/16, (z-8)*3/16)

def build_truck():
    a = Atlas("truck")
    a.add("olive", p_flat("#3f4a2a", 7))
    a.add("olive_panel", p_panel_lines("#44502d", "#2d361c"))
    a.add("camo", p_camo("#3f4a2a", "#2b3320", "#5c5a3c"))
    a.add("cab_front", p_cab_front("#3f4a2a"))
    a.add("cab_side", p_window_split("#3f4a2a", glass_rows=6))
    a.add("black", p_flat("#18191a", 4))
    a.add("chassis", p_flat("#222321", 5))
    a.add("tire", p_tire())
    a.add("hub", p_hub("#3f4a2a"))
    a.add("headlight", p_led("#fff4c8", "#18191a"))
    a.add("taillight", p_led("#d01b1b", "#18191a"))
    a.add("bumper", p_hazard("#d0b020", "#18191a"))
    a.add("tank", p_stripes("#3a4527", "#2c351d", 3, vertical=True))
    a.add("tarp", p_flat("#4d5536", 9))
    a.add("rail", p_flat("#6c7175", 5, light=1.2))
    a.add("rail_dark", p_flat("#3d4144", 4))
    a.add("plate", p_label("#e8e8e8", "#e8e8e8", "#222222"))
    a.add("exhaust", p_flat("#2b2522", 6))
    a.save()

    body = Model(a)
    # frame rails and bed
    body.box([4.8, 4.0, -8], [6.4, 5.33, 22], "chassis")
    body.box([9.6, 4.0, -8], [11.2, 5.33, 22], "chassis")
    body.box([1.6, 5.33, -8], [14.4, 6.4, 14.4], {"all": "olive", "up": "olive_panel"})
    body.box([1.4, 6.4, -8], [1.8, 7.6, 14.4], "olive")
    body.box([14.2, 6.4, -8], [14.6, 7.6, 14.4], "olive")
    body.box([1.6, 6.4, -8.2], [14.4, 7.6, -7.8], {"all": "olive", "north": "olive_panel"})
    body.box([1.8, 4.6, -8.4], [3.4, 5.6, -8.0], "taillight")
    body.box([12.6, 4.6, -8.4], [14.2, 5.6, -8.0], "taillight")
    body.box([6.4, 4.4, -8.4], [9.6, 5.4, -8.0], "plate")
    # cab (cab-over, like a KamAZ)
    body.box([1.9, 4.8, 15.2], [14.1, 13.4, 24], {"all": "camo", "south": "cab_front", "east": "cab_side",
                                                  "west": "cab_side", "up": "olive_panel"})
    body.box([2.4, 13.4, 15.8], [13.6, 13.9, 23.2], "olive")
    body.box([1.6, 3.6, 24], [14.4, 5.0, 24.7], "bumper")
    body.box([2.4, 5.4, 24], [4.4, 6.6, 24.4], "headlight")
    body.box([11.6, 5.4, 24], [13.6, 6.6, 24.4], "headlight")
    body.box([1.4, 9.0, 21.5], [1.9, 11.0, 22.2], "black")
    body.box([14.1, 9.0, 21.5], [14.6, 11.0, 22.2], "black")
    # side boxes / tanks
    body.box([1.6, 3.4, 8.5], [3.6, 5.33, 13.5], "tank")
    body.box([12.4, 3.4, 8.5], [14.4, 5.33, 13.5], "black")
    body.box([12.8, 5.33, 13.6], [13.4, 14.5, 14.2], "exhaust")
    # fenders
    for z in (19.2, 2.9, -2.9):
        body.box([1.5, 5.6, z - 3.4], [3.2, 6.0, z + 3.4], "black")
        body.box([12.8, 5.6, z - 3.4], [14.5, 6.0, z + 3.4], "black")
    body.save("truck")

    wheel = Model(a)
    r = 2.93
    for rot in (None, ([8, 8, 8], "x", 45)):
        wheel.box([7.07, 8 - r, 8 - r * 0.42], [8.93, 8 + r, 8 + r * 0.42], {"all": "tire", "east": "hub", "west": "hub"}, rot=rot)
        wheel.box([7.07, 8 - r * 0.42, 8 - r], [8.93, 8 + r * 0.42, 8 + r], {"all": "tire", "east": "hub", "west": "hub"}, rot=rot)
    wheel.box([6.9, 7.0, 7.0], [9.1, 9.0, 9.0], "hub")
    wheel.save("truck_wheel")

    # rack: pivot (front bottom) at model (8, 0, 24), extends back to z ~5.6
    rack = Model(a)
    rack.box([1.6, 0, 5.6], [2.8, 0.8, 24], "rail_dark")
    rack.box([13.2, 0, 5.6], [14.4, 0.8, 24], "rail_dark")
    for z in (5.6, 14.4, 23.0):
        rack.box([1.6, 0, z], [2.4, 14.4, z + 1.0], "rail")
        rack.box([13.6, 0, z], [14.4, 14.4, z + 1.0], "rail")
    for i in range(5):
        y = 0.8 + i * 2.667
        rack.box([7.2, y, 5.6], [8.8, y + 0.4, 24], "rail")
        rack.box([1.6, y, 23.0], [14.4, y + 0.4, 24], "bumper" if i == 0 else "rail_dark")
        rack.box([1.6, y, 5.6], [14.4, y + 0.4, 6.4], "rail_dark")
    rack.box([1.6, 14.0, 5.6], [14.4, 14.4, 24], {"all": "rail_dark", "up": "tarp"})
    rack.save("truck_rack")

    # item icon: everything in one model, rack lifted 22.5 like in game
    icon = Model(a)
    icon.elements = list(body.elements)
    for wz in (19.2, 2.9, -2.9):
        for wx in (2.35, 13.65):
            icon.box([wx - 0.93, 0, wz - r], [wx + 0.93, 2 * r, wz + r], {"all": "tire", "east": "hub", "west": "hub"})
    pivot = (8, 6.4, 14.4)
    for e in rack.elements:
        e2 = json.loads(json.dumps(e))
        fr, to = e2["from"], e2["to"]
        dz = pivot[2] - 24
        fr[1] += pivot[1]
        to[1] += pivot[1]
        fr[2] += dz
        to[2] += dz
        e2["rotation"] = {"origin": list(pivot), "axis": "x", "angle": 22.5}
        icon.elements.append(e2)
    icon.save("launch_truck", display(0.32, gui_rot=(25, 135, 0), ground=0.25, hand=0.2, fixed=0.3, gui_tr=(0, -2, 0)),
              folder="item")


# ---------------------------------------------------------------------------
# blocks

def block_textures():
    rng = random.Random(7)

    def base_tex(col, noise=8):
        im = Image.new("RGBA", (16, 16))
        px = im.load()
        c = hexc(col)
        for y in range(16):
            for x in range(16):
                n = rng.randint(-noise, noise)
                px[x, y] = (max(0, c[0] + n), max(0, c[1] + n), max(0, c[2] + n), 255)
        return im

    def out(name):
        return path(ASSETS, "textures", "block", name + ".png")

    im = base_tex("#4b5320")
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=hexc("#363c17"))
    for p in [(2, 2), (13, 2), (2, 13), (13, 13)]:
        d.point(p, fill=hexc("#6b7536"))
        d.point((p[0] + 1, p[1] + 1), fill=hexc("#2b3011"))
    d.line([0, 8, 15, 8], fill=hexc("#3d4419"))
    for _ in range(6):
        d.point((rng.randint(1, 14), rng.randint(1, 14)), fill=hexc("#8a8d86"))
    im.save(out("launcher_metal"))

    im = base_tex("#7d838a", 6)
    d = ImageDraw.Draw(im)
    d.rectangle([6, 0, 9, 15], fill=hexc("#2e3135"))
    d.line([5, 0, 5, 15], fill=hexc("#a9afb6"))
    d.line([10, 0, 10, 15], fill=hexc("#a9afb6"))
    for y in range(1, 16, 4):
        d.point((7, y), fill=hexc("#4a4e53"))
    im.save(out("launcher_rail"))

    im = base_tex("#3a4129", 7)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=hexc("#262b1a"))
    for x in range(0, 16, 5):
        d.line([x, 0, x + 3, 15], fill=hexc("#33391f"))
    im.save(out("launcher_base"))

    im = base_tex("#5a5f5a", 5)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=hexc("#2e312e"))
    d.rectangle([2, 2, 13, 7], fill=hexc("#0c1c10"))
    d.line([3, 5, 7, 3], fill=hexc("#45ff7b"))
    d.line([7, 3, 12, 6], fill=hexc("#45ff7b"))
    d.rectangle([3, 10, 5, 12], fill=hexc("#d22626"))
    d.rectangle([7, 10, 8, 12], fill=hexc("#e0c021"))
    d.rectangle([10, 10, 12, 12], fill=hexc("#2e9b3d"))
    im.save(out("launcher_panel"))

    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = hexc("#e3b40f") if ((x + y) // 4) % 2 == 0 else hexc("#1c1c1c")
    im.save(out("launcher_hazard"))

    # black PLA with visible layers
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            g = (30 if y % 2 == 0 else 22) + rng.randint(-3, 3)
            px[x, y] = (g, g, g + 2, 255)
    im.save(out("launch_pad"))


def box_uv(fr, to, d):
    w = {"north": (0, 1), "south": (0, 1), "east": (2, 1), "west": (2, 1), "up": (0, 2), "down": (0, 2)}[d]
    a_ = min(16, abs(to[w[0]] - fr[w[0]]))
    b_ = min(16, abs(to[w[1]] - fr[w[1]]))
    return [0, 0, round(a_, 3), round(b_, 3)]


def block_model(name, textures, boxes, disp):
    els = []
    for fr, to, tex, rot, faces in boxes:
        e = {"from": fr, "to": to, "faces": {}}
        for d in DIRS:
            t = (faces or {}).get(d, tex)
            e["faces"][d] = {"uv": box_uv(fr, to, d), "texture": "#" + t}
        if rot:
            e["rotation"] = {"origin": rot[0], "axis": rot[1], "angle": rot[2]}
        els.append(e)
    save_json({"parent": "block/block", "ambientocclusion": False, "textures": textures,
               "elements": els, "display": disp}, ASSETS, "models", "block", name + ".json")


def build_launcher():
    r = ([8, 9, 8], "x", 22.5)
    boxes = [
        ([0, 0, 0], [16, 2, 16], "base", None, {"north": "hazard", "south": "hazard"}),
        ([7, 2, 2.5], [9, 10.4, 4], "metal", None, None),
        ([7, 2, 11.5], [9, 7.2, 13], "metal", None, None),
        ([3, 2, 3], [13, 3, 4], "metal", None, None),
        ([3, 2, 12], [13, 3, 13], "metal", None, None),
        ([6, 8.5, -8], [10, 9.5, 24], "rail", r, None),
        ([5.4, 9.5, -8], [6, 10.6, 24], "metal", r, None),
        ([10, 9.5, -8], [10.6, 10.6, 24], "metal", r, None),
        ([6.5, 9.5, -8], [9.5, 11.5, -6], "metal", r, None),
        ([11, 2, 9], [15, 6, 14], "metal", None, {"east": "panel"}),
        ([12.6, 6, 12.5], [13.0, 9, 12.9], "metal", None, None),
    ]
    tex = {"particle": f"{MODID}:block/launcher_metal", "metal": f"{MODID}:block/launcher_metal",
           "rail": f"{MODID}:block/launcher_rail", "base": f"{MODID}:block/launcher_base",
           "panel": f"{MODID}:block/launcher_panel", "hazard": f"{MODID}:block/launcher_hazard"}
    disp = {
        "gui": {"rotation": [30, 135, 0], "translation": [0, -1, 0], "scale": [0.5] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25] * 3},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.45] * 3},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.3] * 3},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.35] * 3},
    }
    block_model("launcher", tex, boxes, disp)
    save_json({"variants": {
        "facing=north": {"model": f"{MODID}:block/launcher"},
        "facing=east": {"model": f"{MODID}:block/launcher", "y": 90},
        "facing=south": {"model": f"{MODID}:block/launcher", "y": 180},
        "facing=west": {"model": f"{MODID}:block/launcher", "y": 270},
    }}, ASSETS, "blockstates", "launcher.json")
    save_json({"parent": f"{MODID}:block/launcher"}, ASSETS, "models", "item", "launcher.json")


PAD_FORK_TOP = 14.6


def build_launch_pad():
    t = "pad"
    boxes = [([6, 0, 6], [10, 2.6, 10], t, None, None)]
    for ang in (45, -45):
        boxes.append(([1.5, 0.8, 7.4], [14.5, 2.0, 8.6], t, ([8, 1.4, 8], "y", ang), None))
    for cx in (3.4, 12.6):
        for cz in (3.4, 12.6):
            boxes.append(([cx - 1.0, 0, cz - 1.0], [cx + 1.0, 3.0, cz + 1.0], t, None, None))
            boxes.append(([cx - 0.7, 3.0, cz - 0.7], [cx + 0.7, 14.0, cz + 0.7], t, None, None))
            boxes.append(([cx - 1.0, 14.0, cz - 1.0], [cx + 1.0, PAD_FORK_TOP, cz + 1.0], t, None, None))
            # prongs sit across the diagonal so the frame arm drops between them
            sx = 1 if cx < 8 else -1
            sz = 1 if cz < 8 else -1
            p1 = (cx + 0.7 * sx, cz - 0.7 * sz)
            p2 = (cx - 0.7 * sx, cz + 0.7 * sz)
            for px_, pz_ in (p1, p2):
                boxes.append(([px_ - 0.3, PAD_FORK_TOP, pz_ - 0.3], [px_ + 0.3, 16, pz_ + 0.3], t, None, None))
    tex = {"particle": f"{MODID}:block/launch_pad", "pad": f"{MODID}:block/launch_pad"}
    disp = {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25] * 3},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5] * 3},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375] * 3},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4] * 3},
    }
    block_model("launch_pad", tex, boxes, disp)
    save_json({"variants": {"": {"model": f"{MODID}:block/launch_pad"}}}, ASSETS, "blockstates", "launch_pad.json")
    save_json({"parent": f"{MODID}:block/launch_pad"}, ASSETS, "models", "item", "launch_pad.json")


# ---------------------------------------------------------------------------
# flat item icons

def pixel_icon(name, rows, pal):
    assert len(rows) == 16, name
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    for y, row in enumerate(rows):
        assert len(row) == 16, (name, y, row)
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = hexc(pal[ch])
    im.save(path(ASSETS, "textures", "item", name + ".png"))
    save_json({"parent": "item/generated", "textures": {"layer0": f"{MODID}:item/{name}"}},
              ASSETS, "models", "item", name + ".json")


def build_icons():
    pixel_icon("fpv_goggles", [
        "................",
        "................",
        "................",
        "..kkkkkkkkkkkk..",
        ".kllllllllllllk.",
        ".kmmmmmmmmmmmmk.",
        "skmddddddddddmks",
        "skmdkkkddkkkdmks",
        "skmdkbbkdkbbkmks",
        "skmdkbckdkbckmks",
        ".kmdkkkddkkkdmk.",
        ".kmmmmmggmmmmmk.",
        "..kkkkkkkkkkkk..",
        "...kpp....ppk...",
        "...kkk....kkk...",
        "................",
    ], {"k": "#0e0f11", "l": "#5d646e", "m": "#3a3f46", "d": "#24272c", "b": "#3546b5",
        "c": "#a8c8ff", "s": "#8b1f1f", "g": "#2cff6a", "p": "#1c1d20"})

    pixel_icon("drone_remote", [
        "..a..........a..",
        "..a..........a..",
        "..a..........a..",
        "..a..kkkkkk..a..",
        ".kkkkkssssskkkk.",
        "kmmmmksggsskmmmk",
        "kmmmmkssssskmmmk",
        "kmhmmmkkkkkmmhmk",
        "kmhhmmmmmmmmhhmk",
        "kmmhmmmrmmymhmmk",
        "kmmmmmmmmmmmmmmk",
        "kmmmmmmmmmmmmmmk",
        ".kmmmmkkkkmmmmk.",
        "..kmmk....kmmk..",
        "...kk......kk...",
        "................",
    ], {"a": "#2a2c30", "k": "#0d0e10", "m": "#2a2c31", "s": "#0a1a0d", "g": "#45ff7b",
        "h": "#8d939b", "r": "#d01b1b", "y": "#e3b40f"})

    pixel_icon("tablet", [
        "................",
        ".kkkkkkkkkkkkkk.",
        ".kffffffffffffk.",
        ".kfssssssssssfk.",
        ".kfsggsssgsssfk.",
        ".kfsgsssrrssgfk.",
        ".kfssssrwrrssfk.",
        ".kfsgssrrrssgfk.",
        ".kfsssssrssssfk.",
        ".kfsgsgsssgssfk.",
        ".kfssssssssssfk.",
        ".kfssssssssssfk.",
        ".kffffffffffffk.",
        ".kffbffffffrffk.",
        ".kkkkkkkkkkkkkk.",
        "................",
    ], {"k": "#16190f", "f": "#3d4636", "s": "#0b1d10", "g": "#2f8f47", "r": "#e23c3c",
        "w": "#ffffff", "b": "#5ac8ff"})

    pixel_icon("launch_remote", [
        "...........a....",
        "...........a....",
        "...........a....",
        "...........ao...",
        "...kkkkkkkkkk...",
        "..kmmmmmmmmmmk..",
        "..kmsssssssmmk..",
        "..kmsgggsgsmmk..",
        "..kmsssssssmmk..",
        "..kmmmmmmmmmmk..",
        "..kmyyyymtmmmk..",
        "..kmyrrymtmmmk..",
        "..kmyrrymmmmmk..",
        "..kmyyyymmmmmk..",
        "...kkkkkkkkkk...",
        "................",
    ], {"a": "#232428", "o": "#ee3333", "k": "#0f1012", "m": "#4b5320", "s": "#0a1a0d",
        "g": "#45ff7b", "y": "#e3b40f", "r": "#d01b1b", "t": "#c7c7c7"})

    pixel_icon("flight_controller", [
        "................",
        ".kkkkkkkkkkkkkk.",
        ".kggggggggggggk.",
        ".kgoggggggggogk.",
        ".kgllggggggllgk.",
        ".kggggxxxxggggk.",
        ".kgllgxccxgllgk.",
        ".kggggxccxggggk.",
        ".kgllgxxxxgllgk.",
        ".kggggggggggggk.",
        ".kgrggllggggbgk.",
        ".kggggggggggggk.",
        ".kgoggggggggogk.",
        ".kggggggggggggk.",
        ".kkkkkkkkkkkkkk.",
        "................",
    ], {"k": "#0d2a13", "g": "#1f6b31", "o": "#d8b04a", "l": "#46b35c", "x": "#151515",
        "c": "#3a3a3a", "r": "#ff3b3b", "b": "#3b8bff"})


def armor_texture():
    im = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    strap, strap_hi = hexc("#1b1c20"), hexc("#33353b")
    for x0 in (0, 16, 24):
        d.rectangle([x0, 10, x0 + 7, 12], fill=strap)
        d.line([x0, 10, x0 + 7, 10], fill=strap_hi)
    d.rectangle([8, 9, 15, 13], fill=hexc("#24272c"))
    d.line([8, 9, 15, 9], fill=hexc("#5d646e"))
    d.line([8, 13, 15, 13], fill=hexc("#0e0f11"))
    d.rectangle([9, 10, 10, 11], fill=hexc("#3a3f46"))
    d.rectangle([13, 10, 14, 11], fill=hexc("#3a3f46"))
    d.point((11, 12), fill=hexc("#2cff6a"))
    d.rectangle([12, 2, 12, 5], fill=hexc("#202226"))
    d.point((12, 1), fill=hexc("#ff6a00"))
    im.save(path(ASSETS, "textures", "models", "armor", "fpv_goggles_layer_1.png"))
    Image.new("RGBA", (64, 32), (0, 0, 0, 0)).save(
        path(ASSETS, "textures", "models", "armor", "fpv_goggles_layer_2.png"))


def item_models():
    def parented(name, model, disp):
        save_json({"parent": f"{MODID}:entity/{model}", "display": disp}, ASSETS, "models", "item", name + ".json")

    strike = display(0.42, gui_rot=(30, 150, 0), ground=0.25, hand=0.25, fixed=0.4, gui_tr=(1, 0, 0))
    parented("shahed136", "shahed136", strike)
    parented("geran2", "geran2", strike)
    parented("geran5", "geran5", display(0.4, gui_rot=(30, 150, 0), ground=0.25, hand=0.25, fixed=0.4, gui_tr=(1, 0, 0)))
    parented("propeller", "propeller", display(0.9, gui_rot=(45, 30, 0), ground=0.5, hand=0.5))
    parented("pg7vs", "pg7vs", display(1.0, gui_rot=(0, 135, 45), ground=0.5, hand=0.6))
    parented("og7", "og7", display(1.0, gui_rot=(0, 135, 45), ground=0.5, hand=0.6))
    parented("explosive_canister", "explosive_canister", display(0.85, ground=0.5, hand=0.6))
    parented("rgd5", "rgd5", display(1.3, ground=0.6, hand=0.7))
    parented("vog17m", "vog17m", display(1.3, ground=0.6, hand=0.7))


# ---------------------------------------------------------------------------
# lang

RU = {
    "itemGroup.fpvdrones": "FPV Drones",
    "item.fpvdrones.fpv_goggles": "ФПВ-очки",
    "item.fpvdrones.drone_remote": "Пульт управления дроном",
    "item.fpvdrones.fpv_drone": "FPV-дрон",
    "item.fpvdrones.mavic3": "Мавик-3",
    "item.fpvdrones.shahed136": "Шахед-136",
    "item.fpvdrones.geran2": "Герань-2",
    "item.fpvdrones.geran5": "Герань-5",
    "item.fpvdrones.launch_truck": "Пусковой грузовик",
    "item.fpvdrones.tablet": "Планшет оператора",
    "item.fpvdrones.launch_remote": "Пульт удалённого запуска",
    "item.fpvdrones.pg7vs": "ПГ-7ВС",
    "item.fpvdrones.og7": "ОГ-7",
    "item.fpvdrones.explosive_canister": "5-литровка со взрывчаткой",
    "item.fpvdrones.rgd5": "РГД-5",
    "item.fpvdrones.vog17m": "ВОГ-17М",
    "item.fpvdrones.propeller": "Пропеллер",
    "item.fpvdrones.flight_controller": "Полётный контроллер",
    "block.fpvdrones.launcher": "Пусковая установка",
    "block.fpvdrones.launch_pad": "Стартовая площадка",
    "entity.fpvdrones.fpv_drone": "FPV-дрон",
    "entity.fpvdrones.mavic3": "Мавик-3",
    "entity.fpvdrones.strike_drone": "Ударный БПЛА",
    "entity.fpvdrones.munition": "Боеприпас",
    "entity.fpvdrones.operator": "Оператор",
    "entity.fpvdrones.launch_truck": "Пусковой грузовик",
    "key.categories.fpvdrones": "FPV Drones",
    "key.fpvdrones.toggle_view": "Подключить / отключить видео",
    "key.fpvdrones.action": "Сброс / подрыв / меню грузовика",
    "key.fpvdrones.zoom_in": "Зум + (Мавик)",
    "key.fpvdrones.zoom_out": "Зум − (Мавик)",
    "screen.fpvdrones.launcher": "Пусковая установка",
    "screen.fpvdrones.truck": "Пусковой грузовик",
    "screen.fpvdrones.save": "Сохранить",
    "screen.fpvdrones.launch": "ПУСК",
    "screen.fpvdrones.salvo": "ЗАЛП",
    "screen.fpvdrones.unload": "Разрядить",
    "screen.fpvdrones.mavic_point": "← точка Мавика",
    "screen.fpvdrones.target": "Координаты цели",
    "screen.fpvdrones.freq": "Частота, МГц",
    "screen.fpvdrones.y_hint": "Y пусто = по земле",
    "screen.fpvdrones.empty_slot": "пусто",
    "screen.fpvdrones.distance": "Дальность: %s м",
    "msg.fpvdrones.remote_bound_drone": "Пульт привязан к: %s",
    "msg.fpvdrones.no_remote": "Нет пульта, привязанного к дрону",
    "msg.fpvdrones.drone_not_found": "Дрон не найден",
    "msg.fpvdrones.out_of_range": "Дрон вне зоны связи",
    "msg.fpvdrones.connected": "Видео подключено. [%s] — отключиться",
    "msg.fpvdrones.put_goggles": "Надень ФПВ-очки",
    "msg.fpvdrones.payload_attached": "Подвес установлен: %s",
    "msg.fpvdrones.payload_incompatible": "Этот подвес не подходит к этому дрону",
    "msg.fpvdrones.payload_full": "Нет свободного крепления",
    "msg.fpvdrones.charged": "Аккумулятор: %s%%",
    "msg.fpvdrones.already_viewing": "Ты уже подключён к камере",
    "msg.fpvdrones.spectator": "Нельзя подключиться в режиме наблюдателя",
    "msg.fpvdrones.loaded": "Заряжено: %s",
    "msg.fpvdrones.site_full": "Все направляющие заняты",
    "msg.fpvdrones.unloaded": "Разряжено",
    "msg.fpvdrones.bound_tablet": "Планшет привязан: %s",
    "msg.fpvdrones.bound_tablet_flying": "Планшет привязан к БПЛА в полёте",
    "msg.fpvdrones.no_strike_drone": "Нет активного БПЛА для планшета",
    "msg.fpvdrones.other_dim": "БПЛА в другом измерении",
    "msg.fpvdrones.remote_bound": "Привязано (%s шт., частота %s МГц)",
    "msg.fpvdrones.remote_unbound": "Отвязано (осталось %s)",
    "msg.fpvdrones.remote_wrong_freq": "Частота не совпадает! Пульт: %s МГц, установка: %s МГц",
    "msg.fpvdrones.remote_empty": "К пульту ничего не привязано",
    "msg.fpvdrones.remote_result": "Пущено: %s, пропущено: %s",
    "msg.fpvdrones.remote_status": "Пульт: частота %s МГц, установок: %s",
    "msg.fpvdrones.launch_ok": "ПУСК!",
    "msg.fpvdrones.salvo_ok": "ЗАЛП: %s шт.",
    "msg.fpvdrones.launch_empty": "Установка не заряжена",
    "msg.fpvdrones.launch_no_target": "Не задана цель",
    "msg.fpvdrones.launch_too_close": "Цель слишком близко",
    "msg.fpvdrones.launch_too_far": "Цель дальше максимальной дальности",
    "msg.fpvdrones.launch_busy": "Идёт залп",
    "msg.fpvdrones.saved": "Настройки сохранены",
    "msg.fpvdrones.pickup_flying": "Нельзя поднять дрон, пока им управляют",
    "reason.fpvdrones.signal_lost": "СИГНАЛ ПОТЕРЯН",
    "reason.fpvdrones.target_lost": "СВЯЗЬ С БОРТОМ ПОТЕРЯНА",
    "reason.fpvdrones.detonated": "ПОДРЫВ",
    "reason.fpvdrones.body_attacked": "ОПЕРАТОР АТАКОВАН!",
    "reason.fpvdrones.disconnected": "ВИДЕО ОТКЛЮЧЕНО",
    "reason.fpvdrones.goggles_removed": "ОЧКИ СНЯТЫ",
    "reason.fpvdrones.remote_lost": "НЕТ ПУЛЬТА",
    "tooltip.fpvdrones.goggles": "Надень — увидишь картинку с дрона, к которому привязан твой пульт.",
    "tooltip.fpvdrones.drone_remote": "Shift+ПКМ по дрону — привязать.",
    "tooltip.fpvdrones.bound": "Привязано: %s",
    "tooltip.fpvdrones.unbound": "Не привязано",
    "tooltip.fpvdrones.fpv": "Подвес: ПГ-7ВС, ОГ-7 или 5-литровка. Врезался — бабах.",
    "tooltip.fpvdrones.mavic": "Разведка и сбросы: до 2× РГД-5 / ВОГ-17М. Сброс — [G].",
    "tooltip.fpvdrones.battery": "Аккумулятор: %s%%",
    "tooltip.fpvdrones.strike": "Заряжается в пусковую установку или грузовик.",
    "tooltip.fpvdrones.truck": "ПКМ — сесть за руль, ПКМ БПЛА — зарядить, Shift+ПКМ — меню. В кабине [G] — меню.",
    "tooltip.fpvdrones.tablet": "ПКМ по установке/грузовику или Shift+ПКМ по БПЛА — привязать. ПКМ — камера.",
    "tooltip.fpvdrones.remote": "ПКМ по установке/грузовику — привязать. ПКМ в воздух — залп. Shift+ПКМ — статус.",
    "tooltip.fpvdrones.remote_freq": "Частота: %s МГц, установок: %s",
    "tooltip.fpvdrones.payload_fpv": "Подвес для FPV-дрона",
    "tooltip.fpvdrones.payload_mavic": "Сброс с Мавика",
    "tooltip.fpvdrones.launch_pad": "ПКМ дроном — поставить дрон на площадку.",
    "death.attack.fpvdrones.shrapnel": "%1$s посекло осколками",
    "death.attack.fpvdrones.shrapnel.player": "%1$s посекло осколками, пока он убегал от %2$s",
    "death.attack.fpvdrones.shrapnel_by": "%1$s посекло осколками от %2$s",
    "death.attack.fpvdrones.shrapnel_by.item": "%1$s посекло осколками от %2$s",
    "death.attack.fpvdrones.cumulative": "%1$s прожгло кумулятивной струёй",
    "death.attack.fpvdrones.cumulative.player": "%1$s прожгло кумулятивной струёй, пока он убегал от %2$s",
    "death.attack.fpvdrones.cumulative_by": "%1$s прожгло кумулятивной струёй от %2$s",
    "death.attack.fpvdrones.cumulative_by.item": "%1$s прожгло кумулятивной струёй от %2$s",
    "subtitles.fpvdrones.fpv_motor": "Жужжит FPV-дрон",
    "subtitles.fpvdrones.mavic_motor": "Гудит квадрокоптер",
    "subtitles.fpvdrones.shahed_engine": "Тарахтит мопед в небе",
    "subtitles.fpvdrones.jet_engine": "Свистит реактивный двигатель",
    "subtitles.fpvdrones.shahed_launch": "Старт БПЛА",
    "subtitles.fpvdrones.truck_engine": "Рычит дизель",
    "subtitles.fpvdrones.release": "Сброс",
    "subtitles.fpvdrones.beep": "Писк",
    "subtitles.fpvdrones.static": "Помехи",
}

EN = {
    "itemGroup.fpvdrones": "FPV Drones",
    "item.fpvdrones.fpv_goggles": "FPV Goggles",
    "item.fpvdrones.drone_remote": "Drone Controller",
    "item.fpvdrones.fpv_drone": "FPV Drone",
    "item.fpvdrones.mavic3": "Mavic 3",
    "item.fpvdrones.shahed136": "Shahed-136",
    "item.fpvdrones.geran2": "Geran-2",
    "item.fpvdrones.geran5": "Geran-5",
    "item.fpvdrones.launch_truck": "Launch Truck",
    "item.fpvdrones.tablet": "Operator Tablet",
    "item.fpvdrones.launch_remote": "Remote Launch Controller",
    "item.fpvdrones.pg7vs": "PG-7VS",
    "item.fpvdrones.og7": "OG-7",
    "item.fpvdrones.explosive_canister": "5L Explosive Canister",
    "item.fpvdrones.rgd5": "RGD-5",
    "item.fpvdrones.vog17m": "VOG-17M",
    "item.fpvdrones.propeller": "Propeller",
    "item.fpvdrones.flight_controller": "Flight Controller",
    "block.fpvdrones.launcher": "Launcher",
    "block.fpvdrones.launch_pad": "Launch Pad",
    "entity.fpvdrones.fpv_drone": "FPV Drone",
    "entity.fpvdrones.mavic3": "Mavic 3",
    "entity.fpvdrones.strike_drone": "Strike UAV",
    "entity.fpvdrones.munition": "Munition",
    "entity.fpvdrones.operator": "Operator",
    "entity.fpvdrones.launch_truck": "Launch Truck",
    "key.categories.fpvdrones": "FPV Drones",
    "key.fpvdrones.toggle_view": "Connect / disconnect video",
    "key.fpvdrones.action": "Drop / detonate / truck menu",
    "key.fpvdrones.zoom_in": "Zoom + (Mavic)",
    "key.fpvdrones.zoom_out": "Zoom - (Mavic)",
    "screen.fpvdrones.launcher": "Launcher",
    "screen.fpvdrones.truck": "Launch Truck",
    "screen.fpvdrones.save": "Save",
    "screen.fpvdrones.launch": "LAUNCH",
    "screen.fpvdrones.salvo": "SALVO",
    "screen.fpvdrones.unload": "Unload",
    "screen.fpvdrones.mavic_point": "<- Mavic point",
    "screen.fpvdrones.target": "Target coordinates",
    "screen.fpvdrones.freq": "Frequency, MHz",
    "screen.fpvdrones.y_hint": "Empty Y = ground level",
    "screen.fpvdrones.empty_slot": "empty",
    "screen.fpvdrones.distance": "Range: %s m",
    "msg.fpvdrones.remote_bound_drone": "Controller bound to: %s",
    "msg.fpvdrones.no_remote": "No controller bound to a drone",
    "msg.fpvdrones.drone_not_found": "Drone not found",
    "msg.fpvdrones.out_of_range": "Drone is out of signal range",
    "msg.fpvdrones.connected": "Video connected. [%s] to disconnect",
    "msg.fpvdrones.put_goggles": "Put on FPV goggles",
    "msg.fpvdrones.payload_attached": "Payload attached: %s",
    "msg.fpvdrones.payload_incompatible": "This payload doesn't fit this drone",
    "msg.fpvdrones.payload_full": "No free mount",
    "msg.fpvdrones.charged": "Battery: %s%%",
    "msg.fpvdrones.already_viewing": "You are already connected to a camera",
    "msg.fpvdrones.spectator": "Cannot connect while in spectator mode",
    "msg.fpvdrones.loaded": "Loaded: %s",
    "msg.fpvdrones.site_full": "All rails are taken",
    "msg.fpvdrones.unloaded": "Unloaded",
    "msg.fpvdrones.bound_tablet": "Tablet bound: %s",
    "msg.fpvdrones.bound_tablet_flying": "Tablet bound to the flying UAV",
    "msg.fpvdrones.no_strike_drone": "No active UAV for this tablet",
    "msg.fpvdrones.other_dim": "The UAV is in another dimension",
    "msg.fpvdrones.remote_bound": "Bound (%s total, %s MHz)",
    "msg.fpvdrones.remote_unbound": "Unbound (%s left)",
    "msg.fpvdrones.remote_wrong_freq": "Frequency mismatch! Remote: %s MHz, launcher: %s MHz",
    "msg.fpvdrones.remote_empty": "Nothing bound to this remote",
    "msg.fpvdrones.remote_result": "Launched: %s, skipped: %s",
    "msg.fpvdrones.remote_status": "Remote: %s MHz, launchers: %s",
    "msg.fpvdrones.launch_ok": "LAUNCH!",
    "msg.fpvdrones.salvo_ok": "SALVO: %s",
    "msg.fpvdrones.launch_empty": "Launcher is not loaded",
    "msg.fpvdrones.launch_no_target": "No target set",
    "msg.fpvdrones.launch_too_close": "Target is too close",
    "msg.fpvdrones.launch_too_far": "Target is beyond max range",
    "msg.fpvdrones.launch_busy": "Salvo in progress",
    "msg.fpvdrones.saved": "Settings saved",
    "msg.fpvdrones.pickup_flying": "Can't pick up a drone that is being flown",
    "reason.fpvdrones.signal_lost": "SIGNAL LOST",
    "reason.fpvdrones.target_lost": "LINK LOST",
    "reason.fpvdrones.detonated": "DETONATION",
    "reason.fpvdrones.body_attacked": "OPERATOR UNDER ATTACK!",
    "reason.fpvdrones.disconnected": "VIDEO OFF",
    "reason.fpvdrones.goggles_removed": "GOGGLES REMOVED",
    "reason.fpvdrones.remote_lost": "NO CONTROLLER",
    "tooltip.fpvdrones.goggles": "Wear them to see the drone your controller is bound to.",
    "tooltip.fpvdrones.drone_remote": "Shift+RMB a drone to bind.",
    "tooltip.fpvdrones.bound": "Bound: %s",
    "tooltip.fpvdrones.unbound": "Not bound",
    "tooltip.fpvdrones.fpv": "Payload: PG-7VS, OG-7 or 5L canister. Impact = boom.",
    "tooltip.fpvdrones.mavic": "Recon and drops: up to 2x RGD-5 / VOG-17M. Drop with [G].",
    "tooltip.fpvdrones.battery": "Battery: %s%%",
    "tooltip.fpvdrones.strike": "Load it into a launcher or a launch truck.",
    "tooltip.fpvdrones.truck": "RMB to drive, RMB with a UAV to load, Shift+RMB for menu. [G] in the cab opens the menu.",
    "tooltip.fpvdrones.tablet": "RMB a launcher/truck or Shift+RMB a flying UAV to bind. RMB to watch.",
    "tooltip.fpvdrones.remote": "RMB a launcher/truck to bind. RMB in air to fire. Shift+RMB for status.",
    "tooltip.fpvdrones.remote_freq": "Frequency: %s MHz, launchers: %s",
    "tooltip.fpvdrones.payload_fpv": "FPV drone payload",
    "tooltip.fpvdrones.payload_mavic": "Mavic drop",
    "tooltip.fpvdrones.launch_pad": "RMB it with a drone to put the drone on the pad.",
    "death.attack.fpvdrones.shrapnel": "%1$s was shredded by shrapnel",
    "death.attack.fpvdrones.shrapnel.player": "%1$s was shredded by shrapnel while fleeing %2$s",
    "death.attack.fpvdrones.shrapnel_by": "%1$s was shredded by %2$s's shrapnel",
    "death.attack.fpvdrones.shrapnel_by.item": "%1$s was shredded by %2$s's shrapnel",
    "death.attack.fpvdrones.cumulative": "%1$s was burned through by a shaped charge",
    "death.attack.fpvdrones.cumulative.player": "%1$s was burned through by a shaped charge while fleeing %2$s",
    "death.attack.fpvdrones.cumulative_by": "%1$s was burned through by %2$s's shaped charge",
    "death.attack.fpvdrones.cumulative_by.item": "%1$s was burned through by %2$s's shaped charge",
    "subtitles.fpvdrones.fpv_motor": "FPV drone buzzes",
    "subtitles.fpvdrones.mavic_motor": "Quadcopter hums",
    "subtitles.fpvdrones.shahed_engine": "Moped in the sky",
    "subtitles.fpvdrones.jet_engine": "Jet engine whines",
    "subtitles.fpvdrones.shahed_launch": "UAV launches",
    "subtitles.fpvdrones.truck_engine": "Diesel rumbles",
    "subtitles.fpvdrones.release": "Release",
    "subtitles.fpvdrones.beep": "Beep",
    "subtitles.fpvdrones.static": "Static",
}


def lang():
    assert set(RU) == set(EN), set(RU) ^ set(EN)
    save_json(RU, ASSETS, "lang", "ru_ru.json")
    save_json(EN, ASSETS, "lang", "en_us.json")


# ---------------------------------------------------------------------------
# recipes / loot

def recipes():
    def shaped(name, pattern, key, result, count=1):
        k = {}
        for ch, it in key.items():
            k[ch] = {"tag": it[1:]} if it.startswith("#") else {"item": it}
        save_json({"type": "minecraft:crafting_shaped", "pattern": pattern, "key": k,
                   "result": {"item": f"{MODID}:{result}", "count": count}},
                  DATA, "recipes", name + ".json")

    M = "minecraft:"
    FC = f"{MODID}:flight_controller"
    PR = f"{MODID}:propeller"
    shaped("propeller", ["N N", " I ", "N N"], {"N": M + "iron_nugget", "I": M + "iron_ingot"}, "propeller", 4)
    shaped("flight_controller", ["RQR", "GCG", "RQR"],
           {"R": M + "redstone", "Q": M + "quartz", "G": M + "gold_nugget", "C": M + "repeater"}, "flight_controller")
    shaped("fpv_drone", ["P P", "IFI", "P P"], {"P": PR, "I": M + "iron_ingot", "F": FC}, "fpv_drone")
    shaped("mavic3", ["P P", "IFI", "PEP"], {"P": PR, "I": M + "iron_ingot", "F": FC, "E": M + "ender_eye"}, "mavic3")
    shaped("fpv_goggles", ["L L", "GFG"], {"L": M + "leather", "G": M + "glass_pane", "F": FC}, "fpv_goggles")
    shaped("drone_remote", ["R R", "IFI", "BIB"],
           {"R": M + "iron_bars", "I": M + "iron_ingot", "F": FC,
            "B": M + "stone_button"}, "drone_remote")
    shaped("tablet", ["III", "IGI", "IFI"], {"I": M + "iron_ingot", "G": M + "glass_pane", "F": FC}, "tablet")
    shaped("launch_remote", [" R ", "IFI", "IBI"],
           {"R": M + "redstone_torch", "I": M + "iron_ingot", "F": FC, "B": M + "stone_button"}, "launch_remote")
    shaped("launcher", ["  R", "IRI", "BBB"], {"R": M + "rail", "I": M + "iron_ingot", "B": M + "iron_block"}, "launcher")
    shaped("launch_pad", ["S S", " D ", "S S"], {"S": M + "stick", "D": M + "black_dye"}, "launch_pad")
    shaped("launch_truck", ["RRR", "BFB", "WIW"],
           {"R": M + "rail", "B": M + "iron_block", "F": FC, "W": M + "black_wool", "I": M + "piston"}, "launch_truck")
    shaped("shahed136", ["ITI", "WFW", "IPI"],
           {"I": M + "iron_ingot", "T": M + "tnt", "W": M + "iron_block", "F": FC, "P": PR}, "shahed136")
    shaped("geran2", ["ITI", "WFW", "CPC"],
           {"I": M + "iron_ingot", "T": M + "tnt", "W": M + "iron_block", "F": FC, "P": PR, "C": M + "coal_block"}, "geran2")
    shaped("geran5", ["TBT", "WFW", "IGI"],
           {"T": M + "tnt", "B": M + "blaze_rod", "W": M + "iron_block", "F": FC, "I": M + "iron_ingot",
            "G": M + "gold_ingot"}, "geran5")
    shaped("pg7vs", [" N ", "ITI", " I "], {"N": M + "iron_nugget", "I": M + "iron_ingot", "T": M + "tnt"}, "pg7vs")
    shaped("og7", [" N ", "GTG", " I "],
           {"N": M + "iron_nugget", "G": M + "gunpowder", "T": M + "tnt", "I": M + "iron_ingot"}, "og7")
    shaped("explosive_canister", ["GSG", "TBT", "GTG"],
           {"G": M + "gunpowder", "S": M + "string", "T": M + "tnt", "B": M + "bucket"}, "explosive_canister")
    shaped("rgd5", [" N ", "IGI", " I "], {"N": M + "iron_nugget", "I": M + "iron_ingot", "G": M + "gunpowder"}, "rgd5", 2)
    shaped("vog17m", [" N ", "IGI", " S "],
           {"N": M + "iron_nugget", "I": M + "iron_ingot", "G": M + "gunpowder", "S": M + "stick"}, "vog17m", 4)

    for block in ("launcher", "launch_pad"):
        save_json({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{MODID}:{block}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}]}]},
            DATA, "loot_tables", "blocks", block + ".json")


# ---------------------------------------------------------------------------
# sounds

SOUNDS = ["fpv_motor", "mavic_motor", "shahed_engine", "jet_engine", "shahed_launch", "truck_engine",
          "release", "beep", "static"]


def sounds_json():
    save_json({name: {"category": "neutral", "subtitle": f"subtitles.{MODID}.{name}",
                      "sounds": [{"name": f"{MODID}:{name}", "stream": False}]} for name in SOUNDS},
              ASSETS, "sounds.json")


def gen_sounds():
    try:
        import numpy as np
    except ImportError:
        print("no numpy, skipping sounds")
        return
    if not shutil.which("ffmpeg"):
        print("no ffmpeg, skipping sounds")
        return
    sr = 44100
    rng = np.random.default_rng(1)

    def write(name, sig):
        sig = np.asarray(sig, dtype=np.float64)
        sig = sig / (np.max(np.abs(sig)) + 1e-9) * 0.9
        out = path(ASSETS, "sounds", name + ".ogg")
        with tempfile.NamedTemporaryFile(suffix=".raw", delete=False) as f:
            f.write((sig * 32767).astype("<i2").tobytes())
            raw = f.name
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(sr), "-ac", "1",
                        "-i", raw, "-c:a", "libvorbis", "-q:a", "5", out], check=True)
        os.unlink(raw)

    def loop_noise(n, cutoff_bins):
        spec = np.zeros(n // 2 + 1, dtype=complex)
        spec[1:cutoff_bins] = rng.normal(size=cutoff_bins - 1) + 1j * rng.normal(size=cutoff_bins - 1)
        return np.fft.irfft(spec, n)

    # loops use whole periods so they don't click
    n = sr
    t = np.arange(n) / sr

    sig = np.zeros(n)
    for f0 in (310, 317, 304, 322):
        for h in range(1, 7):
            sig += np.sin(2 * np.pi * f0 * h * t + rng.uniform(0, 6.28)) / (h ** 1.1)
    sig += 0.35 * loop_noise(n, 9000)
    sig *= 1 + 0.15 * np.sin(2 * np.pi * 7 * t)
    write("fpv_motor", sig)

    sig = np.zeros(n)
    for f0 in (515, 522, 508, 530):
        for h in range(1, 4):
            sig += np.sin(2 * np.pi * f0 * h * t + rng.uniform(0, 6.28)) / (h ** 2)
    sig += 0.25 * loop_noise(n, 5000)
    write("mavic_motor", sig)

    n2 = sr * 2
    t2 = np.arange(n2) / sr
    f0 = 95.0
    phase = (t2 * f0) % 1.0
    pulse = np.exp(-phase * 9.0) * (1 + 0.3 * rng.normal(size=n2) * np.exp(-phase * 4))
    sig = pulse - pulse.mean()
    for h in (2, 3, 5):
        sig += 0.25 * np.sin(2 * np.pi * f0 * h * t2) * (0.6 + 0.4 * np.sin(2 * np.pi * 3 * t2))
    sig += 0.25 * loop_noise(n2, 3000)
    write("shahed_engine", np.tanh(sig * 2.2))

    # turbojet: broadband roar + compressor whine
    sig = loop_noise(n2, 14000) * 0.8 + loop_noise(n2, 1800) * 1.2
    for f in (2900, 3010, 5800):
        sig += 0.18 * np.sin(2 * np.pi * f * t2)
    write("jet_engine", sig)

    # diesel idle: low firing pulses
    f0 = 36.0
    phase = (t2 * f0) % 1.0
    pulse = np.exp(-phase * 6.0)
    sig = pulse - pulse.mean() + 0.35 * np.sin(2 * np.pi * f0 * 2 * t2) + 0.4 * loop_noise(n2, 900)
    write("truck_engine", np.tanh(sig * 1.8))

    n3 = int(sr * 2.2)
    t3 = np.arange(n3) / sr
    noise = rng.normal(size=n3)
    boom = noise * np.exp(-t3 * 9) * 1.4
    hiss = np.convolve(noise, np.ones(6) / 6, mode="same") * np.exp(-t3 * 1.3) * (1 - np.exp(-t3 * 40))
    low = np.sin(2 * np.pi * 55 * t3) * np.exp(-t3 * 5)
    write("shahed_launch", boom + 0.8 * hiss + low)

    n4 = int(sr * 0.25)
    t4 = np.arange(n4) / sr
    click = rng.normal(size=n4) * np.exp(-t4 * 120) + 0.5 * np.sin(2 * np.pi * 2200 * t4) * np.exp(-t4 * 60)
    write("release", click + 0.3 * np.sin(2 * np.pi * 180 * t4) * np.exp(-t4 * 14))

    n5 = int(sr * 0.35)
    t5 = np.arange(n5) / sr
    env = ((t5 < 0.12) | ((t5 > 0.2) & (t5 < 0.32))).astype(float)
    write("beep", np.sin(2 * np.pi * 1760 * t5) * env)

    n6 = int(sr * 0.9)
    t6 = np.arange(n6) / sr
    write("static", rng.normal(size=n6) * (0.6 + 0.4 * np.sin(2 * np.pi * 50 * t6)) * np.minimum(1, (0.9 - t6) * 6))


def clean():
    # drop generated files that no longer exist in the generator
    for sub in ("models/entity", "models/item", "textures/entity"):
        d = os.path.join(ASSETS, sub)
        if os.path.isdir(d):
            for f in os.listdir(d):
                os.remove(os.path.join(d, f))


def main():
    clean()
    props = build_props()
    build_fpv(props)
    build_mavic(props)
    for name, pal in SHAHED_PALETTES.items():
        build_shahed(name, pal)
    build_geran5()
    build_payloads()
    build_truck()
    block_textures()
    build_launcher()
    build_launch_pad()
    build_icons()
    armor_texture()
    item_models()
    lang()
    recipes()
    sounds_json()
    gen_sounds()
    print("done:", RES)


if __name__ == "__main__":
    main()
