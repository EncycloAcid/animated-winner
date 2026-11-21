package io.github.encycloacid.clearwaytower

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Clearway Tower: air traffic control on real airport maps, drawn natively. */
class MainActivity : ComponentActivity() {
    internal lateinit var game: Game

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        game = Game(this)
        game.load(game.code)
        setContent { TowerApp(game) }
    }

    // the shift pauses while the app is in the background, and the map stops drawing
    override fun onPause() {
        super.onPause()
        game.tower?.let { if (it.on) it.pause(true) }
        game.map.pause()
    }

    override fun onResume() {
        super.onResume()
        game.map.resume()
    }

    override fun onDestroy() {
        game.map.release()
        super.onDestroy()
    }
}
