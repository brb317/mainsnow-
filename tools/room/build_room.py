import sys, os, math, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image
import atlas as AT
from meshlib import *
from roomlib import parse_room

import os
HERE = os.path.dirname(os.path.abspath(__file__))
OLD = os.path.join(HERE, 'legacy') + '/'
OUT = os.environ.get('ROOM_OUT', os.path.join(HERE, 'out')) + '/'

OUT_TINT = (0.78, 0.92, 1.45)     # moonlit outdoors
OUT_GLOW = 0.5


def S(*a, **k):
    return sc.box(*a, **k)


def only(*faces):
    return tuple(f for f in FACE_NAMES if f not in faces)


# ======================================================================= shell
def shell():
    # floor
    S((0, -0.3, 0), (12, 0, 12), {'py': ['floorA', 'floorB'], 'side': 'wallplank', 'ny': 'wallplank'}, var=0.07, key=1)
    # ceiling planks
    S((0, 5.92, 0), (12, 6.0, 12), {'ny': ['wallplankB', 'wallplank'], 'all': 'wallplankB'}, var=0.08, skip=only('ny'), key=2)
    # back wall (z=0), faces +z
    S((0, 0, -0.5), (12, 6, 0), {'pz': ['wallplank', 'wallplankB']}, var=0.10, skip=only('pz'), key=3)
    # right wall (x=12) faces -x, front wall (z=12) faces -z (mostly behind the camera)
    S((12, 0, 0), (12.5, 6, 12), {'nx': ['wallplank', 'wallplankB']}, var=0.10, skip=only('nx'), key=4)
    S((0, 0, 12), (12, 6, 12.5), {'nz': ['wallplank', 'wallplankB']}, var=0.10, skip=only('nz'), key=5)
    # left wall (x=0) with window opening  y 1.2..4.6, z 0.9..5.3
    lw = {'px': ['wallplank', 'wallplankB']}
    S((-0.5, 0, 0), (0, WY0, 12), lw, var=0.10, skip=only('px'), key=6)
    S((-0.5, WY1, 0), (0, 6, 12), lw, var=0.10, skip=only('px'), key=7)
    S((-0.5, WY0, 0), (0, WY1, WZ0), lw, var=0.10, skip=only('px', 'pz'), key=8)
    S((-0.5, WY0, WZ1), (0, WY1, 12), lw, var=0.10, skip=only('px', 'nz'), key=9)

    # timber frame: corner posts, rails
    post = {'side': 'log_v', 'py': 'log_end', 'ny': 'log_end'}
    S((0, 0, 0), (0.45, 6, 0.45), post, skip=('ny',))
    S((11.55, 0, 0), (12, 6, 0.45), post)
    S((0, 0, 11.55), (0.45, 6, 12), post)
    S((11.55, 0, 11.55), (12, 6, 12), post)
    # baseboards
    S((0, 0, 0), (12, 0.28, 0.12), {'all': 'trim'}, skip=('nz',))
    S((0, 0, 0.12), (0.12, 0.28, 12), {'all': 'trim'}, skip=('nx',))
    # chair rail
    S((0.45, 1.05, 0), (12, 1.3, 0.1), {'all': 'trim'})
    # top plates
    S((0, 5.6, 0), (12, 5.92, 0.22), {'side': 'log_h', 'ny': 'log_h', 'py': 'log_h'})
    S((0, 5.6, 0.22), (0.22, 5.92, 12), {'side': 'log_h', 'ny': 'log_h', 'py': 'log_h'})
    # ceiling beams along x
    for zc in (2.8, 5.8, 8.8):
        S((0, 5.45, zc - 0.28), (12, 5.92, zc + 0.28), {'side': 'log_h', 'ny': 'log_h', 'py': 'log_h', 'nx': 'log_end', 'px': 'log_end'}, key=int(zc * 10))
    # cross beams along z
    for xc in (2.6, 6.0, 9.4):
        S((xc - 0.2, 5.62, 0.22), (xc + 0.2, 5.92, 12),
          {'px': 'log_h', 'nx': 'log_h', 'ny': 'log_v', 'py': 'log_v', 'pz': 'log_end', 'nz': 'log_end'}, key=int(xc * 10))


# ======================================================================= window + outdoors
def window():
    fr = {'all': 'trim'}
    t0, t1 = -0.30, -0.10
    # frame outer
    S((t0, WY0, WZ0), (t1, WY1, WZ0 + 0.16), fr)
    S((t0, WY0, WZ1 - 0.16), (t1, WY1, WZ1), fr)
    S((t0, WY0, WZ0), (t1, WY0 + 0.16, WZ1), fr)
    S((t0, WY1 - 0.16, WZ0), (t1, WY1, WZ1), fr)
    # mullions
    zm = (WZ0 + WZ1) / 2
    S((t0, WY0, zm - 0.07), (t1, WY1, zm + 0.07), fr)
    for yy in ((WY0 + (WY1 - WY0) / 3), (WY0 + 2 * (WY1 - WY0) / 3)):
        S((t0, yy - 0.07, WZ0), (t1, yy + 0.07, WZ1), fr)
    # glass (blended pass)
    S((-0.22, WY0 + 0.16, WZ0 + 0.16), (-0.19, WY1 - 0.16, WZ1 - 0.16), {'px': 'glass'}, group='staticglass',
      skip=only('px'), occ=False, ao=False, cull=False)
    # inside casing and sill
    cs = {'all': 'wallplank'}
    S((0, WY1, WZ0 - 0.2), (0.14, WY1 + 0.2, WZ1 + 0.2), cs)
    S((0, WY0, WZ0 - 0.2), (0.14, WY1, WZ0), cs)
    S((0, WY0, WZ1), (0.14, WY1, WZ1 + 0.2), cs)
    S((-0.1, WY0 - 0.1, WZ0 - 0.25), (0.5, WY0 + 0.04, WZ1 + 0.25), {'all': 'sill'})
    # reveal inside the wall thickness (sides of the opening)
    S((-0.5, WY0, WZ0), (-0.3, WY1, WZ0 + 0.01), {'pz': 'wallplank'}, skip=only('pz'), occ=False)
    S((-0.5, WY0 + 0.0, WZ0), (-0.3, WY0 + 0.01, WZ1), {'py': 'wallplank'}, skip=only('py'), occ=False)
    # curtain rod + curtain at the near side
    S((0.04, 4.95, WZ1 - 0.1), (0.2, 5.03, WZ1 + 1.6), {'all': 'iron_dark'})
    for k in range(0, 5):
        zc = WZ1 + 0.05 + k * 0.28
        S((0.05, 0.35 + 0.02 * (k % 2), zc), (0.18 + 0.05 * (k % 2), 4.95, zc + 0.28), {'all': 'curtain'}, key=40 + k, skip=('ny',))
    # candle on the sill
    S((0.12, WY0 + 0.04, WZ1 - 0.8), (0.3, WY0 + 0.44, WZ1 - 0.62), {'side': 'candle_side', 'py': 'candle_top'})


def outdoors():
    tint = OUT_TINT
    kw = dict(tint=tint, glow=OUT_GLOW, occ=False, ao=False)
    # sky: a wall far away
    S((-26, -2, -30), (-25.9, 16, 14), {'px': ['sky0', 'sky1', 'sky2', 'sky3']}, tint=(1.1, 1.1, 1.3), glow=1.0, occ=False, ao=False,
      skip=only('px'), cull=False, key=3)
    # moon
    S((-25.5, 7.2, -14.5), (-25.4, 8.6, -13.1), {'px': 'moon'}, glow=1.0, occ=False, ao=False, skip=only('px'), cull=False)
    # snowy ground
    S((-26, 0.2, -30), (-0.5, 1.0, 14), {'py': ['snowtop'], 'side': 'snowtop'}, skip=only('py'), key=11, var=0.03, **kw)
    # hills / drifts
    rr = random.Random(5)
    for i in range(18):
        x = -rr.uniform(2.0, 20.0)
        z = rr.uniform(-22, 6)
        w = rr.uniform(1.5, 3.5)
        h = rr.uniform(0.2, 0.6)
        S((x, 1.0, z), (x + w, 1.0 + h, z + rr.uniform(1.5, 3.5)), {'py': 'snowtop', 'side': 'snowtop'}, skip=only('py', 'px', 'nx', 'pz', 'nz'), **kw)

    def spruce(x, z, h, wmax):
        # trunk
        S((x - 0.25, 1.0, z - 0.25), (x + 0.25, 1.9, z + 0.25), {'side': 'log_v', 'py': 'log_end'}, **kw)
        n = max(4, int(h / 0.85))
        y = 1.5
        for i in range(n):
            f = 1.0 - i / n
            hw = max(0.35, wmax * f)
            S((x - hw, y, z - hw), (x + hw, y + 0.9, z + hw), {'side': 'spruce_snow', 'py': 'snowtop', 'ny': 'spruce_leaf'}, **kw, key=i)
            y += 0.85

    rr = random.Random(11)
    trees = []
    for x, z, h, w in [(xx, zz + 4.0, hh, ww) for (xx, zz, hh, ww) in [(-4.0, -5.0, 6.5, 1.6), (-6.0, -2.2, 7.5, 1.9), (-5.5, 1.5, 6.0, 1.5), (-3.2, -0.6, 5.0, 1.2),
                       (-8.0, -6.5, 8.0, 2.0), (-8.5, 0.0, 7.0, 1.8), (-10.5, -3.5, 8.5, 2.1), (-11.0, 2.5, 7.0, 1.8),
                       (-7.0, -10.0, 7.5, 1.9), (-13.0, -8.0, 9.0, 2.2), (-13.5, -1.0, 8.0, 2.0), (-4.5, -9.0, 6.0, 1.5),
                       (-9.5, -13.0, 8.0, 2.0), (-15.0, -5.0, 9.0, 2.2), (-2.5, 4.5, 4.5, 1.1)]]:
        spruce(x, z, h, w)


# ======================================================================= fireplace
IT = (0.85, 0.96, 1.25)
WZ0, WZ1, WY0, WY1 = 1.9, 5.9, 1.2, 4.6      # окно на левой стене
TCX, TCZ = 1.15, 1.2                          # ёлка стоит в углу
FX0, FX1 = 3.5, 5.7      # firebox opening x
FCX = 4.6


def fireplace():
    br = {'all': ['brickA', 'brickB', 'brickC']}
    brk = dict(var=0.06)
    # breast (below mantel)
    S((2.0, 0, 0), (FX0, 3.0, 1.6), br, key=21, **brk)
    S((FX1, 0, 0), (7.2, 3.0, 1.6), br, key=22, **brk)
    S((FX0, 0, 0), (FX1, 3.0, 0.25), {'pz': 'brick_soot', 'all': 'brick_soot'}, key=23)
    S((FX0, 2.5, 0.25), (FX1, 3.0, 1.6), {'ny': 'brick_soot', 'all': 'brickA', 'pz': ['brickA', 'brickB']}, key=24)
    S((FX0, 0, 0.25), (FX1, 0.25, 1.6), {'py': 'ash', 'all': 'brick_soot'}, key=25)
    # side walls of firebox (soot)
    S((FX0, 0.25, 0.25), (FX0 + 0.01, 2.5, 1.6), {'px': 'brick_soot'}, skip=only('px'), occ=False)
    S((FX1 - 0.01, 0.25, 0.25), (FX1, 2.5, 1.6), {'nx': 'brick_soot'}, skip=only('nx'), occ=False)
    # lintel highlights: corbel blocks under the mantel
    S((1.85, 2.6, 1.6), (2.45, 3.0, 1.9), {'all': 'trim'})
    S((6.75, 2.6, 1.6), (7.35, 3.0, 1.9), {'all': 'trim'})
    # mantel shelf
    S((1.5, 3.0, 0), (7.7, 3.4, 1.9), {'all': 'trim', 'py': 'wallplank', 'pz': 'trim'}, key=26)
    S((1.5, 3.4, 1.82), (7.7, 3.5, 1.9), {'all': 'trim'})
    # upper chimney
    S((2.4, 3.4, 0), (6.8, 6.0, 1.15), br, key=27, **brk)
    S((2.3, 3.4, 0.9), (2.4, 6.0, 1.15), {'all': 'brickB'}, skip=('px', 'nx'))
    # hearth slab
    S((1.7, 0, 1.6), (7.5, 0.22, 2.7), {'py': 'hearth', 'side': 'hearth', 'ny': 'hearth'}, key=28, var=0.05)
    S((1.7, 0.22, 2.55), (7.5, 0.3, 2.7), {'all': 'hearth'})
    # logs and embers
    S((3.65, 0.25, 0.35), (5.55, 0.33, 1.45), {'py': 'ember', 'all': 'ember'}, glow=0.75, occ=False, ao=False)
    S((3.95, 0.33, 0.45), (4.35, 0.65, 1.4), {'side': 'log_v', 'pz': 'log_end', 'nz': 'log_end', 'py': 'log_v'}, key=3, occ=False, ao=False, tint=(0.75, 0.62, 0.55))
    S((4.95, 0.33, 0.45), (5.35, 0.65, 1.4), {'side': 'log_v', 'pz': 'log_end', 'nz': 'log_end', 'py': 'log_v'}, key=4, occ=False, ao=False, tint=(0.75, 0.62, 0.55))
    S((3.8, 0.65, 0.8), (5.5, 0.97, 1.15), {'side': 'log_h', 'px': 'log_end', 'nx': 'log_end', 'py': 'log_h'}, key=5, occ=False, ao=False, tint=(0.8, 0.66, 0.55))
    S((4.3, 0.33, 0.9), (4.9, 0.55, 1.1), {'all': 'ember'}, glow=0.9, occ=False, ao=False)

    # iron fire guard in front of the opening
    iz0, iz1 = 1.78, 1.9
    ix0, ix1 = 3.42, 5.78
    ir = {'all': 'iron'}
    ird = {'all': 'iron_dark'}
    gy0, gy1 = 0.22, 1.42
    # end posts
    for xp in (ix0, ix1 - 0.12):
        S((xp, gy0, iz0 - 0.02), (xp + 0.12, gy1, iz1 + 0.02), ird, tint=IT)
        S((xp - 0.02, gy1, iz0 - 0.04), (xp + 0.14, gy1 + 0.14, iz1 + 0.04), ird, tint=IT)      # cap
    # rails
    S((ix0, gy1 - 0.14, iz0), (ix1, gy1 - 0.04, iz1), ird, tint=IT)
    S((ix0, gy0 + 0.14, iz0), (ix1, gy0 + 0.24, iz1), ird, tint=IT)
    # vertical bars
    nb = 10
    for i in range(nb):
        xb = ix0 + 0.12 + (ix1 - ix0 - 0.24 - 0.07) * i / (nb - 1)
        S((xb, gy0 + 0.1, iz0 + 0.02), (xb + 0.07, gy1 - 0.04, iz1 - 0.02), ir, tint=IT)
    # scroll bar (decorative second rail)
    S((ix0, 0.7, iz0 + 0.02), (ix1, 0.77, iz1 - 0.02), ir, tint=IT)
    # return legs toward the fireplace
    for xp in (ix0, ix1 - 0.12):
        S((xp, gy0, 1.6), (xp + 0.12, gy0 + 0.14, iz0), ird, tint=IT)
        S((xp, gy1 - 0.14, 1.6), (xp + 0.12, gy1 - 0.04, iz0), ird, tint=IT)
    # feet
    for xp in (ix0 - 0.04, ix1 - 0.08):
        S((xp, 0.22, iz0 - 0.06), (xp + 0.2, 0.3, iz1 + 0.06), ird, tint=IT)

    # log basket at the right of the hearth
    S((7.6, 0, 0.5), (8.4, 0.5, 1.3), {'all': 'wallplankB', 'py': 'wallplankB'}, key=31)
    for (lx, lz) in ((7.75, 0.65), (8.0, 0.95), (7.8, 1.0), (8.15, 0.7)):
        S((lx, 0.5, lz), (lx + 0.22, 0.95, lz + 0.22), {'side': 'log_v', 'py': 'log_end'}, key=int(lx * 10))
    # poker tools
    S((7.35, 0, 1.75), (7.42, 1.2, 1.82), {'all': 'iron_dark'})
    S((7.2, 1.15, 1.72), (7.42, 1.27, 1.85), {'all': 'iron_dark'})


# ======================================================================= bookshelf
def bookshelf():
    bs = ['bookshelf0', 'bookshelf1', 'bookshelf2', 'bookshelf3']
    for i in range(3):
        for j in range(3):
            S((8.2 + i, j, 0), (9.2 + i, j + 1, 0.85), {'pz': bs, 'all': 'trim'}, key=50 + i * 3 + j)
    S((8.1, 3.0, 0), (11.4, 3.18, 0.95), {'all': 'trim'})
    S((8.1, 0, 0), (8.2, 3.0, 0.9), {'all': 'trim'})
    S((11.2, 0, 0), (11.4, 3.0, 0.9), {'all': 'trim'})
    # stack of books and a candle on top
    S((8.4, 3.18, 0.12), (9.1, 3.34, 0.6), {'all': 'bookshelf1'})
    S((8.45, 3.34, 0.14), (9.0, 3.48, 0.56), {'all': 'bookshelf2'})
    S((10.2, 3.18, 0.15), (10.4, 3.58, 0.35), {'side': 'candle_side', 'py': 'candle_top'})
    S((10.1, 3.18, 0.1), (10.5, 3.24, 0.4), {'all': 'iron_dark'})




# ======================================================================= rug, gifts, furniture
def rug():
    x0, x1, z0, z1 = 3.3, 7.8, 2.9, 8.6
    S((x0, 0, z0), (x1, 0.04, z1), {'py': ['rug_field', 'rug_field', 'rug_field', 'rug_flake'], 'side': 'rug_field'}, var=0.04, skip=only('py'), key=61, ao=True)
    y0, y1 = 0.04, 0.052
    for (a, b, c, d) in ((x0 + 0.22, z0 + 0.22, x1 - 0.22, z0 + 0.32), (x0 + 0.22, z1 - 0.32, x1 - 0.22, z1 - 0.22),
                         (x0 + 0.22, z0 + 0.22, x0 + 0.32, z1 - 0.22), (x1 - 0.32, z0 + 0.22, x1 - 0.22, z1 - 0.22)):
        S((a, y0, b), (c, y1, d), {'py': 'gold_wool'}, skip=only('py'), ao=False)
    for (a, b, c, d) in ((x0 + 0.38, z0 + 0.38, x1 - 0.38, z0 + 0.44), (x0 + 0.38, z1 - 0.44, x1 - 0.38, z1 - 0.38),
                         (x0 + 0.38, z0 + 0.38, x0 + 0.44, z1 - 0.38), (x1 - 0.44, z0 + 0.38, x1 - 0.38, z1 - 0.38)):
        S((a, y0, b), (c, y1, d), {'py': 'white'}, skip=only('py'), ao=False)
    x = x0 + 0.1
    while x < x1 - 0.1:
        S((x, 0.0, z0 - 0.2), (x + 0.07, 0.03, z0), {'py': 'white', 'all': 'white'}, ao=False, occ=False)
        x += 0.16


def gift(x, z, w, h, d, col, rot=False, y=0.0):
    ribbon = {'red': 'gold_wool', 'green': 'white', 'blue': 'white', 'gold': 'red_wool'}[col]
    S((x, y, z), (x + w, y + h, z + d), {'side': 'gift_%s_s' % col, 'py': 'gift_%s_t' % col, 'ny': 'gift_%s_s' % col},
      stretch=True, var=0.04, key=int(x * 13 + z * 7))
    cx, cz, ty = x + w / 2, z + d / 2, y + h
    S((cx - 0.07, ty, cz - 0.07), (cx + 0.07, ty + 0.12, cz + 0.07), {'all': ribbon}, ao=False)
    S((cx - 0.2, ty, cz - 0.045), (cx - 0.05, ty + 0.1, cz + 0.045), {'all': ribbon}, ao=False)
    S((cx + 0.05, ty, cz - 0.045), (cx + 0.2, ty + 0.1, cz + 0.045), {'all': ribbon}, ao=False)


def gifts():
    gift(0.35, 2.45, 0.7, 0.55, 0.7, 'red')
    gift(1.2, 2.75, 0.55, 0.42, 0.55, 'green')
    gift(0.45, 3.35, 0.5, 0.4, 0.5, 'blue')
    gift(0.5, 2.55, 0.4, 0.34, 0.4, 'gold', y=0.55)
    gift(7.75, 2.25, 0.65, 0.5, 0.65, 'green')
    gift(8.5, 2.5, 0.5, 0.38, 0.5, 'gold')
    gift(8.1, 8.5, 0.62, 0.5, 0.62, 'red')
    gift(8.85, 8.9, 0.45, 0.36, 0.45, 'blue')


def side_table():
    x0, z0 = 9.2, 5.6
    top = {'all': 'wallplank', 'py': 'trim'}
    S((x0, 0.62, z0), (x0 + 1.1, 0.74, z0 + 0.8), top)
    for (lx, lz) in ((x0 + 0.05, z0 + 0.05), (x0 + 0.93, z0 + 0.05), (x0 + 0.05, z0 + 0.63), (x0 + 0.93, z0 + 0.63)):
        S((lx, 0, lz), (lx + 0.12, 0.62, lz + 0.12), {'side': 'log_v', 'py': 'log_end', 'ny': 'log_end'})
    S((x0 + 0.1, 0.74, z0 + 0.14), (x0 + 0.4, 0.98, z0 + 0.44), {'all': 'mug'}, stretch=True)
    S((x0 + 0.5, 0.74, z0 + 0.12), (x0 + 0.95, 0.78, z0 + 0.58), {'all': 'white3'})
    for (cx, cz) in ((0.55, 0.2), (0.72, 0.32), (0.58, 0.42)):
        S((x0 + cx, 0.78, z0 + cz), (x0 + cx + 0.16, 0.84, z0 + cz + 0.16), {'all': 'cookie'}, stretch=True)
    S((x0 + 0.15, 0.74, z0 + 0.52), (x0 + 0.3, 0.9, z0 + 0.62), {'all': 'candy'})


def floor_lantern():
    S((9.9, 0, 8.5), (10.75, 0.55, 9.3), {'side': 'wallplankB', 'py': 'trim', 'ny': 'trim'}, key=71)
    cx, cz, y0 = 10.32, 8.9, 0.55
    S((cx - 0.2, y0, cz - 0.2), (cx + 0.2, y0 + 0.06, cz + 0.2), {'all': 'iron_dark'})
    S((cx - 0.15, y0 + 0.06, cz - 0.15), (cx + 0.15, y0 + 0.5, cz + 0.15), {'all': 'lamp_glass'}, glow=0.9, ao=False, occ=False)
    for (dx, dz) in ((-0.17, -0.17), (0.12, -0.17), (-0.17, 0.12), (0.12, 0.12)):
        S((cx + dx, y0 + 0.06, cz + dz), (cx + dx + 0.05, y0 + 0.5, cz + dz + 0.05), {'all': 'iron_dark'})
    S((cx - 0.2, y0 + 0.5, cz - 0.2), (cx + 0.2, y0 + 0.57, cz + 0.2), {'all': 'iron_dark'})
    S((cx - 0.12, y0 + 0.57, cz - 0.02), (cx + 0.12, y0 + 0.64, cz + 0.02), {'all': 'iron_dark'})


# ======================================================================= mantel decor, wreath
def mantel_decor():
    rr = random.Random(3)
    x = 1.7
    i = 0
    while x < 7.5:
        sag = 0.05 * math.sin(i * 0.5) + rr.uniform(0, 0.05)
        S((x, 3.5, 1.3 + rr.uniform(0, 0.06)), (x + 0.26, 3.72 + sag, 1.82), {'all': ['wreath_leaf', 'berry_leaf']}, key=i, ao=False, occ=False)
        if i % 3 == 1:
            S((x + 0.05, 3.7 + sag, 1.72), (x + 0.13, 3.78 + sag, 1.8), {'all': 'light_warm'}, group='treeA' if i % 2 else 'treeB', glow=1.0)
        if i % 4 == 0:
            S((x + 0.12, 3.68 + sag, 1.74), (x + 0.2, 3.75 + sag, 1.82), {'all': 'bauble_red'}, ao=False, occ=False)
        x += 0.24
        i += 1
    cand = [(2.3, 0.55, 1.5), (2.75, 0.35, 1.42), (6.5, 0.5, 1.5), (6.95, 0.3, 1.42)]
    for k, (cx, h, cz) in enumerate(cand):
        S((cx - 0.08, 3.5, cz - 0.08), (cx + 0.08, 3.5 + h, cz + 0.08), {'side': 'candle_side', 'py': 'candle_top'})
        POINTS['candle%d' % k] = (cx, 3.5 + h, cz)
    # holders
    for (cx, cz) in ((2.3, 1.5), (6.5, 1.5)):
        S((cx - 0.14, 3.5, cz - 0.14), (cx + 0.14, 3.55, cz + 0.14), {'all': 'iron_dark'})


def wreath():
    cx, cy, z0 = 4.6, 4.55, 1.15
    p = 0.15
    rr = random.Random(9)
    for i in range(-7, 8):
        for j in range(-7, 8):
            r = math.hypot(i * p, j * p)
            if 0.56 <= r <= 0.9:
                dz = 0.2 + (0.06 if rr.random() < 0.35 else 0.0)
                S((cx + i * p - p / 2, cy + j * p - p / 2, z0), (cx + i * p + p / 2, cy + j * p + p / 2, z0 + dz),
                  {'all': ['wreath_leaf', 'wreath_leaf', 'berry_leaf']}, key=i * 31 + j, ao=False, occ=False)
    for k in range(10):
        a = rr.uniform(0, 2 * math.pi)
        r = rr.uniform(0.6, 0.86)
        bx, by = cx + math.cos(a) * r, cy + math.sin(a) * r
        S((bx - 0.05, by - 0.05, z0 + 0.22), (bx + 0.05, by + 0.05, z0 + 0.32), {'all': 'bauble_red'}, ao=False)
    for k in range(9):
        a = k * 2 * math.pi / 9 + 0.3
        r = 0.74
        bx, by = cx + math.cos(a) * r, cy + math.sin(a) * r
        S((bx - 0.035, by - 0.035, z0 + 0.22), (bx + 0.035, by + 0.035, z0 + 0.3), {'all': 'light_warm'}, group='treeB' if k % 2 else 'treeA', glow=1.0)
    # bow at the top
    by = cy + 0.86
    bow = {'all': 'bow'}
    S((cx - 0.12, by - 0.12, z0 + 0.22), (cx + 0.12, by + 0.12, z0 + 0.42), bow, ao=False, stretch=True)
    S((cx - 0.55, by - 0.02, z0 + 0.2), (cx - 0.12, by + 0.3, z0 + 0.36), bow, ao=False, stretch=True)
    S((cx + 0.12, by - 0.02, z0 + 0.2), (cx + 0.55, by + 0.3, z0 + 0.36), bow, ao=False, stretch=True)
    S((cx - 0.4, by - 0.36, z0 + 0.2), (cx - 0.14, by - 0.1, z0 + 0.32), bow, ao=False, stretch=True)
    S((cx + 0.14, by - 0.36, z0 + 0.2), (cx + 0.4, by - 0.1, z0 + 0.32), bow, ao=False, stretch=True)


def string_lights():
    # fairy lights along the middle ceiling beam
    z = 5.8 - 0.3
    for i in range(34):
        x = 0.6 + i * 0.33
        y = 5.38 - 0.1 * abs(math.sin(i * 0.5))
        col = ['light_warm', 'light_red', 'light_white', 'light_blue'][i % 4]
        S((x, y, z - 0.04), (x + 0.08, y + 0.08, z + 0.04), {'all': col}, group='treeA' if i % 2 else 'treeB', glow=1.0)
        S((x - 0.2, y + 0.07, z - 0.01), (x, y + 0.09, z + 0.01), {'all': 'iron_dark'}, occ=False, ao=False)


# ======================================================================= tree
def tree():
    cx, cz = TCX, TCZ
    rr = random.Random(21)
    S((cx - 0.2, 0, cz - 0.2), (cx + 0.2, 1.0, cz + 0.2), {'side': 'log_v', 'py': 'log_end'})
    n, lh, y = 12, 0.36, 0.55
    layers = []
    for i in range(n):
        w = 0.98 - i * 0.07 + (0.15 if i % 3 == 0 else 0.0)
        S((cx - w, y, cz - w), (cx + w, y + lh, cz + w), {'all': 'wreath_leaf'}, var=0.08, key=100 + i)
        layers.append((w, y))
        y += lh
    top = y
    # много мелких тёплых огоньков, как на референсе
    for li, (w, y0) in enumerate(layers):
        for face in range(4):
            cnt = 6 if face in (0, 2) else 2
            for k in range(cnt):
                o = rr.uniform(-w + 0.06, w - 0.06)
                yy = y0 + rr.uniform(0.04, lh - 0.12)
                g = 'treeA' if (li + k) % 2 == 0 else 'treeB'
                tile = 'light_warm' if rr.random() < 0.8 else 'light_red'
                if face == 0:
                    a = (cx + w + 0.0, yy, cz + o)
                elif face == 1:
                    a = (cx - w - 0.07, yy, cz + o)
                elif face == 2:
                    a = (cx + o, yy, cz + w + 0.0)
                else:
                    a = (cx + o, yy, cz - w - 0.07)
                S(a, (a[0] + 0.07, a[1] + 0.07, a[2] + 0.07), {'all': tile}, group=g, glow=1.0)
        # красные и золотые шары на видимых сторонах
        for k in range(2):
            tile = rr.choice(['bauble_red', 'bauble_red', 'bauble_gold'])
            o = rr.uniform(-w + 0.2, w - 0.2)
            yy = y0 + rr.uniform(0.02, 0.14)
            a = (cx + w - 0.01, yy, cz + o) if k == 0 else (cx + o, yy, cz + w - 0.01)
            S(a, (a[0] + 0.16, a[1] + 0.16, a[2] + 0.16), {'all': tile}, glow=0.3, ao=False, occ=False)
    # звезда
    pat = ["..#..", ".###.", "#####", ".###.", ".#.#."]
    p = 0.19
    sy = top + 0.05
    for r_, row in enumerate(pat):
        for c_, ch in enumerate(row):
            if ch != '#':
                continue
            dx = (c_ - 2) * p
            yy = sy + (4 - r_) * p
            S((cx + dx - p / 2, yy, cz - p / 2 * 0.9), (cx + dx + p / 2, yy + p, cz + p / 2 * 0.9), {'all': 'star'}, group='star', glow=1.0)
            if c_ != 2:
                S((cx - p / 2 * 0.9, yy, cz + dx - p / 2), (cx + p / 2 * 0.9, yy + p, cz + dx + p / 2), {'all': 'star'}, group='star', glow=1.0)
    POINTS['star'] = (cx, sy + 0.4, cz)
    return top


# ======================================================================= stockings and lantern (dynamic)
def stocking(group, px, py, pz, leg_tile, cuff_tile):
    g = group
    S((px - 0.30, py - 0.36, pz - 0.14), (px + 0.30, py - 0.06, pz + 0.14), {'all': cuff_tile}, group=g)
    S((px - 0.25, py - 0.98, pz - 0.11), (px + 0.25, py - 0.36, pz + 0.11), {'side': leg_tile, 'pz': 'rug_flake', 'nz': leg_tile}, group=g, stretch=False)
    S((px - 0.25, py - 1.25, pz - 0.11), (px + 0.36, py - 0.98, pz + 0.11), {'all': leg_tile}, group=g)
    S((px + 0.36, py - 1.25, pz - 0.115), (px + 0.54, py - 0.98, pz + 0.115), {'all': cuff_tile}, group=g)
    S((px - 0.25, py - 1.25, pz - 0.115), (px - 0.12, py - 1.08, pz + 0.115), {'all': cuff_tile}, group=g)
    S((px - 0.26, py - 0.06, pz - 0.03), (px - 0.18, py + 0.0, pz + 0.03), {'all': 'iron_dark'}, group=g)
    S((px - 0.30, py - 0.04, pz - 0.14), (px - 0.18, py + 0.04, pz + 0.14), {'all': 'iron_dark'}, group=g)


def lantern(group, cx, top, cz):
    S((cx - 0.025, top - 0.6, cz - 0.025), (cx + 0.025, top, cz + 0.025), {'all': 'chain'}, group=group)
    S((cx - 0.1, top - 0.66, cz - 0.1), (cx + 0.1, top - 0.6, cz + 0.1), {'all': 'iron_dark'}, group=group)
    y0 = top - 1.2
    S((cx - 0.17, y0 + 0.5, cz - 0.17), (cx + 0.17, y0 + 0.56, cz + 0.17), {'all': 'iron_dark'}, group=group)
    S((cx - 0.12, y0 + 0.56, cz - 0.12), (cx + 0.12, y0 + 0.62, cz + 0.12), {'all': 'iron_dark'}, group=group)
    S((cx - 0.17, y0, cz - 0.17), (cx + 0.17, y0 + 0.06, cz + 0.17), {'all': 'iron_dark'}, group=group)
    S((cx - 0.13, y0 + 0.06, cz - 0.13), (cx + 0.13, y0 + 0.5, cz + 0.13), {'all': 'lamp_glass'}, group=group, glow=0.95)
    for (dx, dz) in ((-0.17, -0.17), (0.12, -0.17), (-0.17, 0.12), (0.12, 0.12)):
        S((cx + dx, y0 + 0.06, cz + dz), (cx + dx + 0.05, y0 + 0.5, cz + dz + 0.05), {'all': 'iron_dark'}, group=group)
    return y0


# ======================================================================= fox (local frame, forward +x, up +y, right +z)
FOX_U = 0.072          # block per model pixel (1.6x vanilla)
FOXOX, FOXOY = 128, 128


def fbox(group, mn, mx, uv):
    ox, oy, w, h, d = uv
    S(tuple(v * FOX_U for v in mn), tuple(v * FOX_U for v in mx), None, group=group, uvmc=(FOXOX + ox, FOXOY + oy, w, h, d), occ=False, ao=False)


def fox():
    fbox('fbody', (-6, 0, -3), (5, 6, 3), (0, 17, 6, 6, 11))
    fbox('fhead', (5, 0, -4), (11, 6, 4), (0, 0, 8, 6, 6))
    fbox('fhead', (11, 0, -2), (14, 2, 2), (0, 12, 4, 2, 3))
    fbox('fearr', (6, 6, 2), (7, 8, 4), (28, 0, 2, 2, 1))
    fbox('fearl', (6, 6, -4), (7, 8, -2), (28, 0, 2, 2, 1))
    fbox('ftail', (-16, 0, -2.5), (-6, 5, 2.5), (34, 0, 5, 5, 10))
    fbox('fpaws', (7, 0, 1), (11, 2, 3), (36, 17, 2, 2, 4))
    fbox('fpaws', (7, 0, -3), (11, 2, -1), (36, 17, 2, 2, 4))
    fbox('fpaws', (-6, 0, 3), (-2, 2.5, 5), (36, 17, 2, 2, 4))
    fbox('fpaws', (-6, 0, -5), (-2, 2.5, -3), (36, 17, 2, 2, 4))
    u = FOX_U
    POINTS['fx_root'] = (5.0, 0.05, 4.4)
    POINTS['fx_yaw'] = (305.0, 0.0, 0.0)
    POINTS['fp_head'] = (5 * u, 3 * u, 0.0)
    POINTS['fp_earr'] = (6.5 * u, 6 * u, 3 * u)
    POINTS['fp_earl'] = (6.5 * u, 6 * u, -3 * u)
    POINTS['fp_tail'] = (-6 * u, 2.5 * u, 0.0)


# ======================================================================= assemble
POINTS = {}
sc = None
CHAIR_SHIFT = (0.0, 0.0)


def main():
    global sc
    import os
    os.makedirs(OUT, exist_ok=True)
    img, names = AT.build_atlas(OLD + 'room_atlas.png')
    Image.fromarray(img).save(OUT + 'room_atlas.png')
    sc = Scene(names, 256, 256)
    shell(); window(); outdoors(); fireplace(); bookshelf(); rug(); gifts(); side_table(); floor_lantern()
    mantel_decor(); wreath(); string_lights(); tree()
    stocking('stock0', 2.9, 3.0, 1.75, 'red_wool', 'fur')
    stocking('stock1', 4.5, 3.0, 1.75, 'green_wool', 'fur')
    stocking('stock2', 6.1, 3.0, 1.75, 'red_wool', 'fur')
    for i, x in enumerate((2.9, 4.5, 6.1)):
        POINTS['stock%d' % i] = (x, 3.0, 1.75)
    lantern('lantern0', 6.0, 5.45, 5.8)
    POINTS['lanternpiv'] = (6.0, 5.45, 5.8)
    fox()
    POINTS.update({'mug': (6.47 + CHAIR_SHIFT[0], 1.5, 7.41 + CHAIR_SHIFT[1]), 'chairpiv': (6.4 + CHAIR_SHIFT[0], 0.0, 8.4 + CHAIR_SHIFT[1]), 'chairax': (0.91, 0.0, -0.42),
                   'fire': (4.6, 0.45, 0.95), 'candle4': (0.21, 1.64, WZ1 - 0.71), 'candle5': (10.3, 3.58, 0.25)})
    sc.finish()
    groups = export_static(sc)
    nstat = len(groups)
    for g in ('treeA', 'treeB', 'star'):
        groups.append((g,) + sc.group_arrays(g))
    old = parse_room(OLD + 'room.bin')
    cg = old.groups['chair']
    cpos = cg.pos.astype(np.float32).copy()
    cpos[:, 0] += CHAIR_SHIFT[0]
    cpos[:, 2] += CHAIR_SHIFT[1]
    groups.append(('chair', cpos, cg.nrm.astype(np.float32), (cg.uv * 0.5).astype(np.float32),
                   cg.col.astype(np.float32), cg.idx.astype(np.uint32)))
    for i in range(3):
        groups.append(('stock%d' % i,) + sc.group_arrays('stock%d' % i))
    groups.append(('lantern0',) + sc.group_arrays('lantern0'))
    for g in ('fbody', 'fhead', 'fearl', 'fearr', 'ftail', 'fpaws'):
        arr = list(sc.group_arrays(g))
        arr[3] = arr[3].copy()
        arr[3][:, :3] = np.clip(NEUTRAL * np.array([0.82, 0.96, 1.28]), 0, 1)
        groups.append((g,) + tuple(arr))
    lights = [
        (4.6, 1.1, 1.1, 2.7, 1.1, 0.38, 14.0, 0.22),
        (6.0, 4.4, 5.8, 1.5, 0.95, 0.45, 9.0, 0.05),
        (2.3, 4.0, 1.5, 1.0, 0.6, 0.28, 5.5, 0.18),
        (6.5, 4.0, 1.5, 1.0, 0.6, 0.28, 5.5, 0.18),
        (1.15, 2.7, 1.25, 0.8, 0.62, 0.26, 6.5, 0.08),
        (10.32, 0.9, 8.9, 1.3, 0.8, 0.36, 5.0, 0.04),
        (0.8, 3.0, 3.9, 0.2, 0.32, 0.75, 8.0, 0.0),
        (8.0, 1.4, 7.0, 0.5, 0.3, 0.14, 3.5, 0.15)]
    halos = [
        (6.0, 4.3, 5.8, 1.0, 0.7, 0.3, 1.5),
        (2.3, 4.0, 1.5, 1.0, 0.65, 0.3, 0.7),
        (6.5, 4.0, 1.5, 1.0, 0.65, 0.3, 0.7),
        (10.32, 0.85, 8.9, 1.0, 0.7, 0.3, 1.5),
        (4.6, 1.4, 1.0, 1.0, 0.55, 0.2, 3.2),
        (1.15, 5.45, 1.2, 1.0, 0.85, 0.4, 1.4)]
    size = write_room(OUT + 'room.bin', groups, lights, halos, POINTS)
    print('written room.bin', size, 'bytes')
    for g in groups:
        print(g[0], len(g[1]), 'verts')


if __name__ == '__main__':
    main()
