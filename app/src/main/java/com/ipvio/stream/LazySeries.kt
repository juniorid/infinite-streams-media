package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Serien mit Platzhalter (Runde 21): Beim Laden einer Xtream-Playlist über die Schnittstelle steht von jeder Serie nur ein
 * Eintrag in der Liste. Ihre Folgen holt die App hier, wenn die Serie geöffnet oder abgespielt wird – eine Abfrage statt
 * zehntausender beim Laden. Das Ergebnis wird für die Sitzung gemerkt.
 */
object LazySeries {
    private class Hit(val item: Item, val at: Long)

    private val done = ConcurrentHashMap<String, Hit>()
    private const val MAX_AGE = 30 * 60_000L

    fun isStub(i: Item): Boolean = XtreamLogic.isLazy(i)

    /** Die Serie mit ihren echten Folgen, falls schon geladen; sonst die Serie selbst. */
    fun full(i: Item): Item {
        if (!isStub(i)) return i
        val h = done[i.id] ?: return i
        return if (System.currentTimeMillis() - h.at < MAX_AGE) h.item else i
    }

    fun loaded(i: Item): Boolean = !isStub(i) || full(i) !== i

    /** Alles vergessen (neue Playlist geladen). */
    fun clear() = done.clear()

    /** Folgen laden. null = der Anbieter antwortet nicht (Netzwerk, Zugang). */
    suspend fun load(@Suppress("UNUSED_PARAMETER") ctx: Context, i: Item): Item? {
        val have = full(i)
        if (have !== i) return have
        val urls = XtreamLogic.lazyUrls(i)
        if (urls.isEmpty()) return i
        return withContext(Dispatchers.IO) {
            val api = PlaylistStore.xtreamApi()
            var eps: List<Episode>? = null
            for ((url, label) in urls) {
                val (acct, sid) = XtreamLogic.parseLazy(url) ?: continue
                val l = api.episodeList(acct, sid) ?: continue
                eps = if (eps == null) l else MultiLogic.mergeEpisodes(eps, l, label.ifBlank { "2" })
            }
            val e = eps ?: return@withContext null
            if (e.isEmpty()) return@withContext null
            val r = i.copy(episodes = e)
            done[i.id] = Hit(r, System.currentTimeMillis())
            r
        }
    }
}
