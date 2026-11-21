package io.github.encycloacid.clearwaytower

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A simple controller plays a whole shift at each airport; the game must keep flowing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** one arrival at a time per runway; takeoffs only with the runway clear and nobody close in */
    fun bot(t: Tower) {
        fun onRw(r: Runway) = t.planes.filter { it.end?.r === r && it.state in setOf(St.APPROACH, St.FINAL, St.ROLLOUT, St.LINEUP, St.TAKEOFF) }
        for (p in t.planes.toList()) when (p.state) {
            St.INBOUND, St.HOLDING -> t.landEnds.firstOrNull { onRw(it.r).isEmpty() }?.let { t.clearToLand(p, it) }
            St.VACATED -> t.nearestFreeStand(p)?.let { t.assignGate(p, it) }
            St.READY -> t.pushTo(p, t.departEnds.random())
            St.HOLDSHORT -> if (onRw(p.end!!.r).none { it.state != St.APPROACH } && !t.runwayBusy(p.end!!.r, p)) t.clearTakeoff(p)
            else -> {}
        }
    }

    @Test fun playEachAirport() {
        val out = StringBuilder()
        for (code in Airport.list(ctx)) {
            val ap = Airport.load(ctx, code)
            val t0 = System.nanoTime()
            val tw = Tower(ap, BaseMap(ap), 0, object : Tower.Events {
                override fun toast(msg: String) {}
                override fun buzz(strong: Boolean) {}
                override fun over(score: Int, handled: Int, time: Double, why: String) {}
            })
            val build = (System.nanoTime() - t0) / 1e6
            tw.start()
            var steps = 0
            while (tw.on && tw.t < 900) { tw.step(.05); if (++steps % 20 == 0) bot(tw) }
            val line = "$code: graph ${tw.ground.xs.size} nodes in ${build.toInt()} ms; runways ${tw.ground.ends.joinToString { it.name }}; " +
                "land ${tw.landEnds.joinToString { it.name }}; depart ${tw.departEnds.joinToString { it.name }}; stands ${tw.standList.size}; " +
                "lasted ${tw.t.toInt()} s, score ${tw.score}, moves ${tw.handled}, strikes ${tw.strikes}"
            println(line); out.appendLine(line)
            assertTrue("$code: the game stopped flowing", tw.handled >= 2)
            assertTrue("$code: no runways to land on", tw.landEnds.isNotEmpty() && tw.departEnds.isNotEmpty())
        }
        File("build/playtest.txt").writeText(out.toString())
    }

    @Test fun paintBaseMaps() {
        for (code in Airport.list(ctx)) for (dark in listOf(false, true)) {
            val ap = Airport.load(ctx, code); val base = BaseMap(ap)
            val bmp = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
            val b = ap.bounds; val k = 860f / maxOf(b.width(), b.height())
            val m = Matrix().apply { setScale(k, k); postTranslate(20 - b.left * k, 20 - b.top * k) }
            val t0 = System.nanoTime()
            base.paint(Canvas(bmp), m, k, 0f, Palette(dark))
            println("$code paint ${(System.nanoTime() - t0) / 1_000_000} ms")
            File("build/shots").mkdirs()
            File("build/shots/base_${code}_${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
