package com.ipvio.stream

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Playlist-Dateien vom Gerät oder Handy (Runde 20): werden in den App-Ordner „imports“ übernommen und dann wie eine
 * Playlist-Adresse („file://…“) geladen. Nur echte M3U-Dateien werden angenommen.
 */
object ImportFiles {
    fun dir(c: Context): File = File(c.filesDir, "imports").also { it.mkdirs() }
    fun tmpDir(c: Context): File = File(c.cacheDir, "upload").also { it.mkdirs() }

    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringBeforeLast('.').replace(Regex("""[^\p{L}\p{N} ._-]"""), "_").trim().take(60)
        return (base.ifBlank { "Playlist" }) + "_" + (System.currentTimeMillis() % 100000) + ".m3u"
    }

    /** Datei prüfen und übernehmen; Rückgabe: Adresse zum Laden oder null. */
    fun save(c: Context, name: String, src: File): String? {
        if (!src.exists() || src.length() < 8) return null
        val head = runCatching { src.inputStream().use { s -> val b = ByteArray(512); val n = s.read(b); if (n > 0) String(b, 0, n, Charsets.UTF_8) else "" } }.getOrDefault("")
        if (!XtreamLogic.looksLikeM3u(head)) return null
        val dst = File(dir(c), safeName(name))
        if (!src.renameTo(dst)) { runCatching { src.copyTo(dst, overwrite = true) }.getOrElse { return null } }
        return Uri.fromFile(dst).toString()
    }

    /** Datei aus der Dateiauswahl des Geräts übernehmen. */
    fun fromUri(c: Context, uri: Uri): String? {
        val tmp = File(tmpDir(c), "pick_" + System.currentTimeMillis())
        return try {
            c.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } ?: return null
            save(c, uri.lastPathSegment ?: "Playlist", tmp)
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** Absturzbericht als Text für die Handy-Seite. */
    fun crashText(c: Context): String? {
        val l = CrashLog.last(c) ?: return null
        return l.first + "\n\n" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(l.second)) +
            "\nIPVIO Stream " + AppUpdate.currentName(c) + " · Android " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
    }
}
