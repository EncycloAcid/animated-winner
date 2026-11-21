package io.github.encycloacid.clearwaytower

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ================= Clearway Tower: air traffic control on the airport map =================
// Planes arrive and hold until you clear them to land. Once they're off the runway you give them a
// gate (tap a gate number). After boarding you push them back to a runway and clear them for takeoff.
// A plane that runs out of fuel diverts, and clearing a takeoff onto a busy runway is an incursion:
// three strikes and the shift is over.

enum class St { INBOUND, HOLDING, MISSED, APPROACH, FINAL, ROLLOUT, VACATED, TAXIIN, BOARDING, READY, PUSHBACK, TAXIOUT, HOLDSHORT, LINEUP, TAKEOFF, CLIMB }

/** a stand: where a plane parks at a gate, nose to the terminal, and the taxiway node it pushes back to */
class Stand(val code: String, val x: Double, val y: Double, val a: Double, val push: P, val node: Int) { var plane: Plane? = null }

class Plane(val id: Int, val cs: String, var dep: Boolean) {
    var x = 0.0; var y = 0.0; var h = 0.0; var v = 0.0
    var air = false; var landed = false
    var state = St.INBOUND
    var path: List<P> = emptyList(); var pi = 0; var taxi: List<P> = emptyList()
    var fuel = 0.0; var t = 0.0; var wait = 0.0
    var blocked = 0.0; var blockedBy: Plane? = null; var ghost = 0.0
    var end: RunwayEnd? = null; var stand: Stand? = null
    var hold = P(0.0, 0.0)
    var waitedAtPush = 0.0; var incursion = false
    val needsYou get() = state in NEEDS
    val urgent get() = !landed && fuel > 0 && fuel < 30
    companion object { val NEEDS = setOf(St.INBOUND, St.HOLDING, St.VACATED, St.READY, St.HOLDSHORT) }
}

fun mmss(s: Double): String { val t = max(0, ceil(s).toInt()); return "${t / 60}:${(t % 60).toString().padStart(2, '0')}" }

class Tower(val ap: Airport, val base: BaseMap, private val best: Int, private val events: Events) {
    interface Events {
        fun toast(msg: String)
        fun buzz(strong: Boolean = false)
        fun over(score: Int, handled: Int, time: Double, why: String)
    }

    val ground = Ground(ap)
    val landEnds = ground.ends.filter { it.canLand }
    val departEnds = ground.ends.filter { it.canDepart }
    val stands: Map<String, Stand>
    val standList: List<Stand>
    /** the play area: the airport, its runways, and room to hold around them */
    val cx: Double; val cy: Double; val holdR: Double; val spawnR: Double
    val playBounds: android.graphics.RectF
    val airportBounds: android.graphics.RectF

    var on = false; var paused = false; var over = false; var speed = 1
    var t = 0.0; var score = 0; var strikes = 0; var handled = 0
    private var nextSpawn = 0.0
    val planes = ArrayList<Plane>()
    var selected: Plane? = null
    private var nextId = 1
    /** bumped whenever something the panel shows changes state */
    var version = 0; private set

    init {
        stands = ap.stands.associate { s ->
            val nose = vecOf(s.a.toDouble())
            val push = P(s.x - nose.x * 45, s.y - nose.y * 45)
            s.code to Stand(s.code, s.x.toDouble(), s.y.toDouble(), s.a.toDouble(), push, ground.nearest(push.x, push.y))
        }
        standList = stands.values.filter { it.node >= 0 }
        // the aircraft OpenStreetMap shows parked at gates make way for the game's own
        base.parked = ap.parked.filter { p -> stands.values.none { hypot(it.x - p[0], it.y - p[1]) < 30 } }
        val xs = listOf(ap.bounds.left, ap.bounds.right).map { it.toDouble() } + ground.runways.flatMap { listOf(it.a.x, it.b.x) }
        val ys = listOf(ap.bounds.top, ap.bounds.bottom).map { it.toDouble() } + ground.runways.flatMap { listOf(it.a.y, it.b.y) }
        cx = (xs.min() + xs.max()) / 2; cy = (ys.min() + ys.max()) / 2
        val half = max(xs.max() - xs.min(), ys.max() - ys.min()) / 2
        holdR = half + 350; spawnR = half + 1700
        // what "show the whole airport" fits: the terminals and runways
        airportBounds = android.graphics.RectF(xs.min().toFloat() - 150, ys.min().toFloat() - 150, xs.max().toFloat() + 150, ys.max().toFloat() + 150)
        val m = (holdR + 380).toFloat()
        playBounds = android.graphics.RectF(cx.toFloat() - m, cy.toFloat() - m, cx.toFloat() + m, cy.toFloat() + m)
    }

    private fun changed() { version++ }

    // ---------- Starting, stopping ----------
    fun start() {
        for (p in planes.toList()) remove(p)
        on = true; paused = false; over = false; t = 0.0; score = 0; strikes = 0; handled = 0; nextSpawn = 3.0; selected = null
        parkDeparture(5.0); parkDeparture(22.0); parkDeparture(45.0)
        changed()
    }
    fun pause(p: Boolean) { if (on) { paused = p; changed() } }

    private val names = listOf("Bluejay", "Cardinal", "Northwind", "Lakeshore", "Summit", "Harbor", "Prairie", "Redtail", "Skylark", "Juniper", "Comet", "Falcon", "Meridian", "Osprey", "Kestrel", "Tailwind")
    private fun callsign(): String {
        while (true) { val c = names.random() + " " + Random.nextInt(10, 990); if (planes.none { it.cs == c }) return c }
    }
    private fun rand(a: Double, b: Double) = a + Random.nextDouble() * (b - a)

    private fun make(dep: Boolean, x: Double, y: Double, h: Double) = Plane(nextId++, callsign(), dep).also { it.x = x; it.y = y; it.h = h; planes.add(it) }
    private fun remove(p: Plane) {
        planes.remove(p); p.stand?.let { if (it.plane === p) it.plane = null }
        if (selected === p) selected = null
        changed()
    }
    private fun parkDeparture(board: Double) {
        val free = standList.filter { it.plane == null }
        if (free.isEmpty()) return
        val s = free.random()
        val p = make(true, s.x, s.y, s.a)
        p.stand = s; s.plane = p; p.landed = true; p.state = St.BOARDING; p.t = board
    }
    private fun spawnArrival() {
        val mins = t / 60
        nextSpawn = t + (max(13.0, 32 - mins * 3) + rand(-4.0, 4.0)) * (if (ground.runways.size > 1) 1.0 else 2.2)   // one runway can take fewer
        if (planes.count { !it.dep && !it.landed } >= 9) return
        val b = rand(0.0, Math.PI * 2); val dx = cos(b); val dy = sin(b)
        val p = make(false, cx + dx * spawnR, cy + dy * spawnR, hdgOf(-dx, -dy))
        p.air = true; p.v = 110.0; p.state = St.INBOUND; p.fuel = rand(130.0, 175.0) - min(50.0, mins * 5)
        p.hold = P(cx + dx * holdR, cy + dy * holdR)
        changed()
    }

    // ---------- Runway use ----------
    private fun onRunway(p: Plane, r: Runway): Boolean {
        if (p.air) return false
        val s = r.along(p.x, p.y); val l = r.lateral(p.x, p.y)
        return abs(l) < 42 && s > -40 && s < r.len + 40
    }
    /** a runway is in use while anything is on it, or lining up and rolling for takeoff */
    fun runwayBusy(r: Runway, except: Plane? = null) =
        planes.any { it !== except && (onRunway(it, r) || ((it.state == St.LINEUP || it.state == St.TAKEOFF) && it.end?.r === r)) }
    fun finalOn(r: Runway, within: Double, except: Plane?) =
        planes.firstOrNull { it !== except && it.state == St.FINAL && it.end?.r === r && -it.end!!.along(it.x, it.y) < within }

    // ---------- Commands ----------
    fun clearToLand(p: Plane, e: RunwayEnd) {
        if (p.state !in setOf(St.INBOUND, St.HOLDING, St.MISSED)) return
        p.end = e; p.state = St.APPROACH; events.buzz()
        events.toast("${p.cs}, cleared to land runway ${e.name}"); changed()
    }
    fun goAround(p: Plane, why: String?) {
        p.state = St.MISSED; p.t = 0.0
        if (why != null) score = max(0, score - 50)
        events.toast(if (why != null) "${p.cs} going around — $why" else "${p.cs}, go around"); changed()
    }
    fun assignGate(p: Plane, s: Stand): Boolean {
        if (s.plane != null || (p.state != St.VACATED && p.state != St.TAXIIN)) return false
        p.stand?.plane = null
        val r = ground.route(ground.nearest(p.x, p.y, false), s.node)
        p.stand = s; s.plane = p
        p.path = (r ?: listOf(s.node)).map { ground.p(it) } + listOf(s.push, P(s.x, s.y)); p.pi = 0
        p.state = St.TAXIIN; events.buzz()
        events.toast("${p.cs}, taxi to gate ${s.code}"); changed()
        return true
    }
    fun nearestFreeStand(p: Plane) = standList.filter { it.plane == null }.minByOrNull { hypot(it.x - p.x, it.y - p.y) }
    fun pushTo(p: Plane, e: RunwayEnd) {
        val s = p.stand ?: return
        if (p.state != St.READY) return
        val r = ground.route(s.node, e.entryClear.last())
        p.end = e; p.state = St.PUSHBACK; p.waitedAtPush = p.wait
        p.path = listOf(s.push); p.pi = 0
        p.taxi = (r ?: emptyList()).map { ground.p(it) }
        events.buzz(); events.toast("${p.cs}, push back, taxi to runway ${e.name}"); changed()
    }
    fun clearTakeoff(p: Plane) {
        val e = p.end ?: return
        if (p.state != St.HOLDSHORT) return
        if (runwayBusy(e.r, p)) {
            // the pilot catches it after the first time; only the first wrong clearance is a strike
            if (p.incursion) events.toast("${p.cs}: unable, runway ${e.name} is still in use")
            else { p.incursion = true; strike("Runway incursion! ${e.name} wasn't clear") }
            return
        }
        p.state = St.LINEUP
        p.path = e.entryClear.reversed().drop(1).map { ground.p(it) } + listOf(e.at(max(0.0, e.entryAt))); p.pi = 0
        events.buzz(); events.toast("${p.cs}, runway ${e.name}, cleared for takeoff"); changed()
    }
    private fun strike(msg: String) {
        strikes++; events.buzz(true)
        events.toast(msg + if (strikes < 3) " · strike $strikes of 3" else "")
        changed()
        if (strikes >= 3) { on = false; over = true; events.over(score, handled, t, msg) }
    }

    // ---------- Moving things ----------
    private fun steer(p: Plane, tx: Double, ty: Double, rate: Double, dt: Double) {
        val want = hdgOf(tx - p.x, ty - p.y)
        p.h = (p.h + angDiff(p.h, want).coerceIn(-rate * dt, rate * dt) + 360) % 360
    }
    private fun fly(p: Plane, dt: Double) { val v = vecOf(p.h); p.x += v.x * p.v * dt; p.y += v.y * p.v * dt }
    /** along the path; true at its end. Pushback moves tail first. */
    private fun follow(p: Plane, dt: Double, v: Double, reverse: Boolean = false): Boolean {
        var left = v * dt
        while (left > 0 && p.pi < p.path.size) {
            val tg = p.path[p.pi]; val d = dist(p.x, p.y, tg.x, tg.y)
            if (d < .5) { p.pi++; continue }
            val want = if (reverse) hdgOf(p.x - tg.x, p.y - tg.y) else hdgOf(tg.x - p.x, tg.y - p.y)
            if (d > 3) p.h = (p.h + angDiff(p.h, want).coerceIn(-140 * dt, 140 * dt) + 360) % 360
            val step = min(left, d)
            p.x += (tg.x - p.x) / d * step; p.y += (tg.y - p.y) / d * step; left -= step
            if (step == d) p.pi++
        }
        return p.pi >= p.path.size
    }
    // on the ground, stop for a plane just ahead going the same way, or standing still. Planes queue
    // behind one waiting at the same runway; one stuck a while behind a parked plane squeezes past.
    private fun blockedAhead(p: Plane, dt: Double): Boolean {
        if (p.ghost > 0) { p.ghost -= dt; return false }
        // never stop on a runway: a queue reaching back across it would shut it for everyone
        if (ground.runways.any { onRunway(p, it) }) { p.blocked = 0.0; p.blockedBy = null; return false }
        val f = vecOf(p.h)
        for (q in planes) {
            if (q === p || q.air || q.state == St.BOARDING || q.state == St.READY) continue
            val dx = q.x - p.x; val dy = q.y - p.y; val d = hypot(dx, dy)
            if (d < 58 && d > 1 && (dx * f.x + dy * f.y) / d > .75) {
                val still = q.state == St.HOLDSHORT || q.state == St.VACATED || q.blocked > 0
                if (!still && abs(angDiff(p.h, q.h)) > 70) continue
                p.blocked += dt; p.blockedBy = q
                val queue = q.state == St.HOLDSHORT && p.end === q.end
                val patience = if (q.blockedBy === p) 1.0 else if (queue) Double.MAX_VALUE else 6.0
                if (p.blocked > patience) { p.blocked = 0.0; p.blockedBy = null; p.ghost = 4.0; return false }
                return true
            }
        }
        p.blocked = 0.0; p.blockedBy = null; return false
    }

    /** advance the game by dt seconds of game time (call with small steps) */
    fun step(dt: Double) {
        if (!on || paused) return
        t += dt
        if (t >= nextSpawn) spawnArrival()
        for (p in planes.toList()) {
            if (!p.landed && !p.dep) {
                p.fuel -= dt
                if (p.fuel <= 0) { remove(p); strike("${p.cs} ran out of fuel and diverted"); if (over) return; continue }
            }
            when (p.state) {
                St.INBOUND -> {
                    p.v = 110.0; steer(p, p.hold.x, p.hold.y, 30.0, dt); fly(p, dt)
                    if (dist(p.x, p.y, p.hold.x, p.hold.y) < 320) { p.state = St.HOLDING; changed() }
                }
                St.HOLDING -> {
                    p.v = 100.0
                    val a = atan2(p.y - p.hold.y, p.x - p.hold.x) + .7
                    steer(p, p.hold.x + cos(a) * 300, p.hold.y + sin(a) * 300, 34.0, dt); fly(p, dt)
                }
                St.MISSED -> {
                    p.v = 110.0; p.t += dt
                    if (p.t > 8) steer(p, p.hold.x, p.hold.y, 30.0, dt)
                    fly(p, dt)
                    if (p.t > 8 && dist(p.x, p.y, p.hold.x, p.hold.y) < 320) { p.state = St.HOLDING; changed() }
                }
                St.APPROACH -> {
                    val e = p.end!!; val faf = e.at(-1400.0)
                    p.v = 105.0; steer(p, faf.x, faf.y, 32.0, dt); fly(p, dt)
                    val s = e.along(p.x, p.y)
                    val aligned = abs(angDiff(p.h, e.hdg)) < 50 && abs(e.lateral(p.x, p.y)) < 350 && s < -400 && s > -2600
                    if (dist(p.x, p.y, faf.x, faf.y) < 450 || aligned) p.state = St.FINAL
                }
                St.FINAL -> {
                    val e = p.end!!; val s = e.along(p.x, p.y)
                    p.v += (75 - p.v) * min(1.0, dt * .6)
                    val aim = e.at(s + 650); steer(p, aim.x, aim.y, 26.0, dt); fly(p, dt)
                    if (s > -1100 && s < -40 && runwayBusy(e.r, p)) { goAround(p, "runway ${e.name} in use"); continue }
                    if (abs(e.lateral(p.x, p.y)) > 900 || s < -3500) { p.state = St.APPROACH; continue }
                    if (s >= 0) {
                        val l = e.lateral(p.x, p.y); p.x += e.uy * l; p.y -= e.ux * l   // onto the centreline
                        p.h = e.hdg; p.air = false; p.landed = true; p.state = St.ROLLOUT
                        p.path = listOf(e.at(e.exitAt)) + e.exitClear.map { ground.p(it) }; p.pi = 0
                        score += 50; changed()
                    }
                }
                St.ROLLOUT -> {
                    val toExit = if (p.pi == 0) dist(p.x, p.y, p.path[0].x, p.path[0].y) else 0.0
                    p.v = if (p.pi == 0) max(16.0, min(p.v - 1.5 * dt, sqrt(16.0 * 16 + 2 * 3.5 * toExit))) else 20.0
                    // once off the centreline, wait behind anyone ahead (a plane left waiting for a gate blocks the turn-off)
                    if ((p.pi == 0 || !blockedAhead(p, dt)) && follow(p, dt, p.v)) { p.state = St.VACATED; p.wait = 0.0; changed() }
                }
                St.VACATED, St.READY, St.HOLDSHORT -> p.wait += dt
                St.TAXIIN -> {
                    if (p.pi >= p.path.size - 2) { if (follow(p, dt, 8.0)) arriveAtGate(p) }
                    else if (!blockedAhead(p, dt)) follow(p, dt, 24.0)
                }
                St.BOARDING -> { p.t -= dt; if (p.t <= 0) { p.state = St.READY; p.wait = 0.0; changed() } }
                St.PUSHBACK -> if (follow(p, dt, 9.0, true)) {
                    p.state = St.TAXIOUT; p.path = p.taxi; p.pi = 0
                    p.stand?.let { if (it.plane === p) it.plane = null }; p.stand = null; changed()
                }
                St.TAXIOUT -> if (!blockedAhead(p, dt) && follow(p, dt, 24.0)) { p.state = St.HOLDSHORT; p.wait = 0.0; changed() }
                St.LINEUP -> if (follow(p, dt, 15.0)) {
                    val d = angDiff(p.h, p.end!!.hdg)
                    p.h = (p.h + d.coerceIn(-120 * dt, 120 * dt) + 360) % 360
                    if (abs(d) < 1) { p.h = p.end!!.hdg; p.state = St.TAKEOFF; p.v = 0.0 }
                }
                St.TAKEOFF -> { p.v += 5 * dt; fly(p, dt); if (p.v > 80) { p.state = St.CLIMB; p.air = true; changed() } }
                St.CLIMB -> {
                    p.v = min(130.0, p.v + 6 * dt); fly(p, dt)
                    if (dist(p.x, p.y, cx, cy) > spawnR) { handled++; score += 100 + if (p.waitedAtPush < 30) 50 else 0; remove(p) }
                }
            }
        }
    }
    private fun arriveAtGate(p: Plane) {
        p.state = St.BOARDING; p.dep = true; p.t = rand(18.0, 32.0); p.h = p.stand!!.a; p.v = 0.0
        handled++; score += 100; changed()
    }

    // ---------- What the panel shows ----------
    fun status(p: Plane): Pair<String, String> {
        val e = p.end?.name ?: ""; val g = p.stand?.code ?: ""
        return when (p.state) {
            St.INBOUND -> "Inbound" to "Arriving · fuel ${mmss(p.fuel)}"
            St.HOLDING -> "Holding" to "Holding · fuel ${mmss(p.fuel)}"
            St.MISSED -> "Go-around" to "Going around · fuel ${mmss(p.fuel)}"
            St.APPROACH, St.FINAL -> "Land $e" to "Cleared to land $e · fuel ${mmss(p.fuel)}"
            St.ROLLOUT -> "Landing" to "Landing on $e"
            St.VACATED -> "Needs gate" to "Off the runway · needs a gate"
            St.TAXIIN -> "To $g" to "Taxiing to gate $g"
            St.BOARDING -> "Boarding" to "Boarding at $g · ready in ${mmss(p.t)}"
            St.READY -> "Ready" to "Ready at $g · waiting ${mmss(p.wait)}"
            St.PUSHBACK, St.TAXIOUT -> "To $e" to "Taxiing to runway $e"
            St.HOLDSHORT -> "Hold $e" to "Holding short of $e"
            St.LINEUP, St.TAKEOFF -> "Departing" to "Taking off from $e"
            St.CLIMB -> "Departed" to "Departed"
        }
    }
    fun hint(p: Plane) = when (p.state) {
        St.INBOUND, St.HOLDING -> "Pick a runway to land on. Keep an eye on the fuel."
        St.MISSED -> "Clear it to land again when the runway is free."
        St.VACATED -> "Tap a free gate on the map, or send it to the nearest one."
        St.TAXIIN -> "Tap another free gate to change."
        St.READY -> "Pick a runway. It pushes back and taxis to wait short of it."
        St.HOLDSHORT -> "Clear it only when the runway is empty. Arrivals close in will have to go around."
        else -> null
    }
    val waiting: List<Plane> get() = planes.filter { it.needsYou }
        .sortedWith(compareBy<Plane> { if (it.landed) 999.0 else it.fuel }.thenByDescending { it.wait })
    val picking get() = selected?.let { it.state == St.VACATED || it.state == St.TAXIIN } ?: false
    /** the selected plane's route ahead, for the dotted line */
    fun selectedRoute(): List<P> {
        val p = selected ?: return emptyList()
        val e = p.end
        return when (p.state) {
            St.APPROACH -> listOf(P(p.x, p.y), e!!.at(-1400.0), e.thr)
            St.FINAL -> listOf(P(p.x, p.y), e!!.thr)
            St.ROLLOUT, St.TAXIIN, St.TAXIOUT, St.LINEUP -> listOf(P(p.x, p.y)) + p.path.drop(p.pi)
            St.PUSHBACK -> listOf(P(p.x, p.y)) + p.path.drop(p.pi) + p.taxi
            else -> emptyList()
        }
    }
    @Suppress("unused") val bestScore get() = best
}
