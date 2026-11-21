#!/usr/bin/env python3
"""Aircraft models for the game, seen from above: nose up (-y), in metres, centred on the fuselage.

Each model is a few SVG paths drawn in order, flat-shaded from the plane's colour:
  wing    wings and tailplanes (the plane's colour)
  eng     engine pods (a little darker), intake (dark rings across their fronts)
  fuse    fuselage (a little lighter), hl (light along its top)
  fin     the fin, seen edge-on (darker), win (cockpit windows)
plus len and span in metres. Writes src/aircraft.json, which the web page and the Android app share.

Usage: python3 tools/aircraft.py
"""
import json, os

def f(v): return ('%.2f' % v).rstrip('0').rstrip('.') if abs(v) > 1e-9 else '0'

def smooth(right):
    """closed, mirrored outline through right-side points from the nose (x=0) to the tail (x=0); Catmull-Rom"""
    pts = right + [(-x, y) for x, y in reversed(right[1:-1])]
    n = len(pts); d = 'M%s %s' % (f(pts[0][0]), f(pts[0][1]))
    for i in range(n):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        d += 'C%s %s %s %s %s %s' % (f(c1[0]), f(c1[1]), f(c2[0]), f(c2[1]), f(p2[0]), f(p2[1]))
    return d + 'Z'

def poly(pts, mirror=True):
    d = ''
    for sgn in ([1, -1] if mirror else [1]):
        q = [(sgn * x, y) for x, y in pts]
        d += 'M' + 'L'.join('%s %s' % (f(x), f(y)) for x, y in q) + 'Z'
    return d

def capsule(cx, y0, y1, w, front=1.0):
    """an engine pod: rounded, slightly wider at the intake; mirrored to both sides"""
    d = ''
    for s in (1, -1):
        x = s * cx; r = w / 2
        d += smooth_abs([(x, y0), (x + r * front, y0 + .35 * w), (x + r, y0 + w), (x + r * .85, y1 - .3 * w), (x, y1), (x - r * .85, y1 - .3 * w), (x - r, y0 + w), (x - r * front, y0 + .35 * w)])
    return d

def smooth_abs(pts):
    n = len(pts); d = 'M%s %s' % (f(pts[0][0]), f(pts[0][1]))
    for i in range(n):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        d += 'C%s %s %s %s %s %s' % (f(c1[0]), f(c1[1]), f(c2[0]), f(c2[1]), f(p2[0]), f(p2[1]))
    return d + 'Z'

def wing(root_x, root_le, root_te, tip_x, tip_le, tip_te, kink=None):
    """a swept wing (right side, mirrored): root leading edge → tip → trailing edge, with a rounded tip"""
    pts = [(root_x, root_le), (tip_x - .4, tip_le), (tip_x, tip_le + .35 * (tip_te - tip_le)), (tip_x - .25, tip_te)]
    if kink: pts.append(kink)
    pts.append((root_x, root_te))
    return poly(pts)

def model(L, W, span, wing_le, wing_te, tip_le, tip_te, kink, eng, stab, rear=False):
    h = L / 2; r = W / 2
    fuse = smooth([(0, -h), (r * .45, -h + .35 * W), (r * .85, -h + .9 * W), (r, -h + 1.7 * W), (r, h - .32 * L),
                   (r * .9, h - .2 * L), (r * .62, h - .09 * L), (r * .28, h - .015 * L), (0, h)])
    wings = wing(r * .9, wing_le, wing_te, span / 2, tip_le, tip_te, kink)
    sx, sle, ste, stx, stle, stte = stab
    wings += wing(r * .7, sle, ste, stx, stle, stte)
    ex, ey0, ey1, ew = eng
    engs = capsule(ex, ey0, ey1, ew)
    # the intakes: a dark ring across the front of each pod
    intake = ''.join('M%s %sQ%s %s %s %sQ%s %s %s %sZ' % (f(s * ex - ew * .36), f(ey0 + .3 * ew), f(s * ex), f(ey0 + .08 * ew), f(s * ex + ew * .36), f(ey0 + .3 * ew), f(s * ex), f(ey0 + .5 * ew), f(s * ex - ew * .36), f(ey0 + .3 * ew)) for s in (1, -1))
    nose = -h
    win = 'M%s %sQ0 %s %s %sL%s %sQ0 %s %s %sZ' % (f(-r * .62), f(nose + .9 * W), f(nose + .6 * W), f(r * .62), f(nose + .9 * W),
                                                 f(r * .52), f(nose + 1.05 * W), f(nose + .8 * W), f(-r * .52), f(nose + 1.05 * W))
    hl = smooth([(0, -h + 1.8 * W), (r * .22, -h + 2.4 * W), (r * .22, h - .34 * L), (0, h - .28 * L)])
    fin = smooth([(0, h - .27 * L), (.22 * r, h - .2 * L), (.16 * r, h - .03 * L), (0, h + .01 * L)])
    return dict(len=L, span=span, wing=wings, eng=engs, intake=intake, fuse=fuse, hl=hl, win=win, fin=fin)

M = {
    # a twin-jet narrowbody, about 38 m long and 36 m across
    'narrow': model(38, 4.6, 35.8, wing_le=-4.8, wing_te=5.2, tip_le=5.2, tip_te=7.6, kink=(7.0, 5.8),
                    eng=(6.0, -6.6, -.6, 2.4), stab=(0, 12.2, 16.4, 6.4, 15.9, 17.8)),
    # a widebody twin, about 64 m long and 61 m across
    'wide': model(64, 7.0, 61, wing_le=-9.0, wing_te=9.4, tip_le=10.8, tip_te=14.6, kink=(12.0, 10.6),
                  eng=(10.4, -13.4, -3.2, 4.0), stab=(0, 21.5, 28.0, 10.6, 27.2, 30.4)),
    # a regional jet with its engines at the back and a T-tail, about 31 m long and 26 m across
    'regional': model(31, 3.4, 26, wing_le=-2.2, wing_te=4.8, tip_le=4.8, tip_te=6.9, kink=None,
                      eng=(2.95, 4.0, 9.8, 1.9), stab=(0, 11.8, 14.6, 4.7, 14.6, 15.7)),
}
out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'src', 'aircraft.json')
with open(out, 'w') as fh: json.dump(M, fh, indent=1)
print(out, {k: round(sum(len(v) for v in m.values() if isinstance(v, str)) / 1024, 1) for k, m in M.items()}, 'KB')
