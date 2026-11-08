package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.mutableStateListOf

/**
 * Neue Folgen bei Favoriten-Serien melden (Glocke oben rechts). Der Stand je Serie wird auf dem Gerät gemerkt;
 * geprüft wird, wenn die Playlist geladen ist. Öffnet man die Serie, ist die Meldung erledigt.
 */
object NewEpisodes {
    val list = mutableStateListOf<NewEpisodeLogic.Notice>()
    private val known = HashMap<String, Int>()
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_newep")

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        (p.getString("known", "") ?: "").split("\n").forEach { l ->
            val t = l.split("\t"); if (t.size == 2) t[1].toIntOrNull()?.let { known[t[0]] = it }
        }
        (p.getString("list", "") ?: "").split("\n").forEach { l ->
            val t = l.split("\t")
            if (t.size == 5) list.add(NewEpisodeLogic.Notice(t[0], t[1], t[2].toIntOrNull() ?: 0, t[3].toIntOrNull() ?: 0, t[4].toIntOrNull() ?: 1))
        }
    }

    private fun save(c: Context) {
        prefs(c).edit()
            .putString("known", known.entries.joinToString("\n") { it.key + "\t" + it.value })
            .putString("list", list.joinToString("\n") { listOf(it.id, it.title.replace('\t', ' ').replace('\n', ' '), it.season, it.num, it.added).joinToString("\t") })
            .apply()
    }

    /** Favoriten-Serien prüfen (nach dem Laden der Playlist und wenn sich die Favoriten ändern). */
    fun check(c: Context, favSeries0: List<Item>) {
        init(c)
        // Serien mit Platzhalter kennen ihre Folgen noch nicht: kein Vergleich (sonst falsche Meldungen)
        val favSeries = favSeries0.filter { !XtreamLogic.isLazy(it) }
        if (favSeries.isEmpty()) return
        val r = NewEpisodeLogic.detect(known, favSeries.map { s -> NewEpisodeLogic.Show(s.id, s.title, s.episodes.map { it.season to it.num }) })
        val ids = favSeries.map { it.id }.toSet()
        known.putAll(r.known)
        for (n in r.notices) {
            val old = list.firstOrNull { it.id == n.id }
            if (old != null) list.remove(old)
            list.add(0, if (old != null) n.copy(added = n.added + old.added) else n)
        }
        // Meldungen zu Serien, die keine Favoriten mehr sind, entfernen
        list.removeAll { it.id !in ids }
        save(c)
    }

    /** Serie geöffnet: Meldung erledigt. */
    fun dismiss(c: Context, id: String) {
        init(c)
        if (list.removeAll { it.id == id }) save(c)
    }
}
