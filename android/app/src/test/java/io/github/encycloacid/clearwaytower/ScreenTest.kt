package io.github.encycloacid.clearwaytower

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The real activity: screenshots of the start screen and of a shift under way. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        File("build/shots").mkdirs()
        // draw the whole window (the map view and the Compose controls) into a bitmap
        lateinit var bmp: Bitmap
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
        }
        File("build/shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun settle(ms: Long = 1500) {
        // the base map renders on its own thread and posts back
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { Thread.sleep(50); rule.mainClock.advanceTimeBy(50); rule.waitForIdle() }
    }

    private fun play(name: String) {
        val g = rule.activity.game
        rule.waitUntil(20_000) { g.screen == Screen.Intro }
        settle()
        shot("${name}_intro")
        rule.onNodeWithText("Start shift").performClick()
        val t = g.tower!!
        val bot = PlayTest()
        // play 70 s with the bot, then pause (a running game redraws every frame, which never lets the test go idle)
        rule.runOnUiThread { g.setPaused(true); t.paused = false; repeat(1400) { t.step(.05); if (it % 20 == 0) bot.bot(t) }; g.setPaused(true) }
        settle()
        shot("${name}_play")
        println("$name waiting: ${t.waiting.map { it.cs + " " + it.state }}; quiet text nodes: " + rule.onAllNodesWithText("All quiet", substring = true).fetchSemanticsNodes().size)
        rule.runOnUiThread {
            val p = t.planes.firstOrNull { !it.air && it.needsYou } ?: t.planes.firstOrNull { !it.air } ?: t.planes.first()
            g.select(p); g.map.centerOn(p.x, p.y)
        }
        settle(2500)
        shot("${name}_selected")
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") fun phone() = play("phone")
    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi") fun phoneDark() = play("phone_dark")
    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi") fun tablet() = play("tablet")
}
