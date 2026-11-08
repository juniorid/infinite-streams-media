package com.ipvio.stream

/** Schlüssel der Favoriten in der Kategorie-Auswahl (Live TV und Player). */
const val FAV_CAT = "#FAV"

/** Eigene Senderliste: Name und Sender in eigener Reihenfolge (Sender-IDs wie bei den Favoriten). */
data class ChList(val id: Int, val name: String, val ids: List<String>)

/** Eigene Senderlisten anlegen, füllen, sortieren (ohne Android, testbar). */
object ChannelListLogic {
    const val MAX_NAME = 40
    /** Schlüssel einer Liste in der Kategorie-Auswahl (neben Favoriten und „ALL“). */
    const val PREFIX = "L:"

    fun key(l: ChList) = PREFIX + l.id
    fun idOfKey(k: String): Int? = if (k.startsWith(PREFIX)) k.removePrefix(PREFIX).toIntOrNull() else null

    fun cleanName(n: String): String = n.replace(Regex("[\\t\\n\\r]"), " ").trim().take(MAX_NAME)

    /** Neue Liste; leerer Name → „Senderliste N“. */
    fun create(lists: List<ChList>, name: String, first: String? = null): List<ChList> {
        val id = (lists.maxOfOrNull { it.id } ?: 0) + 1
        val n = cleanName(name).ifEmpty { "Senderliste $id" }
        return lists + ChList(id, n, listOfNotNull(first))
    }

    fun rename(lists: List<ChList>, id: Int, name: String): List<ChList> {
        val n = cleanName(name)
        if (n.isEmpty()) return lists
        return lists.map { if (it.id == id) it.copy(name = n) else it }
    }

    fun delete(lists: List<ChList>, id: Int): List<ChList> = lists.filter { it.id != id }

    /** Sender hinten anhängen (kein Doppel). */
    fun add(lists: List<ChList>, id: Int, ch: String): List<ChList> =
        lists.map { if (it.id == id && ch !in it.ids) it.copy(ids = it.ids + ch) else it }

    fun remove(lists: List<ChList>, id: Int, ch: String): List<ChList> =
        lists.map { if (it.id == id) it.copy(ids = it.ids - ch) else it }

    /** Sender um [delta] Plätze verschieben (−1 = nach oben). */
    fun move(lists: List<ChList>, id: Int, ch: String, delta: Int): List<ChList> = lists.map { l ->
        if (l.id != id) l else {
            val i = l.ids.indexOf(ch)
            val j = i + delta
            if (i < 0 || j !in l.ids.indices) l else l.copy(ids = l.ids.toMutableList().also { m -> m.removeAt(i); m.add(j, ch) })
        }
    }

    /** Sender der Liste in ihrer Reihenfolge; nicht (mehr) vorhandene werden übersprungen. */
    fun <T> resolve(l: ChList, byId: Map<String, T>): List<T> = l.ids.mapNotNull { byId[it] }

    /** Textform: je Liste eine Zeile „id TAB name TAB sender,sender,…“. */
    fun encode(lists: List<ChList>): String = lists.joinToString("\n") { "${it.id}\t${cleanName(it.name)}\t${it.ids.joinToString(",")}" }

    fun decode(s: String?): List<ChList> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 3) return@mapNotNull null
            val id = p[0].toIntOrNull() ?: return@mapNotNull null
            ChList(id, p[1], p[2].split(',').filter { it.isNotBlank() })
        }
    }
}

/** Erinnerung an eine Sendung (Zeiten in Sekunden seit 1970). */
data class Reminder(val chId: String, val chTitle: String, val title: String, val start: Long, val end: Long) {
    val key: String get() = chId + "@" + start
}

/** Wann an welche Sendung erinnert wird (ohne Android, testbar). */
object ReminderLogic {
    /** Vorlaufzeiten zur Auswahl in Minuten. */
    val LEADS = listOf(0, 2, 5, 10, 15)

    /** Fällig: Vorlauf erreicht und die Sendung ist noch nicht zu Ende. */
    fun due(list: List<Reminder>, nowSec: Long, leadSec: Long): List<Reminder> =
        list.filter { nowSec >= it.start - leadSec && nowSec < it.end }

    /** Alte Erinnerungen (Sendung vorbei) fallen weg. */
    fun prune(list: List<Reminder>, nowSec: Long): List<Reminder> = list.filter { it.end > nowSec }

    /** Nächster Weckzeitpunkt (Sekunden) oder null. */
    fun nextAlarm(list: List<Reminder>, nowSec: Long, leadSec: Long): Long? =
        list.filter { it.end > nowSec }.minOfOrNull { maxOf(it.start - leadSec, nowSec) }

    fun toggle(list: List<Reminder>, r: Reminder): List<Reminder> =
        if (list.any { it.key == r.key }) list.filter { it.key != r.key } else (list + r).sortedBy { it.start }

    /** Kann man an diese Sendung erinnern? Nur wenn sie noch nicht begonnen hat. */
    fun canRemind(start: Long, nowSec: Long): Boolean = start > nowSec

    private fun esc(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    fun encode(list: List<Reminder>): String =
        list.joinToString("\n") { "${esc(it.chId)}\t${esc(it.chTitle)}\t${esc(it.title)}\t${it.start}\t${it.end}" }

    fun decode(s: String?): List<Reminder> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 5) return@mapNotNull null
            Reminder(p[0], p[1], p[2], p[3].toLongOrNull() ?: return@mapNotNull null, p[4].toLongOrNull() ?: return@mapNotNull null)
        }
    }
}
