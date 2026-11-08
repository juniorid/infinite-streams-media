package com.ipvio.stream

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Stalker-/Ministra-Portale (Portal-Adresse + MAC-Adresse), Runde 20. Ohne Android, testbar.
 *
 * Gespeichert wird ein Zugang als „stalker:“ + Portal-Adresse + „#mac=“ + MAC (verschlüsselt wie alle Zugänge).
 * Die App baut daraus eine Liste im M3U-Format. Weil Portale die Abspiel-Adressen erst auf Anfrage und oft nur
 * kurz gültig herausgeben, steht in der Liste je Sender/Film eine eigene Adresse „stalker://QUELLE/live|movie/CMD“;
 * die echte Adresse holt der Player beim Start ([StalkerApi.resolve]).
 */
object StalkerLogic {
    data class Source(val portal: String, val mac: String) {
        /** Schlüssel der Quelle in den Abspiel-Adressen (Hash der gespeicherten Angabe, ohne die Angaben selbst). */
        val key: Int get() = encode().trim().hashCode()
        fun encode(): String = "stalker:" + portal + "#mac=" + mac
    }

    private val MAC = Regex("""^([0-9A-F]{2}[:-]){5}[0-9A-F]{2}$""")

    /** MAC in der üblichen Form „00:1A:79:AB:CD:EF“ oder null, wenn ungültig. */
    fun normMac(raw: String): String? {
        val t = raw.trim().uppercase().replace('-', ':')
        if (MAC.matches(t)) return t
        val hex = t.filter { it.isDigit() || it in 'A'..'F' }
        return if (hex.length == 12 && t.all { it.isDigit() || it in 'A'..'F' || it == ':' || it == ' ' }) hex.chunked(2).joinToString(":") else null
    }

    /** Zugang aus den Eingaben; null = Portal-Adresse oder MAC ungültig. */
    fun source(portalRaw: String, macRaw: String): Source? {
        var p = portalRaw.trim()
        if (p.isEmpty()) return null
        if (!p.startsWith("http://", true) && !p.startsWith("https://", true)) p = "http://$p"
        if (PlaylistLogic.normalizeUrl(p) == null) return null
        val m = normMac(macRaw) ?: return null
        return Source(p, m)
    }

    fun isSource(url: String): Boolean = url.trim().startsWith("stalker:", true) && !url.trim().startsWith("stalker://", true)

    fun parse(url: String): Source? {
        val u = url.trim()
        if (!isSource(u)) return null
        val body = u.substring("stalker:".length)
        val i = body.lastIndexOf("#mac=")
        if (i <= 0) return null
        return source(body.substring(0, i), body.substring(i + 5))
    }

    /** Mögliche Schnittstellen-Adressen eines Portals, in dieser Reihenfolge ausprobieren. */
    fun endpoints(portal: String): List<String> {
        val p = portal.trim().trimEnd('/')
        val base = when {
            p.endsWith("/c", true) -> p.dropLast(2)
            p.endsWith("/stalker_portal", true) -> p
            else -> p
        }.trimEnd('/')
        val out = LinkedHashSet<String>()
        if (base.endsWith("/stalker_portal", true)) out.add("$base/server/load.php")
        out.add("$base/portal.php")
        out.add("$base/server/load.php")
        out.add("$base/stalker_portal/server/load.php")
        if (p.endsWith(".php", true)) { out.clear(); out.add(p) }
        return out.toList()
    }

    /** Adresse der Logos (Portale geben oft nur den Dateinamen an). */
    fun logoUrl(portal: String, logo: String?): String? {
        val l = logo?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        if (l.startsWith("http://") || l.startsWith("https://")) return l
        val p = portal.trim().trimEnd('/')
        val base = if (p.endsWith("/c", true)) p.dropLast(2) else p
        return if (l.startsWith("/")) Regex("""^(https?://[^/]+)""").find(base)?.value?.plus(l) else "$base/misc/logos/320/$l"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun api(endpoint: String, type: String, action: String, extra: String = ""): String =
        "$endpoint?type=$type&action=$action$extra&JsHttpRequest=1-xml"

    fun createLink(endpoint: String, type: String, cmd: String): String =
        api(endpoint, type, "create_link", "&cmd=" + enc(cmd) + "&series=&forced_storage=undefined&disable_ad=0&download=0")

    /** „ffmpeg http://…“ / „ffrt http://…“ / „auto http://…“ → reine Adresse. */
    fun stripCmd(cmd: String?): String? {
        val c = cmd?.trim() ?: return null
        val i = c.indexOf("http")
        val u = if (i >= 0) c.substring(i).trim() else return null
        return u.substringBefore(' ').takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** Cookie wie bei einer MAG-Box. */
    fun cookie(mac: String, tz: String = "Europe/Berlin"): String = "mac=" + enc(mac) + "; stb_lang=en; timezone=" + enc(tz)

    const val UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    const val X_UA = "Model: MAG250; Link: WiFi"

    private fun hash(alg: String, s: String) = MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02X".format(it) }

    /** Geräte-Kennungen, die manche Portale beim Profil-Abruf erwarten (aus der MAC abgeleitet, immer gleich). */
    fun profileExtra(mac: String): String {
        val sn = hash("MD5", mac).take(13)
        val dev = hash("SHA-256", mac)
        return "&hd=1&stb_type=MAG250&client_type=STB&image_version=218&sn=$sn&device_id=$dev&device_id2=$dev&auth_second_step=1&not_valid_token=0"
    }

    // ------------------------------------------------------------------ Abspiel-Adressen in der Liste

    private fun hex(s: String): String = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    private fun unhex(h: String): String? {
        if (h.length % 2 != 0) return null
        val b = ByteArray(h.length / 2)
        for (i in b.indices) b[i] = (h.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        return String(b, Charsets.UTF_8)
    }

    /** „stalker://KEY/live/HEX.ts“ bzw. „…/movie/HEX.mp4“ – die Endung hilft beim Einordnen (Live/Film). */
    fun itemUrl(key: Int, kind: String, cmd: String): String = "stalker://$key/$kind/" + hex(cmd) + (if (kind == "movie") ".mp4" else ".ts")

    class ItemRef(val key: Int, val type: String, val cmd: String)

    fun itemRef(url: String): ItemRef? {
        if (!url.startsWith("stalker://")) return null
        val rest = url.removePrefix("stalker://").split('/')
        if (rest.size != 3) return null
        val key = rest[0].toIntOrNull() ?: return null
        val type = when (rest[1]) { "live" -> "itv"; "movie" -> "vod"; else -> return null }
        val cmd = unhex(rest[2].substringBefore('.')) ?: return null
        return ItemRef(key, type, cmd)
    }

    fun isItem(url: String): Boolean = url.startsWith("stalker://")

    private fun attr(s: String?) = (s ?: "").replace('"', '\'').replace('\n', ' ').trim()
    private fun name(s: String?) = (s ?: "").replace(',', '‚').replace('\n', ' ').trim()

    /** Ein Sender als zwei M3U-Zeilen. */
    fun liveEntry(src: Source, f: Map<String, String>, genres: Map<String, String>): String? {
        val cmd = f["cmd"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val n = name(f["name"]).ifEmpty { return null }
        val sb = StringBuilder("#EXTINF:-1")
        attr(f["xmltv_id"]).takeIf { it.isNotEmpty() }?.let { sb.append(" tvg-id=\"").append(it).append('"') }
        logoUrl(src.portal, f["logo"])?.let { sb.append(" tvg-logo=\"").append(attr(it)).append('"') }
        sb.append(" group-title=\"").append(attr(f["tv_genre_id"]?.let { genres[it] } ?: "Sonstige")).append('"')
        return sb.append(',').append(n).append('\n').append(itemUrl(src.key, "live", cmd)).toString()
    }

    /** Ein Film als zwei M3U-Zeilen. */
    fun movieEntry(src: Source, f: Map<String, String>, category: String): String? {
        val cmd = f["cmd"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var n = name(f["name"]).ifEmpty { return null }
        val y = f["year"]?.trim()?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        if (y != null && !n.contains("($y)")) n = "$n ($y)"
        val sb = StringBuilder("#EXTINF:-1")
        attr(f["screenshot_uri"]).takeIf { it.startsWith("http") }?.let { sb.append(" tvg-logo=\"").append(it).append('"') }
        sb.append(" group-title=\"").append(attr(category.ifBlank { "Filme" })).append('"')
        return sb.append(',').append(n).append('\n').append(itemUrl(src.key, "movie", cmd)).toString()
    }
}
