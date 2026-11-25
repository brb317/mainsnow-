import math
import numpy as np
from roomlib import rot_about

def T(p):
    M = np.eye(4); M[:3, 3] = p; return M

# pose: rootYaw, headPitch, headYaw, headRoll, tailYaw, tailPitch, earPitch
POSES = {
    'A': (0.0, -6.0, 25.0, 0.0, -100.0, 0.0, 30.0),
    'B': (0.0, -4.0, 0.0, 0.0, 35.0, 0.0, 22.0),
    'C': (15.0, -8.0, -30.0, 4.0, 110.0, 0.0, 34.0),
    'D': (0.0, 8.0, 10.0, 0.0, 20.0, -12.0, 0.0),
}

def fox_matrices(R, pose, breath=0.0):
    pr = R.points
    root = np.array(pr['fx_root']); yaw0 = pr['fx_yaw'][0]
    ry, hp, hy, hr, ty, tp, ep = pose
    Mroot = T(root) @ rot_about((0, 0, 0), yaw0 + ry, (0, 1, 0))
    ph = np.array(pr['fp_head']); pt = np.array(pr['fp_tail'])
    pel = np.array(pr['fp_earl']); per = np.array(pr['fp_earr'])
    Mhead = Mroot @ rot_about(ph, hy, (0, 1, 0)) @ rot_about(ph, hp, (0, 0, 1)) @ rot_about(ph, hr, (1, 0, 0))
    Mear_l = Mhead @ rot_about(pel, ep, (0, 0, 1))
    Mear_r = Mhead @ rot_about(per, ep, (0, 0, 1))
    Mtail = Mroot @ rot_about(pt, ty, (0, 1, 0)) @ rot_about(pt, tp, (0, 0, 1))
    Mbody = Mroot
    return {'fbody': Mbody, 'fhead': Mhead, 'fearl': Mear_l, 'fearr': Mear_r, 'ftail': Mtail, 'fpaws': Mroot}
