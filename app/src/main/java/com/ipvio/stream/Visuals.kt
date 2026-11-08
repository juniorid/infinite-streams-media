package com.ipvio.stream

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints

/*
 * Gemeinsame Bausteine der Oberfläche:
 *  - ArtPlaceholder: Platzhalter für fehlende Bilder (Verlauf, große Initialen, Symbol, Titel)
 *  - screenEnter: Einblenden von Bildschirmen (nur Transparenz und Verschiebung)
 *  - Pulse: kurzes Nachgeben beim Bestätigen
 *  - EmptyState: Leer-/Fehlerzustand mit Erklärung und Aktion
 * Alles beachtet „Animationen“ (Aus/Dezent/Normal) und läuft nie im Player.
 */

// ---------------------------------------------------------------------------------------------------------- Platzhalter

private fun initialsOf(title: String): String {
    val words = title.replace(Regex("""[^\p{L}\p{N} ]"""), " ").split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

private fun iconOf(k: Kind) = when (k) { Kind.LIVE -> AIcon.Live; Kind.SERIES -> AIcon.Layers; Kind.MOVIE -> AIcon.Play }

/**
 * Platzhalter für fehlende Bilder: Verlauf aus der Titel-Farbe, große Initialen als Motiv, Symbol der Art (Film/Serie/Sender)
 * und der Titel unten. Liegt unter dem Bild – lädt das Bild nicht, bleibt er sichtbar.
 */
@Composable
fun ArtPlaceholder(item: Item, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    // Runde 17c: schlank (ohne BoxWithConstraints, das in langen Reihen teuer war); feste, kleine Größen
    val base = colorFor(item)
    Box(modifier.background(Brush.linearGradient(listOf(lerp(base, Color.Black, 0.15f), lerp(Look.bg, base, 0.22f), Look.bg)))) {
        Text(
            raw = true, text = initialsOf(item.title), color = Color.White.copy(alpha = 0.14f), fontSize = spx(54),
            fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.align(Alignment.Center)
        )
        AppIcon(iconOf(item.kind), Color.White.copy(alpha = 0.55f), Modifier.align(Alignment.TopEnd).padding(dpx(10)), size = dpx(20))
        if (showTitle) Text(
            raw = true, text = item.title, color = Tok.TextHi, fontSize = spx(16),
            fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(dpx(12))
        )
    }
}

// ---------------------------------------------------------------------------------------------------------- Bewegung

/**
 * Einblenden eines Bildschirms: weich aus leichter Verschiebung, nur Transparenz/Verschiebung (ohne Zwischenpuffer).
 * Neuer [key] = neu einblenden (z. B. anderer Reiter).
 */
@Composable
fun Modifier.screenEnter(key: Any? = Unit): Modifier {
    if (Look.motion == 0) return this
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(if (Look.motion == 1) 220 else 380, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 18.dp.toPx()
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** Kurzes Nachgeben beim Bestätigen: [pulse] aufrufen, [scale] in graphicsLayer verwenden. */
class Pulse(private val anim: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, private val scope: kotlinx.coroutines.CoroutineScope) {
    val scale: Float get() = anim.value
    fun pulse() {
        if (Look.motion == 0) return
        scope.launch {
            anim.snapTo(0.93f)
            anim.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
        }
    }
}

@Composable
fun rememberPulse(): Pulse {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val anim = remember { Animatable(1f) }
    return remember { Pulse(anim, scope) }
}

// ---------------------------------------------------------------------------------------------------------- Leer-/Fehlerzustand

/** Leerer oder fehlerhafter Bereich: Symbol, Titel, Erklärung und (optional) eine Aktion, die weiterhilft. */
@Composable
fun EmptyState(
    icon: AIcon, title: String, text: String, modifier: Modifier = Modifier,
    action: String? = null, actionModifier: Modifier = Modifier, onAction: (() -> Unit)? = null
) {
    Column(
        modifier.widthIn(max = dpx(760)).padding(dpx(24)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(14))
    ) {
        Box(
            Modifier.size(dpx(84)).clip(RoundedCornerShape(50))
                .background(Brush.radialGradient(listOf(Accent1.copy(alpha = 0.28f), Color.Transparent))),
            contentAlignment = Alignment.Center
        ) { AppIcon(icon, Accent2, size = dpx(40)) }
        Text(title, color = Tok.TextHi, fontSize = spx(30), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(text, color = Tok.TextLow, fontSize = spx(19), lineHeight = spx(27), textAlign = TextAlign.Center)
        if (action != null && onAction != null) Row(Modifier.padding(top = dpx(8))) {
            HeroButton(action, AIcon.Right, true, actionModifier, 48) { onAction() }
        }
    }
}
