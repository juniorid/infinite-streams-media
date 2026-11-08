package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sprachfilter je Profil: blendet Inhalte in Sprachen aus, die man nicht braucht (z. B. türkische, arabische Kategorien).
 * Wirkt überall – Startseite, Filme, Serien, Live-TV, Suche, TV-Programm. Ausgeblendete Sprachen werden gar nicht erst
 * in den Arbeitsspeicher geladen (siehe PlaylistStore); die Sprache jedes Eintrags steht in [Item.lang].
 */
object LangFilter {
    private const val PREFS = "ipvio_lang"

    /** Ausgeblendete Sprachen (Kürzel aus [LangTagLogic], "?" = ohne Kennung). */
    var hidden by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Ändert sich bei jeder Umstellung. */
    var version by mutableIntStateOf(0)
        private set
    /**
     * Bewusst „alle Sprachen“ gewählt (Sprachfrage übersprungen oder „Alle zeigen“): dann wird bei großen Playlists nicht
     * bei jedem Laden erneut gefragt.
     */
    var allChosen by mutableStateOf(false)
        private set

    fun init(c: Context) {
        val p = Profiles.sp(c, PREFS)
        hidden = LangTagLogic.decode(p.getString("hidden", ""))
        allChosen = p.getBoolean("all", false)
        version++
    }

    /** Speichert die Auswahl. Die Playlist muss danach neu eingelesen werden ([PlaylistStore.reapplyLanguages]). Leer = bewusst alle. */
    fun set(c: Context, h: Set<String>) {
        hidden = h
        allChosen = h.isEmpty()
        Profiles.sp(c, PREFS).edit().putString("hidden", LangTagLogic.encode(h)).putBoolean("all", allChosen).apply()
        version++
    }

    /** Sprachauswahl zurücksetzen, ohne „alle“ zu wählen (bei der nächsten großen Playlist wird wieder gefragt). */
    fun reset(c: Context) {
        hidden = emptySet()
        allChosen = false
        Profiles.sp(c, PREFS).edit().putString("hidden", "").putBoolean("all", false).apply()
        version++
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * Speichert die Auswahl und liest die Playlist kurz danach aus dem Zwischenspeicher neu ein.
     * Mehrere schnelle Klicks werden zusammengefasst; läuft gerade ein Ladevorgang, wird danach angewendet.
     * Läuft unabhängig von der Einstellungsseite weiter, auch wenn man sie sofort verlässt.
     */
    fun setAndApply(c: Context, h: Set<String>) {
        set(c, h)
        val app = c.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(600)
            repeat(60) {
                if (PlaylistStore.savedUrl(app) == null) return@launch
                if (PlaylistStore.reapplyLanguages(app)) return@launch
                delay(500)
            }
        }
    }
}
