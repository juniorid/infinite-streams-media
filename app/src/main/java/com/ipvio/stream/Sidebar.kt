package com.ipvio.stream

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MenuIcon(id: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(26.dp)) {
        val s = size.minDimension
        // Strichstärke im Verhältnis zur Größe (wie die Umriss-Symbole im Entwurf)
        val sw = Stroke(width = maxOf(1.4.dp.toPx(), s * 0.085f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = sw.width
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), strokeWidth = w, cap = StrokeCap.Round)
        fun rect(x: Float, y: Float, rw: Float, rh: Float, r: Float) =
            drawRoundRect(color, topLeft = Offset(x * s, y * s), size = Size(rw * s, rh * s), cornerRadius = CornerRadius(r * s), style = sw)
        // Symbole wie im Entwurf (Umriss-Stil)
        when (id) {
            0 -> { // Home: Haus
                val p = Path().apply {
                    moveTo(.12f * s, .5f * s); lineTo(.5f * s, .15f * s); lineTo(.88f * s, .5f * s)
                    moveTo(.22f * s, .44f * s); lineTo(.22f * s, .86f * s); lineTo(.78f * s, .86f * s); lineTo(.78f * s, .44f * s)
                }
                drawPath(p, color, style = sw)
            }
            1 -> { // Filme: Filmstreifen
                rect(.14f, .14f, .72f, .72f, .1f)
                line(.32f, .14f, .32f, .86f); line(.68f, .14f, .68f, .86f)
                line(.14f, .38f, .32f, .38f); line(.14f, .62f, .32f, .62f)
                line(.68f, .38f, .86f, .38f); line(.68f, .62f, .86f, .62f)
            }
            2 -> { // Serien: Filmklappe
                rect(.12f, .38f, .76f, .48f, .08f)
                val p = Path().apply {
                    moveTo(.12f * s, .38f * s); lineTo(.16f * s, .2f * s); lineTo(.86f * s, .12f * s); lineTo(.88f * s, .3f * s); close()
                }
                drawPath(p, color, style = sw)
                line(.36f, .17f, .42f, .34f); line(.6f, .14f, .66f, .32f)
            }
            3 -> { // Live TV: Fernseher mit Antenne
                rect(.1f, .3f, .8f, .56f, .1f)
                line(.34f, .12f, .5f, .3f); line(.66f, .12f, .5f, .3f)
            }
            4 -> { // Suche: Lupe
                drawCircle(color, radius = .26f * s, center = Offset(.44f * s, .44f * s), style = sw)
                line(.63f, .63f, .86f, .86f)
            }
            5 -> { // Einstellungen: Zahnrad
                val c = Offset(.5f * s, .5f * s)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val cx = kotlin.math.cos(a).toFloat(); val cy = kotlin.math.sin(a).toFloat()
                    drawLine(color, Offset(c.x + cx * .3f * s, c.y + cy * .3f * s), Offset(c.x + cx * .4f * s, c.y + cy * .4f * s), strokeWidth = w * 1.5f, cap = StrokeCap.Round)
                }
                drawCircle(color, radius = .27f * s, center = c, style = sw)
                drawCircle(color, radius = .1f * s, center = c, style = sw)
            }
            6 -> { // EPG: Liste mit Punkten
                for (y in listOf(.26f, .5f, .74f)) {
                    drawCircle(color, radius = .055f * s, center = Offset(.18f * s, y * s))
                    line(.34f, y, .88f, y)
                }
            }
            7 -> { // Favoriten: Herz
                val p = Path().apply {
                    moveTo(.5f * s, .84f * s)
                    cubicTo(.08f * s, .56f * s, .06f * s, .2f * s, .3f * s, .18f * s)
                    cubicTo(.42f * s, .17f * s, .48f * s, .26f * s, .5f * s, .32f * s)
                    cubicTo(.52f * s, .26f * s, .58f * s, .17f * s, .7f * s, .18f * s)
                    cubicTo(.94f * s, .2f * s, .92f * s, .56f * s, .5f * s, .84f * s)
                    close()
                }
                drawPath(p, color, style = sw)
            }
            8 -> { // Meine Liste: Lesezeichen
                val p = Path().apply {
                    moveTo(.26f * s, .12f * s); lineTo(.74f * s, .12f * s); lineTo(.74f * s, .88f * s)
                    lineTo(.5f * s, .68f * s); lineTo(.26f * s, .88f * s); close()
                }
                drawPath(p, color, style = sw)
            }
            9 -> { // Verlauf: Uhr mit Zeigern
                drawCircle(color, radius = .38f * s, center = Offset(.5f * s, .5f * s), style = sw)
                line(.5f, .5f, .5f, .28f)
                line(.5f, .5f, .66f, .6f)
            }
            10 -> { // Sport: Ball mit Nähten
                val c = Offset(.5f * s, .5f * s)
                drawCircle(color, radius = .36f * s, center = c, style = sw)
                drawArc(color, 200f, 140f, false, topLeft = Offset(.14f * s, .02f * s), size = Size(.72f * s, .5f * s), style = sw)
                drawArc(color, 20f, 140f, false, topLeft = Offset(.14f * s, .48f * s), size = Size(.72f * s, .5f * s), style = sw)
            }
            else -> {
                for (y in listOf(.26f, .5f, .74f)) line(.12f, y, .88f, y)
            }
        }
    }
}

// Seitenleiste (Runde 14): eingeklappt nur Symbole (immer sichtbar), mit Fokus klappt sie über den Inhalt auf und zeigt die Texte
private val SidebarBg: Color get() = lerp(Look.bg, Color.Black, 0.25f)
private val SidebarInk = Color(0xFFCFCFD6)

/** Lage und Stil der Seitenleiste (Runde 20): links, rechts oder oben; nur Symbole oder immer mit Text. */
object SidebarPrefs {
    const val LEFT = 0
    const val RIGHT = 1
    const val TOP = 2
    val POS_NAMES = listOf("Links", "Rechts", "Oben")
    var pos by mutableIntStateOf(LEFT)
        private set
    /** Immer mit Text (sonst nur Symbole, Texte beim Hineingehen). */
    var labels by mutableStateOf(false)
        private set

    fun init(c: android.content.Context) {
        val p = Profiles.sp(c, "ipvio_side")
        pos = p.getInt("pos", LEFT).coerceIn(LEFT, TOP)
        labels = p.getBoolean("labels", false)
    }

    fun set(c: android.content.Context, pos: Int = this.pos, labels: Boolean = this.labels) {
        this.pos = pos.coerceIn(LEFT, TOP); this.labels = labels
        Profiles.sp(c, "ipvio_side").edit().putInt("pos", this.pos).putBoolean("labels", labels).apply()
    }

    fun align(): Alignment = when (pos) { RIGHT -> Alignment.CenterEnd; TOP -> Alignment.TopCenter; else -> Alignment.CenterStart }
}

/** Höhe der Leiste oben. */
val TopBarH: androidx.compose.ui.unit.Dp get() = dpx(68)

/** Platz für die Seitenleiste am Inhalt (links, rechts oder oben). */
fun Modifier.sidebarPad(): Modifier = when (SidebarPrefs.pos) {
    SidebarPrefs.RIGHT -> this.padding(end = DS.SidebarW)
    SidebarPrefs.TOP -> this.padding(top = TopBarH)
    else -> this.padding(start = DS.SidebarW)
}

/** Abstand links bis zum Inhalt (nur bei der Leiste links). */
val SideStart: androidx.compose.ui.unit.Dp get() = if (SidebarPrefs.pos == SidebarPrefs.LEFT) DS.SidebarW else 0.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Sidebar(tab: Int, onTab: (Int) -> Unit, modifier: Modifier, activeFocus: FocusRequester, onFocusIn: (Boolean) -> Unit) {
    var focusIn by remember { mutableStateOf(false) }   // = Seitenleiste hat den Fokus
    val pos = SidebarPrefs.pos
    val expanded = focusIn || SidebarPrefs.labels
    val fm = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val visible = AppSettings.menuOrder.filter { it !in AppSettings.menuHidden }
    val activeVisible = tab in visible
    // Zurück in den Inhalt: die Richtung weg von der Leiste
    val outKey = when (pos) { SidebarPrefs.RIGHT -> Key.DirectionLeft; SidebarPrefs.TOP -> Key.DirectionDown; else -> Key.DirectionRight }
    val onPick: (Int) -> Unit = { id ->
        AppNav.focusTab = id
        HeaderFocus.returnTo = null
        onTab(id)
        scope.launch {
            delay(200)
            // Solange die Seitenleiste den Fokus noch hat, weiter versuchen: erst das Ziel des Inhalts,
            // dann "in den Inhalt". Ein einzelner Versuch scheitert, wenn die Liste noch lädt.
            var n = 0
            while (focusIn && n < 14) {
                val ok = ContentFocus.tab == id && runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)
                if (ok) AppNav.focusTab = -1
                if (!ok && n >= 4) runCatching {
                    fm.moveFocus(when (pos) { SidebarPrefs.RIGHT -> FocusDirection.Left; SidebarPrefs.TOP -> FocusDirection.Down; else -> FocusDirection.Right })
                }
                delay(100)
                n++
            }
        }
    }
    val group = Modifier
        .focusProperties { enterTo { if (activeVisible) activeFocus else FocusRequester.Default } }
        // Zurück zum zuletzt markierten Element im Inhalt (nicht zum Element neben dem Menüpunkt)
        .onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == outKey) {
                ContentMemory.restore() || runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)
            } else false
        }
        .focusGroup()
        .onFocusChanged { focusIn = it.hasFocus; onFocusIn(it.hasFocus) }

    if (pos == SidebarPrefs.TOP) {
        // Leiste oben: Symbole mit Text nebeneinander
        Row(
            modifier.fillMaxWidth().height(TopBarH).background(SidebarBg)
                .drawBehind { drawLine(Color(0x1AFFFFFF), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f) }
                .then(group)
                // Viele Menüpunkte: Leiste lässt sich seitlich verschieben (der markierte Punkt rückt ins Bild)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = dpx(28)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dpx(8))
        ) {
            Box(Modifier.size(dpx(40)), contentAlignment = Alignment.Center) { BrandMark(Modifier.fillMaxSize()) }
            Spacer(Modifier.width(dpx(18)))
            visible.forEach { id ->
                SideItem(id, MENU_LABELS[id], id == tab, true, if (id == tab) Modifier.focusRequester(activeFocus) else Modifier, horizontal = true) { onPick(id) }
            }
        }
        return
    }

    val right = pos == SidebarPrefs.RIGHT
    val w by animateDpAsState(if (expanded) DS.SidebarOpenW else DS.SidebarCollapsedW, if (Look.motion == 0) snap() else tween(220), label = "sideW")
    Box(modifier.fillMaxHeight()) {
        // Aufgeklappt (nur bei „nur Symbole“): Inhalt dahinter abdunkeln, damit die Texte gut lesbar sind
        if (focusIn && !SidebarPrefs.labels) Box(
            Modifier.width(w + dpx(260)).fillMaxHeight().align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
                .background(
                    if (right) Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color(0xB3000000), 1f to Color(0xF2000000))
                    else Brush.horizontalGradient(0f to Color(0xF2000000), 0.55f to Color(0xB3000000), 1f to Color.Transparent)
                )
        )
        Column(
            Modifier.width(w).fillMaxHeight().align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
                .background(SidebarBg)
                .drawBehind {
                    val x = if (right) 0f else size.width - 1f
                    drawLine(Color(0x1AFFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
                }
                .then(group)
                .padding(start = dpx(16), end = dpx(16), top = dpx(22))
        ) {
            Box(Modifier.padding(start = dpx(6)).size(dpx(48)), contentAlignment = Alignment.Center) { BrandMark(Modifier.fillMaxSize()) }
            Spacer(Modifier.height(dpx(34)))
            Column(verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                visible.forEach { id ->
                    SideItem(id, MENU_LABELS[id], id == tab, expanded, if (id == tab) Modifier.focusRequester(activeFocus) else Modifier) { onPick(id) }
                }
            }
        }
    }
}

@Composable
private fun SideItem(id: Int, label: String, active: Boolean, expanded: Boolean, modifier: Modifier, horizontal: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(12))
    // Aktiv: Symbol in der Designfarbe auf getönter Fläche. Fokus: weiße Fläche, dunkle Schrift (eindeutig „markiert“).
    val ink = if (f) DS.FocusInk else if (active) Accent1 else SidebarInk
    Row(
        (if (horizontal) modifier.height(dpx(48)) else modifier.fillMaxWidth().height(dpx(56))).clip(shape)
            .background(
                if (f) SolidColor(DS.FocusFill)
                else if (active) SolidColor(Accent1.copy(alpha = .16f))
                else SolidColor(Color.Transparent)
            )
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(start = dpx(17), end = if (horizontal) dpx(17) else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MenuIcon(id, ink, Modifier.size(dpx(26)))
        if (expanded) {
            Spacer(Modifier.width(dpx(18)))
            Text(label, color = if (f) DS.FocusInk else Color.White, fontSize = spx(19),
                fontWeight = if (active || f) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        }
    }
}
