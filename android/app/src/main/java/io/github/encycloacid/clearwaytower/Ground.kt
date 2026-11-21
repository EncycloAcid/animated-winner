package io.github.encycloacid.clearwaytower

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class P(val x: Double, val y: Double)

fun dist(ax: Double, ay: Double, bx: Double, by: Double) = hypot(ax - bx, ay - by)
/** compass heading of a vector: 0 = north, clockwise (y points south) */
fun hdgOf(dx: Double, dy: Double) = (Math.toDegrees(atan2(dx, -dy)) + 360) % 360
fun vecOf(h: Double) = P(sin(Math.toRadians(h)), -cos(Math.toRadians(h)))
fun angDiff(a: Double, b: Double) = ((b - a) % 360 + 540) % 360 - 180

/** SVG path data made only of M, L and Z (OpenStreetMap ways) → polylines */
fun polylines(d: String?): List<List<P>> {
    if (d.isNullOrEmpty()) return emptyList()
    return d.split('M').filter { it.isNotBlank() }.map { part ->
        part.replace("Z", "").split('L').mapNotNull { s ->
            val n = s.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            if (n.size == 2) P(n[0], n[1]) else null
        }
    }.filter { it.size > 1 }
}

class Runway(val i: Int, val a: P, val b: P) {
    val len = dist(a.x, a.y, b.x, b.y)
    val ux = (b.x - a.x) / len; val uy = (b.y - a.y) / len
    fun along(x: Double, y: Double) = (x - a.x) * ux + (y - a.y) * uy
    fun lateral(x: Double, y: Double) = (x - a.x) * -uy + (y - a.y) * ux
}

/** One direction of a runway: where landings touch down, where they turn off, where departures wait. */
class RunwayEnd(val r: Runway, val fwd: Boolean) {
    val thr = if (fwd) r.a else r.b
    val ux = if (fwd) r.ux else -r.ux; val uy = if (fwd) r.uy else -r.uy
    val hdg = hdgOf(ux, uy)
    val num = ((hdg / 10).roundToInt() % 36).let { if (it == 0) 36 else it }
    var sfx = ""
    val name get() = num.toString().padStart(2, '0') + sfx
    /** distance from the threshold of the turn-off (landing) or the entry (takeoff), and the taxiway nodes out to well clear */
    var exitAt = 0.0; var exitClear = emptyList<Int>(); var hasExit = false
    var entryAt = 0.0; var entryClear = emptyList<Int>(); var hasEntry = false
    fun along(x: Double, y: Double) = (x - thr.x) * ux + (y - thr.y) * uy
    fun lateral(x: Double, y: Double) = (x - thr.x) * -uy + (y - thr.y) * ux
    fun at(s: Double) = P(thr.x + ux * s, thr.y + uy * s)
    val canLand get() = hasExit && exitAt >= 300 && exitAt <= r.len
    val canDepart get() = hasEntry && r.len - max(0.0, entryAt) >= 1100
}

/** The airport's taxiways and runways from OpenStreetMap as a graph planes can be routed on. */
class Ground(ap: Airport) {
    val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
    val adj = ArrayList<MutableList<Int>>(); val rw = ArrayList<Int>()
    lateinit var main: BooleanArray
    val runways: List<Runway>
    val ends = ArrayList<RunwayEnd>()
    private val grid = HashMap<Long, MutableList<Int>>()

    fun p(n: Int) = P(xs[n], ys[n])
    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    private fun node(x: Double, y: Double, r: Int = -1): Int {
        val cx = floor(x / 6).toInt(); val cy = floor(y / 6).toInt()
        for (i in -1..1) for (j in -1..1) grid[key(cx + i, cy + j)]?.forEach { n ->
            if (hypot(xs[n] - x, ys[n] - y) < 3) { if (r >= 0) rw[n] = r; return n }
        }
        val n = xs.size
        xs.add(x); ys.add(y); adj.add(mutableListOf()); rw.add(r)
        grid.getOrPut(key(cx, cy)) { mutableListOf() }.add(n)
        return n
    }
    private fun link(a: Int, b: Int) { if (a == b || adj[a].contains(b)) return; adj[a].add(b); adj[b].add(a) }
    private fun unlink(a: Int, b: Int) { adj[a].remove(b); adj[b].remove(a) }

    init {
        runways = polylines(ap.layers["runway"]).mapIndexed { i, pts -> Runway(i, pts.first(), pts.last()) }
        class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double, val r: Int) { val ts = mutableListOf(0.0, 1.0) }
        val segs = ArrayList<Seg>()
        for (pl in polylines(ap.layers["taxiway"])) for (i in 1 until pl.size) segs.add(Seg(pl[i - 1].x, pl[i - 1].y, pl[i].x, pl[i].y, -1))
        for (r in runways) segs.add(Seg(r.a.x, r.a.y, r.b.x, r.b.y, r.i))
        // where ways cross without sharing a point, split both there
        for (i in segs.indices) {
            val s = segs[i]
            val sx0 = min(s.ax, s.bx); val sx1 = max(s.ax, s.bx); val sy0 = min(s.ay, s.by); val sy1 = max(s.ay, s.by)
            for (j in i + 1 until segs.size) {
                val o = segs[j]
                if (sx0 > max(o.ax, o.bx) || min(o.ax, o.bx) > sx1 || sy0 > max(o.ay, o.by) || min(o.ay, o.by) > sy1) continue
                val d = (s.bx - s.ax) * (o.by - o.ay) - (s.by - s.ay) * (o.bx - o.ax)
                if (d == 0.0) continue
                val t = ((o.ax - s.ax) * (o.by - o.ay) - (o.ay - s.ay) * (o.bx - o.ax)) / d
                val u = ((o.ax - s.ax) * (s.by - s.ay) - (o.ay - s.ay) * (s.bx - s.ax)) / d
                if (t > .001 && t < .999 && u > .001 && u < .999) { s.ts.add(t); o.ts.add(u) }
            }
        }
        for (s in segs) {
            var prev = -1
            for (t in s.ts.distinct().sorted()) {
                val n = node(s.ax + (s.bx - s.ax) * t, s.ay + (s.by - s.ay) * t, s.r)
                if (prev >= 0) link(prev, n)
                prev = n
            }
        }
        // taxiways that stop short of another way (most stop at the runway edge): join them to it
        val dangling = (0 until xs.size).filter { adj[it].size == 1 && rw[it] < 0 }
        for (n in dangling) {
            var bd = Double.MAX_VALUE; var bp = -1; var bq = -1; var bt = 0.0
            for (p in 0 until xs.size) for (q in adj[p]) {
                if (q <= p || p == n || q == n || adj[n].contains(p) || adj[n].contains(q)) continue
                val dx = xs[q] - xs[p]; val dy = ys[q] - ys[p]; val l2 = (dx * dx + dy * dy).takeIf { it > 0 } ?: 1.0
                val t = ((xs[n] - xs[p]) * dx + (ys[n] - ys[p]) * dy) / l2
                val tc = t.coerceIn(0.0, 1.0)
                val d = hypot(xs[p] + dx * tc - xs[n], ys[p] + dy * tc - ys[n])
                val lim = if (rw[p] >= 0 && rw[q] >= 0) 34.0 else 14.0
                if (d < lim && d < bd) { bd = d; bp = p; bq = q; bt = tc }
            }
            if (bp < 0) continue
            when {
                bt < .02 -> link(n, bp)
                bt > .98 -> link(n, bq)
                else -> {
                    val onRw = rw[bp] >= 0 && rw[bq] >= 0
                    val m = node(xs[bp] + (xs[bq] - xs[bp]) * bt, ys[bp] + (ys[bq] - ys[bp]) * bt, if (onRw) rw[bp] else -1)
                    unlink(bp, bq); link(bp, m); link(m, bq); link(n, m)
                }
            }
        }
        // the ground network is the biggest connected piece that has a runway in it
        val comp = IntArray(xs.size) { -1 }
        var best = -1; var bestScore = -1L
        for (s in 0 until xs.size) {
            if (comp[s] >= 0) continue
            val st = ArrayDeque<Int>(); st.add(s); comp[s] = s
            var size = 0L; var rws = 0
            while (st.isNotEmpty()) {
                val n = st.removeLast(); size++; if (rw[n] >= 0) rws++
                for (m in adj[n]) if (comp[m] < 0) { comp[m] = s; st.add(m) }
            }
            val score = size + if (rws > 0) 1_000_000L else 0L
            if (score > bestScore) { bestScore = score; best = s }
        }
        main = BooleanArray(xs.size) { comp[it] == best }

        // runway ends: names, where landings turn off, where departures join
        for (r in runways) {
            class Conn(val n: Int, val m: Int, val s: Double)
            val conns = ArrayList<Conn>()
            for (n in 0 until xs.size) if (rw[n] == r.i && main[n]) for (m in adj[n]) if (rw[m] < 0 && main[m]) conns.add(Conn(n, m, r.along(xs[n], ys[n])))
            for (fwd in listOf(true, false)) {
                val e = RunwayEnd(r, fwd)
                fun sOf(c: Conn) = if (fwd) c.s else r.len - c.s
                val ex = conns.filter { sOf(it) > 650 }.minByOrNull { sOf(it) } ?: conns.maxByOrNull { sOf(it) }
                val en = conns.filter { sOf(it) > -40 && sOf(it) < r.len * .45 }.minByOrNull { sOf(it) } ?: conns.minByOrNull { sOf(it) }
                if (ex != null) { e.hasExit = true; e.exitAt = sOf(ex); e.exitClear = walkClear(r, ex.m, ex.n) }
                if (en != null) { e.hasEntry = true; e.entryAt = sOf(en); e.entryClear = walkClear(r, en.m, en.n) }
                ends.add(e)
            }
        }
        // parallel runways: L, C and R from the left as you look down them
        for (e in ends) {
            val same = ends.filter { it.num == e.num }
            if (same.size < 2) continue
            val sorted = same.sortedBy { it.thr.x * -it.uy + it.thr.y * it.ux }
            sorted.forEachIndexed { i, o -> o.sfx = if (sorted.size == 2) "LR"[i].toString() else if (i == 0) "L" else if (i == sorted.size - 1) "R" else "C" }
        }
    }

    // from a runway node, walk out along the taxiway until well clear of the runway
    private fun walkClear(r: Runway, start: Int, from: Int): List<Int> {
        val out = ArrayList<Int>(); var n = start; var prev = from
        for (k in 0 until 8) {
            out.add(n)
            if (abs(r.lateral(xs[n], ys[n])) > 70 && rw[n] < 0) break
            val next = adj[n].filter { it != prev && rw[it] < 0 }.maxByOrNull { abs(r.lateral(xs[it], ys[it])) } ?: break
            prev = n; n = next
        }
        return out
    }

    fun nearest(x: Double, y: Double, offRunway: Boolean = true): Int {
        var best = -1; var bd = Double.MAX_VALUE
        for (n in 0 until xs.size) {
            if (!main[n] || (offRunway && rw[n] >= 0)) continue
            val d = hypot(xs[n] - x, ys[n] - y)
            if (d < bd) { bd = d; best = n }
        }
        return best
    }

    /** shortest route; taxiing along a runway costs more than going round it */
    fun route(from: Int, to: Int): List<Int>? {
        if (from < 0 || to < 0) return null
        val n = xs.size
        val d = DoubleArray(n) { Double.MAX_VALUE }; val prev = IntArray(n) { -1 }; val done = BooleanArray(n)
        val q = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        d[from] = 0.0; q.add(0.0 to from)
        while (q.isNotEmpty()) {
            val (dn, u) = q.poll()!!
            if (done[u]) continue
            done[u] = true
            if (u == to) break
            for (v in adj[u]) {
                val w = hypot(xs[v] - xs[u], ys[v] - ys[u]) * if (rw[v] >= 0 && rw[u] >= 0) 8 else 1
                if (dn + w < d[v]) { d[v] = dn + w; prev[v] = u; q.add(d[v] to v) }
            }
        }
        if (d[to] == Double.MAX_VALUE) return null
        val out = ArrayList<Int>(); var v = to
        while (v >= 0) { out.add(v); v = prev[v] }
        return out.reversed()
    }
}
