package com.ipvio.stream

/** Suchindex: Titel einmal klein geschrieben und ohne Akzente/Sonderzeichen, damit „Amelie“ auch „Amélie“ findet. */
class SearchIndex(val all: List<Item>) {
    val keys: Array<String> = Array(all.size) { norm(all[it].title) }
    val byId: Map<String, Item> = all.associateBy { it.id }

    fun find(q: String): Triple<List<Item>, List<Item>, List<Item>> {
        val n = norm(q)
        val m = ArrayList<Item>(); val s = ArrayList<Item>(); val l = ArrayList<Item>()
        if (n.isEmpty()) return Triple(m, s, l)
        for (i in all.indices) if (keys[i].contains(n)) {
            val it = all[i]
            when (it.kind) { Kind.MOVIE -> m.add(it); Kind.SERIES -> s.add(it); Kind.LIVE -> l.add(it) }
        }
        // Wenige Treffer: Tippfehler verzeihen (Filme und Serien, Sender nicht)
        if (m.size + s.size < FUZZY_BELOW && n.length >= 4) {
            val k = maxErrors(n.length)
            val hits = ArrayList<Pair<Int, Int>>()
            for (i in all.indices) {
                val kind = all[i].kind
                if (kind == Kind.LIVE) continue
                val key = keys[i]
                if (key.length < n.length - k || key.contains(n)) continue
                val d = approxDist(n, key)
                if (d <= k) hits.add(d to i)
            }
            hits.sortedWith(compareBy({ it.first }, { keys[it.second].length })).take(FUZZY_MAX).forEach { (_, i) ->
                val it = all[i]
                if (it.kind == Kind.MOVIE) m.add(it) else s.add(it)
            }
        }
        return Triple(m, s, l)
    }

    companion object {
        const val FUZZY_BELOW = 3
        const val FUZZY_MAX = 40

        /** Erlaubte Tippfehler je Suchlänge: 4–5 Zeichen = 1, ab 6 = 2. */
        fun maxErrors(len: Int): Int = if (len >= 6) 2 else 1

        /** Kleinster Abstand (Einfügen/Löschen/Ersetzen) zwischen [p] und irgendeiner Stelle in [t] (Sellers-Verfahren). */
        fun approxDist(p: String, t: String): Int {
            val m = p.length
            if (m == 0) return 0
            val col = IntArray(m + 1) { it }
            var best = m
            for (c in t) {
                var diag = col[0]
                col[0] = 0
                for (i in 1..m) {
                    val up = col[i]
                    col[i] = minOf(col[i] + 1, col[i - 1] + 1, diag + (if (p[i - 1] == c) 0 else 1))
                    diag = up
                }
                if (col[m] < best) best = col[m]
            }
            return best
        }

        private val MARKS = Regex("""\p{Mn}+""")
        fun norm(t: String): String =
            java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "").replace("ß", "ss")
    }
}
