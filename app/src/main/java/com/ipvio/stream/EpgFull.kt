package com.ipvio.stream

object EpgFull {
    private val cache = LruCache<String, Pair<Long, List<EpgEntry>>>(300)
    fun trim() = cache.clear()

    /**
     * Programm eines Senders: ab 1 h zurück, bei Sendern mit Archiv (Catchup) so weit zurück, wie das Archiv reicht.
     * Zuerst Zwischenspeicher und geladenes XMLTV (ohne Netz), dann Xtream.
     */
    suspend fun get(item: Item): List<EpgEntry> {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        cache[item.id]?.let { if (nowMs - it.first < 600_000) return it.second }
        val days = Catchup.of(item)?.days ?: 0
        val from = if (days > 0) nowSec - days * 86400L else nowSec - 12 * 3600L
        val local = Xmltv.forChannel(item.tvgId).filter { it.end > from }
        // Mit Archiv reicht XMLTV nur, wenn es auch vergangene Sendungen enthält
        val localOk = local.size >= 3 && local.any { it.end > nowSec } && (days == 0 || local.first().start <= nowSec - 6 * 3600)
        if (localOk) return local
        val url = Epg.xtreamApi(item, "get_simple_data_table")
        if (url != null) {
            val body = EpgNet.fetch(url)
            if (body != null) {
                val out = Epg.parseListings(body) { _, e -> e > from }
                if (out.isNotEmpty()) { cache[item.id] = nowMs to out; return out }
            }
        }
        if (local.isNotEmpty()) return local
        return Epg.now(item)
    }

    /** Vergangene Sendungen im Archiv eines Senders, neueste zuerst. */
    suspend fun archive(item: Item): List<EpgEntry> {
        val info = Catchup.of(item) ?: return emptyList()
        return CatchupLogic.archived(info, get(item), System.currentTimeMillis() / 1000)
    }
}
