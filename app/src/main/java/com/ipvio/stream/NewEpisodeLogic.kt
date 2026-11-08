package com.ipvio.stream

/**
 * Neue Folgen bei Favoriten-Serien (Runde 15): vergleicht die Zahl der Folgen mit dem letzten bekannten Stand.
 * Ohne Android, testbar. Beim ersten Mal (Serie ist neu in den Favoriten) wird nur der Stand gemerkt, es gibt keine Meldung.
 */
object NewEpisodeLogic {
    /** Eine Serie der Favoriten: Kennung, Titel, Folgen als (Staffel, Nummer). */
    class Show(val id: String, val title: String, val episodes: List<Pair<Int, Int>>)

    /** Meldung: neue Folge(n) einer Serie; [season]/[num] = die jüngste Folge, [added] = wie viele neu sind. */
    data class Notice(val id: String, val title: String, val season: Int, val num: Int, val added: Int)

    class Result(val known: Map<String, Int>, val notices: List<Notice>)

    fun detect(known: Map<String, Int>, shows: List<Show>): Result {
        val out = HashMap<String, Int>()
        val notices = ArrayList<Notice>()
        for (s in shows) {
            val n = s.episodes.size
            val old = known[s.id]
            if (n == 0) { if (old != null) out[s.id] = old; continue }   // noch nicht geladen: Stand behalten
            out[s.id] = n
            if (old != null && n > old) {
                val last = s.episodes.maxWithOrNull(compareBy({ it.first }, { it.second }))!!
                notices.add(Notice(s.id, s.title, last.first, last.second, n - old))
            }
        }
        // Serien, die nicht mehr in den Favoriten sind, vergessen
        return Result(out, notices)
    }

    /** Text für die Glocke, z. B. „Neue Folge · Staffel 3, Folge 5“ oder „3 neue Folgen · bis Staffel 3, Folge 8“. */
    fun text(n: Notice): String =
        if (n.added <= 1) "Neue Folge · Staffel ${n.season}, Folge ${n.num}" else "${n.added} neue Folgen · bis Staffel ${n.season}, Folge ${n.num}"
}
