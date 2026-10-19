"""
Real 3D models into Minecraft: glTF (.glb) in, a few triangle meshes and one baked texture per vehicle out.

    load_glb(path)            -> Scene: world-space triangles with uv, material and node name
    Scene.split(rules)        -> {part: Scene}, by node name or by where each connected piece sits
    decimate(scene, n)        -> (verts, faces) with about n triangles
    bake(parts, size)         -> new uv atlas for all parts + texture sampled from the original textures
    write_mesh(path, ...)     -> the .mesh file the mods read (gzip, big-endian floats)
    render(...)               -> a quick textured preview with a z-buffer

The bake does not need the original uvs to survive decimation: it samples the original surface into a dense
coloured point cloud and gives every texel of the new atlas the colour of the nearest original surface that
faces the same way. That copes with many materials, tiled textures and vertex colours alike.
"""
import gzip
import os
import io
import json
import math
import re
import struct

import numpy as np
from PIL import Image, ImageFilter
from scipy.spatial import cKDTree

# ---------------------------------------------------------------------------
# glTF loading

_CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
_NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}
MAX_TEX = 2048


def _quat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def _node_matrix(n):
    if "matrix" in n:
        return np.array(n["matrix"], dtype=np.float64).reshape(4, 4).T
    m = np.eye(4)
    s = np.array(n.get("scale", [1, 1, 1]), dtype=np.float64)
    r = _quat(n.get("rotation", [0, 0, 0, 1]))
    m[:3, :3] = r * s
    m[:3, 3] = n.get("translation", [0, 0, 0])
    return m


class Material:
    def __init__(self, name, color, image=None, alpha_mode="OPAQUE", cutoff=0.5):
        self.name = name
        self.color = np.array(color, dtype=np.float64)  # rgba 0..1, linear factor as stored (treated as sRGB)
        self.image = image  # float array h x w x 4, 0..1, or None
        self.alpha_mode = alpha_mode
        self.cutoff = cutoff


class Scene:
    """Flat list of triangles: P (n,3,3), UV (n,3,2), C (n,3,4) vertex colours, mat (n,), node (n,)"""

    def __init__(self, P, UV, C, mat, node, materials, node_names):
        self.P, self.UV, self.C, self.mat, self.node = P, UV, C, mat, node
        self.materials = materials
        self.node_names = node_names

    def __len__(self):
        return len(self.P)

    def subset(self, mask):
        return Scene(self.P[mask], self.UV[mask], self.C[mask], self.mat[mask], self.node[mask], self.materials, self.node_names)

    def transform(self, M):
        P = self.P @ M[:3, :3].T + M[:3, 3]
        if np.linalg.det(M[:3, :3]) < 0:
            P = P[:, ::-1]
            return Scene(P, self.UV[:, ::-1], self.C[:, ::-1], self.mat, self.node, self.materials, self.node_names)
        return Scene(P, self.UV, self.C, self.mat, self.node, self.materials, self.node_names)

    def bounds(self):
        p = self.P.reshape(-1, 3)
        return p.min(0), p.max(0)

    def components(self, eps=1e-4):
        """connected pieces: triangles sharing a (rounded) vertex position"""
        n = len(self.P)
        key = np.round(self.P.reshape(-1, 3) / eps).astype(np.int64)
        _, inv = np.unique(key, axis=0, return_inverse=True)
        inv = inv.reshape(-1)
        parent = np.arange(inv.max() + 1)

        def find(a):
            while parent[a] != a:
                parent[a] = parent[parent[a]]
                a = parent[a]
            return a
        tri = inv.reshape(n, 3)
        for a, b in ((0, 1), (1, 2)):
            for x, y in zip(tri[:, a], tri[:, b]):
                rx, ry = find(x), find(y)
                if rx != ry:
                    parent[rx] = ry
        roots = np.array([find(v) for v in tri[:, 0]])
        _, comp = np.unique(roots, return_inverse=True)
        return comp

    def split(self, rules, default="hull", by="component"):
        """rules: list of (part, test). test(info) -> bool, info has name, center, min, max (of the piece).
        Pieces are connected components (by='component') or whole glTF nodes (by='node')."""
        groups = self.components() if by == "component" else self.node
        out = {}
        assign = np.empty(len(self.P), dtype=object)
        for g in np.unique(groups):
            m = groups == g
            p = self.P[m].reshape(-1, 3)
            info = {"name": self.node_names[self.node[m][0]], "mat": self.materials[self.mat[m][0]].name,
                    "center": (p.min(0) + p.max(0)) / 2,
                    "min": p.min(0), "max": p.max(0), "tris": int(m.sum())}
            part = default
            for name, test in rules:
                if test(info):
                    part = name
                    break
            assign[m] = part
        for part in sorted(set(assign)):
            if part == "drop":
                continue
            out[part] = self.subset(assign == part)
        return out


def _accessor(g, binary, i):
    a = g["accessors"][i]
    dt = _CT[a["componentType"]]
    nc = _NC[a["type"]]
    count = a["count"]
    if "bufferView" not in a:
        return np.zeros((count, nc), dtype=np.float64)
    bv = g["bufferViews"][a["bufferView"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    item = np.dtype(dt).itemsize * nc
    stride = bv.get("byteStride", item)
    if stride == item:
        arr = np.frombuffer(binary, dtype=dt, count=count * nc, offset=off).reshape(count, nc)
    else:
        raw = np.frombuffer(binary, dtype=np.uint8, count=stride * (count - 1) + item, offset=off)
        idx = np.arange(count)[:, None] * stride + np.arange(item)[None, :]
        arr = raw[idx].copy().view(dt).reshape(count, nc)
    arr = arr.astype(np.float64)
    if a.get("normalized"):
        arr /= float(np.iinfo(dt).max)
    return arr


def _image(g, binary, tex_index, cache):
    if tex_index is None:
        return None
    if tex_index in cache:
        return cache[tex_index]
    tex = g["textures"][tex_index]
    src = tex.get("source")
    if src is None:
        for ext in tex.get("extensions", {}).values():
            src = ext.get("source", src)
    img = None
    if src is not None:
        im = g["images"][src]
        if "bufferView" in im:
            bv = g["bufferViews"][im["bufferView"]]
            data = binary[bv.get("byteOffset", 0): bv.get("byteOffset", 0) + bv["byteLength"]]
            pil = Image.open(io.BytesIO(data)).convert("RGBA")
            if max(pil.size) > MAX_TEX:
                k = MAX_TEX / max(pil.size)
                pil = pil.resize((max(1, int(pil.width * k)), max(1, int(pil.height * k))), Image.LANCZOS)
            img = np.asarray(pil).astype(np.float32) / 255.0
    cache[tex_index] = img
    return img


def load_glb(path):
    b = open(path, "rb").read()
    assert b[:4] == b"glTF", path
    off = 12
    g, binary = None, b""
    while off < len(b):
        ln, kind = struct.unpack("<II", b[off:off + 8])
        chunk = b[off + 8: off + 8 + ln]
        if kind == 0x4E4F534A:
            g = json.loads(chunk)
        elif kind == 0x004E4942:
            binary = chunk
        off += 8 + ln
    cache = {}
    mats = []
    for m in g.get("materials", []):
        pbr = m.get("pbrMetallicRoughness", {})
        color = pbr.get("baseColorFactor", [1, 1, 1, 1])
        tex = pbr.get("baseColorTexture", {}).get("index")
        sg = m.get("extensions", {}).get("KHR_materials_pbrSpecularGlossiness")
        if sg:
            color = sg.get("diffuseFactor", color)
            tex = sg.get("diffuseTexture", {}).get("index", tex)
        mats.append(Material(m.get("name", "?"), color, _image(g, binary, tex, cache), m.get("alphaMode", "OPAQUE"),
                             m.get("alphaCutoff", 0.5)))
    mats.append(Material("default", [0.6, 0.6, 0.6, 1]))
    default_mat = len(mats) - 1

    P, UV, C, MAT, NODE = [], [], [], [], []
    names = []

    def visit(ni, parent):
        n = g["nodes"][ni]
        M = parent @ _node_matrix(n)
        if "mesh" in n:
            nid = len(names)
            names.append(n.get("name", "node%d" % ni))
            for pr in g["meshes"][n["mesh"]]["primitives"]:
                mode = pr.get("mode", 4)
                if mode not in (4, 5, 6):
                    continue
                at = pr["attributes"]
                pos = _accessor(g, binary, at["POSITION"])
                nv = len(pos)
                uv = _accessor(g, binary, at["TEXCOORD_0"]) if "TEXCOORD_0" in at else np.zeros((nv, 2))
                col = _accessor(g, binary, at["COLOR_0"]) if "COLOR_0" in at else np.ones((nv, 4))
                if col.shape[1] == 3:
                    col = np.concatenate([col, np.ones((nv, 1))], 1)
                idx = _accessor(g, binary, pr["indices"])[:, 0].astype(np.int64) if "indices" in pr else np.arange(nv)
                if mode == 5:
                    t = [(idx[i], idx[i + 1], idx[i + 2]) if i % 2 == 0 else (idx[i + 1], idx[i], idx[i + 2])
                         for i in range(len(idx) - 2)]
                    idx = np.array(t).reshape(-1)
                elif mode == 6:
                    idx = np.array([(idx[0], idx[i], idx[i + 1]) for i in range(1, len(idx) - 1)]).reshape(-1)
                tri = idx.reshape(-1, 3)
                pw = pos @ M[:3, :3].T + M[:3, 3]
                if np.linalg.det(M[:3, :3]) < 0:
                    tri = tri[:, ::-1]
                P.append(pw[tri])
                UV.append(uv[tri])
                C.append(col[tri])
                MAT.append(np.full(len(tri), pr.get("material", default_mat)))
                NODE.append(np.full(len(tri), nid))
        for c in n.get("children", []):
            visit(c, M)

    scene = g["scenes"][g.get("scene", 0)]
    for r in scene["nodes"]:
        visit(r, np.eye(4))
    sc = Scene(np.concatenate(P), np.concatenate(UV), np.concatenate(C), np.concatenate(MAT), np.concatenate(NODE),
               mats, names)
    # drop degenerate triangles
    e1 = sc.P[:, 1] - sc.P[:, 0]
    e2 = sc.P[:, 2] - sc.P[:, 0]
    area = np.linalg.norm(np.cross(e1, e2), axis=1)
    return sc.subset(area > 1e-12)


# ---------------------------------------------------------------------------
# helpers to place a model

def rot(axis, deg):
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


def trans(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def scale(k):
    m = np.eye(4)
    m[0, 0] = m[1, 1] = m[2, 2] = k
    return m


# ---------------------------------------------------------------------------
# colour sampling of the original surface

def _srgb_to_lin(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def _lin_to_srgb(c):
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(np.maximum(c, 0), 1 / 2.4) - 0.055)


def _sample_texture(img, uv):
    h, w = img.shape[:2]
    u = (uv[:, 0] % 1.0) * w - 0.5
    v = (uv[:, 1] % 1.0) * h - 0.5
    x0 = np.floor(u).astype(np.int64)
    y0 = np.floor(v).astype(np.int64)
    fx = (u - x0)[:, None]
    fy = (v - y0)[:, None]
    x0 %= w
    y0 %= h
    x1 = (x0 + 1) % w
    y1 = (y0 + 1) % h
    return ((img[y0, x0] * (1 - fx) + img[y0, x1] * fx) * (1 - fy) + (img[y1, x0] * (1 - fx) + img[y1, x1] * fx) * fy)


def surface_samples(scene, total=3_000_000, seed=1, min_per_tri=1):
    """points spread over the surface by area with their colour (rgba 0..1) and face normal"""
    rng = np.random.default_rng(seed)
    P = scene.P
    e1 = P[:, 1] - P[:, 0]
    e2 = P[:, 2] - P[:, 0]
    cr = np.cross(e1, e2)
    area = np.linalg.norm(cr, axis=1) / 2
    nrm = cr / np.maximum(1e-12, 2 * area)[:, None]
    counts = np.maximum(min_per_tri, np.round(area / area.sum() * total)).astype(np.int64)
    ti = np.repeat(np.arange(len(P)), counts)
    r1 = rng.random(len(ti))
    r2 = rng.random(len(ti))
    s = np.sqrt(r1)
    b0, b1, b2 = 1 - s, s * (1 - r2), s * r2
    bary = np.stack([b0, b1, b2], 1)
    pts = np.einsum("nk,nkd->nd", bary, P[ti])
    uv = np.einsum("nk,nkd->nd", bary, scene.UV[ti])
    vc = np.einsum("nk,nkd->nd", bary, scene.C[ti])
    col = np.empty((len(ti), 4), dtype=np.float32)
    mats = scene.mat[ti]
    for mi in np.unique(mats):
        m = mats == mi
        mat = scene.materials[mi]
        # glTF factors and vertex colours are linear, textures sRGB: multiply in linear, store sRGB
        c = np.tile(mat.color.astype(np.float32), (m.sum(), 1))
        if mat.image is not None:
            t = _sample_texture(mat.image, uv[m])
            c[:, :3] *= _srgb_to_lin(t[:, :3])
            c[:, 3] *= t[:, 3]
        c[:, :3] *= vc[m][:, :3].astype(np.float32)
        c[:, :3] = _lin_to_srgb(np.clip(c[:, :3], 0, 1))
        if mat.alpha_mode == "OPAQUE":
            c[:, 3] = 1.0
        elif mat.alpha_mode == "BLEND":
            # glass: cutout rendering can't blend, so it becomes a fine screen door (alpha 0.5 marks it for the
            # bake): tinted from outside, and you can see out of a cab or a cockpit
            a = c[:, 3:4]
            c[:, :3] = c[:, :3] * a + np.array([0.16, 0.2, 0.24], dtype=np.float32) * (1 - a)
            c[:, 3] = 0.5
        else:
            c[:, 3] = np.where(c[:, 3] >= mat.cutoff, 1.0, 0.0)
        col[m] = c
    return pts, col, nrm[ti]


# ---------------------------------------------------------------------------
# decimation

def decimate(scene, target, merge=None):
    import pymeshlab
    p = scene.P.reshape(-1, 3)
    f = np.arange(len(p)).reshape(-1, 3)
    ms = pymeshlab.MeshSet()
    ms.add_mesh(pymeshlab.Mesh(vertex_matrix=p, face_matrix=f))
    lo, hi = scene.bounds()
    diag = float(np.linalg.norm(hi - lo))
    ms.meshing_merge_close_vertices(threshold=pymeshlab.PercentageValue((merge or 0.0005) * 100))
    ms.meshing_remove_duplicate_faces()
    ms.meshing_remove_null_faces()
    if ms.current_mesh().face_number() > target:
        ms.meshing_decimation_quadric_edge_collapse(targetfacenum=int(target), preservenormal=True, preservetopology=False,
                                                    qualitythr=0.3, optimalplacement=True, planarquadric=True,
                                                    autoclean=True)
    m = ms.current_mesh()
    return m.vertex_matrix().astype(np.float64), m.face_matrix().astype(np.int64)


# ---------------------------------------------------------------------------
# uv atlas and the bake

def _corner_normals(V, F, crease=40.0):
    """smooth normals that stay sharp across creases, per face corner (n,3,3)"""
    tri = V[F]
    fn = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
    area = np.linalg.norm(fn, axis=1)
    fnu = fn / np.maximum(area, 1e-12)[:, None]
    cosc = math.cos(math.radians(crease))
    out = np.repeat(fnu[:, None, :], 3, 1).copy()
    # faces around each vertex
    corner_v = F.reshape(-1)
    corner_f = np.repeat(np.arange(len(F)), 3)
    order = np.argsort(corner_v, kind="stable")
    cv, cf = corner_v[order], corner_f[order]
    starts = np.flatnonzero(np.r_[True, cv[1:] != cv[:-1]])
    ends = np.r_[starts[1:], len(cv)]
    for s, e in zip(starts, ends):
        faces = cf[s:e]
        if len(faces) == 1:
            continue
        ns = fnu[faces]
        w = fn[faces]
        sim = ns @ ns.T >= cosc
        acc = sim.astype(np.float64) @ w
        acc /= np.maximum(np.linalg.norm(acc, axis=1), 1e-12)[:, None]
        v = cv[s]
        for k, fi in enumerate(faces):
            j = int(np.flatnonzero(F[fi] == v)[0])
            out[fi, j] = acc[k]
    return out


def _triplanar(name, P, N, tile):
    """a CC0 detail map (tools/detail) projected along the three axes, blended by the normal"""
    import uvbake
    a = uvbake.detail_map(name)
    if a is None:
        return np.full(len(P), 0.5)
    H, W = a.shape[:2]
    w = np.abs(N) ** 4
    w /= np.maximum(w.sum(1, keepdims=True), 1e-9)
    out = np.zeros(len(P))
    for ax, (u, v) in enumerate(((2, 1), (0, 2), (0, 1))):
        x = ((P[:, u] / tile + 0.37 * ax) % 1.0) * W
        y = ((-P[:, v] / tile + 0.61 * ax) % 1.0) * H
        out += w[:, ax] * a[y.astype(int) % H, x.astype(int) % W]
    return out


def weather(rgb, P, N):
    """the mod's look on flat-coloured models: blotchy paint, rain streaks, scratches, dust and mud low down"""
    blotch = _triplanar("grime_blotch", P, N, 3.0)
    streak = _triplanar("grime_streaks", P, N, 2.0)
    scratch = _triplanar("scratches", P, N, 1.5)
    vert = np.clip(1 - np.abs(N[:, 1]) * 1.6, 0, 1)
    rgb = rgb * (0.84 + 0.28 * blotch)[:, None]
    rgb = rgb * (1 - 0.22 * (1 - streak) * vert)[:, None]
    rgb = rgb + (np.clip(scratch - 0.6, 0, 1) * 0.35)[:, None] * (np.array([0.62, 0.62, 0.6]) - rgb)
    low = np.clip(1 - P[:, 1] / 1.1, 0, 1) ** 1.5
    dust = np.array([0.55, 0.5, 0.4])
    rgb = rgb + (low * 0.45 * (0.6 + 0.4 * blotch))[:, None] * (dust - rgb)
    up = np.clip(N[:, 1], 0, 1)
    rgb = rgb + (up * 0.12 * blotch)[:, None] * (dust - rgb)
    return np.clip(rgb, 0, 1)


def bake(parts, size=1024, samples=3_000_000, pad=3, padding_px=4, weathered=False):
    """parts: {name: (V, F, scene)}. Returns {name: (P (n,3,3), UV (n,3,2), N (n,3,3))} and the RGBA image."""
    import xatlas
    names = list(parts)
    allV, allF, off = [], [], 0
    for n in names:
        V, F, _ = parts[n]
        allV.append(V)
        allF.append(F + off)
        off += len(V)
    V = np.concatenate(allV)
    F = np.concatenate(allF)
    owner = np.concatenate([np.full(len(parts[n][1]), i) for i, n in enumerate(names)])
    atlas = xatlas.Atlas()
    atlas.add_mesh(V.astype(np.float32), F.astype(np.uint32))
    po = xatlas.PackOptions()
    po.resolution = size
    po.padding = pad
    po.bilinear = True
    po.bruteForce = False
    co = xatlas.ChartOptions()
    co.max_iterations = 2
    atlas.generate(chart_options=co, pack_options=po)
    vmap, F2, UV = atlas[0]
    V2 = V[vmap]
    tri3 = V2[F2]
    uv3 = UV[F2]
    # the source of each new face: xatlas keeps face order
    nrm3 = _corner_normals(V2, F2)

    # dense coloured samples of the originals, one cloud for everything
    scenes = [parts[n][2] for n in names]
    tot = sum(len(s) for s in scenes)
    pts, cols, nrms = [], [], []
    for s in scenes:
        p, c, nn = surface_samples(s, int(samples * len(s) / tot) + 1000)
        pts.append(p)
        cols.append(c)
        nrms.append(nn)
    pts = np.concatenate(pts)
    cols = np.concatenate(cols)
    nrms = np.concatenate(nrms)
    tree = cKDTree(pts)

    W = H = size
    img = np.zeros((H, W, 4), dtype=np.float32)
    filled = np.zeros((H, W), dtype=bool)
    # rasterise every face into texel positions
    TX, TY, TP, TN = [], [], [], []
    fn = np.cross(tri3[:, 1] - tri3[:, 0], tri3[:, 2] - tri3[:, 0])
    fn /= np.maximum(np.linalg.norm(fn, axis=1), 1e-12)[:, None]
    for i in range(len(F2)):
        uv = uv3[i] * [W, H]
        x0, y0 = np.floor(uv.min(0)).astype(int)
        x1, y1 = np.ceil(uv.max(0)).astype(int)
        x0, y0 = max(x0 - 1, 0), max(y0 - 1, 0)
        x1, y1 = min(x1 + 1, W - 1), min(y1 + 1, H - 1)
        xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        xs, ys = xs.ravel(), ys.ravel()
        a, b, c = uv
        d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(d) < 1e-12:
            continue
        l0 = ((b[1] - c[1]) * (xs - c[0]) + (c[0] - b[0]) * (ys - c[1])) / d
        l1 = ((c[1] - a[1]) * (xs - c[0]) + (a[0] - c[0]) * (ys - c[1])) / d
        l2 = 1 - l0 - l1
        tol = -0.75 / max(1.0, np.abs(d) ** 0.5)  # a little conservative so edges get covered
        m = (l0 >= tol) & (l1 >= tol) & (l2 >= tol)
        if not m.any():
            continue
        L = np.stack([l0[m], l1[m], l2[m]], 1).clip(0, 1)
        L /= L.sum(1, keepdims=True)
        TX.append((xs[m] - 0.5).astype(int))
        TY.append((ys[m] - 0.5).astype(int))
        TP.append(L @ tri3[i])
        TN.append(np.repeat(fn[i][None], m.sum(), 0))
    TX = np.concatenate(TX)
    TY = np.concatenate(TY)
    TP = np.concatenate(TP)
    TN = np.concatenate(TN)
    K = 12
    dist, idx = tree.query(TP, k=K, workers=-1)
    dots = np.einsum("nkd,nd->nk", nrms[idx], TN)
    # prefer samples facing the same way (both sides of a thin plate are close together)
    good = np.abs(dots) > 0.35
    good &= dots > -0.2
    w = np.where(good, 1.0 / (dist + 1e-4), 0.0)
    none = w.sum(1) == 0
    w[none, 0] = 1.0
    w /= w.sum(1, keepdims=True)
    c = np.einsum("nk,nkd->nd", w, cols[idx])
    if weathered:
        c[:, :3] = weather(c[:, :3].astype(np.float64), TP, TN)
    a = c[:, 3]
    # see-through parts (windows) become opaque tinted glass: a checkerboard of holes looked bad in game
    glass = a < 0.75
    c[glass, :3] = c[glass, :3] * 0.3 + np.array([46, 58, 66], dtype=np.float32) / 255 * 0.7
    c[:, 3] = 1.0
    img[TY, TX] = c
    filled[TY, TX] = True

    # bleed the islands outwards so mipmaps and filtering don't pick up the background
    rgb = img[..., :3].copy()
    a = img[..., 3].copy()
    mask = filled.copy()
    for _ in range(padding_px):
        acc = np.zeros_like(rgb)
        cnt = np.zeros(mask.shape)
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1)):
            sm = np.roll(np.roll(mask, dy, 0), dx, 1)
            acc += np.roll(np.roll(rgb, dy, 0), dx, 1) * sm[..., None]
            cnt += sm
        grow = (~mask) & (cnt > 0)
        rgb[grow] = acc[grow] / cnt[grow][:, None]
        a[grow] = 1.0
        mask |= grow
    out = np.concatenate([rgb, a[..., None]], -1)
    out[~mask] = 0
    image = Image.fromarray((out.clip(0, 1) * 255).astype(np.uint8), "RGBA")

    res = {}
    for i, n in enumerate(names):
        m = owner == i
        res[n] = (tri3[m], uv3[m], nrm3[m])
    return res, image


# ---------------------------------------------------------------------------
# keeping the original textures: decimate each material with its uvs, export the material's own image

def decimate_textured(sc, target):
    """one material's triangles -> (V, F, wedge uv (n,3,2)), decimated with uvs kept; falls back to the original.
    Goes through an OBJ with a material, the only way MeshLab marks the faces as textured."""
    import tempfile
    import pymeshlab
    P = sc.P.reshape(-1, 3)
    if len(sc) <= target:
        return P, np.arange(len(P)).reshape(-1, 3), sc.UV.copy()
    try:
        with tempfile.TemporaryDirectory() as d:
            Image.new("RGB", (4, 4)).save(os.path.join(d, "t.png"))
            with open(os.path.join(d, "m.mtl"), "w") as f:
                f.write("newmtl m\nmap_Kd t.png\n")
            # vertices welded by position, uvs per corner
            key = np.round(P / 1e-5).astype(np.int64)
            uniq, inv = np.unique(key, axis=0, return_inverse=True)
            inv = inv.reshape(-1)
            Vw = np.zeros((len(uniq), 3))
            Vw[inv] = P
            uv = sc.UV.reshape(-1, 2)
            lines = ["mtllib m.mtl", "usemtl m"]
            lines += ["v %.6f %.6f %.6f" % tuple(x) for x in Vw]
            # OBJ's v runs upwards, glTF's downwards
            lines += ["vt %.6f %.6f" % (u, 1 - v) for u, v in uv]
            tri = inv.reshape(-1, 3)
            for i, t in enumerate(tri):
                k = i * 3
                lines.append("f %d/%d %d/%d %d/%d" % (t[0] + 1, k + 1, t[1] + 1, k + 2, t[2] + 1, k + 3))
            with open(os.path.join(d, "m.obj"), "w") as f:
                f.write("\n".join(lines))
            ms = pymeshlab.MeshSet()
            ms.load_new_mesh(os.path.join(d, "m.obj"))
            ms.meshing_remove_null_faces()
            ms.meshing_decimation_quadric_edge_collapse_with_texture(targetfacenum=int(target), qualitythr=0.6,
                                                                     extratcoordw=1.0, preserveboundary=True,
                                                                     boundaryweight=1.5, optimalplacement=True,
                                                                     preservenormal=True, planarquadric=True)
            m = ms.current_mesh()
            V = m.vertex_matrix().astype(np.float64)
            F = m.face_matrix().astype(np.int64)
            UV = m.wedge_tex_coord_matrix().astype(np.float64).reshape(-1, 3, 2)
            UV[..., 1] = 1 - UV[..., 1]
        if len(F) == 0 or len(UV) != len(F):
            raise ValueError("lost the uvs")
        return V, F, UV
    except Exception as e:  # noqa: BLE001
        print("    (kept undecimated:", str(e).splitlines()[0], ")")
        return P, np.arange(len(P)).reshape(-1, 3), sc.UV.copy()


def material_image(mat, max_size=1024, glass_checker=True):
    """the material as a texture: base colour image times its factor (linear maths), alpha by the material mode"""
    if mat.image is None:
        return None
    img = mat.image.astype(np.float64)
    rgb = _lin_to_srgb(np.clip(_srgb_to_lin(img[..., :3]) * mat.color[:3], 0, 1))
    a = img[..., 3] * mat.color[3]
    if mat.alpha_mode == "OPAQUE":
        a = np.ones_like(a)
    elif mat.alpha_mode == "MASK":
        a = (a >= mat.cutoff).astype(np.float64)
    else:
        if glass_checker:
            tint = np.array([0.16, 0.2, 0.24])
            rgb = rgb * a[..., None] + tint * (1 - a[..., None])
            yy, xx = np.mgrid[0:a.shape[0], 0:a.shape[1]]
            a = ((xx + yy) % 2 == 0).astype(np.float64)
        else:
            # real see-through glass: its own alpha, never quite invisible
            a = np.clip(a * float(mat.color[3]) if len(mat.color) > 3 else a, 0.18, 1.0)
    out = Image.fromarray((np.dstack([rgb, a]).clip(0, 1) * 255).astype(np.uint8), "RGBA")
    if max(out.size) > max_size:
        k = max_size / max(out.size)
        out = out.resize((max(1, int(out.width * k)), max(1, int(out.height * k))), Image.LANCZOS if mat.alpha_mode != "BLEND" else Image.NEAREST)
    return out


def solid_colour(mat, sc_vertex_colours=None):
    """rgba 0..255 of an image-less material (factor and average vertex colour, linear maths)"""
    c = mat.color[:3].copy()
    if sc_vertex_colours is not None and len(sc_vertex_colours):
        c = c * sc_vertex_colours.reshape(-1, 4)[:, :3].mean(0)
    rgb = _lin_to_srgb(np.clip(c, 0, 1))
    return tuple(int(x * 255) for x in rgb), mat.alpha_mode


def write_mesh_sections(path, sections):
    """version 2: several textures in one file. sections: list of (texture ref, P (n,3,3), UV (n,3,2), N (n,3,3))"""
    buf = io.BytesIO()
    buf.write(b"MTMS")
    buf.write(struct.pack(">i", 2))
    buf.write(struct.pack(">i", len(sections)))
    for tex, P, UV, N in sections:
        t = tex.encode("utf-8")
        buf.write(struct.pack(">H", len(t)))
        buf.write(t)
        buf.write(struct.pack(">i", len(P)))
        buf.write(np.concatenate([P, UV, N], -1).astype(">f4").tobytes())
    with gzip.open(path, "wb", compresslevel=9) as f:
        f.write(buf.getvalue())


def read_mesh_sections(path):
    with gzip.open(path, "rb") as f:
        b = f.read()
    assert b[:4] == b"MTMS"
    ver = struct.unpack(">i", b[4:8])[0]
    if ver == 1:
        tex, P, UV, N = read_mesh(path)
        return [(tex, P, UV, N)]
    n = struct.unpack(">i", b[8:12])[0]
    o = 12
    out = []
    for _ in range(n):
        ln = struct.unpack(">H", b[o:o + 2])[0]
        tex = b[o + 2:o + 2 + ln].decode()
        o += 2 + ln
        cnt = struct.unpack(">i", b[o:o + 4])[0]
        o += 4
        d = np.frombuffer(b, dtype=">f4", offset=o, count=cnt * 24).astype(np.float64).reshape(cnt, 3, 8)
        o += cnt * 24 * 4
        out.append((tex, d[..., :3], d[..., 3:5], d[..., 5:8]))
    return out


# ---------------------------------------------------------------------------
# output

def write_mesh(path, texture, P, UV, N, origin=(0, 0, 0)):
    """gzip: 'MTMS' int version, utf texture, int tris, then per corner x y z u v nx ny nz (big-endian float)"""
    P = P - np.array(origin)
    data = np.concatenate([P, UV, N], -1).astype(">f4")
    buf = io.BytesIO()
    buf.write(b"MTMS")
    buf.write(struct.pack(">i", 1))
    t = texture.encode("utf-8")
    buf.write(struct.pack(">H", len(t)))
    buf.write(t)
    buf.write(struct.pack(">i", len(P)))
    buf.write(data.tobytes())
    with gzip.open(path, "wb", compresslevel=9) as f:
        f.write(buf.getvalue())


def read_mesh(path):
    with gzip.open(path, "rb") as f:
        b = f.read()
    assert b[:4] == b"MTMS"
    n = struct.unpack(">H", b[8:10])[0]
    tex = b[10:10 + n].decode()
    o = 10 + n
    cnt = struct.unpack(">i", b[o:o + 4])[0]
    d = np.frombuffer(b, dtype=">f4", offset=o + 4, count=cnt * 24).astype(np.float64).reshape(cnt, 3, 8)
    return tex, d[..., :3], d[..., 3:5], d[..., 5:8]


def ray_distance(P, origin, direction, max_dist=50.0):
    """distance along a ray to the nearest triangle of P (n,3,3), or max_dist (Moller-Trumbore, all at once)"""
    o = np.asarray(origin, dtype=np.float64)
    d = np.asarray(direction, dtype=np.float64)
    d = d / np.linalg.norm(d)
    v0, e1, e2 = P[:, 0], P[:, 1] - P[:, 0], P[:, 2] - P[:, 0]
    h = np.cross(d, e2)
    a = np.einsum("ij,ij->i", e1, h)
    ok = np.abs(a) > 1e-12
    f = np.where(ok, 1.0 / np.where(ok, a, 1), 0)
    sv = o - v0
    u = f * np.einsum("ij,ij->i", sv, h)
    q = np.cross(sv, e1)
    v = f * (q @ d)
    t = f * np.einsum("ij,ij->i", e2, q)
    hit = ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (t > 1e-6)
    return float(t[hit].min()) if hit.any() else max_dist


# ---------------------------------------------------------------------------
# preview renderer: textured triangles, z-buffer, a key light

def look(yaw, pitch):
    """camera rotation: yaw around y (0 = looking at the front, from +z), then pitch down"""
    return rot("x", pitch)[:3, :3] @ rot("y", -yaw)[:3, :3]


def render(items, size=(900, 600), yaw=35, pitch=20, bg=(206, 214, 222), fit=None, ground=True, transparent=False):
    """items: list of (P (n,3,3), UV (n,3,2) or None, image (PIL) or colour, N or None). Orthographic."""
    W, H = size
    R = look(yaw, pitch)
    allp = np.concatenate([it[0].reshape(-1, 3) for it in items])
    cam = allp @ R.T
    lo, hi = cam.min(0), cam.max(0)
    if fit is None:
        fit = 0.9 * min(W / (hi[0] - lo[0]), H / (hi[1] - lo[1]))
    cx, cy = (lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2
    zbuf = np.full((H, W), -np.inf)
    out = np.zeros((H, W, 3))
    out[:] = np.array(bg) / 255.0
    alpha = np.zeros((H, W))
    light = np.array([0.45, 0.8, 0.4])
    light /= np.linalg.norm(light)
    if ground:
        gy = allp[:, 1].min()
        ext = max(hi[0] - lo[0], hi[1] - lo[1]) * 0.8
        gx = np.array([[-ext, gy, -ext], [ext, gy, -ext], [ext, gy, ext], [-ext, gy, ext]])
        gx[:, 0] += allp[:, 0].mean()
        gx[:, 2] += allp[:, 2].mean()
        gp = np.array([[gx[0], gx[1], gx[2]], [gx[0], gx[2], gx[3]]])
        items = [(gp, None, (150, 150, 140), None)] + list(items)
    for P, UV, tex, N in items:
        cp = P.reshape(-1, 3) @ R.T
        sx = (cp[:, 0] - cx) * fit + W / 2
        sy = H / 2 - (cp[:, 1] - cy) * fit
        sz = cp[:, 2]
        S = np.stack([sx, sy, sz], 1).reshape(-1, 3, 3)
        fn = np.cross(P[:, 1] - P[:, 0], P[:, 2] - P[:, 0])
        fn /= np.maximum(np.linalg.norm(fn, axis=1), 1e-12)[:, None]
        if isinstance(tex, Image.Image):
            timg = np.asarray(tex.convert("RGBA")).astype(np.float64) / 255.0
        else:
            timg = None
            col = np.array(tex[:3]) / 255.0
        for i in range(len(S)):
            s = S[i]
            x0, y0 = np.floor(s[:, :2].min(0)).astype(int)
            x1, y1 = np.ceil(s[:, :2].max(0)).astype(int)
            x0, y0, x1, y1 = max(x0, 0), max(y0, 0), min(x1, W - 1), min(y1, H - 1)
            if x0 > x1 or y0 > y1:
                continue
            xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            a, b, c = s[0], s[1], s[2]
            d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
            if abs(d) < 1e-9:
                continue
            l0 = ((b[1] - c[1]) * (xs - c[0]) + (c[0] - b[0]) * (ys - c[1])) / d
            l1 = ((c[1] - a[1]) * (xs - c[0]) + (a[0] - c[0]) * (ys - c[1])) / d
            l2 = 1 - l0 - l1
            m = (l0 >= 0) & (l1 >= 0) & (l2 >= 0)
            if not m.any():
                continue
            z = l0 * a[2] + l1 * b[2] + l2 * c[2]
            yy = (ys[m] - 0.5).astype(int)
            xx = (xs[m] - 0.5).astype(int)
            zz = z[m]
            vis = zz > zbuf[yy, xx]
            if not vis.any():
                continue
            yy, xx, zz = yy[vis], xx[vis], zz[vis]
            if timg is not None and UV is not None:
                u = l0[m][vis] * UV[i, 0, 0] + l1[m][vis] * UV[i, 1, 0] + l2[m][vis] * UV[i, 2, 0]
                v = l0[m][vis] * UV[i, 0, 1] + l1[m][vis] * UV[i, 1, 1] + l2[m][vis] * UV[i, 2, 1]
                th, tw = timg.shape[:2]
                px = timg[((v % 1.0) * th).astype(int) % th, ((u % 1.0) * tw).astype(int) % tw]
                keep = px[:, 3] > 0.1
                yy, xx, zz, px = yy[keep], xx[keep], zz[keep], px[keep]
                cc = px[:, :3]
            else:
                cc = np.repeat(col[None], len(yy), 0)
            n = fn[i]
            # two-sided, like the mod's no-cull render type
            view = R[2]
            if np.dot(n, view) < 0:
                n = -n
            shade = 0.45 + 0.55 * max(0.0, float(np.dot(n, light)))
            zbuf[yy, xx] = zz
            out[yy, xx] = cc * shade
            alpha[yy, xx] = 1
    im = (out.clip(0, 1) * 255).astype(np.uint8)
    if transparent:
        return Image.fromarray(np.dstack([im, (alpha * 255).astype(np.uint8)]), "RGBA")
    return Image.fromarray(im, "RGB")


def flat_preview(scene, size=(900, 600), yaw=35, pitch=20, colors=None, max_tris=60000):
    """untextured preview of a loaded scene, each part/material its own colour; for setting up a model"""
    items = []
    if colors is None:
        colors = {}
    sub = scene
    if len(scene) > max_tris:
        idx = np.random.default_rng(0).choice(len(scene), max_tris, replace=False)
        sub = scene.subset(np.sort(idx))
    for mi in np.unique(sub.mat):
        m = sub.mat == mi
        mat = sub.materials[mi]
        c = mat.color[:3]
        if mat.image is not None:
            c = c * mat.image[..., :3].reshape(-1, 3).mean(0)
        items.append((sub.P[m], None, tuple(int(x * 255) for x in c), None))
    return render(items, size, yaw, pitch)
