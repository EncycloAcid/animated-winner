package io.github.encycloacid.clearwaytower

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

// ---------- Colours for the controls (the same tokens as the web version) ----------
class Tok(val dark: Boolean) {
    private fun c(l: Long, d: Long) = Color(if (dark) d else l)
    val map = c(0xFFEEF1EC, 0xFF111513)
    val glass = c(0xF5FAFAFC, 0xF2222226)
    val solid = c(0xFFFBFBFD, 0xFF242428)
    val fill = c(0x1F767680, 0x3D767680)
    val fill2 = c(0x33767680, 0x57767680)
    val label = c(0xFF101318, 0xFFF3F4F8)
    val label2 = c(0xA33C3C43, 0x9EEBEBF5)
    val label3 = c(0x573C3C43, 0x4DEBEBF5)
    val hairline = c(0x293C3C43, 0x8C545458)
    val brand = c(0xFF2F50D0, 0xFF3C5EE6)
    val primary = c(0xFF2F50D0, 0xFFA9B8FF)
    val route = c(0xFF2F50D0, 0xFF5B7CFF)
    val ok = c(0xFF1D8A4A, 0xFF4FD07A)
    val warn = c(0xFFB86E00, 0xFFC98A1A)
    val no = c(0xFFD33A32, 0xFFFF6B63)
    val acAir = c(0xFF2F50D0, 0xFF7F99FF)
    val acGround = c(0xFF5D6B85, 0xFFAAB4C4)
    val acDep = c(0xFFD9480F, 0xFFFF8A4C)
    val scrim = c(0x8CEEF1EC, 0x8C111513)
}

sealed class Screen { data object Loading : Screen(); data object Intro : Screen(); data object Playing : Screen(); data class Over(val score: Int, val handled: Int, val time: Double, val why: String, val best: Int, val newBest: Boolean) : Screen() }

/** Everything the screen shows: the map view, the game, and what's on top of it. */
class Game(private val ctx: Context) : MapView.Listener, Tower.Events {
    val map = MapView(ctx).also { it.listener = this }
    private val prefs = ctx.getSharedPreferences("tower", Context.MODE_PRIVATE)
    val airports = Airport.names(ctx)
    var code by mutableStateOf(prefs.getString("airport", null)?.takeIf { c -> airports.any { it.first == c } } ?: airports.first().first)
    var airport by mutableStateOf<Airport?>(null)
    var tower by mutableStateOf<Tower?>(null)
    var screen by mutableStateOf<Screen>(Screen.Loading)
    var tick by mutableIntStateOf(0)
    var toast by mutableStateOf<String?>(null); var toastId by mutableIntStateOf(0)
    var turn by mutableFloatStateOf(0f)
    private var uiAcc = 0.0; private var lastVersion = -1

    fun best(c: String) = prefs.getInt("best.$c", 0)

    fun load(c: String) {
        code = c; prefs.edit().putString("airport", c).apply()
        screen = Screen.Loading; tower = null
        Thread {
            val ap = Airport.load(ctx, c); val base = BaseMap(ap); val t = Tower(ap, base, best(c), this)
            map.post { airport = ap; tower = t; map.setAirport(ap, base, t); screen = Screen.Intro; tick++; map.kick() }
        }.start()
    }

    fun start() { tower?.start(); screen = Screen.Playing; tick++; map.kick() }
    fun select(p: Plane?) { tower?.selected = p; buzz(); tick++ }
    fun setPaused(p: Boolean) { tower?.pause(p); tick++; map.kick() }

    // MapView.Listener
    override fun onTapPlane(p: Plane) { val t = tower ?: return; if (!t.on) return; select(if (t.selected === p) null else p) }
    override fun onTapGate(code: String): Boolean {
        val t = tower ?: return false; val p = t.selected ?: return false
        if (!t.on || !t.picking) return false
        val s = t.stands[code]
        if (s != null && s.plane == null) t.assignGate(p, s) else toast("Gate $code is taken")
        tick++; return true
    }
    override fun isLive() = tower?.let { it.on && !it.paused } ?: false
    override fun onTapEmpty() { if (tower?.selected != null) select(null) }
    override fun onTurn(deg: Float) { if (abs(deg - turn) > .2f || (deg == 0f && turn != 0f)) turn = deg }
    override fun onFrame(dt: Double) {
        val t = tower ?: return
        if (t.on && !t.paused) { var left = dt * t.speed; while (left > 0 && t.on) { val s = min(.05, left); t.step(s); left -= s } }
        uiAcc += dt
        if (uiAcc > .25 || t.version != lastVersion) { uiAcc = 0.0; lastVersion = t.version; tick++ }
    }

    // Tower.Events
    override fun toast(msg: String) { toast = msg; toastId++ }
    override fun buzz(strong: Boolean) { map.performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK) }
    override fun over(score: Int, handled: Int, time: Double, why: String) {
        val b = best(code); val nb = score > b
        if (nb) prefs.edit().putInt("best.$code", score).apply()
        screen = Screen.Over(score, handled, time, why, b, nb); tick++
    }

}

// ---------- The screen ----------
@Composable
fun TowerApp(g: Game) {
    val dark = isSystemInDarkTheme()
    val tk = remember(dark) { Tok(dark) }
    LaunchedEffect(dark) { g.map.setDark(dark) }
    LaunchedEffect(g.tick) { g.map.invalidate() }   // a command or selection while the game is paused still shows on the map
    @Suppress("UNUSED_VARIABLE") val tick = g.tick
    val t = g.tower
    var menu by remember { mutableStateOf(false) }
    var conMenu by remember { mutableStateOf(false) }
    val dens = LocalDensity.current

    BackHandler(enabled = menu || conMenu || t?.selected != null || (t?.on == true && !t.paused)) {
        when {
            menu -> menu = false
            conMenu -> conMenu = false
            t?.selected != null -> g.select(null)
            else -> { g.setPaused(true); g.toast("Paused") }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(tk.map)) {
        val wide = maxWidth >= 760.dp
        val roomy = maxWidth >= 400.dp
        var topBottom by remember { mutableFloatStateOf(0f) }
        var dockTop by remember { mutableFloatStateOf(0f) }
        var dockRight by remember { mutableFloatStateOf(0f) }
        val totalH = with(dens) { maxHeight.toPx() }
        LaunchedEffect(topBottom, dockTop, dockRight, wide) {
            g.map.setObscured(topBottom, if (wide || dockTop == 0f) 0f else totalH - dockTop, if (wide) dockRight else 0f)
        }
        AndroidView(factory = { g.map }, modifier = Modifier.fillMaxSize())

        // airport and score, top left
        Column(
            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                .padding(start = 16.dp, top = 12.dp, end = 76.dp)
                .onGloballyPositioned { topBottom = it.boundsInRoot().bottom },
        ) {
            Box {
                Row(
                    Modifier.glass(tk, 22.dp).clickable { g.buzz(); menu = true }.height(44.dp).padding(start = 6.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(g.code, Modifier.clip(RoundedCornerShape(16.dp)).background(tk.brand).padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    Text(g.airport?.name ?: "Loading…", color = tk.label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Glyph("chev", tk.label2, 14.dp)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = tk.solid, shape = RoundedCornerShape(20.dp)) {
                    for ((c, name, city) in g.airports) DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(c, color = tk.primary, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.width(44.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = tk.label, fontSize = 15.sp)
                                    Text(city + (g.best(c).takeIf { it > 0 }?.let { " · best $it" } ?: ""), color = tk.label2, fontSize = 13.sp)
                                }
                                if (c == g.code) Glyph("check", tk.primary, 20.dp)
                            }
                        },
                        onClick = { menu = false; if (c != g.code) g.load(c) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (t != null) Row(
                Modifier.glass(tk, 20.dp).height(40.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Stat(tk, "Score", t.score.toString()); if (roomy) Stat(tk, "Moves", t.handled.toString())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i -> Box(Modifier.size(10.dp).clip(CircleShape).background(if (i < t.strikes) tk.no else tk.fill2)) }
                }
                Text(mmss(t.t), color = tk.label, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                SmallButton(tk, { g.buzz(); t.speed = if (t.speed == 1) 2 else 1; g.tick++ }) { Text("${t.speed}×", color = tk.label, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                SmallButton(tk, { if (t.on) { g.buzz(); g.setPaused(!t.paused) } }) { Glyph(if (t.paused) "play" else "pause", tk.label, 16.dp) }
            }
        }

        // map controls, top right
        Row(
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(top = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(abs(g.turn) > .25f, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Box(Modifier.size(44.dp).glass(tk, 22.dp).clickable { g.buzz(); g.map.northUp() }, contentAlignment = Alignment.Center) {
                    Compass(tk, g.turn)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Capsule(tk) {
                    CapButton(tk, { g.map.zoomBy(1.6f) }) { Glyph("plus", tk.label, 20.dp) }
                    CapButton(tk, { g.map.zoomBy(1 / 1.6f) }) { Glyph("minus", tk.label, 20.dp) }
                    CapButton(tk, { g.map.fitAll() }) { Glyph("fit", tk.label, 20.dp) }
                }
                val ap = g.airport
                if (ap != null) Box {
                    Capsule(tk) {
                        if (ap.concourses.size <= 4) for (c in ap.concourses) CapButton(tk, { g.buzz(); g.map.fitCon(c.id) }) { Badge(c, dark, 28.dp) }
                        else CapButton(tk, { g.buzz(); conMenu = true }) { Glyph("plane", tk.label, 22.dp) }
                    }
                    DropdownMenu(expanded = conMenu, onDismissRequest = { conMenu = false }, containerColor = tk.solid, shape = RoundedCornerShape(20.dp)) {
                        for (c in ap.concourses) {
                            val gs = ap.gates.filter { it.con == c.id }.sortedBy { it.code.filter(Char::isDigit).toIntOrNull() ?: 0 }
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Badge(c, dark, 26.dp)
                                        Text("Concourse ${c.id}", color = tk.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        if (gs.isNotEmpty()) Text("${gs.first().code}–${gs.last().code}", color = tk.label2, fontSize = 13.sp)
                                    }
                                },
                                onClick = { conMenu = false; g.map.fitCon(c.id) },
                            )
                        }
                    }
                }
            }
        }

        // the flight panel: bottom on phones, left column on wide screens
        if (t != null) {
            val dockMod = if (wide) Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 12.dp, top = 120.dp, bottom = 12.dp).width(380.dp).fillMaxHeight()
            else Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(12.dp).fillMaxWidth().heightIn(max = 420.dp)
            Column(
                dockMod.onGloballyPositioned { val b = it.boundsInRoot(); dockTop = b.top; dockRight = b.right }.glass(tk, 24.dp).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                t.selected?.let { CommandCard(g, t, it, tk, g.tick) }
                Strips(g, t, tk, if (wide) Modifier.weight(1f, fill = false) else Modifier.heightIn(max = 128.dp), g.tick)
            }
        }

        // toasts, under the score
        LaunchedEffect(g.toastId) { if (g.toast != null) { delay(2200); g.toast = null } }
        AnimatedVisibility(g.toast != null, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = 112.dp, start = 16.dp, end = 16.dp),
            enter = fadeIn() + scaleIn(initialScale = .9f), exit = fadeOut()) {
            Text(g.toast ?: "", Modifier.glass(tk, 18.dp).padding(horizontal = 16.dp, vertical = 10.dp), color = tk.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
        }

        // start, game over, loading
        val sc = g.screen
        if (sc != Screen.Playing) Box(Modifier.fillMaxSize().background(tk.scrim).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(tk.solid).padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ap = g.airport
                when (sc) {
                    is Screen.Loading -> { Text("Clearway Tower", color = tk.label2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text("Opening the tower…", color = tk.label, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
                    is Screen.Intro -> {
                        Text("Clearway Tower · ${g.code}", color = tk.label2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("You're in the tower", color = tk.label, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 32.sp)
                        Text("Run the ground and runways at ${ap?.name}.", color = tk.label, fontSize = 15.sp)
                        Step(tk, 1, "Arrivals", " hold until you pick a runway for them. Watch their fuel.")
                        Step(tk, 2, "Tap a free gate number", " to send a plane there once it's off the runway.")
                        Step(tk, 3, "Push it back", " to a runway after boarding, then clear it for takeoff, but only when the runway is empty.")
                        Text("A diversion or a runway incursion is a strike. Three strikes ends your shift." + (g.best(g.code).takeIf { it > 0 }?.let { " Best here: $it." } ?: ""), color = tk.label2, fontSize = 14.sp)
                        BigButton(tk, "Start shift") { g.start() }
                    }
                    is Screen.Over -> {
                        Text("Shift over · ${mmss(sc.time)}", color = tk.label2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${sc.score} points", color = tk.label, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        Text("${sc.why}.", color = tk.label, fontSize = 15.sp)
                        Text("You handled ${sc.handled} movements." + if (sc.newBest) " That's a new best here." else if (sc.best > 0) " Best here: ${sc.best}." else "", color = tk.label, fontSize = 15.sp)
                        BigButton(tk, "Start a new shift") { g.start() }
                    }
                    else -> {}
                }
            }
        }
    }
}

// ---------- The selected flight and its commands ----------
private class Act(val label: String, val note: String?, val warn: Boolean, val run: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@NonSkippableComposable
@Composable
private fun CommandCard(g: Game, t: Tower, p: Plane, tk: Tok, @Suppress("UNUSED_PARAMETER") tick: Int) {   // tick: redraw as the game moves on
    val acts = ArrayList<Act>()
    fun did() { g.tick++ }
    when (p.state) {
        St.INBOUND, St.HOLDING, St.MISSED -> for (e in t.landEnds) {
            val busy = t.runwayBusy(e.r)
            acts.add(Act("Land ${e.name}", if (busy) "runway in use" else null, busy) { t.clearToLand(p, e); did() })
        }
        St.APPROACH, St.FINAL -> acts.add(Act("Go around", null, false) { t.goAround(p, null); did() })
        St.VACATED, St.TAXIIN -> acts.add(Act("Nearest free gate", null, false) { t.nearestFreeStand(p)?.let { t.assignGate(p, it) } ?: g.toast("No free gates"); did() })
        St.READY -> for (e in t.departEnds) acts.add(Act("Push · runway ${e.name}", null, false) { t.pushTo(p, e); did() })
        St.HOLDSHORT -> {
            val e = p.end!!; val busy = t.runwayBusy(e.r, p); val traffic = !busy && t.finalOn(e.r, 1300.0, p) != null
            acts.add(Act("Cleared for takeoff ${e.name}", if (busy) "runway in use" else if (traffic) "arrival close in" else null, busy || traffic) { t.clearTakeoff(p); did() })
        }
        else -> {}
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tk.solid).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(p, tk)
            Column(Modifier.weight(1f)) {
                Text(p.cs, color = tk.label, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(t.status(p).second, color = tk.label2, fontSize = 13.sp)
            }
            SmallButton(tk, { g.select(null) }) { Glyph("x", tk.label2, 14.dp) }
        }
        if (!p.dep && !p.landed) {
            val frac = (p.fuel / 175).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(tk.fill)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (p.fuel < 30) tk.no else tk.ok))
            }
        }
        if (acts.isNotEmpty()) FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in acts) {
                val src = remember { MutableInteractionSource() }
                val pressed by src.collectIsPressedAsState()
                val sc by animateFloatAsState(if (pressed) .94f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "press")
                Column(
                    Modifier.scale(sc).clip(RoundedCornerShape(if (pressed) 12.dp else 20.dp)).background(if (a.warn) tk.warn else tk.brand)
                        .clickable(src, null) { a.run() }.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(a.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    a.note?.let { Text(it, color = Color.White.copy(alpha = .9f), fontSize = 11.sp) }
                }
            }
        }
        t.hint(p)?.let { Text(it, Modifier.padding(top = 10.dp, start = 2.dp), color = tk.label2, fontSize = 13.sp) }
    }
}

@NonSkippableComposable
@Composable
private fun Strips(g: Game, t: Tower, tk: Tok, mod: Modifier, @Suppress("UNUSED_PARAMETER") tick: Int) {
    val list = t.waiting
    Column(mod.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (list.isEmpty()) Text(if (t.on) "All quiet. Nothing is waiting on you." else "", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = tk.label2, fontSize = 13.sp)
        for (p in list) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (t.selected === p) tk.fill2 else Color.Transparent)
                .clickable { g.select(p); g.map.centerOn(p.x, p.y) }.heightIn(min = 40.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Chip(p, tk)
            Text(p.cs, Modifier.weight(1f), color = tk.label, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.status(p).first, color = tk.label2, fontSize = 13.sp)
            Text(if (!p.landed) mmss(p.fuel) else mmss(p.wait), Modifier.widthIn(min = 36.dp), color = if (p.urgent) tk.no else tk.label2,
                fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.End)
        }
    }
}

// ---------- Small pieces ----------
private fun Modifier.glass(tk: Tok, r: Dp) = this.clip(RoundedCornerShape(r)).background(tk.glass).border(.5.dp, tk.hairline, RoundedCornerShape(r))

@Composable private fun Stat(tk: Tok, k: String, v: String) = Text(buildAnnotatedString {
    withStyle(SpanStyle(color = tk.label2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)) { append("$k ") }
    withStyle(SpanStyle(color = tk.label, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)) { append(v) }
})

@Composable private fun Chip(p: Plane, tk: Tok) =
    Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(if (p.air) tk.acAir else if (p.dep) tk.acDep else tk.acGround))

@Composable private fun SmallButton(tk: Tok, onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.size(32.dp).clip(CircleShape).background(tk.fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }

@Composable private fun Capsule(tk: Tok, content: @Composable () -> Unit) = Column(Modifier.glass(tk, 22.dp)) { content() }

@Composable private fun CapButton(tk: Tok, onClick: () -> Unit, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) .86f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "cap")
    Box(Modifier.size(44.dp).clickable(src, null, onClick = onClick).scale(sc), contentAlignment = Alignment.Center) { content() }
}

@Composable private fun BigButton(tk: Tok, label: String, onClick: () -> Unit) =
    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(tk.brand).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }

@Composable private fun Step(tk: Tok, n: Int, bold: String, rest: String) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("$n.", color = tk.label, fontSize = 15.sp)
    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }; append(rest) }, color = tk.label, fontSize = 15.sp)
}

@Composable fun Badge(c: Concourse, dark: Boolean, size: Dp) {
    val path = remember(c) { c.shape.asComposePath() }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension / 22f * 1.05f
            translate(this.size.width / 2, this.size.height / 2) { scale(s, s, Offset.Zero) { drawPath(path, Color(if (dark) c.dark else c.light)) } }
        }
        Text(c.id, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * .42f).sp)
    }
}

@Composable private fun Compass(tk: Tok, deg: Float) = Canvas(Modifier.size(40.dp).rotate(deg)) {
    val c = Offset(size.width / 2, size.height / 2); val u = size.minDimension / 44f
    for (i in 1 until 12) {
        val a = Math.toRadians(i * 30.0)
        drawLine(tk.label3, Offset(c.x + (17 * u * Math.sin(a)).toFloat(), c.y - (17 * u * Math.cos(a)).toFloat()),
            Offset(c.x + (13.8f * u * Math.sin(a)).toFloat(), c.y - (13.8f * u * Math.cos(a)).toFloat()), 1.3f * u, StrokeCap.Round)
    }
    val red = Color(0xFFE5484D)
    drawPath(Path().apply { moveTo(c.x, c.y - 20.6f * u); lineTo(c.x + 3.4f * u, c.y - 15.2f * u); lineTo(c.x - 3.4f * u, c.y - 15.2f * u); close() }, red)
    drawPath(Path().apply { moveTo(c.x, c.y + 20.6f * u); lineTo(c.x + 3.4f * u, c.y + 15.2f * u); lineTo(c.x - 3.4f * u, c.y + 15.2f * u); close() }, tk.label3)
    drawPath(Path().apply { moveTo(c.x - 4 * u, c.y + 5 * u); lineTo(c.x - 4 * u, c.y - 5 * u); lineTo(c.x + 4 * u, c.y + 5 * u); lineTo(c.x + 4 * u, c.y - 5 * u) },
        red, style = Stroke(2.2f * u, cap = StrokeCap.Round))
}

/** small line icons, drawn on a 24-unit grid like the web version's SVG symbols */
@Composable fun Glyph(name: String, color: Color, size: Dp) {
    val plane = remember { Airport.PLANE.asComposePath() }
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val st = Stroke(2f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun p(vararg pts: Float, close: Boolean = false) = Path().apply { for (i in pts.indices step 2) if (i == 0) moveTo(pts[0] * u, pts[1] * u) else lineTo(pts[i] * u, pts[i + 1] * u); if (close) close() }
        when (name) {
            "plus" -> { drawPath(p(12f, 5f, 12f, 19f), color, style = st); drawPath(p(5f, 12f, 19f, 12f), color, style = st) }
            "minus" -> drawPath(p(5f, 12f, 19f, 12f), color, style = st)
            "fit" -> for (q in listOf(p(4f, 9f, 4f, 4f, 9f, 4f), p(20f, 9f, 20f, 4f, 15f, 4f), p(4f, 15f, 4f, 20f, 9f, 20f), p(20f, 15f, 20f, 20f, 15f, 20f))) drawPath(q, color, style = st)
            "play" -> drawPath(p(7f, 5f, 19f, 12f, 7f, 19f, close = true), color)
            "pause" -> { drawPath(p(8f, 5f, 8f, 19f), color, style = st); drawPath(p(16f, 5f, 16f, 19f), color, style = st) }
            "x" -> { drawPath(p(6f, 6f, 18f, 18f), color, style = st); drawPath(p(18f, 6f, 6f, 18f), color, style = st) }
            "check" -> drawPath(p(5f, 12.5f, 9.5f, 17f, 19f, 7.5f), color, style = st)
            "chev" -> drawPath(p(6f, 9f, 12f, 15f, 18f, 9f), color, style = Stroke(2.6f * u, cap = StrokeCap.Round))
            "plane" -> translate(12 * u, 12 * u) { scale(1.3f * u, 1.3f * u, Offset.Zero) { drawPath(plane, color) } }
        }
    }
}
