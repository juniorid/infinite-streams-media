package com.ipvio.stream

/**
 * Reine Regeln für das Navigationsmenü (ohne Android), damit sie getestet werden können.
 * Garantien: Jeder Bereich kommt genau einmal vor, "Start" (0) und "Einstellungen" (5) können nie ausgeblendet werden.
 */
object MenuLogic {
    const val COUNT = 11
    const val HOME = 0
    const val SEARCH = 4
    const val SETTINGS = 5
    /** Seit Runde 13 (Entwurf): TV-Guide, Favoriten (Sender), Meine Liste (Filme/Serien). */
    const val EPG = 6
    const val FAVS = 7
    const val MYLIST = 8
    /** Runde 15 (Nachtrag 17): Verlauf – zuletzt Angesehenes, Filme und Serien getrennt. */
    const val VERLAUF = 9
    /** Runde 20: Sport – laufende und kommende Sport-Sendungen, Sportsender. */
    const val SPORT = 10
    /** Wie im Entwurf: Home, Live TV, Filme, Serien, EPG, Favoriten, Meine Liste, (Suche), Einstellungen. */
    val DEFAULT_ORDER = listOf(0, 3, 1, 2, SPORT, EPG, FAVS, MYLIST, VERLAUF, SEARCH, 5)
    /**
     * Suche steht oben im Kopf, nicht in der Seitenleiste: standardmäßig ausgeblendet.
     * EPG (Runde 14, Wunsch des Nutzers): ebenfalls ausgeblendet – der TV-Guide ist über Live TV erreichbar.
     * Beides lässt sich unter Einstellungen > Menü wieder einblenden.
     */
    const val DEFAULT_HIDDEN = "4,6"

    fun parseOrder(raw: String?): List<Int> {
        val order = (raw ?: DEFAULT_ORDER.joinToString(",")).split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0 until COUNT }.distinct().toMutableList()
        for (id in 0 until COUNT) if (id !in order) {
            // Neuer Bereich (gespeicherte Reihenfolge von vorher): vor „Einstellungen“ einreihen, nicht dahinter
            val s = order.indexOf(SETTINGS)
            if (id >= EPG && s >= 0) order.add(s, id) else order.add(id)
        }
        return order
    }

    fun parseHidden(raw: String?): List<Int> =
        (raw ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0 until COUNT && it != HOME && it != SETTINGS }.distinct()

    fun move(order: List<Int>, id: Int, delta: Int): List<Int> {
        val i = order.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= order.size) return order
        val m = order.toMutableList()
        m.removeAt(i); m.add(j, id)
        return m
    }

    fun toggle(hidden: List<Int>, id: Int): List<Int> {
        if (id == HOME || id == SETTINGS || id !in 0 until COUNT) return hidden
        return if (id in hidden) hidden - id else hidden + id
    }

    fun visible(order: List<Int>, hidden: List<Int>): List<Int> = order.filter { it !in hidden }
}
