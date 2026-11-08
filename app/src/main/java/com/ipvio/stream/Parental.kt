package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom

/** Jugendschutz: blendet Erwachsenen-Inhalte aus und schützt die Einstellung mit einer 4-stelligen PIN. */
object Parental {
    private const val PREFS = "ipvio_general"
    var hideAdult by mutableStateOf(false)
        private set
    var pinSet by mutableStateOf(false)
        private set
    /** Ändert sich bei jeder Umstellung, damit gefilterte Listen neu berechnet werden. */
    var version by mutableIntStateOf(0)
        private set
    /** Nur solange die Jugendschutz-Seite offen ist; wird beim Verlassen zurückgesetzt. */
    var unlocked by mutableStateOf(false)

    private fun prefs(c: Context) = Profiles.sp(c, PREFS)

    fun init(c: Context) {
        val p = prefs(c)
        hideAdult = p.getBoolean("parental_hide", false)
        pinSet = !p.getString("parental_hash", null).isNullOrEmpty()
        unlocked = false
        version++
    }

    fun setHide(c: Context, v: Boolean) {
        hideAdult = v
        prefs(c).edit().putBoolean("parental_hide", v).apply()
        version++
    }

    private val guard = PinGuard()

    /** Sekunden, bis nach zu vielen Fehlversuchen wieder eine PIN eingegeben werden darf (0 = sofort). */
    fun lockSec(): Long = guard.waitSec(System.currentTimeMillis())

    fun verify(c: Context, pin: String): Boolean = verifyFor(c, Profiles.active, pin)

    /** Jugendschutz-Einstellungen eines beliebigen Profils (nicht nur des aktiven). */
    private fun prefsOf(c: Context, profileId: Int) =
        c.applicationContext.getSharedPreferences(Profiles.scopedName(PREFS, profileId), Context.MODE_PRIVATE)

    /**
     * Ein Profil ist gesperrt, wenn dort Erwachsenen-Inhalte ausgeblendet sind und eine PIN gesetzt ist (z. B. ein Kinderprofil).
     * Aus einem gesperrten Profil kommt man nur mit dessen PIN heraus – sonst würde der Wechsel den Schutz aushebeln.
     */
    fun profileLocked(c: Context, profileId: Int): Boolean {
        val p = prefsOf(c, profileId)
        return p.getBoolean("parental_hide", false) && !p.getString("parental_hash", null).isNullOrEmpty()
    }

    fun verifyFor(c: Context, profileId: Int, pin: String): Boolean {
        val now = System.currentTimeMillis()
        if (guard.waitSec(now) > 0) return false
        val p = prefsOf(c, profileId)
        val h = p.getString("parental_hash", null) ?: return false
        val ok = ParentalLogic.check(pin, p.getString("parental_salt", "") ?: "", h)
        guard.record(ok, now)
        return ok
    }

    /** Fehlertext nach einer falschen PIN (mit Wartezeit, wenn gesperrt). */
    fun pinError(): String {
        val w = lockSec()
        return if (w > 0) "Zu viele Fehlversuche. Bitte in $w s erneut versuchen." else "Falsche PIN"
    }

    fun setPin(c: Context, pin: String) {
        if (!ParentalLogic.validPin(pin)) return
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs(c).edit().putString("parental_salt", salt).putString("parental_hash", ParentalLogic.hash(pin, salt)).apply()
        pinSet = true
    }

    fun clearPin(c: Context) {
        prefs(c).edit().remove("parental_salt").remove("parental_hash").apply()
        pinSet = false
    }

    fun filter(list: List<Item>): List<Item> = list.filterNot { ParentalLogic.isAdult(it.group, it.title) }
}
