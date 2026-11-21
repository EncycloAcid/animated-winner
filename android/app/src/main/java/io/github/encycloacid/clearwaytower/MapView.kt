package io.github.encycloacid.clearwaytower

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The airport map: the OpenStreetMap base (painted into a bitmap off the main thread, and slid and
 * scaled while you move the map), with gate numbers, concourse badges, names, runway numbers and
 * the game's aircraft drawn on top every frame. Drag, flick, pinch, twist, and double-tap to zoom.
 */
@SuppressLint("ViewConstructor")
class MapView(context: Context) : View(context) {
    interface Listener {
        fun onTapPlane(p: Plane)
        /** true if the game used the tap (giving a plane a gate) */
        fun onTapGate(code: String): Boolean
        fun onTapEmpty()
        fun onFrame(dt: Double)
        fun onTurn(deg: Float)
        /** true while the game is running, so the map keeps redrawing */
        fun isLive(): Boolean
    }
    var listener: Listener? = null
    private val d = resources.displayMetrics.density
    private var ap: Airport? = null
    private var base: BaseMap? = null
    var tower: Tower? = null; private set
    private var pal = Palette(false)
    private var bounds = RectF(-500f, -500f, 500f, 500f)       // how far the map can go: the airport and its holding area
    private var home = RectF(bounds)                            // the whole airport: terminals and runways

    // ---------- The view: screen = (tx, ty) + k · rotate(r) · map point ----------
    private var k = 1f; private var tx = 0f; private var ty = 0f; private var r = 0f
    private var obscTop = 0f; private var obscBottom = 0f; private var obscLeft = 0f
    private var laidOut = false; private var wantFit = true

    fun setAirport(ap: Airport, base: BaseMap, tower: Tower) {
        this.ap = ap; this.base = base; this.tower = tower
        bounds = RectF(tower.playBounds); home = RectF(tower.airportBounds)
        front = null; frontView = null; wantFit = true
        gatesByNum = ap.gates.sortedBy { it.code.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (laidOut) { fitAll(true); wantFit = false }
        requestRender(); invalidate()
    }
    fun setDark(dark: Boolean) { if (pal.dark != dark) { pal = Palette(dark); front = null; requestRender(); invalidate() } }
    /** parts of the screen covered by the controls and the flight panel */
    fun setObscured(top: Float, bottom: Float, left: Float) { obscTop = top; obscBottom = bottom; obscLeft = left }

    private fun sx(x: Float, y: Float) = tx + k * (cos(r) * x - sin(r) * y)
    private fun sy(x: Float, y: Float) = ty + k * (sin(r) * x + cos(r) * y)
    private fun toMap(px: Float, py: Float): PointF {
        val dx = (px - tx) / k; val dy = (py - ty) / k
        return PointF(cos(r) * dx + sin(r) * dy, -sin(r) * dx + cos(r) * dy)
    }
    private class V(val k: Float, val r: Float, val x: Float, val y: Float)
    /** the view at zoom k and turn r that puts map point p at screen point (px, py) */
    private fun viewAt(k: Float, r: Float, p: PointF, px: Float, py: Float) =
        V(k, r, px - k * (cos(r) * p.x - sin(r) * p.y), py - k * (sin(r) * p.x + cos(r) * p.y))
    private fun apply(v: V) { k = v.k; r = v.r; tx = v.x; ty = v.y }
    private fun wrap(a: Float) = atan2(sin(a), cos(a))
    private val kc get() = k / d                       // dp per metre, like the web version's CSS px
    private fun kMin() = max(.02f * d, min(.25f * d, (width - 24 * d) / bounds.width() * .9f))
    private fun clampK(v: Float) = v.coerceIn(kMin(), 10 * d)
    private fun clampView() {
        // keep at least part of the airport on screen
        val m = 90 * d
        val xs = floatArrayOf(sx(bounds.left, bounds.top), sx(bounds.right, bounds.top), sx(bounds.left, bounds.bottom), sx(bounds.right, bounds.bottom))
        val ys = floatArrayOf(sy(bounds.left, bounds.top), sy(bounds.right, bounds.top), sy(bounds.left, bounds.bottom), sy(bounds.right, bounds.bottom))
        if (xs.max() < m) tx += m - xs.max(); if (xs.min() > width - m) tx -= xs.min() - (width - m)
        if (ys.max() < m + 60 * d) ty += m + 60 * d - ys.max(); if (ys.min() > height - m) ty -= ys.min() - (height - m)
    }
    private fun changed() { clampView(); lastMove = SystemClock.uptimeMillis(); listener?.onTurn(Math.toDegrees(r.toDouble()).toFloat()) }

    private fun visible(): RectF {
        val v = RectF(obscLeft + 12 * d, obscTop + 6 * d, width - 66 * d, height - obscBottom - 8 * d)
        if (v.height() < 140 * d) v.bottom = v.top + 140 * d
        if (v.width() < 140 * d) v.left = max(12 * d, v.right - 140 * d)
        return v
    }
    fun fitBox(x0: Float, y0: Float, x1: Float, y1: Float, pad: Float = 20f, maxK: Float = 4f, instant: Boolean = false, rot: Float = r) {
        val v = visible(); val bw = max(x1 - x0, 30f); val bh = max(y1 - y0, 30f)
        val c = abs(cos(rot)); val s = abs(sin(rot)); val w = c * bw + s * bh; val h = s * bw + c * bh
        val nk = clampK(min(min((v.width() - pad * 2 * d) / w, (v.height() - pad * 2 * d) / h), maxK * d))
        val t = viewAt(nk, rot, PointF((x0 + x1) / 2, (y0 + y1) / 2), v.centerX(), v.centerY())
        if (instant) { apply(t); changed(); invalidate(); kick() } else animateTo(t)
    }
    fun fitAll(instant: Boolean = false) = fitBox(home.left, home.top, home.right, home.bottom, 4f, 4f, instant, 0f)
    fun fitCon(con: String) {
        val gs = ap?.gates?.filter { it.con == con } ?: return
        if (gs.isNotEmpty()) fitBox(gs.minOf { it.x }, gs.minOf { it.y }, gs.maxOf { it.x }, gs.maxOf { it.y }, 24f, 5f)
    }
    fun zoomBy(f: Float) { val v = visible(); zoomAnimAt(v.centerX(), v.centerY(), f) }
    fun northUp() { val v = visible(); animateTo(viewAt(k, 0f, toMap(v.centerX(), v.centerY()), v.centerX(), v.centerY())) }
    fun centerOn(x: Double, y: Double) { val v = visible(); animateTo(viewAt(max(k, .9f * d), r, PointF(x.toFloat(), y.toFloat()), v.centerX(), v.centerY())) }
    private fun zoomAt(px: Float, py: Float, f: Float) {
        val nk = clampK(k * f); val q = nk / k
        tx = px - (px - tx) * q; ty = py - (py - ty) * q; k = nk; changed()
    }
    private fun zoomAnimAt(px: Float, py: Float, f: Float) {
        val nk = clampK(k * f); val q = nk / k
        animateTo(V(nk, r, px - (px - tx) * q, py - (py - ty) * q))
    }

    // ---------- Camera animation: the centre flies straight while zoom changes geometrically ----------
    private class Anim(val from: V, val to: V, val c0: PointF, val c1: PointF, val dr: Float, val lift: Float, val t0: Long, val dur: Float)
    private var anim: Anim? = null
    private fun animateTo(target: V) {
        fling = null
        val w = width / 2f; val h = height / 2f
        val from = V(k, r, tx, ty)
        val c0 = toMap(w, h)
        val c1 = run { val dx = (w - target.x) / target.k; val dy = (h - target.y) / target.k; PointF(cos(target.r) * dx + sin(target.r) * dy, -sin(target.r) * dx + cos(target.r) * dy) }
        val hop = hypot(c1.x - c0.x, c1.y - c0.y) * min(k, target.k) / max(w, h)
        val lift = min(.45f, max(0f, hop - .6f) * .25f)
        anim = Anim(from, target, c0, c1, wrap(target.r - r), lift, SystemClock.uptimeMillis(), min(900f, 480 + hop * 120))
        kick()
    }
    private fun stepAnim(now: Long) {
        val a = anim ?: return
        val p = min(1f, (now - a.t0) / a.dur)
        val e = 1 - exp(-7f * p) * (1 + 7 * p)            // critically damped, no overshoot
        val nk = a.from.k * (a.to.k / a.from.k).pow(e) * (1 - a.lift * sin(PI.toFloat() * e))
        apply(viewAt(nk, a.from.r + a.dr * e, PointF(a.c0.x + (a.c1.x - a.c0.x) * e, a.c0.y + (a.c1.y - a.c0.y) * e), width / 2f, height / 2f))
        if (p >= 1) { apply(a.to); r = wrap(r); anim = null }
        changed()
    }

    // ---------- Base map in a bitmap, rendered off the main thread ----------
    private val renderThread = HandlerThread("basemap").apply { start() }
    private val renderer = Handler(renderThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var front: Bitmap? = null
    private var frontView: FloatArray? = null          // k, tx, ty, r, margin of the bitmap
    private var rendering = false; private var renderAgain = false
    private var lastMove = 0L
    private fun requestRender() {
        val b = base ?: return
        if (width == 0 || height == 0) return
        if (rendering) { renderAgain = true; return }
        rendering = true
        val margin = (.2f * max(width, height)).toInt()
        val w = width + 2 * margin; val h = height + 2 * margin
        val snap = floatArrayOf(k, tx, ty, r, margin.toFloat()); val p = pal; val kcNow = kc
        renderer.post {
            val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null }
            if (bmp != null) {
                val m = Matrix().apply { setScale(snap[0], snap[0]); postRotate(Math.toDegrees(snap[3].toDouble()).toFloat()); postTranslate(snap[1] + margin, snap[2] + margin) }
                b.paint(Canvas(bmp), m, kcNow, snap[3], p)
            }
            main.post {
                rendering = false
                if (bmp != null && p === pal && b === base) { front = bmp; frontView = snap }
                invalidate()
                if (renderAgain) { renderAgain = false; requestRender() }
            }
        }
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bmpMatrix = Matrix(); private val tmp = Matrix()
    /** bitmap pixels → screen, for the current view */
    private fun bitmapToScreen(fv: FloatArray): Matrix {
        tmp.reset(); tmp.setScale(fv[0], fv[0]); tmp.postRotate(Math.toDegrees(fv[3].toDouble()).toFloat()); tmp.postTranslate(fv[1] + fv[4], fv[2] + fv[4])
        tmp.invert(bmpMatrix)
        val ms = Matrix().apply { setScale(k, k); postRotate(Math.toDegrees(r.toDouble()).toFloat()); postTranslate(tx, ty) }
        bmpMatrix.postConcat(ms)
        return bmpMatrix
    }
    private fun maybeRender(now: Long) {
        if (rendering || base == null) return
        val fv = frontView ?: return requestRender()
        val same = fv[0] == k && fv[1] == tx && fv[2] == ty && fv[3] == r
        val still = ptrs.isEmpty() && anim == null && fling == null && now - lastMove > 120
        if (still) { if (!same) requestRender(); return }
        // mid-gesture: redraw when the picture would be too stretched or no longer covers the screen
        val q = k / fv[0]
        if (q > 1.6f || q < .62f || abs(wrap(r - fv[3])) > .35f) return requestRender()
        val inv = Matrix(); bitmapToScreen(fv).invert(inv)
        val pts = floatArrayOf(0f, 0f, width.toFloat(), 0f, 0f, height.toFloat(), width.toFloat(), height.toFloat())
        inv.mapPoints(pts)
        val bw = width + 2 * fv[4]; val bh = height + 2 * fv[4]
        for (i in 0 until 4) if (pts[2 * i] < 0 || pts[2 * i + 1] < 0 || pts[2 * i] > bw || pts[2 * i + 1] > bh) return requestRender()
    }

    // ---------- Frame loop: the game, the camera, a flick. It stops when nothing is moving. ----------
    private var active = false; private var looping = false; private var lastFrame = 0L
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            looping = false
            if (!active) return
            val dt = if (lastFrame == 0L) 0.0 else min(.1, (nanos - lastFrame) / 1e9)
            lastFrame = nanos
            listener?.onFrame(dt)
            val now = SystemClock.uptimeMillis()
            stepAnim(now); stepFling(dt.toFloat())
            maybeRender(now)
            invalidate()
            if (needsFrames()) { looping = true; Choreographer.getInstance().postFrameCallback(this) } else lastFrame = 0L
        }
    }
    private fun needsFrames(): Boolean {
        if (listener?.isLive() == true || anim != null || fling != null || ptrs.isNotEmpty()) return true
        // keep ticking until the map has settled and a sharp picture of it has been asked for
        val fv = frontView ?: return !rendering
        return !rendering && (fv[0] != k || fv[1] != tx || fv[2] != ty || fv[3] != r)
    }
    fun resume() { active = true; kick() }
    fun pause() { active = false }
    /** start the frame loop if it isn't running (the game started, a gesture began…) */
    fun kick() { if (active && !looping) { looping = true; lastFrame = 0L; Choreographer.getInstance().postFrameCallback(frameCb) } }
    fun release() { active = false; renderThread.quitSafely() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        laidOut = w > 0 && h > 0
        if (laidOut && ap != null) { if (wantFit) { fitAll(true); wantFit = false } else { clampView() } }
        front = null; requestRender()
    }

    // ---------- Drawing ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val gateText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = condensed; textAlign = Paint.Align.CENTER; color = android.graphics.Color.WHITE }
    private val areaText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = .08f }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val tagSub = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gatesByNum: List<Gate> = emptyList()
    private class Box(val l: Float, val t: Float, val r: Float, val b: Float) { fun hits(o: Box) = l < o.r && r > o.l && t < o.b && b > o.t }
    private val gateShown = HashMap<String, Int>()         // 0 pill, 1 dot, 2 hidden
    private val tagBoxes = ArrayList<Pair<RectF, Plane>>()
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(c: Canvas) {
        c.drawColor(pal.map)
        val a = ap ?: return
        val fv = frontView; val bmp = front
        if (bmp != null && fv != null) c.drawBitmap(bmp, bitmapToScreen(fv), bmpPaint)
        val s = d * kc.pow(.38f)                          // px per marker unit: grows slowly as you zoom in
        val tw = tower
        val blocks = ArrayList<Box>()

        // gate numbers: pills that would overlap shrink to dots (lower numbers win); dots still on a pill are left out
        val pills = ArrayList<Box>()
        for (g in gatesByNum) {
            val px = sx(g.x, g.y); val py = sy(g.x, g.y)
            val hw = ((if (g.code.length > 2) 17 else 14) / 2f + .6f) * s; val hh = 6 * s
            val box = Box(px - hw, py - hh, px + hw, py + hh)
            if (pills.none { it.hits(box) }) { gateShown[g.code] = 0; pills.add(box); blocks.add(box); continue }
            val rr = 2.6f * s; val db = Box(px - rr, py - rr, px + rr, py + rr)
            val covered = pills.any { it.hits(db) }
            gateShown[g.code] = if (covered) 2 else 1
            if (!covered) blocks.add(db)
        }
        for (rd in a.roundels) { val px = sx(rd.x, rd.y); val py = sy(rd.x, rd.y); val w = 16 * s; blocks.add(Box(px - w, py - w, px + w, py + w)) }

        // area names, only where they have room
        areaText.textSize = 8.5f * s; areaText.color = pal.label2
        for (l in a.labels) {
            if (l.level != null && l.level != "up") continue
            val px = sx(l.x, l.y); val py = sy(l.x, l.y); val text = l.text.uppercase()
            val w = areaText.measureText(text)
            val box = if (l.start) Box(px, py - 7 * s, px + w, py + 2 * s) else Box(px - w / 2, py - 7 * s, px + w / 2, py + 2 * s)
            if (box.r < 0 || box.l > width || box.b < 0 || box.t > height || box.t < obscTop || blocks.any { it.hits(box) }) continue
            blocks.add(box)
            areaText.textAlign = if (l.start) Paint.Align.LEFT else Paint.Align.CENTER
            c.drawText(text, px, py, areaText)
        }

        // aircraft sizes; they're drawn over the gate numbers and badges, below
        // real size, gently enlarged when zoomed far out so a narrowbody stays about 13 dp long (the others in proportion)
        val acF = hypot(1f, 13f / (38f * kc)); val sc = acF * k
        val rdeg = Math.toDegrees(r.toDouble()).toFloat()
        val now = SystemClock.uptimeMillis()
        val blink = .45f + .55f * (.5f + .5f * cos(now / 1000f * 2 * PI.toFloat()))
        // gate pills; while choosing a gate, free ones pulse and taken ones step back
        val picking = tw?.picking == true
        val pulse = 1 + .25f * (.5f - .5f * cos(now / 1200f * 2 * PI.toFloat()))
        gateText.textSize = 6.6f * s
        for (g in gatesByNum) {
            val shown = gateShown[g.code] ?: 0
            if (shown == 2) continue
            val stand = tw?.stands?.get(g.code)
            val free = stand != null && stand.plane == null
            val px = sx(g.x, g.y); val py = sy(g.x, g.y)
            if (px < -30 || py < -30 || px > width + 30 || py > height + 30) continue
            val alpha = if (picking && !free) (255 * .28f).toInt() else 255
            var sc2 = s * (if (shown == 1) .42f else 1f)
            if (picking && free) sc2 *= pulse
            val w = if (shown == 1) 11f else if (g.code.length > 2) 17f else 14f
            c.save(); c.translate(px, py); c.scale(sc2, sc2)
            fill.color = pal.drop; fill.alpha = alpha * 0x38 / 255; c.drawRoundRect(-w / 2 - .3f, -4.9f, w / 2 + .3f, 6.7f, 5.8f, 5.8f, fill)
            fill.color = pal.con(a.concourse(g.con)); fill.alpha = alpha; c.drawRoundRect(-w / 2, -5.5f, w / 2, 5.5f, 5.5f, 5.5f, fill)
            line.pathEffect = null
            if (picking && free) { line.color = pal.route; line.strokeWidth = 2f } else { line.color = pal.bldg; line.strokeWidth = 1f; line.alpha = alpha }
            c.drawRoundRect(-w / 2, -5.5f, w / 2, 5.5f, 5.5f, 5.5f, line); line.alpha = 255
            c.restore()
            if (shown == 0) { gateText.alpha = alpha; c.drawText(g.code, px, py + gateText.textSize * .35f * (if (picking && free) pulse else 1f), gateText) }
            fill.alpha = 255
        }
        // concourse badges
        gateText.alpha = 255
        for (rd in a.roundels) {
            val px = sx(rd.x, rd.y); val py = sy(rd.x, rd.y)
            val con = a.concourse(rd.con) ?: continue
            c.save(); c.translate(px, py); c.scale(s * 1.25f, s * 1.25f); fill.color = pal.con(con); c.drawPath(con.shape, fill); c.restore()
            gateText.textSize = 14 * s; c.drawText(rd.con, px, py + 14 * s * .36f, gateText)
        }

        // aircraft, on top of the gate numbers and badges: they're what you're controlling
        if (tw != null) for (p in tw.planes) {
            val px = sx(p.x.toFloat(), p.y.toFloat()); val py = sy(p.x.toFloat(), p.y.toFloat())
            if (px < -200 || py < -200 || px > width + 200 || py > height + 200) continue
            val m = Aircraft.get(p.type)
            if (p.air) {
                val o = 5.5f * sc
                c.save(); c.translate(px + o, py + o * 1.4f); c.rotate(p.h.toFloat() + rdeg); c.scale(sc, sc)
                fill.color = pal.acShadow; c.drawPath(m.silhouette, fill); c.restore()
            }
            c.save(); c.translate(px, py); c.rotate(p.h.toFloat() + rdeg); c.scale(sc, sc)
            val sel = tw.selected === p; val halo = m.size * .62f
            if (sel || p.needsYou) {
                fill.color = if (sel) pal.route else pal.acNeed; fill.alpha = (255 * .22f).toInt(); c.drawCircle(0f, 0f, halo, fill); fill.alpha = 255
                if (sel) { line.color = pal.route; line.strokeWidth = 2 * d / sc; line.pathEffect = null; c.drawCircle(0f, 0f, halo, line) }
            }
            val color = when { p.urgent -> pal.no; p.air -> pal.acAir; p.dep -> pal.acDep; else -> pal.acGround }
            m.draw(c, color, pal.bldg, d / sc, if (p.urgent) blink else 1f, fill, line)
            c.restore()
        }

        val u = 1.9f * d                                    // tags and runway numbers keep their size on screen
        // the selected plane's route ahead
        if (tw != null) {
            val pts = tw.selectedRoute()
            if (pts.size > 1) {
                val path = Path()
                pts.forEachIndexed { i, q -> val x = sx(q.x.toFloat(), q.y.toFloat()); val y = sy(q.x.toFloat(), q.y.toFloat()); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                routePaint.color = pal.route; routePaint.strokeWidth = 3.5f * d; routePaint.pathEffect = DashPathEffect(floatArrayOf(.1f, 7f * d), 0f)
                c.drawPath(path, routePaint)
            }
            // runway numbers off each end
            gateText.textSize = 7 * u
            for (e in tw.ground.ends) {
                val q = e.at(-70.0); val px = sx(q.x.toFloat(), q.y.toFloat()); val py = sy(q.x.toFloat(), q.y.toFloat())
                val w = (e.name.length * 5 + 7) * u / 2
                fill.color = pal.runwaySign; c.drawRoundRect(px - w, py - 6 * u, px + w, py + 6 * u, 3 * u, 3 * u, fill)
                c.drawText(e.name, px, py + 7 * u * .36f, gateText)
            }
            // flight tags beside each plane
            tagBoxes.clear()
            tagText.textSize = 5.4f * u; tagSub.textSize = 4.2f * u
            for (p in tw.planes) {
                val px = sx(p.x.toFloat(), p.y.toFloat()); val py = sy(p.x.toFloat(), p.y.toFloat())
                if (px < -300 || py < -100 || px > width + 50 || py > height + 100) continue
                val st = if (p.needsYou && !p.landed && p.fuel > 0) mmss(p.fuel) + " fuel" else tw.status(p).first
                val off = Aircraft.get(p.type).size * .45f * sc + 3 * u
                val w = max(tagText.measureText(p.cs), tagSub.measureText(st)) + 7 * u
                val box = RectF(px + off, py - 6.5f * u, px + off + w, py + 6.5f * u)
                val sel = tw.selected === p
                val bg = when { p.urgent -> pal.no; p.needsYou -> pal.acNeed; else -> pal.solid }
                fill.color = bg; c.drawRoundRect(box, 4 * u, 4 * u, fill)
                line.pathEffect = null
                if (sel) { line.color = pal.route; line.strokeWidth = 1.2f * u; c.drawRoundRect(box, 4 * u, 4 * u, line) }
                else if (bg == pal.solid) { line.color = pal.hairline; line.strokeWidth = .5f * u; c.drawRoundRect(box, 4 * u, 4 * u, line) }
                val ink = if (bg == pal.solid) pal.label else android.graphics.Color.WHITE
                tagText.color = ink; tagSub.color = if (bg == pal.solid) pal.label2 else ink
                c.drawText(p.cs, box.left + 3.5f * u, py - 1.6f * u + 1.9f * u, tagText)
                c.drawText(st, box.left + 3.5f * u, py + 3.9f * u + 1.5f * u, tagSub)
                tagBoxes.add(box to p)
            }
        }
    }

    // ---------- Touch ----------
    private val ptrs = LinkedHashMap<Int, PointF>()
    private var moved = 0f; private var pinch: FloatArray? = null   // distance, mid x, mid y, angle, twist, on
    private var vt: VelocityTracker? = null
    private var fling: PointF? = null
    private var lastTap: Triple<Long, Float, Float>? = null

    private fun stepFling(dt: Float) {
        val f = fling ?: return
        tx += f.x * dt; ty += f.y * dt
        val q = exp(-dt / .32f); f.x *= q; f.y *= q
        if (hypot(f.x, f.y) < 20 * d) fling = null
        changed()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ptrs.clear(); ptrs[e.getPointerId(0)] = PointF(e.x, e.y)
                moved = 0f; pinch = null; anim = null; fling = null
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                kick()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; ptrs[e.getPointerId(i)] = PointF(e.getX(i), e.getY(i)); pinch = null; moved = 99 * d
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (ptrs.size == 1) {
                    val id = e.getPointerId(0); val prev = ptrs[id] ?: return true
                    val dx = e.getX(0) - prev.x; val dy = e.getY(0) - prev.y
                    moved += abs(dx) + abs(dy); prev.set(e.getX(0), e.getY(0))
                    tx += dx; ty += dy; changed()
                } else if (ptrs.size >= 2) {
                    for (i in 0 until e.pointerCount) ptrs[e.getPointerId(i)]?.set(e.getX(i), e.getY(i))
                    val (pa, pb) = ptrs.values.take(2)
                    val dist = hypot(pa.x - pb.x, pa.y - pb.y); val mx = (pa.x + pb.x) / 2; val my = (pa.y + pb.y) / 2
                    val ang = atan2(pb.y - pa.y, pb.x - pa.x)
                    val pp = pinch
                    if (pp != null) {
                        // two fingers twisting turn the map once past a small threshold, so a pinch doesn't wobble it
                        var turn = 0f; var twist = pp[4]; var on = pp[5] > 0
                        val da = wrap(ang - pp[3])
                        if (on) turn = da else { twist += da; if (abs(twist) > .2f) { on = true; turn = twist; performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) } }
                        zoomAt(mx, my, dist / pp[0])
                        if (turn != 0f) { val cs = cos(turn); val sn = sin(turn); val dx = tx - mx; val dy = ty - my; tx = mx + cs * dx - sn * dy; ty = my + sn * dx + cs * dy; r = wrap(r + turn) }
                        tx += mx - pp[1]; ty += my - pp[2]; changed()
                        pinch = floatArrayOf(dist, mx, my, ang, twist, if (on) 1f else 0f)
                    } else pinch = floatArrayOf(dist, mx, my, ang, 0f, 0f)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                ptrs.remove(e.getPointerId(e.actionIndex)); pinch = null
                for (i in 0 until e.pointerCount) if (i != e.actionIndex) ptrs[e.getPointerId(i)]?.set(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f; val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                ptrs.clear(); pinch = null
                if (moved < 8 * d) tap(e.x, e.y)
                else if (moved < 90 * d || hypot(vx, vy) > 300 * d) { if (hypot(vx, vy) > 150 * d) fling = PointF(vx, vy) }
                lastMove = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_CANCEL -> { ptrs.clear(); pinch = null; vt?.recycle(); vt = null }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            anim = null; zoomAt(e.x, e.y, exp(e.getAxisValue(MotionEvent.AXIS_VSCROLL) * .18f)); kick(); return true
        }
        return super.onGenericMotionEvent(e)
    }

    private fun tap(x: Float, y: Float) {
        val tw = tower; val a = ap ?: return
        // a plane or its tag
        if (tw != null) {
            tagBoxes.firstOrNull { it.first.contains(x, y) }?.let { listener?.onTapPlane(it.second); return }
            tw.planes.minByOrNull { hypot(sx(it.x.toFloat(), it.y.toFloat()) - x, sy(it.x.toFloat(), it.y.toFloat()) - y) }?.let { p ->
                if (hypot(sx(p.x.toFloat(), p.y.toFloat()) - x, sy(p.x.toFloat(), p.y.toFloat()) - y) < 26 * d) { listener?.onTapPlane(p); return }
            }
        }
        val s = d * kc.pow(.38f)
        // a gate number
        a.gates.filter { (gateShown[it.code] ?: 0) != 2 }.minByOrNull { hypot(sx(it.x, it.y) - x, sy(it.x, it.y) - y) }?.let { g ->
            val gx = sx(g.x, g.y); val gy = sy(g.x, g.y)
            if (hypot(gx - x, gy - y) < max(16 * d, 9 * s)) {
                if (listener?.onTapGate(g.code) == true) return
                zoomAnimAt(gx, gy, max(1f, 3 * d / k)); return
            }
        }
        // a concourse badge
        a.roundels.firstOrNull { hypot(sx(it.x, it.y) - x, sy(it.x, it.y) - y) < 16 * s }?.let { fitCon(it.con); return }
        listener?.onTapEmpty()
        // double-tap the map to zoom in
        val now = SystemClock.uptimeMillis(); val lt = lastTap
        if (lt != null && now - lt.first < 320 && hypot(x - lt.second, y - lt.third) < 30 * d) { zoomAnimAt(x, y, 2f); lastTap = null }
        else lastTap = Triple(now, x, y)
    }
}
