package com.ipvio.stream

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.Writer
import java.io.BufferedReader

/**
 * Lädt einen Xtream-Zugang über player_api.php und schreibt daraus eine Liste im get.php-Format (siehe [XtreamLogic]).
 * Große Antworten (zehntausende Filme) werden Eintrag für Eintrag gelesen und sofort geschrieben, nie ganz im Speicher.
 */
class XtreamApi(private val client: OkHttpClient, private val ua: String) {

    /** Fortschritt: Text für den Ladebildschirm. */
    var onPhase: (String) -> Unit = {}
    /** Wird regelmäßig aufgerufen und wirft bei Abbruch. */
    var check: () -> Unit = {}

    private fun req(url: String) = Request.Builder().url(url).header("User-Agent", ua).build()

    private fun text(url: String): String {
        client.newCall(req(url)).execute().use { r ->
            if (!r.isSuccessful) throw PlaylistError(PlaylistLogic.httpError(r.code))
            return r.body?.string() ?: ""
        }
    }

    /** Prüft den Zugang. Wirft bei abgelehntem Zugang eine verständliche Meldung. */
    fun verify(a: XtreamLogic.Acct) {
        val body = text(XtreamLogic.api(a))
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw PlaylistError("Die Schnittstelle des Anbieters antwortet nicht wie erwartet.")
        val ui = o.optJSONObject("user_info")
        val ok = XtreamLogic.authOk(ui?.opt("auth")?.toString(), ui?.optString("status"))
        if (ok == false) throw PlaylistError("Zugang abgelehnt. Benutzername, Passwort oder Abo prüfen.")
    }

    private fun categories(a: XtreamLogic.Acct, action: String): Map<String, String> {
        val out = HashMap<String, String>()
        val arr = runCatching { JSONArray(text(XtreamLogic.api(a, action))) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.opt("category_id")?.toString()?.trim().orEmpty()
            val n = o.optString("category_name").trim()
            if (id.isNotEmpty() && n.isNotEmpty()) out[id] = n
        }
        return out
    }

    /** Liest ein flaches JSON-Objekt (Texte und Zahlen; bei Listen nur den ersten Wert, z. B. „category_ids“). */
    private fun flat(r: JsonReader): Map<String, String> {
        val f = HashMap<String, String>(24)
        r.beginObject()
        while (r.hasNext()) {
            val n = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> f[n] = r.nextString()
                JsonToken.BOOLEAN -> f[n] = r.nextBoolean().toString()
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    var first: String? = null
                    while (r.hasNext()) {
                        if (first == null && (r.peek() == JsonToken.STRING || r.peek() == JsonToken.NUMBER)) first = r.nextString() else r.skipValue()
                    }
                    r.endArray()
                    if (first != null && !f.containsKey(n)) f[n] = first
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        // Manche Anbieter nennen die Kategorie nur in category_ids
        if (f["category_id"].isNullOrBlank()) f["category_ids"]?.let { f["category_id"] = it }
        return f
    }

    /** Geht eine (große) Liste Eintrag für Eintrag durch. Antwortet der Anbieter nicht mit einer Liste, passiert nichts. */
    private fun stream(url: String, each: (Map<String, String>) -> Unit) {
        client.newCall(req(url)).execute().use { r ->
            if (!r.isSuccessful) throw PlaylistError(PlaylistLogic.httpError(r.code))
            val body = r.body ?: return
            JsonReader(BufferedReader(body.charStream(), 1 shl 16)).use { jr ->
                jr.isLenient = true
                if (jr.peek() != JsonToken.BEGIN_ARRAY) return
                jr.beginArray()
                var k = 0
                while (jr.hasNext()) {
                    if (jr.peek() == JsonToken.BEGIN_OBJECT) each(flat(jr)) else jr.skipValue()
                    if (++k % 1000 == 0) check()
                }
                jr.endArray()
            }
        }
    }

    /**
     * Geht die Folgen einer Serie durch und meldet jede an [sink] (Folge als flache Felder, Staffel aus dem Schlüssel).
     * „episodes“ ist je nach Anbieter ein Objekt (Staffel → Liste) oder eine Liste. false = Fehler (nach einem zweiten Versuch).
     */
    private fun walkEpisodes(a: XtreamLogic.Acct, seriesId: String, sink: (Map<String, String>, Int?) -> Unit, reset: () -> Unit): Boolean {
        for (attempt in 0 until 2) {
            try {
                var ok = true
                client.newCall(req(XtreamLogic.api(a, "get_series_info", "&series_id=$seriesId"))).execute().use { r ->
                    if (!r.isSuccessful) { ok = false; return@use }
                    val body = r.body ?: run { ok = false; return@use }
                    JsonReader(body.charStream()).use { jr ->
                        jr.isLenient = true
                        if (jr.peek() != JsonToken.BEGIN_OBJECT) { ok = false; return@use }
                        jr.beginObject()
                        while (jr.hasNext()) {
                            if (jr.nextName() != "episodes") { jr.skipValue(); continue }
                            when (jr.peek()) {
                                JsonToken.BEGIN_OBJECT -> {
                                    jr.beginObject()
                                    while (jr.hasNext()) {
                                        val season = jr.nextName().trim().toIntOrNull()
                                        epList(jr, season, sink)
                                    }
                                    jr.endObject()
                                }
                                JsonToken.BEGIN_ARRAY -> epList(jr, null, sink)
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                    }
                }
                return ok
            } catch (e: IOException) {
                reset()
                if (attempt == 1) return false
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    private fun epList(jr: JsonReader, season: Int?, sink: (Map<String, String>, Int?) -> Unit) {
        if (jr.peek() != JsonToken.BEGIN_ARRAY) { jr.skipValue(); return }
        jr.beginArray()
        while (jr.hasNext()) {
            when (jr.peek()) {
                JsonToken.BEGIN_OBJECT -> sink(flat(jr), season)
                // Manche Anbieter verschachteln eine Ebene tiefer
                JsonToken.BEGIN_ARRAY -> epList(jr, season, sink)
                else -> jr.skipValue()
            }
        }
        jr.endArray()
    }

    /** Folgen einer Serie als fertige Zeilen; leer bei Fehler. */
    private fun episodes(a: XtreamLogic.Acct, s: XtreamLogic.Series): String {
        val sb = StringBuilder()
        val ok = walkEpisodes(a, s.id, { f, season -> XtreamLogic.episodeEntry(a, s, f, season)?.let { sb.append(it).append('\n') } }, { sb.setLength(0) })
        return if (ok) sb.toString() else ""
    }

    /**
     * Folgen einer Serie beim Öffnen laden (siehe [LazySeries]). null = Anbieter antwortet nicht; leere Liste = Serie ohne Folgen.
     * Mehrere Einträge mit gleicher Staffel/Folge (Doppelte beim Anbieter) werden zusammengefasst, die erste Adresse gilt.
     */
    fun episodeList(a: XtreamLogic.Acct, seriesId: String): List<Episode>? {
        val seen = HashSet<Long>()
        var out = ArrayList<Episode>()
        val ok = walkEpisodes(a, seriesId, { f, season ->
            XtreamLogic.episodeParts(a, f, season)?.let { (se, n, url) ->
                if (seen.add((se.toLong() shl 32) or n.toLong())) out.add(Episode(se, n, EpTitles.of(n), url, emptyList(), null))
            }
        }, { seen.clear(); out = ArrayList() })
        if (!ok) return null
        out.sortWith(compareBy({ it.season }, { it.num }))
        return out
    }

    /**
     * Schreibt die ganze Liste nach [out]. Rückgabe: Anzahl Einträge (Sender + Filme + Serien).
     * Von Serien kommt nur ein Platzhalter; die Folgen holt [LazySeries] erst beim Öffnen einer Serie.
     */
    fun write(a: XtreamLogic.Acct, out: Writer): Int {
        onPhase("Zugang wird geprüft …")
        verify(a)
        var n = 0
        out.write(XtreamLogic.header(a)); out.write("\n")

        onPhase("Sender werden geladen …")
        val liveCats = categories(a, "get_live_categories")
        stream(XtreamLogic.api(a, "get_live_streams")) { f ->
            XtreamLogic.liveEntry(a, f, liveCats)?.let { out.write(it); out.write("\n"); n++ }
        }
        check()

        onPhase("Filme werden geladen …")
        val vodCats = categories(a, "get_vod_categories")
        stream(XtreamLogic.api(a, "get_vod_streams")) { f ->
            XtreamLogic.movieEntry(a, f, vodCats)?.let { out.write(it); out.write("\n"); n++ }
        }
        check()

        onPhase("Serien werden geladen …")
        val serCats = categories(a, "get_series_categories")
        // Stream series placeholders directly to disk instead of retaining every Series object.
        // Episodes are fetched lazily only when a series is opened.
        stream(XtreamLogic.api(a, "get_series")) { f ->
            XtreamLogic.seriesOf(f, serCats)?.let { series ->
                out.write(XtreamLogic.lazyEntry(a, series)); out.write("\n"); n++
            }
        }
        out.flush()
        return n
    }

}
