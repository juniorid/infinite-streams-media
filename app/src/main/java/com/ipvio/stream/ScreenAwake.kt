package com.ipvio.stream

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference

/**
 * Hält den Bildschirm wach (FLAG_KEEP_SCREEN_ON am Fenster der Activity), solange mindestens ein Grund aktiv ist.
 * Wird ausschließlich im Hauptthread benutzt (Compose-Effekte, Lebenszyklus der Activity).
 * Die CPU- und WLAN-Sperre während der Wiedergabe übernimmt der Player selbst (WAKE_MODE_NETWORK).
 */
object ScreenAwake {
    private val tags = AwakeTags()
    private var window: WeakReference<Window>? = null

    /** true, wenn der Bildschirm gerade wach gehalten wird. */
    var isOn by mutableStateOf(false)
        private set
    /** true, wenn wach gehalten werden müsste, es aber nicht geht (kein Fenster, Fehler des Systems). */
    var unavailable by mutableStateOf(false)
        private set
    /** Gründe, die gerade aktiv sind (für die Diagnose). */
    var reasons by mutableStateOf("")
        private set

    /** Die letzten Wechsel (an/aus mit Uhrzeit und Grund) – in Einstellungen → Diagnose sichtbar, zum Nachprüfen. */
    val history = ArrayDeque<Pair<Long, String>>()

    private fun note(on: Boolean) {
        history.addFirst(System.currentTimeMillis() to (if (on) "an (" + reasons + ")" else "aus"))
        while (history.size > 6) history.removeLast()
    }

    fun attach(a: Activity) { window = WeakReference(a.window); apply() }

    fun detach(a: Activity) {
        if (window?.get() === a.window) {
            runCatching { a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            window = null
            isOn = false
        }
    }

    fun set(tag: String, on: Boolean) { tags.set(tag, on); apply() }

    /** Setzt das Fenster-Flag passend zum Zustand. Auch nach Rückkehr aus dem Standby aufrufen (falls das System es verworfen hat). */
    fun apply() {
        reasons = tags.names().joinToString(", ")
        val want = tags.active
        val w = window?.get()
        if (w == null) { if (isOn) note(false); isOn = false; unavailable = want; return }
        try {
            if (want) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val now = want && (w.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            if (now != isOn) note(now)
            isOn = now
            unavailable = want && !isOn
        } catch (e: Exception) {
            isOn = false
            unavailable = want
        }
    }
}

/** Hält den Bildschirm wach, solange [active] gilt und die Stelle angezeigt wird. Beim Verlassen wird sicher freigegeben. */
@Composable
fun KeepScreenOn(tag: String, active: Boolean) {
    DisposableEffect(tag, active) {
        ScreenAwake.set(tag, active)
        onDispose { ScreenAwake.set(tag, false) }
    }
}
