import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter1d, gaussian_filter

AW = AH = 256
TS = 16


def T():
    return np.zeros((16, 16, 4), np.uint8)


def clampc(c):
    return np.clip(np.asarray(c, float), 0, 255)


def paint(a, mask, c):
    a[mask, :3] = clampc(c).astype(np.uint8)
    a[mask, 3] = 255


def fillrect(a, x0, y0, x1, y1, c):
    a[y0:y1, x0:x1, :3] = clampc(c).astype(np.uint8)
    a[y0:y1, x0:x1, 3] = 255


def shadef(a, k, mask=None):
    """multiply colour by factor array/scalar"""
    f = np.asarray(k, float)
    if f.ndim == 2:
        f = f[..., None]
    rgb = a[..., :3].astype(float) * f
    if mask is None:
        a[..., :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    else:
        a[mask, :3] = np.clip(rgb[mask], 0, 255).astype(np.uint8)


def noise_mul(a, amp, seed, sx=0.0, sy=0.0):
    r = np.random.RandomState(seed)
    n = r.uniform(-1, 1, (16, 16))
    if sx or sy:
        n = gaussian_filter(n, sigma=(sy, sx), mode='wrap')
        n = n / max(np.abs(n).max(), 1e-6)
    shadef(a, 1 + n * amp)


# ------------------------------------------------------------ wood
def planks(base, seed, rows=4, dark=0.62):
    r = np.random.RandomState(seed)
    a = T()
    rh = 16 // rows
    for i in range(rows):
        tone = 1 + r.uniform(-0.08, 0.08)
        fillrect(a, 0, i * rh, 16, (i + 1) * rh, np.array(base) * tone)
    noise_mul(a, 0.10, seed + 1, sx=2.5, sy=0.3)
    noise_mul(a, 0.05, seed + 2)
    for i in range(rows):
        y = i * rh
        shadef(a, dark, mask=_row(y))
        if y + 1 < 16:
            shadef(a, 1.10, mask=_row(y + 1))
        sx = r.randint(2, 14)
        m = np.zeros((16, 16), bool)
        m[y:y + rh, sx] = True
        shadef(a, dark + 0.05, mask=m)
        if r.rand() < 0.7:
            nx, ny = r.randint(1, 15), y + rh // 2
            m = np.zeros((16, 16), bool)
            m[ny, nx] = True
            shadef(a, 0.7, mask=m)
    return a


def _row(y):
    m = np.zeros((16, 16), bool)
    m[y, :] = True
    return m


def log_side(seed, horizontal=False, base=(76, 52, 30)):
    r = np.random.RandomState(seed)
    a = T()
    fillrect(a, 0, 0, 16, 16, base)
    col = r.uniform(0.78, 1.18, 16)
    col = gaussian_filter1d(col, 0.7, mode='wrap')
    shadef(a, np.tile(col, (16, 1)))
    noise_mul(a, 0.10, seed + 5, sx=0.4, sy=2.5)
    for x in r.choice(16, 3, replace=False):
        m = np.zeros((16, 16), bool)
        y0 = r.randint(0, 10)
        m[y0:y0 + r.randint(3, 7), x] = True
        shadef(a, 0.62, mask=m)
    if horizontal:
        a = np.ascontiguousarray(a.transpose(1, 0, 2))
    return a


def log_end(seed, base=(122, 90, 52)):
    a = T()
    yy, xx = np.mgrid[0:16, 0:16]
    d = np.maximum(np.abs(xx - 7.5), np.abs(yy - 7.5))
    fillrect(a, 0, 0, 16, 16, base)
    ring = (d.astype(int) % 2 == 0)
    shadef(a, 0.84, mask=ring)
    edge = d >= 7
    paint(a, edge, (70, 48, 28))
    noise_mul(a, 0.07, seed)
    return a


# ------------------------------------------------------------ stone
def bricks(base, seed, soot=0.0, rowh=8):
    r = np.random.RandomState(seed)
    a = T()
    fillrect(a, 0, 0, 16, 16, np.array(base) * 0.55)
    rows = 16 // rowh
    for ri in range(rows):
        off = 0 if ri % 2 == 0 else rowh // 2 * (16 // rowh) // 2
        off = 0 if ri % 2 == 0 else 4
        bw = 8
        xs = list(range(-bw + off, 16, bw))
        for x0 in xs:
            x1 = x0 + bw
            tone = 1 + r.uniform(-0.11, 0.11)
            c = np.array(base) * tone
            xa, xb = max(x0 + 1, 0), min(x1, 16)
            ya, yb = ri * rowh + 1, (ri + 1) * rowh
            if xa >= xb:
                continue
            fillrect(a, xa, ya, xb, yb, c)
            # bevel
            m = np.zeros((16, 16), bool)
            m[ya, xa:xb] = True
            shadef(a, 1.13, mask=m)
            m = np.zeros((16, 16), bool)
            m[yb - 1, xa:xb] = True
            shadef(a, 0.84, mask=m)
            m = np.zeros((16, 16), bool)
            m[ya:yb, xb - 1] = True
            shadef(a, 0.88, mask=m)
    noise_mul(a, 0.07, seed + 3)
    if soot > 0:
        n = np.random.RandomState(seed + 9).uniform(0, 1, (16, 16))
        shadef(a, 1 - soot * (0.5 + 0.5 * n))
    return a


def slab(base, seed):
    a = T()
    fillrect(a, 0, 0, 16, 16, base)
    noise_mul(a, 0.10, seed, sx=0.8, sy=0.8)
    noise_mul(a, 0.05, seed + 1)
    m = np.zeros((16, 16), bool)
    m[0, :] = True
    m[:, 0] = True
    shadef(a, 0.75, mask=m)
    m = np.zeros((16, 16), bool)
    m[1, 1:] = True
    m[1:, 1] = True
    shadef(a, 1.08, mask=m)
    return a


def iron(seed=3, base=(132, 140, 158)):
    a = T()
    fillrect(a, 0, 0, 16, 16, base)
    noise_mul(a, 0.07, seed, sx=0.5, sy=3.0)
    m = np.zeros((16, 16), bool)
    m[:, 0:2] = True
    shadef(a, 1.28, mask=m)
    m = np.zeros((16, 16), bool)
    m[:, 13:16] = True
    shadef(a, 0.62, mask=m)
    return a


def dark_iron(seed=4):
    a = iron(seed, (70, 74, 88))
    return a


def chain_tile():
    a = T()
    for y in range(16):
        if (y // 2) % 2 == 0:
            fillrect(a, 6, y, 10, y + 1, (70, 72, 80))
        else:
            fillrect(a, 7, y, 9, y + 1, (96, 98, 108))
    return a


# ------------------------------------------------------------ books
BOOKCOL = [(148, 38, 38), (40, 70, 130), (44, 112, 66), (184, 140, 52), (112, 50, 124), (198, 196, 184),
           (170, 80, 40), (60, 100, 110)]


def bookshelf(seed):
    r = np.random.RandomState(seed)
    a = T()
    fillrect(a, 0, 0, 16, 16, (50, 34, 20))
    plank = planks((110, 78, 44), seed + 7, rows=4)
    a[0:2] = plank[0:2]
    a[14:16] = plank[14:16]
    a[7:9] = plank[6:8]
    for (y0, y1) in ((2, 7), (9, 14)):
        x = 0
        while x < 16:
            w = r.choice([1, 2, 2, 3])
            w = min(w, 16 - x)
            h = r.randint(y1 - y0 - 2, y1 - y0 + 1)
            c = BOOKCOL[r.randint(len(BOOKCOL))]
            if r.rand() < 0.1:
                x += w
                continue
            fillrect(a, x, y1 - h, x + w, y1, c)
            m = np.zeros((16, 16), bool)
            m[y1 - h:y1, x] = True
            shadef(a, 1.22, mask=m)
            if r.rand() < 0.4 and h > 3:
                m = np.zeros((16, 16), bool)
                m[y1 - h + 1, x:x + w] = True
                paint(a, m, (222, 200, 120))
            x += w
    noise_mul(a, 0.05, seed + 11)
    return a


# ------------------------------------------------------------ misc tiles
def leaves(seed, base=(30, 84, 46), snow=0):
    r = np.random.RandomState(seed)
    a = T()
    fillrect(a, 0, 0, 16, 16, base)
    n = r.uniform(0.65, 1.3, (16, 16))
    n = gaussian_filter(n, 0.6, mode='wrap')
    shadef(a, n / n.mean())
    for _ in range(26):
        x, y = r.randint(0, 16), r.randint(0, 16)
        m = np.zeros((16, 16), bool)
        m[y, x] = True
        shadef(a, r.choice([0.55, 1.3]), mask=m)
    if snow:
        for x in range(16):
            h = int(snow + r.randint(-1, 2))
            for y in range(max(h, 0)):
                a[y, x, :3] = (235, 241, 252)
                if y == h - 1:
                    a[y, x, :3] = (208, 220, 240)
    return a


def snow_tile(seed, sparkle=False):
    a = T()
    fillrect(a, 0, 0, 16, 16, (238, 244, 254))
    noise_mul(a, 0.04, seed, sx=1.2, sy=1.2)
    r = np.random.RandomState(seed)
    for _ in range(10):
        x, y = r.randint(0, 16), r.randint(0, 16)
        m = np.zeros((16, 16), bool)
        m[y, x] = True
        shadef(a, 0.9, mask=m)
    return a


def sky_tile(seed, stars=6):
    r = np.random.RandomState(seed)
    a = T()
    for y in range(16):
        c = np.array([20, 28, 66]) * (1 - 0.15 * (y / 15.0)) 
        fillrect(a, 0, y, 16, y + 1, c)
    noise_mul(a, 0.04, seed)
    for _ in range(stars):
        x, y = r.randint(1, 15), r.randint(1, 15)
        c = r.choice([0, 1, 2])
        col = [(240, 244, 255), (200, 215, 255), (255, 244, 210)][c]
        a[y, x, :3] = col
        if r.rand() < 0.25:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                a[y + dy, x + dx, :3] = (120, 140, 190)
    return a


def moon_tile():
    a = T()
    fillrect(a, 0, 0, 16, 16, (238, 240, 226))
    noise_mul(a, 0.05, 3, sx=1.2, sy=1.2)
    for (x, y) in ((4, 5), (10, 4), (7, 10), (11, 11), (3, 11)):
        a[y:y + 2, x:x + 2, :3] = (205, 208, 196)
    return a


def curtain(seed):
    a = T()
    fillrect(a, 0, 0, 16, 16, (150, 30, 36))
    col = 1 + 0.18 * np.sin(np.arange(16) / 16.0 * 2 * np.pi * 2 + 0.5)
    shadef(a, np.tile(col, (16, 1)))
    noise_mul(a, 0.06, seed, sx=0.4, sy=2.0)
    return a


def candle_side():
    a = T()
    fillrect(a, 0, 0, 16, 16, (240, 236, 222))
    noise_mul(a, 0.05, 5, sx=0.4, sy=2.0)
    a[0:3, :, :3] = (252, 250, 240)
    for x in (3, 8, 12):
        a[3:6, x, :3] = (222, 216, 200)
    return a


def candle_top():
    a = T()
    fillrect(a, 0, 0, 16, 16, (246, 242, 230))
    a[7:9, 7:9, :3] = (60, 50, 40)
    return a


def fur():
    a = T()
    fillrect(a, 0, 0, 16, 16, (246, 246, 250))
    noise_mul(a, 0.05, 8, sx=0.5, sy=1.5)
    return a


def berry_leaf(seed):
    a = leaves(seed, base=(34, 92, 50))
    r = np.random.RandomState(seed + 4)
    for _ in range(5):
        x, y = r.randint(1, 14), r.randint(1, 14)
        a[y:y + 2, x:x + 2, :3] = (206, 34, 40)
        a[y, x, :3] = (250, 110, 100)
    return a


def bow_tile():
    a = T()
    fillrect(a, 0, 0, 16, 16, (196, 28, 34))
    noise_mul(a, 0.07, 6, sx=0.5, sy=0.5)
    m = np.zeros((16, 16), bool)
    m[:, 0] = True
    m[0, :] = True
    shadef(a, 0.72, mask=m)
    return a


def solid_glow(c):
    a = T()
    fillrect(a, 0, 0, 16, 16, c)
    m = np.zeros((16, 16), bool)
    m[0, :] = m[15, :] = m[:, 0] = m[:, 15] = True
    shadef(a, 0.82, mask=m)
    return a


def gift_side(base, ribbon, seed):
    a = T()
    fillrect(a, 0, 0, 16, 16, base)
    noise_mul(a, 0.07, seed, sx=0.6, sy=0.6)
    fillrect(a, 6, 0, 10, 16, ribbon)
    m = np.zeros((16, 16), bool)
    m[:, 6] = True
    shadef(a, 1.18, mask=m)
    m = np.zeros((16, 16), bool)
    m[:, 9] = True
    shadef(a, 0.78, mask=m)
    m = np.zeros((16, 16), bool)
    m[0, :] = True
    shadef(a, 1.12, mask=m)
    return a


def gift_top(base, ribbon, seed):
    a = T()
    fillrect(a, 0, 0, 16, 16, base)
    noise_mul(a, 0.07, seed, sx=0.6, sy=0.6)
    fillrect(a, 6, 0, 10, 16, ribbon)
    fillrect(a, 0, 6, 16, 10, ribbon)
    a[6:10, 6:10, :3] = (np.array(ribbon) * 0.82).astype(np.uint8)
    return a


def rug_field(seed):
    a = T()
    fillrect(a, 0, 0, 16, 16, (150, 28, 34))
    noise_mul(a, 0.08, seed, sx=0.7, sy=0.7)
    for i in range(0, 16, 2):
        m = np.zeros((16, 16), bool)
        m[i, :] = True
        shadef(a, 0.94, mask=m)
    return a


def rug_flake(seed):
    a = rug_field(seed)
    W = (236, 232, 228)
    pts = [(7, 7), (8, 7), (7, 8), (8, 8),
           (7, 5), (8, 5), (7, 10), (8, 10), (5, 7), (5, 8), (10, 7), (10, 8),
           (5, 5), (10, 5), (5, 10), (10, 10), (6, 6), (9, 6), (6, 9), (9, 9),
           (7, 3), (8, 3), (7, 12), (8, 12), (3, 7), (3, 8), (12, 7), (12, 8)]
    for (x, y) in pts:
        a[y, x, :3] = W
    return a


def rug_border(seed):
    a = rug_field(seed)
    fillrect(a, 0, 0, 16, 2, (214, 170, 70))
    fillrect(a, 0, 2, 16, 3, (232, 226, 214))
    fillrect(a, 0, 3, 16, 5, (116, 20, 28))
    for x in range(0, 16, 4):
        a[8, x:x + 2, :3] = (232, 226, 214)
    return a


def mug_tile():
    a = T()
    fillrect(a, 0, 0, 16, 16, (236, 234, 228))
    fillrect(a, 6, 4, 10, 8, (60, 130, 70))
    fillrect(a, 7, 8, 9, 11, (60, 130, 70))
    fillrect(a, 3, 4, 4, 6, (196, 40, 44))
    a[0:2, :, :3] = (150, 100, 70)
    return a


def cookie_tile():
    a = T()
    fillrect(a, 0, 0, 16, 16, (196, 138, 72))
    noise_mul(a, 0.10, 9, sx=0.6, sy=0.6)
    for (x, y) in ((4, 4), (10, 6), (6, 10), (11, 11), (3, 9)):
        a[y:y + 2, x:x + 2, :3] = (70, 40, 24)
    return a


def wood_trim(seed=2):
    return planks((86, 58, 34), seed, rows=2, dark=0.7)


def sill_tile():
    a = planks((120, 84, 48), 21, rows=2, dark=0.7)
    return a


def ember_glow(seed=2):
    a = T()
    fillrect(a, 0, 0, 16, 16, (180, 50, 16))
    r = np.random.RandomState(seed)
    for _ in range(40):
        x, y = r.randint(0, 16), r.randint(0, 16)
        a[y, x, :3] = r.choice([0, 1, 2]) and (255, 150, 40) or (110, 24, 12)
    for _ in range(8):
        x, y = r.randint(0, 16), r.randint(0, 16)
        a[y, x, :3] = (255, 220, 120)
    return a


def ash(seed=3):
    a = T()
    fillrect(a, 0, 0, 16, 16, (38, 34, 34))
    noise_mul(a, 0.25, seed, sx=0.6, sy=0.6)
    r = np.random.RandomState(seed)
    for _ in range(14):
        x, y = r.randint(0, 16), r.randint(0, 16)
        a[y, x, :3] = (190, 70, 20)
    return a


def glass_tile():
    # semi transparent pane with a faint reflection
    a = T()
    a[..., :3] = (150, 190, 230)
    a[..., 3] = 34
    for i in range(3, 12):
        a[i, 3 + (i - 3) // 2, 3] = 80
    a[2:5, 12:14, 3] = 70
    return a


# ------------------------------------------------------------ fox texture (64x48 px area)
FOXW, FOXH = 64, 48


def paint_fox():
    f = np.zeros((FOXH, FOXW, 4), np.uint8)
    r = np.random.RandomState(42)
    f[..., :3] = (226, 236, 252)
    f[..., 3] = 255
    n = r.uniform(-1, 1, (FOXH, FOXW))
    n = gaussian_filter(n, 0.7)
    f[..., :3] = np.clip(f[..., :3] + (n[..., None] * 9), 0, 255).astype(np.uint8)
    # soft blue-grey shading band along bottoms
    # head unwrap at (0,0): w=8,h=6,d=6 -> right(0,6,6,6) front(6,6,8,6) left(14,6,6,6) back(20,6,8,6) top(6,0,8,6) bottom(14,0,8,6)
    def rect(x0, y0, w, h, c):
        f[y0:y0 + h, x0:x0 + w, :3] = c

    # cheeks / sides slightly grey at the lower rows
    for (x0, y0, w, h) in ((0, 6, 6, 6), (14, 6, 6, 6), (6, 6, 8, 6)):
        f[y0 + 4:y0 + 6, x0:x0 + w, :3] = (214, 224, 236)
    # front face: closed eyes + cheek patches
    rect(7, 8, 2, 1, (92, 120, 156))
    rect(11, 8, 2, 1, (92, 120, 156))
    rect(7, 9, 1, 1, (170, 190, 214))
    rect(12, 9, 1, 1, (170, 190, 214))
    # brow shadow
    rect(6, 7, 8, 1, (226, 232, 242))
    # top of head: ears root shading
    rect(6, 0, 8, 1, (226, 232, 242))
    # nose unwrap at (0,12): w=4,h=2,d=3 -> right(0,15,3,2) front(3,15,4,2) left(7,15,3,2) back(10,15,4,2) top(3,12,4,3) bottom(7,12,4,3)
    rect(0, 15, 3, 2, (232, 236, 244))
    rect(3, 15, 4, 2, (240, 243, 248))
    rect(4, 15, 2, 1, (22, 22, 28))      # black nose
    rect(3, 12, 4, 3, (236, 240, 246))
    rect(7, 12, 4, 3, (214, 222, 234))
    # mouth line
    rect(4, 16, 2, 1, (210, 218, 230))
    # ear unwrap at (28,0): w=2,h=2,d=1 -> right(28,1,1,2) front(29,1,2,2) left(31,1,1,2) back(32,1,2,2) top(29,0,2,1) bottom(31,0,2,1)
    rect(28, 1, 1, 2, (226, 232, 242))
    rect(29, 1, 2, 2, (206, 182, 190))   # inner ear (front)
    rect(31, 1, 1, 2, (226, 232, 242))
    rect(32, 1, 2, 2, (236, 240, 246))
    rect(29, 0, 2, 1, (92, 98, 116))     # dark ear tip
    # tail unwrap at (36,0): w=5,h=5,d=10 -> right(36,10,10,5) front(46,10,5,5) left(51,10,10,5) back(61,10,5,5)... overflow guard below
    # body unwrap at (0,17): w=6,h=6,d=11 -> top(11,17,6,11) bottom(17,17,6,11) right(0,28,11,6) front(11,28,6,6) left(17,28,11,6) back(28,28,6,6)
    f[17:28, 11:17, :3] = np.clip(f[17:28, 11:17, :3].astype(int) + 4, 0, 255)
    for (x0, y0, w, h) in ((0, 28, 11, 6), (17, 28, 11, 6)):
        f[y0 + 4:y0 + 6, x0:x0 + w, :3] = (210, 220, 234)
    # paws unwrap at (14,12): w=2,h=3,d=4 ... use area (40,28) for legs to avoid overlap; dark socks on lower pixels
    return f


def build_atlas(old_path):
    img = np.zeros((AH, AW, 4), np.uint8)
    old = np.asarray(Image.open(old_path).convert('RGBA'))
    img[0:128, 0:128] = old
    names = {}

    def put(name, col, row, tile):
        img[row * 16:(row + 1) * 16, col * 16:(col + 1) * 16] = tile
        names[name] = (col, row)

    # old tiles by position
    oldn = ['oak', 'spruce', 'log_side', 'log_top', 'stone_brick', 'cobble', 'red_wool', 'white',
            'green_wool', 'snow', 'glass_old', 'leaves_old', 'bookshelf_old', 'lantern', 'birch', 'dark',
            'gold_wool', 'carpet', 'carpet_flake', 'carpet_gold', 'white_wool', 'lgray', 'red2', 'white2',
            'skin', 'black', 'gift_red_old', 'gift_green_old', 'yellow', 'white3', 'ornament', 'gold2',
            'candy', 'night', 'teal', 'magma', 'wolf_face', 'glow', 'red3', 'orange',
            'black2', 'dgray', 'red4', 'white4', 'amber', 'lgray2', 'brown', 'red5']
    for i, n in enumerate(oldn):
        names[n] = (i % 8, i // 8)

    # row 6
    put('floorA', 0, 6, planks((150, 108, 62), 101, rows=4, dark=0.64))
    put('floorB', 1, 6, planks((138, 98, 56), 102, rows=4, dark=0.64))
    put('wallplank', 2, 6, planks((104, 70, 40), 103, rows=4, dark=0.60))
    put('wallplankB', 3, 6, planks((96, 64, 36), 104, rows=4, dark=0.60))
    put('log_v', 4, 6, log_side(111, False))
    put('log_h', 5, 6, log_side(112, True))
    put('log_end', 6, 6, log_end(113))
    put('trim', 7, 6, wood_trim(114))
    # row 7
    put('brickA', 0, 7, bricks((158, 152, 144), 121))
    put('brickB', 1, 7, bricks((150, 146, 140), 122))
    put('brickC', 2, 7, bricks((164, 156, 146), 123))
    put('brick_soot', 3, 7, bricks((96, 90, 86), 124, soot=0.55))
    put('hearth', 4, 7, slab((128, 124, 120), 125))
    put('iron', 5, 7, iron())
    put('iron_dark', 6, 7, dark_iron())
    put('chain', 7, 7, chain_tile())
    # row 8
    for i in range(4):
        put('bookshelf%d' % i, i, 8, bookshelf(130 + i))
    put('ember', 4, 8, ember_glow())
    put('ash', 5, 8, ash())
    put('curtain', 6, 8, curtain(141))
    put('sill', 7, 8, sill_tile())
    # row 9
    for i in range(4):
        put('sky%d' % i, i, 9, sky_tile(150 + i, stars=5 + i))
    put('moon', 4, 9, moon_tile())
    put('spruce_leaf', 5, 9, leaves(161, base=(26, 74, 44)))
    put('spruce_snow', 6, 9, leaves(162, base=(26, 74, 44), snow=5))
    put('snowtop', 7, 9, snow_tile(163))
    # row 10
    put('wreath_leaf', 0, 10, leaves(171, base=(30, 88, 48)))
    put('berry_leaf', 1, 10, berry_leaf(172))
    put('bow', 2, 10, bow_tile())
    put('candle_side', 3, 10, candle_side())
    put('candle_top', 4, 10, candle_top())
    put('fur', 5, 10, fur())
    put('mug', 6, 10, mug_tile())
    put('cookie', 7, 10, cookie_tile())
    # row 11 gifts
    gifts = [('red', (178, 30, 38), (232, 196, 72)), ('green', (36, 110, 62), (236, 232, 226)),
             ('blue', (46, 84, 160), (236, 232, 226)), ('gold', (214, 170, 60), (178, 30, 38))]
    for i, (n, b, rb) in enumerate(gifts):
        put('gift_%s_s' % n, i * 2, 11, gift_side(b, rb, 180 + i))
        put('gift_%s_t' % n, i * 2 + 1, 11, gift_top(b, rb, 190 + i))
    # row 12: rug + lights
    put('rug_field', 0, 12, rug_field(201))
    put('rug_flake', 1, 12, rug_flake(202))
    put('rug_border', 2, 12, rug_border(203))
    put('light_warm', 3, 12, solid_glow((255, 214, 120)))
    put('light_red', 4, 12, solid_glow((255, 90, 70)))
    put('light_blue', 5, 12, solid_glow((130, 190, 255)))
    put('light_white', 6, 12, solid_glow((255, 250, 235)))
    put('star', 7, 12, solid_glow((255, 230, 120)))
    # row 13: glass
    put('glass', 0, 13, glass_tile())
    put('bauble_red', 1, 13, solid_glow((200, 30, 40)))
    put('bauble_gold', 2, 13, solid_glow((226, 176, 56)))
    put('bauble_blue', 3, 13, solid_glow((60, 100, 190)))
    put('bauble_silver', 4, 13, solid_glow((200, 206, 218)))
    put('lamp_glass', 5, 13, solid_glow((255, 190, 90)))
    # fox region: pixels (0,192)-(64,240) -> rows 12.. no: placed at atlas y=224.. to avoid tiles above
    fox = paint_fox()
    FOX_X, FOX_Y = 128, 128
    img[FOX_Y:FOX_Y + FOXH, FOX_X:FOX_X + FOXW] = fox
    names['__fox__'] = (FOX_X, FOX_Y)
    return img, names
