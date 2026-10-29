"""ZAK-30 "Tsitadel" built from primitives (there is no model of it to borrow): pedestal, turret with the radar
and the electro-optical head, 2A42-type gun. Three meshes in the frame AirDefenseGunRenderer draws them in:
model pixels, the part's pivot at (8, 0, 8), 1 px = 2/16 block."""
import os

import numpy as np
from PIL import Image

import meshkit as mk

TEX = 64
# atlas regions (x, y, w, h) in texels
CAMO = (0, 0, 32, 32)
STEEL = (32, 0, 16, 16)
DARK = (48, 0, 16, 16)
RUBBER = (32, 16, 16, 16)
GLASS = (48, 16, 16, 16)
PANEL = (0, 32, 32, 32)
LIGHT = (32, 32, 16, 16)


def atlas(path):
    rng = np.random.default_rng(30)
    a = np.zeros((TEX, TEX, 4), np.uint8)
    a[..., 3] = 255
    # Russian digital camo: green base, olive and dark blobs grown from 2x2 cells
    base, mid, dark = np.array([86, 98, 62]), np.array([62, 72, 44]), np.array([38, 42, 32])
    cells = np.zeros((16, 16), int)
    for colour, n in ((1, 22), (2, 14)):
        for _ in range(n):
            y, x = rng.integers(0, 16, 2)
            for _ in range(rng.integers(2, 6)):
                cells[y % 16, x % 16] = colour
                y += rng.integers(-1, 2)
                x += rng.integers(-1, 2)
    pal = np.array([base, mid, dark])
    camo = pal[cells].repeat(2, 0).repeat(2, 1)
    camo = camo + rng.integers(-5, 6, camo.shape[:2])[..., None]
    a[0:32, 0:32, :3] = camo.clip(0, 255)

    def fill(r, c, noise=4):
        x, y, w, h = r
        a[y:y + h, x:x + w, :3] = (np.array(c) + rng.integers(-noise, noise + 1, (h, w))[..., None]).clip(0, 255)
    fill(STEEL, (70, 72, 70))
    fill(DARK, (44, 46, 42))
    fill(RUBBER, (26, 26, 27), 2)
    fill(GLASS, (34, 52, 70), 0)
    a[GLASS[1] + 2:GLASS[1] + 6, GLASS[0] + 2:GLASS[0] + 6, :3] = (90, 120, 150)
    fill(LIGHT, (180, 40, 30), 0)
    # radar face: a grid of radiating elements on the panel colour
    x, y, w, h = PANEL
    fill(PANEL, (74, 84, 58), 3)
    for i in range(2, 32, 4):
        a[y + 1:y + h - 1, x + i, :3] = (58, 66, 46)
        a[y + i, x + 1:x + w - 1, :3] = (58, 66, 46)
    Image.fromarray(a, "RGBA").save(path)


class Mesh:
    def __init__(self):
        self.P, self.UV, self.N = [], [], []

    def tri(self, p, uv, n):
        self.P.append(np.array(p, float))
        self.UV.append(np.array(uv, float))
        self.N.append(np.array(n, float))

    def quad(self, q, region, n=None, scale=2.0):
        """four corners counter-clockwise from outside; texture laid flat at {scale} texels per px"""
        q = [np.array(p, float) for p in q]
        nn = np.cross(q[1] - q[0], q[2] - q[0])
        nn = nn / (np.linalg.norm(nn) + 1e-9) if n is None else np.array(n, float)
        u = q[1] - q[0]
        u /= np.linalg.norm(u) + 1e-9
        v = np.cross(nn, u)
        x0, y0, w, h = region
        uv = []
        for p in q:
            d = p - q[0]
            s, t = (d @ u) * scale, (d @ v) * scale
            uv.append(((x0 + 0.5 + min(abs(s), w - 1)) / TEX, (y0 + 0.5 + min(abs(t), h - 1)) / TEX))
        self.tri((q[0], q[1], q[2]), (uv[0], uv[1], uv[2]), (nn, nn, nn))
        self.tri((q[0], q[2], q[3]), (uv[0], uv[2], uv[3]), (nn, nn, nn))

    def hexa(self, c, region):
        """eight corners: bottom 0-3, top 4-7, each counter-clockwise seen from above"""
        c = [np.array(p, float) for p in c]
        self.quad((c[3], c[2], c[1], c[0]), region)
        self.quad((c[4], c[5], c[6], c[7]), region)
        for i in range(4):
            j = (i + 1) % 4
            self.quad((c[i], c[j], c[j + 4], c[i + 4]), region)

    def box(self, a, b, region):
        (x0, y0, z0), (x1, y1, z1) = a, b
        self.hexa([(x0, y0, z0), (x0, y0, z1), (x1, y0, z1), (x1, y0, z0),
                   (x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)], region)

    def cyl(self, axis, centre, r0, r1, a0, a1, region, n=14, caps=True):
        """frustum along axis 'x'/'y'/'z' from a0 (radius r0) to a1 (radius r1)"""
        ax = "xyz".index(axis)
        u, v = [i for i in range(3) if i != ax]
        x0, y0, w, h = region

        def pt(r, a, t):
            p = np.zeros(3)
            p[ax] = a
            p[u] = centre[0] + r * np.cos(t)
            p[v] = centre[1] + r * np.sin(t)
            return p

        def nrm(t):
            k = np.zeros(3)
            k[u], k[v] = np.cos(t), np.sin(t)
            k[ax] = (r0 - r1) / max(abs(a1 - a0), 1e-6) * np.sign(a1 - a0)
            return k / np.linalg.norm(k)
        sgn = 1 if a1 > a0 else -1
        for i in range(n):
            t0, t1 = 2 * np.pi * i / n, 2 * np.pi * (i + 1) / n
            s0, s1 = (x0 + 0.5 + (w - 1) * i / n) / TEX, (x0 + 0.5 + (w - 1) * (i + 1) / n) / TEX
            ta, tb = (y0 + 0.5) / TEX, (y0 + h - 0.5) / TEX
            A, B, C, D = pt(r0, a0, t0), pt(r0, a0, t1), pt(r1, a1, t1), pt(r1, a1, t0)
            tri1, tri2 = (A, B, C), (A, C, D)
            uvs = {id(A): (s0, ta), id(B): (s1, ta), id(C): (s1, tb), id(D): (s0, tb)}
            for tr in (tri1, tri2):
                self._oriented(tr, [uvs[id(p)] for p in tr], [nrm(t0 if p is A or p is D else t1) for p in tr])
            if caps:
                for a, r, d in ((a0, r0, -sgn), (a1, r1, sgn)):
                    if r <= 0:
                        continue
                    c = pt(0, a, 0)
                    k = np.zeros(3)
                    k[ax] = d
                    p0, p1 = pt(r, a, t0), pt(r, a, t1)
                    cu = ((x0 + w / 2) / TEX, (y0 + h / 2) / TEX)
                    self._oriented((c, p0, p1), [cu, cu, cu], [k, k, k])

    def _oriented(self, tr, uv, ns):
        """wind the triangle so it faces along its normals (the renderer doesn't cull, lighting does care)"""
        p = [np.array(x, float) for x in tr]
        if np.dot(np.cross(p[1] - p[0], p[2] - p[0]), np.sum(ns, 0)) < 0:
            p, uv, ns = p[::-1], uv[::-1], ns[::-1]
        self.tri(p, uv, ns)

    def write(self, path, tex_ref):
        P = np.array(self.P) / 16.0
        mk.write_mesh_sections(path, [(tex_ref, P, np.array(self.UV), np.array(self.N))])
        return P * 16


def base():
    m = Mesh()
    # cross of outriggers with screw jacks, a round plinth, the column, the slewing ring at 9.2 px (PIVOT)
    for sx, sz in ((1, 0), (0, 1)):
        a = (8 - 7.5 * sx - 1.1 * sz, 0.0, 8 - 7.5 * sz - 1.1 * sx)
        b = (8 + 7.5 * sx + 1.1 * sz, 0.9, 8 + 7.5 * sz + 1.1 * sx)
        m.box(a, b, DARK)
    for dx, dz in ((-7.0, 0), (7.0, 0), (0, -7.0), (0, 7.0)):
        m.cyl("y", (8 + dx, 8 + dz), 1.2, 1.2, 0.0, 0.35, RUBBER, 10)
        m.cyl("y", (8 + dx, 8 + dz), 0.45, 0.45, 0.35, 2.2, STEEL, 8)
    m.cyl("y", (8, 8), 5.0, 4.6, 0.9, 1.6, CAMO, 18)
    m.cyl("y", (8, 8), 3.3, 3.3, 1.6, 8.2, CAMO, 16)
    m.cyl("y", (8, 8), 4.4, 4.4, 8.2, 9.2, DARK, 20)
    # power and data box on the column, with its cable
    m.box((10.6, 2.4, 6.4), (12.6, 5.6, 9.6), CAMO)
    m.cyl("x", (3.0, 8.0), 0.35, 0.35, 12.6, 14.0, RUBBER, 6)
    return m


def turret():
    m = Mesh()
    # floor plate, then two armoured cheeks with sloped faces either side of the gun slot (x 6..10)
    m.box((2.4, 0.0, 2.6), (13.6, 1.0, 13.0), CAMO)
    for x0, x1 in ((2.6, 6.0), (10.0, 13.4)):
        m.hexa([(x0, 1.0, 2.8), (x0, 1.0, 12.8), (x1, 1.0, 12.8), (x1, 1.0, 2.8),
                (x0 + 0.4, 5.0, 3.6), (x0 + 0.4, 5.0, 10.2), (x1 - 0.4, 5.0, 10.2), (x1 - 0.4, 5.0, 3.6)], CAMO)
    # rear bustle joining them over the gun's breech
    m.hexa([(6.0, 1.0, 2.8), (6.0, 1.0, 5.2), (10.0, 1.0, 5.2), (10.0, 1.0, 2.8),
            (6.0, 5.0, 3.6), (6.0, 5.0, 5.2), (10.0, 5.0, 5.2), (10.0, 5.0, 3.6)], CAMO)
    # ammunition box on the right cheek, feed chute into the slot
    m.box((0.8, 1.2, 4.2), (2.6, 4.4, 9.6), DARK)
    # electro-optical head on the left (sightOffset): TV/IR windows and the laser rangefinder
    m.cyl("y", (12.0, 7.6), 1.0, 1.0, 5.0, 5.8, DARK, 12)
    m.box((10.6, 5.8, 6.4), (13.4, 8.2, 9.4), DARK)
    for x0, x1, y0, y1 in ((10.9, 12.1, 6.2, 7.6), (12.4, 13.1, 6.2, 6.9), (12.4, 13.1, 7.1, 7.8)):
        m.quad(((x1, y0, 9.42), (x0, y0, 9.42), (x0, y1, 9.42), (x1, y1, 9.42)), GLASS, (0, 0, 1))
    # surveillance radar: a flat array on a mast at the back, leaning back a little
    m.cyl("y", (8.0, 3.6), 0.5, 0.5, 5.0, 7.2, STEEL, 8)
    m.hexa([(4.8, 7.0, 3.2), (4.8, 7.0, 4.0), (11.2, 7.0, 4.0), (11.2, 7.0, 3.2),
            (4.8, 11.4, 2.4), (4.8, 11.4, 3.2), (11.2, 11.4, 3.2), (11.2, 11.4, 2.4)], CAMO)
    m.quad(((11.0, 7.3, 4.02), (5.0, 7.3, 4.02), (5.0, 11.2, 3.22), (11.0, 11.2, 3.22)), PANEL, scale=4.0)
    # whip antenna and a beacon
    m.cyl("y", (3.4, 3.4), 0.12, 0.08, 5.0, 12.0, RUBBER, 5, caps=False)
    m.cyl("y", (12.8, 3.6), 0.3, 0.3, 5.0, 5.6, LIGHT, 8)
    return m


def gun():
    m = Mesh()
    # cradle and receiver round the trunnion (origin), mantlet, recoil sleeve, barrel, muzzle brake: to 28.8 px
    m.box((6.5, -1.2, 3.6), (9.5, 1.2, 12.4), DARK)
    m.box((7.0, 1.2, 5.0), (9.0, 1.8, 11.6), DARK)
    m.hexa([(5.6, -1.9, 11.6), (5.6, -1.9, 12.4), (10.4, -1.9, 12.4), (10.4, -1.9, 11.6),
            (5.9, 1.9, 11.6), (5.9, 1.9, 12.2), (10.1, 1.9, 12.2), (10.1, 1.9, 11.6)], CAMO)
    m.cyl("z", (8.0, 0.0), 0.95, 0.85, 12.4, 15.6, DARK, 14)
    m.cyl("z", (8.0, 0.0), 0.42, 0.42, 15.6, 27.4, STEEL, 12)
    m.cyl("z", (8.0, 0.0), 0.62, 0.62, 27.4, 28.8, DARK, 12)
    # feed chute from the ammunition box
    m.box((5.2, -0.6, 6.0), (6.5, 0.4, 9.0), DARK)
    return m


def build(assets, modid):
    tex_dir = os.path.join(assets, "textures", "entity", "mesh", "citadel")
    os.makedirs(tex_dir, exist_ok=True)
    atlas(os.path.join(tex_dir, "atlas.png"))
    ref = f"{modid}:entity/mesh/citadel/atlas"
    out = {}
    for name, fn in (("adgun_base", base), ("adgun_turret", turret), ("adgun_gun", gun)):
        P = fn().write(os.path.join(assets, "models", "entity", name + ".mesh"), ref)
        print(f"  {name}: {len(P)} tris, px {np.round(P.reshape(-1, 3).min(0), 2)} .. {np.round(P.reshape(-1, 3).max(0), 2)}")
        out[name] = P
    return list(out)
