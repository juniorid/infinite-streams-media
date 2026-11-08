package com.ipvio.stream

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

object ContentFocus {
    val req = FocusRequester()
    var tab = -1
}

/**
 * Zuletzt fokussiertes Element im Inhalt (Karten, Knöpfe). Rechts aus der Seitenleiste geht es dorthin zurück
 * statt zum Element, das zufällig neben dem gewählten Menüpunkt liegt (Runde 14).
 */
object ContentMemory {
    var last: FocusRequester? = null
    fun restore(): Boolean = last?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
}

/** Merkt sich dieses Element als zuletzt fokussiert (siehe [ContentMemory]). */
@Composable
fun Modifier.trackFocus(): Modifier {
    val fr = remember { FocusRequester() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (ContentMemory.last === fr) ContentMemory.last = null } }
    return this.focusRequester(fr).onFocusChanged { if (it.isFocused) ContentMemory.last = fr }
}

object LastFocus {
    var pending: String? = null
}

/**
 * Zuletzt im Player gespielte Folge einer Serie. Die Detailseite öffnet beim Zurückkommen genau bei dieser Staffel/Folge und
 * markiert sie, damit man gleich eine andere wählen kann (statt wieder bei „Fortsetzen“ zu landen). Wird einmal gelesen und gelöscht.
 */
object DetailReturn {
    private var id: String? = null
    private var season = 0
    private var ep = 0
    fun set(itemId: String, s: Int, e: Int) { if (s > 0 || e > 0) { id = itemId; season = s; ep = e } }
    fun take(itemId: String): Pair<Int, Int>? = if (id == itemId) { id = null; season to ep } else null
}

object HomeFlags {
    var heroDone = false
}

object NavState {
    var tab = 0
}

/**
 * App-weite Navigation, die nicht an einen Bildschirm gebunden ist:
 * - [homeSignal]: zählt hoch, wenn die App nach der Home-Taste (oder App-Wechsel) wieder geöffnet wird → zurück zur Startseite.
 * - [focusTab]: Tab, dessen Inhalt nach einem Wechsel den Fokus bekommen soll, wenn dabei der ganze Bildschirm ausgetauscht
 *   wurde (z. B. Start → Suche). Die Seitenleiste des alten Bildschirms ist dann schon weg und kann das nicht mehr erledigen.
 */
object AppNav {
    var homeSignal by androidx.compose.runtime.mutableIntStateOf(0)
    /** Player offen? Dann verschiebt die Hintergrund-Aktualisierung das Neuladen der Playlist (kein Ruckeln mitten im Film). */
    @Volatile var playing = false
    /** Sender, der geöffnet werden soll (Tippen auf eine Erinnerungs-Benachrichtigung). */
    var openChannel by androidx.compose.runtime.mutableStateOf<String?>(null)
    @Volatile var leftByUser = false
    @Volatile var focusTab = -1
    /** Beim nächsten Start des Players gleich den TV-Guide öffnen (Live TV > „TV-Guide“). */
    @Volatile var openGuide = false
}

object RowStates {
    private val m = HashMap<String, LazyListState>()
    fun get(k: String): LazyListState = m.getOrPut(k) { LazyListState() }
}

@Composable
fun Modifier.restoreFocus(id: String): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (LastFocus.pending == id) {
            delay(150)
            runCatching { fr.requestFocus() }
            LastFocus.pending = null
        }
    }
    return this.focusRequester(fr)
}
