"""Electronic warfare: the jammer on a Ural-4320 (Brout's model plus a mast with antennas built here), the
jamming tower and the relay mast for Shaheds (both built from primitives). Meshes are in the frame
RadioMachineRenderer draws them in: model pixels, the entity's origin at (8, 0, 8), 16 px to the block,
+z forward. Primitives below are written in blocks and scaled."""
import os

import numpy as np

import gen_citadel as gc
import meshkit as mk

STEEL, DARK, CAMO, PANEL, RUBBER, GLASS, LIGHT = gc.STEEL, gc.DARK, gc.CAMO, gc.PANEL, gc.RUBBER, gc.GLASS, gc.LIGHT


class Kit(gc.Mesh):
    """gc.Mesh in blocks around the entity origin"""

    @staticmethod
    def px(p):
        return (8 + p[0] * 16, p[1] * 16, 8 + p[2] * 16)

    def box_b(self, a, b, region):
        self.box(self.px(a), self.px(b), region)

    def cyl_b(self, axis, centre, r0, r1, a0, a1, region, n=12, caps=True):
        ax = "xyz".index(axis)
        u, v = [i for i in range(3) if i != ax]
        c = (8 + centre[0] * 16 if u != 1 else centre[0] * 16, 8 + centre[1] * 16 if v != 1 else centre[1] * 16)
        off = 8 if ax != 1 else 0
        self.cyl(axis, c, r0 * 16, r1 * 16, off + a0 * 16, off + a1 * 16, region, n, caps)

    def beam(self, p0, p1, w, region):
        """a square bar of width w (blocks) from p0 to p1"""
        p0, p1 = np.array(self.px(p0), float), np.array(self.px(p1), float)
        d = p1 - p0
        d /= np.linalg.norm(d)
        a = np.cross(d, [0, 1, 0] if abs(d[1]) < 0.9 else [1, 0, 0])
        a /= np.linalg.norm(a)
        b = np.cross(d, a)
        h = w * 8
        x0, y0, rw, rh = region
        uv = ((x0 + rw / 2) / gc.TEX, (y0 + rh / 2) / gc.TEX)
        offs = [a * h + b * h, -a * h + b * h, -a * h - b * h, a * h - b * h]
        for i in range(4):
            j = (i + 1) % 4
            n = (offs[i] + offs[j])
            n /= np.linalg.norm(n)
            q = [p0 + offs[i], p0 + offs[j], p1 + offs[j], p1 + offs[i]]
            self._oriented((q[0], q[1], q[2]), [uv] * 3, [n] * 3)
            self._oriented((q[0], q[2], q[3]), [uv] * 3, [n] * 3)
        for p, s in ((p0, -1), (p1, 1)):
            q = [p + o for o in offs]
            self._oriented((q[0], q[1], q[2]), [uv] * 3, [d * s] * 3)
            self._oriented((q[0], q[2], q[3]), [uv] * 3, [d * s] * 3)

    def panel(self, centre, yaw, w, h, t, y0):
        """a flat sector antenna facing outwards at yaw (deg), its back at centre + 0 (blocks)"""
        r = np.radians(yaw)
        f = np.array([np.sin(r), 0, np.cos(r)])
        s = np.array([np.cos(r), 0, -np.sin(r)])
        c = np.array([centre[0], y0, centre[1]])
        corners = []
        for dy in (0, h):
            for ds, dt in ((-1, 0), (1, 0), (1, 1), (-1, 1)):
                p = c + s * ds * w / 2 + f * dt * t + np.array([0, dy, 0])
                corners.append(self.px(p))
        self.hexa(corners, DARK)
        # the radiating face, a hair in front of the box
        q = [c + s * ds * w * 0.46 + f * (t + 0.004) + np.array([0, dy, 0])
             for ds, dy in ((-1, h * 0.05), (1, h * 0.05), (1, h * 0.95), (-1, h * 0.95))]
        self.quad([self.px(p) for p in q], PANEL, n=f, scale=4.0)

    def section(self, ref):
        return ref, np.array(self.P) / 16.0, np.array(self.UV), np.array(self.N)


def ew_kit():
    """what goes on the Ural: a mast at the front of the bed with four sector panels and a dome, whips"""
    k = Kit()
    k.box_b((-0.35, 3.05, 0.0), (0.35, 3.35, 0.6), CAMO)
    k.cyl_b("y", (0.0, 0.3), 0.13, 0.11, 3.3, 5.0, STEEL, 10)
    k.cyl_b("y", (0.0, 0.3), 0.09, 0.09, 5.0, 5.75, STEEL, 10)
    for yaw in (0, 90, 180, 270):
        r = np.radians(yaw)
        k.panel((np.sin(r) * 0.12, 0.3 + np.cos(r) * 0.12), yaw, 0.5, 0.75, 0.07, 5.0)
    k.cyl_b("y", (0.0, 0.3), 0.2, 0.06, 5.75, 6.05, DARK, 12)
    for x, z, top in ((0.62, 1.95, 4.3), (-0.62, 1.95, 4.3), (1.2, -2.9, 4.6), (-1.2, -2.9, 4.6)):
        y0 = 2.85 if z > 1 else 3.15
        k.cyl_b("y", (x, z), 0.035, 0.02, y0, top, RUBBER, 5, caps=False)
    # generator and cable drum on the rear of the bed, under the canvas edge
    k.box_b((-1.1, 1.25, -3.25), (-0.45, 1.75, -2.75), DARK)
    k.cyl_b("x", (1.5, -3.0), 0.22, 0.22, 0.4, 0.95, RUBBER, 12)
    return k


def tower():
    k = Kit()
    h, base, top = 9.0, 0.95, 0.35
    feet = [(base, base), (-base, base), (-base, -base), (base, -base)]
    heads = [(top, top), (-top, top), (-top, -top), (top, -top)]

    def at(i, y):
        t = y / h
        return (feet[i][0] + (heads[i][0] - feet[i][0]) * t, y, feet[i][1] + (heads[i][1] - feet[i][1]) * t)

    for i in range(4):
        k.beam(at(i, 0), at(i, h), 0.11, STEEL)
        k.box_b((feet[i][0] - 0.16, 0, feet[i][1] - 0.16), (feet[i][0] + 0.16, 0.12, feet[i][1] + 0.16), DARK)
    levels = [0.3, 1.8, 3.3, 4.8, 6.3, 7.8, h]
    for y in levels:
        for i in range(4):
            k.beam(at(i, y), at((i + 1) % 4, y), 0.06, STEEL)
    for y0, y1 in zip(levels, levels[1:]):
        for i in range(4):
            j = (i + 1) % 4
            k.beam(at(i, y0), at(j, y1), 0.045, STEEL)
            k.beam(at(j, y0), at(i, y1), 0.045, STEEL)
    k.box_b((-0.6, h, -0.6), (0.6, h + 0.1, 0.6), DARK)
    for yaw in (0, 90, 180, 270):
        r = np.radians(yaw)
        k.panel((np.sin(r) * 0.42, np.cos(r) * 0.42), yaw, 0.5, 1.1, 0.08, h + 0.1)
    k.cyl_b("y", (0.0, 0.0), 0.1, 0.1, h + 0.1, h + 1.3, STEEL, 8)
    k.cyl_b("y", (0.0, 0.0), 0.28, 0.08, h + 1.3, h + 1.6, DARK, 12)
    for x in (0.5, -0.5):
        k.cyl_b("y", (x, 0.5), 0.03, 0.02, h + 0.1, h + 1.9, RUBBER, 5, caps=False)
    k.cyl_b("y", (0.5, -0.5), 0.07, 0.07, h + 0.1, h + 0.3, LIGHT, 8)
    # equipment cabinet and generator between the legs
    k.box_b((-0.45, 0, -0.3), (0.45, 1.2, 0.3), CAMO)
    k.box_b((-0.4, 0.75, 0.3), (0.4, 1.05, 0.32), DARK)
    k.box_b((0.1, 0, -0.85), (0.75, 0.55, -0.4), DARK)
    return k


def relay():
    k = Kit()
    mast = 4.6
    k.cyl_b("y", (0.0, 0.0), 0.07, 0.05, 0.0, mast, STEEL, 8)
    for a in (0, 120, 240):
        r = np.radians(a)
        k.beam((np.sin(r) * 0.95, 0.0, np.cos(r) * 0.95), (0, 2.1, 0), 0.06, STEEL)
        k.box_b((np.sin(r) * 0.95 - 0.1, 0, np.cos(r) * 0.95 - 0.1), (np.sin(r) * 0.95 + 0.1, 0.06, np.cos(r) * 0.95 + 0.1), DARK)
    for a in (0, 120, 240):
        r = np.radians(a)
        k.panel((np.sin(r) * 0.07, np.cos(r) * 0.07), a, 0.24, 0.55, 0.05, mast - 0.6)
    k.cyl_b("y", (0.0, 0.0), 0.025, 0.015, mast, mast + 0.9, RUBBER, 5, caps=False)
    # a small dish for the link back to the launch site, looking forward
    k.cyl_b("z", (0.0, 3.7), 0.26, 0.12, 0.12, 0.26, DARK, 14)
    k.cyl_b("z", (0.0, 3.7), 0.04, 0.04, 0.0, 0.14, STEEL, 6)
    # battery box and a solar panel
    k.box_b((-0.25, 0, 0.2), (0.25, 0.4, 0.55), CAMO)
    sp = [(-0.45, 0.45, 0.35), (0.45, 0.45, 0.35), (0.45, 0.85, -0.1), (-0.45, 0.85, -0.1)]
    k.quad([k.px(p) for p in sp][::-1], GLASS, scale=8.0)
    k.beam((0, 0.4, 0.35), (0, 0.8, -0.05), 0.04, STEEL)
    k.cyl_b("y", (0.0, 0.0), 0.06, 0.06, mast + 0.02, mast + 0.12, LIGHT, 8)
    return k


def build(assets, modid, gm):
    tex_dir = os.path.join(assets, "textures", "entity", "mesh", "ew")
    os.makedirs(tex_dir, exist_ok=True)
    gc.atlas(os.path.join(tex_dir, "atlas.png"))
    ref = f"{modid}:entity/mesh/ew/atlas"
    out = []

    # Ural-4320 7.37 m long, front to +z; the jammer kit goes on as its own section. The wheels come off
    # the hull: one of them is ew_truck_wheel, EwTruckEntity puts it on the six hubs and spins it
    sc = gm.load({"src": "ural4320"})
    lo, hi = sc.bounds()
    k = 7.37 / (hi[2] - lo[2]) * 16
    M = mk.trans(8 - (lo[0] + hi[0]) / 2 * k, -lo[1] * k, 8 - (lo[2] + hi[2]) / 2 * k) @ mk.scale(k)
    tyres = [i for m, i in gm.comp_info(sc)
             if abs(i["center"][0]) > 0.9 and i["max"][1] < 1.4 and min(i["max"][1:] - i["min"][1:]) > 1.0]
    r = np.mean([(i["max"][1] - i["min"][1]) / 2 for i in tyres])
    hubs = [i["center"] for i in tyres]

    def on_hub(i, hub):
        return (np.sign(i["center"][0]) == np.sign(hub[0]) and abs(i["center"][0] - hub[0]) < 0.45
                and np.hypot(i["center"][1] - hub[1], i["center"][2] - hub[2]) < r * 0.9
                and max(i["max"][1:] - i["min"][1:]) < r * 2.1)

    wheels = gm.select(sc, lambda i: any(on_hub(i, h) for h in hubs))
    gm.export("ew_truck", sc.subset(~wheels), {"tris": 8000}, M)
    front_left = max(hubs, key=lambda c: (c[2], c[0]))
    W = mk.trans(8, 0, 8) @ mk.scale(k) @ mk.trans(-front_left[0], -front_left[1], -front_left[2])
    gm.export("ew_truck_wheel", sc.subset(gm.select(sc, lambda i: on_hub(i, front_left))),
              {"tris": 700, "max_tex": 512}, W)
    spots = sorted((tuple(((M @ np.append(c, 1))[:3] - [8, 0, 8]) / 16) for c in hubs), key=lambda s: (-s[2], s[0]))
    print("  EwTruckEntity.WHEELS = {" + ", ".join("{%.2f, %.2f}" % (s[0], s[2]) for s in spots)
          + "}; WHEEL_RADIUS = %.3f" % (r * k / 16))
    path = os.path.join(assets, "models", "entity", "ew_truck.mesh")
    secs = mk.read_mesh_sections(path)
    secs.append(ew_kit().section(ref))
    mk.write_mesh_sections(path, secs)
    out += ["ew_truck", "ew_truck_wheel"]

    for name, fn in (("ew_station", tower), ("relay", relay)):
        fn().write(os.path.join(assets, "models", "entity", name + ".mesh"), ref)
        out.append(name)
    for name in out:
        P = np.concatenate([s[1].reshape(-1, 3) for s in mk.read_mesh_sections(
            os.path.join(assets, "models", "entity", name + ".mesh"))])
        print(f"  {name}: blocks {np.round(P.min(0) - [0.5, 0, 0.5], 2)} .. {np.round(P.max(0) - [0.5, 0, 0.5], 2)}")
    return out
