package com.ipvio.stream

/**
 * Mehrere Playlists gleichzeitig (Runde 20). Ohne Android, testbar.
 *
 * - Sender: alle bleiben (jede Quelle hat eigene Adressen); im Live-TV lässt sich nach Quelle filtern.
 * - Filme: gleicher Titel + Jahr aus einer späteren Quelle wird in „Alle“ ausgeblendet ([Merged.shadow]),
 *   ist aber über den Quellen-Filter weiter erreichbar.
 * - Serien: gleiche Serie aus mehreren Quellen wird zu EINER Serie; dieselbe Folge aus der zweiten Quelle wird eine
 *   weitere Quelle der Folge (wie mehrere Qualitäten) mit dem Playlist-Namen in der Beschriftung. Die Serien-ID bleibt,
 *   damit Weiterschauen, Meine Liste und Verlauf erhalten bleiben.
 * - Gleiche ID zweimal (z. B. dieselbe Playlist doppelt): nur der erste Eintrag zählt.
 */
object MultiLogic {
    class Merged(
        val items: List<Item>,
        /** IDs, die in „Alle“ nicht gezeigt werden (doppelte Filme späterer Quellen). */
        val shadow: Set<String>,
        /** Serien aus mehreren Quellen: ID → Bitmaske der Quellen (Bit k = Quelle k). */
        val seriesSrc: Map<String, Int>
    ) {
        /** Alles, was „Alle“ zeigt. */
        fun visible(): List<Item> = if (shadow.isEmpty()) items else items.filter { it.id !in shadow }

        /** Gehört der Titel zur Quelle [k]? */
        fun inSource(i: Item, k: Int): Boolean {
            if (i.src == k) return true
            val m = seriesSrc[i.id] ?: return false
            return k in 0..30 && (m shr k) and 1 == 1
        }
    }

    fun merge(lists: List<List<Item>>, names: List<String>, pool: StringPool? = null): Merged {
        if (lists.size <= 1) return Merged(lists.firstOrNull() ?: emptyList(), emptySet(), emptyMap())
        val out = ArrayList<Item>(lists.sumOf { it.size })
        val ids = HashSet<String>(out.size * 2)
        val seriesAt = HashMap<String, Int>()
        val movieKeys = HashSet<String>()
        val shadow = HashSet<String>()
        val seriesSrc = HashMap<String, Int>()
        for ((k, l) in lists.withIndex()) {
            val name = names.getOrElse(k) { "Playlist ${k + 1}" }
            val kMovies = HashSet<String>()
            for (it0 in l) {
                when (it0.kind) {
                    Kind.SERIES -> {
                        val at = seriesAt[it0.id]
                        if (at == null) {
                            if (!ids.add(it0.id)) continue
                            seriesAt[it0.id] = out.size
                            out.add(if (k == 0) it0 else it0.copy(src = k))
                        } else {
                            val a = out[at]
                            // Platzhalter (Folgen erst beim Öffnen) nie als „Folge“ zu echten Folgen mischen: dann gilt die Quelle mit echten Folgen
                            val la = XtreamLogic.isLazy(a)
                            val lb = XtreamLogic.isLazy(it0)
                            out[at] = a.copy(episodes = when {
                                la && !lb -> it0.episodes
                                !la && lb -> a.episodes
                                else -> mergeEpisodes(a.episodes, it0.episodes, name, pool)
                            })
                            seriesSrc[a.id] = (seriesSrc[a.id] ?: (1 shl a.src.coerceIn(0, 30))) or (1 shl k.coerceIn(0, 30))
                        }
                    }
                    else -> {
                        if (!ids.add(it0.id)) continue
                        val it = if (k == 0) it0 else it0.copy(src = k)
                        if (it.kind == Kind.MOVIE) {
                            // Doppelt innerhalb derselben Quelle bleibt wie bisher sichtbar; nur Doppelte späterer Quellen
                            if (k > 0 && it.mk in movieKeys) shadow.add(it.id)
                            kMovies.add(it.mk)
                        }
                        out.add(it)
                    }
                }
            }
            movieKeys.addAll(kMovies)
        }
        out.trimToSize()
        return Merged(out, shadow, seriesSrc)
    }

    /** Folgen zweier Quellen zusammenführen: gleiche Staffel/Folge → eine Folge mit mehreren Quellen. */
    fun mergeEpisodes(a: List<Episode>, b: List<Episode>, nameB: String, pool: StringPool? = null): List<Episode> {
        if (b.isEmpty()) return a
        val byKey = LinkedHashMap<Long, MutableList<Variant>>()
        val first = HashMap<Long, Episode>()
        fun keyOf(e: Episode) = (e.season.toLong() shl 32) or (e.num.toLong() and 0xFFFFFFFFL)
        for (e in a) {
            val key = keyOf(e)
            first.putIfAbsent(key, e)
            byKey.getOrPut(key) { ArrayList() }.addAll(e.variants.ifEmpty { listOf(Variant(e.url, "", e.quality, null)) })
        }
        for (e in b) {
            val key = keyOf(e)
            first.putIfAbsent(key, e)
            val vs = e.variants.ifEmpty { listOf(Variant(e.url, "", e.quality, null)) }
            byKey.getOrPut(key) { ArrayList() }.addAll(vs.map { v ->
                val l = if (v.label.isBlank()) nameB else "$nameB · ${v.label}"
                Variant(v.url, pool?.of(l) ?: l, v.quality, v.lang)
            })
        }
        val out = ArrayList<Episode>(byKey.size)
        for ((key, vs) in byKey) {
            val e = first[key]!!
            if (vs.size == 1) out.add(Episode(e.season, e.num, e.title, vs[0].url, emptyList(), vs[0].quality))
            else out.add(Episode(e.season, e.num, e.title, vs[0].url, vs, vs[0].quality))
        }
        out.sortWith(compareBy({ it.season }, { it.num }))
        return EpisodeTable.of(out, pool)
    }

    /** Kategorie-Reihenfolge: zuerst die der Haupt-Playlist, dann neue Namen der weiteren in ihrer Reihenfolge. */
    fun mergeOrder(orders: List<Map<String, Int>>): Map<String, Int> {
        if (orders.size <= 1) return orders.firstOrNull() ?: emptyMap()
        val out = LinkedHashMap<String, Int>()
        var base = 0
        for (o in orders) {
            for ((k, v) in o.entries.sortedBy { it.value }) if (!out.containsKey(k)) out[k] = base + v
            base += (o.values.maxOrNull() ?: 0) + 1
        }
        return out
    }

    /** Gespeicherte Auswahl „gleichzeitig zeigen“ als Schlüssel (Hash der Adresse – die Adresse selbst enthält Zugangsdaten). */
    fun encodeKeys(keys: Collection<Int>): String = keys.sorted().joinToString(",")
    fun decodeKeys(s: String?): Set<Int> = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
}
