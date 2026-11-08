package com.ipvio.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Aufnahmen im Live TV (Runde 20). Eine Aufnahme liest aus dem Live-Puffer ([LiveTap]) desselben Senders –
 * schaut man den Sender gerade, braucht sie keine zweite Verbindung zum Anbieter. Ist die laufende Sendung schon
 * im Puffer, beginnt die Aufnahme an ihrem Anfang, sonst ab jetzt. Ende: Ende der Sendung laut TV-Guide plus
 * Nachlauf, oder von Hand. Gespeichert wird als .ts mit einer kleinen .json daneben (Sender, Titel, Zeiten).
 */
object Recorder {
    class Rec(
        val name: String, val channelId: String, val channel: String, val title: String, val file: File,
        val startMs: Long, @Volatile var endAtMs: Long, val tap: LiveTap
    ) {
        @Volatile var bytes = 0L
        @Volatile var running = true
        @Volatile var error: String? = null
        internal var thread: Thread? = null
    }

    /** Fertige oder laufende Aufnahme auf dem Speicher. */
    class RecFile(val file: File, val channel: String, val title: String, val startMs: Long, val endMs: Long, val bytes: Long, val running: Boolean)

    val active = mutableStateListOf<Rec>()

    /** Höchstens so lange ohne Programm-Ende (Sicherheit, falls niemand stoppt). */
    private const val MAX_MS = 4L * 3600_000

    /** Mögliche Speicherorte: interner Speicher und eingesteckte USB-Sticks/SD-Karten (App-Ordner, keine Berechtigung nötig). */
    fun dirs(c: Context): List<File> {
        val ext = runCatching { c.getExternalFilesDirs("recordings").filterNotNull() }.getOrDefault(emptyList())
        return ext.ifEmpty { listOf(File(c.filesDir, "recordings")) }
    }

    fun dirLabel(c: Context, i: Int, f: File): String {
        val free = runCatching { f.usableSpace }.getOrDefault(0L) / (1024L * 1024 * 1024)
        val kind = if (i == 0) "Interner Speicher" else "USB / Speicherkarte $i"
        return Lang.tr(kind) + " · " + free + " GB " + Lang.tr("frei")
    }

    fun dir(c: Context): File {
        val d = dirs(c)
        return (d.getOrNull(LiveSettings.recDir) ?: d.first()).also { it.mkdirs() }
    }

    fun isRecording(channelId: String): Boolean = active.any { it.channelId == channelId && it.running }

    /**
     * Aufnahme des Senders [ch] aus dem Puffer [tap] starten (der Puffer wird dafür festgehalten).
     * [prog] = laufende Sendung (Beginn/Ende), null = ab jetzt bis zum Stoppen.
     */
    fun start(c: Context, ch: Item, prog: EpgEntry?, tap: LiveTap): Rec? {
        active.firstOrNull { it.channelId == ch.id && it.running }?.let { return it }
        val now = System.currentTimeMillis()
        val dir = dir(c)
        if (dir.usableSpace < 300L * 1024 * 1024) return null
        // Beginn: Anfang der Sendung, wenn er noch im Puffer liegt, sonst ab jetzt
        val progStartMs = prog?.start?.let { it * 1000 }
        val fromTapMs = if (progStartMs != null) (progStartMs - tap.startedWall).coerceIn(tap.oldestMs(), tap.nowMs()) else tap.nowMs()
        val fromByte = if (tap.index.size() == 0) tap.start else tap.index.byteAt(fromTapMs)
        val startMs = tap.startedWall + tap.index.msAt(fromByte).coerceAtMost(tap.nowMs())
        val end = prog?.end?.let { it * 1000 + LiveSettings.recPad * 60_000L } ?: (now + MAX_MS)
        val title = prog?.title?.takeIf { it.isNotBlank() } ?: ch.title
        val name = TapLogic.fileName(ch.title, if (prog != null) title else "", startMs)
        val f = File(dir, name)
        val r = Rec(name, ch.id, ch.title, title, f, startMs, end, tap.acquire())
        writeMeta(f, r, running = true)
        val t = Thread({ copy(r, fromByte) }, "Rec-" + ch.id)
        t.isDaemon = true
        r.thread = t
        active.add(r)
        t.start()
        RecordService.update(c)
        return r
    }

    fun stop(c: Context, r: Rec) {
        r.running = false
        r.thread?.interrupt()
        RecordService.update(c)
    }

    fun stopFor(c: Context, channelId: String) { active.filter { it.channelId == channelId && it.running }.forEach { stop(c, it) } }

    private fun copy(r: Rec, fromByte: Long) {
        val at = longArrayOf(fromByte)
        val raf = arrayOfNulls<RandomAccessFile>(1)
        val idx = longArrayOf(-1)
        val buf = ByteArray(64 * 1024)
        try {
            FileOutputStream(r.file).use { out ->
                while (r.running && System.currentTimeMillis() < r.endAtMs) {
                    val n = try { r.tap.read(at, buf, 0, buf.size, raf, idx) } catch (e: java.io.InterruptedIOException) { break }
                    if (n < 0) { r.error = r.tap.failure ?: "Verbindung beendet"; break }
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    r.bytes += n
                }
            }
        } catch (e: Exception) {
            r.error = e.javaClass.simpleName
        } finally {
            runCatching { raf[0]?.close() }
            r.running = false
            writeMeta(r.file, r, running = false)
            r.tap.release()
            done(r)
        }
    }

    @Volatile private var appCtx: Context? = null
    fun attach(c: Context) { appCtx = c.applicationContext }

    private fun done(r: Rec) {
        val c = appCtx
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            active.remove(r)
            if (c != null) RecordService.update(c)
        }
    }

    private fun metaFile(f: File) = File(f.path.removeSuffix(".ts") + ".json")

    private fun writeMeta(f: File, r: Rec, running: Boolean) {
        runCatching {
            metaFile(f).writeText(JSONObject().put("ch", r.channel).put("chId", r.channelId).put("title", r.title)
                .put("start", r.startMs).put("end", if (running) 0L else System.currentTimeMillis()).put("bytes", r.bytes).toString())
        }
    }

    /** Aufnahmen auf dem gewählten Speicher (und den anderen), neueste zuerst. */
    fun list(c: Context): List<RecFile> {
        val out = ArrayList<RecFile>()
        for (d in dirs(c)) {
            val files = d.listFiles { f -> f.isFile && f.name.endsWith(".ts") } ?: continue
            for (f in files) {
                val m = runCatching { JSONObject(metaFile(f).readText()) }.getOrNull()
                val running = active.any { it.file == f && it.running }
                out.add(RecFile(f, m?.optString("ch").orEmpty(), m?.optString("title")?.ifBlank { null } ?: f.name.removeSuffix(".ts"),
                    m?.optLong("start") ?: f.lastModified(), m?.optLong("end") ?: f.lastModified(), f.length(), running))
            }
        }
        return out.sortedByDescending { it.startMs }
    }

    fun delete(c: Context, f: RecFile) {
        active.firstOrNull { it.file == f.file }?.let { stop(c, it) }
        runCatching { f.file.delete() }
        runCatching { metaFile(f.file).delete() }
    }

    /** Eine Aufnahme als Eintrag zum Abspielen (wie ein Film: spulbar, Fortschritt wird gemerkt). */
    fun itemOf(f: RecFile): Item = Item("rec:" + f.file.name.hashCode(), Kind.MOVIE, f.title, null, fileUrl(f.file), null, "Aufnahmen")
}

/**
 * Hält die App am Leben, solange eine Aufnahme läuft (Benachrichtigung „Aufnahme läuft“). Ohne diesen Dienst
 * könnte Android die App im Hintergrund beenden und die Aufnahme bräche ab.
 */
class RecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val running = Recorder.active.filter { it.running }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, Lang.tr("Aufnahmen"), NotificationManager.IMPORTANCE_LOW))
        val text = running.joinToString(" · ") { it.channel + ": " + it.title }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(Lang.tr("Aufnahme läuft"))
            .setContentText(text)
            .setOngoing(true)
            .build()
        // Nach startForegroundService() muss startForeground() immer kommen – auch wenn gleich wieder beendet wird
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        } catch (e: Exception) {
            // Vordergrund nicht erlaubt: Aufnahme läuft trotzdem, solange die App lebt
        }
        if (running.isEmpty()) stopNow()
        return START_NOT_STICKY
    }

    private fun stopNow() {
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
        stopSelf()
    }

    /** Android 15+: Zeitgrenze für Hintergrund-Datenübertragung erreicht – sofort beenden (sonst stürzt die App ab). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Recorder.active.toList().forEach { Recorder.stop(this, it) }
        stopNow()
    }

    companion object {
        private const val CHANNEL = "rec"
        private const val ID = 4711

        /** Dienst passend zu den laufenden Aufnahmen starten oder beenden. */
        fun update(c: Context) {
            val any = Recorder.active.any { it.running }
            val i = Intent(c, RecordService::class.java)
            runCatching {
                if (any) { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }
                else c.stopService(i)
            }
        }
    }
}
