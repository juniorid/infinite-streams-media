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
        Cat("Baseball", rx("""\b(mlb|baseball|major\s?league\s?baseball)\b""")),
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

    /**
     * Rank a playlist channel for a particular scoreboard event.
     * Generic channels such as FOX One / ESPN are deliberately not accepted as a match
     * unless their title or group actually identifies the sport or one of the teams.
     */
    fun scoreChannelRank(game: ScoreGame, channel: Item): Int {
        val text = (channel.title + " " + channel.group).lowercase()
        val leagueTerms = when (game.league) {
            "NFL" -> listOf("nfl", "american football", "football", "gridiron")
            "College Football" -> listOf("ncaa football", "college football", "ncaaf")
            "NBA" -> listOf("nba", "basketball")
            "WNBA" -> listOf("wnba", "basketball")
            "Men's College Basketball" -> listOf("ncaa basketball", "college basketball", "ncaab", "basketball")
            "MLB" -> listOf("mlb", "baseball")
            "NHL" -> listOf("nhl", "ice hockey", "hockey", "eishockey")
            "MLS" -> listOf("mls", "major league soccer", "soccer")
            "Premier League" -> listOf("premier league", "epl", "english football", "soccer")
            "Champions League" -> listOf("champions league", "uefa", "soccer")
            "UFC" -> listOf("ufc", "mma", "mixed martial arts")
            "Tennis" -> listOf("tennis", "atp", "wta")
            "Golf" -> listOf("golf", "pga")
            "Formula 1" -> listOf("formula 1", "formula one", "f1", "motorsport")
            "Cricket" -> listOf("cricket", "icc")
            else -> emptyList()
        }
        var rank = 0
        for (term in leagueTerms) {
            if (text.contains(term)) rank = maxOf(rank, if (term == "basketball" || term == "soccer" || term == "football" || term == "hockey" || term == "motorsport") 2 else 5)
        }
        val teams = listOf(game.home, game.away).filter { it.isNotBlank() }
        for (team in teams) {
            val normalized = team.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
            if (normalized.length >= 4 && text.contains(normalized)) rank = maxOf(rank, 8)
            val words = normalized.split(Regex("\\s+")).filter { it.length >= 5 }
            if (words.any { text.contains(it) }) rank = maxOf(rank, 6)
        }
        return rank
    }

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

    // ESPN's public, undocumented scoreboard feeds. No API key is required; responses may change.
    data class ScoreGame(
        val id: String, val league: String, val name: String, val state: String,
        val detail: String, val home: String, val away: String,
        val homeScore: String, val awayScore: String,
        val homeLogo: String, val awayLogo: String, val status: String,
        val periodScores: String = "", val lastPlay: String = "", val clock: String = "", val period: String = ""
    )

    private data class ScoreFeed(val label: String, val path: String)

    data class StandingRow(
        val rank: String, val team: String, val record: String,
        val summary: String, val logo: String, val group: String
    )

    data class StandingsTable(val league: String, val rows: List<StandingRow>, val groups: List<String>)

    /** Fetch the selected league's current ESPN standings table. Unsupported sports return an empty table. */
    fun fetchStandings(league: String): StandingsTable {
        val feed = SCORE_FEEDS.firstOrNull { it.label == league }
            ?: return StandingsTable(league, emptyList(), emptyList())
        var connection: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL("https://site.api.espn.com/apis/v2/sports/${feed.path}/standings")
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 InfiniteStreamsMedia")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return StandingsTable(league, emptyList(), emptyList())
            val root = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val rows = ArrayList<StandingRow>()
            val groups = ArrayList<String>()
            fun stat(entry: org.json.JSONObject, vararg names: String): String {
                val stats = entry.optJSONArray("stats") ?: return ""
                for (i in 0 until stats.length()) {
                    val s = stats.optJSONObject(i) ?: continue
                    val key = (s.optString("name") + " " + s.optString("abbreviation") + " " + s.optString("shortDisplayName")).lowercase()
                    if (names.any { key.contains(it) }) {
                        val value = s.optString("displayValue").ifBlank {
                            val number = s.opt("value")
                            if (number == null || number == org.json.JSONObject.NULL) "" else number.toString()
                        }
                        if (value.isNotBlank()) return value
                    }
                }
                return ""
            }
            fun addEntries(standings: org.json.JSONObject, group: String) {
                val entries = standings.optJSONArray("entries") ?: return
                if (entries.length() == 0) return
                if (group.isNotBlank() && group !in groups) groups.add(group)
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val team = entry.optJSONObject("team") ?: continue
                    val name = team.optString("displayName", team.optString("name", "Team"))
                    val wins = stat(entry, "wins", "win")
                    val losses = stat(entry, "losses", "loss")
                    val ties = stat(entry, "ties", "tie")
                    val record = when {
                        wins.isNotBlank() && losses.isNotBlank() && ties.isNotBlank() && ties != "0" -> "$wins-$losses-$ties"
                        wins.isNotBlank() && losses.isNotBlank() -> "$wins-$losses"
                        else -> stat(entry, "overall record", "record").ifBlank { stat(entry, "games played", "gp") }
                    }
                    val rank = entry.optString("rank").ifBlank {
                        stat(entry, "playoffseed", "playoff seed", "rank", "position").ifBlank { (i + 1).toString() }
                    }
                    val summaryParts = listOf(
                        stat(entry, "winpercent", "win pct", "percentage").takeIf { it.isNotBlank() }?.let { "Win% $it" },
                        stat(entry, "gamesbehind", "games behind").takeIf { it.isNotBlank() && it != "0" }?.let { "$it GB" },
                        stat(entry, "pointsfor", "points for").takeIf { it.isNotBlank() }?.let { "PF $it" },
                        stat(entry, "pointsagainst", "points against").takeIf { it.isNotBlank() }?.let { "PA $it" },
                        stat(entry, "streak").takeIf { it.isNotBlank() }?.let { "Streak $it" }
                    ).filterNotNull()
                    val logo = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
                        .ifBlank { team.optString("logo") }
                    rows.add(StandingRow(rank, name, record.ifBlank { "—" }, summaryParts.joinToString("  ·  "), logo, group))
                }
            }
            fun walk(node: org.json.JSONObject, group: String = "") {
                val label = node.optString("name").ifBlank { node.optString("abbreviation") }.ifBlank { group }
                node.optJSONObject("standings")?.let { addEntries(it, label) }
                node.optJSONArray("children")?.let { children ->
                    for (i in 0 until children.length()) children.optJSONObject(i)?.let { walk(it, label) }
                }
            }
            walk(root)
            StandingsTable(league, rows, groups)
        } catch (_: Exception) {
            StandingsTable(league, emptyList(), emptyList())
        } finally {
            connection?.disconnect()
        }
    }


    private val SCORE_FEEDS = listOf(
        ScoreFeed("NFL", "football/nfl"),
        ScoreFeed("College Football", "football/college-football"),
        ScoreFeed("NBA", "basketball/nba"),
        ScoreFeed("WNBA", "basketball/wnba"),
        ScoreFeed("Men's College Basketball", "basketball/mens-college-basketball"),
        ScoreFeed("MLB", "baseball/mlb"),
        ScoreFeed("NHL", "hockey/nhl"),
        ScoreFeed("MLS", "soccer/usa.1"),
        ScoreFeed("Premier League", "soccer/eng.1"),
        ScoreFeed("Champions League", "soccer/uefa.champions"),
        ScoreFeed("UFC", "mma/ufc"),
        ScoreFeed("Tennis", "tennis/atp"),
        ScoreFeed("Golf", "golf/pga"),
        ScoreFeed("Formula 1", "racing/f1"),
        ScoreFeed("Cricket", "cricket/icc")
    )

    fun fetchScoreboards(): List<ScoreGame> {
        // Fetch leagues concurrently. Sequential 5-second timeouts meant later feeds (often NHL/soccer)
        // could succeed while NFL/NBA/MLB were never reached in time.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        return try {
            val tasks = SCORE_FEEDS.map { feed ->
                java.util.concurrent.Callable {
                    val games = ArrayList<ScoreGame>()
                    var connection: java.net.HttpURLConnection? = null
                    try {
                        val url = java.net.URL("https://site.api.espn.com/apis/site/v2/sports/${feed.path}/scoreboard?limit=100")
                        connection = url.openConnection() as java.net.HttpURLConnection
                        connection.connectTimeout = 7000
                        connection.readTimeout = 7000
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 InfiniteStreamsMedia")
                        connection.setRequestProperty("Accept", "application/json")
                        if (connection.responseCode !in 200..299) return@Callable games
                        val root = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val events = root.optJSONArray("events") ?: return@Callable games
                        for (i in 0 until events.length()) {
                            val event = events.optJSONObject(i) ?: continue
                            val comp = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                            val competitors = comp.optJSONArray("competitors") ?: continue
                            var home = org.json.JSONObject()
                            var away = org.json.JSONObject()
                            for (j in 0 until competitors.length()) {
                                val team = competitors.optJSONObject(j) ?: continue
                                if (team.optString("homeAway") == "home") home = team else away = team
                            }
                            val homeTeam = home.optJSONObject("team") ?: org.json.JSONObject()
                            val awayTeam = away.optJSONObject("team") ?: org.json.JSONObject()
                            val statusObj = event.optJSONObject("status")?.optJSONObject("type") ?: org.json.JSONObject()
                            val status = statusObj.optString("description", statusObj.optString("name", "Scheduled"))
                            games += ScoreGame(
                                id = event.optString("id"),
                                league = feed.label,
                                name = event.optString("name"),
                                state = statusObj.optString("state"),
                                detail = statusObj.optString("shortDetail", event.optString("date")),
                                home = homeTeam.optString("displayName", "Home"),
                                away = awayTeam.optString("displayName", "Away"),
                                homeScore = home.optString("score", "—"),
                                awayScore = away.optString("score", "—"),
                                homeLogo = homeTeam.optString("logo"),
                                awayLogo = awayTeam.optString("logo"),
                                status = status,
                                periodScores = listOf(away, home).map { competitor ->
                                    val periods = competitor.optJSONArray("linescores") ?: org.json.JSONArray()
                                    (0 until periods.length()).mapNotNull { idx -> periods.optJSONObject(idx)?.optString("displayValue")?.takeIf { it.isNotBlank() } }
                                        .joinToString("  |  ")
                                }.let { scores -> if (scores.any { it.isNotBlank() }) "Away: ${scores[0]}   Home: ${scores[1]}" else "" },
                                lastPlay = comp.optJSONObject("situation")?.optJSONObject("lastPlay")?.optString("text").orEmpty(),
                                clock = event.optJSONObject("status")?.optString("displayClock").orEmpty(),
                                period = event.optJSONObject("status")?.optString("period").orEmpty()
                            )
                        }
                    } catch (_: Exception) {
                        // One unavailable ESPN league must not prevent other leagues from loading.
                    } finally {
                        connection?.disconnect()
                    }
                    games
                }
            }
            pool.invokeAll(tasks).flatMap { task -> runCatching { task.get() }.getOrDefault(emptyList()) }
        } finally {
            pool.shutdownNow()
        }
    }
}
