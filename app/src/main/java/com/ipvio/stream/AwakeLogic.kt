package com.ipvio.stream

/**
 * Regeln dafür, wann der Bildschirm wach bleiben muss (ohne Android, testbar).
 * Pflicht: Während ein Stream läuft oder nachlädt, darf der Bildschirm nie ausgehen.
 * Bei Pause, Ende, Fehler und im Hintergrund wird freigegeben – kein Dauer-Lock im Leerlauf.
 */
object AwakeLogic {
    // Gleiche Werte wie androidx.media3.common.Player.STATE_*
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    /**
     * true, solange die Wiedergabe läuft oder (auch nach einem Neuverbinden) gerade lädt.
     * playWhenReady = false heißt Pause bzw. Sleep-Timer abgelaufen.
     */
    fun playerNeedsScreen(playWhenReady: Boolean, state: Int, hasError: Boolean, foreground: Boolean): Boolean =
        foreground && playWhenReady && !hasError && (state == STATE_BUFFERING || state == STATE_READY)
}

/**
 * Mehrere Stellen können den Bildschirm wach halten (Player, Laden der Playlist).
 * Der Schalter bleibt an, solange mindestens eine davon es braucht; keine Stelle schaltet einer anderen das Licht aus.
 */
class AwakeTags {
    private val tags = LinkedHashSet<String>()

    /** Setzt oder entfernt einen Grund und liefert, ob der Bildschirm danach wach bleiben muss. */
    fun set(tag: String, on: Boolean): Boolean {
        if (on) tags.add(tag) else tags.remove(tag)
        return tags.isNotEmpty()
    }

    val active: Boolean get() = tags.isNotEmpty()
    fun names(): List<String> = tags.toList()
}
