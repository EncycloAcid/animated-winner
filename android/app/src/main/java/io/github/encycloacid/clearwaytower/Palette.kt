package io.github.encycloacid.clearwaytower

import android.graphics.Color

/** The map's colours, light and dark (the same tokens as the web version). */
class Palette(val dark: Boolean) {
    private fun c(light: String, dark: String) = Color.parseColor(if (this.dark) dark else light)
    private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color.argb((a * 255).toInt(), r, g, b)

    val map = c("#eef1ec", "#111513")
    val corridor = c("#e2e8f2", "#3d4656")
    val stairs = c("#95a1b2", "#7a8696")
    val grass = c("#d4e9cb", "#172519")
    val wood = c("#c2ddb7", "#1a2b1d")
    val water = c("#b3d3f3", "#12263b")
    val apron = c("#e5e7eb", "#181b20")
    val taxi = c("#dadde3", "#20242a")
    val taxiLine = c("#e6c14a", "#8a7323")
    val runway = c("#c6cad2", "#2a2f37")
    val runwayMark = c("#ffffff", "#575e69")
    val roadCase = c("#d3d8de", "#22272e")
    val roadFill = c("#ffffff", "#2d333b")
    val mwayCase = c("#e8c574", "#4d4024")
    val mwayFill = c("#fdeab4", "#5e5030")
    val rail = c("#aeb6c0", "#3f4650")
    val lot = c("#e8eaee", "#1b1f24")
    val otherBldg = c("#dfe2e7", "#22262d")
    val termClosed = c("#ebecef", "#1e2228")
    val bldg = c("#ffffff", "#2f3540")
    val bldgEdge = c("#bfc8d4", "#4a5262")
    val bldgShadow = if (dark) rgba(0, 0, 0, .55f) else rgba(30, 45, 80, .2f)
    val plane = c("#c9d0d9", "#2c333d")
    val bridge = c("#c3ccd6", "#434c59")
    val garage = c("#dde1e7", "#1c2026")
    val conTint = if (dark) .2f else .13f
    val hall = c("#dcf1eb", "#1d3833")
    val hallEdge = c("#86c6b4", "#3b7768")
    val hallText = c("#2f8a74", "#79c2ae")
    val curb = c("#eceef2", "#2a2f37")
    val zebra = c("#ffffff", "#8e97a5")
    val facade = if (dark) rgba(34, 34, 38, .95f) else rgba(250, 250, 252, .96f)
    val counter = c("#5d6b85", "#9aa7c0")
    val door = c("#2f50d0", "#7d97ff")
    val route = c("#2f50d0", "#5b7cff")

    // overlay: labels, gates, aircraft
    val label = c("#101318", "#f3f4f8")
    val label2 = if (dark) rgba(235, 235, 245, .62f) else rgba(60, 60, 67, .64f)
    val solid = c("#fbfbfd", "#242428")
    val hairline = if (dark) rgba(84, 84, 88, .55f) else rgba(60, 60, 67, .16f)
    val drop = rgba(20, 30, 50, .22f)
    val acAir = c("#2f50d0", "#7f99ff")
    val acGround = c("#5d6b85", "#aab4c4")
    val acDep = c("#d9480f", "#ff8a4c")
    val acNeed = c("#2f50d0", "#3c5ee6")
    val acShadow = if (dark) rgba(0, 0, 0, .4f) else rgba(20, 30, 50, .18f)
    val no = c("#d33a32", "#ff6b63")
    val runwaySign = Color.parseColor("#2b3340")

    fun con(c: Concourse?) = if (c == null) label2 else if (dark) c.dark else c.light

    companion object {
        /** a over b, t = amount of a */
        fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * t + Color.red(b) * (1 - t)).toInt(),
            (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(),
            (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt(),
        )
    }
}
