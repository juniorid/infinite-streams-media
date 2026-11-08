package com.ipvio.stream

/**
 * Erkennt die Sprache eines Eintrags aus den Kennzeichnungen des Anbieters (ohne Android, testbar).
 *
 * IPTV-Anbieter markieren die Tonspur fast immer in der Kategorie oder im Namen, z. B. "DE | Action",
 * "|TR| Filmler", "VOD - EN - Comedy", "Netflix [GER]", "TÜRK FILMLERI" oder "DE: Das Erste HD".
 * Das ist die Sprache der Tonspur (deutsch synchronisiert), nicht das Herkunftsland des Films.
 * Die echte Tonspur lässt sich erst beim Abspielen lesen – für 100.000+ Titel ist die Kennzeichnung der einzige Weg.
 */
object LangTagLogic {
    const val UNKNOWN = "?"

    /** Kürzel → Sprache. Mehrdeutige Kürzel (IN, AL, NO, CA, SE, BE, MY) bewusst weggelassen: "beIN Sports", "Al Jazeera" … */
    private val CODES = mapOf(
        "DE" to "de", "GER" to "de", "DEU" to "de", "AT" to "de", "CH" to "de",
        "EN" to "en", "ENG" to "en", "UK" to "en", "GB" to "en", "US" to "en", "USA" to "en",
        "TR" to "tr", "TUR" to "tr",
        "AR" to "ar", "ARA" to "ar", "ARB" to "ar",
        "FR" to "fr", "FRA" to "fr", "FRE" to "fr",
        "ES" to "es", "ESP" to "es", "SPA" to "es", "LAT" to "es", "MX" to "es",
        "IT" to "it", "ITA" to "it",
        "NL" to "nl", "NED" to "nl", "DUT" to "nl",
        "PL" to "pl", "POL" to "pl",
        "PT" to "pt", "POR" to "pt", "BR" to "pt",
        "RU" to "ru", "RUS" to "ru",
        "HIN" to "hi",
        "JP" to "ja", "JPN" to "ja", "JAP" to "ja",
        "KR" to "ko", "KOR" to "ko",
        "CN" to "zh", "CHN" to "zh", "ZH" to "zh",
        "ALB" to "sq",
        "EXYU" to "yu", "YU" to "yu", "HR" to "yu", "RS" to "yu", "SRB" to "yu", "BA" to "yu", "BIH" to "yu", "SI" to "yu", "MK" to "yu",
        "GR" to "el", "GRE" to "el",
        "RO" to "ro", "ROM" to "ro",
        "HU" to "hu", "HUN" to "hu",
        "CZ" to "cs", "SK" to "cs",
        "SWE" to "sv", "DK" to "da", "DAN" to "da", "NOR" to "nb", "FI" to "fi", "FIN" to "fi",
        "IR" to "fa", "PER" to "fa",
        "KURD" to "ku",
        "IL" to "he", "HEB" to "he",
        "UA" to "uk", "UKR" to "uk",
        "BG" to "bg", "BUL" to "bg",
        "AFG" to "fa", "PK" to "ur", "URD" to "ur",
        "VN" to "vi", "TH" to "th", "PH" to "tl"
    )

    /** Ganze Wörter → Sprache. */
    private val WORDS = mapOf(
        "GERMAN" to "de", "GERMANY" to "de", "DEUTSCH" to "de", "DEUTSCHE" to "de", "DEUTSCHLAND" to "de", "ALLEMAGNE" to "de",
        "ENGLISH" to "en",
        "TURK" to "tr", "TURKISH" to "tr", "TURKIYE" to "tr", "TÜRK" to "tr", "TÜRKIYE" to "tr", "TÜRKİYE" to "tr", "TÜRKÇE" to "tr",
        "ARABIC" to "ar", "ARAB" to "ar",
        "FRANCE" to "fr", "FRENCH" to "fr", "FRANÇAIS" to "fr", "FRANCAIS" to "fr",
        "SPAIN" to "es", "SPANISH" to "es", "ESPAÑA" to "es", "ESPANA" to "es", "LATINO" to "es", "ESPAÑOL" to "es",
        "ITALIA" to "it", "ITALY" to "it", "ITALIAN" to "it", "ITALIANO" to "it",
        "POLSKA" to "pl", "POLAND" to "pl", "POLISH" to "pl",
        "PORTUGAL" to "pt", "BRASIL" to "pt", "BRAZIL" to "pt",
        "RUSSIA" to "ru", "RUSSIAN" to "ru",
        "INDIA" to "hi", "HINDI" to "hi", "INDIAN" to "hi",
        "JAPAN" to "ja", "JAPANESE" to "ja",
        "KOREA" to "ko", "KOREAN" to "ko",
        "CHINA" to "zh", "CHINESE" to "zh",
        "NETHERLANDS" to "nl", "NEDERLAND" to "nl", "DUTCH" to "nl",
        "ALBANIA" to "sq", "SHQIP" to "sq", "SHQIPTARE" to "sq",
        "BALKAN" to "yu",
        "GREECE" to "el", "GREEK" to "el",
        "ROMANIA" to "ro", "ROMANIAN" to "ro",
        "KURDISH" to "ku", "KURDI" to "ku", "KURDISTAN" to "ku",
        "PERSIAN" to "fa", "IRAN" to "fa", "AFGHAN" to "fa",
        "UKRAINE" to "uk", "BULGARIA" to "bg", "HUNGARY" to "hu",
        "SWEDEN" to "sv", "DENMARK" to "da", "NORWAY" to "nb", "FINLAND" to "fi",
        "PAKISTAN" to "ur", "VIETNAM" to "vi", "THAILAND" to "th", "PHILIPPINES" to "tl"
    )

    /** Anzeige-Namen in der jeweiligen Sprache selbst, so erkennt jeder seine Sprache sofort. */
    val NAMES = linkedMapOf(
        "de" to "Deutsch", "en" to "English", "tr" to "Türkçe", "ar" to "العربية", "fr" to "Français",
        "es" to "Español", "it" to "Italiano", "nl" to "Nederlands", "pl" to "Polski", "pt" to "Português",
        "ru" to "Русский", "hi" to "हिन्दी", "ja" to "日本語", "ko" to "한국어", "zh" to "中文", "sq" to "Shqip",
        "yu" to "Ex-YU / Balkan", "el" to "Ελληνικά", "ro" to "Română", "hu" to "Magyar", "cs" to "Čeština",
        "sv" to "Svenska", "da" to "Dansk", "nb" to "Norsk", "fi" to "Suomi", "fa" to "فارسی", "ku" to "Kurdî",
        "he" to "עברית", "uk" to "Українська", "bg" to "Български", "ur" to "اردو", "vi" to "Tiếng Việt",
        "th" to "ไทย", "tl" to "Filipino"
    )

    fun name(code: String): String = NAMES[code] ?: code.uppercase()

    private val SPLIT = Regex("""[^\p{L}0-9]+""")

    /** Sprache aus einem Text oder null. Prüft die ersten zwei Wörter, das letzte Wort und bekannte Sprachwörter. */
    fun detect(text: String): String? {
        if (text.isBlank()) return null
        val t = text.uppercase().split(SPLIT).filter { it.isNotEmpty() }
        if (t.isEmpty()) return null
        for (w in t.take(2)) CODES[w]?.let { return it }
        if (t.size > 1) CODES[t.last()]?.let { return it }
        for (w in t) WORDS[w]?.let { return it }
        return null
    }

    // Kennung am Anfang ("DE | Titel", "[DE] Titel", "DE: Titel", "DE - Titel") oder am Ende ("Titel [DE]", "Titel (GER)")
    private val TAG_START = Regex("""^\W*([A-Za-z]{2,4})\s*([|:\]]|[-–]\s)""")
    private val TAG_END = Regex("""[\[(|]\s*([A-Za-z]{2,4})\s*[\])|]?\s*$""")
    /** Kürzel, die auch englische Wörter sind ("IT – Welcome to Derry", "Us - …"): nur mit | : oder Klammer gültig. */
    private val WORDLIKE = setOf("IT", "US", "AT", "UK", "DE", "ES")

    private val DASH_TAG = Regex("""^\s*([A-Za-z]{2,4})\s*[-–]\s+""")

    /** Entfernt eine Sprachkennung mit Bindestrich am Anfang ("EN - Titel" → "Titel"), damit Titel und TMDB-Suche sauber sind. */
    fun stripDashTag(name: String): String {
        val m = DASH_TAG.find(name) ?: return name
        val code = m.groupValues[1].uppercase()
        return if (code in CODES && code !in WORDLIKE) name.substring(m.range.last + 1) else name
    }

    /** Sprache aus einer ausdrücklichen Kennung im (unbereinigten) Film- oder Serientitel, sonst null. */
    fun detectTagged(name: String): String? {
        TAG_START.find(name)?.let { m ->
            val code = m.groupValues[1].uppercase()
            val dash = m.groupValues[2].trim().let { it == "-" || it == "–" }
            if (!(dash && code in WORDLIKE)) CODES[code]?.let { return it }
        }
        TAG_END.find(name)?.let { m -> CODES[m.groupValues[1].uppercase()]?.let { return it } }
        return null
    }

    /**
     * Sprache eines Eintrags: zuerst aus der Kategorie (zuverlässig), bei Live-Sendern sonst aus dem Sendernamen,
     * bei Filmen und Serien nur aus einer ausdrücklichen Kennung im Originalnamen der Playlist ("DE | …", "… [GER]").
     */
    fun of(group: String, title: String, live: Boolean): String =
        detect(group) ?: (if (live) detect(title) else detectTagged(title)) ?: UNKNOWN

    /** Verborgene Sprachen als Text speichern und wieder lesen. */
    fun encode(hidden: Set<String>): String = hidden.sorted().joinToString(",")
    fun decode(s: String?): Set<String> = (s ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
