"""Превращает minecraft_bee.glb (Baby Bee) в assets/bee/baby.bin: готовая сетка с цветами вершин.
Формат BBY1 (little endian): "BBY1", int частей; для каждой части: int вершин, int индексов,
float pivot[3]; затем для каждой части вершины (12 float: pos3 nrm3 uv2 color4) и индексы (ushort)."""
import json, struct, math, sys, os
import numpy as np

def load_glb(path):
    b = open(path, 'rb').read()
    off = 12; js = None; bin_ = None
    while off < len(b):
        cl, ct = struct.unpack_from('<I4s', b, off)
        d = b[off + 8: off + 8 + cl]
        if ct == b'JSON': js = json.loads(d)
        elif ct.startswith(b'BIN'): bin_ = d
        off += 8 + cl
    return js, bin_

def load_gltf(path):
    js = json.load(open(path))
    bin_ = open(os.path.join(os.path.dirname(path), js['buffers'][0]['uri']), 'rb').read()
    return js, bin_

def acc(js, bin_, i):
    a = js['accessors'][i]; bv = js['bufferViews'][a['bufferView']]
    n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[a['type']]
    dt = {5126: '<f4', 5125: '<u4', 5123: '<u2', 5121: 'u1'}[a['componentType']]
    off = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
    stride = bv.get('byteStride')
    if stride and stride != n * np.dtype(dt).itemsize:
        raw = np.frombuffer(bin_, dtype='u1', count=stride * a['count'], offset=off).reshape(a['count'], stride)
        return np.frombuffer(raw[:, :n * np.dtype(dt).itemsize].tobytes(), dtype=dt).reshape(a['count'], n)
    return np.frombuffer(bin_, dtype=dt, count=a['count'] * n, offset=off).reshape(a['count'], n)

def local(n):
    if 'matrix' in n:
        return np.array(n['matrix'], float).reshape(4, 4).T
    M = np.eye(4)
    t = n.get('translation', [0, 0, 0]); r = n.get('rotation', [0, 0, 0, 1]); s = n.get('scale', [1, 1, 1])
    x, y, z, w = r
    R = np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])
    M[:3, :3] = R * np.array(s)
    M[:3, 3] = t
    return M

def flatten(js, bin_):
    """список (name_of_material, verts(N,3) world, normals, indices, color) для всех мешей"""
    out = []
    def rec(i, P, path):
        n = js['nodes'][i]
        W = P @ local(n)
        if 'mesh' in n:
            for p in js['meshes'][n['mesh']]['primitives']:
                at = p['attributes']
                pos = acc(js, bin_, at['POSITION']).astype(float)
                nrm = acc(js, bin_, at['NORMAL']).astype(float) if 'NORMAL' in at else np.tile([0, 1, 0], (len(pos), 1)).astype(float)
                idx = acc(js, bin_, p['indices']).reshape(-1).astype(int)
                wp = (W @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
                nm = (np.linalg.inv(W[:3, :3]).T @ nrm.T).T
                nm /= np.maximum(np.linalg.norm(nm, axis=1, keepdims=True), 1e-9)
                mat = js['materials'][p['material']] if 'material' in p else {}
                f = mat.get('pbrMetallicRoughness', {}).get('baseColorFactor')
                out.append(dict(mat=mat.get('name', ''), pos=wp, nrm=nm, idx=idx, color=f, path=path + [n.get('name', '')]))
        for c in n.get('children', []):
            rec(c, W, path + [n.get('name', '')])
    for r in js['scenes'][js.get('scene', 0)]['nodes']:
        rec(r, np.eye(4), [])
    return out

if __name__ == '__main__':
    here = os.path.dirname(os.path.abspath(__file__))
    glb = sys.argv[1]
    outp = sys.argv[2]
    adult = sys.argv[3] if len(sys.argv) > 3 else None
    js, b = load_glb(glb)
    prims = flatten(js, b)
    wing = lambda p: any(x == 'ala' for x in p['path'])
    allp = np.concatenate([p['pos'] for p in prims])
    body = np.concatenate([p['pos'] for p in prims if not wing(p)])
    print('baby bbox', allp.min(0).round(3), allp.max(0).round(3), 'body', body.min(0).round(3), body.max(0).round(3))
    blk = [p for p in prims if p['mat'] == 'standardSurface4']
    print('black planes centroid', np.concatenate([p['pos'] for p in blk]).mean(0).round(3), 'body centroid', body.mean(0).round(3))
    if adult:
        ja, ba = load_gltf(adult)
        ap = flatten(ja, ba)
        aall = np.concatenate([p['pos'] for p in ap])
        print('adult bbox', aall.min(0).round(3), aall.max(0).round(3))
        eyes = [p for p in ap if p['mat'] in ('Material.004', 'Material.005')]
        print('adult eyes centroid', np.concatenate([p['pos'] for p in eyes]).mean(0).round(3), 'adult centroid', aall.mean(0).round(3))
        print('adult materials', sorted(set(p['mat'] for p in ap)))


def build(glb, outp):
    js, b = load_glb(glb)
    prims = flatten(js, b)
    is_wing = lambda p: 'ala' in p['path']
    body = np.concatenate([p['pos'] for p in prims if not is_wing(p)])
    c = (body.min(0) + body.max(0)) / 2
    k = 100.0 * 0.55          # метры -> единицы модели взрослой пчелы, малыш вдвое меньше
    def conv(P):
        Q = (P - c) * k
        Q[:, 0] *= -1         # у малыша перед в +Z, у взрослой в -Z
        Q[:, 2] *= -1
        return Q
    bc = conv(body)
    dy = -2.44 - bc[:, 1].min()   # лапы на той же высоте, что у взрослой (FOOT)
    def finish(P):
        Q = conv(P); Q[:, 1] += dy; return Q
    def col(p):
        f = p['color']
        if f is None or is_wing(p):
            return [1, 1, 1, 1]
        return [min(1, max(0, f[0])) ** (1 / 2.2), min(1, max(0, f[1])) ** (1 / 2.2), min(1, max(0, f[2])) ** (1 / 2.2), 1.0]
    groups = {0: [], -1: [], 1: []}
    for p in prims:
        P = finish(p['pos'])
        if not is_wing(p):
            groups[0].append((P, p['nrm'].copy(), p['idx'], col(p)))
            continue
        # крылья делим на левое и правое по треугольникам
        tris = p['idx'].reshape(-1, 3)
        cx = P[tris].mean(axis=1)[:, 0]
        for side, sel in ((-1, cx < 0), (1, cx >= 0)):
            tt = tris[sel]
            if len(tt) == 0:
                continue
            used = np.unique(tt)
            remap = {int(v): i for i, v in enumerate(used)}
            nidx = np.array([remap[int(v)] for v in tt.reshape(-1)])
            groups[side].append((P[used], p['nrm'][used].copy(), nidx, col(p)))
    def fix_normals(P, N):
        N = N.copy(); N[:, 0] *= -1; N[:, 2] *= -1; return N
    parts = []
    for side in (0, -1, 1):
        V = []; I = []; base = 0
        for (P, N, idx, cl) in groups[side]:
            N2 = fix_normals(P, N)
            for i in range(len(P)):
                V.append(list(P[i]) + list(N2[i]) + [0, 0] + cl)
            I.extend((idx + base).tolist()); base += len(P)
        V = np.array(V, '<f4'); I = np.array(I, '<u2')
        if side == 0:
            piv = [0, 0, 0]
        else:
            xs = V[:, 0]
            inner = xs[np.argmin(np.abs(xs))]
            piv = [float(inner), float(V[:, 1].mean()), float(V[:, 2].mean())]
        parts.append((side, V, I, piv))
        print('part', side, len(V), 'verts', len(I) // 3, 'tris', 'pivot', np.round(piv, 2))
    out = bytearray(b'BBY1') + struct.pack('<i', len(parts))
    for side, V, I, piv in parts:
        out += struct.pack('<iii3f', len(V), len(I), side, *piv)
    for side, V, I, piv in parts:
        out += V.tobytes() + I.tobytes()
    open(outp, 'wb').write(out)
    print('wrote', outp, len(out))
