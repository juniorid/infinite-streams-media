package com.ipvio.stream

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Regeln für das Laden großer Playlists (ohne Android, testbar):
 * Speicher-Schätzung, Sprach-Vorauswahl, Fortsetzen eines abgebrochenen Downloads und der gemerkte Ladeauftrag.
 */
object LoadLogic {
    /** Anteil des Java-Speichers, den die Playlist höchstens belegen soll (Rest: Oberfläche, Bilder, Player, Metadaten). */
    const val SHARE = 0.40

    /**
     * Speicher je Byte der Liste. Gemessen (283.000 Titel, 230 MB): früher 0,92, seit die Folgen kompakt gespeichert werden
     * ([EpisodeTable]) 0,61 – gerechnet wird mit 0,65 (Reserve).
     */
    const val FACTOR = 0.65

    /** Wie viele Byte Playlist in den Speicher passen (Speicheranteil [SHARE] geteilt durch [FACTOR]). */
    fun budget(maxHeap: Long): Long = (maxHeap * SHARE / FACTOR).toLong()

    /**
     * Datenmenge der gezeigten Sprachen in Byte der Liste (verglichen mit [budget], das schon umgerechnet ist).
     */
    fun estimate(bytes: Map<String, Long>, hidden: Set<String>): Long =
        bytes.entries.sumOf { if (it.key in hidden) 0L else it.value }

    /** Passt die Auswahl? Ohne Daten (alte Zwischenspeicher) gilt: ja. */
    fun fits(bytes: Map<String, Long>, hidden: Set<String>, budget: Long): Boolean =
        bytes.isEmpty() || estimate(bytes, hidden) <= budget

    /**
     * Vorauswahl für die Rückfrage bei sehr großen Listen: die Sprache der App, dazu „ohne Kennung“, wenn beides zusammen passt.
     * Gibt es die App-Sprache nicht, die größte Sprache, die allein passt. Ergebnis = gezeigte Sprachen.
     */
    fun preselect(counts: Map<String, Int>, bytes: Map<String, Long>, appLang: String, budget: Long): Set<String> {
        val all = counts.keys
        fun fitsShown(s: Set<String>) = estimate(bytes, all - s) <= budget
        if (appLang in all) {
            val withUnknown = setOf(appLang, LangTagLogic.UNKNOWN).filter { it in all }.toSet()
            return if (withUnknown.size > 1 && fitsShown(withUnknown)) withUnknown else setOf(appLang)
        }
        val best = counts.entries.filter { it.key != LangTagLogic.UNKNOWN && fitsShown(setOf(it.key)) }.maxByOrNull { it.value }
        return if (best != null) setOf(best.key) else emptySet()
    }

    /** Gerätesprache (ISO, z. B. "de", "sr", "nb", "iw") → Kürzel der Sprachfilter ([LangTagLogic]). */
    fun langCodeOf(system: String): String = when (val l = system.lowercase()) {
        "bs", "hr", "sr", "sl", "mk", "sh", "cnr" -> "yu"
        "no", "nn", "nb" -> "nb"
        "iw", "he" -> "he"
        "fil", "tl" -> "tl"
        "sk", "cs" -> "cs"
        else -> l
    }

    /**
     * Vorauswahl der Sprachfrage: die Sprache des Geräts, wenn die Playlist Titel in ihr hat, sonst Englisch.
     * Gibt es beides nicht, ist nichts vorausgewählt. Ergebnis = gezeigte Sprachen.
     */
    fun systemPreselect(counts: Map<String, Int>, systemLang: String): Set<String> {
        val code = langCodeOf(systemLang)
        return when {
            (counts[code] ?: 0) > 0 -> setOf(code)
            (counts["en"] ?: 0) > 0 -> setOf("en")
            else -> emptySet()
        }
    }

    // ------------------------------------------------------------------ Fortsetzen (HTTP Range)

    const val APPEND = 0
    const val RESTART = 1
    const val ERROR = 2

    /** Starker Validator für „If-Range“: nur ein starkes ETag (ohne W/) oder Last-Modified. Ohne Validator kein Fortsetzen. */
    fun validator(etag: String?, lastModified: String?): String? = when {
        !etag.isNullOrBlank() && !etag.startsWith("W/") -> etag
        !lastModified.isNullOrBlank() -> lastModified
        else -> null
    }

    /**
     * Kopfzeilen, um einen Download ab [offset] fortzusetzen, oder leer (dann von vorn).
     * „If-Range“ sorgt dafür, dass ein geänderter Inhalt ganz neu geschickt wird statt falsch zusammengesetzt.
     */
    fun resumeHeaders(offset: Long, etag: String?, lastModified: String?): Map<String, String> {
        val v = validator(etag, lastModified) ?: return emptyMap()
        if (offset <= 0) return emptyMap()
        return mapOf("Range" to "bytes=$offset-", "If-Range" to v)
    }

    /** Was mit der Antwort zu tun ist: an die Teildatei anhängen, von vorn schreiben oder Fehler. */
    fun resumeAction(code: Int, contentRange: String?, offset: Long): Int = when {
        code == 206 && offset > 0 && rangeStart(contentRange) == offset -> APPEND
        code == 206 -> ERROR           // Teil-Antwort, die nicht passt: lieber neu versuchen als eine kaputte Liste
        code in 200..299 -> RESTART
        else -> ERROR
    }

    /** Startbyte aus „Content-Range: bytes 1000-1999/5000“, sonst -1. */
    fun rangeStart(contentRange: String?): Long {
        val m = Regex("""bytes\s+(\d+)-""").find(contentRange ?: return -1) ?: return -1
        return m.groupValues[1].toLongOrNull() ?: -1
    }

    /** Gesamtgröße aus „Content-Range: bytes 1000-1999/5000“, sonst -1. */
    fun rangeTotal(contentRange: String?): Long =
        contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1

    /** Angaben zur Teildatei eines unterbrochenen Downloads. */
    data class PartInfo(val urlHash: Int, val etag: String?, val lastModified: String?, val charset: String) {
        fun encode(): String = kv("u" to urlHash.toString(), "e" to etag, "m" to lastModified, "c" to charset)

        companion object {
            fun decode(s: String?): PartInfo? {
                val o = unkv(s) ?: return null
                val u = o["u"]?.toIntOrNull() ?: return null
                return PartInfo(u, o["e"], o["m"], o["c"] ?: "UTF-8")
            }
        }
    }

    // ------------------------------------------------------------------ gemerkter Ladeauftrag

    const val RUNNING = "run"
    const val FAILED = "fail"
    /** Ab so vielen unterbrochenen Versuchen wird nicht mehr automatisch weitergeladen. */
    const val MAX_AUTO = 3

    /**
     * Eine Playlist, die gerade hinzugefügt wird. Wird VOR dem Download gespeichert:
     * stürzt die App ab, fällt der Strom aus oder wird sie geschlossen, geht beim nächsten Start nichts verloren.
     */
    data class Pending(val url: String, val state: String, val attempts: Int, val msg: String?) {
        /** Beim letzten Mal mitten im Laden beendet (Absturz, Stromausfall, App geschlossen). */
        val interrupted: Boolean get() = state == RUNNING
        val autoResume: Boolean get() = interrupted && attempts < MAX_AUTO

        fun encode(): String = kv("url" to url, "s" to state, "a" to attempts.toString(), "m" to msg)

        companion object {
            fun decode(s: String?): Pending? {
                val o = unkv(s) ?: return null
                val url = o["url"]?.takeIf { it.isNotBlank() } ?: return null
                return Pending(url, o["s"] ?: RUNNING, o["a"]?.toIntOrNull() ?: 1, o["m"])
            }
        }
    }

    /** Neuer Versuch: von Hand gestartet zählt neu ab 1, automatisch fortgesetzt zählt weiter. */
    fun started(prev: Pending?, url: String, resumed: Boolean): Pending =
        Pending(url, RUNNING, if (resumed && prev?.url == url) prev.attempts + 1 else 1, null)

    /** Einfache Textform „schlüssel=wert“ je Zeile (Werte kodiert); ohne org.json, damit es in JVM-Tests läuft. */
    private fun kv(vararg p: Pair<String, String?>): String =
        p.filter { it.second != null }.joinToString("\n") { it.first + "=" + URLEncoder.encode(it.second, "UTF-8") }

    private fun unkv(s: String?): Map<String, String>? {
        if (s.isNullOrBlank()) return null
        return try {
            s.split('\n').mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i) to URLDecoder.decode(l.substring(i + 1), "UTF-8")
            }.toMap()
        } catch (e: Exception) { null }
    }

    /** Xtream-Zugang aus einer get.php-Adresse (für das Vorausfüllen des Formulars), sonst null. */
    fun xtreamParts(url: String): Triple<String, String, String>? {
        val m = Regex("""^(https?://[^/?#]+)/get\.php\?(.*)$""").find(url.trim()) ?: return null
        val q = m.groupValues[2].split('&').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i) to URLDecoder.decode(p.substring(i + 1), "UTF-8")
        }.toMap()
        val user = q["username"] ?: return null
        val pass = q["password"] ?: return null
        return Triple(m.groupValues[1], user, pass)
    }
}

/**
 * Unterhaltung bei sehr langem Laden (Wunsch des Nutzers, bewusst auf Englisch):
 * ab 5 Minuten Zitate berühmter Leute über Geduld, ab 10 Minuten sarkastische Sprüche, ab 15 Minuten der Hinweis auf die Sprachauswahl.
 */
object LoadFun {
    const val QUOTES_AFTER = 5 * 60_000L
    const val SARCASM_AFTER = 10 * 60_000L
    const val HINT_AFTER = 15 * 60_000L
    /** So lange bleibt ein Spruch stehen. */
    const val ROTATE = 15_000L

    val QUOTES = listOf(
        "Rome wasn't built in a day." to "Proverb",
        "Adopt the pace of nature: her secret is patience." to "Ralph Waldo Emerson",
        "The two most powerful warriors are patience and time." to "Leo Tolstoy",
        "He that can have patience can have what he will." to "Benjamin Franklin",
        "It does not matter how slowly you go as long as you do not stop." to "Confucius",
        "Slow and steady wins the race." to "Aesop",
        "Patience and fortitude conquer all things." to "Ralph Waldo Emerson",
        "Have patience. All things are difficult before they become easy." to "Saadi",
        "All good things come to those who wait." to "Proverb"
    )

    val SARCASM = listOf(
        "Seriously, how many titles do they have?",
        "Still loading. Your provider apparently owns every movie ever made.",
        "At this point the playlist needs its own zip code.",
        "Somewhere a server is sweating. Probably yours.",
        "You could have watched a whole episode by now. Just saying.",
        "Your provider called. They want to know why you need all of this.",
        "This isn't a playlist anymore. It's a lifestyle.",
        "Grab a snack. Actually, grab two.",
        "Have you tried turning your patience off and on again?",
        "Counting titles… we ran out of fingers a while ago."
    )

    /** 0 = nichts, 1 = Zitate, 2 = Sarkasmus, 3 = zusätzlich Hinweis auf die Sprachauswahl. */
    fun stage(elapsedMs: Long): Int = when {
        elapsedMs >= HINT_AFTER -> 3
        elapsedMs >= SARCASM_AFTER -> 2
        elapsedMs >= QUOTES_AFTER -> 1
        else -> 0
    }

    /** Der Spruch für diesen Moment (Text, Urheber oder null) – wechselt alle [ROTATE] ms; vor 5 Minuten null. */
    fun line(elapsedMs: Long): Pair<String, String?>? = when (stage(elapsedMs)) {
        0 -> null
        1 -> QUOTES[(((elapsedMs - QUOTES_AFTER) / ROTATE) % QUOTES.size).toInt()]
        else -> SARCASM[(((elapsedMs - SARCASM_AFTER) / ROTATE) % SARCASM.size).toInt()] to null
    }
}
