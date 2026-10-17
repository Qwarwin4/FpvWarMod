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


def build_shahed238():
    """Shahed airframe, prop swapped for the small turbojet on top"""
    a = Atlas("shahed238")
    pal = SHAHED_PALETTES["shahed136"]
    a.add("hull", p_flat("#4a4e54", 6, light=1.15))
    a.add("hull_top", p_panel_lines("#50545a", "#2e3034"))
    a.add("wing", p_panel_lines("#55595f", "#33363a"))
    a.add("wing_under", p_flat(pal["under"], 5))
    a.add("nose", p_flat("#232427", 4))
    a.add("tip", p_flat("#5a5d61", 6))
    a.add("band", p_stripes("#d0d0d0", "#a8a8a8", 2))
    a.add("winglet", p_flat("#3a3d42", 5))
    a.add("stencil", p_label("#4a4e54", "#4a4e54", "#e6e6e6"))
    a.add("nacelle", p_vent("#9aa0a6", "#5d6268"))
    a.add("intake", p_motor_top("#2a2c30", "#c9c9c9"))
    a.add("nozzle", p_flat("#4a4038", 10))
    a.add("glow", p_flat("#ff8a2a", 20, border=0.9, light=1.4))
    a.add("gnss", p_flat("#d9d9d4", 6))
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
    m.box([6.6, 10, -7], [9.4, 12.6, 5], {"all": "nacelle", "south": "intake"})
    m.box([6.9, 10.3, -8.2], [9.1, 12.3, -7], {"all": "nozzle", "north": "glow"})
    m.box([7.6, 10, 5], [8.4, 10.6, 9], "hull")
    m.box([7.5, 10, 16], [8.5, 10.5, 19], "gnss")
    m.save("shahed238")


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
    a.add("rb_body", p_flat("#4b5532", 7))
    a.add("rb_face", p_label("#4b5532", "#1a1c15", "#c8d0a0"))
    a.add("rb_led", p_led("#ff2020", "#1a1c15"))
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

    # radio beacon: olive box, whip antenna, red LED, clip on top
    rb = Model(a)
    rb.box([5.5, 2, 6], [10.5, 8.5, 10], {"all": "rb_body", "south": "rb_face"})
    rb.box([6, 8.5, 6.5], [10, 9.5, 9.5], "steel_dark")
    rb.box([9, 9.5, 7.6], [9.6, 15.5, 8.2], "black_band")
    rb.box([6.5, 6.5, 9.9], [7.5, 7.5, 10.3], "rb_led")
    rb.box([7.4, 9.5, 7.4], [8.6, 11, 8.6], "steel")
    rb.save("radio_beacon")


# ---------------------------------------------------------------------------
# truck, a KamAZ-5350 style cab-over 6x6. Model unit = 3/16 block (rendered with scale 3), ground at y=0.
# boxes are written in truck-local blocks and converted: u = local * 16/3 (+8 on x/z)

TRUCK_WHEELS = [(-1.0, 2.35), (1.0, 2.35), (-1.0, -1.0), (1.0, -1.0), (-1.0, -2.3), (1.0, -2.3)]
TRUCK_WHEEL_R = 0.62
TRUCK_RACK_PIVOT = (0, 1.2, 0.35)


def tu(v, axis):
    return round(v * 16 / 3 + (0 if axis == 1 else 8), 3)


def build_truck():
    a = Atlas("truck")
    a.add("olive", p_flat("#46522f", 7))
    a.add("olive_panel", p_panel_lines("#4a5633", "#323b20"))
    a.add("grille", p_stripes("#1c1f16", "#3a4529", 2))
    a.add("cab_front", p_cab_front("#46522f"))
    a.add("cab_side", p_window_split("#46522f", glass="#1a2630", glass_rows=6))
    a.add("cab_back", p_flat("#3f4a2a", 6))
    a.add("black", p_flat("#18191a", 4))
    a.add("chassis", p_flat("#222321", 5))
    a.add("tire", p_tire())
    a.add("hub", p_hub("#46522f"))
    a.add("headlight", p_led("#fff4c8", "#18191a"))
    a.add("taillight", p_led("#d01b1b", "#18191a"))
    a.add("bumper", p_flat("#3a4428", 5))
    a.add("tank", p_stripes("#3a4527", "#2c351d", 3, vertical=True))
    a.add("tarp", p_camo("#4b5634", "#36402a", "#5f6a3e"))
    a.add("tarp_plain", p_flat("#4d5836", 9))
    a.add("rail", p_flat("#6c7175", 5, light=1.2))
    a.add("rail_dark", p_flat("#3d4144", 4))
    a.add("hazard", p_hazard("#d0b020", "#18191a"))
    a.add("plate", p_label("#e8e8e8", "#e8e8e8", "#222222"))
    a.add("dash", p_flat("#141516", 3))
    a.add("steel", p_flat("#7c8288", 6, light=1.2))
    a.add("wheel_rim", p_flat("#2a2b2c", 4))
    a.save()

    body = Model(a)

    def B(x1, y1, z1, x2, y2, z2, faces, rot=None):
        fr = [tu(x1, 0), tu(y1, 1), tu(z1, 2)]
        to = [tu(x2, 0), tu(y2, 1), tu(z2, 2)]
        if rot:
            o, axis, ang = rot
            rot = ([tu(o[0], 0), tu(o[1], 1), tu(o[2], 2)], axis, ang)
        body.box(fr, to, faces, rot=rot)

    def both(x1, y1, z1, x2, y2, z2, faces, rot=None):
        B(x1, y1, z1, x2, y2, z2, faces, rot)
        if rot:
            o, axis, ang = rot
            rot = ((-o[0], o[1], o[2]), axis, -ang if axis in ("y", "z") else ang)
        B(-x2, y1, z1, -x1, y2, z2, faces, rot)

    # frame rails and the big front bumper
    both(0.38, 0.78, -3.7, 0.62, 1.02, 3.2, "chassis")
    B(-1.25, 0.55, 3.2, 1.25, 1.02, 3.48, {"all": "bumper", "up": "black"})
    both(0.82, 0.68, 3.48, 1.12, 0.9, 3.52, "headlight")
    both(0.45, 0.7, 3.48, 0.6, 0.82, 3.52, "taillight")
    B(-0.32, 0.62, 3.48, 0.32, 0.78, 3.52, "plate")
    B(-0.2, 0.5, 3.3, 0.2, 0.62, 3.6, "black")
    # cab-over: flat nose, lower grille, big windscreen
    B(-1.2, 1.02, 1.8, 1.2, 1.7, 3.2, {"all": "olive", "south": "grille", "up": "olive_panel"})
    B(-1.2, 1.7, 1.8, 1.2, 2.95, 3.12, {"all": "olive", "south": "cab_front", "east": "cab_side",
                                        "west": "cab_side", "north": "cab_back", "up": "olive_panel"})
    B(-1.05, 2.95, 1.9, 1.05, 3.05, 3.0, "olive")
    # sun visor, mirrors, steps
    B(-1.15, 2.88, 3.12, 1.15, 2.95, 3.3, "olive")
    both(1.2, 2.1, 2.9, 1.28, 2.15, 3.15, "black")
    both(1.28, 1.75, 3.1, 1.34, 2.35, 3.2, "black")
    both(1.2, 0.72, 2.75, 1.32, 0.78, 3.15, "black")
    both(1.2, 1.0, 2.75, 1.32, 1.06, 3.15, "black")
    # front wheel arches
    both(0.7, 1.02, 1.6, 1.3, 1.12, 3.1, "black")
    # inside: dashboard and the wheel, only seen from the driver's seat
    B(-1.1, 1.7, 2.85, 1.1, 2.0, 3.05, "dash")
    B(0.32, 1.9, 2.55, 0.78, 2.3, 2.6, "dash", rot=((0.55, 2.1, 2.57), "x", 22.5))
    # snorkel behind the cab
    B(-1.2, 1.4, 1.62, -1.02, 3.15, 1.8, "black")
    B(-1.2, 3.0, 1.62, -0.85, 3.18, 1.8, "black")
    # tanks and battery box between the axles
    both(0.65, 0.48, 0.15, 1.15, 1.0, 1.4, "tank")
    # equipment module under a tarp right behind the cab, like the cargo body on the real thing
    B(-1.25, 1.02, 0.55, 1.25, 1.2, 1.55, {"all": "olive", "up": "olive_panel"})
    B(-1.25, 1.2, 0.6, 1.25, 2.85, 1.55, {"all": "tarp", "north": "tarp_plain"})
    B(-1.27, 1.2, 0.6, 1.27, 1.32, 1.57, "olive")
    for z in (0.75, 1.15):
        both(1.25, 1.32, z, 1.29, 2.75, z + 0.05, "olive")
    # flat bed under the rack
    B(-1.25, 1.02, -3.7, 1.25, 1.2, 0.55, {"all": "olive", "up": "olive_panel"})
    both(1.18, 1.2, -3.7, 1.25, 1.42, 0.55, "olive")
    B(-1.25, 1.2, -3.72, 1.25, 1.42, -3.65, {"all": "olive", "north": "hazard"})
    both(0.9, 1.2, -3.5, 1.2, 1.3, -0.4, "black")
    # rear mudflaps, lights, hitch
    both(0.85, 0.35, -3.72, 1.25, 1.02, -3.68, "black")
    both(0.85, 0.8, -3.73, 1.12, 0.95, -3.7, "taillight")
    B(-0.32, 0.65, -3.73, 0.32, 0.88, -3.7, "plate")
    B(-0.15, 0.6, -3.85, 0.15, 0.78, -3.65, "steel")
    # mudguards over the rear tandem
    both(0.72, 1.02, -2.95, 1.3, 1.1, -0.35, "black")
    body.save("truck")

    wheel = Model(a)
    r = TRUCK_WHEEL_R * 16 / 3
    w = 0.42 * 16 / 3 / 2
    for rot in (None, ([8, 8, 8], "x", 45)):
        wheel.box([8 - w, 8 - r, 8 - r * 0.42], [8 + w, 8 + r, 8 + r * 0.42], {"all": "tire", "east": "hub", "west": "hub"}, rot=rot)
        wheel.box([8 - w, 8 - r * 0.42, 8 - r], [8 + w, 8 + r * 0.42, 8 + r], {"all": "tire", "east": "hub", "west": "hub"}, rot=rot)
    wheel.box([8 - w - 0.15, 7.0, 7.0], [8 + w + 0.15, 9.0, 9.0], "hub")
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
        rack.box([1.6, y, 23.0], [14.4, y + 0.4, 24], "hazard" if i == 0 else "rail_dark")
        rack.box([1.6, y, 5.6], [14.4, y + 0.4, 6.4], "rail_dark")
    rack.box([1.6, 14.0, 5.6], [14.4, 14.4, 24], {"all": "rail_dark", "up": "tarp"})
    rack.save("truck_rack")

    # item icon: body + wheels + rack in one model
    icon = Model(a)
    icon.elements = list(body.elements)
    for wx, wz in TRUCK_WHEELS:
        cx, cz = tu(wx, 0), tu(wz, 2)
        icon.box([cx - w, 0, cz - r], [cx + w, 2 * r, cz + r], {"all": "tire", "east": "hub", "west": "hub"})
    pivot = (8, tu(TRUCK_RACK_PIVOT[1], 1), tu(TRUCK_RACK_PIVOT[2], 2))
    for e in rack.elements:
        e2 = json.loads(json.dumps(e))
        dz = pivot[2] - 24
        for v in (e2["from"], e2["to"]):
            v[1] += pivot[1]
            v[2] += dz
        e2["rotation"] = {"origin": list(pivot), "axis": "x", "angle": 22.5}
        icon.elements.append(e2)
    icon.save("launch_truck", display(0.3, gui_rot=(25, 135, 0), ground=0.25, hand=0.2, fixed=0.3, gui_tr=(0, -2, 0)),
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
    parented("shahed238", "shahed238", strike)
    parented("geran5", "geran5", display(0.4, gui_rot=(30, 150, 0), ground=0.25, hand=0.25, fixed=0.4, gui_tr=(1, 0, 0)))
    parented("propeller", "propeller", display(0.9, gui_rot=(45, 30, 0), ground=0.5, hand=0.5))
    parented("pg7vs", "pg7vs", display(1.0, gui_rot=(0, 135, 45), ground=0.5, hand=0.6))
    parented("og7", "og7", display(1.0, gui_rot=(0, 135, 45), ground=0.5, hand=0.6))
    parented("explosive_canister", "explosive_canister", display(0.85, ground=0.5, hand=0.6))
    parented("rgd5", "rgd5", display(1.3, ground=0.6, hand=0.7))
    parented("vog17m", "vog17m", display(1.3, ground=0.6, hand=0.7))
    parented("radio_beacon", "radio_beacon", display(1.3, ground=0.6, hand=0.7))


# ---------------------------------------------------------------------------
# parts

def draw_icon(name, fn):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    fn(ImageDraw.Draw(im), im)
    im.save(path(ASSETS, "textures", "item", name + ".png"))
    save_json({"parent": "item/generated", "textures": {"layer0": f"{MODID}:item/{name}"}},
              ASSETS, "models", "item", name + ".json")


def build_part_icons():
    K = hexc("#0e0f11")

    def frame(d, im):
        for a, b in (((2, 2), (13, 13)), ((13, 2), (2, 13))):
            d.line([a, b], fill=hexc("#1c1d21"), width=3)
            d.line([a, b], fill=hexc("#34363c"), width=1)
        d.rectangle([6, 3, 9, 12], fill=hexc("#17181b"), outline=K)
        d.rectangle([7, 5, 8, 10], fill=hexc("#2a2c31"))
        for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
            d.ellipse([x - 2, y - 2, x + 2, y + 2], fill=hexc("#26282c"), outline=K)

    def motor(d, im):
        d.rectangle([3, 11, 12, 13], fill=hexc("#1b1c1f"), outline=K)
        d.rectangle([3, 4, 12, 10], fill=hexc("#232428"), outline=K)
        d.line([4, 8, 11, 8], fill=hexc("#b06a2c"))
        d.line([4, 9, 11, 9], fill=hexc("#8a4f1f"))
        d.line([3, 4, 12, 4], fill=hexc("#5c6068"))
        d.rectangle([7, 1, 8, 3], fill=hexc("#b8bcc2"))
        d.point((4, 5), fill=hexc("#5c6068"))

    def esc(d, im):
        d.rectangle([2, 3, 13, 12], fill=hexc("#141815"), outline=K)
        for x in (4, 9):
            for y in (5, 9):
                d.rectangle([x, y, x + 2, y + 1], fill=hexc("#050505"))
        for x in range(3, 13, 3):
            d.point((x, 3), fill=hexc("#d8b04a"))
            d.point((x, 12), fill=hexc("#d8b04a"))
        d.line([6, 13, 6, 15], fill=hexc("#c4161c"))
        d.line([9, 13, 9, 15], fill=hexc("#111111"))

    def battery(d, im):
        d.rectangle([1, 4, 12, 11], fill=hexc("#17181b"), outline=K)
        d.rectangle([1, 6, 12, 9], fill=hexc("#e6b800"))
        d.point((4, 7), fill=K)
        d.point((5, 8), fill=K)
        d.point((8, 7), fill=K)
        d.point((9, 8), fill=K)
        d.line([12, 6, 14, 5], fill=hexc("#c4161c"))
        d.line([12, 9, 14, 10], fill=hexc("#111111"))
        d.rectangle([14, 5, 15, 10], fill=hexc("#d6a816"))

    def camera(d, im):
        d.rectangle([3, 3, 12, 12], fill=hexc("#141416"), outline=K)
        d.ellipse([4, 4, 11, 11], fill=hexc("#0a0a0c"), outline=hexc("#3a3c42"))
        d.ellipse([6, 6, 9, 9], fill=hexc("#2a2f7a"))
        d.point((6, 6), fill=hexc("#a9d4ff"))
        d.line([2, 5, 2, 10], fill=hexc("#2a2c31"))
        d.line([13, 5, 13, 10], fill=hexc("#2a2c31"))

    def vtx(d, im):
        d.rectangle([1, 5, 10, 13], fill=hexc("#141815"), outline=K)
        for y in range(6, 13, 2):
            d.line([2, y, 9, y], fill=hexc("#3a3c42"))
        d.rectangle([4, 8, 7, 10], fill=hexc("#050505"))
        d.rectangle([10, 8, 13, 10], fill=hexc("#d6a816"))
        d.rectangle([13, 7, 14, 11], fill=hexc("#b8963a"))
        d.point((3, 12), fill=hexc("#ff3b3b"))

    def receiver(d, im):
        d.rectangle([5, 8, 10, 12], fill=hexc("#1f3a8a"), outline=K)
        d.rectangle([6, 9, 8, 10], fill=hexc("#050505"))
        d.line([6, 8, 2, 2], fill=hexc("#202020"))
        d.line([9, 8, 13, 2], fill=hexc("#202020"))
        d.point((2, 2), fill=hexc("#e8e8e8"))
        d.point((13, 2), fill=hexc("#e8e8e8"))
        d.point((9, 11), fill=hexc("#45ff7b"))

    def antenna(d, im):
        d.rectangle([5, 1, 10, 4], fill=hexc("#1e1f23"), outline=K)
        d.point((7, 2), fill=hexc("#ff6a00"))
        d.line([7, 5, 7, 13], fill=hexc("#111113"), width=2)
        d.rectangle([6, 13, 9, 15], fill=hexc("#b8963a"))

    def gimbal(d, im):
        d.arc([2, 1, 13, 14], 200, 340, fill=hexc("#8d9196"), width=2)
        d.rectangle([4, 5, 11, 12], fill=hexc("#2b2c30"), outline=K)
        d.ellipse([5, 6, 10, 11], fill=hexc("#101012"))
        d.ellipse([6, 7, 9, 10], fill=hexc("#3b2d6e"))
        d.point((6, 7), fill=hexc("#d7c2ff"))

    def gnss(d, im):
        d.ellipse([1, 2, 14, 13], fill=hexc("#2a2b2e"), outline=K)
        d.ellipse([3, 3, 12, 11], fill=hexc("#3a3b40"))
        d.rectangle([5, 5, 10, 9], fill=hexc("#c9b98a"), outline=hexc("#8a7b55"))
        d.point((7, 7), fill=hexc("#b89a42"))
        d.point((12, 11), fill=hexc("#45ff7b"))

    def display(d, im):
        d.rectangle([1, 3, 14, 12], fill=hexc("#16171a"), outline=K)
        d.rectangle([2, 4, 13, 10], fill=hexc("#0b1d2a"))
        d.line([3, 6, 7, 6], fill=hexc("#45ff7b"))
        d.line([3, 8, 10, 8], fill=hexc("#45ff7b"))
        d.point((12, 5), fill=hexc("#ff3b3b"))
        d.line([6, 11, 9, 11], fill=hexc("#3a3c42"))

    def stack(d, im):
        d.rectangle([2, 9, 13, 13], fill=hexc("#141815"), outline=K)
        d.rectangle([2, 3, 13, 7], fill=hexc("#16171a"), outline=K)
        for x in (3, 12):
            d.line([x, 7, x, 9], fill=hexc("#b89a42"))
        d.rectangle([6, 4, 9, 6], fill=hexc("#050505"))
        d.rectangle([5, 10, 7, 12], fill=hexc("#050505"))
        d.rectangle([9, 10, 11, 12], fill=hexc("#050505"))
        d.point((4, 4), fill=hexc("#3b8bff"))
        d.point((11, 4), fill=hexc("#ff3b3b"))

    def airframe(d, im):
        d.polygon([(8, 1), (15, 11), (9, 10), (8, 14), (7, 10), (1, 11)], fill=hexc("#3a3d42"), outline=K)
        d.line([8, 2, 8, 13], fill=hexc("#5a5d61"))
        d.line([1, 11, 1, 8], fill=hexc("#26282b"))
        d.line([15, 11, 15, 8], fill=hexc("#26282b"))

    def piston(d, im):
        d.rectangle([3, 6, 12, 13], fill=hexc("#8d9399"), outline=hexc("#3a3d42"))
        for y in range(7, 13, 2):
            d.line([3, y, 12, y], fill=hexc("#5f646a"))
        d.rectangle([5, 3, 10, 6], fill=hexc("#a9aeb3"), outline=hexc("#3a3d42"))
        d.rectangle([7, 1, 8, 3], fill=hexc("#c4161c"))
        d.rectangle([0, 9, 3, 11], fill=hexc("#2a2b2e"))
        d.rectangle([12, 10, 15, 11], fill=hexc("#5b3a26"))

    def warhead(d, im):
        d.ellipse([3, 3, 12, 15], fill=hexc("#4f5a2e"), outline=K)
        d.rectangle([3, 8, 12, 9], fill=hexc("#c9b23f"))
        d.rectangle([6, 0, 9, 3], fill=hexc("#a9adb2"), outline=K)
        d.point((5, 6), fill=hexc("#6b7a3e"))

    def booster(d, im):
        d.rectangle([6, 1, 9, 12], fill=hexc("#7c8288"), outline=K)
        d.rectangle([6, 4, 9, 5], fill=hexc("#e3b40f"))
        d.polygon([(5, 12), (10, 12), (11, 15), (4, 15)], fill=hexc("#2b2522"))
        d.point((7, 14), fill=hexc("#ff8a2a"))
        d.line([6, 2, 6, 11], fill=hexc("#a9aeb3"))

    def iron(d, im):
        d.line([1, 14, 3, 12], fill=hexc("#c9c9c9"), width=1)
        d.line([3, 12, 7, 8], fill=hexc("#8d9399"), width=2)
        d.line([7, 8, 12, 3], fill=hexc("#c4161c"), width=3)
        d.line([8, 8, 12, 4], fill=hexc("#7a0f12"), width=1)
        d.line([12, 3, 15, 1], fill=hexc("#111111"))
        d.point((1, 14), fill=hexc("#ff8a2a"))

    for name, fn in [("frame", frame), ("motor", motor), ("esc", esc), ("battery", battery), ("fpv_camera", camera),
                     ("vtx", vtx), ("receiver", receiver), ("antenna", antenna), ("gimbal_camera", gimbal),
                     ("gnss_module", gnss), ("display", display), ("flight_stack", stack), ("airframe", airframe),
                     ("piston_engine", piston), ("warhead", warhead), ("booster", booster),
                     ("soldering_iron", iron)]:
        draw_icon(name, fn)


def build_jet_engine():
    a = Atlas("small_jet_engine")
    a.add("metal", p_flat("#9aa0a6", 6, light=1.25))
    a.add("metal_dark", p_flat("#5d6268", 5))
    a.add("intake", p_motor_top("#2a2c30", "#c9c9c9"))
    a.add("nozzle", p_flat("#4a4038", 10))
    a.add("glow", p_flat("#ff8a2a", 20, border=0.9, light=1.4))
    a.add("band", p_stripes("#e3b40f", "#1c1c1c", 2))
    a.add("mount", p_flat("#2b2d31", 4))
    a.save()
    m = Model(a)
    r = 3.2
    for rot in (None, ([8, 8, 8], "z", 45)):
        m.box([8 - r, 8 - r * 0.42, 2.5], [8 + r, 8 + r * 0.42, 12], {"all": "metal", "south": "intake"}, rot=rot)
        m.box([8 - r * 0.42, 8 - r, 2.5], [8 + r * 0.42, 8 + r, 12], {"all": "metal", "south": "intake"}, rot=rot)
    m.box([8 - r - 0.1, 7.3, 9.5], [8 + r + 0.1, 8.7, 10.3], "band")
    m.box([6, 6, 12], [10, 10, 13.5], {"all": "metal_dark", "south": "intake"})
    m.box([6.3, 6.3, 0.5], [9.7, 9.7, 2.5], {"all": "nozzle", "north": "glow"})
    m.box([7, 11, 5], [9, 12.5, 9], "mount")
    m.box([4.2, 7.5, 4], [4.8, 8.5, 10], "metal_dark")
    m.save("small_jet_engine")
    save_json({"parent": f"{MODID}:entity/small_jet_engine",
               "display": display(1.0, gui_rot=(25, 135, 0), ground=0.5, hand=0.6)},
              ASSETS, "models", "item", "small_jet_engine.json")


def bench_textures():
    rng = random.Random(11)

    def out(name):
        return path(ASSETS, "textures", "block", name + ".png")

    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            g = rng.randint(-6, 6)
            c = (46 + g, 110 + g, 70 + g, 255)
            if x % 4 == 0 or y % 4 == 0:
                c = (60 + g, 128 + g, 86 + g, 255)
            px[x, y] = c
    im.save(out("bench_mat"))

    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            g = rng.randint(-8, 8) + (10 if (y + x // 5) % 5 == 0 else 0)
            px[x, y] = (88 + g, 62 + g, 38 + g, 255)
    d = ImageDraw.Draw(im)
    d.line([0, 7, 15, 7], fill=hexc("#3e2b19"))
    d.line([0, 15, 15, 15], fill=hexc("#3e2b19"))
    im.save(out("bench_wood"))

    im = Image.new("RGBA", (16, 16), hexc("#2b2d31"))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=hexc("#111214"))
    d.rectangle([2, 2, 9, 7], fill=hexc("#0b0b0b"))
    d.text((2, 0), "350", fill=hexc("#ff3b3b"))
    d.ellipse([11, 3, 14, 6], fill=hexc("#8d9399"))
    d.rectangle([2, 10, 4, 12], fill=hexc("#45ff7b"))
    d.rectangle([6, 10, 8, 12], fill=hexc("#e3b40f"))
    im.save(out("bench_station"))

    im = Image.new("RGBA", (16, 16), hexc("#8d9399"))
    d = ImageDraw.Draw(im)
    for y in range(0, 16, 3):
        d.line([0, y, 15, y], fill=hexc("#6f757b"))
    im.save(out("bench_metal"))

    im = Image.new("RGBA", (16, 16), hexc("#b0141a"))
    d = ImageDraw.Draw(im)
    for x in range(0, 16, 3):
        d.line([x, 0, x, 15], fill=hexc("#7a0f12"))
    im.save(out("bench_handle"))

    im = Image.new("RGBA", (16, 16), hexc("#9aa0a6"))
    d = ImageDraw.Draw(im)
    d.ellipse([2, 2, 13, 13], fill=hexc("#c9c9c9"), outline=hexc("#6f757b"))
    d.ellipse([6, 6, 9, 9], fill=hexc("#3a3c42"))
    im.save(out("bench_spool"))


def build_bench():
    bench_textures()
    boxes = [
        ([0, 12, 0], [16, 13.8, 16], "wood", None, {"up": "mat"}),
        ([0.5, 13.8, 0.5], [15.5, 14.1, 15.5], "mat", None, None),
        ([1, 3, 1], [15, 4, 15], "wood", None, None),
    ]
    for x in (0.5, 13):
        for z in (0.5, 13):
            boxes.append(([x, 0, z], [x + 2.5, 12, z + 2.5], "wood", None, None))
    # soldering station + iron on its stand
    boxes += [
        ([10.5, 14.1, 0.8], [15, 16.5, 4.2], "metal", None, {"south": "station"}),
        ([11, 14.1, 5], [13.5, 14.6, 7.5], "metal", None, None),
        ([11.8, 14.6, 5.4], [12.6, 15.4, 9.5], "handle", None, None),
        ([12.0, 14.8, 9.5], [12.4, 15.2, 12.5], "metal", None, None),
        ([3, 14.1, 1.5], [5.5, 16.2, 4], "spool", None, None),
        ([1.5, 14.1, 11.5], [5, 15.8, 14.5], "metal", None, None),
        ([2.5, 15.8, 12.5], [4, 16, 13.5], "handle", None, None),
    ]
    # half-built frame lying on the mat
    for ang in (45, -45):
        boxes.append(([3, 14.1, 7.4], [10, 14.4, 8.6], "station", ([6.5, 14.25, 8], "y", ang), {"up": "metal"}))
    tex = {"particle": f"{MODID}:block/bench_wood", "wood": f"{MODID}:block/bench_wood",
           "mat": f"{MODID}:block/bench_mat", "metal": f"{MODID}:block/bench_metal",
           "station": f"{MODID}:block/bench_station", "handle": f"{MODID}:block/bench_handle",
           "spool": f"{MODID}:block/bench_spool"}
    disp = {
        "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25] * 3},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5] * 3},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375] * 3},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4] * 3},
    }
    block_model("assembly_bench", tex, boxes, disp)
    save_json({"variants": {
        "facing=north": {"model": f"{MODID}:block/assembly_bench"},
        "facing=east": {"model": f"{MODID}:block/assembly_bench", "y": 90},
        "facing=south": {"model": f"{MODID}:block/assembly_bench", "y": 180},
        "facing=west": {"model": f"{MODID}:block/assembly_bench", "y": 270},
    }}, ASSETS, "blockstates", "assembly_bench.json")
    save_json({"parent": f"{MODID}:block/assembly_bench"}, ASSETS, "models", "item", "assembly_bench.json")


def gui_texture():
    im = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    W, H = 176, 166
    bg, light, dark = hexc("#c6c6c6"), hexc("#ffffff"), hexc("#555555")
    d.rectangle([0, 0, W - 1, H - 1], fill=bg)
    d.line([1, 0, W - 3, 0], fill=hexc("#000000"))
    d.line([0, 1, 0, H - 3], fill=hexc("#000000"))
    d.line([W - 1, 2, W - 1, H - 2], fill=hexc("#000000"))
    d.line([2, H - 1, W - 2, H - 1], fill=hexc("#000000"))
    d.line([1, 1, W - 3, 1], fill=light)
    d.line([1, 1, 1, H - 3], fill=light)
    d.line([W - 2, 2, W - 2, H - 2], fill=dark)
    d.line([2, H - 2, W - 2, H - 2], fill=dark)

    def slot(x, y, size=18):
        d.rectangle([x, y, x + size - 1, y + size - 1], fill=hexc("#8b8b8b"))
        d.line([x, y, x + size - 2, y], fill=hexc("#373737"))
        d.line([x, y, x, y + size - 2], fill=hexc("#373737"))
        d.line([x + 1, y + size - 1, x + size - 1, y + size - 1], fill=light)
        d.line([x + size - 1, y + 1, x + size - 1, y + size - 1], fill=light)

    for r in range(3):
        for c in range(3):
            slot(29 + c * 18, 16 + r * 18)
    slot(119, 30, 26)
    slot(93, 57)
    # faint iron in the empty tool slot
    d.line([96, 71, 98, 69], fill=hexc("#7a7a7a"))
    d.line([98, 69, 102, 65], fill=hexc("#6e6e6e"), width=2)
    d.line([102, 65, 107, 60], fill=hexc("#7d6a6a"), width=2)
    # arrow
    d.polygon([(90, 39), (106, 39), (106, 35), (114, 43), (106, 51), (106, 47), (90, 47)], fill=hexc("#8b8b8b"))
    # mat stripe under the grid
    d.rectangle([28, 72, 85, 74], fill=hexc("#4f8a5a"))
    for r in range(3):
        for c in range(9):
            slot(7 + c * 18, 83 + r * 18)
    for c in range(9):
        slot(7 + c * 18, 141)
    im.save(path(ASSETS, "textures", "gui", "assembly_bench.png"))


# ---------------------------------------------------------------------------
# armour: parts are written in blocks relative to their pivot and scaled on save.
# unit = scale/16 block; model (8, 0, 8) sits on the pivot (see Parts.draw on the Java side)


class Part:
    def __init__(self, atlas):
        self.atlas = atlas
        self.boxes = []

    def B(self, x1, y1, z1, x2, y2, z2, faces, rot=None):
        self.boxes.append(((min(x1, x2), min(y1, y2), min(z1, z2)), (max(x1, x2), max(y1, y2), max(z1, z2)), faces, rot))
        return self

    def both(self, x1, y1, z1, x2, y2, z2, faces, rot=None):
        self.B(x1, y1, z1, x2, y2, z2, faces, rot)
        mrot = None
        if rot:
            o, axis, ang = rot
            mrot = ((-o[0], o[1], o[2]), axis, -ang if axis in ("y", "z") else ang)
        if isinstance(faces, dict) and ("east" in faces or "west" in faces):
            faces = dict(faces)
            faces["east"], faces["west"] = faces.get("west", faces.get("all")), faces.get("east", faces.get("all"))
        self.B(-x2, y1, z1, -x1, y2, z2, faces, mrot)
        return self

    def model(self, scale, offset=(0, 0, 0), center_y=False, into=None):
        m = into or Model(self.atlas)
        k = 16.0 / scale
        cy = 8 if center_y else 0

        def u(v, axis):
            return round((v + offset[axis]) * k + (cy if axis == 1 else 8), 3)

        for fr, to, faces, rot in self.boxes:
            r = None
            if rot:
                o, axis, ang = rot
                r = ([u(o[0], 0), u(o[1], 1), u(o[2], 2)], axis, ang)
            m.box([u(fr[0], 0), u(fr[1], 1), u(fr[2], 2)], [u(to[0], 0), u(to[1], 1), u(to[2], 2)], faces, rot=r)
        return m


def p_jacket(base="#3c4434"):
    """perforated barrel shroud"""
    def paint(t, rng):
        p_flat(base, 5)(t, rng)
        d = ImageDraw.Draw(t)
        for x in range(2, 15, 3):
            d.rectangle([x, 4, x + 1, 11], fill=hexc("#0f120d"))
    return paint


def p_door(base):
    def paint(t, rng):
        p_flat(base, 6)(t, rng)
        d = ImageDraw.Draw(t)
        d.rectangle([2, 2, 13, 13], outline=shade(hexc(base), 0.62))
        d.rectangle([11, 7, 12, 9], fill=hexc("#1c1f18"))
        d.point((3, 3), fill=shade(hexc(base), 1.3))
        d.point((12, 12), fill=shade(hexc(base), 1.3))
    return paint


def p_tracks():
    def paint(t, rng):
        px = t.load()
        for y in range(CELL):
            for x in range(CELL):
                g = 34 + rng.randint(-5, 5)
                if y % 4 == 0:
                    g = 22
                elif y % 4 == 1:
                    g += 22
                if x in (0, 15):
                    g = 18
                px[x, y] = (g, g, g + 3, 255)
    return paint


def p_era(base):
    """bricks of reactive armour"""
    def paint(t, rng):
        p_flat(base, 6)(t, rng)
        d = ImageDraw.Draw(t)
        for y in (0, 8):
            d.line([0, y, 15, y], fill=shade(hexc(base), 0.55))
        for x in (0, 5, 10):
            d.line([x, 0, x, 7], fill=shade(hexc(base), 0.55))
        for x in (2, 7, 12):
            d.line([x, 8, x, 15], fill=shade(hexc(base), 0.55))
    return paint


def p_tube_end(base):
    def paint(t, rng):
        p_flat(base, 5)(t, rng)
        d = ImageDraw.Draw(t)
        d.ellipse([2, 2, 13, 13], fill=hexc("#16180f"), outline=shade(hexc(base), 0.6))
        d.ellipse([5, 5, 10, 10], fill=hexc("#0a0b07"))
    return paint


def build_armor():
    a = Atlas("armor")
    a.add("olive", p_flat("#4f5a3a", 7))
    a.add("olive_dark", p_flat("#3b4430", 6))
    a.add("olive_panel", p_panel_lines("#525d3c", "#384128"))
    a.add("door", p_door("#525d3c"))
    a.add("grey", p_flat("#6b726a", 6))
    a.add("dark", p_flat("#1e211b", 4))
    a.add("black", p_flat("#141414", 3))
    a.add("steel", p_flat("#7c8288", 6, light=1.2))
    a.add("jacket", p_jacket())
    a.add("muzzle", p_vent("#2b3027", "#0d0f0b"))
    a.add("lens", p_lens(rim="#1b1d18", glass="#283a6a", glint="#b8d4ff", bg="#2a2f25"))
    a.add("lens_amber", p_lens(rim="#1b1d18", glass="#6a4a18", glint="#ffd27a", bg="#2a2f25"))
    a.add("tracks", p_tracks())
    a.add("tire", p_tire())
    a.add("hub", p_hub("#4f5a3a"))
    a.add("era", p_era("#56603f"))
    a.add("grille", p_stripes("#1c1f16", "#3a4529", 2))
    a.add("tube", p_flat("#5a6440", 6))
    a.add("tube_end", p_tube_end("#5a6440"))
    a.add("tube_green", p_flat("#3d5a2e", 7))
    a.add("cap", p_flat("#b0821c", 8, light=1.2))
    a.add("hazard", p_hazard("#d0b020", "#18191a"))
    a.add("headlight", p_led("#fff4c8", "#18191a"))
    a.add("taillight", p_led("#d01b1b", "#18191a"))
    a.add("drum", p_stripes("#3e4630", "#2f3624", 2, vertical=True))
    a.add("tracer", p_flat("#ffb347", 4, border=1.0, light=1.0))
    a.add("tracer_core", p_flat("#fff2c0", 2, border=1.0, light=1.0))
    a.add("missile", p_flat("#5d6b45", 6))
    a.add("missile_nose", p_flat("#3a3f33", 5, light=1.25))
    a.add("missile_band", p_stripes("#b8962e", "#8a6f20", 2))
    a.add("fin", p_flat("#2e3329", 4))
    a.add("jav_body", p_flat("#6b6f4e", 6))
    a.add("jav_dome", p_lens(rim="#2a2b2e", glass="#5a5f63", glint="#e8eef2", bg="#6b6f4e"))
    a.add("plume", p_flat("#ff8a2a", 4, border=1.0, light=1.0))
    a.add("tan", p_flat("#8c8156", 7))
    a.add("tan_dark", p_flat("#6c6440", 6))
    a.add("strap", p_flat("#2f3226", 4))
    a.save()

    # -- 30 mm remote weapon station on a pedestal (scale 2)
    base = Part(a)
    base.B(-0.68, 0, -0.68, 0.68, 0.08, 0.68, "olive_dark")
    base.B(-0.55, 0.08, -0.55, 0.55, 1.05, 0.55, {"all": "door", "up": "olive", "down": "olive_dark"})
    base.B(-0.6, 1.05, -0.6, 0.6, 1.12, 0.6, "olive_dark")
    base.B(-0.42, 1.12, -0.42, 0.42, 1.15, 0.42, "dark")
    base.both(0.55, 0.45, -0.1, 0.59, 0.5, 0.1, "dark")
    base.B(-0.1, 0.45, 0.55, 0.1, 0.5, 0.59, "dark")
    for x in (-0.62, 0.58):
        base.B(x, 0.0, -0.62, x + 0.04, 0.12, -0.58, "steel")
        base.B(x, 0.0, 0.58, x + 0.04, 0.12, 0.62, "steel")
    base.model(2).save("adgun_base")

    tur = Part(a)
    # armoured boxes either side of the gun, faceted fronts
    tur.both(0.3, 0.02, -0.52, 0.76, 0.6, 0.32, {"all": "olive_panel", "up": "olive", "down": "olive_dark",
                                                 "south": "olive"})
    tur.both(0.3, 0.06, 0.28, 0.66, 0.52, 0.48, "olive", rot=((0.48, 0.3, 0.38), "y", 22.5))
    tur.both(0.76, 0.12, -0.42, 0.8, 0.5, 0.2, "door")
    tur.B(-0.3, 0.02, -0.52, 0.3, 0.2, 0.3, "olive_dark")
    tur.B(-0.3, 0.2, -0.52, 0.3, 0.62, -0.3, "olive_panel")
    tur.B(-0.32, 0.02, -0.55, 0.32, 0.08, 0.35, "dark")
    # optics block on a short mast, top left
    tur.B(0.46, 0.6, -0.05, 0.58, 0.72, 0.12, "dark")
    tur.B(0.4, 0.72, -0.12, 0.66, 0.94, 0.22, {"all": "grey", "south": "lens", "up": "olive_dark"})
    tur.B(0.42, 0.94, -0.05, 0.64, 0.97, 0.15, "dark")
    # little sensor box on the right
    tur.B(-0.62, 0.6, -0.2, -0.42, 0.72, 0.05, {"all": "olive_dark", "south": "lens_amber"})
    tur.model(2).save("adgun_turret")

    gun = Part(a)
    gun.B(-0.17, -0.15, -0.5, 0.17, 0.15, 0.55, {"all": "olive_panel", "south": "dark"})
    gun.B(-0.12, 0.15, -0.35, 0.12, 0.22, 0.5, "olive_dark")
    gun.B(-0.2, -0.05, -0.1, -0.17, 0.08, 0.3, "dark")
    gun.B(-0.088, -0.088, 0.55, 0.088, 0.088, 2.05, {"all": "jacket", "south": "dark", "north": "dark"})
    gun.B(-0.05, -0.05, 2.05, 0.05, 0.05, 2.35, "black")
    gun.B(-0.085, -0.085, 2.35, 0.085, 0.085, 2.6, {"all": "muzzle", "south": "black"})
    gun.B(-0.1, -0.1, 0.55, 0.1, 0.1, 0.68, "olive_dark")
    gun.model(2).save("adgun_gun")

    icon = base.model(2)
    tur.model(2, offset=(0, 1.15, 0), into=icon)
    gun.model(2, offset=(-0.05, 1.15 + 0.42, 0.15), into=icon)
    icon.save("air_defense_gun", display(0.55, gui_rot=(25, 210, 0), ground=0.3, hand=0.3, fixed=0.5, gui_tr=(0, -3, 0)),
              folder="item")

    # -- Kornet on a tripod (scale 1)
    tri = Part(a)
    tri.B(-0.06, 0.45, -0.06, 0.06, 0.78, 0.06, "dark")
    tri.B(-0.1, 0.62, -0.1, 0.1, 0.7, 0.1, "olive_dark")
    # three legs splayed out from the hub: front, left, right
    tri.B(-0.03, 0.0, 0.05, 0.03, 0.72, 0.11, "olive_dark", rot=((0, 0.7, 0.08), "x", -22.5))
    tri.B(0.05, 0.0, -0.03, 0.11, 0.72, 0.03, "olive_dark", rot=((0.08, 0.7, 0), "z", 22.5))
    tri.B(-0.11, 0.0, -0.03, -0.05, 0.72, 0.03, "olive_dark", rot=((-0.08, 0.7, 0), "z", -22.5))
    for x, z in ((0, 0.36), (0.36, 0), (-0.36, 0)):
        tri.B(x - 0.05, 0, z - 0.05, x + 0.05, 0.03, z + 0.05, "dark")
    tri.model(1).save("kornet_tripod")

    head = Part(a)
    head.B(-0.1, 0, -0.12, 0.1, 0.06, 0.12, "olive_dark")
    head.both(0.08, 0.02, -0.04, 0.11, 0.17, 0.04, "olive_dark")
    head.model(1).save("kornet_head")

    sight = Part(a)
    sight.B(-0.07, -0.15, -0.3, 0.07, -0.09, 0.42, "dark")
    sight.B(0.11, -0.1, -0.32, 0.33, 0.12, 0.16, {"all": "olive", "south": "lens", "up": "olive_panel"})
    sight.B(0.18, -0.03, -0.44, 0.27, 0.06, -0.32, "black")
    sight.B(0.15, 0.12, -0.2, 0.29, 0.16, 0.05, "olive_dark")
    sight.B(0.07, -0.08, -0.05, 0.11, 0.0, 0.05, "dark")
    sight.model(1).save("kornet_sight")

    tube = Part(a)
    tube.B(-0.09, -0.09, -0.45, 0.09, 0.09, 0.75, {"all": "tube_green", "south": "tube_end", "north": "tube_end"})
    tube.B(-0.1, -0.1, 0.62, 0.1, 0.1, 0.68, "olive_dark")
    tube.B(-0.1, -0.1, -0.4, 0.1, 0.1, -0.34, "olive_dark")
    tube.B(-0.02, 0.09, 0.05, 0.02, 0.12, 0.3, "strap")
    tube.model(1).save("kornet_tube")

    icon = tri.model(1)
    head.model(1, offset=(0, 0.78, 0), into=icon)
    sight.model(1, offset=(0, 0.92, 0), into=icon)
    tube.model(1, offset=(0, 0.92, 0), into=icon)
    icon.save("kornet", display(0.7, gui_rot=(25, 210, 0), ground=0.4, hand=0.4, fixed=0.6, gui_tr=(0, -3, 0)),
              folder="item")

    # -- BMPT "Terminator" (scale 3)
    hull = Part(a)
    hull.B(-1.12, 0.45, -3.1, 1.12, 1.4, 2.25, {"all": "olive", "up": "olive_panel"})
    hull.B(-1.12, 0.45, 2.25, 1.12, 1.15, 3.0, "olive")
    # upper glacis with ERA, and the lower nose plate
    hull.B(-1.15, 1.05, 2.0, 1.15, 1.2, 3.25, {"all": "era", "down": "olive_dark"}, rot=((0, 1.12, 2.2), "x", 22.5))
    hull.B(-1.1, 0.4, 2.95, 1.1, 0.95, 3.1, "olive_dark", rot=((0, 0.68, 3.0), "x", -22.5))
    hull.both(0.9, 0.62, 3.0, 1.08, 0.74, 3.08, "headlight")
    hull.B(-0.25, 1.4, 1.8, 0.25, 1.48, 2.3, {"all": "dark", "up": "olive_dark"})
    hull.B(-0.12, 1.48, 2.18, 0.12, 1.56, 2.28, "lens")
    # track shelves and side skirts with reactive armour
    hull.both(1.12, 1.18, -3.2, 1.66, 1.26, 3.1, "olive_dark")
    hull.both(1.62, 0.72, -2.9, 1.68, 1.22, 2.9, {"all": "era", "up": "olive_dark"})
    # tracks: bottom run, top run, drive sprocket at the back, idler at the front
    hull.both(1.18, 0.0, -2.95, 1.6, 0.14, 2.85, "tracks")
    hull.both(1.18, 0.98, -3.05, 1.6, 1.1, 2.95, "tracks")
    hull.both(1.18, 0.14, -3.05, 1.6, 0.98, -2.82, {"all": "tracks", "east": "hub", "west": "hub"})
    hull.both(1.18, 0.2, 2.75, 1.6, 0.95, 2.98, {"all": "tracks", "east": "hub", "west": "hub"})
    hull.both(1.12, 0.3, -2.8, 1.18, 1.0, 2.75, "dark")
    # engine deck, exhaust, fuel drums on the back
    hull.B(-1.0, 1.4, -3.0, 1.0, 1.46, -1.4, "grille")
    hull.B(1.25, 1.26, -2.2, 1.6, 1.34, -1.7, "black")
    hull.both(0.25, 1.15, -3.45, 1.0, 1.55, -3.1, {"all": "drum", "north": "olive_dark", "south": "olive_dark"})
    hull.both(0.95, 0.55, -3.14, 1.08, 0.7, -3.1, "taillight")
    hull.B(-0.4, 0.5, -3.16, 0.4, 0.62, -3.1, "steel")
    hull.model(3).save("bmpt_hull")

    wheel = Part(a)
    r, wdt = 0.42, 0.15
    for rot in (None, ((0, 0, 0), "x", 45)):
        wheel.B(-wdt, -r, -r * 0.42, wdt, r, r * 0.42, {"all": "tire", "east": "hub", "west": "hub"}, rot=rot)
        wheel.B(-wdt, -r * 0.42, -r, wdt, r * 0.42, r, {"all": "tire", "east": "hub", "west": "hub"}, rot=rot)
    wheel.B(-wdt - 0.02, -0.12, -0.12, wdt + 0.02, 0.12, 0.12, "hub")
    wheel.model(0.5).save("bmpt_wheel")

    tr = Part(a)
    tr.B(-1.0, 0.0, -1.2, 1.0, 0.42, 0.9, {"all": "olive", "up": "olive_panel"})
    tr.B(-0.95, 0.02, 0.9, 0.95, 0.38, 1.25, "era", rot=((0, 0.2, 1.0), "x", -22.5))
    tr.both(0.95, 0.04, -1.1, 1.08, 0.4, 0.8, "era")
    tr.B(-0.9, 0.0, -1.55, 0.9, 0.35, -1.2, {"all": "olive_dark", "north": "door"})
    tr.both(0.48, 0.42, 0.25, 0.56, 0.86, 0.85, "olive_dark")
    tr.B(-0.85, 0.42, 0.1, -0.42, 0.95, 0.55, {"all": "olive_panel", "south": "lens", "up": "olive_dark"})
    tr.B(0.42, 0.42, -0.75, 0.62, 0.8, -0.45, "dark")
    tr.B(0.36, 0.8, -0.82, 0.68, 1.02, -0.38, {"all": "grey", "south": "lens", "north": "lens", "east": "lens"})
    tr.B(-0.3, 0.42, -0.9, 0.3, 0.5, -0.4, "dark")
    tr.model(3).save("bmpt_turret")

    guns = Part(a)
    guns.B(-0.44, -0.2, -0.6, 0.44, 0.2, 0.85, {"all": "olive_panel", "south": "dark"})
    guns.B(-0.36, 0.2, -0.45, 0.36, 0.28, 0.7, "olive_dark")
    for x in (-0.2, 0.2):
        guns.B(x - 0.06, -0.06, 0.85, x + 0.06, 0.06, 1.15, "dark")
        guns.B(x - 0.045, -0.045, 1.15, x + 0.045, 0.045, 2.3, "black")
        guns.B(x - 0.07, -0.07, 2.3, x + 0.07, 0.07, 2.45, {"all": "muzzle", "south": "black"})
    # ATGM pods on arms
    guns.both(0.44, -0.06, -0.1, 0.96, 0.06, 0.3, "olive_dark")
    guns.both(0.96, -0.3, -0.75, 1.3, 0.38, 1.2, {"all": "tube", "south": "tube_end", "north": "olive_dark"})
    guns.both(0.95, 0.03, -0.75, 1.31, 0.05, 1.2, "olive_dark")
    guns.model(3).save("bmpt_guns")

    cap = Part(a)
    cap.B(-0.13, -0.13, 0.0, 0.13, 0.13, 0.05, {"all": "cap", "south": "missile_nose"})
    cap.model(0.5).save("ataka_cap")

    icon = hull.model(4)
    for z in (2.25, 1.35, 0.45, -0.45, -1.35, -2.25):
        for side in (-1, 1):
            wheel.model(4, offset=(side * 1.38, 0.54, z), into=icon)
    tr.model(4, offset=(0, 1.4, -0.35), into=icon)
    guns.model(4, offset=(0, 1.4 + 0.78, -0.35 + 0.55), into=icon)
    icon.save("terminator", display(0.38, gui_rot=(25, 210, 0), ground=0.25, hand=0.2, fixed=0.35, gui_tr=(0, -2, 0)),
              folder="item")

    # -- missiles in flight (scale 1, centred)
    def missile(name, rad, length, body, nose, band=True, mid_fins=False):
        p = Part(a)
        half = length / 2
        p.B(-rad, -rad, -half, rad, rad, half - 0.15, {"all": body, "north": "plume"})
        p.B(-rad * 0.7, -rad * 0.7, half - 0.15, rad * 0.7, rad * 0.7, half, {"all": nose, "south": "jav_dome" if nose == "jav_dome" else nose})
        if band:
            p.B(-rad - 0.005, -rad - 0.005, half - 0.32, rad + 0.005, rad + 0.005, half - 0.27, "missile_band")
        span = rad * 2.4
        p.B(-span, -0.008, -half, span, 0.008, -half + 0.16, "fin")
        p.B(-0.008, -span, -half, 0.008, span, -half + 0.16, "fin")
        if mid_fins:
            p.B(-span * 0.8, -0.008, -0.05, span * 0.8, 0.008, 0.12, "fin")
            p.B(-0.008, -span * 0.8, -0.05, 0.008, span * 0.8, 0.12, "fin")
        else:
            p.B(-rad * 2, -0.006, half - 0.42, rad * 2, 0.006, half - 0.36, "fin")
        p.model(1, center_y=True).save(name + "_flight")
        return p

    missile("kornet_missile", 0.075, 1.1, "missile", "missile_nose")
    missile("ataka_missile", 0.085, 1.3, "missile", "missile_nose")
    missile("javelin_missile", 0.07, 1.0, "jav_body", "jav_dome", band=False, mid_fins=True)

    tracer = Model(a)
    tracer.box([7.4, 7.4, -8], [8.6, 8.6, 24], "tracer")
    tracer.box([7.7, 7.7, 12], [8.3, 8.3, 24.5], "tracer_core")
    tracer.save("tracer")

    # -- Javelin launch tube with the CLU, as a held item (1 unit = 1/16 block)
    jav = Part(a)
    jav.B(-0.11, -0.11, -0.62, 0.11, 0.11, 0.62, {"all": "tan", "south": "dark", "north": "dark"})
    jav.B(-0.13, -0.13, 0.5, 0.13, 0.13, 0.62, "tan_dark")
    jav.B(-0.13, -0.13, -0.62, 0.13, 0.13, -0.5, "tan_dark")
    jav.B(-0.3, -0.1, -0.05, -0.1, 0.12, 0.3, {"all": "olive_dark", "south": "lens", "north": "black"})
    jav.B(-0.26, 0.12, 0.0, -0.14, 0.16, 0.25, "dark")
    jav.B(-0.02, -0.26, 0.02, 0.04, -0.11, 0.1, "black")
    jav.B(-0.02, 0.11, -0.4, 0.02, 0.13, 0.4, "strap")
    jav_m = jav.model(1, center_y=True)
    jav_m.save("javelin", {
        "gui": {"rotation": [25, 225, 0], "translation": [0, 0, 0], "scale": [0.75] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5] * 3},
        "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.7] * 3},
        "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.7] * 3},
        "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 6, 2], "scale": [0.8] * 3},
        "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 6, 2], "scale": [0.8] * 3},
        "firstperson_righthand": {"rotation": [0, 0, 0], "translation": [2, 2, -2], "scale": [0.8] * 3},
        "firstperson_lefthand": {"rotation": [0, 0, 0], "translation": [-2, 2, -2], "scale": [0.8] * 3},
    }, folder="item")


def build_weapon_icons():
    pixel_icon("ammo_30mm", [
        "................",
        "................",
        "..y...y...y...y.",
        ".yyy.yyy.yyy.yyy",
        ".yyy.yyy.yyy.yyy",
        ".bbb.bbb.bbb.bbb",
        ".ggg.ggg.ggg.ggg",
        ".ggg.ggg.ggg.ggg",
        ".ggg.ggg.ggg.ggg",
        ".ggg.ggg.ggg.ggg",
        ".ccc.ccc.ccc.ccc",
        "kkkkkkkkkkkkkkkk",
        "kmmkmmkmmkmmkmmk",
        "kkkkkkkkkkkkkkkk",
        "................",
        "................",
    ], {"y": "#c9b23f", "b": "#d01b1b", "g": "#b98a3a", "c": "#8a6420", "k": "#1d2017", "m": "#4b5320"})

    def missile_icon(name, body, band, cap):
        pixel_icon(name, [
            "................",
            "...........cc...",
            "..........cbbc..",
            ".........cbbbbc.",
            "........kbbbbc..",
            ".......kbbbbk...",
            "......kbbbbk....",
            ".....kbbbbk.....",
            "....kyyybk......",
            "...kbbbyk.......",
            "..kbbbbk........",
            ".cbbbbk.........",
            "cbbbbk..........",
            ".cbbk...........",
            "..cc............",
            "................",
        ], {"b": body, "y": band, "k": "#1a1c15", "c": cap})

    missile_icon("kornet_missile", "#3d5a2e", "#c9b23f", "#22291a")
    missile_icon("ataka_missile", "#5d6b45", "#d01b1b", "#2e3329")
    missile_icon("javelin_missile", "#8c8156", "#2f3226", "#5e563a")

    pixel_icon("autocannon", [
        "................",
        "................",
        "................",
        "................",
        "............mm..",
        "kk.k.k.k.k.kmmkk",
        "kggggggggggggggk",
        "kk.k.k.k.k.kmmkk",
        "kkkkk.......mm..",
        "kooookk.........",
        "kooookk.........",
        "kkoook..........",
        "..kk............",
        "................",
        "................",
        "................",
    ], {"k": "#1b1e17", "g": "#3c4434", "o": "#525d3c", "m": "#2b3027"})


def build_remote_icons():
    """32x32 textures for the two radios, drawn rather than pixel-mapped"""
    S = 32

    def save(name, im):
        im.save(path(ASSETS, "textures", "item", name + ".png"))
        save_json({"parent": "item/generated", "textures": {"layer0": f"{MODID}:item/{name}"}},
                  ASSETS, "models", "item", name + ".json")

    def gimbal(d, cx, cy, r, ring, well, stick):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ring)
        d.ellipse([cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1], fill=well)
        d.ellipse([cx - 2, cy - 2, cx + 1, cy + 1], fill=stick)
        d.point((cx - 1, cy - 2), fill=hexc("#d9dde1"))

    # FPV radio: black body, two gimbals, small screen, single antenna, switches on the shoulders
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    body, edge, hi = hexc("#24262b"), hexc("#0b0c0e"), hexc("#3b3f46")
    d.line([16, 1, 16, 9], fill=hexc("#16171a"), width=2)
    d.rectangle([15, 0, 17, 2], fill=hexc("#2b2d31"))
    d.rounded_rectangle([1, 9, 30, 27], radius=5, fill=body, outline=edge) if hasattr(d, "rounded_rectangle") \
        else d.rectangle([1, 9, 30, 27], fill=body, outline=edge)
    d.polygon([(3, 26), (9, 26), (8, 31), (4, 31)], fill=body, outline=edge)
    d.polygon([(22, 26), (28, 26), (27, 31), (23, 31)], fill=body, outline=edge)
    d.line([3, 10, 28, 10], fill=hi)
    for x in (4, 7, 24, 27):
        d.rectangle([x, 6, x + 1, 9], fill=hexc("#8d939b"))
        d.point((x, 6), fill=hexc("#d9dde1"))
    gimbal(d, 8, 18, 5, hexc("#4a4e55"), hexc("#111214"), hexc("#9aa0a8"))
    gimbal(d, 23, 18, 5, hexc("#4a4e55"), hexc("#111214"), hexc("#9aa0a8"))
    d.rectangle([13, 12, 18, 17], fill=hexc("#07160b"), outline=hexc("#0b0c0e"))
    d.line([14, 14, 16, 14], fill=hexc("#45ff7b"))
    d.point((14, 16), fill=hexc("#45ff7b"))
    d.point((17, 16), fill=hexc("#e3b40f"))
    d.rectangle([14, 20, 15, 21], fill=hexc("#d01b1b"))
    d.rectangle([16, 20, 17, 21], fill=hexc("#5a5e66"))
    d.text((12, 22), "", fill=hi)
    save("drone_remote", im)

    # DJI RC: light grey, sticks on top, buttons between, big screen underneath
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    grey, grey_d, grey_l = hexc("#c4c7cb"), hexc("#8f9398"), hexc("#dfe2e5")
    shape = [2, 6, 29, 29]
    if hasattr(d, "rounded_rectangle"):
        d.rounded_rectangle(shape, radius=5, fill=grey, outline=grey_d)
    else:
        d.rectangle(shape, fill=grey, outline=grey_d)
    d.rectangle([1, 10, 3, 26], fill=hexc("#6f7378"))
    d.rectangle([28, 10, 30, 26], fill=hexc("#6f7378"))
    d.line([5, 7, 26, 7], fill=grey_l)
    d.rectangle([4, 5, 8, 6], fill=hexc("#5c6065"))
    d.rectangle([23, 5, 27, 6], fill=hexc("#5c6065"))
    gimbal(d, 8, 11, 4, hexc("#a3a7ac"), hexc("#3d4045"), hexc("#d9dde1"))
    gimbal(d, 23, 11, 4, hexc("#a3a7ac"), hexc("#3d4045"), hexc("#d9dde1"))
    for x in (14, 15, 16, 17):
        d.point((x, 8), fill=hexc("#6f7378"))
    d.ellipse([12, 11, 14, 13], fill=hexc("#3d4045"))
    d.point((13, 12), fill=hexc("#ff8a2a"))
    d.rectangle([15, 11, 17, 13], fill=hexc("#5c6065"))
    d.ellipse([18, 11, 20, 13], fill=hexc("#3d4045"))
    # screen with a little aerial picture on it
    d.rectangle([5, 16, 26, 27], fill=hexc("#0a0b0c"))
    d.rectangle([6, 17, 25, 26], fill=hexc("#3f6a8a"))
    d.rectangle([6, 22, 25, 26], fill=hexc("#4f6b3a"))
    d.line([6, 22, 25, 21], fill=hexc("#6f8a4a"))
    d.rectangle([12, 23, 14, 24], fill=hexc("#8a7a5a"))
    d.line([15, 21, 16, 21], fill=hexc("#ffffff"))
    d.line([15, 19, 15, 23], fill=hexc("#ffffff"))
    d.line([6, 17, 25, 17], fill=hexc("#1a1c1e"))
    d.point((24, 17), fill=hexc("#45ff7b"))
    d.point((7, 17), fill=hexc("#ffffff"))
    save("mavic_remote", im)


def build_beacon_and_battery():
    # depleted pack: same look, empty gauge
    def depleted(d, im):
        K = hexc("#0e0f11")
        d.rectangle([1, 4, 12, 11], fill=hexc("#17181b"), outline=K)
        d.rectangle([1, 6, 12, 9], fill=hexc("#6b5a1a"))
        d.rectangle([2, 7, 3, 8], fill=hexc("#d01b1b"))
        d.line([12, 6, 14, 5], fill=hexc("#7a1214"))
        d.line([12, 9, 14, 10], fill=hexc("#111111"))
        d.rectangle([14, 5, 15, 10], fill=hexc("#7a6a2a"))
        d.point((8, 2), fill=hexc("#d01b1b"))
        d.point((8, 13), fill=hexc("#d01b1b"))
    draw_icon("depleted_battery", depleted)


# ---------------------------------------------------------------------------
# lang

RU = {
    "itemGroup.fpvdrones": "FPV Drones",
    "item.fpvdrones.frame": "Рама 7\"",
    "item.fpvdrones.motor": "Бесколлекторный мотор",
    "item.fpvdrones.esc": "Регулятор 4в1",
    "item.fpvdrones.flight_stack": "Полётный стек",
    "item.fpvdrones.battery": "АКБ LiPo 6S",
    "item.fpvdrones.fpv_camera": "FPV-камера",
    "item.fpvdrones.vtx": "Видеопередатчик",
    "item.fpvdrones.receiver": "Приёмник ELRS",
    "item.fpvdrones.antenna": "Антенна",
    "item.fpvdrones.gimbal_camera": "Камера на подвесе",
    "item.fpvdrones.gnss_module": "Модуль ГНСС",
    "item.fpvdrones.display": "Дисплей",
    "item.fpvdrones.airframe": "Планер БПЛА",
    "item.fpvdrones.piston_engine": "Поршневой двигатель",
    "item.fpvdrones.small_jet_engine": "Малый реактивный двигатель",
    "item.fpvdrones.warhead": "Боевая часть",
    "item.fpvdrones.booster": "Стартовый ускоритель",
    "item.fpvdrones.soldering_iron": "Паяльник",
    "block.fpvdrones.assembly_bench": "Сборочный верстак",
    "screen.fpvdrones.needs_iron": "Нужен паяльник",
    "tooltip.fpvdrones.part": "Запчасть",
    "tooltip.fpvdrones.soldering_iron": "Кладётся в слот сборочного верстака.",
    "item.fpvdrones.fpv_goggles": "ФПВ-очки",
    "item.fpvdrones.drone_remote": "Пульт управления дроном",
    "item.fpvdrones.fpv_drone": "FPV-дрон",
    "item.fpvdrones.mavic3": "Мавик-3",
    "item.fpvdrones.shahed136": "Шахед-136",
    "item.fpvdrones.shahed238": "Шахед-238",
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
    "tooltip.fpvdrones.drone_remote": "Shift+ПКМ по дрону — привязать. Картинка — в FPV-очках.",
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
    "item.fpvdrones.mavic_remote": "Пульт Мавика (DJI RC)",
    "item.fpvdrones.radio_beacon": "Радиометка",
    "item.fpvdrones.depleted_battery": "Севший АКБ LiPo",
    "item.fpvdrones.terminator": "БМПТ «Терминатор»",
    "item.fpvdrones.air_defense_gun": "30-мм зенитный боевой модуль",
    "item.fpvdrones.kornet": "ПТРК «Корнет»",
    "item.fpvdrones.javelin": "ПТРК «Javelin»",
    "item.fpvdrones.ammo_30mm": "Лента 30-мм ОФЗ (50 шт.)",
    "item.fpvdrones.kornet_missile": "ПТУР 9М133 «Корнет»",
    "item.fpvdrones.ataka_missile": "ПТУР 9М120 «Атака»",
    "item.fpvdrones.javelin_missile": "Ракета FGM-148 в ТПК",
    "item.fpvdrones.autocannon": "30-мм пушка 2А42",
    "entity.fpvdrones.radio_beacon": "Радиометка",
    "entity.fpvdrones.terminator": "БМПТ «Терминатор»",
    "entity.fpvdrones.air_defense_gun": "30-мм зенитный модуль",
    "entity.fpvdrones.kornet": "ПТРК «Корнет»",
    "entity.fpvdrones.shell": "30-мм снаряд",
    "entity.fpvdrones.missile": "ПТУР",
    "key.fpvdrones.return_home": "Мавик: домой (RTH)",
    "screen.fpvdrones.beacon": "Код метки",
    "screen.fpvdrones.beacon_hint": "0 — без метки",
    "msg.fpvdrones.rc_unbound": "Пульт не привязан — Shift+ПКМ по Мавику",
    "msg.fpvdrones.rc_mavic_only": "Этот пульт только для Мавика",
    "msg.fpvdrones.rth_manual": "Мавик возвращается домой. Точка сохранена: %s",
    "msg.fpvdrones.rth_battery": "Заряд 20%% — Мавик сам летит домой. Точка сохранена: %s",
    "msg.fpvdrones.rth_cancel": "Возврат домой отменён",
    "msg.fpvdrones.rth_landed": "Мавик сел на точку взлёта",
    "msg.fpvdrones.rth_no_home": "Точка взлёта не записана",
    "msg.fpvdrones.beacon_dropped": "Метка #%s сброшена",
    "msg.fpvdrones.beacon_landed": "Метка #%s легла: %s — код уже в меню пусковой",
    "msg.fpvdrones.battery_full": "АКБ и так полный",
    "msg.fpvdrones.battery_swapped": "АКБ заменён",
    "msg.fpvdrones.adgun_auto_on": "Автоматический режим: огонь по чужим дронам",
    "msg.fpvdrones.adgun_auto_off": "Ручной режим",
    "msg.fpvdrones.ammo": "Боекомплект: %s/%s",
    "msg.fpvdrones.ammo_full": "Боекомплект полный",
    "msg.fpvdrones.missiles": "ПТУР: %s/%s",
    "msg.fpvdrones.no_missiles": "Нет ракет",
    "msg.fpvdrones.no_lock": "Нет захвата — держи цель в рамке",
    "tooltip.fpvdrones.mavic_remote": "Shift+ПКМ по Мавику — привязать. ПКМ — лететь, очки не нужны.",
    "tooltip.fpvdrones.radio_beacon": "Вешается на Мавик. Куда упадёт — туда наведутся Шахеды/Герани.",
    "tooltip.fpvdrones.terminator": "ПКМ — сесть (1-й водитель, 2-й наводчик). ПКМ лентой/ПТУР — зарядить. Shift+ПКМ — забрать.",
    "tooltip.fpvdrones.air_defense_gun": "ПКМ — сесть за прицел, ЛКМ — огонь, [G] или Shift+ПКМ — автомат по дронам.",
    "tooltip.fpvdrones.kornet": "ПКМ — к прицелу, ЛКМ — пуск. Держи прицел на цели до попадания.",
    "tooltip.fpvdrones.javelin": "Зажми ПКМ, держи цель до захвата, отпусти — ракета летит сама.",
    "tooltip.fpvdrones.ammo_30mm": "Для зенитного модуля и БМПТ.",
    "tooltip.fpvdrones.kornet_missile": "Для «Корнета». Запасные в инвентаре заряжаются сами.",
    "tooltip.fpvdrones.ataka_missile": "Для БМПТ «Терминатор», 4 штуки.",
    "tooltip.fpvdrones.javelin_missile": "Для ПТРК «Javelin».",
    "death.attack.fpvdrones.shell": "%1$s разорвало 30-мм снарядом",
    "death.attack.fpvdrones.shell.player": "%1$s разорвало 30-мм снарядом, пока он убегал от %2$s",
    "death.attack.fpvdrones.shell_by": "%1$s разорвало 30-мм снарядом от %2$s",
    "death.attack.fpvdrones.shell_by.item": "%1$s разорвало 30-мм снарядом от %2$s",
    "subtitles.fpvdrones.cannon_30": "Бьёт 30-мм пушка",
    "subtitles.fpvdrones.atgm_launch": "Пуск ПТУР",
    "subtitles.fpvdrones.missile_motor": "Летит ракета",
    "subtitles.fpvdrones.reload": "Перезарядка",
    "subtitles.fpvdrones.seeker_tone": "ГСН ищет цель",
    "subtitles.fpvdrones.lock_tone": "Захват цели",
    "subtitles.fpvdrones.tracks": "Лязг гусениц",
}

EN = {
    "itemGroup.fpvdrones": "FPV Drones",
    "item.fpvdrones.frame": "7\" Frame",
    "item.fpvdrones.motor": "Brushless Motor",
    "item.fpvdrones.esc": "4-in-1 ESC",
    "item.fpvdrones.flight_stack": "Flight Stack",
    "item.fpvdrones.battery": "6S LiPo Battery",
    "item.fpvdrones.fpv_camera": "FPV Camera",
    "item.fpvdrones.vtx": "Video Transmitter",
    "item.fpvdrones.receiver": "ELRS Receiver",
    "item.fpvdrones.antenna": "Antenna",
    "item.fpvdrones.gimbal_camera": "Gimbal Camera",
    "item.fpvdrones.gnss_module": "GNSS Module",
    "item.fpvdrones.display": "Display",
    "item.fpvdrones.airframe": "UAV Airframe",
    "item.fpvdrones.piston_engine": "Piston Engine",
    "item.fpvdrones.small_jet_engine": "Small Jet Engine",
    "item.fpvdrones.warhead": "Warhead",
    "item.fpvdrones.booster": "Launch Booster",
    "item.fpvdrones.soldering_iron": "Soldering Iron",
    "block.fpvdrones.assembly_bench": "Assembly Bench",
    "screen.fpvdrones.needs_iron": "Needs a soldering iron",
    "tooltip.fpvdrones.part": "Part",
    "tooltip.fpvdrones.soldering_iron": "Goes into the assembly bench tool slot.",
    "item.fpvdrones.fpv_goggles": "FPV Goggles",
    "item.fpvdrones.drone_remote": "Drone Controller",
    "item.fpvdrones.fpv_drone": "FPV Drone",
    "item.fpvdrones.mavic3": "Mavic 3",
    "item.fpvdrones.shahed136": "Shahed-136",
    "item.fpvdrones.shahed238": "Shahed-238",
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
    "tooltip.fpvdrones.drone_remote": "Shift+RMB a drone to bind. Watch through FPV goggles.",
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
    "item.fpvdrones.mavic_remote": "Mavic Controller (DJI RC)",
    "item.fpvdrones.radio_beacon": "Radio Beacon",
    "item.fpvdrones.depleted_battery": "Depleted LiPo Battery",
    "item.fpvdrones.terminator": "BMPT \"Terminator\"",
    "item.fpvdrones.air_defense_gun": "30 mm Air Defense Station",
    "item.fpvdrones.kornet": "Kornet ATGM",
    "item.fpvdrones.javelin": "Javelin",
    "item.fpvdrones.ammo_30mm": "30 mm HE Belt (50)",
    "item.fpvdrones.kornet_missile": "9M133 Kornet Missile",
    "item.fpvdrones.ataka_missile": "9M120 Ataka Missile",
    "item.fpvdrones.javelin_missile": "FGM-148 Missile Tube",
    "item.fpvdrones.autocannon": "2A42 30 mm Cannon",
    "entity.fpvdrones.radio_beacon": "Radio Beacon",
    "entity.fpvdrones.terminator": "BMPT \"Terminator\"",
    "entity.fpvdrones.air_defense_gun": "30 mm Air Defense Station",
    "entity.fpvdrones.kornet": "Kornet ATGM",
    "entity.fpvdrones.shell": "30 mm Shell",
    "entity.fpvdrones.missile": "ATGM",
    "key.fpvdrones.return_home": "Mavic: return to home",
    "screen.fpvdrones.beacon": "Beacon code",
    "screen.fpvdrones.beacon_hint": "0 = none",
    "msg.fpvdrones.rc_unbound": "Controller not bound - Shift+RMB a Mavic",
    "msg.fpvdrones.rc_mavic_only": "This controller only works with a Mavic",
    "msg.fpvdrones.rth_manual": "Mavic returning home. Point saved: %s",
    "msg.fpvdrones.rth_battery": "Battery at 20%% - Mavic is flying home. Point saved: %s",
    "msg.fpvdrones.rth_cancel": "Return to home cancelled",
    "msg.fpvdrones.rth_landed": "Mavic landed at the home point",
    "msg.fpvdrones.rth_no_home": "No home point recorded",
    "msg.fpvdrones.beacon_dropped": "Beacon #%s dropped",
    "msg.fpvdrones.beacon_landed": "Beacon #%s landed at %s - the code is in the launcher menu",
    "msg.fpvdrones.battery_full": "Battery is already full",
    "msg.fpvdrones.battery_swapped": "Battery swapped",
    "msg.fpvdrones.adgun_auto_on": "Auto mode: engaging hostile drones",
    "msg.fpvdrones.adgun_auto_off": "Manual mode",
    "msg.fpvdrones.ammo": "Ammo: %s/%s",
    "msg.fpvdrones.ammo_full": "Fully loaded",
    "msg.fpvdrones.missiles": "Missiles: %s/%s",
    "msg.fpvdrones.no_missiles": "No missiles",
    "msg.fpvdrones.no_lock": "No lock - keep the target in the box",
    "tooltip.fpvdrones.mavic_remote": "Shift+RMB a Mavic to bind. RMB to fly, no goggles needed.",
    "tooltip.fpvdrones.radio_beacon": "Hangs under a Mavic. Shaheds/Gerans home on wherever it lands.",
    "tooltip.fpvdrones.terminator": "RMB to board (1st drives, 2nd shoots). RMB with belts/ATGMs to load. Shift+RMB to pick up.",
    "tooltip.fpvdrones.air_defense_gun": "RMB to take the sight, LMB fires, [G] or Shift+RMB toggles auto mode against drones.",
    "tooltip.fpvdrones.kornet": "RMB to take the sight, LMB to launch. Keep the crosshair on target until impact.",
    "tooltip.fpvdrones.javelin": "Hold RMB, keep the target boxed until lock, release to launch.",
    "tooltip.fpvdrones.ammo_30mm": "For the air defense station and the BMPT.",
    "tooltip.fpvdrones.kornet_missile": "For the Kornet. Spares in your inventory reload automatically.",
    "tooltip.fpvdrones.ataka_missile": "For the BMPT \"Terminator\", holds 4.",
    "tooltip.fpvdrones.javelin_missile": "For the Javelin.",
    "death.attack.fpvdrones.shell": "%1$s was blown apart by a 30 mm shell",
    "death.attack.fpvdrones.shell.player": "%1$s was blown apart by a 30 mm shell while fleeing %2$s",
    "death.attack.fpvdrones.shell_by": "%1$s was blown apart by %2$s's 30 mm shell",
    "death.attack.fpvdrones.shell_by.item": "%1$s was blown apart by %2$s's 30 mm shell",
    "subtitles.fpvdrones.cannon_30": "30 mm cannon fires",
    "subtitles.fpvdrones.atgm_launch": "ATGM launch",
    "subtitles.fpvdrones.missile_motor": "Missile flies",
    "subtitles.fpvdrones.reload": "Reloading",
    "subtitles.fpvdrones.seeker_tone": "Seeker searching",
    "subtitles.fpvdrones.lock_tone": "Target locked",
    "subtitles.fpvdrones.tracks": "Tracks clank",
}


def lang():
    assert set(RU) == set(EN), set(RU) ^ set(EN)
    save_json(RU, ASSETS, "lang", "ru_ru.json")
    save_json(EN, ASSETS, "lang", "en_us.json")


# ---------------------------------------------------------------------------
# recipes / loot

RECIPE_ROWS = []


def recipes():
    out_dir = os.path.join(DATA, "recipes")
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    M = "minecraft:"

    def key_of(key):
        k = {}
        for ch, it in key.items():
            if it.startswith("#"):
                k[ch] = {"tag": it[1:]}
            else:
                k[ch] = {"item": it if ":" in it else f"{MODID}:{it}"}
        return k

    def crafting(name, pattern, key, count=1):
        save_json({"type": "minecraft:crafting_shaped", "pattern": pattern, "key": key_of(key),
                   "result": {"item": f"{MODID}:{name}", "count": count}}, DATA, "recipes", name + ".json")
        RECIPE_ROWS.append((name, pattern, key, count, "верстак"))

    def bench(name, pattern, key, count=1, solder=False, result=None):
        result = result or name
        obj = {"type": f"{MODID}:assembly", "pattern": pattern, "key": key_of(key),
               "result": {"item": f"{MODID}:{result}", "count": count}}
        if solder:
            obj["soldering"] = True
        save_json(obj, DATA, "recipes", name + ".json")
        RECIPE_ROWS.append((result, pattern, key, count, "сборочный + паяльник" if solder else "сборочный"))

    # the only two things made on a vanilla crafting table
    crafting("soldering_iron", ["  I", " R ", "S  "], {"I": M + "iron_ingot", "R": M + "redstone", "S": M + "stick"})
    crafting("assembly_bench", ["CCC", "ITI", "P P"],
             {"C": M + "green_carpet", "I": M + "iron_ingot", "T": M + "crafting_table", "P": "#minecraft:planks"})

    # parts
    bench("frame", ["I I", "IDI", "I I"], {"I": M + "iron_ingot", "D": M + "black_dye"})
    bench("motor", [" I ", "RGR", " I "], {"I": M + "iron_ingot", "R": M + "redstone", "G": M + "gold_ingot"}, 2)
    bench("propeller", ["N N", " I ", "N N"], {"N": M + "iron_nugget", "I": M + "iron_ingot"}, 4)
    bench("esc", ["RQR", "NCN"], {"R": M + "redstone", "Q": M + "quartz", "N": M + "gold_nugget", "C": M + "comparator"},
          solder=True)
    bench("flight_controller", ["RQR", "GCG", "RQR"],
          {"R": M + "redstone", "Q": M + "quartz", "G": M + "gold_nugget", "C": M + "repeater"}, solder=True)
    bench("receiver", ["A", "Q", "N"], {"A": M + "iron_bars", "Q": M + "quartz", "N": M + "gold_nugget"}, 2, solder=True)
    bench("flight_stack", ["F", "E", "R"], {"F": "flight_controller", "E": "esc", "R": "receiver"}, solder=True)
    bench("battery", ["NRN", "RGR", "NRN"], {"N": M + "iron_nugget", "R": M + "redstone", "G": M + "glowstone_dust"},
          solder=True)
    bench("fpv_camera", ["NGN", "NQN"], {"N": M + "iron_nugget", "G": M + "glass_pane", "Q": M + "quartz"}, solder=True)
    bench("antenna", ["G", "B", "B"], {"G": M + "gold_nugget", "B": M + "iron_bars"}, 2)
    bench("vtx", [" A ", "QGQ"], {"A": "antenna", "Q": M + "quartz", "G": M + "gold_ingot"}, solder=True)
    bench("gimbal_camera", ["NMN", "NCN"], {"N": M + "iron_nugget", "M": "motor", "C": "fpv_camera"}, solder=True)
    bench("gnss_module", ["A", "C", "Q"], {"A": "antenna", "C": M + "compass", "Q": M + "quartz"}, solder=True)
    bench("display", ["GGG", "RQR"], {"G": M + "glass_pane", "R": M + "redstone", "Q": M + "quartz"}, solder=True)
    bench("airframe", ["WWW", "III", " W "], {"W": M + "gray_wool", "I": M + "iron_ingot"})
    bench("piston_engine", ["IPI", "IBI", "IFI"],
          {"I": M + "iron_ingot", "P": M + "piston", "B": M + "iron_block", "F": M + "flint_and_steel"})
    bench("small_jet_engine", ["IRI", "CFC", "IBI"],
          {"I": M + "iron_ingot", "R": M + "blaze_rod", "C": M + "fire_charge", "F": "flight_controller",
           "B": M + "iron_block"}, solder=True)
    bench("warhead", ["ITI", "TTT", "ITI"], {"I": M + "iron_ingot", "T": M + "tnt"})
    bench("booster", ["IGI", "IGI", "IFI"], {"I": M + "iron_ingot", "G": M + "gunpowder", "F": M + "fire_charge"})

    # drones and gear
    bench("fpv_drone", ["PBP", "MFM", "VSC"],
          {"P": "propeller", "B": "battery", "M": "motor", "F": "frame", "V": "vtx", "S": "flight_stack",
           "C": "fpv_camera"}, solder=True)
    bench("mavic3", ["PBP", "MGM", "NSA"],
          {"P": "propeller", "B": "battery", "M": "motor", "G": "gimbal_camera", "N": "gnss_module",
           "S": "flight_stack", "A": "antenna"}, solder=True)
    bench("fpv_goggles", ["LAL", "DRD"], {"L": M + "leather", "A": "antenna", "D": "display", "R": "receiver"}, solder=True)
    bench("drone_remote", ["A A", "BDB", "IRI"],
          {"A": "antenna", "B": M + "stone_button", "D": "display", "I": M + "iron_ingot", "R": "receiver"}, solder=True)
    bench("tablet", ["IDI", "IRI", "IBI"], {"I": M + "iron_ingot", "D": "display", "R": "receiver", "B": "battery"},
          solder=True)
    bench("launch_remote", [" A ", "IDI", "IBI"],
          {"A": "antenna", "I": M + "iron_ingot", "D": "display", "B": M + "stone_button"}, solder=True)
    bench("launcher", ["  R", "IRI", "BBB"], {"R": M + "rail", "I": M + "iron_ingot", "B": M + "iron_block"})
    bench("launch_pad", ["S S", " D ", "S S"], {"S": M + "stick", "D": M + "black_dye"})
    bench("launch_truck", ["RRR", "BEB", "WFW"],
          {"R": M + "rail", "B": M + "iron_block", "E": "piston_engine", "W": M + "black_wool", "F": "flight_controller"})
    bench("shahed136", ["FN ", "EAH", " B "],
          {"F": "flight_controller", "N": "gnss_module", "E": "piston_engine", "A": "airframe", "H": "warhead",
           "B": "booster"}, solder=True)
    bench("shahed238", ["FN ", "JAH", " B "],
          {"F": "flight_controller", "N": "gnss_module", "J": "small_jet_engine", "A": "airframe", "H": "warhead",
           "B": "booster"}, solder=True)
    bench("shahed238_upgrade", ["SJ"], {"S": "shahed136", "J": "small_jet_engine"}, result="shahed238")
    bench("geran2", ["FNC", "EAH", " BH"],
          {"F": "flight_controller", "N": "gnss_module", "C": M + "black_dye", "E": "piston_engine", "A": "airframe",
           "H": "warhead", "B": "booster"}, solder=True)
    bench("geran5", ["FN ", "JAH", " BH"],
          {"F": "flight_controller", "N": "gnss_module", "J": "small_jet_engine", "A": "airframe", "H": "warhead",
           "B": "booster"}, solder=True)

    # ammo
    bench("pg7vs", [" N ", "ITI", " I "], {"N": M + "iron_nugget", "I": M + "iron_ingot", "T": M + "tnt"})
    bench("og7", [" N ", "GTG", " I "], {"N": M + "iron_nugget", "G": M + "gunpowder", "T": M + "tnt", "I": M + "iron_ingot"})
    bench("explosive_canister", ["GSG", "TBT", "GTG"],
          {"G": M + "gunpowder", "S": M + "string", "T": M + "tnt", "B": M + "bucket"})
    bench("rgd5", [" N ", "IGI", " I "], {"N": M + "iron_nugget", "I": M + "iron_ingot", "G": M + "gunpowder"}, 2)
    bench("vog17m", [" N ", "IGI", " S "],
          {"N": M + "iron_nugget", "I": M + "iron_ingot", "G": M + "gunpowder", "S": M + "stick"}, 4)

    # beacons, batteries, radios
    bench("radio_beacon", ["A", "T", "N"], {"A": "antenna", "T": M + "redstone_torch", "N": M + "iron_nugget"}, 2,
          solder=True)
    bench("battery_recharge", ["RDR"], {"R": M + "redstone", "D": "depleted_battery"}, result="battery")
    bench("mavic_remote", ["A A", "SDS", "IRI"],
          {"A": "antenna", "S": M + "light_gray_dye", "D": "display", "I": M + "iron_ingot", "R": "receiver"}, solder=True)

    # armour and anti-tank
    bench("autocannon", ["III", "PBF", "III"],
          {"I": M + "iron_ingot", "P": M + "piston", "B": M + "iron_block", "F": M + "flint_and_steel"})
    bench("ammo_30mm", ["IGI", "IGI", "IGI"], {"I": M + "iron_ingot", "G": M + "gunpowder"}, 2)
    bench("air_defense_gun", ["G A", "ICI", "BFB"],
          {"G": "gimbal_camera", "A": "antenna", "I": M + "iron_ingot", "C": "autocannon", "B": M + "iron_block",
           "F": "flight_controller"}, solder=True)
    bench("terminator", ["CIC", "GEF", "BBB"],
          {"C": "autocannon", "I": M + "iron_block", "G": "gimbal_camera", "E": "piston_engine",
           "F": "flight_controller", "B": M + "iron_block"}, solder=True)
    bench("kornet", [" G ", "IFI", "S S"],
          {"G": "gimbal_camera", "I": M + "iron_ingot", "F": "flight_controller", "S": M + "stick"}, solder=True)
    bench("javelin", ["GD", "IF", " I"],
          {"G": "gimbal_camera", "D": "display", "I": M + "iron_ingot", "F": "flight_controller"}, solder=True)
    bench("kornet_missile", ["W", "R", "B"], {"W": "warhead", "R": "receiver", "B": "booster"}, solder=True)
    bench("ataka_missile", ["W", "A", "B"], {"W": "warhead", "A": "antenna", "B": "booster"}, solder=True)
    bench("javelin_missile", ["W", "C", "B"], {"W": "warhead", "C": "fpv_camera", "B": "booster"}, solder=True)

    for block in ("launcher", "launch_pad", "assembly_bench"):
        save_json({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"{MODID}:{block}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}]}]},
            DATA, "loot_tables", "blocks", block + ".json")


def recipe_doc():
    """RECIPES.md next to the README, there's no JEI"""
    vanilla_ru = {"iron_ingot": "железо", "iron_nugget": "жел. самородок", "gold_ingot": "золото",
                  "gold_nugget": "зол. самородок", "redstone": "редстоун", "quartz": "кварц", "comparator": "компаратор",
                  "repeater": "повторитель", "glowstone_dust": "светокамень", "glass_pane": "стекл. панель",
                  "iron_bars": "решётка", "compass": "компас", "gray_wool": "серая шерсть", "piston": "поршень",
                  "iron_block": "жел. блок", "flint_and_steel": "огниво", "blaze_rod": "огн. стержень",
                  "fire_charge": "огн. заряд", "tnt": "ТНТ", "gunpowder": "порох", "leather": "кожа",
                  "stone_button": "кнопка", "rail": "рельсы", "stick": "палка", "black_dye": "чёрный краситель",
                  "black_wool": "чёрная шерсть", "string": "нить", "bucket": "ведро", "green_carpet": "зелёный ковёр",
                  "crafting_table": "верстак", "minecraft:planks": "доски"}

    def name(it):
        it = it.lstrip("#")
        if it.startswith("minecraft:"):
            short = it.split(":", 1)[1]
            return vanilla_ru.get(short, vanilla_ru.get(it, short))
        return RU.get(f"item.{MODID}.{it}", RU.get(f"block.{MODID}.{it}", it))

    lines = ["# Рецепты", "", "Всё, кроме паяльника и самого сборочного верстака, собирается на сборочном верстаке.",
             "«+ паяльник» — паяльник должен лежать в своём слоте, каждая сборка тратит 1 прочности.", ""]
    for res, pattern, key, count, where in RECIPE_ROWS:
        title = name(res) + (f" ×{count}" if count > 1 else "")
        lines.append(f"### {title}")
        lines.append(f"*{where}*")
        lines.append("```")
        lines.extend(p.replace(" ", ".") for p in pattern)
        lines.append("```")
        lines.append(", ".join(f"`{ch}` {name(it)}" for ch, it in key.items()))
        lines.append("")
    root = os.path.abspath(os.path.join(RES, "..", "..", ".."))
    with open(os.path.join(root, "RECIPES.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ---------------------------------------------------------------------------
# sounds

SOUNDS = ["fpv_motor", "mavic_motor", "shahed_engine", "jet_engine", "shahed_launch", "truck_engine",
          "release", "beep", "static", "cannon_30", "atgm_launch", "missile_motor", "reload", "seeker_tone",
          "lock_tone", "tracks"]


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

    # 30 mm: sharp crack, low thump, short tail
    n7 = int(sr * 0.6)
    t7 = np.arange(n7) / sr
    crack = rng.normal(size=n7) * np.exp(-t7 * 60)
    thump = np.sin(2 * np.pi * 70 * t7 * (1 - t7)) * np.exp(-t7 * 14)
    tail = np.convolve(rng.normal(size=n7), np.ones(30) / 30, mode="same") * np.exp(-t7 * 7)
    write("cannon_30", np.tanh((1.2 * crack + 1.4 * thump + 2.0 * tail) * 1.6))

    # ATGM: pop of the ejection charge, then the motor roaring away
    n8 = int(sr * 1.8)
    t8 = np.arange(n8) / sr
    pop = rng.normal(size=n8) * np.exp(-t8 * 40)
    motor = np.convolve(rng.normal(size=n8), np.ones(8) / 8, mode="same") * np.clip((t8 - 0.12) * 8, 0, 1) * np.exp(-t8 * 1.6)
    write("atgm_launch", pop + 1.6 * motor + 0.5 * np.sin(2 * np.pi * 90 * t8) * np.exp(-t8 * 8))

    sig = loop_noise(n2, 9000) + 0.6 * loop_noise(n2, 2500)
    for f in (1900, 1960):
        sig += 0.12 * np.sin(2 * np.pi * f * t2)
    write("missile_motor", sig)

    n9 = int(sr * 0.7)
    t9 = np.arange(n9) / sr
    clank = np.zeros(n9)
    for at, f in ((0.0, 1300), (0.28, 900), (0.45, 1700)):
        k = (t9 >= at)
        tt = np.where(k, t9 - at, 0)
        clank += k * (rng.normal(size=n9) * 0.6 + np.sin(2 * np.pi * f * tt)) * np.exp(-tt * 45)
    write("reload", clank)

    n10 = int(sr * 0.25)
    t10 = np.arange(n10) / sr
    write("seeker_tone", np.sign(np.sin(2 * np.pi * 420 * t10)) * 0.5 * np.minimum(1, (0.25 - t10) * 30))
    n11 = int(sr * 0.2)
    t11 = np.arange(n11) / sr
    write("lock_tone", np.sin(2 * np.pi * 1320 * t11) * np.minimum(1, (0.2 - t11) * 40) * np.minimum(1, t11 * 200))

    # diesel + track links slapping, whole periods so it loops
    f0 = 30.0
    phase = (t2 * f0) % 1.0
    diesel = np.exp(-phase * 5.0)
    links = ((t2 * 12) % 1.0 < 0.04).astype(float) * rng.normal(size=n2)
    write("tracks", np.tanh((diesel - diesel.mean() + 0.5 * loop_noise(n2, 700) + 0.6 * links) * 1.6))


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
    build_shahed238()
    build_payloads()
    build_truck()
    block_textures()
    build_launcher()
    build_launch_pad()
    build_icons()
    build_part_icons()
    build_jet_engine()
    build_armor()
    build_weapon_icons()
    build_remote_icons()
    build_beacon_and_battery()
    build_bench()
    gui_texture()
    armor_texture()
    item_models()
    lang()
    recipes()
    recipe_doc()
    sounds_json()
    gen_sounds()
    print("done:", RES)


if __name__ == "__main__":
    main()
