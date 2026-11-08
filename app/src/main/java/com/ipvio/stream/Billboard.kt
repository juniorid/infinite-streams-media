package com.ipvio.stream

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/*
 * Runde 17b – „Cinematic Remake“ der Startseite (Entwurf „IPVIO Cinematic Remake“, Bild 1):
 *  - Der ganze Bildschirm zeigt das Szenenbild des markierten Titels (weiche Überblendung, langsamer Zoom).
 *  - Oben links stehen Titel, Wertungen, Jahr, Genre, Länge und Beschreibung dieses Titels (Billboard).
 *  - Die markierte Reihe bleibt in der unteren Hälfte stehen (PivotSpec), Reihen darüber verschwinden.
 *  - In der markierten Reihe treten die anderen Karten zurück (abgedunkelt), die markierte wächst.
 */

/** Zuletzt markierter Film/Serie (von den Karten gesetzt); die Startseite zeigt ihn im Hintergrund. */
object Billboard {
    var focused by mutableStateOf<Item?>(null)
    /** true, solange der Fokus auf einer Titelkarte liegt (nicht im großen Bild, bei den Kacheln oder in der Seitenleiste). */
    var cardFocus by mutableStateOf(false)
    /** Position der markierten Karte (zum Berechnen der Oberkante ihrer Reihe, siehe Ausblendung in HomeScreen). */
    var focusCo: LayoutCoordinates? = null
    /** true, solange der Hintergrund mit den Infos oben wirklich gezeigt wird (dann pausiert das große Bild). */
    var active by mutableStateOf(false)
    fun mark(item: Item) {
        if (item.kind == Kind.LIVE) return
        cardFocus = true
        if (focused?.id != item.id) focused = item
    }
}

/** Wie onFocusChanged für Titelkarten: meldet den Fokus, markiert den Titel für den Hintergrund und merkt sich die Position der Karte. */
fun Modifier.billboardFocus(item: Item, onFocus: (Boolean) -> Unit): Modifier = composed {
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    this.onGloballyPositioned { holder[0] = it }.onFocusChanged { s ->
        onFocus(s.isFocused)
        if (s.isFocused) { Billboard.mark(item); Billboard.focusCo = holder[0] }
    }
}

/** true, solange die Reihe, in der eine Karte liegt, den Fokus hat (die übrigen Karten werden abgedunkelt). */
val LocalRowFocus = compositionLocalOf { false }

/**
 * Hält das markierte Element einer Liste auf fester Höhe ([pivot] = Anteil der Höhe, an dem die Oberkante steht).
 * Sehr große Elemente (das große Bild oben) werden wie bisher nur so weit wie nötig ins Bild geholt.
 */
@OptIn(ExperimentalFoundationApi::class)
class PivotSpec(private val pivot: Float, private val list: () -> androidx.compose.foundation.lazy.LazyListState) : BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec = tween<Float>(if (Look.motion == 0) 1 else 380, easing = FastOutSlowInEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (size >= containerSize * 0.45f) return SmoothSpec.calculateScrollDistance(offset, size, containerSize)
        val d = offset - containerSize * pivot
        // 17c: Am Anfang/Ende der Liste geht es nicht weiter – dann nichts mehr verlangen (sonst versucht die Liste es endlos
        // weiter, was den Fire TV dauerhaft beschäftigt). Kleine Reste ignorieren.
        val st = list()
        if (d < 0f && !st.canScrollBackward) return 0f
        if (d > 0f && !st.canScrollForward) return 0f
        return if (kotlin.math.abs(d) < 2f) 0f else d
    }
}

/** Vollbild-Hintergrund mit dem Szenenbild des Titels; ohne Bild ein Verlauf aus der Titelfarbe. */
@Composable
fun BillboardBackdrop(item: Item?, modifier: Modifier = Modifier) {
    Box(modifier.background(Bg)) {
        // 17c: ruhiges Bild (kein Dauer-Zoom), mittlere Bildgröße (w780) statt voller Auflösung – spart Speicher und Rechenzeit
        Crossfade(item, animationSpec = tween(if (Look.motion == 0) 0 else 600), label = "bb") { it0 ->
            if (it0 != null) {
                val m = rememberMeta(it0)
                val url = m?.backdrop?.let { b -> cardBackdrop(b) } ?: m?.poster?.let { p -> cardPoster(p) }
                if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
                else ArtPlaceholder(it0, Modifier.fillMaxSize(), showTitle = false)
            }
        }
        // Verläufe: links kräftig (Text), unten in den Hintergrund (Reihen), oben dezent
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.24f to Bg.copy(alpha = .94f), 0.48f to Bg.copy(alpha = .5f), 0.72f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg.copy(alpha = .5f), 0.16f to Color.Transparent, 0.38f to Color.Transparent, 0.58f to Bg.copy(alpha = .82f), 0.78f to Bg)))
    }
}

/** Infos zum markierten Titel oben links (wie das große Bild, aber für jeden markierten Titel). */
@Composable
fun BillboardInfo(item: Item, modifier: Modifier = Modifier) {
    Crossfade(item, animationSpec = tween(if (Look.motion == 0) 0 else 450), label = "bbi", modifier = modifier) { c ->
        val m = rememberFullMeta(c, rememberMeta(c))
        Column(Modifier.widthIn(max = dpx(980)), verticalArrangement = Arrangement.spacedBy(dpx(14))) {
            Kicker(Lang.tr(kindLabel(c.kind)) + (qualityOf(c)?.let { q -> "  ·  " + q.uppercase() } ?: ""))
            // Der Info-Block muss in den Platz über der markierten Reihe passen: Titel immer einzeilig (lange Titel kleiner),
            // Beschreibung höchstens zwei Zeilen. Sonst läuft er in den Reihentitel.
            val tsz = if (c.title.length <= 24) 84 else if (c.title.length <= 34) 66 else 54
            Text(
                raw = true, text = c.title, color = Tok.TextHi, fontSize = spx(tsz), lineHeight = spx(84),
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            HeroMetaLine(c, m, dots = false)
            m?.overview?.takeIf { it.isNotBlank() }?.let {
                Text(raw = true, text = TitleText.short(it, 170), color = Tok.TextMid, fontSize = spx(20), lineHeight = spx(29), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Kleine Überzeile mit leuchtendem Balken (Art, Neu, Qualität). */
@Composable
fun Kicker(text: String, raw: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
        Box(
            Modifier.width(dpx(5)).height(dpx(24)).shadow(dpx(10), RoundedCornerShape(50), ambientColor = Accent1, spotColor = Accent1)
                .clip(RoundedCornerShape(50)).background(Accent1)
        )
        Text(raw = raw, text = text, color = Accent2, fontSize = spx(17), fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp, maxLines = 1)
    }
}

