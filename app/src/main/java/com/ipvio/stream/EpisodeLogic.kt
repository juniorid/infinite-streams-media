package com.ipvio.stream

/**
 * Lange Staffeln (z. B. One Piece: über 1.000 Folgen, vom Anbieter alle in „Staffel 1“) in Abschnitte teilen,
 * damit niemand bis Folge 1000 scrollen muss. Kennt TMDB die echten Staffeln, werden sie als Abschnitte genommen
 * (mit Namen wie „Alabasta“) und die Folgen-Infos kommen aus der richtigen TMDB-Staffel. Sonst gleich große Blöcke mit höchstens 30 Folgen.
 * Ohne Android, testbar.
 */
object EpisodeLogic {
    /** Ab so vielen Folgen in einer Staffel wird aufgeteilt. */
    const val LONG = 60
    /** Höchstgröße eines Abschnitts, wenn TMDB nicht hilft (gleichmäßig verteilt: 62 Folgen = 21 + 21 + 20). */
    const val BLOCK = 30
    /** Größere TMDB-Staffeln werden zusätzlich in Blöcke geteilt. */
    const val MAX_GROUP = 100

    /** Eine Staffel laut TMDB: Nummer, Zahl der Folgen, Name (oder null). Staffel 0 = Specials, wird nicht genutzt. */
    data class TmdbSeason(val num: Int, val count: Int, val name: String?)

    /**
     * Abschnitt einer Staffel der Playlist: Folgen [from]..[to] (Index in der Folgenliste der Staffel, einschließlich).
     * Infos kommen aus TMDB-Staffel [tmdbSeason] (-1 = keine, z. B. neuer als TMDB), TMDB-Folgennummer = Nummer − [offset].
     */
    data class Group(
        val from: Int, val to: Int, val firstNum: Int, val lastNum: Int,
        val tmdbSeason: Int, val offset: Int, val name: String?
    ) {
        val size: Int get() = to - from + 1
        operator fun contains(index: Int) = index in from..to
    }

    private val GENERIC = Regex("""^\s*(season|staffel|temporada|saison|sezon|sezona|stagione|serie)\s*\d+\s*$""", RegexOption.IGNORE_CASE)

    /** TMDB-Name nur, wenn er mehr sagt als „Season 3“. */
    fun niceName(n: String?): String? = n?.trim()?.takeIf { it.isNotEmpty() && !GENERIC.matches(it) }

    private fun real(tmdb: List<TmdbSeason>) = tmdb.filter { it.num >= 1 && it.count > 0 }.sortedBy { it.num }

    /** Erste (fortlaufende) Folgennummer jeder TMDB-Staffel: Staffel 1 → 1, Staffel 2 → 1 + Folgen von Staffel 1 … */
    fun starts(tmdb: List<TmdbSeason>): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        var n = 1
        for (s in real(tmdb)) { out[s.num] = n; n += s.count }
        return out
    }

    /**
     * Zählt der Anbieter fortlaufend (alles in einer Staffel, Nummern weit über Staffel 1 von TMDB hinaus)?
     * Nur dann werden die Folgen auf die TMDB-Staffeln verteilt.
     */
    fun absolute(eps: List<Episode>, providerSeasons: Int, tmdb: List<TmdbSeason>): Boolean {
        val r = real(tmdb)
        if (providerSeasons != 1 || r.size < 2 || eps.isEmpty()) return false
        val first = r.first().count
        return eps.maxOf { it.num } > first + 2
    }

    /**
     * Abschnitte einer Staffel. [eps] = Folgen dieser Staffel, nach Nummer sortiert.
     * Kurze Staffeln: ein Abschnitt (die Liste sieht aus wie bisher).
     */
    fun groups(eps: List<Episode>, season: Int, providerSeasons: Int, tmdb: List<TmdbSeason>): List<Group> {
        if (eps.isEmpty()) return emptyList()
        val st = starts(tmdb)
        if (absolute(eps, providerSeasons, tmdb)) {
            val r = real(tmdb)
            val out = ArrayList<Group>()
            // TMDB-Staffel je Folge (null = neuer als TMDB weiß), gleiche hintereinander werden ein Abschnitt
            fun seasonOf(n: Int): TmdbSeason? = r.firstOrNull { s -> val a = st.getValue(s.num); n >= a && n < a + s.count }
            var i = 0
            while (i < eps.size) {
                val s = seasonOf(eps[i].num)
                var j = i
                while (j + 1 < eps.size && seasonOf(eps[j + 1].num)?.num == s?.num) j++
                val off = if (s != null) st.getValue(s.num) - 1 else 0
                out.addAll(split(eps, i, j, s?.num ?: -1, off, niceName(s?.name)))
                i = j + 1
            }
            return out
        }
        // Anbieter mit eigenen Staffeln, aber fortlaufender Nummer (Staffel 2 beginnt mit Folge 62)
        val offset = st[season]?.let { a ->
            val cnt = real(tmdb).first { it.num == season }.count
            if (a > 1 && eps.first().num == a && eps.last().num <= a + cnt + 1) a - 1 else 0
        } ?: 0
        if (eps.size <= LONG) return listOf(Group(0, eps.lastIndex, eps.first().num, eps.last().num, season, offset, null))
        return split(eps, 0, eps.lastIndex, season, offset, null, BLOCK)
    }

    private fun split(eps: List<Episode>, from: Int, to: Int, tmdbSeason: Int, offset: Int, name: String?, size: Int = MAX_GROUP): List<Group> {
        val n = to - from + 1
        if (n <= size) return listOf(Group(from, to, eps[from].num, eps[to].num, tmdbSeason, offset, name))
        val out = ArrayList<Group>()
        // Gleichmäßig aufteilen statt 50 + Rest: kürzere Wege zur gesuchten Folge, kein winziger letzter Abschnitt
        val count = (n + BLOCK - 1) / BLOCK
        val per = (n + count - 1) / count
        var a = from
        while (a <= to) {
            val b = minOf(a + per - 1, to)
            out.add(Group(a, b, eps[a].num, eps[b].num, tmdbSeason, offset, name))
            a = b + 1
        }
        return out
    }

    /** Index des Abschnitts, der die Folge an Position [index] enthält (sonst 0). */
    fun groupOf(groups: List<Group>, index: Int): Int = groups.indexOfFirst { index in it }.coerceAtLeast(0)

    /** Position der Folge mit Nummer [num]; gibt es sie nicht, die nächstgelegene. -1 bei leerer Liste. */
    fun indexOfNum(eps: List<Episode>, num: Int): Int {
        if (eps.isEmpty()) return -1
        val exact = eps.indexOfFirst { it.num == num }
        if (exact >= 0) return exact
        return eps.indices.minByOrNull { kotlin.math.abs(eps[it].num - num) } ?: 0
    }

    /**
     * Ziel von „Gehe zu Folge [n]“. Gibt es die Nummer in der aktuellen Staffel, gilt sie dort. Sonst in der ersten anderen
     * Staffel mit dieser Nummer. Gibt es sie nirgends (z. B. 100 bei einer Serie mit je 25 Folgen pro Staffel), zählt sie als
     * Gesamtnummer über alle Staffeln (Staffel 1 Folge 1 = 1). Ergebnis: Staffel und Folgennummer, null wenn es so viele Folgen nicht gibt.
     */
    fun locate(all: List<Episode>, currentSeason: Int, n: Int): Pair<Int, Int>? {
        if (n < 1 || all.isEmpty()) return null
        all.firstOrNull { it.season == currentSeason && it.num == n }?.let { return it.season to it.num }
        all.filter { it.num == n }.minByOrNull { it.season }?.let { return it.season to it.num }
        val sorted = all.sortedWith(compareBy({ it.season }, { it.num }))
        return sorted.getOrNull(n - 1)?.let { it.season to it.num }
    }

    /** Beschriftung eines Abschnitts: „1–61“, bei TMDB-Staffel mit Namen „1–61 · East Blue“. */
    fun label(g: Group): String = (if (g.firstNum == g.lastNum) "${g.firstNum}" else "${g.firstNum}–${g.lastNum}") +
        (g.name?.let { " · $it" } ?: "")

    /** Folgennummer, unter der TMDB die Folge in [Group.tmdbSeason] führt. */
    fun tmdbNum(g: Group, num: Int): Int = num - g.offset
}

/**
 * Weiterschauen: wann eine Folge/ein Film als gesehen gilt, und der Fortschritt je Folge in kompakter Textform.
 */
object ResumeLogic {
    /** Gesehen: ab 92 % oder in den letzten 90 Sekunden (Abspann), sofern mindestens die Hälfte gelaufen ist. */
    fun finished(posMs: Long, durMs: Long): Boolean =
        durMs > 0 && (posMs >= durMs * 0.92 || (durMs - posMs <= 90_000 && posMs >= durMs / 2))

    /** Erst ab 30 Sekunden lohnt sich ein Fortsetzen-Punkt. */
    const val MIN_POS = 30_000L

    /**
     * Soll der Stand gemerkt werden? Ab 30 Sekunden oder zu Ende geschaut immer. Kürzer nur, wenn bereits ein Eintrag für
     * dieselbe Serie existiert und dieser eine ANDERE Folge betrifft: Wer von Folge 40 zu 41 und 42 weitergeht, soll beim
     * Wiederkommen bei 42 landen, nicht bei 40. Ein kurzes Reinschauen in dieselbe Folge ändert nichts.
     */
    fun shouldSave(hasOld: Boolean, oldSeason: Int, oldEp: Int, season: Int, ep: Int, posMs: Long, finished: Boolean): Boolean = when {
        finished -> true
        posMs > MIN_POS -> true
        !hasOld -> false
        (season > 0 || ep > 0) && (oldSeason != season || oldEp != ep) -> true
        else -> false
    }

    /** Fortschritt einer Folge in Sekunden. */
    data class EpPos(val pos: Int, val dur: Int) {
        val done: Boolean get() = finished(pos * 1000L, dur * 1000L)
        val fraction: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    fun key(season: Int, ep: Int): Long = (season.toLong() shl 32) or (ep.toLong() and 0xFFFFFFFFL)

    /** „s:e:pos:dur;…“ (Sekunden). */
    fun encode(m: Map<Long, EpPos>): String = m.entries.joinToString(";") { (k, v) ->
        "${(k shr 32).toInt()}:${k.toInt()}:${v.pos}:${v.dur}"
    }

    fun decode(s: String?): LinkedHashMap<Long, EpPos> {
        val out = LinkedHashMap<Long, EpPos>()
        if (s.isNullOrBlank()) return out
        for (part in s.split(';')) {
            val p = part.split(':')
            if (p.size != 4) continue
            val se = p[0].toIntOrNull() ?: continue
            val e = p[1].toIntOrNull() ?: continue
            out[key(se, e)] = EpPos(p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0)
        }
        return out
    }

    /** Staffel und Folge aus „Titel S1E3“ (alte Einträge ohne eigene Felder). */
    private val SE = Regex("""[Ss](\d{1,3})\s*[Ee](\d{1,4})\s*$""")

    fun seasonEp(title: String): Pair<Int, Int>? =
        SE.find(title)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() }
}
