package com.ipvio.stream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Maßstab des Entwurfs (Bilder des Nutzers, 1658 px breit). Alle Maße der Startseite, Filme/Serien, Seitenleiste und Kopfzeile
 * werden in „Entwurfs-Pixeln“ angegeben und auf die echte Bildschirmbreite umgerechnet – so sieht die App auf jedem Gerät
 * genau wie der Entwurf aus (gleiche Anteile), unabhängig von der Einstellung „Anzeigegröße“.
 */
object Design {
    const val WIDTH = 1658f
    /** dp je Entwurfs-Pixel; wird in MainActivity aus der Bildschirmbreite gesetzt (1130 dp → 0,68). */
    var k by mutableFloatStateOf(0.68f)
}

/** Entwurfs-Pixel → dp. */
fun dpx(v: Number): Dp = (v.toFloat() * Design.k).dp

/** Entwurfs-Pixel → sp (Schriftgröße). */
fun spx(v: Number): TextUnit = typeSize(v.toFloat()).sp

/**
 * Lesbarkeit aus 3 m (Runde 17): kleine Entwurfsgrößen werden nicht beliebig klein. Unter etwa 19 Entwurfs-Pixeln bleibt
 * die Schrift bei mindestens 13 sp (bzw. 85 % des Entwurfswerts, falls kleiner); große Titel folgen genau dem Entwurf.
 */
fun typeSize(v: Float): Float = maxOf(v * Design.k, minOf(v * 0.85f, 13f))
