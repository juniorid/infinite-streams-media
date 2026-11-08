package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class Meta(
    val tmdbId: Int,
    val isTv: Boolean,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val rating: Double?,
    val year: String?,
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val trailerKey: String? = null,
    val imdbId: String? = null,
    val imdbRating: String? = null,
    val rtRating: String? = null,
    val extended: Boolean = false,
    val genreIds: List<Int> = emptyList(),
    val lang: String? = null,
    val votes: Int = 0,
    val countries: List<String> = emptyList(),
    /** Sprache, in der Beschreibung und Genres geladen wurden */
    val uiLang: String = "de",
    /** Treffer beim Öffnen der Detailseite geprüft (Name/Staffeln, siehe [MatchLogic]). */
    val checked: Boolean = false
) {
    /** Wertung für Sortierung und Filter (0 bis 10): zuerst IMDb, dann Rotten Tomatoes, dann TMDB, zuletzt die Angabe des Anbieters. */
    fun score(): Double? =
        imdbRating?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: rtRating?.trim()?.removeSuffix("%")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { it / 10.0 }
            ?: rating
}

data class EpMeta(
    val num: Int, val name: String?, val overview: String?, val still: String?,
    val rating: Double?, val imdb: String?, val runtime: Int?, val air: String?
)

private fun JSONObject.str(k: String): String? =
    if (!has(k) || isNull(k)) null else getString(k).ifBlank { null }

private fun JSONObject.dbl(k: String): Double? =
    if (!has(k) || isNull(k)) null else getDouble(k)

object MetaRepo {
    private val client = Net.base
    /**
     * Zwischenspeicher in zwei Stufen, damit große Playlists den Arbeitsspeicher nicht füllen:
     * - [mem]: nur die zuletzt benutzten vollständigen Einträge (mit Beschreibung, Trailer, Bewertungen)
     * - [slim]: für JEDEN Titel nur das Schlanke (Cover, Genres, Land, Bewertung, Jahr) – das braucht die Sortierung nach Genres.
     * Die vollständigen Daten liegen auf dem Gerät und werden bei Bedarf von dort nachgeladen.
     */
    private const val FULL_CAP = 400
    private val mem = object : LinkedHashMap<String, Meta>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Meta>?) = size > FULL_CAP
    }
    private val slim = ConcurrentHashMap<String, Meta>()
    private val misses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun slimOf(m: Meta) = m.copy(overview = null, trailerKey = null, imdbId = null)
    private fun memGet(k: String): Meta? = synchronized(mem) { mem[k] }
    private fun memPut(k: String, m: Meta) { synchronized(mem) { mem[k] = m }; slim[k] = slimOf(m); slimTs[k] = System.currentTimeMillis(); dirty = true }
    /** Zeitpunkt, an dem der schlanke Eintrag von TMDB kam (für die 30-Tage-Auffrischung) */
    private val slimTs = ConcurrentHashMap<String, Long>()
    private val missTs = ConcurrentHashMap<String, Long>()
    @Volatile private var dirty = false
    private val indexLoaded = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Schlanker Gesamtindex auf dem Gerät (eine Datei statt Tausender Einzeldateien). Ohne ihn las die App bei JEDEM Start für
     * jeden Titel die Einzeldatei neu ein, um Genre/Land/Bewertung zu kennen. Mit dem Index ist das in Sekunden da, und bei einer
     * aktualisierten Playlist werden nur wirklich neue Titel bei TMDB abgefragt.
     */
    suspend fun loadIndex(c: Context) = withContext(Dispatchers.IO) {
        if (!indexLoaded.compareAndSet(false, true)) return@withContext
        try {
            val f = File(dir(c), "_slim.idx")
            if (!f.exists()) return@withContext
            val now = System.currentTimeMillis()
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = line.indexOf('\t')
                    if (t <= 0) continue
                    val k = line.substring(0, t)
                    try {
                        val j = JSONObject(line.substring(t + 1))
                        val ts = j.optLong("ts")
                        if (now - ts > TTL) continue
                        if (j.getInt("tmdbId") == -1) {
                            if (j.optInt("v") >= MATCH_V) { misses.add(k); missTs[k] = ts }
                            continue
                        }
                        slim[k] = Meta(
                            j.getInt("tmdbId"), j.getBoolean("isTv"), j.str("poster"), j.str("backdrop"), null,
                            j.dbl("rating"), j.str("year"),
                            (j.str("genres") ?: "").split("|").filter { it.isNotBlank() },
                            if (j.has("runtime") && !j.isNull("runtime")) j.getInt("runtime") else null,
                            null, null, j.str("imdb"), j.str("rt"), j.optBoolean("ext"),
                            (j.str("gids") ?: "").split(",").mapNotNull { it.toIntOrNull() },
                            j.str("lang"), votes = j.optInt("votes"),
                            countries = (j.str("countries") ?: "").split(",").filter { it.isNotBlank() },
                            uiLang = j.optString("ui", "de"), checked = j.optBoolean("chk")
                        )
                        slimTs[k] = ts
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    suspend fun saveIndex(c: Context) = withContext(Dispatchers.IO) {
        if (!dirty) return@withContext
        dirty = false
        try {
            val tmp = File(dir(c), "_slim.tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, m) in slim) {
                    if (k.indexOf('\t') >= 0 || k.indexOf('\n') >= 0 || k.indexOf('\r') >= 0) continue
                    val j = JSONObject()
                    j.put("ts", slimTs[k] ?: System.currentTimeMillis())
                    j.put("tmdbId", m.tmdbId); j.put("isTv", m.isTv)
                    j.put("poster", m.poster); j.put("backdrop", m.backdrop)
                    j.put("rating", m.rating); j.put("year", m.year)
                    j.put("genres", m.genres.joinToString("|")); j.put("runtime", m.runtime)
                    j.put("ext", m.extended); j.put("imdb", m.imdbRating); j.put("rt", m.rtRating)
                    j.put("gids", m.genreIds.joinToString(",")); j.put("lang", m.lang); j.put("votes", m.votes)
                    j.put("countries", m.countries.joinToString(",")); j.put("ui", m.uiLang); j.put("chk", m.checked)
                    w.write(k); w.write("\t"); w.write(j.toString()); w.write("\n")
                }
                for (k in misses) {
                    if (k.indexOf('\t') >= 0 || k.indexOf('\n') >= 0 || k.indexOf('\r') >= 0) continue
                    w.write(k); w.write("\t")
                    w.write("{\"tmdbId\":-1,\"v\":$MATCH_V,\"ts\":${missTs[k] ?: System.currentTimeMillis()}}\n")
                }
            }
            val dst = File(dir(c), "_slim.idx")
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
        } catch (e: Exception) {
            dirty = true
        }
    }
    private val seasons = HashMap<String, List<EpMeta>>()
    private val gate = Semaphore(6)
    private const val IMG = "https://image.tmdb.org/t/p/"
    private const val TTL = 30L * 24 * 3600 * 1000
    /** Stand der Treffer-Suche. „Nicht gefunden“ aus älteren Ständen wird mit der besseren Suche neu versucht. */
    private const val MATCH_V = 2

    fun enabled() = Config.TMDB_KEY.isNotBlank()

    /** Infos gibt es für diesen Titel: über TMDB (Schlüssel eingetragen) oder direkt vom Xtream-Anbieter. */
    fun usable(i: Item): Boolean = enabled() || ProviderLogic.supports(i)

    private val provMem = ConcurrentHashMap<String, Meta>()
    private val provFail = ConcurrentHashMap<String, Long>()

    /** Infos vom Anbieter (ohne TMDB-Schlüssel). Auf dem Gerät gemerkt, getrennt vom TMDB-Zwischenspeicher. */
    suspend fun providerMeta(ctx: Context, i: Item): Meta? {
        if (i.kind == Kind.LIVE || !ProviderLogic.supports(i)) return null
        val k = "P|" + key(i)
        provMem[k]?.let { return withOmdbTitle(ctx, k, i, it) }
        provFail[k]?.let { if (System.currentTimeMillis() - it < 120_000L) return null }
        load(ctx, k)?.let { provMem[k] = it; return withOmdbTitle(ctx, k, i, it) }
        val m = ProviderInfo.fetch(ctx, i)
        if (m == null) { provFail[k] = System.currentTimeMillis(); return null }
        provMem[k] = m
        save(ctx, k, m)
        return withOmdbTitle(ctx, k, i, m)
    }

    private val omdbTried: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * IMDb- und Rotten-Tomatoes-Wertung auch ohne TMDB: OMDb wird dann über den Titel (und das Jahr) gefragt.
     * Pro Titel und Start der App nur einmal; ein Treffer wird mit den Anbieter-Infos zusammen auf dem Gerät gemerkt.
     */
    private suspend fun withOmdbTitle(ctx: Context, k: String, i: Item, m: Meta): Meta {
        if (Config.OMDB_KEY.isBlank() || m.imdbRating != null || m.rtRating != null || !omdbTried.add(k)) return m
        val q = URLEncoder.encode(MatchLogic.searchTitle(i.title), "UTF-8")
        val yr = (m.year ?: i.year?.toString())?.takeIf { it.length == 4 && i.kind == Kind.MOVIE }?.let { "&y=$it" } ?: ""
        val type = if (i.kind == Kind.SERIES) "series" else "movie"
        val body = getNow("https://www.omdbapi.com/?apikey=${Config.OMDB_KEY}&t=$q&type=$type$yr") ?: return m
        return try {
            val o = JSONObject(body)
            if (o.optString("Response") != "True") return m
            val ir = o.str("imdbRating")?.takeIf { it != "N/A" }
            var rt: String? = null
            o.optJSONArray("Ratings")?.let { ra ->
                for (x in 0 until ra.length()) {
                    val r = ra.getJSONObject(x)
                    if (r.optString("Source") == "Rotten Tomatoes") rt = r.optString("Value")
                }
            }
            if (ir == null && rt == null) return m
            val nm = m.copy(imdbRating = ir, rtRating = rt, imdbId = m.imdbId ?: o.str("imdbID"))
            provMem[k] = nm
            save(ctx, k, nm)
            nm
        } catch (e: Exception) {
            m
        }
    }
    private fun key(i: Item) = i.mk
    /** Bei Speicherknappheit: Zwischenspeicher im Arbeitsspeicher leeren (Daten liegen noch auf dem Gerät). */
    fun trim() { synchronized(mem) { mem.clear() }; seasons.clear() }

    fun cached(i: Item): Meta? = key(i).let { k ->
        memGet(k) ?: slim[k] ?: (if (!enabled() || k in misses) provMem["P|$k"] else null)   // Anbieter-Infos nur ohne TMDB-Treffer
    }
    fun missed(i: Item): Boolean = key(i) in misses

    private fun httpGet(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        gate.withPermit { httpGet(url) }
    }

    private suspend fun getNow(url: String): String? = withContext(Dispatchers.IO) { httpGet(url) }

    private fun dir(c: Context): File = File(c.filesDir, "meta3").also { it.mkdirs() }

    private suspend fun save(c: Context, k: String, m: Meta) = withContext(Dispatchers.IO) {
        try {
            val j = JSONObject()
            j.put("ts", System.currentTimeMillis())
            j.put("tmdbId", m.tmdbId); j.put("isTv", m.isTv)
            j.put("poster", m.poster); j.put("backdrop", m.backdrop); j.put("overview", m.overview)
            j.put("rating", m.rating); j.put("year", m.year)
            j.put("genres", m.genres.joinToString("|")); j.put("runtime", m.runtime)
            j.put("trailer", m.trailerKey); j.put("imdbId", m.imdbId)
            j.put("imdbRating", m.imdbRating); j.put("rt", m.rtRating); j.put("ext", m.extended)
            j.put("gids", m.genreIds.joinToString(",")); j.put("lang", m.lang); j.put("votes", m.votes)
            j.put("countries", m.countries.joinToString(",")); j.put("ui", m.uiLang)
            j.put("chk", m.checked); j.put("v", MATCH_V)
            File(dir(c), k.hashCode().toString()).writeText(j.toString())
        } catch (e: Exception) {
        }
    }

    private suspend fun load(c: Context, k: String): Meta? = withContext(Dispatchers.IO) {
        try {
            val f = File(dir(c), k.hashCode().toString())
            if (!f.exists()) return@withContext null
            val j = JSONObject(f.readText())
            if (System.currentTimeMillis() - j.optLong("ts") > TTL) return@withContext null
            // Früher nicht gefunden (oft wegen „DE - “ vor dem Titel): mit der verbesserten Suche noch einmal versuchen
            if (j.getInt("tmdbId") == -1 && j.optInt("v") < MATCH_V) return@withContext null
            Meta(
                j.getInt("tmdbId"), j.getBoolean("isTv"), j.str("poster"), j.str("backdrop"), j.str("overview"),
                j.dbl("rating"), j.str("year"),
                (j.str("genres") ?: "").split("|").filter { it.isNotBlank() },
                if (j.has("runtime") && !j.isNull("runtime")) j.getInt("runtime") else null,
                j.str("trailer"), j.str("imdbId"), j.str("imdbRating"), j.str("rt"), j.optBoolean("ext"),
                (j.str("gids") ?: "").split(",").mapNotNull { it.toIntOrNull() },
                j.str("lang"), votes = j.optInt("votes"),
                countries = (j.str("countries") ?: "").split(",").filter { it.isNotBlank() },
                uiLang = j.optString("ui", "de"),
                checked = j.optBoolean("chk")
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun basic(ctx: Context, i: Item): Meta? {
        if (!enabled() || i.kind == Kind.LIVE) return null
        val k = key(i)
        memGet(k)?.let { return it }
        if (k in misses) return null
        load(ctx, k)?.let {
            if (it.tmdbId == -1) { misses.add(k); missTs[k] = System.currentTimeMillis(); dirty = true; return null }
            memPut(k, it); return it
        }
        val tv = i.kind == Kind.SERIES
        val results = search(i, tv) ?: return null
        try {
            if (results.length() == 0) {
                misses.add(k); missTs[k] = System.currentTimeMillis(); dirty = true
                save(ctx, k, Meta(-1, tv, null, null, null, null, null))
                return null
            }
            // Nicht blind den ersten Treffer nehmen: ein gleichnamiger gewinnt (siehe MatchLogic)
            val cands = candidates(results, tv)
            val best = MatchLogic.pick(i.title, if (tv) null else i.year, cands) ?: return null
            val m = metaOf(results.getJSONObject(cands.indexOf(best)), tv)
            memPut(k, m)
            save(ctx, k, m)
            return m
        } catch (e: Exception) {
            return null
        }
    }

    /** TMDB-Suche mit bereinigtem Titel (ohne „DE - “ & Co.); null = keine Antwort. */
    private suspend fun search(i: Item, tv: Boolean): org.json.JSONArray? {
        val q = URLEncoder.encode(MatchLogic.searchTitle(i.title), "UTF-8")
        val yr = if (!tv && i.year != null) "&year=${i.year}" else ""
        val body = get(
            "https://api.themoviedb.org/3/search/${if (tv) "tv" else "movie"}" +
                "?api_key=${Config.TMDB_KEY}&language=${Lang.tmdb()}&query=$q$yr"
        ) ?: return null
        return try { JSONObject(body).getJSONArray("results") } catch (e: Exception) { null }
    }

    private fun candidates(r: org.json.JSONArray, tv: Boolean): List<MatchLogic.Candidate> =
        (0 until r.length()).map { x ->
            val o = r.getJSONObject(x)
            MatchLogic.Candidate(
                o.optInt("id"), o.str(if (tv) "name" else "title"), o.str(if (tv) "original_name" else "original_title"),
                o.str(if (tv) "first_air_date" else "release_date")?.take(4), o.optInt("vote_count")
            )
        }

    private fun metaOf(o: JSONObject, tv: Boolean): Meta {
        val gids = ArrayList<Int>()
        o.optJSONArray("genre_ids")?.let { g -> for (x in 0 until g.length()) gids.add(g.getInt(x)) }
        val countries = ArrayList<String>()
        if (tv) o.optJSONArray("origin_country")?.let { g -> for (x in 0 until g.length()) countries.add(g.getString(x)) }
        return Meta(
            o.getInt("id"), tv,
            o.str("poster_path")?.let { IMG + "w500" + it },
            o.str("backdrop_path")?.let { IMG + "w1280" + it },
            o.str("overview"),
            o.dbl("vote_average")?.takeIf { it > 0 },
            o.str(if (tv) "first_air_date" else "release_date")?.take(4),
            genreIds = gids, lang = o.str("original_language"), votes = o.optInt("vote_count"),
            countries = countries, uiLang = Lang.code()
        )
    }

    /**
     * Gespeicherter Treffer passt nicht (anderer Name, weniger Staffeln als in der Playlist): neu suchen und nur zu einem
     * gleichnamigen Treffer wechseln. Gibt den neuen Eintrag zurück oder null, wenn es keinen besseren gibt.
     */
    private suspend fun rematch(ctx: Context, i: Item, wrongId: Int): Meta? {
        val tv = i.kind == Kind.SERIES
        val r = search(i, tv) ?: return null
        return try {
            val cands = candidates(r, tv).filter { it.id != wrongId && MatchLogic.sameTitle(i.title, it.name, it.original) }
            val y = if (tv) null else i.year?.toString()
            val best = (cands.filter { y != null && it.year == y }.ifEmpty { cands }).maxByOrNull { it.votes } ?: return null
            val all = candidates(r, tv)
            val m = metaOf(r.getJSONObject(all.indexOfFirst { it.id == best.id }), tv)
            memPut(key(i), m)
            save(ctx, key(i), m)
            m
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fillCountry(ctx: Context, i: Item) {
        val k = key(i)
        val m = memGet(k) ?: load(ctx, k) ?: return   // vollständigen Eintrag nehmen, nie den schlanken (sonst ginge die Beschreibung auf dem Gerät verloren)
        if (m.tmdbId <= 0) return
        if (m.countries.isNotEmpty()) {
            // Land steht schon auf dem Gerät, fehlt aber im schlanken Zwischenspeicher: dort nachtragen (sonst käme der Titel bei jedem Start wieder dran)
            if (slim[k]?.countries.isNullOrEmpty()) { slim[k] = slimOf(m); dirty = true }
            return
        }
        val type = if (m.isTv) "tv" else "movie"
        val body = get("https://api.themoviedb.org/3/$type/${m.tmdbId}?api_key=${Config.TMDB_KEY}") ?: return
        try {
            val o = JSONObject(body)
            val list = ArrayList<String>()
            if (m.isTv) o.optJSONArray("origin_country")?.let { a -> for (x in 0 until a.length()) list.add(a.getString(x)) }
            else o.optJSONArray("production_countries")?.let { a -> for (x in 0 until a.length()) list.add(a.getJSONObject(x).getString("iso_3166_1")) }
            val nm = m.copy(countries = if (list.isEmpty()) listOf("--") else list)
            memPut(k, nm)
            save(ctx, k, nm)
        } catch (e: Exception) {
        }
    }

    suspend fun extended(ctx: Context, i: Item, rematched: Boolean = false): Meta? {
        if (!enabled()) return providerMeta(ctx, i)
        val b = basic(ctx, i) ?: return providerMeta(ctx, i)
        if (b.extended && b.uiLang == Lang.code() && b.checked) return b
        val type = if (b.isTv) "tv" else "movie"
        val body = getNow(
            "https://api.themoviedb.org/3/$type/${b.tmdbId}?api_key=${Config.TMDB_KEY}" +
                "&language=${Lang.tmdb()}&append_to_response=external_ids,videos&include_video_language=${Lang.code()},en,null"
        ) ?: return b
        var m = b
        try {
            val o = JSONObject(body)
            // Treffer prüfen: Bei Serien mit mehr Staffeln in der Playlist als bei TMDB oder anderem Namen war es oft
            // ein falscher Treffer (z. B. ein beliebter Ableger statt „Game of Thrones“). Dann neu suchen.
            if (!rematched) {
                val maxSeason = i.episodes.maxOfOrNull { it.season } ?: 0
                val nameOk = MatchLogic.sameTitle(
                    i.title, o.str(if (b.isTv) "name" else "title"), o.str(if (b.isTv) "original_name" else "original_title")
                )
                val seasonsOk = !b.isTv || MatchLogic.seasonsFit(maxSeason, o.optInt("number_of_seasons"))
                if (!nameOk || !seasonsOk) {
                    val better = rematch(ctx, i, b.tmdbId)
                    if (better != null && better.tmdbId != b.tmdbId) return extended(ctx, i, rematched = true)
                }
            }
            val genres = ArrayList<String>()
            o.optJSONArray("genres")?.let { g ->
                for (x in 0 until g.length()) genres.add(g.getJSONObject(x).getString("name"))
            }
            val runtime: Int? = if (b.isTv)
                o.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.getInt(0) else null }
            else o.optInt("runtime").takeIf { it > 0 }
            val imdb = if (b.isTv) o.optJSONObject("external_ids")?.str("imdb_id") else o.str("imdb_id")
            var trailer: String? = null
            o.optJSONObject("videos")?.optJSONArray("results")?.let { v ->
                for (x in 0 until v.length()) {
                    val e = v.getJSONObject(x)
                    if (e.optString("site") == "YouTube" && e.optString("type") == "Trailer") {
                        trailer = e.optString("key"); break
                    }
                }
            }
            var ov = o.str("overview")?.takeIf { it.isNotBlank() }
            if (ov == null && Lang.code() != "en") {
                // TMDB hat in dieser Sprache keinen Text: englischen Text als Ersatz holen (nie den Text einer anderen Sprache behalten)
                ov = getNow("https://api.themoviedb.org/3/$type/${b.tmdbId}?api_key=${Config.TMDB_KEY}&language=en-US")
                    ?.let { runCatching { JSONObject(it).str("overview") }.getOrNull() }?.takeIf { it.isNotBlank() }
            }
            m = b.copy(overview = ov ?: b.overview.takeIf { b.uiLang == Lang.code() }, genres = genres, runtime = runtime,
                trailerKey = trailer, imdbId = imdb, uiLang = Lang.code(), checked = true)
        } catch (e: Exception) {
        }
        if (Config.OMDB_KEY.isNotBlank() && m.imdbId != null) {
            val ob = getNow("https://www.omdbapi.com/?apikey=${Config.OMDB_KEY}&i=${m.imdbId}")
            try {
                if (ob != null) {
                    val o = JSONObject(ob)
                    val ir = o.str("imdbRating")?.takeIf { it != "N/A" }
                    var rt: String? = null
                    o.optJSONArray("Ratings")?.let { ra ->
                        for (x in 0 until ra.length()) {
                            val r = ra.getJSONObject(x)
                            if (r.optString("Source") == "Rotten Tomatoes") rt = r.optString("Value")
                        }
                    }
                    m = m.copy(imdbRating = ir, rtRating = rt)
                }
            } catch (e: Exception) {
            }
        }
        m = m.copy(extended = true)
        memPut(key(i), m)
        save(ctx, key(i), m)
        return m
    }

    private val tvSeasonMem = ConcurrentHashMap<String, List<EpisodeLogic.TmdbSeason>>()

    /**
     * Staffeln einer Serie laut TMDB (Nummer, Folgenzahl, Name) – für lange Staffeln wie One Piece,
     * die der Anbieter als eine einzige Staffel liefert. Auf dem Gerät 3 Tage gemerkt (laufende Serien bekommen neue Folgen).
     */
    suspend fun tvSeasons(ctx: Context, tmdbId: Int): List<EpisodeLogic.TmdbSeason> {
        if (tmdbId <= 0 || !enabled()) return emptyList()
        val k = "$tmdbId|${Lang.code()}"
        tvSeasonMem[k]?.let { return it }
        val f = File(dir(ctx), "tvs_" + k.hashCode())
        fun parse(body: String): List<EpisodeLogic.TmdbSeason> {
            val out = ArrayList<EpisodeLogic.TmdbSeason>()
            JSONObject(body).optJSONArray("seasons")?.let { a ->
                for (x in 0 until a.length()) {
                    val o = a.getJSONObject(x)
                    out.add(EpisodeLogic.TmdbSeason(o.optInt("season_number"), o.optInt("episode_count"), o.str("name")))
                }
            }
            return out
        }
        val cached = withContext(Dispatchers.IO) {
            runCatching { if (f.exists() && System.currentTimeMillis() - f.lastModified() < 3L * 24 * 3600 * 1000) parse(f.readText()) else null }.getOrNull()
        }
        if (cached != null) { tvSeasonMem[k] = cached; return cached }
        val body = getNow("https://api.themoviedb.org/3/tv/$tmdbId?api_key=${Config.TMDB_KEY}&language=${Lang.tmdb()}") ?: return emptyList()
        val res = runCatching { parse(body) }.getOrDefault(emptyList())
        if (res.isNotEmpty()) {
            tvSeasonMem[k] = res
            withContext(Dispatchers.IO) { runCatching { f.writeText(body) } }
        }
        return res
    }

    suspend fun season(tmdbId: Int, season: Int, imdbId: String?): List<EpMeta> {
        val k = "$tmdbId|$season|${imdbId ?: ""}|${Lang.code()}"
        seasons[k]?.let { return it }
        val body = getNow("https://api.themoviedb.org/3/tv/$tmdbId/season/$season?api_key=${Config.TMDB_KEY}&language=${Lang.tmdb()}")
            ?: return emptyList()
        val out = ArrayList<EpMeta>()
        try {
            val arr = JSONObject(body).optJSONArray("episodes")
            if (arr != null) for (x in 0 until arr.length()) {
                val ep = arr.getJSONObject(x)
                out.add(
                    EpMeta(
                        ep.optInt("episode_number"), ep.str("name"), ep.str("overview"),
                        ep.str("still_path")?.let { IMG + "w300" + it },
                        ep.dbl("vote_average")?.takeIf { it > 0 }, null,
                        if (ep.has("runtime") && !ep.isNull("runtime")) ep.getInt("runtime") else null,
                        ep.str("air_date")
                    )
                )
            }
        } catch (e: Exception) {
        }
        if (Lang.code() != "en" && out.any { it.overview.isNullOrBlank() }) {
            // fehlende Folgentexte aus dem Englischen ergänzen
            val eb = getNow("https://api.themoviedb.org/3/tv/$tmdbId/season/$season?api_key=${Config.TMDB_KEY}&language=en-US")
            val en = HashMap<Int, String>()
            try {
                val arr = JSONObject(eb ?: "{}").optJSONArray("episodes")
                if (arr != null) for (x in 0 until arr.length()) {
                    val ep = arr.getJSONObject(x); val t = ep.str("overview")
                    if (!t.isNullOrBlank()) en[ep.optInt("episode_number")] = t
                }
            } catch (e: Exception) {
            }
            for (x in out.indices) if (out[x].overview.isNullOrBlank()) en[out[x].num]?.let { out[x] = out[x].copy(overview = it) }
        }
        var res: List<EpMeta> = out
        if (Config.OMDB_KEY.isNotBlank() && imdbId != null && out.isNotEmpty()) {
            val ob = getNow("https://www.omdbapi.com/?apikey=${Config.OMDB_KEY}&i=$imdbId&Season=$season")
            try {
                if (ob != null) {
                    val arr = JSONObject(ob).optJSONArray("Episodes")
                    val map = HashMap<Int, String>()
                    if (arr != null) for (x in 0 until arr.length()) {
                        val ep = arr.getJSONObject(x)
                        val n = ep.optString("Episode").toIntOrNull()
                        val r = ep.optString("imdbRating")
                        if (n != null && r.isNotBlank() && r != "N/A") map[n] = r
                    }
                    res = out.map { it.copy(imdb = map[it.num]) }
                }
            } catch (e: Exception) {
            }
        }
        if (res.isNotEmpty()) seasons[k] = res
        return res
    }
}
