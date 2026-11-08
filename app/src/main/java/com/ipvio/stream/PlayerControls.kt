package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Runder Steuerknopf. Fokus = weiße Fläche mit dunklem Symbol (gut aus 3 m erkennbar). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PRoundButton(
    modifier: Modifier = Modifier,
    size: Dp = PT.BtnSize,
    enabled: Boolean = true,
    label: String = "",
    onLabel: (String) -> Unit = {},
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.12f) else 1f, animationSpec = tween(140), label = "pbtn")
    Box(
        modifier
            .focusProperties { canFocus = enabled }
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .size(size)
            .clip(CircleShape)
            .background(if (f) Color.White else PT.BtnBg)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onLabel(label) }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(if (!enabled) Color(0x59FFFFFF) else if (f) PT.Ink else Color.White)
    }
}

/** Kleine Info-Marke, z. B. "4K", "HEVC", "Staffel 2". */
@Composable
fun PChip(text: String, modifier: Modifier = Modifier, filled: Boolean = false, icon: AIcon? = null, raw: Boolean = false) {
    Box(
        modifier.height(26.dp).clip(RoundedCornerShape(DS.RadiusXs))
            .background(if (filled) Color.White else Color(0x29FFFFFF))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        IconLabel(icon, text, if (filled) PT.Ink else Color.White, 14.sp, FontWeight.Bold, iconSize = 15.dp, raw = raw)
    }
}

@Composable
fun PLiveChip(modifier: Modifier = Modifier, behindSec: Long = 0L) {
    val behind = behindSec >= 3
    Box(
        modifier.height(26.dp).clip(RoundedCornerShape(DS.RadiusXs)).background(if (behind) Color(0xFF555B6E) else PT.Live).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (behind) "LIVE  −" + (behindSec / 60) + ":" + (behindSec % 60).toString().padStart(2, '0') else "LIVE",
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1
        )
    }
}

/** Dezenter Überspringen-Knopf (Intro, Rückblick, Abspann → nächste Folge): klein, durchsichtig, im Stil der App. */
@Composable
fun PSkipPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.05f) else 1f, animationSpec = tween(120), label = "skip")
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .height(38.dp).clip(shape)
            .background(if (f) DS.FocusFill else Color(0x8C000000))
            .border(if (f) 3.dp else 1.dp, if (f) Accent1 else Color(0x59FFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerIcon(PIcon.NextEp, if (f) DS.FocusInk else Color.White, size = 16.dp)
        Text(if (f && !text.endsWith("(OK)")) "$text  (OK)" else text, color = if (f) DS.FocusInk else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Zeichnet die Zeitleiste. Alle Werte kommen als Lambdas und werden erst beim ZEICHNEN gelesen –
 * dadurch muss bei jedem Positions-Tick nur gezeichnet, nicht neu zusammengesetzt werden.
 */
@Composable
fun PSeekTrack(
    modifier: Modifier,
    pos: () -> Long, dur: () -> Long, buffered: () -> Long, segs: () -> List<Seg>,
    focused: Boolean
) {
    val fp by animateFloatAsState(if (focused) 1f else 0f, animationSpec = tween(120), label = "seekfocus")
    Canvas(modifier) {
        val d = dur().coerceAtLeast(1L).toFloat()
        val w = size.width
        val cy = size.height / 2f
        val th = (6f + 4f * fp).dp.toPx()
        val top = cy - th / 2f
        val rad = CornerRadius(th / 2f)
        drawRoundRect(PT.Track, Offset(0f, top), Size(w, th), rad)
        val bw = (buffered().toFloat() / d).coerceIn(0f, 1f) * w
        if (bw > 0f) drawRoundRect(PT.Buffered, Offset(0f, top), Size(bw, th), rad)
        val frac = (pos().toFloat() / d).coerceIn(0f, 1f)
        val pw = frac * w
        // Runde 17b: grüner Schein unter dem gespielten Teil (cinematisch, nur gezeichnet, kein Effekt aufs Video)
        if (pw > 0f) drawRoundRect(Accent1.copy(alpha = 0.22f), Offset(0f, top - th * 0.7f), Size(pw, th * 2.4f), CornerRadius(th * 1.2f))
        if (pw > 0f) drawRoundRect(Accent1, Offset(0f, top), Size(pw, th), rad)
        for (s in segs()) {
            val x1 = (s.start.toFloat() / d).coerceIn(0f, 1f) * w
            val x2 = (s.end.toFloat() / d).coerceIn(0f, 1f) * w
            if (x2 - x1 > 1f) drawRoundRect(Accent2.copy(alpha = 0.85f), Offset(x1, top), Size(x2 - x1, th), rad)
        }
        val tr = 7f.dp.toPx() + 4f.dp.toPx() * fp
        val cx = pw.coerceIn(tr, (w - tr).coerceAtLeast(tr))
        if (fp > 0.01f) drawCircle(Accent2, radius = tr, center = Offset(cx, cy), alpha = fp)
        drawCircle(Color.White, radius = tr - 3f.dp.toPx() * fp, center = Offset(cx, cy))
    }
}

/** Zeit-Blase über dem Regler (folgt der Position). */
@Composable
private fun PTimeBubble(pos: () -> Long, dur: () -> Long) {
    val shape = RoundedCornerShape(DS.RadiusXs)
    Box(
        Modifier
            .layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                val barW = c.maxWidth
                val d = dur().coerceAtLeast(1L)
                val cx = pos().toFloat() / d * barW
                val x = (cx - p.width / 2f).toInt().coerceIn(0, (barW - p.width).coerceAtLeast(0))
                layout(p.width, p.height) { p.place(x, -(p.height + 6.dp.roundToPx())) }
            }
            .clip(shape).background(Surf).border(1.dp, PT.PanelBorder, shape)
            .height(28.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(fmtTime(pos()), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, style = TNUM, maxLines = 1)
    }
}

/** Fokussierbare Zeitleiste: Links/Rechts springen, Halten springt weiter, Runter geht zu den Knöpfen. */
@Composable
fun PSeekBar(
    modifier: Modifier,
    pos: () -> Long, dur: () -> Long, buffered: () -> Long, segs: () -> List<Seg>,
    scrubbing: () -> Boolean,
    onFocus: (Boolean) -> Unit,
    onDown: () -> Unit, onSeekKey: (Int) -> Unit, onRepeat: (Int) -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val active = f || scrubbing()
    Box(
        modifier.height(32.dp)
            .onFocusChanged { f = it.isFocused; onFocus(it.isFocused) }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.DirectionLeft -> { if (e.nativeKeyEvent.repeatCount > 0) onRepeat(-1) else onSeekKey(-1); true }
                    Key.DirectionRight -> { if (e.nativeKeyEvent.repeatCount > 0) onRepeat(1) else onSeekKey(1); true }
                    Key.DirectionDown -> { onDown(); true }
                    else -> false
                }
            }
            .focusable()
    ) {
        PSeekTrack(Modifier.fillMaxSize(), pos, dur, buffered, segs, active)
        if (active) PTimeBubble(pos, dur)
    }
}

/** Nicht fokussierbare Zeitleiste für den Spul-Moment, wenn die Steuerleiste ausgeblendet ist. */
@Composable
fun PMiniSeek(
    pos: () -> Long, dur: () -> Long, buffered: () -> Long, segs: () -> List<Seg>,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(32.dp)) {
            PSeekTrack(Modifier.fillMaxSize(), pos, dur, buffered, segs, true)
            PTimeBubble(pos, dur)
        }
        PTimeRow(pos, dur)
    }
}

/** Zeile unter der Zeitleiste: links vergangene Zeit, rechts Restzeit. */
@Composable
fun PTimeRow(pos: () -> Long, dur: () -> Long) {
    Row(
        Modifier.fillMaxWidth().height(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(fmtTime(pos()), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = TNUM, maxLines = 1)
        Text("−" + fmtTime((dur() - pos()).coerceAtLeast(0L)), color = PT.T2, fontSize = 16.sp, style = TNUM, maxLines = 1)
    }
}

/** Die Uhr selbst (Größe und Deckkraft aus den Einstellungen). */
@Composable
private fun ClockText(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) } }
    val sp = when (AppSettings.clockSize) { 0 -> 16f; 2 -> 30f; else -> 22f }
    Text(
        hhmm(now / 1000), color = Color.White.copy(alpha = AppSettings.clockOpacity / 100f), fontSize = sp.sp, fontWeight = FontWeight.SemiBold, style = TNUM, maxLines = 1,
        modifier = modifier
            .then(if (AppSettings.clockBg) Modifier.background(Color.Black.copy(alpha = AppSettings.clockBgOpacity / 100f), RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Uhrzeit im Bild: ganz in der gewählten Ecke (schmaler Rand). */
@Composable
fun BoxScope.PClock() {
    val al = when (AppSettings.clockPos) { 0 -> Alignment.TopEnd; 1 -> Alignment.TopStart; 2 -> Alignment.BottomEnd; else -> Alignment.BottomStart }
    ClockText(Modifier.align(al).padding(12.dp))
}

/** Vorschau der Uhr in den Einstellungen: nur die Uhr in echter Größe und Deckkraft vor einem Bildhintergrund (ohne Ecken-Muster). */
@Composable
fun ClockSample(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.width(220.dp).height(84.dp).clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3A5A8C), Color(0xFFB07A3C), Color(0xFF1E3A2F))))
            .border(1.dp, Color(0x33FFFFFF), shape),
        contentAlignment = Alignment.Center
    ) { ClockText() }
}

@Composable
fun PEndsAt(pos: () -> Long, dur: () -> Long, speed: () -> Float) {
    val remain = (dur() - pos()).coerceAtLeast(0L)
    Text(
        "Endet um " + hhmm(endClockSec(System.currentTimeMillis(), remain, speed())),
        color = Muted, fontSize = 14.sp, style = TNUM, maxLines = 1
    )
}

// ---------------------------------------------------------------------------------------------
// Menüs (rechts oben)
// ---------------------------------------------------------------------------------------------

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.PPanelFrame(
    width: Dp, title: String, hint: String,
    modifier: Modifier = Modifier,
    onEdgeDown: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val maxH = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(240).dp
    val shape = RoundedCornerShape(PT.PanelRadius)
    Column(
        Modifier.align(Alignment.TopEnd).padding(top = PT.MarginY, end = PT.MarginY)
            .width(width).heightIn(max = maxH)
            .clip(shape).background(PT.PanelBg).border(1.dp, PT.PanelBorder, shape)
            // Runter am Ende der Liste: Menü schließen, der Fokus geht zurück auf das Symbol in der Steuerleiste.
            // "exit" wird nur aufgerufen, wenn der Fokus das Menü nach unten verlassen würde (also nichts mehr darunter im Menü liegt).
            .focusProperties {
                exitTo { dir ->
                    if (dir == FocusDirection.Down && onEdgeDown != null) { onEdgeDown(); FocusRequester.Cancel }
                    else FocusRequester.Default
                }
            }
            .focusGroup()
            .then(modifier)
            .padding(24.dp)
    ) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(hint, color = Muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
        content()
    }
}

/** Menü mit Standard-Tasten: Links schließt, Rechts bleibt im Menü. */
@Composable
fun BoxScope.PSimplePanel(
    width: Dp, title: String, hint: String, onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    PPanelFrame(
        width, title, hint,
        Modifier
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onClose(); true } else false }
            // Rechts erst nach den Zeilen schlucken: Zeilen mit Pfeil öffnen damit ihr Untermenü, sonst bleibt der Fokus im Menü
            .onKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight },
        onEdgeDown = onClose,
        content = content
    )
}

/** Eine Menüzeile: 52 dp hoch, 17 sp. Gewählt = fett + türkiser Haken, Fokus = weiß mit dunkler Schrift. */
@Composable
fun PPanelRow(
    text: String, selected: Boolean, modifier: Modifier = Modifier,
    trailing: String? = null, chevron: Boolean = false, tinted: Boolean = false,
    trailingIcon: AIcon? = null, raw: Boolean = false, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().height(PT.RowHeight).clip(shape)
            .background(if (f) Color.White else if (tinted) DS.Overlay1 else Color.Transparent)
            .onFocusChanged { f = it.isFocused }
            .then(if (chevron || trailing != null) Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onClick(); true } else false
            } else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text, color = if (f) PT.Ink else Color.White, fontSize = 17.sp,
            fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), raw = raw
        )
        if (trailing != null) {
            Text(trailing, color = if (f) PT.InkSoft else PT.T2, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
        }
        if (trailingIcon != null) AppIcon(trailingIcon, if (f) PT.InkSoft else PT.Gold, Modifier.padding(start = 12.dp), 18.dp)
        if (chevron) {
            PlayerIcon(PIcon.Chevron, if (f) PT.Ink else PT.T2, Modifier.padding(start = 8.dp), 20.dp)
        } else if (selected) {
            PlayerIcon(PIcon.Check, if (f) PT.Ink else Accent2, Modifier.padding(start = 8.dp), 20.dp)
        }
    }
}

/** Spaltenüberschrift im Menü (z. B. "Audio", "Untertitel"). */
@Composable
fun PColumnHeader(text: String) {
    Text(
        text, color = PT.T2, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
    )
}
