import struct, math
import numpy as np
from PIL import Image

VB = 24


# ---------------------------------------------------------------- parsing
class Group:
    def __init__(self, name, pos, nrm, uv, col, idx):
        self.name, self.pos, self.nrm, self.uv, self.col, self.idx = name, pos, nrm, uv, col, idx


class Room:
    def __init__(self):
        self.groups = {}
        self.order = []
        self.lights = []
        self.halos = []
        self.points = {}


def parse_room(path):
    raw = open(path, 'rb').read()
    assert raw[:4] == b'RMS1'
    gc, lc, hc, pc = struct.unpack_from('<4i', raw, 4)
    off = 20
    heads = []

    def rn(o):
        return raw[o:o + 16].split(b'\0')[0].decode()

    for _ in range(gc):
        n = rn(off)
        nv, ni = struct.unpack_from('<2i', raw, off + 16)
        off += 24
        heads.append((n, nv, ni))
    R = Room()
    for _ in range(lc):
        R.lights.append(struct.unpack_from('<8f', raw, off))
        off += 32
    for _ in range(hc):
        R.halos.append(struct.unpack_from('<7f', raw, off))
        off += 28
    for _ in range(pc):
        n = rn(off)
        R.points[n] = struct.unpack_from('<3f', raw, off + 16)
        off += 28
    for n, nv, ni in heads:
        vb = np.frombuffer(raw, dtype=np.uint8, count=nv * VB, offset=off).reshape(nv, VB)
        off += nv * VB
        idx = np.frombuffer(raw, dtype='<u2', count=ni, offset=off).astype(np.int64)
        off += ni * 2
        pos = np.frombuffer(vb[:, 0:12].tobytes(), dtype='<f4').reshape(nv, 3).astype(np.float64)
        nrm = vb[:, 12:15].copy().view(np.int8).astype(np.float64) / 127.0
        uvv = np.frombuffer(vb[:, 16:20].tobytes(), dtype='<u2').reshape(nv, 2).astype(np.float64) / 65535.0
        col = vb[:, 20:24].astype(np.float64) / 255.0
        R.groups[n] = Group(n, pos, nrm, uvv, col, idx)
        R.order.append(n)
    return R


# ---------------------------------------------------------------- math
def perspective(fovy, aspect, n, f):
    t = 1.0 / math.tan(math.radians(fovy) / 2)
    M = np.zeros((4, 4))
    M[0, 0] = t / aspect
    M[1, 1] = t
    M[2, 2] = (f + n) / (n - f)
    M[2, 3] = 2 * f * n / (n - f)
    M[3, 2] = -1
    return M


def lookat(eye, tgt, up=(0, 1, 0)):
    eye = np.array(eye, float)
    tgt = np.array(tgt, float)
    f = tgt - eye
    f /= np.linalg.norm(f)
    s = np.cross(f, np.array(up, float))
    s /= np.linalg.norm(s)
    u = np.cross(s, f)
    M = np.eye(4)
    M[0, :3] = s
    M[1, :3] = u
    M[2, :3] = -f
    M[:3, 3] = -M[:3, :3] @ eye
    return M


def rot_about(p, angle_deg, axis):
    a = math.radians(angle_deg)
    axis = np.array(axis, float)
    axis /= np.linalg.norm(axis)
    x, y, z = axis
    c, s = math.cos(a), math.sin(a)
    R = np.eye(4)
    R[:3, :3] = [
        [c + x * x * (1 - c), x * y * (1 - c) - z * s, x * z * (1 - c) + y * s],
        [y * x * (1 - c) + z * s, c + y * y * (1 - c), y * z * (1 - c) - x * s],
        [z * x * (1 - c) - y * s, z * y * (1 - c) + x * s, c + z * z * (1 - c)]]
    T = np.eye(4)
    T[:3, 3] = p
    Ti = np.eye(4)
    Ti[:3, 3] = -np.array(p)
    return T @ R @ Ti


# ---------------------------------------------------------------- rasterizer
class Frame:
    def __init__(self, W, H):
        self.W, self.H = W, H
        self.z = np.full((H, W), np.inf)
        # attribute buffers
        self.pos = np.zeros((H, W, 3))
        self.nrm = np.zeros((H, W, 3))
        self.uv = np.zeros((H, W, 2))
        self.col = np.zeros((H, W, 4))
        self.tint = np.zeros((H, W))
        self.have = np.zeros((H, W), bool)


def raster_group(fr, g, model, vp, atlas, tint, mode):
    """mode 'opaque' writes attribute buffers (alpha-tested), 'glass' returns list of fragments"""
    W, H = fr.W, fr.H
    aw, ah = atlas.shape[1], atlas.shape[0]
    P = np.c_[g.pos, np.ones(len(g.pos))]
    wp = (model @ P.T).T[:, :3]
    nw = (model[:3, :3] @ g.nrm.T).T
    clip = (vp @ np.c_[wp, np.ones(len(wp))].T).T
    glass = []
    tris = g.idx.reshape(-1, 3)
    for tri in tris:
        a, b, c = tri
        cw = clip[[a, b, c], 3]
        if np.any(cw <= 0.05):
            continue
        sx = (clip[[a, b, c], 0] / cw * 0.5 + 0.5) * W
        sy = (1 - (clip[[a, b, c], 1] / cw * 0.5 + 0.5)) * H
        minx = max(int(math.floor(sx.min())), 0)
        maxx = min(int(math.ceil(sx.max())), W - 1)
        miny = max(int(math.floor(sy.min())), 0)
        maxy = min(int(math.ceil(sy.max())), H - 1)
        if minx > maxx or miny > maxy:
            continue
        # backface not culled (renderer disables culling)
        x0, y0 = sx[0], sy[0]
        d = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2])
        if abs(d) < 1e-9:
            continue
        xs = np.arange(minx, maxx + 1) + 0.5
        ys = np.arange(miny, maxy + 1) + 0.5
        X, Y = np.meshgrid(xs, ys)
        l0 = ((sy[1] - sy[2]) * (X - sx[2]) + (sx[2] - sx[1]) * (Y - sy[2])) / d
        l1 = ((sy[2] - sy[0]) * (X - sx[2]) + (sx[0] - sx[2]) * (Y - sy[2])) / d
        l2 = 1 - l0 - l1
        inside = (l0 >= -1e-9) & (l1 >= -1e-9) & (l2 >= -1e-9)
        if not inside.any():
            continue
        iw = 1.0 / cw
        denom = l0 * iw[0] + l1 * iw[1] + l2 * iw[2]
        p0 = l0 * iw[0] / denom
        p1 = l1 * iw[1] / denom
        p2 = l2 * iw[2] / denom
        zc = (l0 * clip[a, 2] / cw[0] + l1 * clip[b, 2] / cw[1] + l2 * clip[c, 2] / cw[2])
        uv = g.uv[a] * p0[..., None] + g.uv[b] * p1[..., None] + g.uv[c] * p2[..., None]
        px = np.clip((uv[..., 0] * aw).astype(int), 0, aw - 1)
        py = np.clip((uv[..., 1] * ah).astype(int), 0, ah - 1)
        texa = atlas[py, px, 3]
        if mode == 'opaque':
            ok = inside & (texa >= 0.9)
        else:
            ok = inside & (texa < 0.9) & (texa >= 0.02)
        zbuf = fr.z[miny:maxy + 1, minx:maxx + 1]
        ok &= (zc < zbuf)
        if not ok.any():
            continue
        if mode == 'opaque':
            zbuf[ok] = zc[ok]
            sl = (slice(miny, maxy + 1), slice(minx, maxx + 1))
            posv = wp[a] * p0[..., None] + wp[b] * p1[..., None] + wp[c] * p2[..., None]
            nv = nw[a] * p0[..., None] + nw[b] * p1[..., None] + nw[c] * p2[..., None]
            cv = g.col[a] * p0[..., None] + g.col[b] * p1[..., None] + g.col[c] * p2[..., None]
            fr.pos[sl][ok] = posv[ok]
            fr.nrm[sl][ok] = nv[ok]
            fr.uv[sl][ok] = uv[ok]
            fr.col[sl][ok] = cv[ok]
            fr.tint[sl][ok] = tint
            fr.have[sl][ok] = True
        else:
            posv = wp[a] * p0[..., None] + wp[b] * p1[..., None] + wp[c] * p2[..., None]
            nv = nw[a] * p0[..., None] + nw[b] * p1[..., None] + nw[c] * p2[..., None]
            cv = g.col[a] * p0[..., None] + g.col[b] * p1[..., None] + g.col[c] * p2[..., None]
            ys_i, xs_i = np.nonzero(ok)
            for yy, xx in zip(ys_i, xs_i):
                glass.append((miny + yy, minx + xx, posv[yy, xx], nv[yy, xx], uv[yy, xx], cv[yy, xx], zc[yy, xx]))
    return glass


KFOG = (0.011, 0.013)


def scat(E, P, Lp):
    d = P - E
    ln = np.maximum(np.linalg.norm(d, axis=-1), 1e-3)
    dirv = d / ln[..., None]
    el = Lp - E
    tl = (dirv * el).sum(-1)
    h = np.sqrt(np.maximum((el * el).sum() - tl * tl, 0.04))
    return (np.arctan((ln - tl) / h) - np.arctan(-tl / h)) / h


def shade(pos, nrm, uv, col, tint, atlas, lights, flick_t, ambient=(0.075, 0.062, 0.10), eye=None, fog=0.0):
    aw, ah = atlas.shape[1], atlas.shape[0]
    px = np.clip((uv[..., 0] * aw).astype(int), 0, aw - 1)
    py = np.clip((uv[..., 1] * ah).astype(int), 0, ah - 1)
    t = atlas[py, px]
    base = t[..., :3] * col[..., :3] * 1.5 * np.asarray(tint)[..., None]
    n = nrm / np.maximum(np.linalg.norm(nrm, axis=-1, keepdims=True), 1e-6)
    moon_dir = np.array([-0.94, 0.31, 0.10])
    moon_col = np.array([0.10, 0.14, 0.30])
    L = np.array(ambient) + moon_col * np.maximum((n * moon_dir).sum(-1), 0)[..., None]
    for i, l in enumerate(lights[:8]):
        lp = np.array(l[0:3])
        lc = np.array(l[3:6])
        rad = l[6]
        fl = l[7]
        f = 1 + fl * (0.6 * math.sin(flick_t * (7 + i * 1.3) + i) + 0.4 * math.sin(flick_t * (13 + i * 2) + i * 2))
        d = lp - pos
        dist = np.linalg.norm(d, axis=-1)
        att = np.clip(1 - dist / rad, 0, 1) ** 2
        nd = np.clip((n * (d / np.maximum(dist, 1e-3)[..., None])).sum(-1), 0, 1) * 0.75 + 0.25
        L = L + (lc * f) * (att * nd)[..., None]
    c = base * L
    c = c * (1 - col[..., 3:4]) + base * 1.7 * col[..., 3:4]
    if fog > 0 and eye is not None:
        for i in (0, 1):
            l = lights[i]
            fl = 1 + l[7] * (0.6 * math.sin(flick_t * (7 + i * 1.3) + i) + 0.4 * math.sin(flick_t * (13 + i * 2) + i * 2))
            c = c + fog * (np.array(l[3:6]) * fl)[None if c.ndim == 1 else None] * (scat(np.array(eye), pos, np.array(l[0:3])) * KFOG[i])[..., None]
    c = 1 - np.exp(-c * 1.55)
    return c


def fx_flame_pixels(vp, W, H, center, w, h, ut, axis, zbuf):
    """returns (mask, rgb) of a flame quad (mode 0 of FX shader)"""
    c = np.array(center)
    r = np.array([w, 0, 0]) if axis == 0 else np.array([0, 0, w])
    u = np.array([0, h, 0])
    corners = [c - r - u, c + r - u, c - r + u, c + r + u]
    cl = [(vp @ np.r_[p, 1]) for p in corners]
    return cl


def draw_quad_fx(img, zbuf, vp, center, right, up, fragfn, depth_test=True, blend='none'):
    W, H = img.shape[1], img.shape[0]
    corners_uv = [(-1, -1), (1, -1), (-1, 1), (1, 1)]
    cl = []
    for (a, b) in corners_uv:
        p = np.array(center) + np.array(right) * a + np.array(up) * b
        cl.append(vp @ np.r_[p, 1])
    cl = np.array(cl)
    tris = [(0, 1, 2), (2, 1, 3)]
    for (a, b, c) in tris:
        cw = cl[[a, b, c], 3]
        if np.any(cw <= 0.05):
            continue
        sx = (cl[[a, b, c], 0] / cw * 0.5 + 0.5) * W
        sy = (1 - (cl[[a, b, c], 1] / cw * 0.5 + 0.5)) * H
        minx = max(int(math.floor(sx.min())), 0)
        maxx = min(int(math.ceil(sx.max())), W - 1)
        miny = max(int(math.floor(sy.min())), 0)
        maxy = min(int(math.ceil(sy.max())), H - 1)
        if minx > maxx or miny > maxy:
            continue
        d = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2])
        if abs(d) < 1e-9:
            continue
        xs = np.arange(minx, maxx + 1) + 0.5
        ys = np.arange(miny, maxy + 1) + 0.5
        X, Y = np.meshgrid(xs, ys)
        l0 = ((sy[1] - sy[2]) * (X - sx[2]) + (sx[2] - sx[1]) * (Y - sy[2])) / d
        l1 = ((sy[2] - sy[0]) * (X - sx[2]) + (sx[0] - sx[2]) * (Y - sy[2])) / d
        l2 = 1 - l0 - l1
        inside = (l0 >= -1e-9) & (l1 >= -1e-9) & (l2 >= -1e-9)
        iw = 1.0 / cw
        denom = l0 * iw[0] + l1 * iw[1] + l2 * iw[2]
        p0 = l0 * iw[0] / denom
        p1 = l1 * iw[1] / denom
        p2 = l2 * iw[2] / denom
        zc = (l0 * cl[a, 2] / cw[0] + l1 * cl[b, 2] / cw[1] + l2 * cl[c, 2] / cw[2])
        vx = np.array([corners_uv[a][0], corners_uv[b][0], corners_uv[c][0]])
        vy = np.array([corners_uv[a][1], corners_uv[b][1], corners_uv[c][1]])
        VX = vx[0] * p0 + vx[1] * p1 + vx[2] * p2
        VY = vy[0] * p0 + vy[1] * p1 + vy[2] * p2
        zb = zbuf[miny:maxy + 1, minx:maxx + 1]
        ok = inside
        if depth_test:
            ok = ok & (zc < zb)
        if not ok.any():
            continue
        mask, rgb, alpha = fragfn(VX, VY)
        ok = ok & mask
        sl = (slice(miny, maxy + 1), slice(minx, maxx + 1))
        dst = img[sl]
        if blend == 'none':
            dst[ok] = rgb[ok]
        elif blend == 'add':
            dst[ok] = np.clip(dst[ok] + rgb[ok], 0, 1)
        elif blend == 'alpha':
            dst[ok] = dst[ok] * (1 - alpha[ok][:, None]) + rgb[ok] * alpha[ok][:, None]


def hashf(x, y):
    v = np.sin(x * 12.9898 + y * 78.233) * 43758.5453
    return v - np.floor(v)


def flame_frag(time, tint):
    def f(VX, VY):
        qx = (VX * 0.5 + 0.5) * 10.0
        qy = (VY * 0.5 + 0.5) * 14.0
        gx = np.floor(qx)
        gy = np.floor(qy)
        k = math.floor(time * 8.0)
        hgt = gy / 14.0
        h = hashf(gx, gy - k)
        h2 = hashf(gx + 3.0, np.full_like(gx, k))
        v = (1 - hgt) * 1.35 - np.abs(gx - 4.5) / 5.0 * 1.1 + (h - 0.5) * 0.75 + (h2 - 0.5) * 0.15
        mask = v >= 0.2
        rgb = np.zeros(VX.shape + (3,))
        c1 = np.array([0.95, 0.22, 0.04])
        c2 = np.array([1.0, 0.55, 0.1])
        c3 = np.array([1.0, 0.92, 0.55])
        rgb[:] = c3
        rgb[v < 0.75] = c2
        rgb[v < 0.45] = c1
        rgb = rgb * tint
        return mask, rgb, np.ones(VX.shape)
    return f


def halo_frag(color):
    def f(VX, VY):
        r = np.sqrt(VX ** 2 + VY ** 2)
        a = np.clip(1 - r, 0, 1)
        a = a * a * color[3]
        rgb = np.stack([color[0] * a, color[1] * a, color[2] * a], -1)
        return np.ones(VX.shape, bool), rgb, a
    return f


def render(room, atlas_img, W=540, H=1170, t=0.0, eye=None, tgt=None, extra=None, anim=None,
           with_fx=True, glass=True, fov=None, fog=1.0, post=True):
    """anim: dict group -> (model 4x4, tint) overrides"""
    atlas = np.asarray(atlas_img.convert('RGBA'), dtype=np.float64) / 255.0
    aspect = W / H
    fov = fov or (54.0 if aspect > 1.2 else 72.0)
    if eye is None:
        eye = (11.3 + math.sin(t * 0.13) * 0.35, 1.6 + math.sin(t * 0.09) * 0.06, 11.3 + math.cos(t * 0.11) * 0.30)
    if tgt is None:
        tgt = (3.3 + math.sin(t * 0.07 + 1) * 0.25, 1.45 + math.sin(t * 0.05) * 0.05, 1.7)
    P = perspective(fov, aspect, 0.1, 80.0)
    V = lookat(eye, tgt)
    vp = P @ V
    fr = Frame(W, H)
    anim = anim or {}
    ident = np.eye(4)
    for name in room.order:
        g = room.groups[name]
        m, tint = anim.get(name, (ident, 1.0))
        raster_group(fr, g, m, vp, atlas, tint, 'opaque')
    c = shade(fr.pos, fr.nrm, fr.uv, fr.col, fr.tint, atlas, room.lights, t, eye=eye, fog=fog)
    img = np.zeros((H, W, 3))
    img[:] = (0.03, 0.02, 0.03)
    img[fr.have] = c[fr.have]
    if glass and 'static' in room.groups:
        frs = raster_group(fr, room.groups['static'], ident, vp, atlas, 1.0, 'glass')
        for (yy, xx, pos, nrm, uv, col, zc) in frs:
            px = min(int(uv[0] * atlas.shape[1]), atlas.shape[1] - 1)
            py = min(int(uv[1] * atlas.shape[0]), atlas.shape[0] - 1)
            ta = atlas[py, px, 3]
            cc = shade(pos[None], nrm[None], uv[None], col[None], np.array([1.0]), atlas, room.lights, t)[0]
            img[yy, xx] = img[yy, xx] * (1 - ta) + cc * ta
    if with_fx:
        fire = room.points.get('fire', (4.6, 0.55, 0.95))
        k = 0.93 + 0.07 * math.sin(t * 9)
        flames = [
            (t, (fire[0], fire[1] + 0.85, fire[2]), 0.9, 0.85 * k, 1.0),
            (t + 0.37, (fire[0] - 0.45, fire[1] + 0.55, fire[2] + 0.1), 0.5, 0.5 * k, 0.95),
            (t + 0.71, (fire[0] + 0.5, fire[1] + 0.5, fire[2] - 0.05), 0.45, 0.45 * k, 0.95)]
        for i in range(2):
            c0 = room.points.get('candle%d' % i)
            if c0 is not None:
                flames.append((t + i * 0.5, (c0[0], c0[1] + 0.1, c0[2]), 0.06, 0.13, 1.0))
        for (tm, cen, w, h, tint) in flames:
            for axis in (0, 1):
                right = (w, 0, 0) if axis == 0 else (0, 0, w)
                draw_quad_fx(img, fr.z, vp, cen, right, (0, h, 0), flame_frag(tm, tint), True, 'none')
        # halos (additive, no depth test)
        rx, ry, rz = V[0, 0], V[0, 1], V[0, 2]
        ux, uy, uz = V[1, 0], V[1, 1], V[1, 2]
        for i, h in enumerate(room.halos):
            s = h[6] * (0.93 + 0.07 * math.sin(t * (5 + i) + i))
            draw_quad_fx(img, fr.z, vp, (h[0], h[1], h[2]), (rx * s, ry * s, rz * s), (ux * s, uy * s, uz * s),
                         halo_frag((h[3], h[4], h[5], 0.5)), False, 'add')
    if post:
        img = postprocess(img, t)
    # vignette
    yy, xx = np.mgrid[0:H, 0:W]
    vx = (xx + 0.5) / W * 2 - 1
    vy = 1 - (yy + 0.5) / H * 2
    r = np.sqrt((vx * 0.9) ** 2 + (vy * 0.78) ** 2)
    a = np.clip((r - 0.6) / (1.5 - 0.6), 0, 1)
    a = a * a * (3 - 2 * a) * 0.62
    img = img * (1 - a[..., None])
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))


def _ss(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def postprocess(img, t, bloom_k=0.55):
    import cv2
    H, W = img.shape[:2]
    c = img.astype(np.float32)
    small = cv2.resize(c, (max(W // 4, 8), max(H // 4, 8)), interpolation=cv2.INTER_AREA)
    l = small.max(-1)
    k = _ss(0.62, 1.0, l)
    bright = small * k[..., None]
    b1 = cv2.GaussianBlur(bright, (0, 0), 2.0)
    b2 = cv2.GaussianBlur(b1, (0, 0), 4.8)
    bl = cv2.resize(b2, (W, H), interpolation=cv2.INTER_LINEAR)
    c = c + bl * bloom_k
    lum = (c * np.array([0.299, 0.587, 0.114])).sum(-1)
    w = _ss(0.15, 0.7, lum)[..., None]
    c = c * (np.array([0.94, 0.98, 1.08]) * (1 - w) + np.array([1.06, 1.0, 0.93]) * w)
    c = np.clip(c, 0, 1)
    c = c * 0.7 + (c * c * (3 - 2 * c)) * 0.3
    rng = np.random.RandomState(int(t * 24) % 1000)
    c = c + (rng.rand(H, W, 1) - 0.5) * 0.022
    return np.clip(c, 0, 1)
