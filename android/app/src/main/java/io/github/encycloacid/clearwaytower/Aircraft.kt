package io.github.encycloacid.clearwaytower

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import org.json.JSONObject

/**
 * The aircraft, seen from above: a regional jet, a narrowbody and a widebody, in metres, nose up and
 * centred on the fuselage. Shared with the web version (src/aircraft.json, made by tools/aircraft.py).
 * Each is drawn flat-shaded from one colour: the plane's status colour.
 */
class Aircraft(val type: String, val len: Float, val span: Float, private val j: JSONObject) {
    private fun part(k: String) = PathParser.createPathFromPathData(j.getString(k))
    val wing = part("wing"); val eng = part("eng"); val intake = part("intake"); val fuse = part("fuse")
    val hl = part("hl"); val fin = part("fin"); val win = part("win")
    /** the outline of the whole plane: for its shadow, the thin edge around it, and parked aircraft */
    val silhouette = Path().apply { addPath(wing); addPath(eng); addPath(fuse) }
    val size get() = maxOf(len, span)

    /** draw at the canvas origin, already turned and scaled to metres; [px] is one screen pixel in metres */
    fun draw(c: Canvas, color: Int, edge: Int, px: Float, alpha: Float, p: Paint, line: Paint) {
        val a = (255 * alpha).toInt()
        line.color = edge; line.alpha = a; line.strokeWidth = 1.6f * px; line.pathEffect = null
        c.drawPath(silhouette, line)
        fun fill(path: Path, col: Int, al: Float = 1f) { p.color = col; p.alpha = (a * al).toInt(); c.drawPath(path, p) }
        fill(wing, color)
        fill(eng, Palette.mix(color, Color.BLACK, .84f))
        fill(intake, WINDOW, .72f)
        fill(fuse, Palette.mix(color, Color.WHITE, .78f))
        fill(hl, Color.WHITE, .22f)
        fill(fin, Palette.mix(color, Color.BLACK, .70f))
        fill(win, WINDOW, .72f)
        p.alpha = 255; line.alpha = 255
    }

    companion object {
        private val WINDOW = Color.parseColor("#1b2433")
        @Volatile private var models: Map<String, Aircraft>? = null
        fun load(ctx: Context): Map<String, Aircraft> = models ?: synchronized(this) {
            models ?: run {
                val j = JSONObject(ctx.assets.open("aircraft.json").bufferedReader().use { it.readText() })
                j.keys().asSequence().associateWith { k ->
                    val m = j.getJSONObject(k)
                    Aircraft(k, m.getDouble("len").toFloat(), m.getDouble("span").toFloat(), m)
                }.also { models = it }
            }
        }
        fun get(type: String) = models!![type] ?: models!!.getValue("narrow")
        /** a mix of regional jets, narrowbodies and widebodies */
        fun pick(r: Double) = if (r < .3) "regional" else if (r < .86) "narrow" else "wide"
    }
}
