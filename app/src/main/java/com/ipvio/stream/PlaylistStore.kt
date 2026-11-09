package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.io.Reader
import java.io.Writer
import java.nio.charset.Charset
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class PL(val name: String, val url: String)

/**
 * Playlists: Liste der gespeicherten Zugänge, Laden, Zwischenspeicher.
 *
 * Ablauf beim Laden (ausgelegt auf Anbieter mit 800.000+ Einträgen und Geräte mit wenig Speicher):
 * 1. Der Zugang wird VOR dem Download gemerkt (Absturz, Stromausfall, App geschlossen → beim nächsten Start geht es weiter).
 * 2. Download in eine Datei (pl_*.part), nicht in den Speicher. Erlaubt der Server es, wird ein abgebrochener Download fortgesetzt.
 * 3. Einlesen Eintrag für Eintrag. Ausgeblendete Sprachen werden nur gezählt. Ist die Liste für das Gerät zu groß und noch keine
 *    Sprache gewählt, wird gefragt, welche Sprachen geladen werden sollen.
 * 4. Gespeichert werden die gezeigten Titel (pl_*.bin, schneller Start) und die ganze Liste komprimiert (pl_*.m3u.gz, ~10 %),
 *    damit eine andere Sprachauswahl ohne neuen Download geht.
 */
object PlaylistStore {
    /**
     * Die geladenen Einträge – schon ohne die im Sprachfilter ausgeblendeten Sprachen.
     * Ausgeblendete Sprachen werden gar nicht erst in den Speicher geholt (große Anbieter: 283.000 → z. B. 57.000 Titel).
     */
    private var rawChannels by mutableStateOf<List<Item>>(emptyList())
    /**
     * Neue Liste übernehmen. [shown] = schon nach Jugendschutz gefiltert (im Hintergrund berechnet), sonst wird es hier gemacht –
     * so muss das Filtern (Regex über alle Titel) nicht beim Zeichnen im Hauptthread passieren.
     */
    private fun setItems(l: List<Item>, shown: List<Item>? = null) {
        LibMatch.reset()
        filtered = shown ?: shownOf(l); filteredFor = l; filteredVer = filterVer()
        rawChannels = l
    }

    /** Stand der Filter (Jugendschutz, „gestörte Titel ausblenden“): ändert er sich, wird die gezeigte Liste neu gebildet. */
    private fun filterVer(): Int = Parental.version * 10_000 + (if (DeadLinks.hide) DeadLinks.version else 0)

    private fun shownOf(l: List<Item>): List<Item> {
        var r = if (Parental.hideAdult) Parental.filter(l) else l
        if (DeadLinks.hide) r = DeadLinks.filter(r)
        return r
    }
    @Volatile private var filteredFor: List<Item>? = null
    @Volatile private var filteredVer = -1
    @Volatile private var filtered: List<Item> = emptyList()

    /** Anzahl Titel je Sprache in der ganzen Playlist (auch ausgeblendete) – für die Sprachauswahl in den Einstellungen. */
    var langCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** Datenmenge je Sprache der ganzen Playlist – für die Speicher-Schätzung ([fits]). */
    var langBytes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /** Mit welcher Sprachauswahl die angezeigten Titel eingelesen wurden. */
    @Volatile private var shownHidden: Set<String> = emptySet()

    /** Alle Einträge, wie sie die App zeigt: ohne ausgeblendete Sprachen und mit aktivem Jugendschutz ohne Erwachsenen-Inhalte. */
    val channels: List<Item>
        get() {
            val raw = rawChannels
            val ver = filterVer()
            if (!Parental.hideAdult && !DeadLinks.hide) return raw
            if (raw !== filteredFor || ver != filteredVer) {
                filtered = shownOf(raw); filteredFor = raw; filteredVer = ver
            }
            return filtered
        }

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** true, solange heruntergeladen oder eingelesen wird (nicht beim schnellen Lesen des Zwischenspeichers) – dann Ladebildschirm. */
    var heavy by mutableStateOf(false)
        private set
    /** Fortschritt für den Ladebildschirm: aktueller Schritt, verarbeitete Bytes und Gesamtgröße (-1 = unbekannt). */
    var phase by mutableStateOf("")
        private set
    var bytesRead by mutableLongStateOf(0L)
        private set
    var totalBytes by mutableLongStateOf(-1L)
        private set
    /** true, sobald Download und Einlesen fertig sind und nur noch gespeichert wird. */
    var downloadDone by mutableStateOf(false)
        private set
    /** Offene Rückfrage: welche Sprachen sollen geladen werden (sehr große Playlist)? Der Ladebildschirm zeigt dann die Auswahl. */
    var langAsk by mutableStateOf<LangAsk?>(null)
        private set
    /** Beginn des laufenden Downloads/Einlesens (für die Hinweise bei langer Ladezeit im Ladebildschirm), 0 = keiner. */
    var loadStarted by mutableLongStateOf(0L)
        private set
    /** Nutzer möchte während des Ladens doch Sprachen wählen: nach dem Download bzw. sofort beim Einlesen fragen. */
    var askRequested by mutableStateOf(false)
        private set
    var epgUrls: List<String> = emptyList()
    var catOrder by mutableStateOf<Map<String, Int>>(emptyMap())
    val lists = mutableStateListOf<PL>()
    var active by mutableIntStateOf(0)

    // ------------------------------------------------------------------ mehrere Playlists gleichzeitig (Runde 20)
    /** Weitere Playlists, die zusätzlich zur aktiven gezeigt werden (Schlüssel = Hash der Adresse, siehe [MultiLogic]). */
    var multi by mutableStateOf<Set<Int>>(emptySet())
        private set
    /** Zusammengeführte Liste aller gezeigten Quellen (mit den in „Alle“ ausgeblendeten Doppelten). */
    var merged by mutableStateOf(MultiLogic.Merged(emptyList(), emptySet(), emptyMap()))
        private set
    /** Namen der gezeigten Quellen: 0 = aktive Playlist, dann die weiteren. */
    var sourceNames by mutableStateOf<List<String>>(emptyList())
        private set
    /** Hinweis, wenn eine weitere Playlist nicht geladen werden konnte. */
    var extraError by mutableStateOf<String?>(null)
    private class Extra(val items: List<Item>, val epg: List<String>, val order: Map<String, Int>)
    private val extras = LinkedHashMap<String, Extra>()
    @Volatile private var primaryItems: List<Item> = emptyList()
    @Volatile private var primaryUrl: String? = null
    private var primaryEpg: List<String> = emptyList()
    private var primaryOrder: Map<String, Int> = emptyMap()
    private var epgSig = 0L
    private var inited = false
    private val loadLock = Mutex()
    @Volatile private var job: Job? = null
    /** Letztes Einlesen war zu groß: beim nächsten Laden mit Oberfläche die Sprachauswahl zeigen. */
    @Volatile private var askNext = false
    @Volatile private var call: Call? = null

    private val client = Net.withTimeouts(20, 120)

    private val XTREAM = Regex("""^(https?://[^/]+)/get\.php\?.*?username=([^&]+).*?password=([^&]+)""")
    private const val UA = "IPVIOStream/1.0"
    /** Eine fertig heruntergeladene, aber noch nicht eingelesene Liste wird so lange wiederverwendet. */
    private const val RAW_MAX_AGE = 12 * 3_600_000L
    /** Freier Platz, der nach dem Download mindestens bleiben soll. */
    private const val SPACE_RESERVE = 64L * 1024 * 1024

    const val MSG_CANCEL = "Laden abgebrochen. Deine Angaben bleiben gespeichert."
    const val MSG_TOO_BIG = "Die gewählten Inhalte sind zu groß für den Speicher dieses Geräts. Bitte weniger Sprachen wählen."

    /** Rückfrage bei sehr großen Playlists: welche Sprachen sollen geladen werden? */
    class LangAsk(val counts: Map<String, Int>, val bytes: Map<String, Long>, val preselect: Set<String>, val budget: Long) {
        internal val answer = CompletableDeferred<Set<String>?>()
        val titles: Int get() = counts.values.sum()
        fun titlesOf(shown: Set<String>): Int = counts.entries.sumOf { if (it.key in shown) it.value else 0 }
        fun estimate(shown: Set<String>): Long = LoadLogic.estimate(bytes, counts.keys - shown)
        fun fits(shown: Set<String>): Boolean = estimate(shown) <= budget
        /** Gewählte (gezeigte) Sprachen, null = abbrechen. */
        fun choose(shown: Set<String>?) { answer.complete(shown) }
        /** Überspringen: die ganze Playlist laden. */
        fun chooseAll() { answer.complete(counts.keys) }
    }

    /** Während des Ladens doch die Sprachauswahl zeigen (Hinweis nach 15 Minuten). */
    fun requestLanguageChoice() { askRequested = true }

    /** Wird beim Einlesen geworfen, wenn der Nutzer währenddessen die Sprachauswahl angefordert hat. */
    private class AskAgain : RuntimeException("Sprachauswahl")

    /** Sprache des Geräts (nicht der App), z. B. "de". */
    private fun systemLang(): String = try {
        android.content.res.Resources.getSystem().configuration.locales[0].language
    } catch (e: Exception) { java.util.Locale.getDefault().language }

    /** Wie viel Speicher die Playlist höchstens belegen soll. */
    fun budget(): Long = LoadLogic.budget(Runtime.getRuntime().maxMemory())

    /** Passt diese Sprachauswahl in den Speicher? (Schätzung aus dem letzten Einlesen; unbekannt = ja.) */
    fun fits(hidden: Set<String>): Boolean = LoadLogic.fits(langBytes, hidden, budget())

    /** Laufendes Laden abbrechen (zweimal Zurück im Ladebildschirm). Die Angaben bleiben gespeichert. */
    fun cancel() {
        error = MSG_CANCEL
        askRequested = false
        langAsk?.choose(null)
        runCatching { call?.cancel() }
        job?.cancel()
    }

    private fun cancelled() = job?.isActive == false

    private fun prefs(c: Context) = c.getSharedPreferences("ipvio", Context.MODE_PRIVATE)

    private fun ensure(c: Context) {
        if (inited) return
        inited = true
        cleanupStale(c)
        val p = prefs(c)
        // Bevorzugt die verschlüsselte Fassung; die alte Klartext-Fassung wird beim ersten Start übernommen und ersetzt.
        val enc = p.getString("lists_enc", null)
        val json = (if (enc != null) Secrets.decrypt(enc) else null) ?: p.getString("lists", "[]")
        try {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                lists.add(PL(o.getString("name"), o.getString("url")))
            }
        } catch (e: Exception) {
        }
        val old = p.getString("url", null)
        if (lists.isEmpty() && old != null) lists.add(PL(hostOf(old), old))
        active = p.getInt("active", 0).coerceIn(0, maxOf(lists.size - 1, 0))
        multi = MultiLogic.decodeKeys(p.getString("multi", null))
        if (enc == null && lists.isNotEmpty()) persist(c)   // Übernahme der alten Klartext-Liste (nach dem Setzen von "active")
    }

    /** Reste abgebrochener Downloads, die älter als zwei Tage sind, löschen (eine Rohdatei kann über 200 MB groß sein). */
    private fun cleanupStale(c: Context) {
        runCatching {
            val now = System.currentTimeMillis()
            c.filesDir.listFiles { f ->
                f.name.startsWith("pl_") && (f.name.endsWith(".m3u") || f.name.endsWith(".part") || f.name.endsWith(".info") || f.name.endsWith(".tmp"))
            }?.filter { now - it.lastModified() > 2 * 86_400_000L }?.forEach { it.delete() }
        }
    }

    private fun persist(c: Context) {
        val a = JSONArray()
        for (l in lists) a.put(JSONObject().put("name", l.name).put("url", l.url))
        val e = prefs(c).edit().putInt("active", active).putString("multi", MultiLogic.encodeKeys(multi)).remove("url")
        val token = Secrets.encrypt(a.toString())
        if (token != null) e.putString("lists_enc", token).remove("lists")
        else e.putString("lists", a.toString()).remove("lists_enc")
        e.apply()
    }

    // ------------------------------------------------------------------ gemerkter Ladeauftrag

    /** Die Playlist, die gerade hinzugefügt wird (oder beim letzten Mal nicht fertig wurde), sonst null. */
    fun pending(c: Context): LoadLogic.Pending? {
        val p = prefs(c)
        val enc = p.getString("pending_enc", null)
        return LoadLogic.Pending.decode((if (enc != null) Secrets.decrypt(enc) else null) ?: p.getString("pending", null))
    }

    private fun writePending(c: Context, v: LoadLogic.Pending?) {
        val e = prefs(c).edit()
        if (v == null) e.remove("pending_enc").remove("pending")
        else {
            val token = Secrets.encrypt(v.encode())
            if (token != null) e.putString("pending_enc", token).remove("pending") else e.putString("pending", v.encode()).remove("pending_enc")
        }
        e.commit()   // sofort auf die Platte: ein Absturz direkt danach darf nichts verlieren
    }

    fun clearPending(c: Context) = writePending(c, null)

    private fun failPending(c: Context, msg: String) {
        runCatching { pending(c)?.let { writePending(c, it.copy(state = LoadLogic.FAILED, msg = msg)) } }
    }

    /** Playlists für die Übertragung auf ein anderes Gerät (Klartext – nur über den Code-geschützten Weg). */
    fun exportLists(c: Context): JSONArray {
        ensure(c)
        val a = JSONArray()
        for (l in lists) a.put(JSONObject().put("name", l.name).put("url", l.url))
        return a
    }

    /** Übertragene Playlists ergänzen (vorhandene Adressen bleiben, wie sie sind). Ohne aktive Liste wird die erste aktiv. */
    fun importLists(c: Context, a: JSONArray) {
        ensure(c)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val u = PlaylistLogic.normalizeUrl(o.optString("url")) ?: continue
            if (u.startsWith("file://")) continue   // Dateien liegen nur auf dem alten Gerät
            if (lists.none { it.url == u }) lists.add(PL(o.optString("name").ifBlank { hostOf(u) }, u))
        }
        active = active.coerceIn(0, maxOf(lists.size - 1, 0))
        persist(c)
        // Sofort auf die Platte (die App startet gleich neu)
        prefs(c).edit().commit()
    }

    fun hostOf(url: String): String {
        if (url.startsWith("file://")) return url.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Playlist" }
        return try {
            val u = StalkerLogic.parse(url)?.portal ?: url
            android.net.Uri.parse(u).host ?: "Playlist"
        } catch (e: Exception) { "Playlist" }
    }

    fun savedUrl(c: Context): String? { ensure(c); return lists.getOrNull(active)?.url }

    /** Xtream-Adresse (Benutzer und Passwort URL-kodiert) oder null, wenn eine Angabe fehlt oder ungültig ist. */
    fun xtreamUrl(host: String, user: String, pass: String): String? = PlaylistLogic.xtreamUrl(host, user, pass)

    fun activate(c: Context, i: Int) { ensure(c); if (i in lists.indices) { active = i; persist(c) } }

    fun rename(c: Context, i: Int, name: String) {
        ensure(c)
        if (i in lists.indices && name.isNotBlank()) { lists[i] = PL(name.trim(), lists[i].url); persist(c) }
    }

    fun remove(c: Context, i: Int) {
        ensure(c)
        if (i !in lists.indices) return
        deleteFiles(c, lists[i].url)
        extras.remove(lists[i].url)
        multi = multi - key(lists[i].url)
        lists.removeAt(i)
        active = active.coerceIn(0, maxOf(lists.size - 1, 0))
        persist(c)
        if (lists.isEmpty()) setItems(emptyList())
    }

    private fun fetchOrder(url: String): Map<String, Int> {
        val m = XTREAM.find(url) ?: return emptyMap()
        val base = "${m.groupValues[1]}/player_api.php?username=${m.groupValues[2]}&password=${m.groupValues[3]}&action="
        val out = LinkedHashMap<String, Int>()
        val categoryActions = listOf("VOD" to "get_vod_categories", "SERIES" to "get_series_categories", "LIVE" to "get_live_categories")
        for ((kind, act) in categoryActions) {
            try {
                val body = client.newCall(Request.Builder().url(base + act).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                } ?: continue
                val a = JSONArray(body)
                for (i in 0 until a.length()) {
                    val n = a.getJSONObject(i).optString("category_name")
                    if (n.isNotBlank()) {
                        out["$kind|$n"] = i
                        if (!out.containsKey(n)) out[n] = i
                    }
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Dateien je Playlist

    private fun key(url: String) = url.trim().hashCode()
    private fun cacheFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".bin")
    private fun gzFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".m3u.gz")
    private fun rawFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".m3u")
    private fun partFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".part")
    private fun infoFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".info")
    private fun tmp(f: File) = File(f.path + ".tmp")

    private fun deleteFiles(c: Context, url: String) {
        if (url.startsWith("file://")) runCatching { File(java.net.URI(url)).takeIf { it.path.contains("/imports/") }?.delete() }
        val main = listOf(cacheFile(c, url), gzFile(c, url), rawFile(c, url), partFile(c, url), infoFile(c, url), Catchup.file(c, url))
        (main + main.map { tmp(it) }).forEach { runCatching { it.delete() } }
    }

    private fun writeCache(
        c: Context, url: String, list: List<Item>, epg: List<String>, order: Map<String, Int>,
        counts: Map<String, Int>, bytes: Map<String, Long>, hidden: Set<String>
    ) {
        try {
            val f = cacheFile(c, url)
            val t = tmp(f)
            BufferedOutputStream(FileOutputStream(t), 1 shl 16).use { PlaylistCacheIO.write(it, url, list, epg, order, counts, bytes, hidden) }
            f.delete()
            t.renameTo(f)
        } catch (e: Exception) {
        }
    }

    private fun readHeader(c: Context, url: String): PlaylistCacheIO.Cached? {
        val f = cacheFile(c, url)
        if (!f.exists()) return null
        return try {
            BufferedInputStream(FileInputStream(f), 1 shl 12).use { PlaylistCacheIO.readHeader(it, url) }
        } catch (e: Exception) {
            runCatching { f.delete() }
            null
        }
    }

    private fun readCache(c: Context, url: String, hidden: Set<String>): PlaylistCacheIO.Cached? {
        val f = cacheFile(c, url)
        if (!f.exists()) return null
        return try {
            BufferedInputStream(FileInputStream(f), 1 shl 16).use { PlaylistCacheIO.read(it, url, hidden, guard(askable = false)) }
        } catch (e: ParseStop) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { f.delete() }
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun sig(l: List<Item>): Long {
        var s = l.size.toLong()
        for (i in l) s = s * 31 + i.id.hashCode()
        return s
    }

    /**
     * Wache für lange Lesevorgänge (alle 2.000 Einträge): Abbruch durch den Nutzer, und Stopp, bevor der Speicher ausgeht.
     * Ein kontrollierter Abbruch mit Hinweis ist besser als ein Absturz mitten im Einlesen.
     */
    private fun guard(askable: Boolean = true): () -> Unit {
        val j = job
        return {
            if (j != null && !j.isActive) throw CancellationException("abgebrochen")
            if (askable && askRequested) throw AskAgain()
            if (Mem.heapTight(0.85)) {
                System.gc()
                if (Mem.heapTight(0.85)) throw ParseStop("Speicher")
            }
        }
    }

    // ------------------------------------------------------------------ Download

    /** Lädt die Liste in eine Datei, mit bis zu zwei neuen Versuchen bei Verbindungsabbruch (fortgesetzt, wenn der Server es erlaubt). */
    private suspend fun downloadWithRetry(c: Context, u: String): Pair<File, Charset> {
        var last: IOException? = null
        for (attempt in 0 until 3) {
            try {
                return download(c, u)
            } catch (e: IOException) {
                if (cancelled()) throw CancellationException("abgebrochen")
                if (!PlaylistLogic.retryable(e)) throw e
                last = e
                if (attempt < 2) { phase = "Verbindung unterbrochen – neuer Versuch …"; delay(3000) }
            }
        }
        throw last ?: IOException("Download")
    }

    private fun charsetOf(name: String?): Charset = try { Charset.forName(name ?: "UTF-8") } catch (e: Exception) { Charsets.UTF_8 }

    /**
     * Lädt die Liste in eine Datei – nicht in den Arbeitsspeicher. Gibt die fertige Datei und ihren Zeichensatz zurück.
     * Liegt noch eine fertig geladene, aber nicht eingelesene Datei vor (Absturz/Stromausfall beim Einlesen), wird sie verwendet.
     */
    private fun download(c: Context, u: String): Pair<File, Charset> {
        // Hochgeladene Datei (Handy-Seite oder Dateiauswahl): liegt schon auf dem Gerät
        if (u.startsWith("file://")) {
            val f = File(java.net.URI(u))
            if (!f.exists() || f.length() == 0L) throw PlaylistError("Die Playlist-Datei ist nicht mehr da. Bitte erneut hochladen.")
            phase = "Playlist wird gelesen …"
            return f to Charsets.UTF_8
        }
        StalkerLogic.parse(u)?.let { return downloadStalker(c, u, it) }
        val acct = XtreamLogic.fromUrl(u) ?: return downloadList(c, u)
        // Hat get.php bei diesem Zugang schon einmal versagt, gleich die Schnittstelle nehmen
        if (useApi(c, u)) return downloadApi(c, u, acct)
        val got = try {
            downloadList(c, u)
        } catch (e: PlaylistError) {
            return apiInstead(c, u, acct, e)
        }
        val head = runCatching { got.first.inputStream().use { s -> val b = ByteArray(512); val k = s.read(b); if (k > 0) String(b, 0, k, got.second) else "" } }.getOrDefault("")
        if (XtreamLogic.looksLikeM3u(head)) return got
        // 200, aber keine Liste (Fehlerseite, leere Antwort): Schnittstelle versuchen
        got.first.delete(); infoFile(c, u).delete()
        return apiInstead(c, u, acct, PlaylistError("Der Server hat keine gültige Playlist geliefert."))
    }

    private fun apiKey(u: String) = "xt_api_" + key(u)
    private fun useApi(c: Context, u: String) = prefs(c).getBoolean(apiKey(u), false)

    /** get.php hat versagt: über player_api.php laden und das für diesen Zugang merken. Scheitert auch das, zählt der klarere Fehler. */
    private fun apiInstead(c: Context, u: String, acct: XtreamLogic.Acct, first: PlaylistError): Pair<File, Charset> {
        if (cancelled()) throw CancellationException("abgebrochen")
        return try {
            val r = downloadApi(c, u, acct)
            prefs(c).edit().putBoolean(apiKey(u), true).apply()
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlaylistError) {
            throw if (e.message?.startsWith("Zugang abgelehnt") == true) e else first
        } catch (e: IOException) {
            throw first
        } catch (e: Exception) {
            throw first
        }
    }

    /** Schnittstellen-Zugriff für die Folgen einer Serie, die erst beim Öffnen geladen werden ([LazySeries]). */
    internal fun xtreamApi(): XtreamApi = XtreamApi(client, UA)

    /** Liste über die Schnittstelle des Anbieters aufbauen (siehe [XtreamApi]) und wie einen fertigen Download ablegen. */
    private fun downloadApi(c: Context, u: String, acct: XtreamLogic.Acct): Pair<File, Charset> {
        val raw = rawFile(c, u)
        val info = infoFile(c, u)
        val part = partFile(c, u)
        val pi = LoadLogic.PartInfo.decode(runCatching { info.readText() }.getOrNull())?.takeIf { it.urlHash == key(u) }
        if (pi != null && raw.exists() && raw.length() > 0 && System.currentTimeMillis() - raw.lastModified() < RAW_MAX_AGE) {
            return raw to Charsets.UTF_8
        }
        raw.delete(); part.delete()
        bytesRead = 0L; totalBytes = -1L; downloadDone = false
        val api = XtreamApi(client, UA)
        api.onPhase = { phase = it }
        api.check = { if (cancelled()) throw CancellationException("abgebrochen") }
        val counter = CountingOut(FileOutputStream(part)) { bytesRead = it }
        try {
            val n = OutputStreamWriter(BufferedOutputStream(counter, 1 shl 16), Charsets.UTF_8).use { w -> api.write(acct, w) }
            if (n == 0) throw PlaylistError("Keine Sender in der Playlist gefunden.")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        info.writeText(LoadLogic.PartInfo(key(u), null, null, "UTF-8").encode())
        if (!part.renameTo(raw)) throw IOException("Datei konnte nicht umbenannt werden")
        return raw to Charsets.UTF_8
    }

    /** Stalker-Portal: Liste über die Schnittstelle des Portals aufbauen (siehe [StalkerApi]). */
    private fun downloadStalker(c: Context, u: String, src: StalkerLogic.Source): Pair<File, Charset> {
        val raw = rawFile(c, u)
        val info = infoFile(c, u)
        val part = partFile(c, u)
        val pi = LoadLogic.PartInfo.decode(runCatching { info.readText() }.getOrNull())?.takeIf { it.urlHash == key(u) }
        if (pi != null && raw.exists() && raw.length() > 0 && System.currentTimeMillis() - raw.lastModified() < RAW_MAX_AGE) return raw to Charsets.UTF_8
        raw.delete(); part.delete()
        bytesRead = 0L; totalBytes = -1L; downloadDone = false
        val counter = CountingOut(FileOutputStream(part)) { bytesRead = it }
        try {
            OutputStreamWriter(BufferedOutputStream(counter, 1 shl 16), Charsets.UTF_8).use { w ->
                StalkerApi.write(src, w, { phase = it }, { if (cancelled()) throw CancellationException("abgebrochen") })
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        info.writeText(LoadLogic.PartInfo(key(u), null, null, "UTF-8").encode())
        if (!part.renameTo(raw)) throw IOException("Datei konnte nicht umbenannt werden")
        return raw to Charsets.UTF_8
    }

    private fun downloadList(c: Context, u: String): Pair<File, Charset> {
        val raw = rawFile(c, u)
        val info = infoFile(c, u)
        val part = partFile(c, u)
        val pi = LoadLogic.PartInfo.decode(runCatching { info.readText() }.getOrNull())?.takeIf { it.urlHash == key(u) }
        if (pi != null && raw.exists() && raw.length() > 0 && System.currentTimeMillis() - raw.lastModified() < RAW_MAX_AGE) {
            return raw to charsetOf(pi.charset)
        }
        raw.delete()
        var offset = if (pi != null && part.exists()) part.length() else 0L
        // Nach einem Stromausfall können die letzten geschriebenen Bytes fehlen oder unsauber sein: ein Stück zurückgehen
        if (offset > 0) { offset = maxOf(0L, offset - 65_536); RandomAccessFile(part, "rw").use { it.setLength(offset) } }
        val extra = if (pi != null) LoadLogic.resumeHeaders(offset, pi.etag, pi.lastModified) else emptyMap()
        if (extra.isEmpty()) offset = 0L
        val rb = Request.Builder().url(u).header("User-Agent", UA)
        extra.forEach { (k, v) -> rb.header(k, v) }
        phase = if (offset > 0) "Download wird fortgesetzt …" else "Verbindung zum Server wird aufgebaut …"
        bytesRead = offset; totalBytes = -1L; downloadDone = false
        val cl = client.newCall(rb.build())
        call = cl
        try {
            cl.execute().use { r ->
                var append = false
                when (LoadLogic.resumeAction(r.code, r.header("Content-Range"), offset)) {
                    LoadLogic.APPEND -> append = true
                    LoadLogic.RESTART -> offset = 0L
                    else -> {
                        if (offset > 0) { part.delete(); info.delete() }   // Fortsetzen ging schief: beim nächsten Versuch von vorn
                        throw PlaylistError(if (r.code == 206 || r.code == 416) "Download konnte nicht fortgesetzt werden. Bitte erneut versuchen." else PlaylistLogic.httpError(r.code))
                    }
                }
                val body = r.body ?: throw PlaylistError("Der Server hat keine Daten geliefert.")
                val len = body.contentLength()
                val total = when {
                    append && len >= 0 -> offset + len
                    append -> LoadLogic.rangeTotal(r.header("Content-Range"))
                    else -> len
                }
                totalBytes = total
                if (len > 0 && c.filesDir.usableSpace < len + SPACE_RESERVE) throw PlaylistError(PlaylistLogic.NO_SPACE)
                val cs = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                info.writeText(LoadLogic.PartInfo(key(u), r.header("ETag"), r.header("Last-Modified"), cs.name()).encode())
                phase = if (append) "Download wird fortgesetzt …" else "Playlist wird heruntergeladen …"
                FileOutputStream(part, append).use { fo ->
                    val out = BufferedOutputStream(fo, 1 shl 16)
                    val buf = ByteArray(1 shl 16)
                    val ins = body.byteStream()
                    var n = offset
                    var last = 0L
                    while (true) {
                        val k = ins.read(buf)
                        if (k < 0) break
                        out.write(buf, 0, k)
                        n += k
                        if (n - last >= 262_144) {
                            last = n; bytesRead = n
                            if (cancelled()) throw CancellationException("abgebrochen")
                        }
                    }
                    out.flush()
                    bytesRead = n
                }
                if (total > 0 && part.length() < total) throw IOException("Download unvollständig")
            }
        } finally {
            call = null
        }
        if (part.length() == 0L) throw PlaylistError("Der Server hat keine Daten geliefert.")
        raw.delete()
        if (!part.renameTo(raw)) throw IOException("Datei konnte nicht umbenannt werden")
        return raw to charsetOf(LoadLogic.PartInfo.decode(runCatching { info.readText() }.getOrNull())?.charset)
    }

    // ------------------------------------------------------------------ Einlesen

    private class Built(val parsed: ParsedPlaylist, val hidden: Set<String>)

    /** Liest eine Datei (heruntergeladen oder komprimiert gespeichert) mit Fortschritt; [teeTo] = gleichzeitig komprimiert speichern. */
    private fun <T> readFile(src: File, gz: Boolean, cs: Charset, teeTo: File?, body: (Reader) -> T): T {
        totalBytes = src.length(); bytesRead = 0L; downloadDone = false
        val fin = CountingStream(FileInputStream(src)) { bytesRead = it }
        val ins: InputStream = if (gz) GZIPInputStream(BufferedInputStream(fin, 1 shl 16), 1 shl 16) else BufferedInputStream(fin, 1 shl 16)
        var reader: Reader = InputStreamReader(ins, if (gz) Charsets.UTF_8 else cs)
        var w: Writer? = null
        try {
            if (teeTo != null) {
                w = OutputStreamWriter(FastGzip(FileOutputStream(teeTo)), Charsets.UTF_8)
                reader = TeeReader(reader, w)
            }
            val r = body(reader)
            w?.close()
            w = null
            return r
        } catch (e: Throwable) {
            runCatching { w?.close() }
            teeTo?.delete()
            throw e
        } finally {
            runCatching { reader.close() }
        }
    }

    /**
     * Liest eine Liste ein. Ist noch keine Sprache gewählt (und nicht bewusst „alles“) und die Liste für dieses Gerät groß,
     * wird zuerst schnell gezählt und dann gefragt ([ask]) – ohne Rückfrage (Hintergrund) wird abgebrochen.
     * Fordert der Nutzer währenddessen die Sprachauswahl an, wird neu gefragt und neu eingelesen.
     */
    private suspend fun build(c: Context, src: File, gz: Boolean, cs: Charset, ask: Boolean, teeTo: File?, forceAsk: Boolean = false): Built {
        var force = forceAsk
        while (true) {
            try {
                return buildOnce(c, src, gz, cs, ask, teeTo, force)
            } catch (e: AskAgain) {
                if (!ask) throw PlaylistError(MSG_TOO_BIG)
                force = true
            }
        }
    }

    private suspend fun buildOnce(c: Context, src: File, gz: Boolean, cs: Charset, ask: Boolean, teeTo: File?, forceAsk: Boolean): Built {
        var hidden = LangFilter.hidden
        val budget = budget()
        val wantAsk = ask && (forceAsk || askRequested)
        askRequested = false
        val unchosen = hidden.isEmpty() && !LangFilter.allChosen
        if (wantAsk || unchosen) {
            val size = if (gz) src.length() * 12 else src.length()
            if (wantAsk || size > budget) {
                phase = "Playlist wird geprüft …"
                val scan = readFile(src, gz, cs, null) { r -> M3uParser.scanStream(r, guard()) }
                if (wantAsk || LoadLogic.estimate(scan.bytes, hidden) > budget) {
                    if (!ask) throw PlaylistError(MSG_TOO_BIG)
                    val now = scan.counts.keys - hidden
                    val pre = if (hidden.isNotEmpty() && now.isNotEmpty()) now else LoadLogic.systemPreselect(scan.counts, systemLang())
                    val shown = askLanguages(scan.counts, scan.bytes, budget, pre) ?: throw PlaylistError(MSG_CANCEL)
                    hidden = scan.counts.keys - shown
                    LangFilter.set(c, hidden)
                    loadStarted = System.currentTimeMillis()
                }
            }
        }
        phase = "Titel werden eingelesen …"
        val parsed = readFile(src, gz, cs, teeTo) { r -> M3uParser.parseStream(r, hidden, guard()) }
        return Built(parsed, hidden)
    }

    private suspend fun askLanguages(counts: Map<String, Int>, bytes: Map<String, Long>, budget: Long, preselect: Set<String>): Set<String>? {
        val a = LangAsk(counts, bytes, preselect, budget)
        langAsk = a
        phase = "Sprachen auswählen"
        try {
            return a.answer.await()
        } finally {
            langAsk = null
        }
    }

    private fun epgFor(u: String, fromList: List<String>): List<String> {
        val urls = ArrayList(fromList)
        XTREAM.find(u)?.let { urls.add("${it.groupValues[1]}/xmltv.php?username=${it.groupValues[2]}&password=${it.groupValues[3]}") }
        return urls.distinct()
    }

    /** Die komprimierte Kopie ist fertig: übernehmen und die große Rohdatei löschen. */
    private fun commitGz(c: Context, u: String, gzTmp: File) {
        val gz = gzFile(c, u)
        if (gzTmp.exists() && gzTmp.length() > 0) { gz.delete(); gzTmp.renameTo(gz) }
        rawFile(c, u).delete(); infoFile(c, u).delete()
    }

    private fun show(
        c: Context, u: String, items: List<Item>, epg: List<String>, order: Map<String, Int>,
        counts: Map<String, Int>, bytes: Map<String, Long>, hidden: Set<String>
    ) {
        langCounts = counts
        langBytes = bytes
        shownHidden = hidden
        primaryItems = items; primaryUrl = u; primaryEpg = epg; primaryOrder = order
        rebuild(c)
    }

    /** Weitere gezeigte Playlists (ohne die aktive), in der Reihenfolge der Liste. */
    fun extraUrls(c: Context): List<String> {
        ensure(c)
        return lists.filterIndexed { i, pl -> i != active && key(pl.url) in multi }.map { it.url }
    }

    fun isShown(i: Int): Boolean = i == active || (i in lists.indices && key(lists[i].url) in multi)

    /** Position der Playlist [i] unter den gezeigten Quellen (0 = aktive, dann die weiteren in Listenreihenfolge), -1 = nicht gezeigt. */
    fun shownIndex(i: Int): Int {
        if (i !in lists.indices) return -1
        if (i == active) return 0
        if (!isShown(i)) return -1
        return 1 + lists.indices.count { it != active && it < i && isShown(it) }
    }

    /**
     * Playlist wechseln (Live TV, ohne Einstellungen): macht [i] zur aktiven Playlist und zeigt sofort den Zwischenspeicher
     * der neuen Playlist; danach wird vom Server erneuert. Die alte Anzeige wird verworfen, sonst bliebe sie sichtbar.
     */
    suspend fun switchTo(c: Context, i: Int) {
        ensure(c)
        if (i !in lists.indices) return
        if (loadLock.isLocked) return   // es läuft gerade ein Laden: nicht dazwischenfunken
        activate(c, i)
        primaryItems = emptyList(); primaryUrl = null
        merged = MultiLogic.Merged(emptyList(), emptySet(), emptyMap()); sourceNames = emptyList()
        setItems(emptyList())
        load(c, lists[i].url, false)
    }

    /** Playlist zusätzlich zeigen (oder nicht mehr). Danach [applyMulti] aufrufen. */
    fun setShown(c: Context, i: Int, on: Boolean) {
        ensure(c)
        if (i !in lists.indices || i == active) return
        val k = key(lists[i].url)
        multi = if (on) multi + k else multi - k
        persist(c)
    }

    /** Liste aus der aktiven und den geladenen weiteren Playlists bilden und anzeigen (Hintergrund-Thread). */
    private fun rebuild(c: Context) {
        val u = primaryUrl ?: return
        val urls = extraUrls(c)
        extras.keys.retainAll(urls.toSet())
        val ex = urls.mapNotNull { url -> extras[url]?.let { url to it } }
        val names = listOf(nameOf(u)) + ex.map { nameOf(it.first) }
        val m = MultiLogic.merge(listOf(primaryItems) + ex.map { it.second.items }, names)
        val vis = m.visible()
        val epg = (primaryEpg + ex.flatMap { it.second.epg }).distinct()
        twins = if (m.shadow.isEmpty()) emptyMap() else HashMap<String, Item>().also { t -> for (it in m.items) if (it.id in m.shadow) t.putIfAbsent("${it.src}|" + it.mk, it) }
        merged = m
        sourceNames = names
        epgUrls = epg
        catOrder = MultiLogic.mergeOrder(listOf(primaryOrder) + ex.map { it.second.order })
        setItems(vis, shownOf(vis))
        Catchup.startAll(c, listOf(u to primaryItems) + ex.map { it.first to it.second.items })
        // TV-Programm nur neu holen, wenn sich Adressen oder Sender geändert haben
        val es = epg.hashCode().toLong() * 31 + sig(vis.filter { it.kind == Kind.LIVE })
        if (es != epgSig) { epgSig = es; Xmltv.start(c, epg, vis) }
    }

    private fun restartCatchup(c: Context) {
        val u = primaryUrl ?: return
        Catchup.startAll(c, listOf(u to primaryItems) + extraUrls(c).mapNotNull { url -> extras[url]?.let { url to it.items } })
    }

    private fun nameOf(u: String): String = lists.firstOrNull { it.url == u }?.name ?: hostOf(u)

    @Volatile private var twins: Map<String, Item> = emptyMap()

    /** Gleicher Film aus Quelle [k], der in „Alle“ ausgeblendet ist (für den Quellen-Filter), sonst null. */
    fun twin(i: Item, k: Int): Item? = if (i.kind == Kind.MOVIE) twins["$k|" + i.mk] else null

    /** Gehört der Titel zur Quelle [k]? */
    fun inSource(i: Item, k: Int): Boolean = merged.inSource(i, k)

    /** Titel einer Quelle (0 = aktive Playlist), mit Jugendschutz-Filter. */
    fun bySource(k: Int): List<Item> {
        val m = merged
        return shownOf(m.items.filter { m.inSource(it, k) })
    }

    /** Weitere Playlists laden: [network] = vom Server (sonst aus dem Zwischenspeicher, nur fehlende vom Server). */
    private suspend fun loadExtras(c: Context, network: Boolean) {
        val urls = extraUrls(c)
        extraError = null
        if (urls.isEmpty()) { if (extras.isNotEmpty()) { extras.clear(); rebuild(c) }; return }
        heavy = false
        var changed = false
        for (url in urls) {
            if (!network && extras.containsKey(url)) continue
            if (cancelled()) throw CancellationException("abgebrochen")
            if (Mem.heapTight(0.6)) { extraError = "Zu wenig Speicher für eine weitere Playlist auf diesem Gerät."; break }
            phase = "Weitere Playlist wird geladen: " + nameOf(url)
            val r = try {
                loadExtra(c, url, network)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Server nicht erreichbar: alten Stand nehmen, wenn es einen gibt
                val old = if (network) runCatching { loadExtra(c, url, false) }.getOrNull() else null
                if (old == null) extraError = nameOf(url) + ": " + (if (e is ParseStop) MSG_TOO_BIG else PlaylistLogic.friendlyError(e))
                old
            } catch (e: OutOfMemoryError) {
                extraError = nameOf(url) + ": " + MSG_TOO_BIG
                null
            }
            if (r != null) { extras[url] = r; changed = true }
        }
        if (changed || extras.keys.any { it !in urls }) rebuild(c)
    }

    private suspend fun loadExtra(c: Context, u: String, network: Boolean): Extra? {
        val hidden = LangFilter.hidden
        if (!network) {
            val head = readHeader(c, u)
            if (head != null && (head.hidden == null || head.hidden == hidden)) {
                readCache(c, u, hidden)?.let { if (it.items.isNotEmpty()) return Extra(it.items, it.epg, it.order) }
            }
            val gz = gzFile(c, u)
            if (gz.exists()) {
                val b = build(c, gz, true, Charsets.UTF_8, false, null)
                val p = b.parsed
                if (p.items.isNotEmpty()) {
                    val epg = head?.epg?.takeIf { it.isNotEmpty() } ?: epgFor(u, p.epgUrls)
                    val order = head?.order ?: emptyMap()
                    writeCache(c, u, p.items, epg, order, p.counts, p.bytes, b.hidden)
                    Catchup.saveParsed(c, u, p.catchup)
                    return Extra(p.items, epg, order)
                }
            }
        }
        val (src, cs) = downloadWithRetry(c, u)
        val gzTmp = tmp(gzFile(c, u))
        val b = build(c, src, false, cs, false, gzTmp)
        val p = b.parsed
        if (p.counts.isEmpty()) throw PlaylistError("Keine Sender in der Playlist gefunden.")
        val urls = epgFor(u, p.epgUrls)
        val order = fetchOrder(u)
        writeCache(c, u, p.items, urls, order, p.counts, p.bytes, b.hidden)
        Catchup.saveParsed(c, u, p.catchup)
        commitGz(c, u, gzTmp)
        return Extra(p.items, urls, order)
    }

    /**
     * Auswahl „gleichzeitig zeigen“ geändert: fehlende Playlists laden (Zwischenspeicher, sonst Server) und neu zusammenführen.
     * false = gerade beschäftigt.
     */
    suspend fun applyMulti(c: Context): Boolean {
        ensure(c)
        if (primaryUrl == null) return true
        if (!loadLock.tryLock()) return false
        try {
            job = currentCoroutineContext()[Job]
            loading = true; error = null
            withContext(Dispatchers.IO) { loadExtras(c, network = false) }
            return true
        } finally {
            loading = false; heavy = false; job = null
            loadLock.unlock()
        }
    }

    private enum class Local { OK, NONE, FAILED }

    /**
     * Ohne Netz laden: aus dem Zwischenspeicher, wenn er zur aktuellen Sprachauswahl passt, sonst aus der komprimiert
     * gespeicherten ganzen Liste neu einlesen ([allowGz]).
     */
    private suspend fun fromLocal(c: Context, u: String, allowGz: Boolean, ask: Boolean): Local {
        val hidden = LangFilter.hidden
        val head = readHeader(c, u)
        if (head != null) {
            // Alte Fassung (ganze Liste ohne Sprach-Zahlen) bei sehr großer Datei und ohne Sprachauswahl: nicht alles in den Speicher holen
            val legacyTooBig = head.hidden == null && hidden.isEmpty() && cacheFile(c, u).length() > budget()
            if (!legacyTooBig && (head.hidden == null || head.hidden == hidden)) {
                val cached = readCache(c, u, hidden)
                if (cached != null && cached.counts.isNotEmpty()) {
                    show(c, u, cached.items, cached.epg, cached.order, cached.counts, cached.bytes, hidden)
                    return Local.OK
                }
            }
        }
        val gz = gzFile(c, u)
        if (!allowGz || !gz.exists()) return Local.NONE
        heavy = true
        if (loadStarted == 0L) loadStarted = System.currentTimeMillis()
        return try {
            val b = build(c, gz, true, Charsets.UTF_8, ask, null)
            val p = b.parsed
            if (p.counts.isEmpty()) return Local.NONE
            val epg = head?.epg?.takeIf { it.isNotEmpty() } ?: epgFor(u, p.epgUrls)
            val order = head?.order ?: emptyMap()
            phase = "Playlist wird gespeichert …"; downloadDone = true
            writeCache(c, u, p.items, epg, order, p.counts, p.bytes, b.hidden)
            Catchup.saveParsed(c, u, p.catchup)
            show(c, u, p.items, epg, order, p.counts, p.bytes, b.hidden)
            Local.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelled()) throw CancellationException("abgebrochen")
            error = if (e is ParseStop) MSG_TOO_BIG else PlaylistLogic.friendlyError(e)
            Local.FAILED
        } catch (e: OutOfMemoryError) {
            error = MSG_TOO_BIG
            Local.FAILED
        }
    }

    /**
     * Lädt eine Playlist. [save] = neu hinzugefügt (wird erst bei Erfolg in die Liste übernommen, aber vorher gemerkt),
     * [ask] = bei sehr großen Listen nach den Sprachen fragen dürfen (nicht im Hintergrund), [resumed] = automatisch fortgesetzt.
     */
    suspend fun load(
        c: Context, url: String, save: Boolean, skipNetworkWhenCached: Boolean = false,
        ask: Boolean = true, resumed: Boolean = false
    ): Boolean {
        if (!loadLock.tryLock()) return rawChannels.isNotEmpty()
        try {
            return loadInner(c, url, save, skipNetworkWhenCached, ask, resumed)
        } finally {
            loading = false   // auch bei Abbruch: sonst bliebe der Ladezustand (und die Bildschirm-Wachhaltung) hängen
            heavy = false
            langAsk = null
            loadStarted = 0L
            askRequested = false
            job = null
            loadLock.unlock()
        }
    }

    private suspend fun loadInner(c: Context, url: String, save: Boolean, skipNetworkWhenCached: Boolean, ask: Boolean, resumed: Boolean): Boolean {
        ensure(c)
        val u = PlaylistLogic.normalizeUrl(url) ?: run { error = "Bitte eine gültige Adresse eingeben (beginnt mit http:// oder https://)."; return false }
        job = currentCoroutineContext()[Job]
        loading = true; error = null; heavy = false
        phase = "Gespeicherte Playlist wird gelesen …"; bytesRead = 0L; totalBytes = -1L; downloadDone = false
        var forceAsk = false
        if (save) {
            val prev = pending(c)
            // Letzter Versuch abgestürzt oder zu groß, jetzt von Hand neu gestartet: bei großer Liste die Sprachen noch einmal zeigen
            forceAsk = !resumed && prev != null && prev.url == u && (prev.interrupted || prev.msg == MSG_TOO_BIG)
            writePending(c, LoadLogic.started(prev, u, resumed))
        }
        if (askNext && ask) forceAsk = true
        var usedCache = false
        if (rawChannels.isEmpty()) {
            val r = withContext(Dispatchers.IO) { fromLocal(c, u, allowGz = skipNetworkWhenCached, ask = ask) }
            usedCache = r == Local.OK
        }
        // Weitere gleichzeitig gezeigte Playlists gleich aus dem Zwischenspeicher dazunehmen (schneller Start)
        if (usedCache) withContext(Dispatchers.IO) { runCatching { loadExtras(c, network = false) }.onFailure { if (it is CancellationException) throw it } }
        if (usedCache && skipNetworkWhenCached) {
            if (save) finishSave(c, u)
            return true
        }
        error = null
        val before = sig(primaryItems)
        heavy = true
        if (loadStarted == 0L) loadStarted = System.currentTimeMillis()
        val ok = withContext(Dispatchers.IO) {
            try {
                val (src, cs) = downloadWithRetry(c, u)
                val gzTmp = tmp(gzFile(c, u))
                val b = build(c, src, false, cs, ask, gzTmp, forceAsk)
                val parsed = b.parsed
                if (parsed.counts.isEmpty()) throw PlaylistError("Keine Sender in der Playlist gefunden.")
                downloadDone = true
                val urls = epgFor(u, parsed.epgUrls)
                phase = "Kategorien werden sortiert …"
                val order = fetchOrder(u)
                phase = "Playlist wird gespeichert …"
                writeCache(c, u, parsed.items, urls, order, parsed.counts, parsed.bytes, b.hidden)
                Catchup.saveParsed(c, u, parsed.catchup)
                commitGz(c, u, gzTmp)
                val list = parsed.items
                if (sig(list) != before || !usedCache || b.hidden != shownHidden) show(c, u, list, urls, order, parsed.counts, parsed.bytes, b.hidden)
                else { primaryEpg = urls; primaryOrder = order; langCounts = parsed.counts; langBytes = parsed.bytes; restartCatchup(c) }
                // Weitere gezeigte Playlists ebenfalls vom Server erneuern
                loadExtras(c, network = true)
                if (save) finishSave(c, u)
                askNext = false
                true
            } catch (e: CancellationException) {
                if (save) failPending(c, MSG_CANCEL)
                throw e
            } catch (e: Exception) {
                if (cancelled()) { if (save) failPending(c, MSG_CANCEL); throw CancellationException("abgebrochen") }
                val msg = if (e is ParseStop) MSG_TOO_BIG else PlaylistLogic.friendlyError(e)
                if (e is ParseStop) askNext = true
                if (rawChannels.isEmpty() || save) error = msg
                if (save) failPending(c, msg)
                usedCache
            } catch (e: OutOfMemoryError) {
                askNext = true
                if (save) failPending(c, MSG_TOO_BIG)
                if (rawChannels.isEmpty() || save) error = MSG_TOO_BIG
                usedCache
            }
        }
        loading = false
        return ok
    }

    /** Neu hinzugefügte Playlist erfolgreich geladen: in die Liste übernehmen und den gemerkten Auftrag löschen. */
    private fun finishSave(c: Context, u: String) {
        val idx = lists.indexOfFirst { it.url == u }
        if (idx >= 0) active = idx else { lists.add(PL(hostOf(u), u)); active = lists.size - 1 }
        persist(c)
        clearPending(c)
    }

    /**
     * Sprachfilter geändert: ohne Netz neu einlesen (aus der gespeicherten ganzen Liste, sonst aus einem alten Zwischenspeicher).
     * Gibt es beides nicht, wird normal geladen. Passt die neue Auswahl nicht in den Speicher, bleibt die alte aktiv.
     * Rückgabe false = gerade beschäftigt (später erneut versuchen), sonst true.
     */
    suspend fun reapplyLanguages(c: Context): Boolean {
        val u = savedUrl(c) ?: return true
        if (!loadLock.tryLock()) return false
        try {
            job = currentCoroutineContext()[Job]
            loading = true; error = null
            phase = "Sprachauswahl wird angewendet …"; bytesRead = 0L; totalBytes = -1L; downloadDone = false
            val prev = shownHidden
            // Wenig Speicher frei: alte Liste vorher freigeben, sonst lägen alte und neue Liste gleichzeitig im Speicher
            if (Mem.heapTight(0.45)) setItems(emptyList())
            val r = withContext(Dispatchers.IO) { fromLocal(c, u, allowGz = true, ask = false) }
            when (r) {
                // Weitere Playlists mit der neuen Sprachauswahl neu einlesen
                Local.OK -> { extras.clear(); withContext(Dispatchers.IO) { runCatching { loadExtras(c, network = false) }.onFailure { if (it is CancellationException) throw it } } }
                Local.NONE -> return loadInner(c, u, false, false, false, false).let { true }
                Local.FAILED -> {
                    // Neue Auswahl passt nicht: alte Auswahl wiederherstellen (und, falls schon freigegeben, wieder einlesen)
                    val msg = error
                    LangFilter.set(c, prev)
                    if (rawChannels.isEmpty()) withContext(Dispatchers.IO) { fromLocal(c, u, allowGz = true, ask = false) }
                    error = msg
                }
            }
            return true
        } finally {
            loading = false
            heavy = false
            loadStarted = 0L
            askRequested = false
            job = null
            loadLock.unlock()
        }
    }

    /**
     * Hintergrund-Aktualisierung bei geschlossener App: herunterladen, mit der gespeicherten Sprachauswahl einlesen und
     * den Zwischenspeicher schreiben. Es wird nicht gefragt; ist die Liste ohne Sprachauswahl zu groß, bleibt der alte Stand.
     */
    suspend fun refreshCacheOnly(c: Context, url: String): Boolean {
        ensure(c)
        val u = PlaylistLogic.normalizeUrl(url) ?: return false
        if (!loadLock.tryLock()) return false
        try {
            job = currentCoroutineContext()[Job]
            return withContext(Dispatchers.IO) {
                try {
                    val (src, cs) = downloadWithRetry(c, u)
                    val gzTmp = tmp(gzFile(c, u))
                    val b = build(c, src, false, cs, false, gzTmp)
                    if (b.parsed.counts.isEmpty()) return@withContext false
                    writeCache(c, u, b.parsed.items, epgFor(u, b.parsed.epgUrls), fetchOrder(u), b.parsed.counts, b.parsed.bytes, b.hidden)
                    Catchup.saveParsed(c, u, b.parsed.catchup)
                    commitGz(c, u, gzTmp)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dropRaw(c, u)
                    false
                } catch (e: OutOfMemoryError) {
                    dropRaw(c, u)
                    false
                }
            }
        } finally {
            job = null
            loadLock.unlock()
        }
    }

    /** Im Hintergrund nicht verwendbare, fertig geladene Rohdatei löschen (Platz); eine Teildatei bleibt zum Fortsetzen. */
    private fun dropRaw(c: Context, u: String) {
        if (rawFile(c, u).exists()) { rawFile(c, u).delete(); infoFile(c, u).delete() }
    }

    /** true, wenn die Oberfläche gerade eine Playlist anzeigt (App läuft im selben Prozess). */
    val hasData: Boolean get() = rawChannels.isNotEmpty()

    fun clear(c: Context) {
        ensure(c)
        lists.forEach { deleteFiles(c, it.url) }
        extras.clear(); multi = emptySet(); primaryItems = emptyList(); primaryUrl = null
        merged = MultiLogic.Merged(emptyList(), emptySet(), emptyMap()); sourceNames = emptyList()
        lists.clear(); persist(c); setItems(emptyList()); langCounts = emptyMap(); langBytes = emptyMap()
    }
}

/** Zählt die gelesenen Bytes (für den Fortschrittsbalken), meldet höchstens alle 128 KB. */
private class CountingStream(inp: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(inp) {
    private var n = 0L
    private var last = 0L
    override fun read(): Int { val b = super.read(); if (b >= 0) add(1); return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) add(r.toLong()); return r }
    private fun add(k: Long) { n += k; if (n - last >= 131072) { last = n; onCount(n) } }
    override fun close() { onCount(n); super.close() }
}

/** Zählt die geschriebenen Bytes (Fortschritt beim Aufbau über die Schnittstelle), meldet höchstens alle 256 KB. */
private class CountingOut(out: OutputStream, private val onCount: (Long) -> Unit) : java.io.FilterOutputStream(out) {
    private var n = 0L
    private var last = 0L
    override fun write(b: Int) { out.write(b); add(1) }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); add(len.toLong()) }
    private fun add(k: Long) { n += k; if (n - last >= 262_144) { last = n; onCount(n) } }
    override fun close() { onCount(n); super.close() }
}

/** Gibt alles Gelesene zusätzlich an [copy] weiter (Einlesen und komprimiert Speichern in einem Durchgang). */
private class TeeReader(private val inner: Reader, private val copy: Writer) : Reader() {
    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        val n = inner.read(cbuf, off, len)
        if (n > 0) copy.write(cbuf, off, n)
        return n
    }
    override fun close() = inner.close()
}

/** gzip mit schnellster Stufe: 230 MB → ~20 MB in gut einer Sekunde (Stufe 6 wäre kaum kleiner, aber doppelt so langsam). */
private class FastGzip(out: OutputStream) : GZIPOutputStream(BufferedOutputStream(out, 1 shl 16), 1 shl 16) {
    init { def.setLevel(Deflater.BEST_SPEED) }
}
