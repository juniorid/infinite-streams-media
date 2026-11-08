package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.util.concurrent.TimeUnit

/**
 * Verbindung zu einem Stalker-/Ministra-Portal (siehe [StalkerLogic]): Anmeldung mit der MAC-Adresse, Sender und
 * Filme als Liste, Abspiel-Adresse beim Start. Serien sind (noch) nicht dabei.
 */
object StalkerApi {
    private val client: OkHttpClient = Net.base.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** Angemeldete Sitzung: Schnittstelle und Token, ein paar Minuten wiederverwendet. */
    private class Session(val src: StalkerLogic.Source, val endpoint: String, val token: String, val at: Long)
    private val sessions = HashMap<Int, Session>()
    private const val SESSION_MS = 10 * 60_000L
    /** Höchstens so viele Filme (Portale liefern nur ~14 je Seite). */
    private const val MAX_MOVIES = 6000

    private fun req(src: StalkerLogic.Source, url: String, token: String?): Request {
        val b = Request.Builder().url(url)
            .header("User-Agent", StalkerLogic.UA)
            .header("X-User-Agent", StalkerLogic.X_UA)
            .header("Referer", src.portal)
            .header("Cookie", StalkerLogic.cookie(src.mac))
        if (token != null) b.header("Authorization", "Bearer $token")
        return b.build()
    }

    /** Antwort als „js“-Teil, null bei Fehler. */
    private fun js(src: StalkerLogic.Source, url: String, token: String?): Any? = try {
        client.newCall(req(src, url, token)).execute().use { r ->
            if (!r.isSuccessful) null
            else {
                val body = r.body?.string().orEmpty()
                val o = runCatching { JSONObject(body) }.getOrNull()
                o?.opt("js")
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Anmelden: Schnittstelle finden, Token holen, Profil abrufen (manche Portale schalten erst damit frei). */
    private fun login(src: StalkerLogic.Source, force: Boolean = false): Session {
        synchronized(sessions) {
            val s = sessions[src.key]
            if (!force && s != null && System.currentTimeMillis() - s.at < SESSION_MS) return s
        }
        var lastErr = "Das Portal antwortet nicht. Adresse prüfen."
        for (ep in StalkerLogic.endpoints(src.portal)) {
            val hs = js(src, StalkerLogic.api(ep, "stb", "handshake", "&token=&prehash=0"), null) as? JSONObject ?: continue
            val token = hs.optString("token").takeIf { it.isNotBlank() } ?: continue
            js(src, StalkerLogic.api(ep, "stb", "get_profile", StalkerLogic.profileExtra(src.mac)), token)
            lastErr = "Das Portal lehnt diese MAC-Adresse ab. MAC und Abo beim Anbieter prüfen."
            val s = Session(src, ep, token, System.currentTimeMillis())
            synchronized(sessions) { sessions[src.key] = s }
            return s
        }
        throw PlaylistError(lastErr)
    }

    private fun categories(s: Session, type: String, action: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val a = js(s.src, StalkerLogic.api(s.endpoint, type, action), s.token) as? JSONArray ?: return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.opt("id")?.toString().orEmpty()
            val t = o.optString("title").ifBlank { o.optString("name") }
            if (id.isNotEmpty() && t.isNotBlank()) out[id] = t.trim()
        }
        return out
    }

    private fun fields(o: JSONObject): Map<String, String> {
        val f = HashMap<String, String>()
        for (k in o.keys()) { val v = o.opt(k); if (v is String || v is Number) f[k] = v.toString() }
        return f
    }

    /**
     * Ganze Liste nach [out] schreiben (M3U). [onPhase] = Fortschritt, [check] wirft bei Abbruch.
     * Rückgabe: Anzahl Einträge.
     */
    fun write(src: StalkerLogic.Source, out: Writer, onPhase: (String) -> Unit, check: () -> Unit): Int {
        onPhase("Zugang wird geprüft …")
        val s = login(src, force = true)
        var n = 0
        out.write("#EXTM3U\n")
        onPhase("Sender werden geladen …")
        val genres = categories(s, "itv", "get_genres")
        val all = js(src, StalkerLogic.api(s.endpoint, "itv", "get_all_channels"), s.token)
        val data = (all as? JSONObject)?.optJSONArray("data") ?: (all as? JSONArray)
        if (data != null) for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            StalkerLogic.liveEntry(src, fields(o), genres)?.let { out.write(it); out.write("\n"); n++ }
            if (i % 1000 == 0) check()
        }
        onPhase("Filme werden geladen …")
        val cats = categories(s, "vod", "get_categories").filterKeys { it != "*" }
        var movies = 0
        loop@ for ((cid, cname) in cats) {
            var page = 1
            var pages = 1
            while (page <= pages && movies < MAX_MOVIES) {
                check()
                val r = js(src, StalkerLogic.api(s.endpoint, "vod", "get_ordered_list", "&category=$cid&p=$page&sortby=added"), s.token) as? JSONObject ?: break
                val total = r.optInt("total_items", 0)
                val per = r.optInt("max_page_items", 14).coerceAtLeast(1)
                pages = ((total + per - 1) / per).coerceAtMost(500)
                val a = r.optJSONArray("data") ?: break
                if (a.length() == 0) break
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    // Serien-Einträge im Film-Bereich überspringen (brauchen eine eigene Abfrage)
                    if (o.optString("is_series") == "1") continue
                    StalkerLogic.movieEntry(src, fields(o), cname)?.let { out.write(it); out.write("\n"); n++; movies++ }
                }
                if (page % 5 == 0) onPhase("Filme werden geladen ($movies) …")
                page++
            }
            if (movies >= MAX_MOVIES) break@loop
        }
        out.flush()
        if (n == 0) throw PlaylistError("Das Portal hat keine Sender geliefert. MAC-Adresse und Abo beim Anbieter prüfen.")
        return n
    }

    /** Echte Abspiel-Adresse zu „stalker://…“ (vom Portal frisch erzeugt). null = nicht möglich. */
    suspend fun resolve(c: Context, url: String): String? = withContext(Dispatchers.IO) {
        val ref = StalkerLogic.itemRef(url) ?: return@withContext null
        val src = PlaylistStore.lists.asSequence().mapNotNull { StalkerLogic.parse(it.url) }.firstOrNull { it.key == ref.key } ?: return@withContext null
        for (attempt in 0 until 2) {
            val s = runCatching { login(src, force = attempt > 0) }.getOrNull() ?: return@withContext null
            val r = js(src, StalkerLogic.createLink(s.endpoint, ref.type, ref.cmd), s.token) as? JSONObject
            val u = StalkerLogic.stripCmd(r?.optString("cmd"))
            if (u != null) return@withContext u
        }
        // Manche Portale liefern im Sender schon eine direkt abspielbare Adresse
        StalkerLogic.stripCmd(ref.cmd)?.takeIf { !it.contains("localhost") }
    }
}
