package com.ipvio.stream

/**
 * Regeln rund um die Wiedergabe (ohne Android, testbar):
 * welche Quelle bei mehreren Qualitäten, wann auf eine kleinere ausgewichen wird, Untertitel-Automatik, defekte Links.
 */
object QualityLogic {
    /** Einstellung „Qualität bei mehreren Quellen“. */
    const val AUTO = 0
    const val HIGHEST = 1
    const val MAX_FHD = 2
    const val MAX_HD = 3

    /** Rang einer Quelle: 4K = 4, FHD = 3, HD = 2, sonst (SD/unbekannt) = 1. */
    fun rank(q: String?): Int = when (q) { "4K" -> 4; "FHD", "2K" -> 3; "HD" -> 2; else -> 1 }

    /**
     * Was dieses Gerät gut schafft: 4K nur mit 4K-Bildschirm UND Hardware-Decoder für 4K (HEVC).
     * [learnedCap] = Grenze, die die App gelernt hat, weil 4K hier mehrfach ruckelte (0 = keine).
     */
    fun deviceMax(displayHeight: Int, hevc4k: Boolean, learnedCap: Int): Int {
        val disp = when {
            displayHeight <= 0 -> 3            // unbekannt: Full HD
            displayHeight >= 2000 -> 4
            displayHeight >= 1000 -> 3
            displayHeight >= 700 -> 2
            else -> 1
        }
        var m = if (disp >= 4 && !hevc4k) 3 else disp
        if (learnedCap in 1..3) m = minOf(m, learnedCap)
        return m
    }

    /** Höchster Rang, den die Automatik wählt. */
    fun cap(pref: Int, deviceMax: Int): Int = when (pref) {
        HIGHEST -> 4
        MAX_FHD -> 3
        MAX_HD -> 2
        else -> deviceMax
    }

    /**
     * Reihenfolge der Quellen: erst die beste bis zur Grenze, dann absteigend; Quellen über der Grenze zuletzt
     * (die kleinste davon zuerst) – gibt es nur 4K, wird 4K trotzdem gespielt.
     */
    fun order(qs: List<String?>, cap: Int): List<Int> =
        qs.indices.sortedWith(compareBy<Int>({ if (rank(qs[it]) <= cap) 0 else 1 }, { if (rank(qs[it]) <= cap) -rank(qs[it]) else rank(qs[it]) }))

    /**
     * Ausweichquelle: noch nicht probiert, nie eine größere Qualität.
     * Ruckeln ([sameFirst] = false): die nächstkleinere Qualität, sonst eine gleich gute.
     * Fehler ([sameFirst] = true, z. B. Link tot): erst eine gleich gute andere Quelle, sonst die nächstkleinere.
     * null = keine mehr.
     */
    fun fallback(qs: List<String?>, current: Int, tried: Set<Int>, sameFirst: Boolean = false): Int? {
        val cr = rank(qs.getOrNull(current))
        val open = qs.indices.filter { it !in tried && it != current }
        val lower = open.filter { rank(qs[it]) < cr }.maxByOrNull { rank(qs[it]) }
        val same = open.firstOrNull { rank(qs[it]) == cr }
        return if (sameFirst) same ?: lower else lower ?: same
    }
}

/** Ruckeln erkennen: mehrere Nachladepausen kurz hintereinander (nicht durch Spulen ausgelöst). */
object StallLogic {
    const val WINDOW = 120_000L
    const val COUNT = 3

    /** Neue Liste der Pausen-Zeitpunkte (nur die im Zeitfenster). */
    fun add(stalls: List<Long>, now: Long): List<Long> = stalls.filter { now - it < WINDOW } + now

    /** Auf eine kleinere Quelle ausweichen? */
    fun tooMany(stalls: List<Long>, now: Long): Boolean = stalls.count { now - it < WINDOW } >= COUNT
}

/** Untertitel-Automatik und Sprachkürzel. */
object TrackLogic {
    const val SUB_OFF = 0
    const val SUB_ALWAYS = 1
    /** Nur wenn die Tonspur nicht in einer Sprache ist, die man versteht. */
    const val SUB_FOREIGN = 2

    private val ISO3 = mapOf(
        "ger" to "de", "deu" to "de", "eng" to "en", "tur" to "tr", "hrv" to "hr", "scr" to "hr", "srp" to "sr", "scc" to "sr",
        "bos" to "bs", "fre" to "fr", "fra" to "fr", "spa" to "es", "ita" to "it", "jpn" to "ja", "kor" to "ko", "por" to "pt",
        "rus" to "ru", "pol" to "pl", "ara" to "ar", "dut" to "nl", "nld" to "nl", "chi" to "zh", "zho" to "zh", "hin" to "hi",
        "gre" to "el", "ell" to "el", "alb" to "sq", "sqi" to "sq", "rum" to "ro", "ron" to "ro", "hun" to "hu", "cze" to "cs", "ces" to "cs"
    )

    /** Sprachkürzel vereinheitlichen: „ger“, „de-DE“, „DE“ → „de“. Unbekannt/„und“ → null. */
    fun norm(code: String?): String? {
        val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        if (c.isEmpty() || c == "und" || c == "mul" || c == "zxx") return null
        return ISO3[c] ?: c.takeIf { it.length == 2 } ?: c
    }

    private val LABEL_LANG = mapOf(
        "deutsch" to "de", "german" to "de", "ger" to "de", "deu" to "de", "de" to "de",
        "english" to "en", "englisch" to "en", "eng" to "en", "en" to "en",
        "japanese" to "ja", "japanisch" to "ja", "jpn" to "ja", "jap" to "ja", "ja" to "ja",
        "french" to "fr", "französisch" to "fr", "franzosisch" to "fr", "fra" to "fr", "fre" to "fr", "fr" to "fr",
        "spanish" to "es", "spanisch" to "es", "spa" to "es", "es" to "es",
        "italian" to "it", "italienisch" to "it", "ita" to "it", "it" to "it",
        "turkish" to "tr", "türkisch" to "tr", "turkisch" to "tr", "tur" to "tr", "tr" to "tr",
        "korean" to "ko", "koreanisch" to "ko", "kor" to "ko", "ko" to "ko",
        "russian" to "ru", "russisch" to "ru", "rus" to "ru", "ru" to "ru",
        "croatian" to "hr", "kroatisch" to "hr", "hrv" to "hr", "hr" to "hr",
        "serbian" to "sr", "serbisch" to "sr", "srp" to "sr", "sr" to "sr",
        "bosnian" to "bs", "bosnisch" to "bs", "bos" to "bs", "bs" to "bs",
        "polish" to "pl", "polnisch" to "pl", "pol" to "pl", "pl" to "pl",
        "arabic" to "ar", "arabisch" to "ar", "ara" to "ar", "ar" to "ar"
    )

    /**
     * Sprache einer Spur: aus dem Sprachkürzel, bei „und“/leer (bei IPTV-Dateien häufig) aus der Bezeichnung
     * („Deutsch 5.1“, „German AC3“, „DE“). Unbekannt → null.
     */
    fun guess(language: String?, label: String?): String? {
        norm(language)?.let { return it }
        val l = label?.lowercase() ?: return null
        return l.split(Regex("[^a-zäöüß]+")).firstNotNullOfOrNull { LABEL_LANG[it] }
    }

    /** Bevorzugte Tonsprachen: gemerkte Wahl für diesen Titel, dann erste und zweite Sprache aus den Einstellungen. */
    fun audioPrefs(primary: String, secondary: String, remembered: String?): List<String> =
        listOfNotNull(norm(remembered), norm(primary), norm(secondary)).distinct()

    /** Sollen Untertitel an sein? [audio] = Sprache der gewählten Tonspur. */
    fun subsWanted(mode: Int, audio: String?, subLang: String, primaryAudio: String): Boolean = when (mode) {
        SUB_OFF -> false
        SUB_ALWAYS -> true
        else -> {
            val a = norm(audio)
            // Sprache unbekannt: lieber nichts einblenden
            a != null && a !in setOfNotNull(norm(subLang), norm(primaryAudio))
        }
    }

    /** Gemerkte Spurwahl je Titel: „Ton|Untertitel“, Untertitel „-“ = aus. */
    fun encode(audio: String?, text: String?): String = (audio ?: "") + "|" + (text ?: "-")

    fun decode(s: String?): Pair<String?, String?>? {
        if (s.isNullOrBlank() || '|' !in s) return null
        val a = s.substringBefore('|').ifBlank { null }
        val t = s.substringAfter('|').let { if (it == "-") null else it.ifBlank { null } }
        return a to t
    }
}

/** Defekte Links: wann eine Adresse als gestört gilt. */
object DeadLinkLogic {
    /** Gestört: sofort bei „gibt es nicht mehr“ (404/410), sonst nach zwei fehlgeschlagenen Versuchen an verschiedenen Tagen/Malen. */
    fun dead(fails: Int, permanent: Boolean): Boolean = permanent || fails >= 2

    /**
     * Zählt dieser Fehler gegen die Adresse? Nicht bei Zugang/Abo/Verbindungsgrenze/Serverproblem (betrifft alle Titel),
     * nicht bei Decoder-Problemen (das Gerät, nicht der Link) und nicht ohne Internet.
     */
    fun counts(status: Int, decoderProblem: Boolean, online: Boolean): Boolean = when {
        !online || decoderProblem -> false
        status == 401 || status == 403 || status == 429 || status == 458 || status == 509 -> false
        status in 500..599 -> false
        else -> true
    }

    fun permanent(status: Int): Boolean = status == 404 || status == 410

    /** „Fehlversuche|Zeit|dauerhaft“ */
    fun encode(fails: Int, ts: Long, perm: Boolean) = "$fails|$ts|${if (perm) 1 else 0}"

    fun decode(s: String?): Triple<Int, Long, Boolean>? {
        val p = s?.split('|') ?: return null
        if (p.size != 3) return null
        return Triple(p[0].toIntOrNull() ?: return null, p[1].toLongOrNull() ?: 0L, p[2] == "1")
    }
}

/**
 * Plausibilität der Überspring-Zeiten von TheIntroDB (Daten von Nutzern, teils falsch oder unvollständig).
 * Liefert den bereinigten Abschnitt (Start, Ende in ms) oder null, wenn er nicht passt.
 * [start]/[end] = null bedeutet: fehlt in den Daten. [dur] = Länge der Folge (0 = unbekannt).
 */
object SegLogic {
    const val INTRO = 0
    const val RECAP = 1
    const val CREDITS = 2

    fun clean(kind: Int, start: Long?, end: Long?, dur: Long): Pair<Long, Long>? {
        val d = if (dur > 0) dur else Long.MAX_VALUE / 4
        when (kind) {
            INTRO, RECAP -> {
                // Intro/Rückblick brauchen ein Ende (sonst reichte der Knopf bis zum Ende der Folge)
                val e = end ?: return null
                val s = start ?: 0L
                val len = e - s
                if (len < 5_000 || len > (if (kind == INTRO) 180_000 else 240_000)) return null
                if (e > d + 5_000) return null
                // Intro höchstens in der ersten Hälfte, Rückblick im ersten Drittel der Folge
                if (dur > 0 && s > dur * (if (kind == INTRO) 0.5 else 0.35)) return null
                return s to minOf(e, d)
            }
            CREDITS -> {
                val s = start ?: return null
                val e = end ?: d
                if (e - s < 5_000) return null
                // Abspann nur im letzten Viertel der Folge
                if (dur > 0 && (s < dur * 0.75 || s >= dur)) return null
                return s to minOf(e, d)
            }
        }
        return null
    }
}
