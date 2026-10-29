"""
Real 3D models for the drones, the Kornet, the truck and the goggles (sources and licences in MODELS.md).
Each source .glb is turned nose to +z, stripped of what the mod draws by itself (warheads, propellers, wheels),
scaled and placed where the box model of the same name was (so the renderers need no new numbers), decimated
and written as models/entity/NAME.mesh with its textures under textures/entity/mesh/NAME/.

    python3 tools/gen_meshes.py              # all
    python3 tools/gen_meshes.py fpv_drone    # one
Sources live outside the repo: MODELS_DIR (default ../models/fpv).
"""
import json
import os
import shutil
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import meshkit as mk  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MODID = "fpvdrones"
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", MODID)
MODELS_DIR = os.environ.get("MODELS_DIR", os.path.join(os.path.dirname(ROOT), "models", "fpv"))
PREVIEW = os.path.join(HERE, "_preview")


def comp_info(sc):
    """connected pieces: (mask, info) with centre/min/max/tris/mat/name"""
    comp = sc.components()
    out = []
    for c in np.unique(comp):
        m = comp == c
        p = sc.P[m].reshape(-1, 3)
        out.append((m, {"center": (p.min(0) + p.max(0)) / 2, "min": p.min(0), "max": p.max(0), "tris": int(m.sum()),
                        "mat": sc.materials[sc.mat[m][0]].name, "name": sc.node_names[sc.node[m][0]]}))
    return out


def select(sc, test):
    keep = np.zeros(len(sc.P), dtype=bool)
    for m, info in comp_info(sc):
        if test(info):
            keep |= m
    return keep


def load(cfg):
    sc = mk.load_glb(os.path.join(MODELS_DIR, cfg["src"] + ".glb"))
    M = np.eye(4)
    for ax, deg in cfg.get("rot", []):
        M = mk.rot(ax, deg) @ M
    return sc.transform(M)


def place(sc, cfg):
    """scale and shift into the box model's frame (JSON pixels): returns the 4x4 that does it"""
    lo, hi = sc.bounds()
    if "k" in cfg:
        k = cfg["k"]
    else:
        ax, span = cfg["fit"]
        ref = cfg.get("fit_box")
        a, b = (ref if ref is not None else (lo, hi))
        k = span / (b[ax] - a[ax])
    lo, hi = lo * k, hi * k
    at = cfg["at"]
    t = np.zeros(3)
    for i, key in enumerate("xyz"):
        if key + "0" in at:
            t[i] = at[key + "0"] - lo[i]
        elif key + "1" in at:
            t[i] = at[key + "1"] - hi[i]
        else:
            t[i] = at.get(key + "c", 8.0) - (lo[i] + hi[i]) / 2
    return mk.trans(*t) @ mk.scale(k)


def export(name, sc, cfg, M):
    """decimate per material, keep the source textures, write the mesh in block units (pixels / 16)"""
    sc = sc.transform(M)
    tex_dir = os.path.join(ASSETS, "textures", "entity", "mesh", name)
    shutil.rmtree(tex_dir, ignore_errors=True)
    os.makedirs(tex_dir, exist_ok=True)
    recolor = cfg.get("recolor", {})
    tint = cfg.get("tint")
    budget = cfg.get("tris", 8000)
    max_tex = cfg.get("max_tex", 1024)
    total = len(sc)
    palette, sections, tex_of = [], {}, {}
    for mi in np.unique(sc.mat):
        sub = sc.subset(sc.mat == mi)
        mat = sc.materials[mi]
        n = max(min(len(sub), 300), int(budget * len(sub) / total))
        V, F, UVw = mk.decimate_textured(sub, n)
        P3 = V[F]
        N3 = mk._corner_normals(V, F)
        if mat.image is not None and mat.name not in recolor:
            key = id(mat.image)
            if key not in tex_of:
                ref = f"{'glass_' if mat.alpha_mode == 'BLEND' else ''}t{len(tex_of)}"
                img = mk.material_image(mat, max_tex, glass_checker=False)
                if mat.alpha_mode == "MASK":
                    a = np.asarray(img)[..., 3]
                    img.putalpha(Image.fromarray(np.where(a > 127, 255, 0).astype(np.uint8)))
                if tint:
                    img = tint(img)
                img.save(os.path.join(tex_dir, ref + ".png"), optimize=True)
                tex_of[key] = ref
            ref, UV = tex_of[key], UVw
        else:
            c = recolor.get(mat.name) or tuple(int(x) for x in mk.solid_colour(mat)[0][:3])
            if tint and mat.name not in recolor:
                c = tuple(np.asarray(tint(Image.new("RGBA", (1, 1), tuple(c) + (255,))))[0, 0, :3])
            glass = mat.alpha_mode == "BLEND"
            ref = "glass" if glass else "palette"
            if glass:
                UV = np.full(P3.shape[:2] + (2,), 0.5)
            else:
                palette.append(tuple(int(x) for x in c))
                i = len(palette) - 1
                UV = np.zeros(P3.shape[:2] + (2,))
                UV[..., 0] = ((i % 16) * 4 + 2) / 64.0
                UV[..., 1] = ((i // 16) * 4 + 2) / 64.0
        sections.setdefault(ref, []).append((P3 / 16.0, UV, N3))
    if palette:
        pim = Image.new("RGBA", (64, 64), (0, 0, 0, 255))
        for i, c in enumerate(palette[:256]):
            pim.paste(Image.new("RGBA", (4, 4), c + (255,)), ((i % 16) * 4, (i // 16) * 4))
        pim.save(os.path.join(tex_dir, "palette.png"))
    if "glass" in sections:
        g = np.zeros((4, 4, 4), dtype=np.uint8)
        g[..., :3] = (40, 48, 56)
        g[..., 3] = 150
        Image.fromarray(g, "RGBA").save(os.path.join(tex_dir, "glass.png"))
    out = []
    for ref, items in sections.items():
        out.append((f"{MODID}:entity/mesh/{name}/{ref}", np.concatenate([i[0] for i in items]),
                    np.concatenate([i[1] for i in items]), np.concatenate([i[2] for i in items])))
    path = os.path.join(ASSETS, "models", "entity", name + ".mesh")
    mk.write_mesh_sections(path, out)
    P = np.concatenate([o[1] for o in out]).reshape(-1, 3) * 16
    print(f"  {name}: {total} -> {sum(len(o[1]) for o in out)} tris, px {np.round(P.min(0), 2)} .. {np.round(P.max(0), 2)}")
    return sc


def json_bounds(name):
    j = json.load(open(os.path.join(ASSETS, "models", "entity", name + ".json")))
    el = j["elements"]
    lo = np.array([min(min(e["from"][i], e["to"][i]) for e in el) for i in range(3)])
    hi = np.array([max(max(e["from"][i], e["to"][i]) for e in el) for i in range(3)])
    return lo, hi


def preview(names, path):
    items = []
    x = 0.0
    for n in names:
        secs = mk.read_mesh_sections(os.path.join(ASSETS, "models", "entity", n + ".mesh"))
        lo = np.concatenate([s[1].reshape(-1, 3) for s in secs]).min(0)
        hi = np.concatenate([s[1].reshape(-1, 3) for s in secs]).max(0)
        for tex, P, UV, N in secs:
            img = Image.open(os.path.join(ASSETS, "textures", tex.split(":")[1] + ".png")).convert("RGB")
            Q = P.copy()
            Q[..., 0] += x - lo[0]
            items.append((Q, UV, img, N))
        x += (hi[0] - lo[0]) + 0.3
    W = Image.new("RGB", (1600, 500))
    W.paste(mk.render(items, (800, 500), 35, 25, ground=False), (0, 0))
    W.paste(mk.render(items, (800, 500), -35, 20, ground=False), (800, 0))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    W.save(path)


# ---------------------------------------------------------------------------------------------------------------
# what goes where. Builders get the config and return the names they wrote.

BUILDERS = {}


def builder(fn):
    BUILDERS[fn.__name__[6:]] = fn
    return fn


def prop_mesh(name, sc, hub, k, cfg=None):
    """one propeller, hub on (8, 8, 8) of its own model, k pixels per source unit"""
    M = mk.trans(8, 8, 8) @ mk.scale(k) @ mk.trans(-hub[0], -hub[1], -hub[2])
    export(name, sc, cfg or {"tris": 1500}, M)
    return name


@builder
def build_fpv():
    """7-inch FPV, Kvasovich's model: the RPG warhead and its straps go (payloads are the mod's), so do the props"""
    sc = load({"src": "fpv_lp"})
    infos = comp_info(sc)
    props = [(m, i) for m, i in infos if i["tris"] == 534]
    drop = np.zeros(len(sc.P), dtype=bool)
    for m, i in infos:
        if i["min"][1] < -0.0185 or i["tris"] == 534:
            drop |= m
    body = sc.subset(~drop)
    motors = [i["center"] for m, i in infos if i["tris"] == 872]
    mc = np.mean(motors, 0)
    k = 6.0 / np.mean([abs(c[0] - mc[0]) for c in motors])
    lo, _ = body.bounds()
    # motor square centred on (8, 8), bottom plate at y 6.5 (DroneRenderer.FPV_BOTTOM)
    M = mk.trans(8 - mc[0] * k, 6.5 - lo[1] * k, 8 - mc[2] * k) @ mk.scale(k)
    export("fpv_drone", body, {"tris": 9000}, M)
    pm, pi = props[0]
    hub = np.array([np.mean([c[0] for c in motors if np.sign(c[0]) == np.sign(pi["center"][0]) and
                             np.sign(c[2] - mc[2]) == np.sign(pi["center"][2] - mc[2])]), pi["center"][1],
                    np.mean([c[2] for c in motors if np.sign(c[0]) == np.sign(pi["center"][0]) and
                             np.sign(c[2] - mc[2]) == np.sign(pi["center"][2] - mc[2])])])
    print("  fpv prop hub y px:", round(6.5 + (hub[1] - lo[1]) * k, 2))
    # the renderer draws the propeller at half scale
    prop_mesh("propeller", sc.subset(pm), hub, k / 0.5)
    return ["fpv_drone", "propeller"]


@builder
def build_mavic():
    """DJI Mavic 3 by maks_modeller: one black material, painted the Mavic's dark grey; props come off"""
    sc = load({"src": "mavic_b"})
    infos = comp_info(sc)
    hubs = [i["center"] for m, i in infos if i["tris"] == 192 and i["min"][1] > 4.5 and i["max"][1] < 5.5]
    hubs = np.array(sorted(hubs, key=lambda c: (c[2], c[0])))
    own = {}
    for m, i in infos:
        c = i["center"]
        d = np.hypot(hubs[:, 0] - c[0], hubs[:, 2] - c[2])
        if d.min() < 16 and i["min"][1] > 3.0 and i["tris"] in (8, 12, 130, 192):
            own.setdefault(int(np.argmin(d)), np.zeros(len(sc.P), dtype=bool))
            own[int(np.argmin(d))] |= m
    drop = np.zeros(len(sc.P), dtype=bool)
    for m in own.values():
        drop |= m
    body = sc.subset(~drop)
    xc, zc = hubs[:, 0].mean(), hubs[:, 2].mean()
    k = 5.6 / np.mean(np.abs(hubs[:, 0] - xc))
    lo, _ = body.bounds()
    M = mk.trans(8 - xc * k, 6.0 - lo[1] * k, 8.1 - zc * k) @ mk.scale(k)
    export("mavic3", body, {"tris": 9000, "recolor": {"Scene_-_Root": (92, 94, 98)}}, M)
    for h in hubs:
        print("  mavic prop px: x %.2f y %.2f z %.2f" % (8 + (h[0] - xc) * k, 6.0 + (h[1] - lo[1]) * k, 8.1 + (h[2] - zc) * k))
    prop_mesh("propeller_mavic", sc.subset(own[0]), hubs[0], k / 0.32,
              {"tris": 1500, "recolor": {"Scene_-_Root": (52, 53, 57)}})
    return ["mavic3", "propeller_mavic"]


SHAHED_GREY = {}
GERAN_BLACK = {"Main": (34, 35, 37), "Komponen_Abu abu": (44, 45, 47), "Silver.001": (52, 53, 56),
               "Main.Biru": (34, 35, 37), "Main.Hitam": (20, 20, 21)}


def shahed_like(name, recolor):
    """Shahed-136 by faintastic: pusher propeller off (the renderer spins its own), hub on the box model's spot"""
    sc = load({"src": "shahed_f"})
    prop_nodes = [i for i, n in enumerate(sc.node_names) if n.startswith("Baling")]
    is_prop = np.isin(sc.node, prop_nodes)
    body, prop = sc.subset(~is_prop), sc.subset(is_prop)
    plo, phi = prop.bounds()
    hub = (plo + phi) / 2
    _, bhi = body.bounds()
    # hub at (8, 8.25, -7.7) like StrikeDroneRenderer's propeller, nose at 29.2 like the box model
    k = (29.2 + 7.7) / (bhi[2] - hub[2])
    M = mk.trans(8 - hub[0] * k, 8.25 - hub[1] * k, -7.7 - hub[2] * k) @ mk.scale(k)
    export(name, body, {"tris": 14000, "recolor": recolor}, M)
    return body, prop, hub, k


@builder
def build_shahed():
    body, prop, hub, k = shahed_like("shahed136", SHAHED_GREY)
    shahed_like("geran2", GERAN_BLACK)
    # the renderer turns the propeller up 90 degrees about x and draws it at 0.55: lay it flat, and bigger
    flat = prop.transform(mk.rot("x", -90) @ mk.trans(-hub[0], -hub[1], -hub[2]))
    prop_mesh("propeller_shahed", flat, np.zeros(3), k / 0.55, {"tris": 2000})
    return ["shahed136", "geran2", "propeller_shahed"]


@builder
def build_jets():
    """Shahed-238 by Rudy27 (a 2M triangle scan, brought down to ~22k): the jet ones, grey 238 and black Geran-5"""
    sc = load({"src": "shahed238", "rot": [("y", 180)]})
    lo, hi = sc.bounds()
    for name, z0, z1, col in (("shahed238", -8.2, 29.2, (150, 152, 156)), ("geran5", -11.2, 28.6, (36, 37, 39))):
        k = (z1 - z0) / (hi[2] - lo[2])
        M = mk.trans(8 - (lo[0] + hi[0]) / 2 * k, 8.6 - (lo[1] + hi[1]) / 2 * k, z0 - lo[2] * k) @ mk.scale(k)
        export(name, sc, {"tris": 14000, "recolor": {"material_0": col}}, M)
    return ["shahed238", "geran5"]


def olive(img):
    """the export model's desert tan to Russian army green, darks kept"""
    a = np.asarray(img.convert("RGBA")).astype(np.float64)
    a[..., :3] *= np.array([0.6, 0.72, 0.5])
    return Image.fromarray(a.clip(0, 255).astype(np.uint8), "RGBA")


@builder
def build_kornet():
    """9M133 Kornet by XEPCOH_552: tripod / yoke that pans / sight that elevates / launch tube, each with its
    origin where KornetRenderer turns it (model pixel (8, 0, 8)); the loose missile beside it goes"""
    sc = load({"src": "kornet", "rot": [("y", -90)]})
    infos = comp_info(sc)
    k = 3.5
    groups = {"kornet_tripod": np.zeros(len(sc.P), bool), "kornet_head": np.zeros(len(sc.P), bool),
              "kornet_sight": np.zeros(len(sc.P), bool), "kornet_tube": np.zeros(len(sc.P), bool)}
    for m, i in infos:
        if i["name"] == "Object_4":
            continue
        if i["name"] == "Object_8":
            groups["kornet_tube"] |= m
        elif i["name"] == "Object_10" or i["max"][1] < -2.85:
            groups["kornet_tripod"] |= m
        elif i["max"][1] < -1.72:
            groups["kornet_head"] |= m
        else:
            groups["kornet_sight"] |= m
    feet = sc.subset(groups["kornet_tripod"]).bounds()[0][1]
    pivot_y = -2.92
    tube = sc.subset(groups["kornet_tube"]).bounds()
    axis_y = (tube[0][1] + tube[1][1]) / 2
    print("  kornet: pivot %.3f blocks above the ground, tube axis %.3f above the pivot, tube front %.3f"
          % ((pivot_y - feet) * k / 16, (axis_y - pivot_y) * k / 16, tube[1][2] * k / 16))
    origins = {"kornet_tripod": feet, "kornet_head": pivot_y, "kornet_sight": axis_y, "kornet_tube": axis_y}
    for name, m in groups.items():
        M = mk.trans(8, -origins[name] * k, 8) @ mk.scale(k)
        export(name, sc.subset(m), {"tris": 6000, "tint": olive}, M)
    return list(groups)


@builder
def build_truck():
    """KamAZ-5350 (42manako's KamAZ-5340 model): hull in TruckRenderer's frame (y + 1.5, x3), one wheel as
    truck_wheel; prints the wheel spots and radius for LaunchTruckEntity"""
    sc = load({"src": "kamaz5340", "rot": [("y", 90)]})
    lo, hi = sc.bounds()
    kw = 7.47 / (hi[2] - lo[2])                       # blocks per source unit: the old truck's length
    W = mk.trans(0, 0, -0.12) @ mk.scale(kw) @ mk.trans(-(lo[0] + hi[0]) / 2, -lo[1], -(lo[2] + hi[2]) / 2)
    to_px = mk.scale(16) @ mk.trans(0.5, 0.5, 0.5) @ mk.scale(1 / 3) @ mk.trans(0, -1.5, 0)
    wheel_node = sc.node_names.index("Object_6")
    wheels = sc.subset(sc.node == wheel_node)
    hull = sc.subset(sc.node != wheel_node)
    # the canvas bows, top rails and stakes over the bed would go through the launch rack: a flat bed
    def bow(i):
        ylo, yhi = (i["min"][1] - lo[1]) * kw, (i["max"][1] - lo[1]) * kw
        foot = max(i["max"][0] - i["min"][0], i["max"][2] - i["min"][2]) * kw
        zw = (i["center"][2] - (lo[2] + hi[2]) / 2) * kw - 0.12
        return zw < 1.35 and (ylo > 1.45 or yhi > 1.55 and foot < 0.5)
    hull = hull.subset(~select(hull, bow))
    tyres = [i for m, i in comp_info(wheels) if i["tris"] > 1400]
    centres = sorted([i["center"] for i in tyres], key=lambda c: (-c[2], c[0]))
    r = np.mean([(i["max"][1] - i["min"][1]) / 2 for i in tyres])
    export("truck", hull, {"tris": 30000}, to_px @ W)
    spots = []
    for c in centres:
        wc = (W @ np.append(c, 1))[:3]
        spots.append("{%.2f, %.2f}" % (wc[0], wc[2]))
    print("  WHEELS = {" + ", ".join(spots) + "}; WHEEL_RADIUS = %.3f" % (r * kw))
    # one wheel, the front left, centred for the renderer (translate to the hub, x3, model centre on it)
    c = centres[0]
    near = select(wheels, lambda i: abs(i["center"][0] - c[0]) < 1.6 and np.hypot(i["center"][1] - c[1], i["center"][2] - c[2]) < r)
    M = mk.trans(8, 8, 8) @ mk.scale(16 / 3 * kw) @ mk.trans(-c[0], -c[1], -c[2])
    export("truck_wheel", wheels.subset(near), {"tris": 2500}, M)
    cab = hull.subset(hull.node == sc.node_names.index("Object_5")).bounds()
    cl, ch = (W @ np.append(cab[0], 1))[:3], (W @ np.append(cab[1], 1))[:3]
    print("  cab interior (blocks): %s .. %s" % (np.round(cl, 2), np.round(ch, 2)))
    bed = hull.subset(select(hull, lambda i: i["center"][2] < (lo[2] + hi[2]) / 2 and i["max"][0] - i["min"][0] > (hi[0] - lo[0]) * 0.8))
    if len(bed):
        print("  bed top (blocks): %.2f" % ((bed.bounds()[1][1] - lo[1]) * kw))
    return ["truck", "truck_wheel"]


@builder
def build_goggles():
    """DJI-style FPV goggles (line-o): drawn on the head by GogglesLayer in head space, blocks, y up, face to +z:
    the head is x -0.25..0.25, y 0..0.5, z -0.25..0.25"""
    sc = load({"src": "goggles"})
    k = 0.42
    # visor centre on the eyes (y 0.28), its back on the face (z 0.25), a little wider so the strap clears the head
    M = mk.trans(0, 0.28 - 0.46 * k, 0.25 - 0.3 * k) @ np.diag([k * 1.12, k, k, 1.0])
    export("goggles", sc, {"tris": 9000}, mk.scale(16) @ M)
    return ["goggles"]


def _parts(name, fn=None, bare=False):
    """mesh sections of a model, points mapped by fn (px array (n,3,3) -> anything); bare: without add_boxes'"""
    out = []
    for tex, P, UV, N in mk.read_mesh_sections(os.path.join(ASSETS, "models", "entity", name + ".mesh")):
        if bare and tex.endswith("/parts"):
            continue
        img = Image.open(os.path.join(ASSETS, "textures", tex.split(":")[1] + ".png")).convert("RGB")
        Q = P * 16
        out.append(((fn(Q) if fn else Q), UV, img, N))
    return out


def _icon(items, name, yaw, pitch):
    big = mk.render(items, (256, 256), yaw, pitch, ground=False, transparent=True).convert("RGBA")
    im = big.resize((64, 64), Image.LANCZOS)
    a = np.asarray(im).astype(np.float64)
    alpha = a[..., 3] > 100
    grown = alpha.copy()
    grown[1:] |= alpha[:-1]
    grown[:-1] |= alpha[1:]
    grown[:, 1:] |= alpha[:, :-1]
    grown[:, :-1] |= alpha[:, 1:]
    out = np.zeros((64, 64, 4), dtype=np.uint8)
    out[..., :3] = np.clip(a[..., :3] * 1.08, 0, 255).astype(np.uint8)
    out[..., 3] = np.where(alpha, 255, 0)
    out[grown & ~alpha] = (20, 22, 24, 255)
    Image.fromarray(out, "RGBA").save(os.path.join(ASSETS, "textures", "item", name + ".png"))
    with open(os.path.join(ASSETS, "models", "item", name + ".json"), "w") as f:
        json.dump({"parent": "item/generated", "textures": {"layer0": f"{MODID}:item/{name}"}}, f, indent=2)


def _at(hub, k, rot=None):
    def fn(Q):
        R = Q - 8.0
        if rot is not None:
            R = R @ rot.T
        return R * k + np.asarray(hub)
    return fn


def add_boxes(name, boxes):
    """3D-printed bits the sources don't have (drop tails, fins, exhaust): (from, to, colour) boxes in JSON pixels,
    added to the mesh as their own section"""
    path = os.path.join(ASSETS, "models", "entity", name + ".mesh")
    secs = mk.read_mesh_sections(path)
    cols = sorted({c for _, _, c in boxes})
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    for i, c in enumerate(cols):
        im.paste(Image.new("RGBA", (4, 4), tuple(c) + (255,)), ((i % 4) * 4, (i // 4) * 4))
    im.save(os.path.join(ASSETS, "textures", "entity", "mesh", name, "parts.png"))
    P, UV, N = [], [], []
    for a, b, c in boxes:
        a, b = np.minimum(a, b), np.maximum(a, b)
        i = cols.index(c)
        uv = (((i % 4) * 4 + 2) / 16.0, ((i // 4) * 4 + 2) / 16.0)
        for ax in range(3):
            for side in (0, 1):
                n = np.zeros(3)
                n[ax] = 1 if side else -1
                u, v = [k for k in range(3) if k != ax]
                q = []
                for du, dv in ((0, 0), (1, 0), (1, 1), (0, 1)):
                    pt = np.array(a, float)
                    pt[ax] = b[ax] if side else a[ax]
                    pt[u] = b[u] if du else a[u]
                    pt[v] = b[v] if dv else a[v]
                    q.append(pt)
                if np.dot(np.cross(q[1] - q[0], q[2] - q[0]), n) < 0:
                    q = q[::-1]
                for t in ((q[0], q[1], q[2]), (q[0], q[2], q[3])):
                    P.append(np.array(t) / 16.0)
                    UV.append(np.full((3, 2), uv))
                    N.append(np.tile(n, (3, 1)))
    secs.append((f"{MODID}:entity/mesh/{name}/parts", np.array(P), np.array(UV), np.array(N)))
    mk.write_mesh_sections(path, secs)


def axial(sc, z0, z1, diameter, cut=None, nose_x=-1):
    """a round of ammunition lying along x with its nose to -x: front part (to {cut}) along +z, nose at z1,
    tail at z0, made {diameter} px thick (the sources are long and thin, the drop rounds stubby)"""
    sc = sc.transform(mk.rot("y", 90 * -nose_x))
    if cut is not None:
        c = sc.P[:, :, 2].mean(1)
        lo, hi = sc.bounds()
        sc = sc.subset(c > hi[2] - (hi[2] - lo[2]) * cut)
    lo, hi = sc.bounds()
    kz = (z1 - z0) / (hi[2] - lo[2])
    kr = diameter / max(hi[0] - lo[0], hi[1] - lo[1])
    return sc, mk.trans(8 - (lo[0] + hi[0]) / 2 * kr, 8 - (lo[1] + hi[1]) / 2 * kr, z0 - lo[2] * kz) @ np.diag([kr, kr, kz, 1])


DROP_TAIL = (40, 41, 43)


def drop_tail(z0, z1, span, hub):
    """the printed tail a drone round gets: a hub and a fin cross"""
    h, s = hub / 2, span / 2
    return [((8 - h, 8 - h, z0), (8 + h, 8 + h, z1), DROP_TAIL),
            ((8 - s, 7.85, z0), (8 + s, 8.15, z0 + (z1 - z0) * 0.55), DROP_TAIL),
            ((7.85, 8 - s, z0), (8.15, 8 + s, z0 + (z1 - z0) * 0.55), DROP_TAIL)]


@builder
def build_payloads():
    """drop rounds and missiles: PG-7V and the 30 mm round (2A42 shell's projectile as the VOG-17) by their authors
    in MODELS.md; the drop ones get printed tails, the missiles their flame"""
    olive_rpg = {"material": (78, 86, 54)}
    rpg = load({"src": "rpg_lp"})
    pg = rpg.subset(select(rpg, lambda i: i["name"] == "Object_4"))
    sc, M = axial(pg, 4.0, 15.5, 3.3, cut=0.6)
    export("pg7vs", sc, {"tris": 1500, "recolor": olive_rpg}, M)
    add_boxes("pg7vs", drop_tail(0.5, 4.6, 3.6, 1.4))

    sc, M = axial(load({"src": "og7b"}), 4.0, 15.5, 2.6, cut=0.62)
    export("og7", sc, {"tris": 3000}, M)
    add_boxes("og7", drop_tail(0.5, 4.6, 3.2, 1.3))

    shell = load({"src": "shell30"})
    k = 7.2 / (1.358 - 0.264)
    proj = shell.subset(select(shell, lambda i: i["name"] in ("Object_3", "Object_4")))
    export("vog17m", proj, {"tris": 1200}, mk.trans(8, 5.0 - 0.264 * k, 8) @ mk.scale(k))
    rx = mk.rot("x", -90)
    add_boxes("vog17m", [((7.3, 2.2, 7.3), (8.7, 5.0, 8.7), DROP_TAIL), ((6.8, 1.5, 6.8), (9.2, 2.2, 9.2), DROP_TAIL),
                         ((5.5, 1.5, 7.8), (10.5, 4.2, 8.2), DROP_TAIL), ((7.8, 1.5, 5.5), (8.2, 4.2, 10.5), DROP_TAIL)])
    k = 2.8 / 0.41
    export("shell_30mm", shell, {"tris": 1500}, mk.trans(8, 8 - 0.035 * k, 8) @ mk.scale(k))

    g = load({"src": "rgd5"})
    k = 7.6 / 4.99
    lo, hi = g.bounds()
    export("rgd5", g, {"tris": 3000}, mk.trans(8 - (lo[0] + hi[0]) / 2 * k, 4.0 - lo[1] * k, 8 - (lo[2] + hi[2]) / 2 * k) @ mk.scale(k))

    can = load({"src": "canister_b", "rot": [("y", 90)]})
    lo, hi = can.bounds()
    k = 12.4 / (hi[1] - lo[1])
    export("explosive_canister", can, {"tris": 1831},
           mk.trans(8 - (lo[0] + hi[0]) / 2 * k, 2.0 - lo[1] * k, 8 - (lo[2] + hi[2]) / 2 * k) @ mk.scale(k))

    flame = (255, 196, 96)
    jav = load({"src": "javelin_m"})
    lo, hi = jav.bounds()
    k = 16.0 / (hi[2] - lo[2])
    export("javelin_missile_flight", jav, {"tris": 1662}, mk.trans(8, 8, -lo[2] * k) @ mk.scale(k))
    add_boxes("javelin_missile_flight", [((7.44, 7.44, -5.6), (8.56, 8.56, 0.0), flame)])

    kor = load({"src": "kornet"})
    kor = kor.subset(select(kor, lambda i: i["name"] == "Object_4"))
    sc, M = axial(kor, -0.8, 16.8, 5.76, nose_x=1)
    export("kornet_missile_flight", sc, {"tris": 852}, M)
    add_boxes("kornet_missile_flight", [((7.4, 7.4, -6.4), (8.6, 8.6, -0.8), flame)])
    return ["pg7vs", "og7", "vog17m", "rgd5", "explosive_canister", "javelin_missile_flight", "kornet_missile_flight",
            "shell_30mm"]


@builder
def build_citadel():
    """ZAK-30 Tsitadel, built from primitives in gen_citadel.py"""
    import gen_citadel
    return gen_citadel.build(ASSETS, MODID)


@builder
def build_icons():
    """inventory icons rendered from the meshes; the drones' items were 3D box models, now flat icons like the rest"""
    fpv = _parts("fpv_drone")
    for h in ((2, 8.25, 2), (14, 8.25, 2), (2, 8.25, 14), (14, 8.25, 14)):
        fpv += _parts("propeller", _at(h, 0.5))
    _icon(fpv, "fpv_drone", 40, 30)
    mav = _parts("mavic3")
    for h in ((2.63, 7.69, 13.33), (13.37, 7.69, 13.33), (2.17, 7.69, 2.87), (13.83, 7.69, 2.87)):
        mav += _parts("propeller_mavic", _at(h, 0.32))
    _icon(mav, "mavic3", 40, 30)
    rx90 = mk.rot("x", 90)[:3, :3]
    for n in ("shahed136", "geran2"):
        _icon(_parts(n) + _parts("propeller_shahed", _at((8, 8.25, -7.7), 0.55, rx90)), n, 145, 35)
    for n in ("shahed238", "geran5"):
        _icon(_parts(n), n, 145, 35)
    # Kornet assembled: tripod on the ground, yoke at 0.78, sight and tube at 0.78 + 0.63 (blocks)
    def part(off):
        return lambda Q: Q / 16 - np.array([0.5, 0, 0.5]) + np.array(off)
    kor = _parts("kornet_tripod", part((0, 0, 0))) + _parts("kornet_head", part((0, 0.78, 0)))
    pitch_up = mk.rot("x", -8)[:3, :3]
    for n in ("kornet_sight", "kornet_tube"):
        kor += _parts(n, lambda Q: (Q / 16 - np.array([0.5, 0, 0.5])) @ pitch_up.T + np.array([0, 1.41, 0]))
    _icon(kor, "kornet", 30, 15)
    # truck: hull and wheels back to blocks
    import re
    src = open(os.path.join(ROOT, "src", "main", "java", "com", "qwarwin", "fpvdrones", "entity", "LaunchTruckEntity.java")).read()
    wheels = [tuple(map(float, w)) for w in re.findall(r"\{(-?[0-9.]+), (-?[0-9.]+)\}", src.split("WHEELS")[1].split(";")[0])]
    radius = float(re.search(r"WHEEL_RADIUS = ([0-9.]+)", src).group(1))
    tr = _parts("truck", lambda Q: (Q / 16 - 0.5) * 3 + np.array([0, 1.5, 0]))
    for wx, wz in wheels:
        tr += _parts("truck_wheel", lambda Q, wx=wx, wz=wz: (Q / 16 - 0.5) * 3 + np.array([wx, radius, wz]))
    _icon(tr, "launch_truck", 40, 25)
    _icon(_parts("goggles"), "fpv_goggles", 25, 15)
    # the Tsitadel stood up: base, turret on the ring (9.2 px), gun on its trunnion, a little elevated
    up = mk.rot("x", -20)[:3, :3]
    cit = _parts("adgun_base") + _parts("adgun_turret", lambda Q: Q + np.array([0, 9.2, 0]))
    cit += _parts("adgun_gun", lambda Q: (Q - np.array([8, 0, 8])) @ up.T + np.array([7.6, 9.2 + 3.36, 9.2]))
    _icon(cit, "air_defense_gun", 35, 20)
    for n in ("pg7vs", "og7"):
        _icon(_parts(n), n, 135, 35)
    _icon(_parts("kornet_missile_flight", bare=True), "kornet_missile", 135, 35)
    _icon(_parts("javelin_missile_flight", bare=True), "javelin_missile", 135, 35)
    _icon(_parts("vog17m"), "vog17m", 30, 20)
    _icon(_parts("shell_30mm"), "ammo_30mm", 30, 20)
    _icon(_parts("rgd5"), "rgd5", 30, 15)
    _icon(_parts("explosive_canister"), "explosive_canister", 30, 20)
    print("  icons")
    return []


def main(argv):
    names = [a for a in argv if not a.startswith("--")] or list(BUILDERS)
    done = []
    for n in names:
        done += BUILDERS[n]() or []
    if done:
        preview(done, os.path.join(PREVIEW, "meshes_" + "_".join(names)[:60] + ".png"))


if __name__ == "__main__":
    main(sys.argv[1:])
