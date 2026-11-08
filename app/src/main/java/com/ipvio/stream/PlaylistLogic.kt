package com.ipvio.stream

import java.net.URLEncoder

/** Eigene Fehlermeldung beim Laden der Playlist (Text ist für den Nutzer bestimmt und enthält nie die Adresse). */
class PlaylistError(msg: String) : Exception(msg)

/** Prüfung von Playlist-Eingaben und verständliche Fehlertexte (ohne Android, testbar). */
object PlaylistLogic {
    /** Erstes Element je Schlüssel, Reihenfolge bleibt (z. B. derselbe Film in HD und 4K nur einmal in den Reihen). */
    fun <T> firstPerKey(list: List<T>, key: (T) -> String): List<T> {
        val seen = HashSet<String>(list.size * 2)
        val out = ArrayList<T>(list.size)
        for (x in list) if (seen.add(key(x))) out.add(x)
        return out
    }

    private val HTTP_URL = Regex("""^https?://[^\s/?#:@]+(:\d{1,5})?([/?#]\S*)?$""", RegexOption.IGNORE_CASE)
    private const val MAX_LEN = 4096

    /** Bereinigte Adresse oder null, wenn sie keine gültige http(s)-Adresse ist. Zugangsdaten in der Adresse sind erlaubt. */
    fun normalizeUrl(raw: String): String? {
        val u = raw.trim()
        if (u.isEmpty() || u.length > MAX_LEN) return null
        // Stalker-Portal (Portal-Adresse + MAC), siehe StalkerLogic
        if (StalkerLogic.isSource(u)) return StalkerLogic.parse(u)?.encode()
        // Hochgeladene Playlist-Datei im App-Ordner
        if (u.startsWith("file:///")) return u.takeIf { it.contains("/files/imports/") && it.endsWith(".m3u", true) && !it.contains("..") }
        val noCred = u.replaceFirst(Regex("""^(https?://)[^/@\s]*@""", RegexOption.IGNORE_CASE), "$1")
        return if (HTTP_URL.matches(noCred)) u else null
    }

    /** Xtream-Adresse aus Server, Benutzer und Passwort (Sonderzeichen werden kodiert) oder null bei fehlenden Angaben. */
    fun xtreamUrl(host: String, user: String, pass: String): String? {
        var h = host.trim().trimEnd('/')
        val u = user.trim()
        val p = pass.trim()
        if (h.isEmpty() || u.isEmpty() || p.isEmpty()) return null
        if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://$h"
        if (normalizeUrl(h) == null) return null
        return "$h/get.php?username=${enc(u)}&password=${enc(p)}&type=m3u_plus&output=ts"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Text zu einer HTTP-Fehlerantwort beim Laden der Playlist. */
    fun httpError(code: Int): String = when (code) {
        401, 403 -> "Zugang abgelehnt ($code). Benutzername, Passwort oder Abo prüfen."
        404 -> "Playlist nicht gefunden (404). Adresse prüfen."
        429, 458, 509 -> "Der Anbieter begrenzt gerade die Verbindungen ($code). Später erneut versuchen."
        in 500..599 -> "Der Anbieter hat gerade ein Problem ($code). Später erneut versuchen."
        else -> "Server antwortet mit Fehler $code."
    }

    /** Fehlertext für den Nutzer. Gibt nie die Meldung fremder Bibliotheken weiter (die kann Adresse und Passwort enthalten). */
    const val NO_SPACE = "Zu wenig freier Speicherplatz auf dem Gerät. Bitte Platz schaffen (z. B. unbenutzte Apps löschen) und erneut versuchen."

    /** Speicherplatz voll (Android meldet das als IOException mit „ENOSPC“ bzw. „No space left“). */
    fun noSpace(e: Throwable): Boolean {
        val m = e.message ?: return false
        return m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
    }

    /** Lohnt ein neuer Versuch? Ja bei abgerissener Verbindung oder Zeitüberschreitung, nein bei falscher Adresse, Zertifikat, vollem Speicher. */
    fun retryable(e: Throwable): Boolean = when (e) {
        is PlaylistError -> false
        is java.net.UnknownHostException -> false
        is javax.net.ssl.SSLException -> false
        is java.net.ConnectException -> false
        else -> e is java.io.IOException && !noSpace(e)
    }

    fun friendlyError(e: Throwable): String = when (e) {
        is PlaylistError -> e.message ?: "Laden fehlgeschlagen."
        is java.net.UnknownHostException -> "Server nicht gefunden. Adresse und Internetverbindung prüfen."
        is java.net.SocketTimeoutException -> "Zeitüberschreitung: Der Server antwortet nicht."
        is java.net.ConnectException -> "Keine Verbindung zum Server."
        is javax.net.ssl.SSLException -> "Sichere Verbindung fehlgeschlagen (Zertifikat)."
        is IllegalArgumentException -> "Ungültige Adresse."
        is OutOfMemoryError -> "Die Playlist ist zu groß für den Speicher dieses Geräts."
        is java.io.IOException -> if (noSpace(e)) NO_SPACE else "Verbindung unterbrochen. Bitte erneut versuchen."
        else -> "Laden fehlgeschlagen (" + e.javaClass.simpleName + ")."
    }
}

/**
 * Anzeige-Titel aus der Playlist: HTML-Zeichen der Anbieter auflösen („Asterix &amp; Obelix“, „Christie&#039;s“)
 * und eine Sprachkennung mit senkrechtem Strich am Anfang entfernen („|EN| Titel“, „EN| Titel“, „[EN] Titel“).
 * Ohne Android, testbar. Schnell bei Titeln ohne „&“ bzw. „|“/„[“ (fast alle).
 */
object TitleText {
    /** Kurzfassung für das große Bild oben: höchstens [max] Zeichen, an einer Wortgrenze gekürzt, mit „…“. */
    fun short(s: String, max: Int = 250): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        if (t.length <= max) return t
        val cut = t.substring(0, max - 1)
        val sp = cut.lastIndexOf(' ')
        return (if (sp > max / 2) cut.substring(0, sp) else cut).trimEnd(',', ';', ':', '-', ' ') + "…"
    }

    private val ENT = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});""")
    private val NAMED = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "eacute" to "é", "egrave" to "è", "aacute" to "á", "agrave" to "à", "ccedil" to "ç", "ntilde" to "ñ"
    )
    private val LEAD = Regex("""^\s*(?:\|\s*[A-Za-z]{2,3}\s*\||[A-Za-z]{2,3}\s*\||\[\s*[A-Za-z]{2,3}\s*])\s*""")

    /** HTML-Zeichen auflösen (auch doppelt kodierte wie „&amp;#039;“). Unbekannte bleiben stehen. */
    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        var t = s
        repeat(2) {
            if (t.indexOf('&') < 0) return t
            t = ENT.replace(t) { m ->
                val k = m.groupValues[1]
                when {
                    k.startsWith("#x") || k.startsWith("#X") -> k.substring(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
                    k.startsWith("#") -> k.substring(1).toIntOrNull()?.let { cp(it) } ?: m.value
                    else -> NAMED[k] ?: m.value
                }
            }
        }
        return t
    }

    private fun cp(c: Int): String? = if (c in 32..0x10FFFF && c !in 0xD800..0xDFFF) String(Character.toChars(c)) else null

    /** Titel für Filme und Serien: HTML-Zeichen auflösen, Kennung „|EN|“ am Anfang entfernen. */
    fun title(s: String): String {
        val d = decode(s)
        if (d.indexOf('|') < 0 && d.indexOf('[') < 0) return d
        val m = LEAD.find(d) ?: return d
        val rest = d.substring(m.range.last + 1).trim()
        return if (rest.isEmpty()) d else rest
    }
}
