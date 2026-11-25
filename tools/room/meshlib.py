import math, struct, zlib
import numpy as np

TILE = 16.0
NEUTRAL = 170.0 / 255.0   # vertex colour that equals brightness 1.0 in the shader
RES = 16                  # occupancy cells per block
GX0, GY0, GZ0 = -1.0, -0.5, -1.0
GNX, GNY, GNZ = 14 * RES, 7 * RES, 14 * RES


def hash2(a, b, c=0, seed=0):
    h = (a * 73856093) ^ (b * 19349663) ^ (c * 83492791) ^ (seed * 2654435761)
    h &= 0xFFFFFFFF
    h ^= h >> 13
    h = (h * 1274126177) & 0xFFFFFFFF
    h ^= h >> 16
    return h / 4294967295.0


class Box:
    pass


FACE_NAMES = ['px', 'nx', 'py', 'ny', 'pz', 'nz']


class Scene:
    def __init__(self, atlas_names, aw=256, ah=256):
        self.names = atlas_names
        self.aw, self.ah = aw, ah
        self.boxes = []
        self.occ = np.zeros((GNX, GNY, GNZ), bool)
        self.quads = {}     # group -> list of quads
        self.lights = []
        self.halos = []
        self.points = {}

    # --------------------------------------------------------- occupancy
    def _cells(self, mn, mx):
        i0 = int(math.floor((mn[0] - GX0) * RES + 1e-6))
        j0 = int(math.floor((mn[1] - GY0) * RES + 1e-6))
        k0 = int(math.floor((mn[2] - GZ0) * RES + 1e-6))
        i1 = int(math.ceil((mx[0] - GX0) * RES - 1e-6))
        j1 = int(math.ceil((mx[1] - GY0) * RES - 1e-6))
        k1 = int(math.ceil((mx[2] - GZ0) * RES - 1e-6))
        i0, j0, k0 = max(i0, 0), max(j0, 0), max(k0, 0)
        i1, j1, k1 = min(i1, GNX), min(j1, GNY), min(k1, GNZ)
        return i0, j0, k0, i1, j1, k1

    def mark(self, mn, mx):
        i0, j0, k0, i1, j1, k1 = self._cells(mn, mx)
        if i1 > i0 and j1 > j0 and k1 > k0:
            self.occ[i0:i1, j0:j1, k0:k1] = True

    # --------------------------------------------------------- adding boxes
    def box(self, mn, mx, tiles, group='static', glow=0.0, tint=(1, 1, 1), occ=True, ao=True,
            cull=True, var=0.0, skip=(), uvmc=None, key=0, stretch=False):
        b = Box()
        b.mn = tuple(float(v) for v in mn)
        b.mx = tuple(float(v) for v in mx)
        b.tiles = tiles
        b.group, b.glow, b.tint = group, glow, tint
        b.occ, b.ao, b.cull, b.var, b.skip, b.uvmc, b.key = occ, ao, cull, var, set(skip), uvmc, key
        b.stretch = stretch
        self.boxes.append(b)
        if occ and group_is_static(group):
            self.mark(b.mn, b.mx)
        return b

    # --------------------------------------------------------- tile lookup
    def tile_px(self, name):
        if name == '__fox__':
            return self.names['__fox__']
        c, r = self.names[name]
        return c * TILE, r * TILE

    def pick(self, spec, face, ci, cj, key):
        if isinstance(spec, dict):
            s = spec.get(face)
            if s is None:
                s = spec.get('side') if face in ('px', 'nx', 'pz', 'nz') else None
            if s is None and face == 'py':
                s = spec.get('top')
            if s is None and face == 'ny':
                s = spec.get('bottom')
            if s is None:
                s = spec.get('all')
            spec = s
        if isinstance(spec, (list, tuple)):
            idx = int(hash2(ci, cj, FACE_NAMES.index(face), key) * len(spec)) % len(spec)
            return spec[idx]
        return spec

    # --------------------------------------------------------- face generation
    AXES = {
        # face: (normal axis, sign, u axis, u sign, v axis, v sign)  v = "up" on texture
        'px': (0, +1, 2, -1, 1, +1),
        'nx': (0, -1, 2, +1, 1, +1),
        'pz': (2, +1, 0, +1, 1, +1),
        'nz': (2, -1, 0, -1, 1, +1),
        'py': (1, +1, 0, +1, 2, -1),
        'ny': (1, -1, 0, +1, 2, +1),
    }

    def _outside_free(self, b, face, a0, a1, b0, b1):
        """True if some cell just outside the face region is NOT occupied (i.e. face may be visible)."""
        na, sg, ua, us, va, vs = self.AXES[face]
        pos = b.mx[na] if sg > 0 else b.mn[na]
        val = (pos - [GX0, GY0, GZ0][na]) * RES
        oc = int(math.ceil(val - 1e-6)) if sg > 0 else int(math.floor(val + 1e-6)) - 1
        gdims = [GNX, GNY, GNZ]
        if oc < 0 or oc >= gdims[na]:
            return True
        org = [GX0, GY0, GZ0]
        ia0 = int(math.floor((a0 - org[ua]) * RES + 1e-6))
        ia1 = int(math.ceil((a1 - org[ua]) * RES - 1e-6))
        ib0 = int(math.floor((b0 - org[va]) * RES + 1e-6))
        ib1 = int(math.ceil((b1 - org[va]) * RES - 1e-6))
        ia0, ib0 = max(ia0, 0), max(ib0, 0)
        ia1, ib1 = min(ia1, gdims[ua]), min(ib1, gdims[va])
        if ia1 <= ia0 or ib1 <= ib0:
            return True
        sl = [None, None, None]
        sl[na] = oc
        sl[ua] = slice(ia0, ia1)
        sl[va] = slice(ib0, ib1)
        region = self.occ[tuple(sl)]
        return not bool(region.all())

    def gen_faces(self):
        for b in self.boxes:
            if b.uvmc is not None:
                self._gen_mc(b)
                continue
            for face in FACE_NAMES:
                if face in b.skip:
                    continue
                self._gen_face(b, face)

    def _gen_face(self, b, face):
        na, sg, ua, us, va, vs = self.AXES[face]
        pos = b.mx[na] if sg > 0 else b.mn[na]
        u0, u1 = b.mn[ua], b.mx[ua]
        v0, v1 = b.mn[va], b.mx[va]
        # split on integer lines
        if b.stretch:
            us_l = [(u0, u1)]
            vs_l = [(v0, v1)]
        else:
            us_l = split_int(u0, u1)
            vs_l = split_int(v0, v1)
        cull_ok = b.cull and b.occ and group_is_static(b.group)
        for (a0, a1) in us_l:
            for (c0, c1) in vs_l:
                if cull_ok and not self._outside_free(b, face, a0, a1, c0, c1):
                    continue
                ci, cj = int(math.floor((a0 + a1) * 0.5)), int(math.floor((c0 + c1) * 0.5))
                name = self.pick(b.tiles, face, ci, cj, b.key)
                if name is None:
                    continue
                tx, ty = self.tile_px(name)
                # corners in (u, v) order: (a0,c0) (a1,c0) (a1,c1) (a0,c1)
                corners = [(a0, c0), (a1, c0), (a1, c1), (a0, c1)]
                verts = []
                for (cu, cv) in corners:
                    p = [0.0, 0.0, 0.0]
                    p[na] = pos
                    p[ua] = cu
                    p[va] = cv
                    if b.stretch:
                        fu = (cu - u0) / (u1 - u0) if us > 0 else (u1 - cu) / (u1 - u0)
                        fv = (cv - v0) / (v1 - v0) if vs > 0 else (v1 - cv) / (v1 - v0)
                    else:
                        fu = (cu - math.floor((a0 + a1) * 0.5)) if us > 0 else (math.floor((a0 + a1) * 0.5) + 1 - cu)
                        fv = (cv - math.floor((c0 + c1) * 0.5)) if vs > 0 else (math.floor((c0 + c1) * 0.5) + 1 - cv)
                    # fu increases along +texture-u, fv along texture-up
                    pu = tx + 0.03 + fu * (TILE - 0.06)
                    pv = ty + 0.03 + (1.0 - fv) * (TILE - 0.06)
                    verts.append((p, pu, pv))
                n = [0.0, 0.0, 0.0]
                n[na] = float(sg)
                self._add_quad(b, verts, n, face, ci, cj)

    def _gen_mc(self, b):
        ox, oy, w, h, d = b.uvmc   # origin px in atlas, dims in tex px
        x0, y0, z0 = b.mn
        x1, y1, z1 = b.mx
        fx, fy = self.tile_px('__fox__') if False else (0, 0)
        # explicit per-face unwrap (forward +x, up +y, right +z)
        def mapuv(face, p):
            x, y, z = p
            fxr = (x - x0) / (x1 - x0)
            fyr = (y - y0) / (y1 - y0)
            fzr = (z - z0) / (z1 - z0)
            if face == 'px':
                return ox + d + (1 - fzr) * w, oy + d + (1 - fyr) * h
            if face == 'nx':
                return ox + d + w + d + fzr * w, oy + d + (1 - fyr) * h
            if face == 'pz':
                return ox + fxr * d, oy + d + (1 - fyr) * h
            if face == 'nz':
                return ox + d + w + (1 - fxr) * d, oy + d + (1 - fyr) * h
            if face == 'py':
                return ox + d + (1 - fzr) * w, oy + fxr * d
            return ox + d + w + (1 - fzr) * w, oy + (1 - fxr) * d

        for face in FACE_NAMES:
            if face in b.skip:
                continue
            na, sg, ua, us, va, vs = self.AXES[face]
            pos = b.mx[na] if sg > 0 else b.mn[na]
            u0, u1 = b.mn[ua], b.mx[ua]
            v0, v1 = b.mn[va], b.mx[va]
            corners = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            verts = []
            for (cu, cv) in corners:
                p = [0.0, 0.0, 0.0]
                p[na] = pos
                p[ua] = cu
                p[va] = cv
                pu, pv = mapuv(face, p)
                # inset towards rect centre by a hair
                verts.append((p, pu, pv))
            # inset: pull uv slightly toward centre of face rect
            cu_m = sum(v[1] for v in verts) / 4
            cv_m = sum(v[2] for v in verts) / 4
            verts = [(p, pu + (0.02 if pu < cu_m else -0.02), pv + (0.02 if pv < cv_m else -0.02)) for (p, pu, pv) in verts]
            n = [0.0, 0.0, 0.0]
            n[na] = float(sg)
            self._add_quad(b, verts, n, face, 0, 0)

    def _add_quad(self, b, verts, n, face, ci, cj):
        shade = 1.0
        if b.var > 0:
            shade = 1.0 + (hash2(ci, cj, FACE_NAMES.index(face), b.key + 7) - 0.5) * 2 * b.var
        q = dict(v=verts, n=n, glow=b.glow, tint=b.tint, shade=shade, ao=b.ao and group_is_static(b.group) and b.glow < 0.5)
        self.quads.setdefault(b.group, []).append(q)

    # --------------------------------------------------------- AO
    def compute_ao(self, q):
        """per-vertex ambient occlusion factors for a quad"""
        out = []
        n = np.array(q['n'])
        dirs = AO_DIRS
        if not hasattr(self, '_aoc'):
            self._aoc = {}
        for (p, _, _) in q['v']:
            ck = (round(p[0] * 64), round(p[1] * 64), round(p[2] * 64), q['n'][0], q['n'][1], q['n'][2])
            if ck in self._aoc:
                out.append(self._aoc[ck])
                continue
            p = np.array(p)
            ok = dirs @ n > 0.25
            dd = dirs[ok]
            wsum = 0.0
            osum = 0.0
            for r, wgt in ((0.10, 1.0), (0.22, 0.8), (0.40, 0.55), (0.65, 0.35)):
                pts = p + n * 0.02 + dd * r
                ii = np.floor((pts[:, 0] - GX0) * RES).astype(int)
                jj = np.floor((pts[:, 1] - GY0) * RES).astype(int)
                kk = np.floor((pts[:, 2] - GZ0) * RES).astype(int)
                valid = (ii >= 0) & (ii < GNX) & (jj >= 0) & (jj < GNY) & (kk >= 0) & (kk < GNZ)
                o = np.zeros(len(pts), bool)
                o[valid] = self.occ[ii[valid], jj[valid], kk[valid]]
                osum += wgt * o.mean()
                wsum += wgt
            occl = osum / wsum
            self._aoc[ck] = occl
            out.append(occl)
        return out

    def finish(self, ao_strength=1.15, ao_floor=0.30):
        self.gen_faces()
        # AO for static groups (batched)
        for gname, qs in self.quads.items():
            for q in qs:
                if q['ao']:
                    occl = self.compute_ao(q)
                    q['aov'] = [max(ao_floor, 1.0 - ao_strength * o) for o in occl]
                else:
                    q['aov'] = [1.0] * 4

    # --------------------------------------------------------- export
    def group_arrays(self, gname):
        qs = self.quads.get(gname, [])
        nq = len(qs)
        pos = np.zeros((nq * 4, 3), np.float32)
        nrm = np.zeros((nq * 4, 3), np.float32)
        uv = np.zeros((nq * 4, 2), np.float32)
        col = np.zeros((nq * 4, 4), np.float32)
        idx = np.zeros(nq * 6, np.uint32)
        for i, q in enumerate(qs):
            for k, (p, pu, pv) in enumerate(q['v']):
                pos[i * 4 + k] = p
                nrm[i * 4 + k] = q['n']
                uv[i * 4 + k] = (pu / self.aw, pv / self.ah)
                a = q['aov'][k]
                rgb = np.array(q['tint']) * NEUTRAL * a * q['shade']
                col[i * 4 + k, :3] = np.clip(rgb, 0, 1)
                col[i * 4 + k, 3] = q['glow']
            base = i * 4
            idx[i * 6:(i + 1) * 6] = (base, base + 1, base + 2, base, base + 2, base + 3)
        return pos, nrm, uv, col, idx


def export_static(scene, max_quads=15000):
    qs = scene.quads.get('staticglass', []) + scene.quads.get('static', [])
    chunks = []
    for i in range(0, len(qs), max_quads):
        chunks.append(qs[i:i + max_quads])
    out = []
    for ci, chunk in enumerate(chunks):
        scene.quads['__tmp__'] = chunk
        arrs = scene.group_arrays('__tmp__')
        out.append(('static' if ci == 0 else 'static%d' % ci,) + arrs)
    scene.quads.pop('__tmp__', None)
    return out


def group_is_static(g):
    return g.startswith('static')


def split_int(a, b):
    """split [a,b] on integer boundaries"""
    out = []
    cur = a
    eps = 1e-6
    while cur < b - eps:
        nxt = min(math.floor(cur + eps) + 1, b)
        out.append((cur, nxt))
        cur = nxt
    return out


def _fib_dirs(n=40):
    pts = []
    ga = math.pi * (3 - math.sqrt(5))
    for i in range(n):
        y = 1 - 2 * (i + 0.5) / n
        r = math.sqrt(1 - y * y)
        t = ga * i
        pts.append((math.cos(t) * r, y, math.sin(t) * r))
    return np.array(pts)


AO_DIRS = _fib_dirs(40)


# ------------------------------------------------------------ binary export
def pack_group(pos, nrm, uv, col, idx):
    nv = len(pos)
    buf = bytearray(nv * 24)
    p32 = pos.astype('<f4')
    n8 = np.clip(np.round(nrm * 127), -127, 127).astype(np.int8)
    uvs = np.clip(np.round(uv * 65535), 0, 65535).astype('<u2')
    c8 = np.clip(np.round(col * 255), 0, 255).astype(np.uint8)
    arr = np.zeros((nv, 24), np.uint8)
    arr[:, 0:12] = np.frombuffer(p32.tobytes(), np.uint8).reshape(nv, 12)
    arr[:, 12:15] = n8.view(np.uint8)
    arr[:, 16:20] = np.frombuffer(uvs.tobytes(), np.uint8).reshape(nv, 4)
    arr[:, 20:24] = c8
    return arr.tobytes(), idx.astype('<u2').tobytes()


def write_room(path, groups, lights, halos, points):
    """groups: list of (name, pos, nrm, uv, col, idx)"""
    head = bytearray(b'RMS1')
    head += struct.pack('<4i', len(groups), len(lights), len(halos), len(points))
    blobs = []
    for (name, pos, nrm, uv, col, idx) in groups:
        vb, ib = pack_group(pos, nrm, uv, col, idx)
        nm = name.encode('ascii')[:15].ljust(16, b'\0')
        head += nm + struct.pack('<2i', len(pos), len(idx))
        blobs.append(vb + ib)
    for l in lights:
        head += struct.pack('<8f', *l)
    for h in halos:
        head += struct.pack('<7f', *h)
    for name, p in points.items():
        nm = name.encode('ascii')[:15].ljust(16, b'\0')
        head += nm + struct.pack('<3f', *p)
    data = bytes(head) + b''.join(blobs)
    open(path, 'wb').write(data)
    return len(data)
