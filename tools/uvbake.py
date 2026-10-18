"""
Box models with a proper UV unwrap.

Every visible face of every box gets its own rectangle in the vehicle's texture, sized to the face at a fixed
texel density, so a 7-block hull side really gets 7 blocks' worth of pixels. Faces are painted by material
painters that know where each pixel sits in the vehicle (part-local block coordinates), which keeps camouflage,
dust and grime continuous across faces. Decals (hatches, bolts, numbers, insignia) go on top per face.

Coordinates are blocks relative to the part pivot; Part.model() converts to Minecraft model units at a scale
where 1 unit = scale/16 block and model (8, 0, 8) sits on the pivot.
"""
import json
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFont

DIRS = ("north", "south", "east", "west", "up", "down")
NORMALS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0),
           "up": (0, 1, 0), "down": (0, -1, 0)}

_FONT = None
for _p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
           "C:/Windows/Fonts/arialbd.ttf"):
    if os.path.exists(_p):
        _FONT = _p
        break


def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], dtype=np.float64)


# ---------------------------------------------------------------------------
# noise

class Noise3:
    """value noise on a 3D lattice, smooth, deterministic per seed"""

    def __init__(self, seed, size=64):
        r = np.random.default_rng(seed)
        self.size = size
        self.g = r.random((size, size, size))

    def __call__(self, p, freq):
        q = p * freq
        i = np.floor(q).astype(np.int64)
        f = q - i
        f = f * f * (3 - 2 * f)
        s = self.size
        x0, y0, z0 = i[..., 0] % s, i[..., 1] % s, i[..., 2] % s
        x1, y1, z1 = (x0 + 1) % s, (y0 + 1) % s, (z0 + 1) % s
        g = self.g
        fx, fy, fz = f[..., 0], f[..., 1], f[..., 2]

        def lerp(a, b, t):
            return a + (b - a) * t

        c00 = lerp(g[x0, y0, z0], g[x1, y0, z0], fx)
        c10 = lerp(g[x0, y1, z0], g[x1, y1, z0], fx)
        c01 = lerp(g[x0, y0, z1], g[x1, y0, z1], fx)
        c11 = lerp(g[x0, y1, z1], g[x1, y1, z1], fx)
        return lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz)

    def fbm(self, p, freq, octaves=4):
        out = np.zeros(p.shape[:-1])
        amp, tot = 1.0, 0.0
        for o in range(octaves):
            out += amp * self(p + o * 17.31, freq * (2 ** o))
            tot += amp
            amp *= 0.5
        return out / tot * 2 - 1


# ---------------------------------------------------------------------------
# face context handed to painters

class Face:
    def __init__(self, box, d, w, h, scheme, rng, noise):
        self.box, self.dir, self.w, self.h = box, d, w, h
        self.scheme, self.rng, self.noise = scheme, rng, noise
        fr, to = box.fr, box.to
        self.size = {"north": (to[0] - fr[0], to[1] - fr[1]), "south": (to[0] - fr[0], to[1] - fr[1]),
                     "east": (to[2] - fr[2], to[1] - fr[1]), "west": (to[2] - fr[2], to[1] - fr[1]),
                     "up": (to[0] - fr[0], to[2] - fr[2]), "down": (to[0] - fr[0], to[2] - fr[2])}[d]
        # u, v in 0..1 across the face, the way Minecraft maps a face's default UVs
        uu = (np.arange(w) + 0.5) / w
        vv = (np.arange(h) + 0.5) / h
        U, V = np.meshgrid(uu, vv)
        self.u, self.v = U, V
        x1, y1, z1 = fr
        x2, y2, z2 = to
        X = np.zeros_like(U)
        Y = np.zeros_like(U)
        Z = np.zeros_like(U)
        if d == "north":
            X, Y, Z = x2 - U * (x2 - x1), y2 - V * (y2 - y1), np.full_like(U, z1)
        elif d == "south":
            X, Y, Z = x1 + U * (x2 - x1), y2 - V * (y2 - y1), np.full_like(U, z2)
        elif d == "west":
            X, Y, Z = np.full_like(U, x1), y2 - V * (y2 - y1), z1 + U * (z2 - z1)
        elif d == "east":
            X, Y, Z = np.full_like(U, x2), y2 - V * (y2 - y1), z2 - U * (z2 - z1)
        elif d == "up":
            X, Y, Z = x1 + U * (x2 - x1), np.full_like(U, y2), z1 + V * (z2 - z1)
        elif d == "down":
            X, Y, Z = x1 + U * (x2 - x1), np.full_like(U, y1), z2 - V * (z2 - z1)
        self.P = np.stack([X, Y, Z], -1) + np.array(box.world_offset)
        self.normal = NORMALS[d]
        # distance to the face border in blocks
        su, sv = self.size
        self.edge = np.minimum(np.minimum(U, 1 - U) * su, np.minimum(V, 1 - V) * sv)

    @property
    def vertical(self):
        return self.dir not in ("up", "down")

    def px(self, blocks):
        """blocks -> pixels on this face (texel density)"""
        return blocks * self.w / max(1e-6, self.size[0])


# ---------------------------------------------------------------------------
# material painters: f(face) -> float RGB array (h, w, 3)

def _grain(face, amt):
    return 1 + (face.rng.random((face.h, face.w)) - 0.5) * amt


def paint_base(face, col, camo=None, mottle=0.07, grain=0.025):
    n = face.noise
    rgb = np.ones((face.h, face.w, 3)) * col
    if camo:
        # big soft-edged blotches that run across faces
        f1 = n.fbm(face.P, 0.32, 4)
        f2 = n.fbm(face.P + 31.7, 0.42, 4)
        for colc, mask in ((camo[0], f1 > 0.14), (camo[1], (f2 > 0.22) & ~(f1 > 0.14))):
            rgb[mask] = colc
        if len(camo) > 2:
            f3 = n.fbm(face.P + 77.3, 0.55, 4)
            rgb[(f3 > 0.36)] = camo[2]
    lum = 1 + n.fbm(face.P, 1.1, 4) * mottle
    rgb *= (lum * _grain(face, grain))[..., None]
    return rgb


def weather(face, rgb, kind="paint"):
    """dust on top, mud at the bottom, streaks, worn edges, a bit of fake occlusion"""
    n = face.noise
    P = face.P
    sch = face.scheme
    dust = hexc(sch.get("dust", "#9a8f6e"))
    mud = hexc(sch.get("mud", "#4a3b28"))
    if face.dir == "up":
        k = np.clip(0.12 + 0.22 * n.fbm(P, 0.7, 4), 0, 0.42) * sch.get("dustiness", 1.0)
        rgb = rgb * (1 - k[..., None]) + dust * k[..., None]
    if face.vertical:
        # rain streaks running down from the top edge
        sx = n(P * np.array([1.4, 0.06, 1.4]) + 5.1, 2.2)
        streak = np.clip((sx - 0.6) * 2.0, 0, 1) * 0.12 * (1 - face.v * 0.5)
        rgb *= (1 - streak)[..., None]
        # darker towards the bottom of a face, lighter at the top lip
        rgb *= (1.04 - 0.12 * face.v ** 1.5)[..., None]
    elif face.dir == "down":
        rgb *= 0.7
    # mud and dust low down
    low = sch.get("mud_line", 0.9)
    m = np.clip((low - P[..., 1]) / max(0.2, low), 0, 1) ** 1.2
    m *= np.clip(0.55 + 0.6 * n.fbm(P + 9.9, 1.0, 4), 0, 1)
    m *= sch.get("muddiness", 1.0)
    rgb = rgb * (1 - 0.75 * m[..., None]) + mud * (0.75 * m[..., None])
    if kind == "paint":
        # chipped paint in clumps along edges, bare primer/steel showing through
        e = face.edge
        clump = n.fbm(P + 3.3, 4.0, 3)
        chip = (e < 0.045) & (clump > 0.18)
        chip |= (e < 0.02) & (clump > 0.0)
        rgb[chip] = rgb[chip] * 0.5 + np.array([92, 90, 82]) * 0.5
        hi = np.clip(1 - e / 0.03, 0, 1)
        rgb *= (1 + 0.1 * hi)[..., None]
    return rgb


def mat_paint(color_key="base", camo=True, worn=True):
    def paint(face):
        sch = face.scheme
        col = hexc(sch[color_key])
        cam = [hexc(c) for c in sch["camo"]] if camo and sch.get("camo") else None
        rgb = paint_base(face, col, cam)
        return weather(face, rgb, "paint" if worn else "none")
    return paint


def mat_flat(color, mottle=0.06, grain=0.06, worn=True, kind="paint"):
    c = hexc(color)

    def paint(face):
        rgb = paint_base(face, c, None, mottle, grain)
        return weather(face, rgb, kind) if worn else rgb
    return paint


def mat_era(color_key="base", brick=(0.5, 0.34), relikt=False):
    """reactive armour bricks laid on the face"""
    def paint(face):
        sch = face.scheme
        col = hexc(sch[color_key])
        cam = [hexc(c) for c in sch["camo"]] if sch.get("camo") else None
        rgb = paint_base(face, col, cam)
        su, sv = face.size
        bw, bh = brick if not relikt else (brick[0] * 1.5, brick[1] * 1.3)
        cu = face.u * su / bw
        cv = face.v * sv / bh
        # stagger every other row
        row = np.floor(cv)
        cu = cu + (row % 2) * 0.5
        fu = cu - np.floor(cu)
        fv = cv - row
        gap = 0.045
        groove = (fu < gap) | (fv < gap * 1.4)
        shade = np.where(fu < gap + 0.05, 1.18, 1.0) * np.where(fv > 1 - 0.08, 0.82, 1.0)
        shade = np.where(fv < gap * 1.4 + 0.06, shade * 1.1, shade)
        per = face.noise(np.stack([np.floor(cu), row, np.zeros_like(row)], -1) * 1.0 + 0.5, 1.0)
        rgb *= (shade * (0.93 + 0.12 * per))[..., None]
        rgb[groove] *= 0.45
        # two bolts per brick
        for bx in (0.25, 0.75):
            d = np.hypot((fu - bx) * bw, (fv - 0.5) * bh)
            b = d < 0.035
            rgb[b] = rgb[b] * 0.55
            rgb[(d < 0.02) & b] *= 1.6
        if relikt:
            diag = np.abs(fu - fv) < 0.03
            rgb[diag] *= 0.85
        return weather(face, rgb, "paint")
    return paint


def mat_tracks():
    def paint(face):
        su, sv = face.size
        n = face.noise
        # links run along the track (world z), pitch 0.17 block
        along = face.P[..., 2] if face.dir in ("up", "down", "east", "west") else face.P[..., 1]
        ph = (along / 0.17) % 1.0
        across = face.u if face.dir in ("up", "down") else face.v
        base = 40 + 14 * n.fbm(face.P, 3.0, 3)
        g = base + np.where(ph < 0.15, -16, np.where(ph < 0.55, 16 * np.sin(np.pi * (ph - 0.15) / 0.4), -4))
        if face.dir in ("up", "down"):
            horn = (np.abs(across - 0.5) < 0.07) & (ph > 0.2) & (ph < 0.5)
            g = np.where(horn, g + 34, g)
            pin = (np.abs(across - 0.5) > 0.42) & (ph < 0.12)
            g = np.where(pin, g + 26, g)
        rgb = np.stack([g, g * 0.97, g * 0.93], -1)
        # rust and dried mud
        rust = np.clip(n.fbm(face.P + 2.2, 4.0, 3) * 1.6, 0, 1)
        rgb = rgb * (1 - 0.35 * rust[..., None]) + np.array([92, 62, 38]) * (0.35 * rust[..., None])
        mud = np.clip(n.fbm(face.P + 8.8, 1.5, 3) + 0.2, 0, 1) * 0.5
        rgb = rgb * (1 - mud[..., None]) + np.array([74, 60, 42]) * mud[..., None]
        return rgb
    return paint


def mat_wheel(color_key="base", tyre=0.25):
    """road wheel seen side-on: rubber tyre ring, painted dish, hub with bolts"""
    def paint(face):
        sch = face.scheme
        col = hexc(sch[color_key])
        rgb = paint_base(face, col, None, 0.06, 0.05)
        du = (face.u - 0.5) * 2
        dv = (face.v - 0.5) * 2
        r = np.hypot(du, dv)
        ang = np.arctan2(dv, du)
        rub = (r > 1 - tyre) & (r <= 1.0)
        g = 34 + 8 * face.noise.fbm(face.P, 6.0, 2)
        rgb[rub] = np.stack([g, g, g * 1.04], -1)[rub]
        rim = np.abs(r - (1 - tyre)) < 0.035
        rgb[rim] *= 0.55
        dish = (r < 1 - tyre) & (r > 0.32)
        rgb[dish] *= (1 + 0.12 * np.cos(ang * 1 - 2.3))[dish][..., None]
        hub = r < 0.3
        rgb[hub] *= 0.85
        for k in range(8):
            a = k * math.pi / 4
            d = np.hypot(du - 0.2 * math.cos(a), dv - 0.2 * math.sin(a))
            rgb[d < 0.04] = np.array([150, 150, 138])
        rgb[r > 1.0] = rgb[r > 1.0] * 0.5
        return weather(face, rgb, "none")
    return paint


def mat_metal(color="#7b8086", brushed=True):
    c = hexc(color)

    def paint(face):
        n = face.noise
        rgb = np.ones((face.h, face.w, 3)) * c
        streak = n(face.P * np.array([0.2, 6.0, 0.2]), 1.0) - 0.5 if brushed else 0
        rgb *= (1 + 0.06 * n.fbm(face.P, 2.0, 3) + 0.08 * streak)[..., None] * _grain(face, 0.05)[..., None]
        sc = (face.rng.random((face.h, face.w)) < 0.003)
        rgb[sc] = np.minimum(255, rgb[sc] * 1.5)
        return weather(face, rgb, "none")
    return paint


def mat_rubber():
    def paint(face):
        g = 30 + 6 * face.noise.fbm(face.P, 4.0, 3)
        rgb = np.stack([g, g, g * 1.04], -1) * _grain(face, 0.08)[..., None]
        return weather(face, rgb, "none")
    return paint


def mat_skirt(color="#2b2e25", seam=0.62):
    """rubber side skirt: matte, panel seams and a bolted top strip"""
    c = hexc(color)

    def paint(face):
        rgb = paint_base(face, c, None, 0.1, 0.04)
        su, sv = face.size
        q = face.u * su / seam
        rgb[np.abs(q - np.round(q)) * seam < 0.012] *= 0.55
        top = face.v * sv < 0.07
        rgb[top] *= 1.25
        b = top & (np.abs((face.u * su / 0.2) % 1.0 - 0.5) < 0.12) & (np.abs(face.v * sv - 0.035) < 0.02)
        rgb[b] = np.array([88, 88, 80])
        return weather(face, rgb, "none")
    return paint


def mat_grille(color_key="base", slats=0.09):
    def paint(face):
        sch = face.scheme
        rgb = paint_base(face, hexc(sch[color_key]), None)
        along = face.v * face.size[1] / slats
        ph = along % 1.0
        rgb *= np.where(ph < 0.45, 0.35, np.where(ph < 0.6, 1.25, 1.0))[..., None]
        border = face.edge < 0.06
        rgb[border] = paint_base(face, hexc(sch[color_key]), None)[border]
        return weather(face, rgb, "paint")
    return paint


def mat_glass(color="#24343e", reflect=True):
    c = hexc(color)

    def paint(face):
        rgb = np.ones((face.h, face.w, 3)) * c
        rgb *= (1.25 - 0.45 * face.v)[..., None]
        if reflect:
            d = face.u - face.v * 0.8
            band = (np.abs(d - 0.35) < 0.07) | (np.abs(d - 0.52) < 0.025)
            rgb[band] = np.minimum(255, rgb[band] * 1.9 + 20)
        frame = face.edge < 0.03
        rgb[frame] = np.array([22, 22, 22])
        return rgb
    return paint


def mat_canopy(color="#5c7a96"):
    c = hexc(color)

    def paint(face):
        rgb = np.ones((face.h, face.w, 3)) * c
        rgb *= (1.35 - 0.6 * face.v)[..., None]
        d = face.u + face.v * 0.6
        band = np.abs(d - 0.55) < 0.06
        rgb[band] = np.minimum(255, rgb[band] * 1.6 + 30)
        frame = face.edge < 0.035
        rgb[frame] = np.array([40, 44, 46])
        return rgb
    return paint


def mat_light(color, glow=1.6):
    c = hexc(color)

    def paint(face):
        r = np.hypot(face.u - 0.5, face.v - 0.5) * 2
        rgb = np.ones((face.h, face.w, 3)) * c
        rgb *= (glow - (glow - 0.8) * np.clip(r, 0, 1))[..., None]
        rgb[face.edge < 0.02] = np.array([25, 25, 25])
        return rgb
    return paint


def mat_glow(color):
    c = hexc(color)

    def paint(face):
        r = np.hypot(face.u - 0.5, face.v - 0.5) * 2
        rgb = np.ones((face.h, face.w, 3)) * c
        rgb *= (1.3 - 0.4 * np.clip(r, 0, 1))[..., None] * _grain(face, 0.06)[..., None]
        return rgb
    return paint


def mat_nozzle():
    """heat-tinted titanium: straw near the end, blue further in"""
    def paint(face):
        n = face.noise
        t = face.v
        straw, blue, grey = hexc("#b49a62"), hexc("#55607a"), hexc("#6f7378")
        rgb = np.where((t < 0.33)[..., None], straw, np.where((t < 0.66)[..., None], blue, grey))
        rgb = rgb * (1 + 0.1 * n.fbm(face.P, 3, 3))[..., None]
        ph = (face.u * 18) % 1.0
        rgb *= np.where(ph < 0.12, 0.7, 1.0)[..., None]
        return rgb
    return paint


def mat_canvas(color="#56603d"):
    c = hexc(color)

    def paint(face):
        rgb = paint_base(face, c, None, 0.12, 0.06)
        w = ((np.arange(face.w)[None, :] + np.arange(face.h)[:, None]) % 2) * 0.05 + 0.975
        rgb *= w[..., None]
        fold = np.sin(face.P[..., 0] * 5 + face.P[..., 2] * 3) * 0.05
        rgb *= (1 + fold)[..., None]
        return weather(face, rgb, "none")
    return paint


def mat_skin(color_key="base", panel=1.3, camo=True):
    """aircraft skin: thin panel lines and rivet rows in the plane of each face, soft weathering, no mud;
    light underside if the scheme has one"""
    def paint(face):
        sch = face.scheme
        cam = [hexc(c) for c in sch["camo"]] if camo and sch.get("camo") else None
        if face.dir == "down" and sch.get("belly"):
            rgb = paint_base(face, hexc(sch["belly"]), None, 0.04, 0.02)
        else:
            rgb = paint_base(face, hexc(sch[color_key]), cam, 0.05, 0.02)
        P = face.P
        n = face.noise
        axes = {"north": (0, 1), "south": (0, 1), "east": (2, 1), "west": (2, 1), "up": (0, 2), "down": (0, 2)}[face.dir]
        lines = np.zeros(rgb.shape[:2], bool)
        rivet = np.zeros(rgb.shape[:2], bool)
        px = 1.0 / max(1.0, face.w / max(1e-6, face.size[0]))  # one pixel in blocks
        lw = max(0.018, px * 0.9)
        for k, ax in enumerate(axes):
            step = panel if ax != 1 else panel * 0.6
            q = (P[..., ax] + 0.37 * (ax + 1)) / step
            d = np.abs(q - np.round(q)) * step
            on = d < lw
            lines |= on
            other = axes[1 - k]
            r = P[..., other] / 0.11
            rivet |= (np.abs(d - lw * 2.6) < lw * 0.7) & (np.abs(r - np.round(r)) < 0.18)
        rgb[lines] *= 0.78
        rgb[rivet & ~lines] *= 1.1
        # soot behind the engines and general grime, very light
        grime = np.clip(n.fbm(P + 4, 0.8, 3), 0, 1) * 0.1
        rgb *= (1 - grime)[..., None]
        rgb[face.edge < 0.015] *= 0.88
        return rgb
    return paint


def mat_missile(color, band=None, band_at=0.75):
    c = hexc(color)

    def paint(face):
        rgb = paint_base(face, c, None, 0.04, 0.03)
        if band is not None:
            # band across the body, along the missile's z axis
            z = face.P[..., 2]
            z0, z1 = face.box.fr[2] + face.box.world_offset[2], face.box.to[2] + face.box.world_offset[2]
            t = (z - z0) / max(1e-6, z1 - z0)
            b = np.abs(t - band_at) < 0.04
            rgb[b] = hexc(band)
        rgb[face.edge < 0.01] *= 0.8
        return rgb
    return paint


def mat_slat(color_key="base", bar=0.06, pitch=0.22):
    """slat armour: vertical bars with see-through gaps (alpha)"""
    def paint(face):
        sch = face.scheme
        rgb = paint_base(face, hexc(sch[color_key]), None)
        rgb = weather(face, rgb, "paint")
        su, sv = face.size
        x = face.u * su / pitch
        frame = face.edge < 0.05
        solid = ((x % 1.0) < bar / pitch) | frame
        a = np.where(solid, 255.0, 0.0)
        rgb[frame] *= 0.85
        return np.concatenate([rgb, a[..., None]], -1)
    return paint


def mat_wood():
    def paint(face):
        n = face.noise
        along = face.P[..., 0]
        ring = n(face.P * np.array([0.3, 6, 6]), 1.0)
        g = 0.85 + 0.25 * ring + 0.1 * n.fbm(face.P * np.array([0.5, 4, 4]), 1.5, 3)
        rgb = hexc("#6b5236") * g[..., None]
        return weather(face, rgb, "none")
    return paint


def mat_drum(color_key="dark"):
    """fuel drum seen from the side: ribs around it"""
    def paint(face):
        sch = face.scheme
        rgb = paint_base(face, hexc(sch[color_key]), None)
        along = face.P[..., 0]
        k = np.abs(face.u - 0.25) < 0.025
        k |= np.abs(face.u - 0.75) < 0.025
        if face.dir in ("east", "west"):
            r = np.hypot(face.u - 0.5, face.v - 0.5) * 2
            rgb *= np.where(np.abs(r - 0.82) < 0.06, 0.6, 1.0)[..., None]
            rgb *= np.where(np.hypot(face.u - 0.3, face.v - 0.3) < 0.08, 0.5, 1.0)[..., None]
        else:
            rgb[k] *= 1.25
        return weather(face, rgb, "paint")
    return paint


def mat_tube_bundle(color_key="base", rows=4, cols=10):
    """side of a rocket pack: the round tubes show as ribs"""
    def paint(face):
        sch = face.scheme
        rgb = paint_base(face, hexc(sch[color_key]), None)
        n = rows if face.dir in ("east", "west") else cols
        q = face.v * n if face.dir in ("east", "west") else face.u * n
        ph = q - np.floor(q)
        rgb *= (0.72 + 0.4 * np.sin(np.pi * ph))[..., None]
        # straps around the pack
        along = face.P[..., 2]
        strap = (np.abs(((along + 0.3) % 1.1) - 0.55) < 0.06)
        rgb[strap] = rgb[strap] * 0.7 + 25
        return weather(face, rgb, "paint")
    return paint


MATERIALS = {}


def material(name, painter):
    MATERIALS[name] = painter


# ---------------------------------------------------------------------------
# decals, drawn after the material, in face uv space

def _draw_text(img, box_px, text, color, outline=None):
    x, y, w, h = box_px
    size = max(6, int(h))
    font = ImageFont.truetype(_FONT, size) if _FONT else ImageFont.load_default()
    d = ImageDraw.Draw(img)
    tw = d.textlength(text, font=font) if hasattr(d, "textlength") else size * len(text) * 0.6
    tx = x + (w - tw) / 2
    if outline:
        for ox in (-1, 0, 1):
            for oy in (-1, 0, 1):
                d.text((tx + ox, y + oy - size * 0.15), text, font=font, fill=outline)
    d.text((tx, y - size * 0.15), text, font=font, fill=color)


def apply_decals(rgb, face, decals):
    if not decals:
        return rgb
    alpha = rgb[..., 3:4].copy()
    img = Image.fromarray(np.clip(rgb[..., :3], 0, 255).astype(np.uint8), "RGB")
    d = ImageDraw.Draw(img)
    W, H = face.w, face.h
    su, sv = face.size
    for dec in decals:
        kind = dec[0]
        if kind == "text":
            _, text, cu, cv, hb, col = dec[:6]
            outline = dec[6] if len(dec) > 6 else None
            hp = hb / sv * H
            wp = hp * 0.7 * len(text)
            _draw_text(img, (cu * W - wp / 2, cv * H - hp / 2, wp, hp), text, col, outline)
        elif kind == "hatch":
            _, cu, cv, rb = dec
            cx, cy = cu * W, cv * H
            rx, ry = rb / su * W, rb / sv * H
            d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], outline=(30, 32, 26), width=max(1, int(rx * 0.08)))
            d.ellipse([cx - rx * 0.86, cy - ry * 0.86, cx + rx * 0.86, cy + ry * 0.86], outline=(110, 118, 92),
                      width=max(1, int(rx * 0.04)))
            d.rectangle([cx - rx * 0.5, cy - ry * 0.08, cx + rx * 0.5, cy + ry * 0.08], fill=(52, 56, 44))
            d.rectangle([cx + rx * 0.7, cy - ry * 0.35, cx + rx * 1.05, cy + ry * 0.35], fill=(44, 46, 38))
        elif kind == "bolts":
            _, spacing, inset = dec
            n = max(2, int(su / spacing))
            for i in range(n):
                for vv in (inset, 1 - inset):
                    x = (i + 0.5) / n * W
                    y = vv * H
                    r = max(1.0, W / su * 0.03)
                    d.ellipse([x - r, y - r, x + r, y + r], fill=(46, 48, 40))
                    d.point((x - r * 0.3, y - r * 0.3), fill=(150, 150, 130))
        elif kind == "star":
            _, cu, cv, rb = dec
            cx, cy = cu * W, cv * H
            r = rb / sv * H
            pts_o, pts_i, pts = [], [], []
            for k in range(10):
                a = -math.pi / 2 + k * math.pi / 5
                rr = r if k % 2 == 0 else r * 0.42
                pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
                rr2 = (r * 1.22) if k % 2 == 0 else r * 0.62
                pts_o.append((cx + math.cos(a) * rr2, cy + math.sin(a) * rr2))
            d.polygon(pts_o, fill=(235, 235, 235))
            d.polygon(pts, fill=(196, 28, 28))
        elif kind == "stripe":
            _, v0, v1, col = dec
            d.rectangle([0, v0 * H, W, v1 * H], fill=col)
        elif kind == "vstripe":
            _, u0, u1, col = dec
            d.rectangle([u0 * W, 0, u1 * W, H], fill=col)
        elif kind == "rect":
            _, u0, v0, u1, v1, col = dec
            d.rectangle([u0 * W, v0 * H, u1 * W, v1 * H], fill=col)
        elif kind == "ring":
            _, cu, cv, rb, col, wb = dec
            cx, cy = cu * W, cv * H
            r = rb / sv * H
            d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=max(1, int(wb / sv * H)))
        elif kind == "disc":
            _, cu, cv, rb, col = dec
            cx, cy = cu * W, cv * H
            r = rb / sv * H
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        elif kind == "tubes":
            # rocket pod / smoke launcher ends: grid of dark holes
            _, cols, rows, col = dec
            for i in range(cols):
                for j in range(rows):
                    cx = (i + 0.5) / cols * W
                    cy = (j + 0.5) / rows * H
                    r = min(W / cols, H / rows) * 0.38
                    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col, outline=(18, 18, 18))
        elif kind == "vent":
            _, u0, v0, u1, v1, n = dec
            for i in range(n):
                y = v0 * H + (i + 0.5) * (v1 - v0) * H / n
                d.line([u0 * W, y, u1 * W, y], fill=(20, 22, 18), width=max(1, int((v1 - v0) * H / n * 0.45)))
    return np.concatenate([np.asarray(img, dtype=np.float64), alpha], -1)


# ---------------------------------------------------------------------------
# geometry

class Box:
    def __init__(self, fr, to, faces, rot=None, world_offset=(0, 0, 0)):
        self.fr = tuple(min(a, b) for a, b in zip(fr, to))
        self.to = tuple(max(a, b) for a, b in zip(fr, to))
        if isinstance(faces, (str, tuple)):
            faces = {"all": faces}
        self.faces = faces
        self.rot = rot
        self.world_offset = world_offset
        self.uv = {}

    def face_spec(self, d):
        spec = self.faces.get(d, self.faces.get("all"))
        if spec is None or spec == "none":
            return None, None
        if isinstance(spec, tuple):
            return spec[0], spec[1]
        return spec, None


class Part:
    def __init__(self, name, world_offset=(0, 0, 0)):
        self.name = name
        self.boxes = []
        self.world_offset = world_offset

    def B(self, x1, y1, z1, x2, y2, z2, faces, rot=None):
        b = Box((x1, y1, z1), (x2, y2, z2), faces, rot, self.world_offset)
        self.boxes.append(b)
        return b

    def both(self, x1, y1, z1, x2, y2, z2, faces, rot=None):
        """box plus its mirror across x = 0 (east/west faces swapped)"""
        self.B(x1, y1, z1, x2, y2, z2, faces, rot)
        mf = faces
        if isinstance(faces, dict) and ("east" in faces or "west" in faces):
            mf = dict(faces)
            mf["east"], mf["west"] = faces.get("west", faces.get("all")), faces.get("east", faces.get("all"))
        mrot = None
        if rot:
            o, axis, ang = rot
            mrot = ((-o[0], o[1], o[2]), axis, -ang if axis in ("y", "z") else ang)
        self.B(-x2, y1, z1, -x1, y2, z2, mf, mrot)

    def cyl(self, cx, cy, cz, r, length, axis, faces, steps=2):
        """octagonal-ish cylinder from two boxes, the second turned 45 degrees"""
        k = r * 0.414 / 0.414
        if axis == "z":
            for rot in (None, ((cx, cy, cz + length / 2), "z", 45)):
                self.B(cx - r * 0.92, cy - r * 0.38, cz, cx + r * 0.92, cy + r * 0.38, cz + length, faces, rot)
                self.B(cx - r * 0.38, cy - r * 0.92, cz, cx + r * 0.38, cy + r * 0.92, cz + length, faces, rot)
        elif axis == "x":
            for rot in (None, ((cx + length / 2, cy, cz), "x", 45)):
                self.B(cx, cy - r * 0.92, cz - r * 0.38, cx + length, cy + r * 0.92, cz + r * 0.38, faces, rot)
                self.B(cx, cy - r * 0.38, cz - r * 0.92, cx + length, cy + r * 0.38, cz + r * 0.92, faces, rot)
        else:
            for rot in (None, ((cx, cy + length / 2, cz), "y", 45)):
                self.B(cx - r * 0.92, cy, cz - r * 0.38, cx + r * 0.92, cy + length, cz + r * 0.38, faces, rot)
                self.B(cx - r * 0.38, cy, cz - r * 0.92, cx + r * 0.38, cy + length, cz + r * 0.92, faces, rot)
        return k


# ---------------------------------------------------------------------------
# bake: pack faces, paint, write texture and models

class Bake:
    def __init__(self, name, modid, scheme, density=32, seed=None, max_size=2048):
        self.name = name
        self.modid = modid
        self.scheme = scheme
        self.density = density
        self.parts = []
        self.max_size = max_size
        self.seed = seed if seed is not None else sum(map(ord, name))
        self.noise = Noise3(self.seed)
        self.size = 0

    def part(self, name, world_offset=(0, 0, 0)):
        p = Part(name, world_offset)
        self.parts.append(p)
        return p

    def _faces(self):
        for p in self.parts:
            for b in p.boxes:
                for d in DIRS:
                    mat, decals = b.face_spec(d)
                    if mat is None:
                        continue
                    fr, to = b.fr, b.to
                    su, sv = {"north": (to[0] - fr[0], to[1] - fr[1]), "south": (to[0] - fr[0], to[1] - fr[1]),
                              "east": (to[2] - fr[2], to[1] - fr[1]), "west": (to[2] - fr[2], to[1] - fr[1]),
                              "up": (to[0] - fr[0], to[2] - fr[2]), "down": (to[0] - fr[0], to[2] - fr[2])}[d]
                    yield b, d, mat, decals, su, sv

    def bake(self, out_png):
        faces = list(self._faces())
        density = self.density
        pad = 2
        # shrink density until everything fits
        while True:
            rects = []
            for i, (b, d, mat, dec, su, sv) in enumerate(faces):
                w = max(2, int(math.ceil(su * density)))
                h = max(2, int(math.ceil(sv * density)))
                rects.append((i, w, h))
            size, placed = self._pack(rects, pad)
            if size <= self.max_size:
                break
            density *= 0.85
        self.size = size
        img = np.zeros((size, size, 4))
        img[..., 3] = 255
        rng = np.random.default_rng(self.seed)
        for i, (b, d, mat, dec, su, sv) in enumerate(faces):
            x, y, w, h = placed[i]
            face = Face(b, d, w, h, self.scheme, rng, self.noise)
            painter = MATERIALS[mat]
            rgb = painter(face)
            if rgb.shape[-1] == 3:
                rgb = np.concatenate([rgb, np.full(rgb.shape[:2] + (1,), 255.0)], -1)
            rgb = apply_decals(rgb, face, dec)
            img[y:y + h, x:x + w] = rgb
            # bleed the border into the padding so mipmaps don't pick up neighbours
            img[max(0, y - pad):y, x:x + w] = rgb[0:1]
            img[y + h:y + h + pad, x:x + w] = rgb[-1:]
            img[max(0, y - pad):y + h + pad, max(0, x - pad):x] = img[max(0, y - pad):y + h + pad, x:x + 1]
            img[max(0, y - pad):y + h + pad, x + w:x + w + pad] = img[max(0, y - pad):y + h + pad, x + w - 1:x + w]
            s = 16.0 / size
            b.uv[d] = [round(x * s, 4), round(y * s, 4), round((x + w) * s, 4), round((y + h) * s, 4)]
        os.makedirs(os.path.dirname(out_png), exist_ok=True)
        Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(out_png)
        return size

    @staticmethod
    def _pack(rects, pad):
        area = sum((w + pad * 2) * (h + pad * 2) for _, w, h in rects)
        size = 64
        while size * size < area * 1.15:
            size *= 2
        order = sorted(rects, key=lambda r: (-r[2], -r[1]))
        while True:
            placed = {}
            x, y, shelf = pad, pad, 0
            ok = True
            for i, w, h in order:
                if w + pad * 2 > size:
                    ok = False
                    break
                if x + w + pad > size:
                    x = pad
                    y += shelf + pad * 2
                    shelf = 0
                if y + h + pad > size:
                    ok = False
                    break
                placed[i] = (x, y, w, h)
                x += w + pad * 2
                shelf = max(shelf, h)
            if ok:
                return size, placed
            size *= 2

    def model_json(self, part, scale, center_y=False, offset=(0, 0, 0), texture=None, extra=None):
        """Minecraft model for one part. 1 unit = scale/16 block."""
        k = 16.0 / scale
        cy = 8 if center_y else 0
        tex = texture or f"{self.modid}:entity/{self.name}"

        def u(v, axis):
            return round((v + offset[axis]) * k + (cy if axis == 1 else 8), 4)

        elements = []
        parts = [part] + (extra or [])
        for p, off in ((pp, (0, 0, 0)) for pp in parts):
            for b in p.boxes:
                fr = [u(b.fr[i], i) for i in range(3)]
                to = [u(b.to[i], i) for i in range(3)]
                for v in fr + to:
                    assert -16 <= v <= 32, (self.name, p.name, fr, to)
                e = {"from": fr, "to": to, "faces": {}}
                for d in DIRS:
                    if d in b.uv:
                        e["faces"][d] = {"uv": b.uv[d], "texture": "#t"}
                if not e["faces"]:
                    continue
                if b.rot:
                    o, axis, ang = b.rot
                    assert ang in (-45, -22.5, 0, 22.5, 45)
                    e["rotation"] = {"origin": [u(o[i], i) for i in range(3)], "axis": axis, "angle": ang}
                elements.append(e)
        # "scale"/"origin" are read by the mod's own model loader (client/BoxModel.java)
        return {"scale": scale, "origin": [8, cy, 8], "ambientocclusion": False,
                "textures": {"t": tex, "particle": tex}, "elements": elements}


def save_json(obj, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
