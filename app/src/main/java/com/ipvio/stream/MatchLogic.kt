package com.ipvio.stream

/**
 * Abgleich eines Playlist-Titels mit TMDB-Treffern (ohne Android, testbar).
 *
 * Früher wurde immer der erste Treffer genommen. TMDB sortiert aber auch nach Beliebtheit: Bei „Game of Thrones“ kann so ein
 * beliebter Ableger mit einer einzigen Staffel vorne stehen – dann zeigt die Serie falsche Bilder und ab Staffel 2 gar nichts.
 * Jetzt gewinnt ein Treffer mit genau gleichem Namen; gibt es keinen, bleibt es beim ersten Treffer von TMDB.
 */
object MatchLogic {
    /** Ein Suchtreffer von TMDB (Serie oder Film). */
    data class Candidate(val id: Int, val name: String?, val original: String?, val year: String?, val votes: Int)

    private val MARKS = Regex("""\p{Mn}+""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    /** Vergleichsform: klein, ohne Akzente und Satzzeichen, „&“ = „and“. */
    fun norm(t: String?): String {
        if (t.isNullOrBlank()) return ""
        val s = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
            .replace("&", " and ").replace("ß", "ss")
        return s.replace(NON_ALNUM, " ").trim()
    }

    /** Gleicher Titel (Name oder Originalname)? */
    fun sameTitle(title: String, name: String?, original: String? = null): Boolean {
        val n = norm(searchTitle(title))
        return n.isNotEmpty() && (n == norm(name) || n == norm(original))
    }

    // Sprach-/Länderkennung am Anfang: "DE - ", "DE: ", "|DE| ", "[DE] ", "(GER) ", "DE | "
    private val LEAD = Regex("""^\s*[\[(|]?\s*([A-Za-z]{2,4})\s*(?:[\])|]\s*[-–|:]?|\s*[-–|:])\s*""")

    /**
     * Titel für die TMDB-Suche: ohne Sprachkennung des Anbieters am Anfang (auch „DE - “, das im angezeigten Titel bleibt).
     * „DE - Game of Thrones“ fand bei TMDB sonst andere Serien.
     */
    fun searchTitle(title: String): String {
        var t = title.trim()
        repeat(2) {
            val m = LEAD.find(t) ?: return@repeat
            if (LangTagLogic.detect(m.groupValues[1]) == null) return@repeat
            val rest = t.substring(m.range.last + 1).trim()
            if (rest.isNotEmpty()) t = rest
        }
        return t
    }

    /**
     * Bester Treffer: genau gleicher Name (bei Filmen mit passendem Jahr bevorzugt), unter mehreren gleichen der mit den meisten
     * Stimmen. Ohne gleichnamigen Treffer der erste Treffer von TMDB (wie bisher).
     */
    fun pick(title: String, year: Int?, results: List<Candidate>): Candidate? {
        if (results.isEmpty()) return null
        val exact = results.filter { sameTitle(title, it.name, it.original) }
        if (exact.isEmpty()) return results.first()
        val y = year?.toString()
        val sameYear = if (y != null) exact.filter { it.year == y } else emptyList()
        return (sameYear.ifEmpty { exact }).maxByOrNull { it.votes }
    }

    /**
     * Passt ein gespeicherter Treffer noch? Nein, wenn die Playlist mehr Staffeln hat als die Serie bei TMDB
     * (typisch für einen falschen Treffer), oder wenn weder Name noch Originalname zum Titel passen und es einen besseren gibt.
     */
    fun seasonsFit(playlistMaxSeason: Int, tmdbSeasons: Int): Boolean =
        playlistMaxSeason <= 0 || tmdbSeasons <= 0 || playlistMaxSeason <= tmdbSeasons
}
