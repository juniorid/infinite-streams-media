package com.ipvio.stream

/**
 * Sport (Runde 20): erkennt Sportsender an Kategorie oder Sendername und Sport-Sendungen am Titel im TV-Guide.
 * Ohne Android, testbar. Keine Daten von außen – alles kommt aus deiner Playlist und ihrem TV-Programm.
 */
object SportLogic {
    // Wortgrenzen verhindern Treffer wie „Transport“; Marken ohne Wortgrenze (Eurosport, DAZN …) stehen einzeln da.
    private val CHANNEL = Regex(
        """\b(sports?|spor|sportklub|sportdigital|deportes?|esportes?|sportivo)\b|eurosport|dazn|espn|bein\s?sports?|sky\s?sports?|supersport|""" +
            """ziggo\s?sport|setanta|\btsn\d?\b|arena\s?sport|nova\s?sport|polsat\s?sport|eleven\s?sports?|\bfox\s?sports?\b|""" +
            """laliga|bundesliga|premier\s?league|champions\s?league|\b(nba|nfl|nhl|mlb|ufc|wwe|f1|motogp|ppv)\b|formula\s?1|formel\s?1|""" +
            """\b(tennis|golf|rugby|cricket|racing|fight|fußball|fussball|football|soccer|futbol|calcio|darts|snooker)\b""",
        RegexOption.IGNORE_CASE
    )
    // Sendungstitel, die fast immer Sport sind (für Sendungen auf allgemeinen Sendern)
    private val EVENT = Regex(
        """\b(bundesliga|champions\s?league|europa\s?league|conference\s?league|premier\s?league|la\s?liga|serie\s?a|ligue\s?1|""" +
            """dfb[-\s]?pokal|fa\s?cup|em\s?20\d\d|wm\s?20\d\d|world\s?cup|euro\s?20\d\d|olymp\w*|formel\s?1|formula\s?1|grand\s?prix|""" +
            """motogp|nba|nfl|nhl|mlb|ufc|wwe|boxen|boxing|tennis|wimbledon|roland\s?garros|us\s?open|australian\s?open|""" +
            """handball|basketball|volleyball|eishockey|ice\s?hockey|biathlon|ski\s?alpin|skispringen|tour\s?de\s?france|radsport|""" +
            """darts|snooker|golf|rugby|cricket|sportschau|sportstudio|live:\s|fußball|fussball|football|soccer)\b""",
        RegexOption.IGNORE_CASE
    )

    fun isSportChannel(i: Item): Boolean = i.kind == Kind.LIVE && (CHANNEL.containsMatchIn(i.group) || CHANNEL.containsMatchIn(i.title))

    fun isSportEvent(title: String): Boolean = EVENT.containsMatchIn(title)

    /** Eine Sendung im Sport-Bereich: Sender, Sendung, läuft gerade? */
    class Slot(val ch: Item, val prog: EpgEntry, val live: Boolean)

    /**
     * Was läuft jetzt und gleich (bis [horizonSec]) auf Sportsendern, dazu Sport-Sendungen auf allen anderen Sendern.
     * [progs] liefert das Programm eines Senders (aus dem geladenen TV-Guide).
     */
    fun slots(channels: List<Item>, progs: (Item) -> List<EpgEntry>, nowSec: Long, horizonSec: Long = 3 * 3600, limit: Int = 200): Pair<List<Slot>, List<Slot>> {
        val now = ArrayList<Slot>()
        val soon = ArrayList<Slot>()
        val seen = HashSet<String>()
        for (ch in channels) {
            val sportCh = isSportChannel(ch)
            val list = progs(ch)
            if (list.isEmpty()) continue
            for (e in list) {
                if (e.end <= nowSec || e.start > nowSec + horizonSec) continue
                if (!sportCh && !isSportEvent(e.title)) continue
                // Gleiche Sendung auf demselben Sender nur einmal (Doppelte aus mehreren Quellen)
                if (!seen.add(ch.title.lowercase() + "|" + e.start + "|" + e.title)) continue
                if (e.start <= nowSec) now.add(Slot(ch, e, true)) else soon.add(Slot(ch, e, false))
            }
            if (now.size + soon.size > limit * 2) break
        }
        // Jetzt: bald endende zuerst ist unübersichtlich – nach Sendername; Gleich: nach Beginn
        return now.sortedWith(compareBy({ !isSportEvent(it.prog.title) }, { it.ch.title.lowercase() })).take(limit) to
            soon.sortedBy { it.prog.start }.take(limit)
    }

    // ───────── Runde 22: Sportarten und Zeitfenster zum Filtern ─────────

    class Cat(val name: String, val re: Regex)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Reihenfolge zählt: „American Football“ vor „Fußball“, weil beide „football“ enthalten. */
    val CATS: List<Cat> = listOf(
        Cat("American Football", rx("""\b(nfl|american\s?football|super\s?bowl|ncaa\s?football|college\s?football|ncaaf)\b""")),
        Cat("Basketball", rx("""\b(nba|wnba|basketball|euroleague|bbl|ncaa\s?basketball|acb)\b""")),
        Cat("Eishockey", rx("""\b(nhl|eishockey|ice\s?hockey|khl|del2?)\b""")),
        Cat("Fußball", rx("""\b(fußball|fussball|football|soccer|futbol|fútbol|calcio|fudbal|nogomet|bundesliga|champions\s?league|europa\s?league|conference\s?league|premier\s?league|la\s?liga|laliga|serie\s?a|ligue\s?1|eredivisie|s[üu]per\s?lig|dfb[-\s]?pokal|fa\s?cup|copa|em\s?20\d\d|wm\s?20\d\d|world\s?cup|euro\s?20\d\d|mls)\b""")),
        Cat("Tennis", rx("""\b(tennis|wimbledon|roland\s?garros|us\s?open|australian\s?open|atp|wta)\b""")),
        Cat("Motorsport", rx("""\b(formel\s?1|formula\s?1|f1|motogp|moto2|moto3|grand\s?prix|nascar|indycar|dtm|rallye|rally|wrc|motorsport|superbike)\b""")),
        Cat("Kampfsport", rx("""\b(ufc|boxen|boxing|wwe|mma|wrestling|kickbox\w*|fight\w*|ppv)\b""")),
        Cat("Golf", rx("""\b(golf|pga|masters)\b""")),
        Cat("Handball & Volleyball", rx("""\b(handball|volleyball|rukomet|odbojka)\b""")),
        Cat("Rugby & Cricket", rx("""\b(rugby|cricket)\b""")),
        Cat("Radsport", rx("""\b(tour\s?de\s?france|radsport|giro|vuelta|cycling|ciclismo)\b""")),
        Cat("Wintersport", rx("""\b(biathlon|ski\w*|skispringen|rodeln|bob|langlauf|wintersport|snowboard)\b""")),
        Cat("Darts & Snooker", rx("""\b(darts|snooker)\b"""))
    )
    val OTHER: Int get() = CATS.size
    fun catName(i: Int): String = CATS.getOrNull(i)?.name ?: "Sonstige"

    /** Sportart: zuerst der Sendungstitel, dann Sendername und Kategorie des Senders; sonst „Sonstige“. */
    fun categoryOf(title: String, chText: String): Int {
        for ((i, c) in CATS.withIndex()) if (c.re.containsMatchIn(title)) return i
        for ((i, c) in CATS.withIndex()) if (c.re.containsMatchIn(chText)) return i
        return OTHER
    }

    /** Zeitfenster: 0 = nächste 3 Stunden, 1 = heute, 2 = morgen (nach Ortszeit der gewählten Zeitzone). */
    fun startsIn(win: Int, start: Long, nowSec: Long, tz: java.util.TimeZone): Boolean {
        val cal = java.util.Calendar.getInstance(tz)
        cal.timeInMillis = nowSec * 1000
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val tomorrow = cal.timeInMillis / 1000
        val dayAfter = tomorrow + 86400
        return when (win) {
            0 -> start <= nowSec + 3 * 3600
            1 -> start < tomorrow
            else -> start in tomorrow until dayAfter
        }
    }
}
