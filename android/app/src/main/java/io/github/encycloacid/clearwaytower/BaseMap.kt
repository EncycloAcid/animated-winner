package io.github.encycloacid.clearwaytower

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paints an airport's OpenStreetMap layers: grass, aprons, runways and taxiways, roads, buildings,
 * terminals tinted by concourse, jet bridges, walkways and the ticketing hall's floor plan.
 * Shapes are parsed once; [paint] is called on a background thread into a bitmap.
 */
class BaseMap(private val ap: Airport) {
    private val paths: Map<String, Path> = ap.layers.mapValues { (_, d) ->
        PathParser.createPathFromPathData(d).apply { fillType = Path.FillType.EVEN_ODD }
    }
    /** parked aircraft that aren't on one of the game's stands */
    @Volatile var parked: List<FloatArray> = ap.parked
    private val parkedPlane = Path(Aircraft.get("narrow").silhouette).apply { fillType = Path.FillType.WINDING }

    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private fun poly(pts: List<PointF>, close: Boolean = true) = Path().apply {
        pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        if (close) close()
    }
    private val hallFloor = ap.halls?.let { poly(it.floor) }
    private val hallCurb = ap.halls?.let { poly(it.curb) }
    private val hallFacade = ap.halls?.let { poly(it.facade, false) }

    /**
     * @param m map units → bitmap pixels
     * @param kc zoom in density-independent pixels per metre (hairlines and dashes depend on it)
     * @param rot how far the map is turned, radians clockwise
     */
    fun paint(c: Canvas, m: Matrix, kc: Float, rot: Float, pal: Palette) {
        c.drawColor(pal.map)
        c.save(); c.concat(m)
        fun fill(key: String, color: Int) { paths[key]?.let { fillP.color = color; c.drawPath(it, fillP) } }
        fun stroke(key: String, color: Int, w: Float, dash: FloatArray? = null) {
            paths[key]?.let {
                strokeP.color = color; strokeP.strokeWidth = w
                strokeP.pathEffect = dash?.let { d -> DashPathEffect(d, 0f) }
                c.drawPath(it, strokeP)
            }
        }
        val hair = { w: Float -> w / max(kc, .5f) }

        fill("grass", pal.grass); fill("wood", pal.wood); fill("water", pal.water)
        fill("apron", pal.apron); fill("lots", pal.lot); fill("ramp", pal.apron)
        stroke("taxiway", pal.taxi, 23f); stroke("runway", pal.runway, 46f)
        // runway centreline dashes, then the taxiway centrelines
        if (kc > .35f) stroke("runway", pal.runwayMark, .9f, floatArrayOf(36f, 24f))
        stroke("taxiway", pal.taxiLine, 1.1f / kc)
        val roads = listOf("service" to 5f, "minor" to 9f, "major" to 11f, "motorway" to 15f)
        for ((r, w) in roads) stroke(r, if (r == "motorway") pal.mwayCase else pal.roadCase, w + 2.6f)
        for ((r, w) in roads) stroke(r, if (r == "motorway") pal.mwayFill else pal.roadFill, w)
        stroke("rail", pal.rail, 2.2f, floatArrayOf(6f, 4f))
        fill("buildings", pal.otherBldg); stroke("buildings", pal.bldgEdge, hair(.6f))
        fill("garage", pal.garage); stroke("garage", pal.bldgEdge, hair(.8f))
        // parked aircraft: the narrowbody model, in one colour
        fillP.color = pal.plane
        for (p in parked) {
            c.save(); c.translate(p[0], p[1]); c.rotate(p[2]); c.drawPath(parkedPlane, fillP); c.restore()
        }
        // terminals raised on a soft shadow (falling down the screen whichever way the map is turned)
        val terms = listOf("termM") + ap.conIds.map { "term$it" } + ap.closedTerms
        c.save(); c.translate(2 * cos(rot) + 4.5f * sin(rot), -2 * sin(rot) + 4.5f * cos(rot))
        for (t in terms) fill(t, pal.bldgShadow)
        c.restore()
        val edge = hair(1f)
        for (t in ap.closedTerms) { fill(t, pal.termClosed); stroke(t, pal.bldgEdge, edge, floatArrayOf(3f, 2f)) }
        fill("termM", pal.bldg); stroke("termM", pal.bldgEdge, edge)
        for (con in ap.concourses) {
            fill("term${con.id}", Palette.mix(pal.con(con), pal.bldg, pal.conTint))
            stroke("term${con.id}", pal.bldgEdge, edge)
        }
        stroke("bridges", pal.bridge, 3.6f)
        // the ticketing hall: the curbside sidewalk and crosswalks, then its floor
        val halls = ap.halls
        if (halls != null && hallFloor != null) {
            fillP.color = pal.curb; c.drawPath(hallCurb!!, fillP)
            strokeP.color = pal.zebra; strokeP.strokeCap = Paint.Cap.BUTT; strokeP.pathEffect = DashPathEffect(floatArrayOf(.6f, .6f), 0f)
            for (z in halls.zebra) {
                val a = Math.toDegrees(atan2(z[3] - z[1], z[2] - z[0]).toDouble()).toFloat(); val len = hypot(z[2] - z[0], z[3] - z[1])
                c.save(); c.translate(z[0], z[1]); c.rotate(a); strokeP.strokeWidth = len
                c.drawLine(len / 2, -1.6f, len / 2, 1.6f, strokeP); c.restore()
            }
            strokeP.pathEffect = null; strokeP.strokeCap = Paint.Cap.ROUND
            fillP.color = pal.hall; c.drawPath(hallFloor, fillP)
            strokeP.color = pal.hallEdge; strokeP.strokeWidth = 1.2f / max(kc, .5f); c.drawPath(hallFloor, strokeP)
        }
        // the gate level's walkways (OpenStreetMap indoor ways)
        stroke("walk1", pal.corridor, 5f)
        // an airport train on this level (DFW's Skylink): a track with ties
        if (paths.containsKey("train") && ap.trainFloor == "up") {
            stroke("train", Palette.mix(pal.stairs, pal.map, .75f), 6 / max(kc, .6f))
            stroke("train", pal.corridor, 2.4f / max(kc, .6f), floatArrayOf(4f, 5f))
        }
        strokeP.strokeCap = Paint.Cap.BUTT
        stroke("stairs", Palette.mix(pal.stairs, pal.map, .6f), 2f, floatArrayOf(.4f, .5f))
        strokeP.strokeCap = Paint.Cap.ROUND
        if (halls != null) paintHall(c, halls, kc, pal)
        // the checkpoint lines across the corridors from ticketing
        strokeP.color = pal.route; strokeP.alpha = (255 * .75f).toInt(); strokeP.strokeWidth = 1.8f
        strokeP.pathEffect = DashPathEffect(floatArrayOf(3f, 2.4f), 0f)
        for (l in ap.securityLines) c.drawLine(l[0], l[1], l[2], l[3], strokeP)
        strokeP.alpha = 255; strokeP.pathEffect = null
        c.restore()
    }

    // check-in desks with the bag belt behind them, the glass front, doors, and the names on the floor
    private fun paintHall(c: Canvas, h: Halls, kc: Float, pal: Palette) {
        for (run in h.counters) for (i in 0 until run.size - 1) {
            val a = run[i]; val b = run[i + 1]
            val ang = Math.toDegrees(atan2(b.y - a.y, b.x - a.x).toDouble()).toFloat(); val len = hypot(b.x - a.x, b.y - a.y)
            c.save(); c.translate(a.x, a.y); c.rotate(ang)
            fillP.color = pal.hallEdge; c.drawRect(-.2f, 1.5f, len + .2f, 2.2f, fillP)
            fillP.color = pal.counter; c.drawRoundRect(.3f, -.8f, len - .3f, 1.1f, .5f, .5f, fillP)
            c.restore()
        }
        strokeP.color = pal.facade; strokeP.strokeWidth = 1.3f; c.drawPath(hallFacade!!, strokeP)
        strokeP.color = pal.hall; strokeP.strokeWidth = .4f; c.drawPath(hallFacade, strokeP)
        for (d in h.doors) {
            c.save(); c.translate(d.x, d.y); c.rotate(if (d.y < -135) -44.6f else -78.4f)
            fillP.color = pal.door; c.drawRoundRect(-2.6f, -.9f, 2.6f, .9f, .9f, .9f, fillP)
            fillP.color = pal.hall; c.drawRect(-1.6f, -.3f, 1.6f, .3f, fillP)
            c.restore()
        }
        val fs = min(5.2f, 15 / kc)
        textP.textSize = fs; textP.letterSpacing = .24f
        for (n in h.names) {
            val road = n.text.contains("DRIVE")
            if (kc < (if (road) 1.3f else .9f)) continue
            c.save(); c.translate(n.x, n.y); c.rotate(n.angle)
            textP.color = if (road) pal.door else pal.hallText; textP.alpha = ((if (road) .7f else .6f) * 255).toInt()
            c.drawText(n.text, 0f, fs * .35f, textP)
            c.restore()
        }
    }
}
