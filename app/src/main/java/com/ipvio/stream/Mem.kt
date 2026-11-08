package com.ipvio.stream

import android.app.ActivityManager
import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import coil.Coil

/**
 * Anpassung an den Arbeitsspeicher des Geräts. Viele TV-Boxen und Sticks haben nur 1 GB RAM:
 * dort werden Bild-Cache und Player-Puffer kleiner gehalten, damit die App nicht vom System beendet wird.
 * Stufen: 0 = wenig (< 1,5 GB), 1 = mittel (< 3 GB), 2 = viel.
 */
object Mem {
    private var cachedTier = -1

    fun totalMb(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    fun tier(ctx: Context): Int {
        if (cachedTier >= 0) return cachedTier
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mb = totalMb(ctx)
        cachedTier = tierFor(mb, am?.isLowRamDevice == true)
        return cachedTier
    }

    fun tierFor(totalMb: Long, lowRamFlag: Boolean): Int = when {
        lowRamFlag || (totalMb in 1..1536) -> 0
        totalMb in 1..3072 -> 1
        else -> 2
    }

    /** Anteil des Arbeitsspeichers für den Bild-Cache (Coil). */
    fun imageCachePercent(tier: Int): Double = when (tier) { 0 -> 0.08; 1 -> 0.13; else -> 0.18 }

    /** Puffergrößen für den Player: (min ms, max ms, Start ms, Nachpuffern ms, Byte-Grenze). */
    fun bufferPlan(tier: Int): IntArray = when (tier) {
        0 -> intArrayOf(10_000, 25_000, 1_500, 3_000, 14 * 1024 * 1024)
        1 -> intArrayOf(20_000, 40_000, 2_000, 4_000, 28 * 1024 * 1024)
        else -> intArrayOf(30_000, 60_000, 2_500, 5_000, -1)
    }

    fun loadControl(ctx: Context): DefaultLoadControl {
        val p = BufferLogic.scale(bufferPlan(tier(ctx)), BufferLogic.level(AppSettings.bufferMode, AppSettings.bufferLearned))
        val b = DefaultLoadControl.Builder().setBufferDurationsMs(p[0], p[1], p[2], p[3])
        if (p[4] > 0) b.setTargetBufferBytes(p[4]).setPrioritizeTimeOverSizeThresholds(false)
        return b.build()
    }

    /** Wahr, wenn der Java-Speicher der App zu mehr als [limit] belegt ist (Hintergrundarbeit hält dann an, statt abzustürzen). */
    fun heapTight(limit: Double = 0.75): Boolean {
        val r = Runtime.getRuntime()
        return r.totalMemory() - r.freeMemory() > r.maxMemory() * limit
    }

    /** Bei Speicherknappheit: alles leeren, was sich neu laden lässt. */
    fun trim(ctx: Context) {
        runCatching { Coil.imageLoader(ctx).memoryCache?.clear() }
        runCatching { MetaRepo.trim() }
        runCatching { Epg.trim(); EpgFull.trim(); CreditsRepo.trim() }
    }
}

/**
 * Merkt sich den letzten Absturz (nur Art und die obersten Stapelzeilen, keine Meldungstexte mit Adressen),
 * damit er im Diagnosebereich angezeigt werden kann.
 */
object CrashLog {
    private const val PREFS = "ipvio_crash"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val frames = e.stackTrace.filter { it.className.startsWith("com.ipvio") }.take(4)
                    .joinToString(" < ") { it.fileName + ":" + it.lineNumber }
                val kind = e.javaClass.simpleName + (if (e is OutOfMemoryError) " (Speicher voll)" else "")
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last", kind + (if (frames.isNotEmpty()) " · " + frames else ""))
                    .putLong("at", System.currentTimeMillis()).commit()
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun last(ctx: Context): Pair<String, Long>? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = sp.getString("last", null) ?: return null
        return s to sp.getLong("at", 0L)
    }
}
