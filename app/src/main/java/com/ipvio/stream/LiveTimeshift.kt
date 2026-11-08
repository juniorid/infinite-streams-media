package com.ipvio.stream

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import java.io.RandomAccessFile

/** Einstellungen für Live TV: Zurückspulen (Puffer) und Aufnahmen. Gilt für alle Profile. */
object LiveSettings {
    private const val PREFS = "ipvio_livetv"
    val BUFFER_MB = listOf(256, 512, 1024, 2048)

    /** Live immer über den Puffer abspielen (Zurückspulen, Pause ohne Verlust). Aus = nur bei einer Aufnahme. */
    var timeshift by mutableStateOf(false)
        private set
    var bufferMb by mutableIntStateOf(512)
        private set
    /** Speicherort der Aufnahmen: Index in [Recorder.dirs]. */
    var recDir by mutableIntStateOf(0)
        private set
    /** Minuten Nachlauf nach dem Ende der Sendung laut TV-Guide. */
    var recPad by mutableIntStateOf(3)
        private set
    private var inited = false

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        timeshift = p.getBoolean("ts", false)
        bufferMb = p.getInt("mb", 512).takeIf { it in BUFFER_MB } ?: 512
        recDir = p.getInt("dir", 0).coerceAtLeast(0)
        recPad = p.getInt("pad", 3).coerceIn(0, 30)
    }

    private fun save(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean("ts", timeshift).putInt("mb", bufferMb).putInt("dir", recDir).putInt("pad", recPad).apply()

    fun setTimeshift(c: Context, v: Boolean) { timeshift = v; save(c) }
    fun setBufferMb(c: Context, v: Int) { bufferMb = v; save(c) }
    fun setRecDir(c: Context, v: Int) { recDir = v; save(c) }
    fun setRecPad(c: Context, v: Int) { recPad = v; save(c) }
}

/**
 * Liest aus einem [LiveTap] („tap://ID/START.ts“). Media3 wartet beim Lesen wie bei einer langsamen Verbindung;
 * die Stelle START ist die Byte-Stelle im Puffer, ab der gespielt wird (Zurückspulen = neu öffnen an anderer Stelle).
 */
class TapDataSource : BaseDataSource(true) {
    private var tap: LiveTap? = null
    private var uri: Uri? = null
    private val at = LongArray(1)
    private val raf = arrayOfNulls<RandomAccessFile>(1)
    private val rafIdx = longArrayOf(-1)
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val u = dataSpec.uri
        uri = u
        val id = u.host?.toIntOrNull() ?: throw IOException("Puffer-Adresse ungültig")
        val t = LiveTaps.get(id) ?: throw IOException("Puffer beendet")
        transferInitializing(dataSpec)
        val base = u.lastPathSegment?.substringBefore('.')?.toLongOrNull() ?: 0L
        at[0] = maxOf(base + dataSpec.position, t.start)
        tap = t
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val t = tap ?: return C.RESULT_END_OF_INPUT
        var n: Int
        do { n = t.read(at, buffer, offset, length, raf, rafIdx) } while (n == 0)
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { raf[0]?.close() }
        raf[0] = null; rafIdx[0] = -1
        tap = null
        uri = null
        if (opened) { opened = false; transferEnded() }
    }
}

/** Leitet „tap://“ an den Puffer, alles andere an die normale Quelle (HTTP, Datei, …). */
class TapAwareDataSource(private val normal: DataSource) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var cur: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val d = if (dataSpec.uri.scheme == "tap") TapDataSource().also { s -> listeners.forEach { s.addTransferListener(it) } } else normal
        cur = d
        return d.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = cur?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = cur?.getUri()

    override fun getResponseHeaders(): Map<String, List<String>> = cur?.getResponseHeaders() ?: emptyMap()

    override fun close() {
        val d = cur
        cur = null
        d?.close()
    }

    class Factory(private val inner: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = TapAwareDataSource(inner.createDataSource())
    }
}

/**
 * Zurückspulen im Live TV über den Puffer. Hält den Puffer des gerade gespielten Senders; der Player spielt
 * „tap://ID/START.ts“. Die Zeit wird selbst gerechnet: [baseMs] = Pufferzeit am Anfang des gerade geöffneten Stücks.
 */
object LiveTimeshift {
    /** Puffer des Players (null = Live läuft direkt vom Server). */
    var tap: LiveTap? = null
        private set
    private var baseMs = 0L
    /** Ändert sich bei jedem Start/Stopp (Oberfläche). */
    var version by mutableIntStateOf(0)
        private set

    /** Puffer aktiv? (liest [version], damit die Oberfläche bei Start/Stopp neu zeichnet) */
    val active: Boolean get() = version >= 0 && tap?.let { !it.closed } == true

    /** Soll diese Adresse über den Puffer laufen? */
    fun wanted(url: String, forRecording: Boolean = false): Boolean = (LiveSettings.timeshift || forRecording) && TapLogic.tappable(url)

    /**
     * Sender über den Puffer starten. Läuft für diese Adresse schon ein Puffer (z. B. Aufnahme), wird er geteilt und
     * kurz vor dem Live-Bild eingestiegen. false = Puffer nicht möglich (zu wenig Speicher) → direkt abspielen.
     */
    fun start(c: Context, player: ExoPlayer, url: String): Boolean {
        val old = tap
        if (old != null && old.url == url && !old.closed) { goLive(player); return true }
        val t = LiveTaps.open(c, url) ?: return false
        tap = t
        old?.release()
        version++
        goLive(player)
        return true
    }

    /** Puffer loslassen (anderer Sender ohne Puffer, Player zu). Eine laufende Aufnahme behält ihn. */
    fun stop() {
        val t = tap ?: return
        tap = null
        t.release()
        version++
    }

    private fun openAt(player: ExoPlayer, t: LiveTap, byte: Long): Boolean {
        baseMs = t.index.msAt(byte)
        val ok = openStream(player, "tap://${t.id}/$byte.ts", 0L)
        if (!ok) stop()
        return ok
    }

    /** Zum Live-Bild (etwa 2 s dahinter, damit der Player etwas Vorrat hat). */
    fun goLive(player: ExoPlayer) {
        val t = tap ?: return
        val target = (t.nowMs() - 2000).coerceAtLeast(t.oldestMs())
        val b = if (t.index.size() == 0) t.start else t.index.byteAt(target)
        openAt(player, t, b)
    }

    /** Aktuelle Stelle in Pufferzeit. */
    fun posMs(player: ExoPlayer): Long = baseMs + player.currentPosition.coerceAtLeast(0)

    /** Abstand zum Live-Bild in ms. */
    fun behindMs(player: ExoPlayer): Long {
        val t = tap ?: return 0
        return (t.nowMs() - posMs(player)).coerceAtLeast(0)
    }

    /** Wie weit zurück der Puffer reicht (ms). */
    fun spanMs(): Long { val t = tap ?: return 0; return (t.nowMs() - t.oldestMs()).coerceAtLeast(0) }

    /** Um [deltaMs] springen (negativ = zurück). Nicht über das Live-Bild hinaus, nicht vor den Puffer. */
    fun seekBy(player: ExoPlayer, deltaMs: Long) {
        val t = tap ?: return
        val now = t.nowMs()
        val target = (posMs(player) + deltaMs).coerceIn(t.oldestMs(), now - 1500)
        if (now - target < 3000) { goLive(player); return }
        val wasPlaying = player.playWhenReady
        openAt(player, t, t.index.byteAt(target))
        player.playWhenReady = wasPlaying || deltaMs != 0L
    }

    /** Neu verbinden: mit Puffer an das Live-Bild, ohne Puffer wie bisher. */
    fun reconnect(player: ExoPlayer) {
        val t = tap
        when {
            t != null && !t.closed && t.failure == null -> { t.kick(); goLive(player) }
            // Puffer gescheitert (z. B. Zugang abgelehnt): direkt vom Server weiter, ohne Puffer
            t != null -> { val u = t.url; stop(); openStream(player, u, 0L) }
            else -> { player.seekToDefaultPosition(); player.prepare() }
        }
    }
}

/** Abspielbare Adresse aus einer Datei (Aufnahme). */
fun fileUrl(f: java.io.File): String = Uri.fromFile(f).toString()
