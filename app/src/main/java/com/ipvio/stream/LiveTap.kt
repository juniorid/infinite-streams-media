package com.ipvio.stream

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Live-Puffer für EINEN Sender (siehe [TapLogic]): eine Verbindung zum Anbieter, die Daten landen in Stücken auf dem
 * Speicher. Player und Aufnahme lesen daraus. Bricht die Verbindung ab, wird neu verbunden und weitergeschrieben.
 *
 * Lebensdauer über Zähler: [acquire]/[release]. Ist niemand mehr dran (Player zu, keine Aufnahme), wird geschlossen
 * und der Ordner gelöscht.
 */
class LiveTap(val id: Int, val url: String, private val dir: File, private val maxBytes: Long, private val ua: String) {
    private val lock = Object()
    @Volatile var written = 0L
        private set
    /** Erste noch vorhandene Byte-Stelle (ältere Stücke sind gelöscht). */
    @Volatile var start = 0L
        private set
    @Volatile var closed = false
        private set
    /** Endgültiger Fehler (z. B. Zugang abgelehnt); dann schreibt der Puffer nicht weiter. */
    @Volatile var failure: String? = null
        private set
    /** Anzahl Neuverbindungen (für den Hinweis im Player). */
    @Volatile var reconnects = 0
        private set
    val index = TapLogic.TimeIndex()
    /** Beginn des Puffers: Uhrzeit (für Sendungszeiten aus dem TV-Guide) und monotone Zeit (für den Abstand). */
    val startedWall = System.currentTimeMillis()
    private val startedMono = System.nanoTime()
    private var refs = 0
    private var thread: Thread? = null

    private val client: OkHttpClient = Net.base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Millisekunden seit Beginn des Puffers. */
    fun nowMs(): Long = (System.nanoTime() - startedMono) / 1_000_000

    /** Zeitpunkt (ms seit Beginn) der ältesten noch vorhandenen Daten. */
    fun oldestMs(): Long = index.msAt(start)

    fun acquire(): LiveTap { synchronized(lock) { refs++ }; return this }

    /** true, wenn danach niemand mehr den Puffer nutzt (er wird dann geschlossen). */
    fun release(): Boolean {
        val last = synchronized(lock) { refs = (refs - 1).coerceAtLeast(0); refs == 0 }
        if (last) close()
        return last
    }

    fun users(): Int = synchronized(lock) { refs }

    private fun chunkFile(idx: Long) = File(dir, "c$idx.ts")

    fun begin() {
        dir.mkdirs()
        val t = Thread({ run() }, "LiveTap-$id")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Verbindung sofort neu aufbauen (Player meint, es hängt). */
    fun kick() { runCatching { call?.cancel() } }

    @Volatile private var call: okhttp3.Call? = null

    private var out: FileOutputStream? = null
    private var outIdx = -1L
    private var lastSample = -1000L

    /** Empfangene Daten an den Puffer hängen (Stück für Stück), Zeitpunkt merken, alte Stücke löschen. */
    internal fun append(buf: ByteArray, n: Int) {
        var done = 0
        while (done < n) {
            val pos = written
            val idx = TapLogic.chunkOf(pos)
            if (idx != outIdx) {
                runCatching { out?.close() }
                out = FileOutputStream(chunkFile(idx), true)
                outIdx = idx
            }
            val room = (TapLogic.CHUNK - TapLogic.offsetIn(pos)).toInt()
            val k = minOf(room, n - done)
            out!!.write(buf, done, k)
            done += k
            synchronized(lock) { written = pos + k; lock.notifyAll() }
        }
        val now = nowMs()
        if (now - lastSample >= 500) { lastSample = now; index.add(now, written) }
        trim()
    }

    private fun run() {
        var backoff = 1000L
        val buf = ByteArray(64 * 1024)
        try {
            while (!closed) {
                try {
                    val c = client.newCall(Request.Builder().url(url).header("User-Agent", ua).build())
                    call = c
                    c.execute().use { r ->
                        if (!r.isSuccessful) {
                            if (isPermanentHttp(r.code)) { failure = PlaylistLogic.httpError(r.code); return }
                            throw IOException("HTTP " + r.code)
                        }
                        val ins = r.body?.byteStream() ?: throw IOException("leer")
                        backoff = 1000L
                        while (!closed) {
                            val n = ins.read(buf)
                            if (n < 0) break   // Server hat den Stream beendet: neu verbinden
                            if (n > 0) append(buf, n)
                        }
                    }
                } catch (e: IOException) {
                    if (closed) break
                }
                if (closed || failure != null) break
                reconnects++
                synchronized(lock) { lock.notifyAll() }
                try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
                backoff = minOf(backoff * 2, 8000L)
            }
        } finally {
            runCatching { out?.close() }
            out = null
            call = null
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** Ältere Stücke löschen, sobald mehr als [maxBytes] gespeichert sind. */
    private fun trim() {
        val keep = TapLogic.keepFrom(written, maxBytes)
        if (keep <= start) return
        var i = TapLogic.chunkOf(start)
        while (i < TapLogic.chunkOf(keep)) { chunkFile(i).delete(); i++ }
        start = keep
        index.trimBefore(keep)
    }

    /**
     * Liest ab [at][0] (wird weitergezählt). Wartet, bis Daten da sind. Liegt die Stelle vor dem Puffer (gelöscht),
     * geht es ab der ältesten vorhandenen Stelle weiter. -1 = Puffer beendet.
     */
    fun read(at: LongArray, buf: ByteArray, off: Int, len: Int, raf: Array<RandomAccessFile?>, rafIdx: LongArray): Int {
        if (at[0] < start) at[0] = start
        synchronized(lock) {
            while (at[0] >= written) {
                if (closed || (failure != null && thread?.isAlive != true)) return -1
                try { lock.wait(250) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException() }
            }
        }
        if (at[0] < start) at[0] = start
        val pos = at[0]
        val idx = TapLogic.chunkOf(pos)
        if (rafIdx[0] != idx || raf[0] == null) {
            runCatching { raf[0]?.close() }
            raf[0] = try { RandomAccessFile(chunkFile(idx), "r") } catch (e: IOException) {
                // Stück gerade gelöscht: zur ältesten Stelle springen
                at[0] = start; rafIdx[0] = -1; return 0.also { if (closed) return -1 }
            }
            rafIdx[0] = idx
        }
        val inChunk = TapLogic.offsetIn(pos)
        val avail = minOf(written - pos, TapLogic.CHUNK - inChunk).toInt()
        val r = raf[0]!!
        r.seek(inChunk)
        val n = r.read(buf, off, minOf(len, avail))
        if (n <= 0) return 0
        at[0] = pos + n
        return n
    }

    /** Geschätzte Datenrate (Bytes je Sekunde) der letzten Zeit, 0 = unbekannt. */
    fun rate(): Long {
        val t1 = index.lastMs(); val t0 = maxOf(index.firstMs(), t1 - 60_000)
        if (t1 - t0 < 2000) return 0
        return (index.byteAt(t1) - index.byteAt(t0)) * 1000 / (t1 - t0)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { call?.cancel() }
        thread?.interrupt()
        synchronized(lock) { lock.notifyAll() }
        Thread {
            runCatching { thread?.join(3000) }
            runCatching { dir.deleteRecursively() }
        }.apply { isDaemon = true }.start()
    }
}

/** Alle laufenden Live-Puffer (einer je Sender; Player und Aufnahme teilen ihn sich). */
object LiveTaps {
    private val taps = HashMap<Int, LiveTap>()
    private var next = 1
    const val UA = "IPVIOStream/1.0"

    @Synchronized fun get(id: Int): LiveTap? = taps[id]?.takeIf { !it.closed }

    /** Vorhandenen Puffer für diese Adresse nehmen oder einen neuen starten. null = zu wenig Speicher. */
    @Synchronized fun open(c: android.content.Context, url: String): LiveTap? {
        taps.values.firstOrNull { it.url == url && !it.closed && it.failure == null }?.let { return it.acquire() }
        taps.entries.removeAll { it.value.closed }
        val root = File(c.cacheDir, "livetap")
        if (taps.isEmpty()) runCatching { root.deleteRecursively() }   // Reste nach einem Absturz
        root.mkdirs()
        val budget = TapLogic.budget(LiveSettings.bufferMb, root.usableSpace)
        if (budget <= 0) return null
        val t = LiveTap(next++, url, File(root, "t" + System.currentTimeMillis()), budget, UA)
        taps[t.id] = t
        t.acquire()
        t.begin()
        return t
    }

    @Synchronized fun all(): List<LiveTap> = taps.values.filter { !it.closed }
}
