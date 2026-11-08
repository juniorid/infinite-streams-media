package com.ipvio.stream

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

/** Sprache der Oberfläche. "" in den Einstellungen = Sprache des Geräts (nicht unterstützt → Englisch). */
object Lang {
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI, DictJ)
        I18nCore.loadColumn(I18nCore.CODES.indexOf("nl"), DictNl1, DictNl2, DictNl3, DictNl4, DictNl5)
    }

    /** Index in I18nCore.CODES. Liest den Zustand, damit Texte beim Umstellen neu gezeichnet werden. */
    val index: Int
        get() {
            ensureLoaded()
            val c = General.lang
            return if (c.isBlank()) I18nCore.indexForSystem(Locale.getDefault().language) else I18nCore.CODES.indexOf(c).coerceAtLeast(0)
        }

    fun code(): String = I18nCore.CODES[index]

    fun locale(): Locale = when (code()) {
        "bs" -> Locale.Builder().setLanguage("bs").setRegion("BA").build()
        "hr" -> Locale.Builder().setLanguage("hr").setRegion("HR").build()
        "sr" -> Locale.Builder().setLanguage("sr").setScript("Latn").setRegion("RS").build()
        else -> Locale.forLanguageTag(code())
    }

    /** Sprachcode für TMDB-Abfragen, z. B. "de-DE". */
    fun tmdb(): String = when (code()) {
        "en" -> "en-US"; "tr" -> "tr-TR"; "bs" -> "bs-BA"; "hr" -> "hr-HR"; "sr" -> "sr-RS"
        "es" -> "es-ES"; "fr" -> "fr-FR"; "it" -> "it-IT"; "nl" -> "nl-NL"; else -> "de-DE"
    }

    fun tr(s: String): String {
        val i = index
        return if (i == 0) s else I18nCore.translate(s, i)
    }
}

/**
 * Text für die ganze App (ersetzt material3.Text, die Bibliothek wird dadurch nicht mehr gebraucht):
 * übersetzt Oberflächentexte in die gewählte Sprache und wendet die gewählte Schriftart an.
 * [raw] = true für Inhalte (Filmtitel, Sendernamen, Beschreibungen, TV-Programm): die werden nie übersetzt –
 * sonst würde z. B. die Serie „Familie“ in der englischen Oberfläche zu „Family“, und jeder neue Titel kostete Wörterbuch-Suchen.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = TextStyle.Default,
    raw: Boolean = false
) {
    val merged = style.merge(
        TextStyle(
            color = if (color != Color.Unspecified) color else if (style.color != Color.Unspecified) style.color else Color.Black,
            fontSize = fontSize, fontWeight = fontWeight, fontStyle = fontStyle,
            fontFamily = fontFamily ?: (if (style.fontFamily == null) General.fontFamily() else null),
            letterSpacing = letterSpacing, textDecoration = textDecoration,
            textAlign = textAlign ?: TextAlign.Unspecified, lineHeight = lineHeight
        )
    )
    BasicText(
        text = if (raw) text else Lang.tr(text), modifier = modifier, style = merged,
        onTextLayout = onTextLayout, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines
    )
}
