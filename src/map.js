// ---------- Active airport ----------
const AP = AIRPORTS[ACTIVE_AIRPORT] || AIRPORTS[Object.keys(AIRPORTS)[0]];
const MAP_BOUNDS = { ...AP.bounds };
// other layers (the tower game) hook in here: run after each re-layout of the overlay, and get first say on taps
const hooks = { render: [], tap: [] };
// Concourses in display order, each with a colour and a badge shape
const CONS = AP.concourses.map(c => c.id);
const gnumOf = g => parseInt(g.replace(/^\D+/, ''), 10) || 0;
// Gates: their number and where the number sits on the map
const GATES = AP.gates.map(([code, x, y]) => ({ code, con: code[0], x, y }));
// Gate ranges by concourse, e.g. "A1–A34"
const CON_RANGE = Object.fromEntries(CONS.map(c => {
  const gs = GATES.filter(g => g.con === c).map(g => g.code).sort((a, b) => gnumOf(a) - gnumOf(b));
  return [c, gs.length ? `${gs[0]}–${gs[gs.length - 1]}` : ''];
}));

// Window size, read once and on resize: reading innerWidth while the overlay is dirty forces a
// full layout of the SVG (every label) mid-gesture.
let vw = innerWidth, vh = innerHeight;
addEventListener('resize', () => { vw = innerWidth; vh = innerHeight; });
// ---------- Shapes ----------
// Material 3 Expressive-style concourse badges: circle, clover, cookie…
function blob(n, amp, R = 10) {
  if (!n) return `M${R} 0A${R} ${R} 0 1 1 ${-R} 0A${R} ${R} 0 1 1 ${R} 0Z`;
  let d = '';
  for (let i = 0; i <= 144; i++) {
    const th = i / 144 * Math.PI * 2, r = R * (1 + amp * Math.cos(n * th)) / (1 + amp);
    d += (i ? 'L' : 'M') + (r * Math.sin(th)).toFixed(2) + ' ' + (-r * Math.cos(th)).toFixed(2);
  }
  return d + 'Z';
}
const CON_SHAPE = Object.fromEntries(AP.concourses.map(c => [c.id, blob(...(c.shape || [0, 0]))]));
// Each concourse's colour, light and dark, and the rules that use it
{
  const light = AP.concourses.map(c => `--con${c.id}: ${c.color[0]};`).join(' '), dark = AP.concourses.map(c => `--con${c.id}: ${c.color[1]};`).join(' ');
  const rules = AP.concourses.map(({ id }) => `.con-shape.${id}, .gate.${id} .pill { fill: var(--con${id}); }`).join('\n');
  const st = document.createElement('style');
  st.textContent = `:root { ${light} }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { ${dark} } }
:root[data-theme="dark"] { ${dark} }
${rules}`;
  document.head.appendChild(st);
}
const badgeSvg = (con, size = 30) =>
  `<svg class="badge" viewBox="-11 -11 22 22" width="${size}" height="${size}" aria-hidden="true"><path class="con-shape ${con}" d="${CON_SHAPE[con]}" transform="scale(1.05)" style="fill:var(--con${con})"/><text y=".6">${con}</text></svg>`;
const icon = name => `<svg class="ico"><use href="#i-${name}"/></svg>`;
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const buzz = (ms = 8) => { try { navigator.vibrate && navigator.vibrate(ms); } catch (e) {} };
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
// Show or hide a popover, letting it fade out rather than vanish
function fade(node, show) {
  clearTimeout(node._fade);
  if (show) { node.classList.remove('leaving'); node.hidden = false; return; }
  if (node.hidden || reduced) { node.hidden = true; node.classList.remove('leaving'); return; }
  node.classList.add('leaving');
  node._fade = setTimeout(() => { node.hidden = true; node.classList.remove('leaving'); }, 160);
}
function stagger(node, sel) { node.querySelectorAll(sel).forEach((r, i) => r.style.setProperty('--n', Math.min(i, 10))); }
const toastEl = $('#toast');
function toast(msg) {
  toastEl.textContent = msg; fade(toastEl, true);
  clearTimeout(toast.t); toast.t = setTimeout(() => fade(toastEl, false), 2200);
}

// ---------- Draw the map (map units are metres; #world is scaled to screen pixels) ----------
const svg = $('#map'), world = $('#world');
const el = (tag, attrs = {}, parent = world) => {
  const n = document.createElementNS(NS, tag);
  for (const k in attrs) n.setAttribute(k, attrs[k]);
  parent.appendChild(n); return n;
};

// Base map from OpenStreetMap (see tools/build_basemap.py), painted on a <canvas>: shapes are
// parsed once into Path2D objects, so a redraw is a few milliseconds of GPU drawing with no
// SVG layout. Gate numbers and labels stay in the light SVG overlay on top.
const B = AP.basemap;
const baseCanvas = $('#base'), bctx = baseCanvas.getContext('2d', { alpha: false });
const P2 = {};
for (const k in B) if (typeof B[k] === 'string' && B[k]) P2[k] = new Path2D(B[k]);
const PLANE_D = 'M0 -8C1 -8 1.3 -6.5 1.3 -5L1.3 -1.5L8 2L8 3.4L1.3 1.6L1.1 5.2L3 6.6L3 7.6L0 7L-3 7.6L-3 6.6L-1.1 5.2L-1.3 1.6L-8 3.4L-8 2L-1.3 -1.5L-1.3 -5C-1.3 -6.5 -1 -8 0 -8Z';
const planePath = new Path2D(PLANE_D);
const HALLS = AP.halls, HALL_PATH = {}, HALL_CURB = {};
if (HALLS) for (const [f, key] of [['up', 'tix'], ['lo', 'bag']]) {
  const p = new Path2D(); HALLS[key].forEach(([x, y], i) => i ? p.lineTo(x, y) : p.moveTo(x, y)); p.closePath(); HALL_PATH[f] = p;
}
let pal = null, floor = 'up';
$('#map').dataset.floor = 'up';
function hexMix(a, b, t) {   // a over b, t = amount of a
  const n = h => { h = h.replace('#', ''); return [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16)); };
  const x = n(a), y = n(b);
  return 'rgb(' + x.map((v, i) => Math.round(v * t + y[i] * (1 - t))).join(',') + ')';
}
function readPalette() {
  const cs = getComputedStyle(document.documentElement), g = k => cs.getPropertyValue('--' + k).trim();
  const p = {};
  for (const k of ['map', 'grass', 'wood', 'water', 'apron-pave', 'lot', 'taxi', 'runway', 'taxi-line', 'road-case', 'road-fill',
    'mway-case', 'mway-fill', 'rail', 'other-bldg', 'garage', 'bldg', 'bldg-edge', 'bldg-shadow', 'plane', 'bridge', 'term-closed',
    'route', 'corridor', 'stairs', 'runway-mark', 'hall-up', 'hall-up-edge', 'hall-up-text', 'hall-lo', 'hall-lo-edge', 'hall-lo-text', 'curb', 'zebra', 'glass', 'counter', 'carousel', 'carousel-belt', 'door', ...CONS.map(c => 'con' + c)]) p[k] = g(k);
  const tint = parseFloat(g('con-tint')) || .1;
  for (const c of CONS) p['term' + c] = /^#[0-9a-f]{6}$/i.test(p['con' + c]) && /^#[0-9a-f]{6}$/i.test(p.bldg) ? hexMix(p['con' + c], p.bldg, tint) : p.bldg;
  return p;
}
const repaint = () => { pal = null; if (committed) render(true); };
matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', repaint);
new MutationObserver(repaint).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
// Sharp redraw of the base map on screen (when the map settles). It also drops the gesture cache.
function drawBase() {
  paintBase(bctx, baseCanvas.width, baseCanvas.height, view.x + ox, view.y + oy, view.k, view.r);
  cacheView = null; scheduleCache();
}
// While a finger or the camera is moving, redrawing every vector each frame is the slow part
// (most of the frame on a phone). Instead the base map is drawn once into a larger cache around
// the screen, and frames just slide and scale that image; it's redrawn sharp when things settle.
const cache = document.createElement('canvas'), cctx = cache.getContext('2d', { alpha: false });
const OVER = 0.35;           // margin around the screen, in screen sizes, on each side
let cacheView = null, cacheIdle = null;
function buildCache(over = OVER) {
  const W = vw, H = vh, mx = Math.round(W * over), my = Math.round(H * over);
  const cw = Math.round((W + 2 * mx) * dpr), ch = Math.round((H + 2 * my) * dpr);
  if (cache.width !== cw || cache.height !== ch) { cache.width = cw; cache.height = ch; }
  paintBase(cctx, cw, ch, view.x + ox + mx, view.y + oy + my, view.k, view.r);
  cacheView = { ...view, mx, my };
}
function scheduleCache() {
  if (cacheIdle) return;
  const run = () => { cacheIdle = null; if (!cacheView && !ptrs.size && !moving) buildCache(); };
  cacheIdle = window.requestIdleCallback ? requestIdleCallback(run, { timeout: 400 }) : setTimeout(run, 80);
}
// the screen transform taking something laid out at view `from` (offset by mx, my) to the current view
function relXf(from, mx = 0, my = 0) {
  const rho = view.k / from.k, dr = view.r - from.r, a = rho * Math.cos(dr), b = rho * Math.sin(dr);
  const tx = from.x + mx, ty = from.y + my;
  return { rho, a, b, e: view.x - (a * tx - b * ty), f: view.y - (b * tx + a * ty) };
}
function blitBase() {
  const W = vw, H = vh;
  let C = cacheView, M = C && relXf(C, C.mx, C.my);
  // does the cached area still cover the whole screen? (each screen corner, back in cache pixels)
  const covers = () => [[0, 0], [W, 0], [0, H], [W, H]].every(([sx, sy]) => {
    const dx = sx - M.e, dy = sy - M.f, q = M.rho * M.rho, x = (M.a * dx + M.b * dy) / q, y = (-M.b * dx + M.a * dy) / q;
    return x >= 0 && y >= 0 && x <= W + 2 * C.mx && y <= H + 2 * C.my;
  });
  if (!C || M.rho > 1.6 || M.rho < .62 || !covers()) {
    buildCache(.12); C = cacheView; M = relXf(C, C.mx, C.my);   // mid-gesture: a slimmer margin keeps this frame quick
  }
  bctx.setTransform(1, 0, 0, 1, 0, 0);
  bctx.fillStyle = pal.map; bctx.fillRect(0, 0, baseCanvas.width, baseCanvas.height);
  bctx.setTransform(M.a, M.b, -M.b, M.a, dpr * M.e, dpr * M.f);
  bctx.imageSmoothingEnabled = true;
  bctx.drawImage(cache, 0, 0);
}
// The halls' fittings, drawn like a floor plan: check-in desks with the bag belt behind them,
// baggage carousels (a belt loop round an island), the curbside doors, and the names on the floor.
function drawHallDetails(c, k, lower) {
  const f = lower ? 'lo' : 'up', ink = pal['hall-' + f + '-edge'];
  c.setLineDash([]);
  if (!lower) {
    for (const run of HALLS.counters) for (let i = 0; i + 1 < run.length; i++) {
      const [x0, y0] = run[i], [x1, y1] = run[i + 1], a = Math.atan2(y1 - y0, x1 - x0), L = Math.hypot(x1 - x0, y1 - y0);
      c.save(); c.translate(x0, y0); c.rotate(a);
      c.fillStyle = ink; c.fillRect(-.2, 1.5, L + .4, .7);                      // the bag belt behind the desks
      c.fillStyle = pal.counter; c.beginPath(); c.roundRect(.3, -.8, L - .6, 1.9, .5); c.fill();
      c.restore();
    }
  } else {
    for (const [x, y, a] of HALLS.carousels) {
      c.save(); c.translate(x, y); c.rotate(a * Math.PI / 180);
      c.beginPath(); c.roundRect(-6.4, -2.6, 12.8, 5.2, 2.6); c.fillStyle = pal.carousel; c.fill();
      c.strokeStyle = pal['carousel-belt']; c.lineWidth = .5; c.stroke();
      c.beginPath(); c.roundRect(-5, -1.3, 10, 2.6, 1.3); c.lineWidth = 1; c.stroke();   // the belt
      c.beginPath(); c.roundRect(-3.6, -.35, 7.2, .7, .35); c.fillStyle = pal['carousel-belt']; c.fill();   // the island
      c.restore();
    }
  }
  // the curb wall is glass: a pale double line, broken by the doors
  c.strokeStyle = pal.glass; c.lineWidth = 1.3; c.beginPath(); HALLS.facade[f].forEach(([x, y], i) => i ? c.lineTo(x, y) : c.moveTo(x, y)); c.stroke();
  c.strokeStyle = pal['hall-' + f]; c.lineWidth = .4; c.stroke();
  // curbside doors: a bar across the wall with a notch, like the entrance marks on a floor plan
  for (const [x, y] of HALLS.doors[f]) {
    c.save(); c.translate(x, y); c.rotate((y < -135 ? -44.6 : -78.4) * Math.PI / 180);
    c.fillStyle = pal.door; c.beginPath(); c.roundRect(-2.6, -.9, 5.2, 1.8, .9); c.fill();
    c.fillStyle = pal['hall-' + f]; c.fillRect(-1.6, -.3, 3.2, .6);
    c.restore();
  }
  // names painted along the floor and the curb: they grow with the map, up to a comfortable size
  const fs = Math.min(5.2, 15 / k);
  c.font = `700 ${fs}px ` + (getComputedStyle(document.documentElement).getPropertyValue('--font-display') || 'sans-serif');
  try { c.letterSpacing = (fs * .24) + 'px'; } catch (e) {}
  c.textAlign = 'center'; c.textBaseline = 'middle';
  for (const [x, y, ang, t, lv] of HALLS.names || []) {
    const road = /DRIVE/.test(t);
    if ((lv === 'lo') !== lower || k < (road ? 1.3 : .9)) continue;
    c.save(); c.translate(x, y); c.rotate(ang * Math.PI / 180);
    c.fillStyle = road ? pal.door : pal['hall-' + f + '-text']; c.globalAlpha = road ? .7 : .6; c.fillText(t, 0, 0);
    c.restore();
  }
  try { c.letterSpacing = '0px'; } catch (e) {}
}
function paintBase(c, cw, ch, tx, ty, k, rot = 0) {
  pal = pal || readPalette();
  c.setTransform(1, 0, 0, 1, 0, 0);
  c.fillStyle = pal.map; c.fillRect(0, 0, cw, ch);
  const kc = dpr * k * Math.cos(rot), ks = dpr * k * Math.sin(rot);
  c.setTransform(kc, ks, -ks, kc, dpr * tx, dpr * ty);
  c.lineCap = c.lineJoin = 'round';
  const fill = (key, color) => { if (P2[key]) { c.fillStyle = color; c.fill(P2[key], 'evenodd'); } };
  const stroke = (key, color, w, dash) => { if (P2[key]) { c.strokeStyle = color; c.lineWidth = w; c.setLineDash(dash || []); c.stroke(P2[key]); } };
  fill('grass', pal.grass); fill('wood', pal.wood); fill('water', pal.water);
  fill('apron', pal['apron-pave']); fill('lots', pal.lot); fill('ramp', pal['apron-pave']);
  stroke('taxiway', pal.taxi, 23); stroke('runway', pal.runway, 46);
  // runway centreline dashes, then the taxiway centrelines
  if (k > .35) stroke('runway', pal['runway-mark'], .9, [36, 24]);
  stroke('taxiway', pal['taxi-line'], 1.1 / k);
  const roads = [['service', 5], ['minor', 9], ['major', 11], ['motorway', 15]];
  for (const [r, w] of roads) stroke(r, r === 'motorway' ? pal['mway-case'] : pal['road-case'], w + 2.6);
  for (const [r, w] of roads) stroke(r, r === 'motorway' ? pal['mway-fill'] : pal['road-fill'], w);
  stroke('rail', pal.rail, 2.2, [6, 4]);
  fill('buildings', pal['other-bldg']); stroke('buildings', pal['bldg-edge'], .6 / Math.max(k, .5));
  fill('garage', pal.garage); stroke('garage', pal['bldg-edge'], .8 / Math.max(k, .5));
  // parked aircraft, roughly 33 m long
  c.fillStyle = pal.plane;
  for (const [x, y, a] of B.planes) {
    c.save(); c.translate(x, y); c.rotate(a * Math.PI / 180); c.scale(2.1, 2.1); c.fill(planePath); c.restore();
  }
  // terminals raised on a soft shadow; concourses tinted by their colour
  // (the shadow falls down the screen whichever way the map is turned)
  c.save(); c.translate(2 * Math.cos(rot) + 4.5 * Math.sin(rot), -2 * Math.sin(rot) + 4.5 * Math.cos(rot)); c.fillStyle = pal['bldg-shadow'];
  const closed = AP.closedTerms || [];
  for (const t of ['termM', ...CONS.map(c => 'term' + c), ...closed]) if (P2[t]) c.fill(P2[t]);
  c.restore();
  const edge = 1 / Math.max(k, .5);
  for (const t of closed) { fill(t, pal['term-closed']); stroke(t, pal['bldg-edge'], edge, [3, 2]); }
  fill('termM', pal.bldg); stroke('termM', pal['bldg-edge'], edge);
  // the concourses only have an upper level, so they fade back on the lower floor
  const lower = floor === 'lo';
  for (const t of CONS) {
    if (!lower) { fill('term' + t, pal['term' + t]); stroke('term' + t, pal['bldg-edge'], edge); continue; }
    c.globalAlpha = .55; fill('term' + t, pal['term-closed']); stroke('term' + t, pal['bldg-edge'], edge * .8); c.globalAlpha = 1;
  }
  if (!lower) stroke('bridges', pal.bridge, 3.6);
  // the landside halls' floors: ticketing upstairs, baggage claim downstairs (their details go on top
  // of the walkways, below)
  const hp = HALLS && HALL_PATH[lower ? 'lo' : 'up'];
  if (hp) {
    const f = lower ? 'lo' : 'up', P = poly => { const p = new Path2D(); poly.forEach(([x, y], i) => i ? p.lineTo(x, y) : p.moveTo(x, y)); p.closePath(); return p; };
    // outside: the curbside sidewalk, and crosswalks over the drive from each door
    c.fillStyle = pal.curb; c.fill(HALL_CURB[f] || (HALL_CURB[f] = P(HALLS.curb[f])));
    c.strokeStyle = pal.zebra; c.lineWidth = 3; c.lineCap = 'butt'; c.setLineDash([.6, .6]);
    for (const [x0, y0, x1, y1] of HALLS.zebra[f]) {
      // stripes run along the crossing: draw a wide dashed line across it
      const a = Math.atan2(y1 - y0, x1 - x0), L = Math.hypot(x1 - x0, y1 - y0);
      c.save(); c.translate(x0, y0); c.rotate(a); c.beginPath(); c.moveTo(L / 2, -1.6); c.lineTo(L / 2, 1.6); c.lineWidth = L; c.stroke(); c.restore();
    }
    c.setLineDash([]); c.lineCap = 'round';
    c.fillStyle = pal['hall-' + f]; c.fill(hp); c.strokeStyle = pal['hall-' + f + '-edge']; c.lineWidth = 1.2 / Math.max(k, .5); c.stroke(hp);
  }
  // this floor's walkways (OpenStreetMap indoor ways) and the escalators between floors
  stroke(lower ? 'walk0' : 'walk1', pal.corridor, 5);
  if (lower) stroke('walkm1', pal.corridor, 5, [5, 4]);
  // an airport train runs on the lower floor: a track with ties
  if (P2.train && lower === ((AP.trainFloor || 'lo') === 'lo')) { c.globalAlpha = .75; stroke('train', pal.stairs, 6 / Math.max(k, .6)); c.globalAlpha = 1; stroke('train', pal.corridor, 2.4 / Math.max(k, .6), [4, 5]); }   // (DFW's Skylink is up with the gates)
  // stairs and escalators: light, crisp treads (round dashes blur into blobs when you zoom in)
  c.lineCap = 'butt'; c.globalAlpha = .6; stroke('stairs', pal.stairs, 2, [.4, .5]); c.globalAlpha = 1; c.lineCap = 'round';
  if (hp) drawHallDetails(c, k, lower);
  if (!lower) {
    // the checkpoint line across the corridor from ticketing
    c.strokeStyle = pal.route; c.globalAlpha = .75; c.lineWidth = 1.8; c.setLineDash([3, 2.4]);
    const sls = AP.securityLine, list = Array.isArray(sls[0]) ? sls : [sls];
    for (const sl of list) { c.beginPath(); c.moveTo(sl[0], sl[1]); c.lineTo(sl[2], sl[3]); c.stroke(); }
    c.globalAlpha = 1;
  }
  c.setLineDash([]);
}
// ---------- Labels, gate numbers and concourse badges ----------
const areaLabels = [];
for (const [x, y, t, anchor, lvl] of AP.labels) {
  const g = el('g', { class: 'area-lbl' }); g.dataset.x = x; g.dataset.y = y; if (lvl) g.dataset.lv = lvl;
  el('text', { class: 'map-lbl', 'text-anchor': anchor || 'middle' }, g).textContent = t;
  g._w = t.length * 6.3; g._anchor = anchor || 'middle';
  areaLabels.push(g);
}
const markers = [];
const layerGates = el('g', {}), layerCon = el('g', {});
for (const it of GATES) {
  const g = el('g', { class: `mk gate ${it.con}`, 'data-gate': it.code }, layerGates);
  const pop = el('g', { class: 'pop' }, g), w = it.code.length > 2 ? 17 : 14;
  el('rect', { class: 'drop', x: -w / 2 - .3, y: -4.9, width: w + .6, height: 11.6, rx: 5.8 }, pop);
  el('rect', { class: 'pill', x: -w / 2, y: -5.5, width: w, height: 11, rx: 5.5 }, pop);
  el('text', { y: .4 }, pop).textContent = it.code;
  el('title', {}, g).textContent = `Gate ${it.code} · Concourse ${it.con}`;
  g.dataset.x = it.x; g.dataset.y = it.y; it.el = g; markers.push(g);
}
for (const [con, x, y] of AP.roundels) {
  const g = el('g', { class: 'mk con-mk', 'data-con': con }, layerCon);
  const pop = el('g', { class: 'pop' }, g);
  el('path', { class: 'con-shape ' + con, d: CON_SHAPE[con], transform: 'scale(1.25)' }, pop);
  el('text', { class: 'con-letter', y: .8 }, pop).textContent = con;
  el('title', {}, g).textContent = `Concourse ${con}`;
  g.dataset.x = x; g.dataset.y = y; markers.push(g);
}
// Mid-pinch the overlay is scaled (and turned) as one picture, which would grow and swing every
// marker until the fingers lift. Each marker's contents sit in a .zs group that the frame
// counter-scales and counter-turns through two CSS variables, so they keep their size and stay upright.
function zsWrap(g) {
  const w = document.createElementNS(NS, 'g'); w.setAttribute('class', 'zs');
  while (g.firstChild) w.appendChild(g.firstChild);
  g.appendChild(w); return g;
}
for (const g of markers) zsWrap(g);
for (const g of areaLabels) zsWrap(g);

// ---------- Pan / zoom in screen pixels ----------
// screen = (x, y) + k · rotate(r) · map point; r turns the map clockwise on screen (0 = north up)
const view = { k: 1, x: 0, y: 0, r: 0 };
let markerScale = 1;
const toScr = (p, v = view) => { const c = Math.cos(v.r || 0), s = Math.sin(v.r || 0); return { x: v.x + v.k * (c * p.x - s * p.y), y: v.y + v.k * (s * p.x + c * p.y) }; };
const toMapPt = (sx, sy, v = view) => { const c = Math.cos(v.r || 0), s = Math.sin(v.r || 0), dx = (sx - v.x) / v.k, dy = (sy - v.y) / v.k; return { x: c * dx + s * dy, y: -s * dx + c * dy }; };
// the view at zoom k and turn r that puts map point p at screen point (sx, sy)
const viewAt = (k, r, p, sx, sy) => { const c = Math.cos(r), s = Math.sin(r); return { k, r, x: sx - k * (c * p.x - s * p.y), y: sy - k * (s * p.x + c * p.y) }; };
const wrapAngle = a => Math.atan2(Math.sin(a), Math.cos(a));
const rotDeg = () => +(view.r * 180 / Math.PI).toFixed(3);
// markers and labels turn back against the map so they always read upright
const upright = (x, y, s) => `translate(${x} ${y})${view.r ? ` rotate(${-rotDeg()})` : ''} scale(${s})`;
function clampView() {
  // keep at least part of the airport on screen
  const W = vw, H = vh, m = 90, M = MAP_BOUNDS;
  const cs = [[M.x0, M.y0], [M.x1, M.y0], [M.x0, M.y1], [M.x1, M.y1]].map(([x, y]) => toScr({ x, y }));
  const l = Math.min(...cs.map(q => q.x)), r = Math.max(...cs.map(q => q.x)), t = Math.min(...cs.map(q => q.y)), b = Math.max(...cs.map(q => q.y));
  if (r < m) view.x += m - r; if (l > W - m) view.x -= l - (W - m);
  if (b < m + 60) view.y += m + 60 - b; if (t > H - m) view.y -= t - (H - m);
}
let scaleTimer = null, lastScaleK = 0, userMoved = false;
function updateScaleBar() {
  if (Math.abs(view.k - lastScaleK) < 1e-4) return;
  const first = !lastScaleK; lastScaleK = view.k;
  const want = 90 / view.k;   // metres covered by ~90 px
  const nice = [5, 10, 20, 25, 50, 100, 200, 250, 500, 1000].reduce((p, n) => n <= want ? n : p, 5);
  const bar = $('#scalebar');
  bar.querySelector('.bar').style.width = (nice * view.k).toFixed(1) + 'px';
  bar.querySelector('.txt').textContent = nice >= 1000 ? nice / 1000 + ' km' : nice + ' m';
  if (first || !userMoved) return;
  bar.classList.add('show'); clearTimeout(scaleTimer);
  scaleTimer = setTimeout(() => bar.classList.remove('show'), 1400);
}
// Rendering, once per animation frame at most:
// - the base map canvas is repainted at the exact view (from a cache while moving);
// - the SVG overlay slides with a CSS transform during a gesture and is re-laid-out once things settle.
let committed = null, commitTimer = null, moving = false, scaledAtK = 0, scaledAtR = 0, framePending = false, zsSet = 1, zrSet = 0;
const ox = 0, oy = 0;
const stage = $('#stage');
let dpr = 1;
function sizeSvg() {
  // cap the backing store: 2x is sharp on phones and keeps repaints and memory in check
  dpr = Math.min(devicePixelRatio || 1, 2);
  baseCanvas.width = Math.round(vw * dpr); baseCanvas.height = Math.round(vh * dpr);
}
sizeSvg();
function apply() {
  clampView();
  if (!committed) return render();
  if (!framePending) { framePending = true; requestAnimationFrame(frame); }
  clearTimeout(commitTimer);
  commitTimer = setTimeout(settle, 140);
}
function settle() { if (!ptrs.size && !moving) render(); else commitTimer = setTimeout(settle, 140); }
function frame() {
  framePending = false;
  if (!committed) return;
  const M = relXf(committed);
  // zoomed or turned far enough that sliding markers would look wrong: re-lay-out now
  if (M.rho > 1.9 || M.rho < .55 || Math.abs(wrapAngle(view.r - committed.r)) > .35) return render();
  blitBase(); updateCompass();
  svg.style.transform = `matrix(${M.a},${M.b},${-M.b},${M.a},${M.e},${M.f})`;
  const zs = Math.pow(M.rho, -.62), zr = -wrapAngle(view.r - committed.r) * 180 / Math.PI;
  if (Math.abs(zs - zsSet) > .004 || Math.abs(zr - zrSet) > .1) {
    zsSet = zs; zrSet = zr; svg.style.setProperty('--zs', zs.toFixed(4)); svg.style.setProperty('--zr', zr.toFixed(2) + 'deg');
  }
}
function render() {
  clearTimeout(commitTimer);
  clampView();
  committed = { ...view };
  if (ptrs.size || moving) blitBase(); else drawBase();
  svg.style.transform = '';
  if (zsSet !== 1 || zrSet !== 0) { zsSet = 1; zrSet = 0; svg.style.removeProperty('--zs'); svg.style.removeProperty('--zr'); }
  world.setAttribute('transform', `translate(${view.x} ${view.y})${view.r ? ` rotate(${rotDeg()})` : ''} scale(${view.k})`);
  // marker transforms only depend on zoom and turn, so a plain pan skips rewriting them
  if (view.k !== scaledAtK || view.r !== scaledAtR) {
    scaledAtK = view.k; scaledAtR = view.r;
    markerScale = 1 / Math.pow(view.k, 0.62);
    for (const m of markers) m.setAttribute('transform', upright(m.dataset.x, m.dataset.y, markerScale));
    for (const g of areaLabels) g.setAttribute('transform', upright(g.dataset.x, g.dataset.y, markerScale));
    updateCompass();
  }
  updateScaleBar();
  cullLabels();
  for (const f of hooks.render) f();
}
// Gate pills that would overlap shrink to dots (lower numbers win) and come back as you zoom in;
// area names show only where they have room.
const GATES_BY_NUM = GATES.slice().sort((a, b) => gnumOf(a.code) - gnumOf(b.code));
let chromeCache = null;
function cullLabels() {
  const u = markerScale * view.k, hit = (r, o) => r.l < o.r && r.r > o.l && r.t < o.b && r.b > o.t;
  const pills = [], blocks = [];
  for (const it of GATES_BY_NUM) {
    const p = toScr(it), hw = ((it.code.length > 2 ? 17 : 14) / 2 + .6) * u, hh = 6 * u;
    const box = { l: p.x - hw, r: p.x + hw, t: p.y - hh, b: p.y + hh };
    if (!pills.some(o => hit(box, o))) { it.el.removeAttribute('data-dot'); pills.push(box); blocks.push(box); continue; }
    // a dot that would still sit on a pill is left out altogether
    const r = 2.6 * u, db = { l: p.x - r, r: p.x + r, t: p.y - r, b: p.y + r };
    const covered = pills.some(o => hit(db, o));
    it.el.setAttribute('data-dot', covered ? 'hide' : '');
    if (!covered) blocks.push(db);
  }
  for (const m of markers) if (m.dataset.con) {
    const p = toScr({ x: +m.dataset.x, y: +m.dataset.y }), w = 16 * u;
    blocks.push({ l: p.x - w, r: p.x + w, t: p.y - w, b: p.y + w });
  }
  // floating controls hide whatever is under them
  const chrome = (chromeCache || (chromeCache = ['.controls', '.topbar', '.dock'].filter(s => $(s)).map(s => $(s).getBoundingClientRect())))
    .map(c => ({ l: c.left, r: c.right, t: c.top, b: c.bottom }));
  const offscreen = r => r.r < 0 || r.l > vw || r.b < 0 || r.t > vh;
  for (const g of areaLabels) {
    const p = toScr({ x: +g.dataset.x, y: +g.dataset.y }), w = g._w * u;
    const r = g._anchor === 'start' ? { l: p.x, r: p.x + w } : { l: p.x - w / 2, r: p.x + w / 2 };
    r.t = p.y - 7 * u; r.b = p.y + 2 * u;
    const ok = (!g.dataset.lv || g.dataset.lv === floor) && !offscreen(r) && !chrome.some(c => hit(r, c)) && !blocks.some(o => hit(r, o));
    g.toggleAttribute('data-off', !ok);
    if (ok) blocks.push(r);
  }
}
// how far out you can zoom: enough to see the whole airport (a big one like ATL needs more)
const kMin = () => Math.max(0.02, Math.min(0.25, (vw - 24) / (MAP_BOUNDS.x1 - MAP_BOUNDS.x0) * .9));
const clampK = k => Math.max(kMin(), Math.min(10, k));
function zoomAt(px, py, f) {
  const nk = clampK(view.k * f), r = nk / view.k;
  view.x = px - (px - view.x) * r; view.y = py - (py - view.y) * r; view.k = nk; apply();
}
function rotateAt(px, py, da) {
  const c = Math.cos(da), s = Math.sin(da), dx = view.x - px, dy = view.y - py;
  view.x = px + c * dx - s * dy; view.y = py + s * dx + c * dy; view.r = wrapAngle(view.r + da);
}
// the compass button appears once the map is turned, its needle pointing to north
const compassBtn = $('#compass'), dial = compassBtn.querySelector('.dial');
let compassShown = false, compassDeg = null;
function updateCompass() {
  const d = rotDeg();
  if (d !== compassDeg) { compassDeg = d; dial.setAttribute('transform', `rotate(${d})`); }
  const want = Math.abs(view.r) > .004;
  if (want !== compassShown) { compassShown = want; fade(compassBtn, want); }
}
compassBtn.onclick = () => {
  buzz();
  const R = visibleRect(), cx = (R.l + R.r) / 2, cy = (R.t + R.b) / 2;
  animateTo(viewAt(view.k, 0, toMapPt(cx, cy), cx, cy));
};
let anim = null;
function animateTo(target) {
  cancelAnimationFrame(anim); cancelAnimationFrame(glide);
  if (reduced) { Object.assign(view, target); render(); return; }
  // Fly the centre of the screen in a straight line while zoom changes geometrically, so places
  // don't drift sideways; long hops ease out a little and back in.
  if (target.r == null) target = { ...target, r: view.r };
  const W = vw / 2, H = vh / 2, from = { ...view }, dr = wrapAngle(target.r - from.r);
  const c0 = toMapPt(W, H, from), c1 = toMapPt(W, H, target);
  const hop = Math.hypot(c1.x - c0.x, c1.y - c0.y) * Math.min(from.k, target.k) / Math.max(W, H);
  const lift = Math.min(0.45, Math.max(0, hop - 0.6) * 0.25);
  const dur = Math.min(900, 480 + hop * 120), t0 = performance.now();
  const step = now => {
    const p = Math.min(1, (now - t0) / dur);
    const e = 1 - Math.exp(-7 * p) * (1 + 7 * p);          // critically damped, no overshoot
    const k = from.k * Math.pow(target.k / from.k, e) * (1 - lift * Math.sin(Math.PI * e));
    const cx = c0.x + (c1.x - c0.x) * e, cy = c0.y + (c1.y - c0.y) * e;
    Object.assign(view, viewAt(k, from.r + dr * e, { x: cx, y: cy }, W, H));
    if (p < 1) { moving = true; apply(); anim = requestAnimationFrame(step); } else { moving = false; Object.assign(view, target, { r: wrapAngle(target.r) }); render(); }
  };
  anim = requestAnimationFrame(step);
}
// the part of the screen not covered by the controls
function visibleRect() {
  const top = $('.topbar').getBoundingClientRect().bottom + 6;
  const R = { l: 12, t: top, r: vw - 66, b: vh - 28 };
  // a panel docked at the bottom (or on the left on wide screens) covers part of the map
  const dock = $('.dock'), d = dock && !dock.hidden && dock.getBoundingClientRect();
  if (d && d.height) { if (d.left < 40 && d.right > vw - 80) R.b = Math.min(R.b, d.top - 8); else R.l = Math.max(R.l, d.right + 12); }
  if (R.b - R.t < 140) R.b = R.t + 140;
  return R;
}
function fitBox(x0, y0, x1, y1, pad = 20, maxK = 4, instant = false, rot = view.r) {
  const R = visibleRect(), bw = Math.max(x1 - x0, 30), bh = Math.max(y1 - y0, 30);
  // the box's size on screen once turned with the map
  const c = Math.abs(Math.cos(rot)), s = Math.abs(Math.sin(rot)), w = c * bw + s * bh, h = s * bw + c * bh;
  const k = clampK(Math.min((R.r - R.l - pad * 2) / w, (R.b - R.t - pad * 2) / h, maxK));
  const target = viewAt(k, rot, { x: (x0 + x1) / 2, y: (y0 + y1) / 2 }, (R.l + R.r) / 2, (R.t + R.b) / 2);
  if (instant) { Object.assign(view, target); render(); } else animateTo(target);
}
function fitItems(list, pad, maxK, instant) {
  const xs = list.map(i => i.x), ys = list.map(i => i.y);
  fitBox(Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys), pad, maxK, instant);
}
const fitAll = instant => fitBox(MAP_BOUNDS.x0, MAP_BOUNDS.y0, MAP_BOUNDS.x1, MAP_BOUNDS.y1, 4, 4, instant, 0);   // the whole airport, north up
const fitCon = con => fitItems(GATES.filter(g => g.con === con), 24, 5);
function zoomAnimAt(px, py, f) {
  const nk = clampK(view.k * f), r = nk / view.k;
  animateTo({ k: nk, x: px - (px - view.x) * r, y: py - (py - view.y) * r });
}

// ---------- Gestures: drag, flick, pinch, twist, wheel, double-tap ----------
stage.addEventListener('wheel', e => {
  e.preventDefault(); cancelAnimationFrame(anim); userMoved = true;
  zoomAt(e.clientX, e.clientY, Math.exp(-e.deltaY * (e.deltaMode ? 0.05 : 0.0018)));
}, { passive: false });
const ptrs = new Map();
let moved = 0, downTarget = null, pinch = null;
let vel = { x: 0, y: 0, t: 0 }, panned = false, glide = null, lastTap = null;
stage.addEventListener('pointerdown', e => {
  try { stage.setPointerCapture(e.pointerId); } catch (err) {}
  ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (ptrs.size === 1) { moved = 0; downTarget = e.target.closest('[data-plane], [data-gate], [data-con]'); } else downTarget = null;
  pinch = null; cancelAnimationFrame(anim); cancelAnimationFrame(glide); moving = false; userMoved = true;
  vel = { x: 0, y: 0, t: e.timeStamp }; panned = ptrs.size === 1;
});
stage.addEventListener('pointermove', e => {
  if (!ptrs.has(e.pointerId)) return;
  const prev = ptrs.get(e.pointerId);
  ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (ptrs.size === 1) {
    const dx = e.clientX - prev.x, dy = e.clientY - prev.y;
    moved += Math.abs(dx) + Math.abs(dy);
    const dt = Math.max(1, e.timeStamp - vel.t);
    vel = { x: .75 * dx / dt + .25 * vel.x, y: .75 * dy / dt + .25 * vel.y, t: e.timeStamp };
    view.x += dx; view.y += dy; apply();
  } else if (ptrs.size === 2) {
    const [a, b] = [...ptrs.values()];
    const d = Math.hypot(a.x - b.x, a.y - b.y), mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, ang = Math.atan2(b.y - a.y, b.x - a.x);
    // two fingers twisting turn the map, once they've turned past a small threshold (so a pinch to
    // zoom doesn't wobble it)
    let turn = 0, twist = pinch ? pinch.twist : 0, on = pinch ? pinch.on : false;
    if (pinch) {
      const da = wrapAngle(ang - pinch.ang);
      if (on) turn = da; else { twist += da; if (Math.abs(twist) > .2) { on = true; turn = twist; buzz(5); } }
      zoomAt(mid.x, mid.y, d / pinch.d);
      if (turn) rotateAt(mid.x, mid.y, turn);
      view.x += mid.x - pinch.mid.x; view.y += mid.y - pinch.mid.y; apply();
    }
    pinch = { d, mid, ang, twist, on }; moved = 99; panned = false;
  }
});
// a flick only counts if the finger was still moving when it lifted
const isStale = () => performance.now() - vel.t > 80 || Math.hypot(vel.x, vel.y) < 0.15;
function startGlide() {
  // momentum after a flick, decaying like an iOS scroll view
  if (reduced || isStale()) return;
  let last = performance.now();
  const step = now => {
    const dt = Math.min(40, now - last); last = now;
    view.x += vel.x * dt; view.y += vel.y * dt;
    const f = Math.exp(-dt / 320); vel.x *= f; vel.y *= f;
    if (Math.hypot(vel.x, vel.y) > 0.02) { moving = true; apply(); glide = requestAnimationFrame(step); } else { moving = false; render(); }
  };
  glide = requestAnimationFrame(step);
}
const endPtr = e => {
  if (!ptrs.has(e.pointerId)) return;
  ptrs.delete(e.pointerId); pinch = null;
  if (!ptrs.size && panned && moved >= 6 && e.type === 'pointerup') { startGlide(); if (!glide || isStale()) render(); }
  if (ptrs.size || moved >= 6 || e.type !== 'pointerup') return;
  const t = downTarget;
  for (const h of hooks.tap) if (h(t, e)) return;
  if (t && t.dataset.con) { buzz(); fitCon(t.dataset.con); return; }
  // tap a gate to zoom in on it
  if (t && t.dataset.gate) { buzz(); const q = toScr({ x: +t.dataset.x, y: +t.dataset.y }); zoomAnimAt(q.x, q.y, Math.max(1, 3 / view.k)); return; }
  // double-tap the map to zoom in, as in Apple Maps and Google Maps
  const now = e.timeStamp;
  if (lastTap && now - lastTap.t < 320 && Math.hypot(e.clientX - lastTap.x, e.clientY - lastTap.y) < 30) {
    zoomAnimAt(e.clientX, e.clientY, 2); buzz(5); lastTap = null;
  } else lastTap = { t: now, x: e.clientX, y: e.clientY };
};
stage.addEventListener('pointerup', endPtr);
stage.addEventListener('pointercancel', endPtr);

// ---------- Buttons ----------
const zoomCenter = f => { const R = visibleRect(); userMoved = true; zoomAnimAt((R.l + R.r) / 2, (R.t + R.b) / 2, f); };
$('#zin').onclick = () => zoomCenter(1.6);
$('#zout').onclick = () => zoomCenter(1 / 1.6);
$('#zfit').onclick = () => fitAll();
// Concourse buttons zoom to a concourse; airports with many concourses get one button that opens a menu
const conMenu = $('#conMenu');
if (CONS.length <= 4) $('#conCap').innerHTML = CONS.map(c => `<button class="shape-btn" data-zoom="${c}" aria-label="Zoom to Concourse ${c}">${badgeSvg(c, 28)}</button>`).join('');
else {
  $('#conCap').innerHTML = `<button id="conBtn" class="shape-btn" aria-label="Zoom to a concourse" aria-haspopup="menu" aria-expanded="false">${icon('gate')}</button>`;
  conMenu.innerHTML = '<div class="lh">Concourses</div>' + CONS.map(c => `<button role="menuitem" data-zoom="${c}">${badgeSvg(c, 26)}Concourse ${c}<span class="s">${esc(CON_RANGE[c])}</span></button>`).join('');
  const openCons = open => {
    if (open) { const r = $('#conBtn').getBoundingClientRect(); conMenu.style.top = Math.max(8, Math.min(r.top, vh - 420)) + 'px'; stagger(conMenu, 'button'); }
    fade(conMenu, open); $('#conBtn').setAttribute('aria-expanded', String(open));
  };
  $('#conBtn').onclick = () => { buzz(5); openCons(conMenu.hidden || conMenu.classList.contains('leaving')); };
  document.addEventListener('pointerdown', e => { if (!conMenu.hidden && !e.target.closest('#conMenu, #conBtn')) openCons(false); }, true);
  conMenu.addEventListener('click', () => openCons(false));
}
document.querySelectorAll('[data-zoom]').forEach(b => b.onclick = () => { buzz(); fitCon(b.dataset.zoom); });

// ---------- Floor picker ----------
function setFloor(f) {
  if (!f || f === floor) return;
  // cross-fade: hold a copy of the old floor over the map and fade it out
  if (!reduced && committed) {
    const fc = $('#baseFade');
    if (fc.width !== baseCanvas.width || fc.height !== baseCanvas.height) { fc.width = baseCanvas.width; fc.height = baseCanvas.height; }
    fc.getContext('2d').drawImage(baseCanvas, 0, 0);
    fc.classList.remove('out'); void fc.offsetWidth; fc.classList.add('out');
    svg.classList.remove('floor-in'); void svg.getBBox(); svg.classList.add('floor-in');
    clearTimeout(setFloor.t); setFloor.t = setTimeout(() => svg.classList.remove('floor-in'), 400);
  }
  floor = f; svg.dataset.floor = f;
  document.querySelectorAll('#floorCap [data-floor]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.floor === f)));
  $('#floorCap').style.setProperty('--i', f === 'lo' ? 1 : 0);
  if (committed) render();
  buzz(5); toast(AP.floors[f]);
}
document.querySelectorAll('#floorCap [data-floor]').forEach(b => {
  b.onclick = () => setFloor(b.dataset.floor);
  const name = (AP.floors[b.dataset.floor] || '').replace(' · ', ': ');
  b.title = name; b.setAttribute('aria-label', name);
});
svg.setAttribute('aria-label', `Map of ${AP.name}`);
document.title = `${AP.code} map · Clearway`;

// ---------- Airport picker ----------
const apMenu = $('#apMenu'), apBtn = $('#apBtn');
$('#apCode').textContent = AP.code; $('#apName').textContent = AP.name;
function openAirports(open) {
  fade(apMenu, open); apBtn.setAttribute('aria-expanded', String(open));
  if (!open) return;
  apMenu.innerHTML = Object.values(AIRPORTS).map(a => `<button role="menuitemradio" aria-checked="${a.code === AP.code}" data-ap="${a.code}">
      <span class="code">${esc(a.code)}</span><span><span>${esc(a.name)}</span><span class="s">${esc(a.city)}</span></span>
      <span style="color:var(--primary)">${a.code === AP.code ? icon('check') : ''}</span></button>`).join('');
  stagger(apMenu, 'button');
  apMenu.querySelectorAll('[data-ap]').forEach(b => b.onclick = () => {
    if (b.dataset.ap === AP.code) return openAirports(false);
    try { localStorage.setItem('cw.airport', b.dataset.ap); } catch (e) {}
    location.reload();
  });
}
apBtn.addEventListener('click', () => { buzz(5); openAirports(apMenu.hidden || apMenu.classList.contains('leaving')); });
document.addEventListener('pointerdown', e => { if (!apMenu.hidden && !e.target.closest('#apMenu, #apBtn')) openAirports(false); });
addEventListener('keydown', e => { if (e.key === 'Escape') { openAirports(false); conMenu.hidden || fade(conMenu, false); } });

// ---------- Start ----------
addEventListener('resize', () => { chromeCache = null; sizeSvg(); render(); });
fitAll(true);
document.fonts && document.fonts.ready.then(() => { chromeCache = null; if (!userMoved) fitAll(true); else render(); });
