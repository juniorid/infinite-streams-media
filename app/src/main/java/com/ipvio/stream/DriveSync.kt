package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Sicherung in Google Drive (Runde 20), optional. Anmeldung wie bei TV-Apps üblich mit Code am Handy
 * (google.com/device) – das geht auch auf Fire TV ohne Google-Dienste. Gespeichert wird im versteckten App-Ordner
 * von Drive („appDataFolder“): die App sieht nur ihre eigene Datei, nichts anderes aus deinem Drive.
 *
 * Braucht eine eigene OAuth-Client-ID (Typ „TVs and Limited Input devices“), siehe docs/GOOGLE_DRIVE.md.
 * Ohne sie ist der Bereich ausgeblendet.
 */
object DriveSync {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val NAME = "ipvio_backup.json"
    private const val PREFS = "ipvio_drive"
    private val client = Net.withTimeouts(15, 60)
    private val JSON = "application/json; charset=UTF-8".toMediaType()

    val clientId: String get() = BuildConfig.GOOGLE_CLIENT_ID.trim()
    private val secret: String get() = BuildConfig.GOOGLE_CLIENT_SECRET.trim()

    fun available(): Boolean = clientId.isNotEmpty()

    var connected by mutableStateOf(false)
        private set
    /** Zeit der letzten Sicherung in Drive (ms), 0 = unbekannt. */
    var lastBackup by mutableLongStateOf(0L)
        private set

    private var access: String? = null
    private var accessUntil = 0L

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(c: Context) {
        connected = refreshToken(c) != null
        lastBackup = prefs(c).getLong("last", 0L)
    }

    private fun refreshToken(c: Context): String? = prefs(c).getString("rt", null)?.let { Secrets.decrypt(it) }

    class DeviceCode(val device: String, val user: String, val url: String, val interval: Int, val expiresAt: Long)

    private fun post(url: String, form: Map<String, String>): JSONObject? {
        val b = FormBody.Builder()
        for ((k, v) in form) b.add(k, v)
        return client.newCall(Request.Builder().url(url).post(b.build()).build()).execute().use { r ->
            runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
        }
    }

    /** Schritt 1: Code holen, den der Nutzer am Handy unter der Adresse eingibt. */
    suspend fun start(): Result<DeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            val o = post("https://oauth2.googleapis.com/device/code", mapOf("client_id" to clientId, "scope" to SCOPE))
                ?: error("Google antwortet nicht")
            val user = o.optString("user_code").ifBlank { error(o.optString("error_description").ifBlank { o.optString("error", "Fehler") }) }
            DeviceCode(o.optString("device_code"), user, o.optString("verification_url").ifBlank { "https://www.google.com/device" },
                o.optInt("interval", 5).coerceIn(2, 30), System.currentTimeMillis() + o.optLong("expires_in", 900) * 1000)
        }
    }

    /** Schritt 2: warten, bis der Nutzer zugestimmt hat. true = verbunden. */
    suspend fun poll(c: Context, d: DeviceCode): Boolean = withContext(Dispatchers.IO) {
        var wait = d.interval * 1000L
        while (System.currentTimeMillis() < d.expiresAt) {
            delay(wait)
            val o = runCatching {
                post("https://oauth2.googleapis.com/token", mapOf(
                    "client_id" to clientId, "client_secret" to secret, "device_code" to d.device,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                ))
            }.getOrNull() ?: continue
            when (o.optString("error")) {
                "" -> {
                    val rt = o.optString("refresh_token").ifBlank { return@withContext false }
                    val enc = Secrets.encrypt(rt) ?: return@withContext false
                    prefs(c).edit().putString("rt", enc).apply()
                    access = o.optString("access_token"); accessUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 120) * 1000
                    connected = true
                    return@withContext true
                }
                "authorization_pending" -> {}
                "slow_down" -> wait += 5000
                else -> return@withContext false
            }
        }
        false
    }

    private fun token(c: Context): String? {
        access?.let { if (System.currentTimeMillis() < accessUntil) return it }
        val rt = refreshToken(c) ?: return null
        val o = runCatching {
            post("https://oauth2.googleapis.com/token", mapOf("client_id" to clientId, "client_secret" to secret, "refresh_token" to rt, "grant_type" to "refresh_token"))
        }.getOrNull() ?: return null
        if (o.optString("error") == "invalid_grant") { disconnect(c); return null }
        val t = o.optString("access_token").ifBlank { return null }
        access = t; accessUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 120) * 1000
        return t
    }

    private fun fileId(t: String): String? {
        val q = URLEncoder.encode("name='$NAME'", "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id,modifiedTime)"
        return client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $t").build()).execute().use { r ->
            if (!r.isSuccessful) null
            else runCatching { JSONObject(r.body?.string().orEmpty()).optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifBlank { null } }.getOrNull()
        }
    }

    /** Sicherung hochladen (ersetzt die vorige). */
    suspend fun upload(c: Context, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val t = token(c) ?: error("Nicht mit Google verbunden")
            val id = fileId(t)
            val req = if (id != null) {
                Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media")
                    .header("Authorization", "Bearer $t").patch(text.toRequestBody(JSON)).build()
            } else {
                val meta = JSONObject().put("name", NAME).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
                val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                    .addPart(meta.toRequestBody(JSON))
                    .addPart(text.toRequestBody(JSON))
                    .build()
                Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                    .header("Authorization", "Bearer $t").post(body).build()
            }
            client.newCall(req).execute().use { r -> if (!r.isSuccessful) error("Google Drive: Fehler ${r.code}") }
            lastBackup = System.currentTimeMillis()
            prefs(c).edit().putLong("last", lastBackup).apply()
        }
    }

    /** Sicherung aus Drive holen (null = keine vorhanden). */
    suspend fun download(c: Context): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val t = token(c) ?: error("Nicht mit Google verbunden")
            val id = fileId(t) ?: return@runCatching null
            client.newCall(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$id?alt=media").header("Authorization", "Bearer $t").build())
                .execute().use { r -> if (!r.isSuccessful) error("Google Drive: Fehler ${r.code}") else r.body?.string() }
        }
    }

    fun disconnect(c: Context) {
        prefs(c).edit().remove("rt").apply()
        access = null; accessUntil = 0L
        connected = false
    }
}
