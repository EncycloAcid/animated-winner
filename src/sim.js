// ================= Clearway Tower: air traffic control on the airport map =================
// Planes arrive and hold until you clear them to land. Once they're off the runway you give them a
// gate (tap a gate number). After boarding you push them back to a runway and clear them for takeoff.
// A plane that runs out of fuel diverts, and clearing a takeoff onto a busy runway is an incursion:
// three strikes and the shift is over.
document.title = `Clearway Tower · ${AP.code}`;
const deg = Math.PI / 180;
const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
const hdgOf = (dx, dy) => (Math.atan2(dx, -dy) / deg + 360) % 360;        // 0 = north, clockwise; y is south
const vecOf = h => ({ x: Math.sin(h * deg), y: -Math.cos(h * deg) });
const angDiff = (a, b) => ((b - a) % 360 + 540) % 360 - 180;
const rand = (a, b) => a + Math.random() * (b - a);
const mmss = s => { s = Math.max(0, Math.ceil(s)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };

// ---------- Ground network: OpenStreetMap's taxiways and runways as a graph ----------
const parsePath = d => (d || '').split('M').filter(Boolean)
  .map(s => s.replace(/Z/g, '').split('L').map(p => p.trim().split(/\s+/).map(Number)).filter(p => p.length === 2 && p.every(isFinite)))
  .filter(p => p.length > 1);
const RUNWAYS = parsePath(B.runway).map((pts, i) => {
  const a = { x: pts[0][0], y: pts[0][1] }, b = { x: pts[pts.length - 1][0], y: pts[pts.length - 1][1] }, L = dist(a, b);
  return { i, a, b, L, u: { x: (b.x - a.x) / L, y: (b.y - a.y) / L } };
});
const G = { x: [], y: [], adj: [], rw: [], main: [] };
const gridN = new Map(), CELL = 6;
function gnode(x, y, rw = -1) {
  const cx = Math.floor(x / CELL), cy = Math.floor(y / CELL);
  for (let i = -1; i <= 1; i++) for (let j = -1; j <= 1; j++) {
    const l = gridN.get((cx + i) + ',' + (cy + j));
    if (l) for (const n of l) if (Math.hypot(G.x[n] - x, G.y[n] - y) < 3) { if (rw >= 0) G.rw[n] = rw; return n; }
  }
  const n = G.x.length; G.x.push(x); G.y.push(y); G.adj.push([]); G.rw.push(rw); G.main.push(false);
  const k = cx + ',' + cy; (gridN.get(k) || gridN.set(k, []).get(k)).push(n); return n;
}
const link = (p, q) => { if (p === q || G.adj[p].includes(q)) return; G.adj[p].push(q); G.adj[q].push(p); };
const unlink = (p, q) => { G.adj[p] = G.adj[p].filter(n => n !== q); G.adj[q] = G.adj[q].filter(n => n !== p); };
{
  const segs = [];
  for (const p of parsePath(B.taxiway)) for (let i = 1; i < p.length; i++) segs.push({ a: p[i - 1], b: p[i], rw: -1, ts: [0, 1] });
  for (const r of RUNWAYS) segs.push({ a: [r.a.x, r.a.y], b: [r.b.x, r.b.y], rw: r.i, ts: [0, 1] });
  // where ways cross without sharing a point, split both there
  const bb = s => [Math.min(s.a[0], s.b[0]), Math.min(s.a[1], s.b[1]), Math.max(s.a[0], s.b[0]), Math.max(s.a[1], s.b[1])];
  const boxes = segs.map(bb);
  for (let i = 0; i < segs.length; i++) for (let j = i + 1; j < segs.length; j++) {
    const A = boxes[i], C = boxes[j];
    if (A[0] > C[2] || C[0] > A[2] || A[1] > C[3] || C[1] > A[3]) continue;
    const p = segs[i].a, q = segs[i].b, r = segs[j].a, s = segs[j].b;
    const d = (q[0] - p[0]) * (s[1] - r[1]) - (q[1] - p[1]) * (s[0] - r[0]);
    if (!d) continue;
    const t = ((r[0] - p[0]) * (s[1] - r[1]) - (r[1] - p[1]) * (s[0] - r[0])) / d, u = ((r[0] - p[0]) * (q[1] - p[1]) - (r[1] - p[1]) * (q[0] - p[0])) / d;
    if (t > 0.001 && t < 0.999 && u > 0.001 && u < 0.999) { segs[i].ts.push(t); segs[j].ts.push(u); }
  }
  for (const s of segs) {
    const ts = [...new Set(s.ts)].sort((a, b) => a - b);
    let prev = null;
    for (const t of ts) {
      const n = gnode(s.a[0] + (s.b[0] - s.a[0]) * t, s.a[1] + (s.b[1] - s.a[1]) * t, s.rw);
      if (prev != null) link(prev, n);
      prev = n;
    }
  }
  // taxiways that stop short of another way (most stop at the runway edge): join them to it
  const edges = () => { const e = []; G.adj.forEach((l, p) => l.forEach(q => { if (p < q) e.push([p, q]); })); return e; };
  let E = edges();
  const dangling = G.adj.map((l, n) => n).filter(n => G.adj[n].length === 1 && G.rw[n] < 0);
  for (const n of dangling) {
    let best = null;
    for (const [p, q] of E) {
      if (p === n || q === n || G.adj[n].includes(p) || G.adj[n].includes(q)) continue;
      const ax = G.x[p], ay = G.y[p], dx = G.x[q] - ax, dy = G.y[q] - ay, L2 = dx * dx + dy * dy || 1;
      const t = Math.max(0, Math.min(1, ((G.x[n] - ax) * dx + (G.y[n] - ay) * dy) / L2));
      const d = Math.hypot(ax + dx * t - G.x[n], ay + dy * t - G.y[n]);
      const lim = G.rw[p] >= 0 && G.rw[q] >= 0 ? 34 : 14;
      if (d < lim && (!best || d < best.d)) best = { d, p, q, t };
    }
    if (!best) continue;
    const { p, q, t } = best;
    if (t < 0.02) link(n, p); else if (t > 0.98) link(n, q);
    else { const m = gnode(G.x[p] + (G.x[q] - G.x[p]) * t, G.y[p] + (G.y[q] - G.y[p]) * t, G.rw[p] >= 0 && G.rw[q] >= 0 ? G.rw[p] : -1); unlink(p, q); link(p, m); link(m, q); link(n, m); }
    E = edges();
  }
  // the ground network is the biggest connected piece that has a runway in it
  const comp = new Array(G.x.length).fill(-1); let bestC = -1, bestSize = 0;
  for (let s = 0; s < G.x.length; s++) {
    if (comp[s] >= 0) continue;
    const st = [s]; comp[s] = s; let size = 0, rws = 0;
    while (st.length) { const n = st.pop(); size++; if (G.rw[n] >= 0) rws++; for (const m of G.adj[n]) if (comp[m] < 0) { comp[m] = s; st.push(m); } }
    const score = size + (rws ? 1e6 : 0);
    if (score > bestSize) { bestSize = score; bestC = s; }
  }
  for (let n = 0; n < G.x.length; n++) G.main[n] = comp[n] === bestC;
}
const GP = n => ({ x: G.x[n], y: G.y[n] });
function nearestNode(p, offRunway = true) {
  let best = -1, bd = Infinity;
  for (let n = 0; n < G.x.length; n++) {
    if (!G.main[n] || (offRunway && G.rw[n] >= 0)) continue;
    const d = Math.hypot(G.x[n] - p.x, G.y[n] - p.y);
    if (d < bd) { bd = d; best = n; }
  }
  return best;
}
// shortest route (taxiing along a runway costs more than going round it)
function route(from, to) {
  const N = G.x.length, D = new Float64Array(N).fill(Infinity), prev = new Int32Array(N).fill(-1), done = new Uint8Array(N);
  const heap = [[0, from]]; D[from] = 0;
  const push = (d, n) => { heap.push([d, n]); let i = heap.length - 1; while (i) { const p = (i - 1) >> 1; if (heap[p][0] <= heap[i][0]) break; [heap[p], heap[i]] = [heap[i], heap[p]]; i = p; } };
  const pop = () => { const top = heap[0], end = heap.pop(); if (heap.length) { heap[0] = end; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < heap.length && heap[l][0] < heap[m][0]) m = l; if (r < heap.length && heap[r][0] < heap[m][0]) m = r; if (m === i) break; [heap[m], heap[i]] = [heap[i], heap[m]]; i = m; } } return top; };
  while (heap.length) {
    const [d, n] = pop();
    if (done[n]) continue; done[n] = 1;
    if (n === to) break;
    for (const m of G.adj[n]) {
      const w = Math.hypot(G.x[m] - G.x[n], G.y[m] - G.y[n]) * (G.rw[m] >= 0 && G.rw[n] >= 0 ? 8 : 1);
      if (d + w < D[m]) { D[m] = d + w; prev[m] = n; push(d + w, m); }
    }
  }
  if (!isFinite(D[to])) return null;
  const out = []; for (let n = to; n >= 0; n = prev[n]) out.push(n);
  return out.reverse();
}
const along = (r, p) => (p.x - r.a.x) * r.u.x + (p.y - r.a.y) * r.u.y;
const lateral = (r, p) => (p.x - r.a.x) * -r.u.y + (p.y - r.a.y) * r.u.x;
// from a runway node, walk out along the taxiway until well clear of the runway
function walkClear(r, start, from) {
  const out = []; let n = start, prev = from;
  for (let k = 0; k < 8; k++) {
    out.push(n);
    if (Math.abs(lateral(r, GP(n))) > 70 && G.rw[n] < 0) break;
    const next = G.adj[n].filter(m => m !== prev && G.rw[m] < 0).sort((a, b) => Math.abs(lateral(r, GP(b))) - Math.abs(lateral(r, GP(a))))[0];
    if (next == null) break;
    prev = n; n = next;
  }
  return out;
}

// ---------- Runway ends: names, where landings turn off, where departures wait ----------
const ENDS = [];
for (const r of RUNWAYS) {
  const conns = [];
  for (let n = 0; n < G.x.length; n++) if (G.rw[n] === r.i && G.main[n]) for (const m of G.adj[n]) if (G.rw[m] < 0 && G.main[m]) conns.push({ n, m, s: along(r, GP(n)) });
  for (const fwd of [true, false]) {
    const thr = fwd ? r.a : r.b, u = fwd ? r.u : { x: -r.u.x, y: -r.u.y }, hdg = hdgOf(u.x, u.y);
    const sOf = c => fwd ? c.s : r.L - c.s;
    const exits = conns.filter(c => sOf(c) > 650).sort((a, b) => sOf(a) - sOf(b));
    const ex = exits[0] || conns.slice().sort((a, b) => sOf(b) - sOf(a))[0];
    const entries = conns.filter(c => sOf(c) > -40 && sOf(c) < r.L * 0.45).sort((a, b) => sOf(a) - sOf(b));
    const en = entries[0] || conns.slice().sort((a, b) => sOf(a) - sOf(b))[0];
    ENDS.push({
      r, fwd, thr, u, hdg, num: Math.round(hdg / 10) % 36 || 36, sfx: '',
      exit: ex && { at: sOf(ex), clear: walkClear(r, ex.m, ex.n) },
      entry: en && { at: sOf(en), clear: walkClear(r, en.m, en.n) },
    });
  }
}
// parallel runways: L, C and R from the left as you look down them
for (const e of ENDS) {
  const same = ENDS.filter(o => o.num === e.num);
  if (same.length < 2) continue;
  const side = o => o.thr.x * -o.u.y + o.thr.y * o.u.x;
  same.sort((a, b) => side(a) - side(b));
  same.forEach((o, i) => o.sfx = same.length === 2 ? 'LR'[i] : i === 0 ? 'L' : i === same.length - 1 ? 'R' : 'C');
}
for (const e of ENDS) e.name = String(e.num).padStart(2, '0') + e.sfx;
// a landing needs somewhere to turn off; a takeoff needs about a kilometre of runway ahead
const LAND = ENDS.filter(e => e.exit && e.exit.at >= 300 && e.exit.at <= e.r.L);
const DEPART = ENDS.filter(e => e.entry && e.r.L - Math.max(0, e.entry.at) >= 1100);

// ---------- Stands at the gates ----------
const STANDS = {};
for (const [code, x, y, a] of AP.stands || []) {
  const nose = vecOf(a), push = { x: x - nose.x * 45, y: y - nose.y * 45 };
  STANDS[code] = { code, x, y, a, push, node: nearestNode(push), plane: null };
}
// the aircraft OpenStreetMap shows parked at gates make way for the game's own
B.planes = B.planes.filter(([x, y]) => !Object.values(STANDS).some(s => Math.hypot(s.x - x, s.y - y) < 30));
const STAND_LIST = Object.values(STANDS).filter(s => s.node >= 0);

// ---------- Play area: the airport, its runways, and room to hold around them ----------
{
  const xs = [AP.bounds.x0, AP.bounds.x1, ...RUNWAYS.flatMap(r => [r.a.x, r.b.x])], ys = [AP.bounds.y0, AP.bounds.y1, ...RUNWAYS.flatMap(r => [r.a.y, r.b.y])];
  const cx = (Math.min(...xs) + Math.max(...xs)) / 2, cy = (Math.min(...ys) + Math.max(...ys)) / 2;
  const half = Math.max(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys)) / 2;
  var AREA = { x: cx, y: cy, hold: half + 350, spawn: half + 1700 };
  const m = AREA.hold + 380;
  Object.assign(MAP_BOUNDS, { x0: cx - m, y0: cy - m, x1: cx + m, y1: cy + m });
}

// ---------- Drawing aircraft ----------
const layerAc = el('g', { class: 'ac-layer' });
world.insertBefore(layerAc, layerGates);
const layerTop = el('g', { class: 'tag-layer' });
const selRoute = el('path', { class: 'sel-route', d: '', 'vector-effect': 'non-scaling-stroke' }, layerTop);
const rwLabels = ENDS.map(e => {
  const p = { x: e.thr.x - e.u.x * 70, y: e.thr.y - e.u.y * 70 };
  const g = el('g', { class: 'rw-lbl', 'data-end': e.name }, layerTop);
  const z = el('g', { class: 'zs' }, g), w = e.name.length * 5 + 7;
  el('rect', { x: -w / 2, y: -6, width: w, height: 12, rx: 3 }, z);
  el('text', { y: .4 }, z).textContent = e.name;
  return { g, p };
});
let acK = 2.1, tagS = 1;
function sizeAircraft() {
  acK = Math.max(2.1, 22 / (16 * view.k));      // real size (~34 m) or at least 22 px long
  tagS = 1.9 / view.k;                            // tags and runway names stay a readable size on screen
  const r = committed ? -committed.r / deg : 0;
  for (const l of rwLabels) l.g.setAttribute('transform', `translate(${l.p.x} ${l.p.y})${r ? ` rotate(${r})` : ''} scale(${tagS})`);
  for (const p of planes) { p.drawnK = -1; }
  drawPlanes();
}

// ---------- The game ----------
const NAMES = ['Bluejay', 'Cardinal', 'Northwind', 'Lakeshore', 'Summit', 'Harbor', 'Prairie', 'Redtail', 'Skylark', 'Juniper', 'Comet', 'Falcon', 'Meridian', 'Osprey', 'Kestrel', 'Tailwind'];
let planes = [], selected = null, nextId = 1;
const game = { on: false, paused: false, speed: 1, t: 0, score: 0, strikes: 0, handled: 0, nextSpawn: 0, over: false };
function callsign() {
  for (;;) { const c = NAMES[Math.floor(Math.random() * NAMES.length)] + ' ' + Math.floor(rand(10, 990)); if (!planes.some(p => p.cs === c)) return c; }
}
function makePlane(kind, at, h) {
  const p = { id: String(nextId++), cs: callsign(), kind, x: at.x, y: at.y, h, v: 0, air: false, state: '', path: [], pi: 0, fuel: 0, t: 0, wait: 0, blocked: 0, ghost: 0 };
  const g = el('g', { class: 'ac', 'data-plane': p.id }, layerAc);
  const zs = el('g', { class: 'zs-s' }, g);
  p.shadowEl = el('path', { class: 'shadow', d: PLANE_D }, zs);
  p.bodyEl = el('g', {}, zs);
  el('circle', { class: 'halo', r: 12 }, p.bodyEl);
  el('circle', { class: 'hit', r: 13 }, p.bodyEl);
  el('path', { class: 'fuse', d: PLANE_D }, p.bodyEl);
  const tag = el('g', { class: 'tag', 'data-plane': p.id }, layerTop), tz = el('g', { class: 'zs' }, tag);
  p.tagRect = el('rect', { y: -6.5, height: 13, rx: 4 }, tz);
  p.tagCs = el('text', { class: 'cs', y: -1.6 }, tz);
  p.tagSt = el('text', { class: 'st', y: 3.9 }, tz);
  p.el = g; p.tagEl = tag; p.drawnK = -1;
  planes.push(p);
  return p;
}
function removePlane(p) {
  p.el.remove(); p.tagEl.remove();
  planes = planes.filter(q => q !== p);
  if (p.stand) p.stand.plane = null;
  if (selected === p) select(null);
}
// what a plane is doing, in a word or two, and whether it's waiting on you
const NEEDS = { holding: 1, inbound: 1, vacated: 1, ready: 1, holdshort: 1 };
function statusOf(p) {
  switch (p.state) {
    case 'inbound': return ['Inbound', 'Arriving · fuel ' + mmss(p.fuel)];
    case 'holding': return ['Holding', 'Holding · fuel ' + mmss(p.fuel)];
    case 'missed': return ['Go-around', 'Going around · fuel ' + mmss(p.fuel)];
    case 'approach': case 'final': return ['Land ' + p.end.name, `Cleared to land ${p.end.name} · fuel ${mmss(p.fuel)}`];
    case 'rollout': return ['Landing', 'Landing on ' + p.end.name];
    case 'vacated': return ['Needs gate', 'Off the runway · needs a gate'];
    case 'taxiin': return ['To ' + p.stand.code, 'Taxiing to gate ' + p.stand.code];
    case 'boarding': return ['Boarding', `Boarding at ${p.stand.code} · ready in ${mmss(p.t)}`];
    case 'ready': return ['Ready', `Ready at ${p.stand.code} · waiting ${mmss(p.wait)}`];
    case 'pushback': case 'taxiout': return ['To ' + p.end.name, `Taxiing to runway ${p.end.name}`];
    case 'holdshort': return ['Hold ' + p.end.name, `Holding short of ${p.end.name}`];
    case 'lineup': case 'takeoff': return ['Departing', 'Taking off from ' + p.end.name];
    case 'climb': return ['Departed', 'Departed'];
  }
  return ['', ''];
}
const urgent = p => p.fuel > 0 && p.fuel < 30 && !p.landed;

// ---------- Runway use ----------
// a runway is in use while anything is on it, or lining up and rolling for takeoff
function onRunway(p, r) {
  if (p.air) return false;
  const s = along(r, p), l = lateral(r, p);
  return Math.abs(l) < 42 && s > -40 && s < r.L + 40;
}
const runwayBusy = (r, except) => planes.some(q => q !== except && (onRunway(q, r) || ((q.state === 'lineup' || q.state === 'takeoff') && q.end.r === r)));
const finalOn = (r, within, except) => planes.find(q => q !== except && q.state === 'final' && q.end.r === r && -along2(q.end, q) < within);
const along2 = (e, p) => (p.x - e.thr.x) * e.u.x + (p.y - e.thr.y) * e.u.y;
const lat2 = (e, p) => (p.x - e.thr.x) * -e.u.y + (p.y - e.thr.y) * e.u.x;

// ---------- Commands ----------
function clearToLand(p, e) {
  p.end = e; p.state = 'approach'; buzz();
  toast(`${p.cs}, cleared to land runway ${e.name}`);
  refreshUI(true);
}
function goAround(p, why) {
  p.state = 'missed'; p.t = 0; game.score = Math.max(0, game.score - 50);
  toast(`${p.cs} going around${why ? ' — ' + why : ''}`);
  refreshUI(true);
}
function assignGate(p, s) {
  if (!s || s.plane) return false;
  if (p.stand) p.stand.plane = null;
  const from = nearestNode(p, false), r = route(from, s.node);
  p.stand = s; s.plane = p;
  p.path = [...(r || [s.node]).map(GP), s.push, { x: s.x, y: s.y }]; p.pi = 0;
  p.state = 'taxiin'; buzz();
  toast(`${p.cs}, taxi to gate ${s.code}`);
  refreshUI(true);
  return true;
}
function nearestFreeStand(p) {
  let best = null, bd = Infinity;
  for (const s of STAND_LIST) if (!s.plane) { const d = dist(p, s); if (d < bd) { bd = d; best = s; } }
  return best;
}
function pushTo(p, e) {
  const r = route(p.stand.node, e.entry.clear[e.entry.clear.length - 1]);
  p.end = e; p.state = 'pushback'; p.score0 = p.wait;
  p.path = [p.stand.push]; p.pi = 0;
  p.taxi = [...(r || []).map(GP)];
  buzz(); toast(`${p.cs}, push back, taxi to runway ${e.name}`);
  refreshUI(true);
}
function clearTakeoff(p) {
  const e = p.end;
  if (runwayBusy(e.r, p)) {
    // the pilot catches it after the first time; only the first wrong clearance is a strike
    if (p.incursion) toast(`${p.cs}: unable, runway ${e.name} is still in use`);
    else { p.incursion = true; strike(`Runway incursion! ${e.name} wasn't clear`); }
    return;
  }
  p.state = 'lineup';
  const line = { x: e.thr.x + e.u.x * Math.max(0, e.entry.at), y: e.thr.y + e.u.y * Math.max(0, e.entry.at) };
  p.path = [...e.entry.clear.slice().reverse().slice(1).map(GP), line]; p.pi = 0;
  buzz(); toast(`${p.cs}, runway ${e.name}, cleared for takeoff`);
  refreshUI(true);
}
function strike(msg) {
  game.strikes++; buzz(40);
  toast(msg + (game.strikes < 3 ? ` · strike ${game.strikes} of 3` : ''));
  refreshUI(true);
  if (game.strikes >= 3) endGame(msg);
}

// ---------- Moving things ----------
function steer(p, target, rate, dt) {
  const want = hdgOf(target.x - p.x, target.y - p.y);
  p.h = (p.h + Math.max(-rate * dt, Math.min(rate * dt, angDiff(p.h, want))) + 360) % 360;
}
function fly(p, dt) { const v = vecOf(p.h); p.x += v.x * p.v * dt; p.y += v.y * p.v * dt; }
// along the path; returns true at its end. Pushback moves tail first.
function follow(p, dt, v, reverse) {
  let left = v * dt;
  while (left > 0 && p.pi < p.path.length) {
    const t = p.path[p.pi], d = dist(p, t);
    if (d < 0.5) { p.pi++; continue; }
    const want = reverse ? hdgOf(p.x - t.x, p.y - t.y) : hdgOf(t.x - p.x, t.y - p.y);
    if (d > 3) p.h = (p.h + Math.max(-140 * dt, Math.min(140 * dt, angDiff(p.h, want))) + 360) % 360;
    const step = Math.min(left, d);
    p.x += (t.x - p.x) / d * step; p.y += (t.y - p.y) / d * step; left -= step;
    if (step === d) p.pi++;
  }
  return p.pi >= p.path.length;
}
// on the ground, stop for a plane just ahead going the same way, or standing still. Planes queue
// behind one waiting at the same runway; one stuck a while behind a parked plane squeezes past.
// (Planes going opposite ways pass each other: the taxiways here are drawn as single lines.)
const PARKED = { boarding: 1, ready: 1 };
function blockedAhead(p, dt) {
  if (p.ghost > 0) { p.ghost -= dt; return false; }
  const f = vecOf(p.h);
  for (const q of planes) {
    if (q === p || q.air || PARKED[q.state]) continue;
    const dx = q.x - p.x, dy = q.y - p.y, d = Math.hypot(dx, dy);
    if (d < 58 && d > 1 && (dx * f.x + dy * f.y) / d > 0.75) {
      const still = q.state === 'holdshort' || q.state === 'vacated' || q.blocked > 0;
      if (!still && Math.abs(angDiff(p.h, q.h)) > 70) continue;
      p.blocked += dt; p.blockedBy = q;
      const queue = q.state === 'holdshort' && p.end === q.end;
      const patience = q.blockedBy === p ? 1 : queue ? Infinity : 6;
      if (p.blocked > patience) { p.blocked = 0; p.blockedBy = null; p.ghost = 4; return false; }
      return true;
    }
  }
  p.blocked = 0; p.blockedBy = null; return false;
}
function step(dt) {
  game.t += dt;
  if (game.t >= game.nextSpawn) spawnArrival();
  for (const p of planes.slice()) {
    if (!p.landed && p.kind === 'arr') {
      p.fuel -= dt;
      if (p.fuel <= 0) { removePlane(p); strike(`${p.cs} ran out of fuel and diverted`); if (game.over) return; continue; }
    }
    switch (p.state) {
      case 'inbound':
        p.v = 110; steer(p, p.fix, 30, dt); fly(p, dt);
        if (dist(p, p.fix) < 320) p.state = 'holding';
        break;
      case 'holding': {
        p.v = 100;
        const a = Math.atan2(p.y - p.hold.y, p.x - p.hold.x) + 0.7;
        steer(p, { x: p.hold.x + Math.cos(a) * 300, y: p.hold.y + Math.sin(a) * 300 }, 34, dt); fly(p, dt);
        break;
      }
      case 'missed':
        p.v = 110; p.t += dt;
        if (p.t > 8) steer(p, p.fix, 30, dt);
        fly(p, dt);
        if (p.t > 8 && dist(p, p.fix) < 320) p.state = 'holding';
        break;
      case 'approach': {
        const e = p.end, faf = { x: e.thr.x - e.u.x * 1400, y: e.thr.y - e.u.y * 1400 };
        p.v = 105; steer(p, faf, 32, dt); fly(p, dt);
        const s = along2(e, p), aligned = Math.abs(angDiff(p.h, e.hdg)) < 50 && Math.abs(lat2(e, p)) < 350 && s < -400 && s > -2600;
        if (dist(p, faf) < 450 || aligned) p.state = 'final';
        break;
      }
      case 'final': {
        const e = p.end, s = along2(e, p);
        p.v += (75 - p.v) * Math.min(1, dt * .6);
        steer(p, { x: e.thr.x + e.u.x * (s + 650), y: e.thr.y + e.u.y * (s + 650) }, 26, dt); fly(p, dt);
        if (s > -1100 && s < -40 && runwayBusy(e.r, p)) { goAround(p, `runway ${e.name} in use`); break; }
        if (Math.abs(lat2(e, p)) > 900 || s < -3500) { p.state = 'approach'; break; }
        if (s >= 0) {
          const l = lat2(e, p); p.x += e.u.y * l; p.y -= e.u.x * l;   // onto the centreline
          p.h = e.hdg; p.air = false; p.landed = true; p.state = 'rollout';
          const ex = e.exit, at = { x: e.thr.x + e.u.x * ex.at, y: e.thr.y + e.u.y * ex.at };
          p.path = [at, ...ex.clear.map(GP)]; p.pi = 0;
          game.score += 50; refreshUI(true);
        }
        break;
      }
      case 'rollout': {
        const toExit = p.pi === 0 ? dist(p, p.path[0]) : 0;
        p.v = p.pi === 0 ? Math.max(16, Math.min(p.v - 1.5 * dt, Math.sqrt(16 * 16 + 2 * 3.5 * toExit))) : 20;
        // once off the runway centreline, wait behind anyone ahead (a plane left waiting for a gate blocks the turn-off)
        if ((p.pi === 0 || !blockedAhead(p, dt)) && follow(p, dt, p.v)) { p.state = 'vacated'; p.wait = 0; refreshUI(true); }
        break;
      }
      case 'vacated': p.wait += dt; break;
      case 'taxiin':
        if (p.pi >= p.path.length - 2) { if (follow(p, dt, 8)) arriveAtGate(p); }
        else if (!blockedAhead(p, dt)) follow(p, dt, 24);
        break;
      case 'boarding':
        p.t -= dt;
        if (p.t <= 0) { p.state = 'ready'; p.wait = 0; refreshUI(true); }
        break;
      case 'ready': p.wait += dt; break;
      case 'pushback':
        if (follow(p, dt, 9, true)) { p.state = 'taxiout'; p.path = p.taxi; p.pi = 0; if (p.stand) { p.stand.plane = null; p.stand = null; } refreshUI(true); }
        break;
      case 'taxiout':
        if (!blockedAhead(p, dt) && follow(p, dt, 24)) { p.state = 'holdshort'; p.wait = 0; refreshUI(true); }
        break;
      case 'holdshort': p.wait += dt; break;
      case 'lineup':
        if (follow(p, dt, 15)) {
          const d = angDiff(p.h, p.end.hdg);
          p.h = (p.h + Math.max(-120 * dt, Math.min(120 * dt, d)) + 360) % 360;
          if (Math.abs(d) < 1) { p.h = p.end.hdg; p.state = 'takeoff'; p.v = 0; }
        }
        break;
      case 'takeoff':
        p.v += 5 * dt; fly(p, dt);
        if (p.v > 80) { p.state = 'climb'; p.air = true; refreshUI(true); }
        break;
      case 'climb':
        p.v = Math.min(130, p.v + 6 * dt); fly(p, dt); p.t += dt;
        if (dist(p, AREA) > AREA.spawn) {
          game.handled++; game.score += 100 + (p.score0 < 30 ? 50 : 0);
          removePlane(p); refreshUI(true);
        }
        break;
    }
  }
}
function arriveAtGate(p) {
  p.state = 'boarding'; p.kind = 'dep'; p.t = rand(18, 32); p.h = p.stand.a; p.v = 0;
  game.handled++; game.score += 100;
  refreshUI(true);
}
function spawnArrival() {
  const mins = game.t / 60;
  game.nextSpawn = game.t + (Math.max(13, 32 - mins * 3) + rand(-4, 4)) * (RUNWAYS.length > 1 ? 1 : 2.2);   // one runway can take fewer
  if (planes.filter(p => p.kind === 'arr' && !p.landed).length >= 9) return;
  const b = rand(0, Math.PI * 2), dir = { x: Math.cos(b), y: Math.sin(b) };
  const at = { x: AREA.x + dir.x * AREA.spawn, y: AREA.y + dir.y * AREA.spawn };
  const p = makePlane('arr', at, hdgOf(-dir.x, -dir.y));
  p.air = true; p.v = 110; p.state = 'inbound'; p.fuel = rand(130, 175) - Math.min(50, mins * 5);
  p.hold = { x: AREA.x + dir.x * AREA.hold, y: AREA.y + dir.y * AREA.hold };
  p.fix = p.hold;
  refreshUI(true);
}
function parkDeparture(board) {
  const free = STAND_LIST.filter(s => !s.plane);
  if (!free.length) return;
  const s = free[Math.floor(Math.random() * free.length)];
  const p = makePlane('dep', s, s.a);
  p.stand = s; s.plane = p; p.landed = true; p.state = 'boarding'; p.t = board;
}

// ---------- Drawing ----------
function drawPlanes() {
  const r = committed ? -committed.r / deg : 0;
  for (const p of planes) {
    p.el.setAttribute('transform', `translate(${p.x.toFixed(2)} ${p.y.toFixed(2)})`);
    if (p.drawnH !== p.h || p.drawnK !== acK) {
      p.drawnH = p.h; p.drawnK = acK;
      p.bodyEl.setAttribute('transform', `rotate(${p.h.toFixed(1)}) scale(${acK.toFixed(3)})`);
      const o = acK * 2.6;
      p.shadowEl.setAttribute('transform', `translate(${o} ${o * 1.4}) rotate(${p.h.toFixed(1)}) scale(${acK.toFixed(3)})`);
    }
    const cls = 'ac ' + (p.air ? 'air ' : '') + p.kind + (selected === p ? ' sel' : '') + (urgent(p) ? ' warn' : '') + (NEEDS[p.state] ? ' need' : '');
    if (p.cls !== cls) { p.cls = cls; p.el.setAttribute('class', cls); p.tagEl.setAttribute('class', cls.replace(/^ac/, 'tag')); }
    // the tag sits beside the plane, upright
    const off = 8 * acK / tagS + 3;
    p.tagEl.setAttribute('transform', `translate(${p.x.toFixed(2)} ${p.y.toFixed(2)})${r ? ` rotate(${r})` : ''} scale(${tagS})`);
    const [short] = statusOf(p), st = NEEDS[p.state] && p.fuel > 0 && !p.landed ? mmss(p.fuel) + ' fuel' : short;
    const key = p.cs + '|' + st + '|' + off.toFixed(1);
    if (p.tagKey !== key) {
      p.tagKey = key;
      p.tagCs.textContent = p.cs; p.tagSt.textContent = st;
      const w = Math.max(p.cs.length * 3.7, st.length * 2.9) + 7;
      p.tagRect.setAttribute('x', off); p.tagRect.setAttribute('width', w);
      p.tagCs.setAttribute('x', off + 3.5); p.tagSt.setAttribute('x', off + 3.5);
    }
  }
  drawSelRoute();
}
function drawSelRoute() {
  const p = selected;
  let pts = [];
  if (p) {
    if (p.state === 'approach' || p.state === 'final') { const e = p.end; pts = [p, ...(p.state === 'approach' ? [{ x: e.thr.x - e.u.x * 1400, y: e.thr.y - e.u.y * 1400 }] : []), e.thr]; }
    else if (['rollout', 'taxiin', 'taxiout', 'lineup'].includes(p.state)) pts = [p, ...p.path.slice(p.pi)];
    else if (p.state === 'pushback') pts = [p, ...p.path.slice(p.pi), ...p.taxi];
  }
  selRoute.setAttribute('d', pts.length > 1 ? 'M' + pts.map(q => q.x.toFixed(1) + ' ' + q.y.toFixed(1)).join('L') : '');
}

// ---------- Selecting, tapping ----------
function select(p) {
  selected = p;
  svg.classList.toggle('pick', !!p && (p.state === 'vacated' || p.state === 'taxiin'));
  refreshUI(true); drawPlanes();
}
function markGates() {
  for (const g of GATES) {
    const s = STANDS[g.code];
    g.el.classList.toggle('busy', !s || !!s.plane);
    g.el.classList.toggle('free', !!s && !s.plane);
  }
}
function nearestPlane(cx, cy, maxPx) {
  let best = null, bd = maxPx;
  for (const p of planes) { const q = toScr(p), d = Math.hypot(q.x - cx, q.y - cy); if (d < bd) { bd = d; best = p; } }
  return best;
}
hooks.tap.push((t, e) => {
  if (!game.on) return false;
  const id = t && t.dataset.plane, p = id ? planes.find(q => q.id === id) : nearestPlane(e.clientX, e.clientY, 26);
  if (p) { buzz(5); select(selected === p ? null : p); return true; }
  if (t && t.dataset.gate && selected && (selected.state === 'vacated' || selected.state === 'taxiin')) {
    const s = STANDS[t.dataset.gate];
    if (s && !s.plane) assignGate(selected, s); else toast(`Gate ${t.dataset.gate} is taken`);
    return true;
  }
  if (!t && selected) select(null);
  return false;
});

// ---------- The panel: the selected flight's commands, and flights waiting on you ----------
const cmdEl = $('#cmd'), stripsEl = $('#strips');
let cmdSig = '', stripSig = '';
function actionsFor(p) {
  const A = [];
  if (['inbound', 'holding', 'missed'].includes(p.state)) for (const e of LAND) A.push([`land:${ENDS.indexOf(e)}`, `Land ${e.name}`, runwayBusy(e.r) ? 'busy' : '']);
  if (p.state === 'approach' || p.state === 'final') A.push(['around', 'Go around', '']);
  if (p.state === 'vacated' || p.state === 'taxiin') A.push(['gate', 'Nearest free gate', '']);
  if (p.state === 'ready') for (const e of DEPART) A.push([`push:${ENDS.indexOf(e)}`, `Push · runway ${e.name}`, '']);
  if (p.state === 'holdshort') A.push(['takeoff', `Cleared for takeoff ${p.end.name}`, runwayBusy(p.end.r, p) ? 'busy' : finalOn(p.end.r, 1300, p) ? 'traffic' : 'go']);
  return A;
}
const HINT = {
  inbound: 'Pick a runway to land on. Keep an eye on the fuel.', holding: 'Pick a runway to land on. Keep an eye on the fuel.', missed: 'Clear it to land again when the runway is free.',
  vacated: 'Tap a free gate on the map, or send it to the nearest one.', taxiin: 'Tap another free gate to change.',
  ready: 'Pick a runway. It pushes back and taxis to wait short of it.',
  holdshort: 'Clear it only when the runway is empty. Arrivals close in will have to go around.',
};
function refreshUI(force) {
  markGates();
  $('#score').textContent = game.score;
  $('#handled').textContent = game.handled;
  $('#clock').textContent = mmss(game.t);
  $('#strikes').innerHTML = [0, 1, 2].map(i => `<i class="${i < game.strikes ? 'on' : ''}"></i>`).join('');
  $('#strikes').setAttribute('aria-label', `${game.strikes} of 3 strikes`);
  // command card
  const p = selected;
  if (p && !planes.includes(p)) selected = null;
  svg.classList.toggle('pick', !!selected && (selected.state === 'vacated' || selected.state === 'taxiin'));
  if (!selected) { cmdEl.hidden = true; cmdSig = ''; }
  else {
    cmdEl.hidden = false;
    const A = actionsFor(p), sig = p.id + '|' + p.state + '|' + A.map(a => a.join(':')).join(',');
    if (sig !== cmdSig || force === 'all') {
      cmdSig = sig;
      cmdEl.innerHTML = `<div class="cmd-head"><span class="chip ${p.kind}${p.air ? ' air' : ''}"></span><div class="grow"><b>${esc(p.cs)}</b><span class="st" id="cmdSt"></span></div>
          <button class="iconbtn" id="cmdX" aria-label="Close">×</button></div>
        ${p.kind === 'arr' && !p.landed ? '<div class="fuel"><i id="cmdFuel"></i></div>' : ''}
        ${A.length ? `<div class="acts">${A.map(([k, label, f]) => `<button class="act ${f}" data-act="${k}">${esc(label)}${f === 'busy' ? '<small>runway in use</small>' : f === 'traffic' ? '<small>arrival close in</small>' : ''}</button>`).join('')}</div>` : ''}
        ${HINT[p.state] ? `<p class="hint">${HINT[p.state]}</p>` : ''}`;
      $('#cmdX').onclick = () => select(null);
      cmdEl.querySelectorAll('[data-act]').forEach(b => b.onclick = () => command(p, b.dataset.act));
    }
    $('#cmdSt').textContent = statusOf(p)[1];
    const f = $('#cmdFuel'); if (f) { f.style.width = Math.max(0, Math.min(100, p.fuel / 175 * 100)) + '%'; f.classList.toggle('low', p.fuel < 30); }
  }
  // strips: flights waiting on you, most urgent first
  const waiting = planes.filter(q => NEEDS[q.state]).sort((a, b) => (a.landed ? 999 : a.fuel) - (b.landed ? 999 : b.fuel) || b.wait - a.wait);
  const sig = waiting.map(q => q.id + q.state).join(',') + '|' + (selected ? selected.id : '');
  if (sig !== stripSig) {
    stripSig = sig;
    stripsEl.innerHTML = waiting.length ? waiting.map(q => `<button class="strip ${q.kind}${q === selected ? ' sel' : ''}" data-id="${q.id}"><span class="chip ${q.kind}${q.air ? ' air' : ''}"></span><b>${esc(q.cs)}</b><span class="what">${esc(statusOf(q)[0])}</span><em data-t="${q.id}"></em></button>`).join('')
      : `<p class="calm">${game.on ? 'All quiet. Nothing is waiting on you.' : ''}</p>`;
    stripsEl.querySelectorAll('[data-id]').forEach(b => b.onclick = () => {
      const q = planes.find(x => x.id === b.dataset.id); if (!q) return;
      buzz(5); select(q);
      const R = visibleRect(); animateTo(viewAt(Math.max(view.k, 0.9), view.r, { x: q.x, y: q.y }, (R.l + R.r) / 2, (R.t + R.b) / 2));
    });
  }
  for (const q of waiting) {
    const t = stripsEl.querySelector(`[data-t="${q.id}"]`); if (!t) continue;
    t.textContent = !q.landed ? mmss(q.fuel) : mmss(q.wait);
    t.classList.toggle('low', urgent(q));
  }
}
function command(p, act) {
  if (!planes.includes(p)) return;
  const [k, i] = act.split(':'), e = ENDS[+i];
  if (k === 'land' && ['inbound', 'holding', 'missed'].includes(p.state)) clearToLand(p, e);
  else if (k === 'around') { p.state = 'missed'; p.t = 0; toast(`${p.cs}, go around`); refreshUI(true); }
  else if (k === 'gate') { const s = nearestFreeStand(p); if (s) assignGate(p, s); else toast('No free gates'); }
  else if (k === 'push' && p.state === 'ready') pushTo(p, e);
  else if (k === 'takeoff' && p.state === 'holdshort') clearTakeoff(p);
  select(planes.includes(p) ? p : null);
}

// ---------- Start, pause, game over ----------
const overlay = $('#overlay');
const bestKey = 'cw.tower.best.' + AP.code;
const best = () => { try { return +localStorage.getItem(bestKey) || 0; } catch (e) { return 0; } };
function showOverlay(html) { overlay.querySelector('.card').innerHTML = html; fade(overlay, true); }
function intro() {
  showOverlay(`<p class="eyebrow">Clearway Tower · ${esc(AP.code)}</p><h1>You're in the tower</h1>
    <p>Run the ground and runways at ${esc(AP.name)}.</p>
    <ol>
      <li><b>Arrivals</b> hold until you pick a runway for them. Watch their fuel.</li>
      <li>Once a plane is off the runway, <b>tap a free gate number</b> to send it there.</li>
      <li>After boarding, <b>push it back</b> to a runway and <b>clear it for takeoff</b>, but only when the runway is empty.</li>
    </ol>
    <p class="s">A diversion or a runway incursion is a strike. Three strikes ends your shift.${best() ? ` Best here: <b>${best()}</b>.` : ''}</p>
    <button class="big" id="go">Start shift</button>`);
  $('#go').onclick = startGame;
}
function startGame() {
  for (const p of planes.slice()) removePlane(p);
  Object.assign(game, { on: true, paused: false, t: 0, score: 0, strikes: 0, handled: 0, nextSpawn: 3, over: false });
  parkDeparture(5); parkDeparture(22); parkDeparture(45);
  fade(overlay, false); setPaused(false);
  refreshUI('all');
}
function endGame(why) {
  game.on = false; game.over = true;
  const b = best(); if (game.score > b) try { localStorage.setItem(bestKey, game.score); } catch (e) {}
  showOverlay(`<p class="eyebrow">Shift over · ${mmss(game.t)}</p><h1>${game.score} points</h1>
    <p>${esc(why)}.</p><p>You handled <b>${game.handled}</b> movements.${game.score > b ? ' That’s a new best here.' : b ? ` Best here: <b>${b}</b>.` : ''}</p>
    <button class="big" id="go">Start a new shift</button>`);
  $('#go').onclick = startGame;
}
function setPaused(on) {
  game.paused = on;
  $('#pause').innerHTML = on ? icon('play') : icon('pause');
  $('#pause').setAttribute('aria-label', on ? 'Resume' : 'Pause');
  document.body.classList.toggle('paused', on && game.on);
}
$('#pause').onclick = () => { if (game.on) { buzz(5); setPaused(!game.paused); } };
$('#speed').onclick = () => { buzz(5); game.speed = game.speed === 1 ? 2 : 1; $('#speed').textContent = game.speed + '×'; };
document.addEventListener('visibilitychange', () => { if (document.hidden && game.on) setPaused(true); });
addEventListener('keydown', e => { if (e.key === ' ' && game.on && e.target === document.body) { e.preventDefault(); setPaused(!game.paused); } });

let lastT = performance.now(), uiT = 0;
function tick(now) {
  const dt = Math.min(0.1, (now - lastT) / 1000); lastT = now;
  if (game.on && !game.paused) {
    let left = dt * game.speed;
    while (left > 0 && game.on) { const d = Math.min(0.05, left); step(d); left -= d; }
    uiT += dt; if (uiT > 0.25) { uiT = 0; refreshUI(); }
  }
  if (planes.length) drawPlanes();
  requestAnimationFrame(tick);
}
hooks.render.push(sizeAircraft);
window.__tower = {   // for automated play-testing
  game, ENDS, STANDS, G, get planes() { return planes; }, command, select, step };
requestAnimationFrame(tick);
chromeCache = null; cacheView = null; fitAll(true);
refreshUI('all');
intro();
})();
</script>
</body>
</html>
