package com.ipvio.stream

/**
 * Live-Puffer auf dem Gerät (Zurückspulen und Aufnehmen im Live TV, Runde 20). Ohne Android, testbar.
 *
 * Der Live-Stream (MPEG-TS) wird über EINE Verbindung in Stücke von [CHUNK] Bytes auf den Speicher geschrieben.
 * Der Player liest aus diesen Stücken (wenige Sekunden hinter dem Live-Bild), eine Aufnahme kopiert daraus.
 * Alte Stücke werden gelöscht, sobald die eingestellte Puffergröße überschritten ist (Ringpuffer).
 * Zeit und Byte-Stelle werden laufend gemerkt ([TimeIndex]), damit „30 s zurück“ die richtige Stelle findet.
 */
object TapLogic {
    const val CHUNK = 16L * 1024 * 1024
    /** Größe eines TS-Pakets: Lesestellen werden darauf ausgerichtet. */
    const val TS = 188L

    fun chunkOf(pos: Long): Long = pos / CHUNK
    fun offsetIn(pos: Long): Long = pos % CHUNK
    fun align(pos: Long): Long = if (pos <= 0) 0 else pos - pos % TS

    /** Ab welcher Stelle der Puffer noch Daten hat, wenn höchstens [maxBytes] behalten werden (ganze Stücke). */
    fun keepFrom(written: Long, maxBytes: Long): Long {
        if (written <= maxBytes) return 0L
        // Das Stück, in dem die Grenze liegt, bleibt ganz erhalten
        return chunkOf(written - maxBytes) * CHUNK
    }

    /** Puffergröße aus der Einstellung (MB) und dem freien Platz: es bleiben immer mindestens [reserve] Bytes frei. */
    fun budget(settingMb: Int, usable: Long, reserve: Long = 400L * 1024 * 1024): Long {
        val want = settingMb.toLong() * 1024 * 1024
        val can = usable - reserve
        return if (can < 3 * CHUNK) 0L else minOf(want, can).coerceAtLeast(3 * CHUNK)
    }

    /** Text für die Anzeige „LIVE −1:05“. */
    fun behindText(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "${s / 3600}:" + ((s / 60) % 60).toString().padStart(2, '0') + ":" + (s % 60).toString().padStart(2, '0')
        else "${s / 60}:" + (s % 60).toString().padStart(2, '0')
    }

    /** Kann diese Adresse über den Puffer laufen? Nur fortlaufende Streams (TS), keine Playlists (HLS/DASH) oder Dateien. */
    fun tappable(url: String): Boolean {
        val u = url.lowercase().substringBefore('?')
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        if (u.endsWith(".m3u8") || u.endsWith(".m3u") || u.endsWith(".mpd") || u.endsWith(".mp4") || u.endsWith(".mkv")) return false
        return true
    }

    /** Dateiname einer Aufnahme (ohne Zeichen, die Dateisysteme nicht mögen). */
    fun fileName(channel: String, title: String, startMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.ROOT).apply { timeZone = zone }
        val base = listOf(channel, title).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" - ")
        val clean = base.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").replace(Regex("""\s+"""), " ").take(80).trim()
        return (if (clean.isEmpty()) "Aufnahme" else clean) + " " + f.format(java.util.Date(startMs)) + ".ts"
    }

    /**
     * Zeit (ms seit Beginn des Puffers) ↔ Byte-Stelle. Wird beim Schreiben etwa jede halbe Sekunde ergänzt.
     * Zwischen zwei Punkten wird linear gerechnet; vor dem ersten bzw. nach dem letzten Punkt gilt der Randwert.
     */
    class TimeIndex {
        private var t = LongArray(256)
        private var b = LongArray(256)
        private var n = 0

        @Synchronized fun add(ms: Long, bytes: Long) {
            if (n > 0 && (ms <= t[n - 1] || bytes < b[n - 1])) return
            if (n == t.size) { t = t.copyOf(n * 2); b = b.copyOf(n * 2) }
            t[n] = ms; b[n] = bytes; n++
        }

        /** Punkte vor der Byte-Stelle [bytes] verwerfen (gelöschte Stücke); einer bleibt als Anker. */
        @Synchronized fun trimBefore(bytes: Long) {
            var i = 0
            while (i + 1 < n && b[i + 1] <= bytes) i++
            if (i == 0) return
            System.arraycopy(t, i, t, 0, n - i); System.arraycopy(b, i, b, 0, n - i)
            n -= i
        }

        @Synchronized fun size() = n
        @Synchronized fun firstMs(): Long = if (n == 0) 0 else t[0]
        @Synchronized fun lastMs(): Long = if (n == 0) 0 else t[n - 1]

        @Synchronized fun byteAt(ms: Long): Long {
            if (n == 0) return 0
            if (ms <= t[0]) return b[0]
            if (ms >= t[n - 1]) return b[n - 1]
            var lo = 0; var hi = n - 1
            while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (t[m] <= ms) lo = m else hi = m }
            val dt = t[hi] - t[lo]
            return if (dt <= 0) b[lo] else b[lo] + (b[hi] - b[lo]) * (ms - t[lo]) / dt
        }

        @Synchronized fun msAt(bytes: Long): Long {
            if (n == 0) return 0
            if (bytes <= b[0]) return t[0]
            if (bytes >= b[n - 1]) return t[n - 1]
            var lo = 0; var hi = n - 1
            while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (b[m] <= bytes) lo = m else hi = m }
            val db = b[hi] - b[lo]
            return if (db <= 0) t[lo] else t[lo] + (t[hi] - t[lo]) * (bytes - b[lo]) / db
        }
    }
}
