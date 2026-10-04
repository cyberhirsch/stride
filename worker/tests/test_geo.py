import math

import numpy as np

from stride_worker import geo
from stride_worker.align import candidate_pairs, prior_focal_px


def test_enu_roundtrip():
    e = geo.to_enu(47.8571, 12.1301, 512.0, 47.8561, 12.1289, 500.0)
    assert abs(e[1] - 111.3) < 1.0 and abs(e[0] - 89.8) < 1.0 and e[2] == 12.0
    lat, lon, alt = geo.from_enu(e, 47.8561, 12.1289, 500.0)
    assert abs(lat - 47.8571) < 1e-9 and abs(lon - 12.1301) < 1e-9 and alt == 512.0


def test_rotation_between():
    for a, b in [([0, 0, 1], [1, 0, 0]), ([0.3, -0.2, 0.9], [0, 0, 1]), ([0, 0, 1], [0, 0, -1])]:
        a, b = np.array(a, float), np.array(b, float)
        R = geo.rotation_between(a, b)
        assert np.allclose(R @ (a / np.linalg.norm(a)), b / np.linalg.norm(b), atol=1e-9)
        assert abs(np.linalg.det(R) - 1) < 1e-9


def test_ransac_similarity_recovers_transform_with_outliers():
    rng = np.random.default_rng(1)
    src = rng.uniform(-20, 20, (30, 2))
    th = math.radians(37)
    R = np.array([[math.cos(th), -math.sin(th)], [math.sin(th), math.cos(th)]])
    dst = (2.5 * (R @ src.T)).T + [100, -40] + rng.normal(0, 0.5, (30, 2))
    dst[:4] += 80  # gross GPS errors
    s, R2, t, inl = geo.ransac_similarity_2d(src, dst, threshold=3)
    assert abs(s - 2.5) < 0.05 and np.allclose(R2, R, atol=0.02) and not inl[:4].any() and inl[4:].all()


def test_estimate_up_from_level_cameras_and_pitch():
    rng = np.random.default_rng(2)
    up = np.array([0.1, 0.2, 0.97]); up /= np.linalg.norm(up)
    rights, axes, pitches = [], [], []
    for _ in range(20):
        h = rng.uniform(0, 2 * math.pi); p = rng.uniform(-0.3, 0.4)
        # build in a frame where z is up, then rotate into the tilted model frame
        R = geo.rotation_between(np.array([0, 0, 1.0]), up)
        axis = R @ np.array([math.sin(h) * math.cos(p), math.cos(h) * math.cos(p), math.sin(p)])
        right = R @ np.array([math.cos(h), -math.sin(h), 0])
        rights.append(right); axes.append(axis); pitches.append(math.degrees(p))
    assert np.dot(geo.estimate_up(rights, axes, pitches), up) > 0.999
    g = geo.estimate_up(rights, axes, [None] * 20)
    assert abs(np.dot(g, up)) > 0.999  # sign fixed later from the cameras


def test_candidate_pairs_prune_far_and_opposite_views():
    photos = [dict(file=f"{i}", gps_accuracy=5, has_heading=True, heading=h, width=1600, height=1200)
              for i, h in enumerate([0] * 31 + [180, 0])]
    enu = [np.array([i * 0.5, 0, 0]) for i in range(31)] + [np.array([30.0, 0, 0]), np.array([500.0, 0, 0])]
    pairs = set(candidate_pairs(photos, enu))
    assert (0, 1) in pairs
    assert all(32 not in p for p in pairs)            # 500 m away
    assert (0, 31) not in pairs                        # 30 m apart and facing opposite ways


def test_prior_focal_from_fov():
    assert abs(prior_focal_px(dict(fov_h=90, width=1600, height=1200)) - 800) < 1e-6
    assert prior_focal_px(dict(width=1600, height=1200)) == 1920
