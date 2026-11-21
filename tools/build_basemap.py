#!/usr/bin/env python3
"""Build an airport's base map layers from OpenStreetMap.

Downloads the area around the terminal from the OSM API (or reads cached
.osm files), projects it to local metres (north up, around the airport's
origin), simplifies it and writes airports/<code>/basemap.js.

Map data © OpenStreetMap contributors, ODbL 1.0.

Usage: python3 tools/build_basemap.py <cle|atl> [cache dir]
Writes airports/<code>/basemap.js (BASEMAP_<CODE>); then run tools/build.py.
To add an airport, add its settings to AIRPORTS below.
Needs shapely (pip install shapely) to pave the ramp around the concourses.
"""
import json, math, os, sys, urllib.request
import xml.etree.ElementTree as ET

# Per airport: origin, crop (x0, y0, x1, y1 in metres), OSM bboxes (the API caps each request,
# so big airports are fetched in tiles), which terminal buildings become which layer,
# and which OSM indoor levels are the upper and lower floors.
AIRPORTS = {
    'cle': dict(origin=(41.4100, -81.8395), crop=(-700, -800, 800, 800),
                bboxes=['-81.856,41.398,-81.824,41.422'],
                terms=[('Concourse D', 'termD'), ('Concourse A', 'termA'), ('Concourse B', 'termB'),
                       ('Concourse C', 'termC'), ('Main Terminal', 'termM')],
                ramp=['Concourse A', 'Concourse B', 'Concourse C', 'Concourse D'],
                levels={'1': 'walk1', '0': 'walk0', '-1': 'walkm1'}),
    # Hartsfield-Jackson: concourses T, A-F in a row, joined underground by the Transportation Mall
    # and the Plane Train (level -1); OSM levels 0-2 are the concourses and terminals.
    'atl': dict(origin=(33.6407, -84.4277), crop=(-1950, -700, 1150, 650),
                bboxes=[f'{-84.4500 + i * 0.0085:.4f},33.6330,{-84.4500 + (i + 1) * 0.0085:.4f},33.6480' for i in range(4)],
                terms=[('Concourse T', 'termT'), ('Concourse A', 'termA'), ('Concourse B', 'termB'), ('Concourse C', 'termC'),
                       ('Concourse D', 'termD'), ('Concourse E', 'termE'), ('Concourse F', 'termF'),
                       ('Domestic Terminal', 'termM'), ('North Terminal', 'termM'), ('South Terminal', 'termM'),
                       ('Maynard H. Jackson', 'termM')],
                ramp=['Concourse T', 'Concourse A', 'Concourse B', 'Concourse C', 'Concourse D', 'Concourse E', 'Concourse F'],
                levels={'2': 'walk1', '1': 'walk1', '0': 'walk1', '-1': 'walk0'},
                train='The Plane Train'),
    # Dallas/Fort Worth: five horseshoe terminals (A–E) either side of the spine road, joined past
    # security by the Skylink train (an elevated people mover)
    'dfw': dict(origin=(32.8990, -97.0405), crop=(-1300, -1800, 1300, 1800),
                bboxes=[f'{-97.0530 + i * 0.0085:.4f},32.8820,{-97.0530 + (i + 1) * 0.0085:.4f},32.8990' for i in range(3)]
                      + [f'{-97.0530 + i * 0.0085:.4f},32.8990,{-97.0530 + (i + 1) * 0.0085:.4f},32.9160' for i in range(3)],
                terms=[('Terminal A', 'termA'), ('Terminal B', 'termB'), ('Terminal C', 'termC'), ('Terminal D', 'termD'), ('Terminal E', 'termE')],
                ramp=['Terminal A', 'Terminal B', 'Terminal C', 'Terminal D', 'Terminal E'],
                levels={'2': 'walk1', '1': 'walk1', '0': 'walk0', '-1': 'walk0', '3': 'walk1'},
                train='Skylink'),
}
CODE = (sys.argv[1] if len(sys.argv) > 1 else 'cle').lower()
CFG = AIRPORTS[CODE]
LAT0, LON0 = CFG['origin']
KX = math.cos(math.radians(LAT0)) * 111320
KY = 110574
CROP = CFG['crop']

def P(lat, lon):
    return ((lon - LON0) * KX, -(lat - LAT0) * KY)

def load(folder):
    nodes, ways, seen, byid, rels = {}, [], set(), {}, {}
    for i, bbox in enumerate(CFG['bboxes']):
        path = os.path.join(folder, f'{CODE}{i if len(CFG["bboxes"]) > 1 else ""}.osm')
        if not os.path.exists(path):
            url = f'https://api.openstreetmap.org/api/0.6/map?bbox={bbox}'
            print('downloading', url)
            urllib.request.urlretrieve(url, path)
        root = ET.parse(path).getroot()
        for n in root.findall('node'):
            nodes[n.get('id')] = (float(n.get('lat')), float(n.get('lon')), {t.get('k'): t.get('v') for t in n.findall('tag')})
        for w in root.findall('way'):
            wid = w.get('id'); nds = [nd.get('ref') for nd in w.findall('nd')]
            byid[wid] = nds
            if wid in seen: continue       # ways crossing tile edges come back from each tile
            seen.add(wid)
            ways.append((wid, nds, {t.get('k'): t.get('v') for t in w.findall('tag')}))
        for r in root.findall('relation'):
            t = {x.get('k'): x.get('v') for x in r.findall('tag')}
            if t.get('type') == 'multipolygon' and (t.get('aeroway') == 'terminal' or t.get('building')):
                rels[r.get('id')] = (t, [m.get('ref') for m in r.findall('member') if m.get('type') == 'way' and m.get('role') == 'outer'])
    # multipolygon buildings (DFW's terminals are mapped this way): join their outer ways into closed
    # rings and treat each ring as a closed way with the relation's tags
    for rid, (t, members) in rels.items():
        segs = [list(byid[m]) for m in members if m in byid and len(byid[m]) > 1]
        n = 0
        while segs:
            ring = segs.pop(0)
            while ring[0] != ring[-1]:
                for i, sg in enumerate(segs):
                    if sg[0] == ring[-1]: ring += sg[1:]
                    elif sg[-1] == ring[-1]: ring += sg[::-1][1:]
                    elif sg[-1] == ring[0]: ring = sg[:-1] + ring
                    elif sg[0] == ring[0]: ring = sg[::-1][:-1] + ring
                    else: continue
                    segs.pop(i); break
                else: break
            if ring[0] == ring[-1] and len(ring) > 3:
                ways.append(('r%s_%d' % (rid, n), ring, t)); n += 1
    return nodes, ways

def dp(pts, tol):
    """Douglas-Peucker simplification."""
    if len(pts) < 3: return pts
    a, b = pts[0], pts[-1]
    dx, dy = b[0] - a[0], b[1] - a[1]; L = math.hypot(dx, dy)
    i, dmax = 0, 0
    for k in range(1, len(pts) - 1):
        # a closed ring starts and ends on the same point, so measure from that point instead of a line
        d = (abs(dy * pts[k][0] - dx * pts[k][1] + b[0] * a[1] - b[1] * a[0]) / L) if L > 1e-9 else math.dist(pts[k], a)
        if d > dmax: i, dmax = k, d
    if dmax <= tol: return [a, b]
    return dp(pts[:i + 1], tol)[:-1] + dp(pts[i:], tol)

def inside(pts):
    x0, y0, x1, y1 = CROP
    return any(x0 <= x <= x1 and y0 <= y <= y1 for x, y in pts)

def path(pts, closed):
    s = 'M' + 'L'.join(f'{round(x)} {round(y)}' for x, y in pts)
    return s + ('Z' if closed else '')

def main():
    nodes, ways = load(sys.argv[2] if len(sys.argv) > 2 else '.')
    L = {k: [] for k in ['grass', 'wood', 'water', 'apron', 'taxiway', 'runway', 'lots', 'motorway', 'major', 'minor',
                         'service', 'rail', 'buildings', 'garage', 'bridges', 'walk1', 'walk0', 'walkm1', 'stairs', 'train']
         + sorted({layer for _, layer in CFG['terms']})}
    stands = []
    for wid, nds, t in ways:
        pts = [P(*nodes[n][:2]) for n in nds if n in nodes]
        if len(pts) < 2 or not inside(pts): continue
        closed = nds[0] == nds[-1]
        simp = dp(pts, 0.8)
        aw, hw, lu, nat = t.get('aeroway'), t.get('highway'), t.get('landuse'), t.get('natural')
        # Indoor walkways by floor (see levels above); jet bridges are skipped.
        lv = t.get('level')
        if CFG.get('train') and t.get('railway') == 'monorail' and CFG['train'] in t.get('name', ''):
            L['train'].append(path(simp, False)); continue
        if lv and hw in ('footway', 'corridor', 'steps') and aw != 'jet_bridge':
            layer = 'stairs' if (';' in lv or hw == 'steps') else CFG['levels'].get(lv)
            if layer:
                L[layer].append(path(simp, False))
                continue
        name = t.get('name', '')
        term = next((layer for key, layer in CFG['terms'] if key in name), None) if aw == 'terminal' and closed else None
        if term: L[term].append(path(simp, True))
        elif t.get('parking') == 'multi-storey': L['garage'].append(path(simp, True))
        elif aw == 'jet_bridge': L['bridges'].append(path(simp, False))
        elif aw == 'parking_position' and len(pts) >= 2: stands.append((pts[0], pts[-1], t.get('ref', '')))
        elif aw == 'apron' and closed: L['apron'].append(path(simp, True))
        elif aw == 'taxiway': L['taxiway'].append(path(simp, False))
        elif aw == 'runway': L['runway'].append(path(simp, closed))
        elif (t.get('building') or aw in ('hangar', 'terminal')) and closed: L['buildings'].append(path(simp, True))
        elif t.get('amenity') == 'parking' and closed: L['lots'].append(path(simp, True))
        elif lu in ('grass', 'meadow') or t.get('leisure') == 'park': L['grass'].append(path(simp, closed))
        elif nat in ('wood', 'scrub', 'wetland') or lu == 'forest': L['wood'].append(path(simp, closed))
        elif nat == 'water' or t.get('waterway'): L['water'].append(path(simp, closed))
        elif hw in ('motorway', 'trunk'): L['motorway'].append(path(simp, False))
        elif hw in ('motorway_link', 'trunk_link', 'primary', 'primary_link', 'secondary', 'secondary_link'): L['major'].append(path(simp, False))
        elif hw in ('tertiary', 'unclassified', 'residential'): L['minor'].append(path(simp, False))
        elif hw == 'service' and t.get('service') not in ('parking_aisle',): L['service'].append(path(simp, False))
        elif t.get('railway') in ('rail', 'subway') and t.get('tunnel') != 'yes': L['rail'].append(path(simp, False))
    # Aircraft at about two thirds of stands, picked by stand position so it never changes.
    # A stand line starts at the nose stop point; the aircraft sits ~17 m behind it, nose to the terminal.
    planes = []
    gates = [P(la, lo) for la, lo, t in nodes.values() if t.get('aeroway') == 'gate']
    near = lambda p: min(math.dist(p, g) for g in gates) if gates else 0
    for (a, b, ref) in stands:
        if near(b) < near(a): a, b = b, a          # the nose end is the one nearest a gate
        if near(a) > 45: continue                  # remote stands: leave empty
        if int(abs(a[0] * 7 + a[1] * 13)) % 3 == 0: continue
        dx, dy = b[0] - a[0], b[1] - a[1]; d = math.hypot(dx, dy) or 1
        ux, uy = dx / d, dy / d
        cx, cy = a[0] + ux * 17, a[1] + uy * 17
        planes.append([round(cx, 1), round(cy, 1), round(math.degrees(math.atan2(-ux, uy)), 1)])  # SVG rotate() so the nose (0,-1) faces the gate
    # Pave the ramp around the concourses (not mapped as an apron in OSM): a 57 m buffer,
    # precomputed as one simple polygon so phones don't rasterise a huge rounded stroke.
    from shapely.geometry import Polygon
    from shapely.ops import unary_union
    rings = []
    for wid, nds, t in ways:
        name = t.get('name', '')
        if t.get('aeroway') == 'terminal' and any(c in name for c in CFG['ramp']):
            pts = [P(*nodes[n][:2]) for n in nds if n in nodes]
            if len(pts) > 3: rings.append(Polygon(pts).buffer(0))
    ramp = unary_union([r.buffer(57, quad_segs=4) for r in rings]).simplify(2.5)
    geoms = getattr(ramp, 'geoms', [ramp])
    L['ramp'] = [path(list(g.exterior.coords)[:-1], True) + ''.join(path(list(i.coords)[:-1], True) for i in g.interiors) for g in geoms]
    out = {k: ''.join(v) for k, v in L.items()}
    out['planes'] = planes
    js = f'const BASEMAP_{CODE.upper()} = ' + json.dumps(out, separators=(',', ':')) + ';\n'
    dest = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'airports', CODE, 'basemap.js')
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    open(dest, 'w').write(js)
    print(dest, len(js) // 1024, 'KB', {k: len(v) for k, v in L.items()}, 'planes', len(planes))

if __name__ == '__main__':
    main()
