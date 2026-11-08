package com.ipvio.stream

/**
 * Übersetzungskern (ohne Android, testbar). Quelltexte sind die deutschen Oberflächentexte.
 * Zeilenformat: de ¦ en ¦ tr ¦ bs ¦ hr ¦ sr ¦ es ¦ fr ¦ it ¦ nl   (hr/sr leer = wie bs; andere leer = wie en)
 * Platzhalter: {0} beliebiger Text, {#0} Zahl. Ein Zeilenumbruch wird als \n geschrieben.
 */
object I18nCore {
    val CODES = listOf("de", "en", "tr", "bs", "hr", "sr", "es", "fr", "it", "nl")
    val NATIVE = listOf("Deutsch", "English", "Türkçe", "Bosanski", "Hrvatski", "Srpski", "Español", "Français", "Italiano", "Nederlands")

    private class Pat(val src: String, val re: Regex, val tr: Array<String>, val numeric: BooleanArray)

    private val exact = HashMap<String, Array<String>>()
    private val byDe = HashMap<String, Array<String>>()
    private val pats = ArrayList<Pat>()
    private val memo = Array(CODES.size) { java.util.concurrent.ConcurrentHashMap<String, String>() }
    private val PH = Regex("""\{(#?)(\d)\}""")
    // Umrahmende Leerzeichen und Pfeilzeichen (‹ ›) gehören nicht zum Text, der übersetzt wird
    private val DECO = Regex("""(?s)^([‹›\s]*)(.*?)([\s‹›]*)$""")
    private val DOT = Regex("""\s*·\s*""")

    @Synchronized
    fun load(vararg blocks: String) {
        for (b in blocks) for (line in b.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split('¦').map { it.trim().replace("\\n", "\n") }
            if (p.size < 2) continue
            val de = p[0]
            val en = p.getOrElse(1) { "" }.ifEmpty { de }
            val bs = p.getOrElse(3) { "" }.ifEmpty { en }
            val row = Array(CODES.size) { i ->
                when (i) {
                    0 -> de
                    1 -> en
                    4, 5 -> p.getOrElse(i) { "" }.ifEmpty { bs }
                    else -> p.getOrElse(i) { "" }.ifEmpty { en }
                }
            }
            if (PH.containsMatchIn(de)) {
                val sb = StringBuilder("^")
                var last = 0
                val numeric = ArrayList<Boolean>()
                for (m in PH.findAll(de)) {
                    sb.append(Regex.escape(de.substring(last, m.range.first)))
                    sb.append(if (m.groupValues[1] == "#") """([\d.,:]+)""" else "(.+?)")
                    numeric.add(m.groupValues[1] == "#")
                    last = m.range.last + 1
                }
                sb.append(Regex.escape(de.substring(last))).append("$")
                // Platzhalter-Nummern in Reihenfolge des Auftretens (0,1,2 …)
                pats.removeAll { it.src == de }
                pats.add(Pat(de, Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL), row, numeric.toBooleanArray()))
            } else exact[de] = row
            byDe[de] = row
        }
        pats.sortByDescending { it.re.pattern.length }
        for (m in memo) m.clear()
    }

    /** Ergänzt eine einzelne Sprachspalte (Zeilen "Deutsch¦Übersetzung") zu bereits geladenen Zeilen. */
    @Synchronized
    fun loadColumn(li: Int, vararg blocks: String) {
        for (b in blocks) for (line in b.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split('¦').map { it.trim().replace("\\n", "\n") }
            if (p.size < 2 || p[1].isEmpty()) continue
            byDe[p[0]]?.set(li, p[1])
        }
        for (m in memo) m.clear()
    }

    fun size() = exact.size + pats.size

    fun translate(s: String, li: Int): String {
        if (li <= 0 || li >= CODES.size || s.isEmpty()) return s
        val m = memo[li]
        m[s]?.let { return it }
        val r = lookup(s, li, 0) ?: s
        if (m.size > 6000) m.clear()
        m[s] = r
        return r
    }

    private fun lookup(s: String, li: Int, depth: Int): String? {
        if (depth > 4) return null
        exact[s]?.let { return it[li] }
        val d = DECO.matchEntire(s)
        if (d != null) {
            val pre = d.groupValues[1]; val core = d.groupValues[2]; val suf = d.groupValues[3]
            if (core.isNotEmpty() && (pre.isNotEmpty() || suf.isNotEmpty()) && core != s) {
                lookup(core, li, depth + 1)?.let { return pre + it + suf }
            }
        }
        for (p in pats) {
            val m = p.re.matchEntire(s) ?: continue
            var out = p.tr[li]
            for (i in 0 until m.groupValues.size - 1) {
                val g = m.groupValues[i + 1]
                val v = if (i < p.numeric.size && p.numeric[i]) g else (lookup(g, li, depth + 1) ?: g)
                out = out.replace("{" + i + "}", v).replace("{#" + i + "}", v)
            }
            return out
        }
        // Zusammengesetzte Texte: "A · B" und "Name: Wert"
        val seps = DOT.findAll(s).toList()
        if (seps.isNotEmpty()) {
            val parts = ArrayList<String>()
            var last = 0
            for (m in seps) { parts.add(s.substring(last, m.range.first)); last = m.range.last + 1 }
            parts.add(s.substring(last))
            var any = false
            val tr = parts.map { part -> lookup(part, li, depth + 1)?.also { any = true } ?: part }
            if (any) {
                val sb = StringBuilder(tr[0])
                for (i in seps.indices) sb.append(seps[i].value).append(tr[i + 1])
                return sb.toString()
            }
        }
        val c = s.indexOf(": ")
        if (c > 0) {
            val a = lookup(s.substring(0, c), li, depth + 1)
            val b = lookup(s.substring(c + 2), li, depth + 1)
            if (a != null || b != null) return (a ?: s.substring(0, c)) + ": " + (b ?: s.substring(c + 2))
        }
        return null
    }

    /** Sprachindex für einen Gerätesprach-Code (z. B. "tr"); unbekannt → Englisch. */
    fun indexForSystem(code: String): Int {
        val i = CODES.indexOf(code.lowercase())
        return if (i >= 0) i else 1
    }
}
