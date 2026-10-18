"""
Tiny software renderer for checking baked models without starting the game.

    sc = Scene(res_root)
    sc.model("militarytech:entity/t72_hull", M)      # M: 4x4, model units -> world blocks
    sc.render("out.png", yaw=35, pitch=20, scale=60)
"""
import json
import math
import os

import numpy as np
from PIL import Image


def T(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def S(k):
    m = np.eye(4)
    m[0, 0] = m[1, 1] = m[2, 2] = k
    return m


def R(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    m = np.eye(4)
    if axis == "x":
        m[1, 1], m[1, 2], m[2, 1], m[2, 2] = c, -s, s, c
    elif axis == "y":
        m[0, 0], m[0, 2], m[2, 0], m[2, 2] = c, s, -s, c
    else:
        m[0, 0], m[0, 1], m[1, 0], m[1, 1] = c, -s, s, c
    return m


def part_matrix(scale, center_y=False):
    """what the Java side does: unit (8,0,8) on the origin, scale/16 block per unit"""
    return S(scale / 16.0) @ T(-8, -(8 if center_y else 0), -8)


def face_corners(fr, to, d):
    x1, y1, z1 = fr
    x2, y2, z2 = to

    def p(u, v):
        if d == "north":
            return (x2 - u * (x2 - x1), y2 - v * (y2 - y1), z1)
        if d == "south":
            return (x1 + u * (x2 - x1), y2 - v * (y2 - y1), z2)
        if d == "west":
            return (x1, y2 - v * (y2 - y1), z1 + u * (z2 - z1))
        if d == "east":
            return (x2, y2 - v * (y2 - y1), z2 - u * (z2 - z1))
        if d == "up":
            return (x1 + u * (x2 - x1), y2, z1 + v * (z2 - z1))
        return (x1 + u * (x2 - x1), y1, z2 - v * (z2 - z1))
    return [p(0, 0), p(1, 0), p(1, 1), p(0, 1)]


class Scene:
    def __init__(self, res_root):
        self.res = res_root
        self.tris = []
        self._tex = {}

    def tex(self, ref):
        if ref not in self._tex:
            ns, p = ref.split(":")
            img = Image.open(os.path.join(self.res, "assets", ns, "textures", p + ".png")).convert("RGBA")
            self._tex[ref] = np.asarray(img, dtype=np.float64)
        return self._tex[ref]

    def part(self, ref, M=None):
        """a baked part placed like the mod does it: the JSON's own scale and origin, then M"""
        ns, p = ref.split(":")
        m = json.load(open(os.path.join(self.res, "assets", ns, "models", p + ".json")))
        k = m.get("scale", 16) / 16.0
        o = m.get("origin", [8, 8, 8])
        base = S(k) @ T(-o[0], -o[1], -o[2])
        self.model(ref, (M if M is not None else np.eye(4)) @ base)

    def model(self, ref, M):
        ns, p = ref.split(":")
        m = json.load(open(os.path.join(self.res, "assets", ns, "models", p + ".json")))
        texs = m["textures"]
        for e in m["elements"]:
            fr, to = e["from"], e["to"]
            ER = np.eye(4)
            if "rotation" in e:
                o = e["rotation"]["origin"]
                ER = T(*o) @ R(e["rotation"]["axis"], e["rotation"]["angle"]) @ T(-o[0], -o[1], -o[2])
            for d, f in e["faces"].items():
                ref_t = texs[f["texture"][1:]]
                while ref_t.startswith("#"):
                    ref_t = texs[ref_t[1:]]
                img = self.tex(ref_t)
                u1, v1, u2, v2 = f["uv"]
                H, W = img.shape[:2]
                uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
                uvs = [(u / 16 * W, v / 16 * H) for u, v in uvs]
                pts = [(M @ ER @ np.array(list(c) + [1]))[:3] for c in face_corners(fr, to, d)]
                self.tris.append((pts[0], pts[1], pts[2], uvs[0], uvs[1], uvs[2], img))
                self.tris.append((pts[0], pts[2], pts[3], uvs[0], uvs[2], uvs[3], img))

    def fit(self, yaw, pitch, size, margin=0.06):
        """scale and centre that make the whole scene fill the image"""
        V = R("x", pitch) @ R("y", yaw)
        pts = np.array([p for t in self.tris for p in t[:3]])
        q = (V[:3, :3] @ pts.T).T
        lo, hi = q.min(0), q.max(0)
        Wd, Hd = size
        scale = min(Wd * (1 - 2 * margin) / max(1e-6, hi[0] - lo[0]), Hd * (1 - 2 * margin) / max(1e-6, hi[1] - lo[1]))
        mid = (lo + hi) / 2
        center = np.linalg.inv(V[:3, :3]) @ mid
        return scale, tuple(center)

    def render(self, out, yaw=35, pitch=20, scale=60, size=(900, 600), center=(0, 1, 0), ground=True, transparent=False):
        Wd, Hd = size
        if scale is None:
            scale, center = self.fit(yaw, pitch, size)
        V = R("x", pitch) @ R("y", yaw) @ T(-center[0], -center[1], -center[2])
        col = np.zeros((Hd, Wd, 3))
        alpha = np.zeros((Hd, Wd))
        sky = np.linspace(0, 1, Hd)[:, None]
        col[:] = (np.array([150, 182, 214]) * (1 - sky) + np.array([212, 222, 228]) * sky)[:, None, :]
        zbuf = np.full((Hd, Wd), -np.inf)
        light = np.array([0.45, 0.8, -0.4]) if not transparent else np.array([-0.35, 0.85, -0.55])
        light /= np.linalg.norm(light)
        tris = list(self.tris)
        if ground:
            g = 40
            gp = [np.array(p, float) for p in ((-g, 0, -g), (g, 0, -g), (g, 0, g), (-g, 0, g))]
            gimg = np.zeros((2, 2, 4))
            gimg[...] = (112, 132, 82, 255)
            tris.append((gp[0], gp[1], gp[2], (0, 0), (1, 0), (1, 1), gimg))
            tris.append((gp[0], gp[2], gp[3], (0, 0), (1, 1), (0, 1), gimg))
        for p0, p1, p2, t0, t1, t2, img in tris:
            n = np.cross(p1 - p0, p2 - p0)
            ln = np.linalg.norm(n)
            if ln < 1e-12:
                continue
            n = n / ln
            shade = 0.55 + 0.45 * abs(float(np.dot(n, light)))
            q = [(V @ np.array(list(p) + [1]))[:3] for p in (p0, p1, p2)]
            xs = [Wd / 2 + v[0] * scale for v in q]
            ys = [Hd / 2 - v[1] * scale for v in q]
            zs = [v[2] for v in q]
            x0, x1 = int(max(0, math.floor(min(xs)))), int(min(Wd - 1, math.ceil(max(xs))))
            y0, y1 = int(max(0, math.floor(min(ys)))), int(min(Hd - 1, math.ceil(max(ys))))
            if x1 < x0 or y1 < y0:
                continue
            X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
            if abs(den) < 1e-9:
                continue
            a = ((ys[1] - ys[2]) * (X - xs[2]) + (xs[2] - xs[1]) * (Y - ys[2])) / den
            b = ((ys[2] - ys[0]) * (X - xs[2]) + (xs[0] - xs[2]) * (Y - ys[2])) / den
            c = 1 - a - b
            inside = (a >= -1e-4) & (b >= -1e-4) & (c >= -1e-4)
            if not inside.any():
                continue
            z = a * zs[0] + b * zs[1] + c * zs[2]
            sub = zbuf[y0:y1 + 1, x0:x1 + 1]
            vis = inside & (z > sub)
            if not vis.any():
                continue
            tu = a * t0[0] + b * t1[0] + c * t2[0]
            tv = a * t0[1] + b * t1[1] + c * t2[1]
            H, W = img.shape[:2]
            iu = np.clip(tu.astype(int), 0, W - 1)
            iv = np.clip(tv.astype(int), 0, H - 1)
            texel = img[iv, iu]
            vis &= texel[..., 3] > 10
            sub[vis] = z[vis]
            region = col[y0:y1 + 1, x0:x1 + 1]
            region[vis] = texel[..., :3][vis] * shade
            alpha[y0:y1 + 1, x0:x1 + 1][vis] = 255
        if transparent:
            rgba = np.concatenate([np.clip(col, 0, 255), alpha[..., None]], -1).astype(np.uint8)
            Image.fromarray(rgba, "RGBA").save(out)
        else:
            Image.fromarray(np.clip(col, 0, 255).astype(np.uint8)).save(out)
