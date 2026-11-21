package io.github.encycloacid.clearwaytower

import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

// An airport pack, shared with the web version: OpenStreetMap layers plus gates, stands and labels.
// Map units are metres east (x) and south (y) of the airport's origin; north is up.

class Concourse(val id: String, val light: Int, val dark: Int, val shape: Path)
class Gate(val code: String, val x: Float, val y: Float) { val con: String get() = code.substring(0, 1) }
class StandDef(val code: String, val x: Float, val y: Float, val a: Float)
class MapLabel(val x: Float, val y: Float, val text: String, val start: Boolean, val level: String?)
class Roundel(val con: String, val x: Float, val y: Float)
class HallName(val x: Float, val y: Float, val angle: Float, val text: String)
/** The ticketing hall's floor plan (upper level), drawn like a plan: desks, the glass front, doors, names. */
class Halls(
    val floor: List<PointF>, val counters: List<List<PointF>>, val facade: List<PointF>, val doors: List<PointF>,
    val curb: List<PointF>, val zebra: List<FloatArray>, val names: List<HallName>,
)

class Airport(
    val code: String, val name: String, val city: String,
    val bounds: RectF,
    val concourses: List<Concourse>,
    val gates: List<Gate>,
    val stands: List<StandDef>,
    val labels: List<MapLabel>,
    val roundels: List<Roundel>,
    val securityLines: List<FloatArray>,
    val closedTerms: List<String>,
    val trainFloor: String?,
    val halls: Halls?,
    /** OpenStreetMap layers as SVG path data, by layer name (grass, runway, taxiway, termA…) */
    val layers: Map<String, String>,
    /** aircraft OpenStreetMap shows parked: x, y, heading in degrees */
    val parked: List<FloatArray>,
) {
    val conIds = concourses.map { it.id }
    fun concourse(id: String) = concourses.firstOrNull { it.id == id }

    companion object {
        fun list(ctx: Context): List<String> =
            (ctx.assets.list("airports") ?: emptyArray()).filter { it.endsWith(".json") }.map { it.removeSuffix(".json").uppercase() }.sorted()

        /** name and city of every pack, for the airport picker, without parsing the map layers */
        fun names(ctx: Context): List<Triple<String, String, String>> = list(ctx).map { code ->
            val text = ctx.assets.open("airports/${code.lowercase()}.json").bufferedReader().use { it.readText() }
            fun field(k: String) = Regex("\"$k\": \"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: code
            Triple(code, field("name"), field("city"))
        }

        fun load(ctx: Context, code: String): Airport {
            val j = JSONObject(ctx.assets.open("airports/${code.lowercase()}.json").bufferedReader().use { it.readText() })
            val b = j.getJSONObject("bounds")
            val base = j.getJSONObject("basemap")
            val layers = mutableMapOf<String, String>()
            for (k in base.keys()) (base.opt(k) as? String)?.let { if (it.isNotEmpty()) layers[k] = it }
            val parked = base.optJSONArray("planes").floats()
            val sec = j.optJSONArray("securityLine")
            val secLines = when {
                sec == null || sec.length() == 0 -> emptyList()
                sec.opt(0) is JSONArray -> (0 until sec.length()).map { sec.getJSONArray(it).toFloats() }
                else -> listOf(sec.toFloats())
            }
            return Airport(
                code = j.getString("code"), name = j.getString("name"), city = j.getString("city"),
                bounds = RectF(b.f("x0"), b.f("y0"), b.f("x1"), b.f("y1")),
                concourses = j.getJSONArray("concourses").objects().map {
                    val c = it.getJSONArray("color")
                    val s = it.optJSONArray("shape")
                    Concourse(it.getString("id"), Color.parseColor(c.getString(0)), Color.parseColor(c.getString(1)),
                        blob(s?.optInt(0) ?: 0, (s?.optDouble(1) ?: 0.0).toFloat()))
                },
                gates = j.getJSONArray("gates").arrays().map { Gate(it.getString(0), it.f(1), it.f(2)) },
                stands = (j.optJSONArray("stands") ?: JSONArray()).arrays().map { StandDef(it.getString(0), it.f(1), it.f(2), it.f(3)) },
                labels = j.getJSONArray("labels").arrays().map {
                    MapLabel(it.f(0), it.f(1), it.getString(2), it.optString(3) == "start", it.optString(4).takeIf { s -> s.isNotEmpty() && s != "null" })
                },
                roundels = j.getJSONArray("roundels").arrays().map { Roundel(it.getString(0), it.f(1), it.f(2)) },
                securityLines = secLines,
                closedTerms = (j.optJSONArray("closedTerms") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } },
                trainFloor = j.optString("trainFloor").takeIf { it.isNotEmpty() },
                halls = j.optJSONObject("halls")?.let { h ->
                    Halls(
                        floor = h.getJSONArray("tix").points(),
                        counters = h.getJSONArray("counters").arrays().map { it.points() },
                        facade = h.getJSONObject("facade").getJSONArray("up").points(),
                        doors = h.getJSONObject("doors").getJSONArray("up").points(),
                        curb = h.getJSONObject("curb").getJSONArray("up").points(),
                        zebra = h.getJSONObject("zebra").getJSONArray("up").floats(),
                        names = h.getJSONArray("names").arrays().filter { it.optString(4) == "up" }
                            .map { HallName(it.f(0), it.f(1), it.f(2), it.getString(3)) },
                    )
                },
                layers = layers, parked = parked,
            )
        }

        /** Material 3 Expressive-style badge: circle (n = 0), clover, cookie… radius 10 */
        fun blob(n: Int, amp: Float, r: Float = 10f): Path {
            val p = Path()
            if (n == 0) { p.addCircle(0f, 0f, r, Path.Direction.CW); return p }
            for (i in 0..144) {
                val th = i / 144.0 * Math.PI * 2
                val rr = r * (1 + amp * cos(n * th)) / (1 + amp)
                val x = (rr * sin(th)).toFloat(); val y = (-rr * cos(th)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close(); return p
        }

        /** the aircraft silhouette used on the map, nose up, 16 units long */
        val PLANE: Path by lazy {
            PathParser.createPathFromPathData("M0 -8C1 -8 1.3 -6.5 1.3 -5L1.3 -1.5L8 2L8 3.4L1.3 1.6L1.1 5.2L3 6.6L3 7.6L0 7L-3 7.6L-3 6.6L-1.1 5.2L-1.3 1.6L-8 3.4L-8 2L-1.3 -1.5L-1.3 -5C-1.3 -6.5 -1 -8 0 -8Z")
        }
    }
}

private fun JSONObject.f(k: String) = getDouble(k).toFloat()
private fun JSONArray.f(i: Int) = optDouble(i, 0.0).toFloat()
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.arrays() = (0 until length()).map { getJSONArray(it) }
private fun JSONArray.toFloats() = FloatArray(length()) { f(it) }
private fun JSONArray?.floats(): List<FloatArray> = if (this == null) emptyList() else arrays().map { it.toFloats() }
private fun JSONArray.points() = arrays().map { PointF(it.f(0), it.f(1)) }
