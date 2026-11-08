package com.ipvio.stream

import java.util.Calendar
import java.util.TimeZone

/** Plan für die automatische Playlist-Aktualisierung. */
data class UpdatePlan(
    /** 0 = bei jedem Start, 1 = alle X Stunden, 2 = täglich, 3 = wöchentlich, 4 = nur von Hand */
    val mode: Int,
    val intervalH: Int = 12,
    val hour: Int = 4,
    val minute: Int = 0,
    /** Wochentag wie Calendar: 1 = Sonntag … 7 = Samstag */
    val dow: Int = Calendar.MONDAY
)

/** Reine Hilfsfunktionen der Einstellungen (ohne Android, daher testbar). */
object SettingsLogic {

    /** Menü-Nummern: 0 Start, 1 Filme, 2 Serien, 3 Live TV, 4 Suche. Ist die gewählte Startseite versteckt, startet die App auf "Start". */
    fun resolveStartTab(pref: Int, order: List<Int>, hidden: List<Int>): Int =
        if (pref in 1..4 && pref in order && pref !in hidden) pref else 0

    /** Letzter planmäßiger Zeitpunkt (≤ jetzt) für täglich/wöchentlich. */
    fun lastScheduled(plan: UpdatePlan, nowMs: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = nowMs
        c.set(Calendar.HOUR_OF_DAY, plan.hour.coerceIn(0, 23))
        c.set(Calendar.MINUTE, plan.minute.coerceIn(0, 59))
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (plan.mode == 3) {
            val diff = (c.get(Calendar.DAY_OF_WEEK) - plan.dow + 7) % 7
            c.add(Calendar.DAY_OF_MONTH, -diff)
        }
        val step = if (plan.mode == 3) 7 else 1
        while (c.timeInMillis > nowMs) c.add(Calendar.DAY_OF_MONTH, -step)
        return c.timeInMillis
    }

    /** Ist laut Plan jetzt eine Aktualisierung fällig? [lastMs] = letzte erfolgreiche Aktualisierung (0 = nie). */
    fun refreshDue(plan: UpdatePlan, lastMs: Long, nowMs: Long, zone: TimeZone): Boolean = when (plan.mode) {
        0 -> true
        1 -> lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= plan.intervalH.coerceAtLeast(1) * 3_600_000L
        2, 3 -> lastMs < lastScheduled(plan, nowMs, zone)
        else -> false
    }

    /** Nächster geplanter Zeitpunkt (null bei "bei jedem Start" und "nur von Hand"). */
    fun nextDue(plan: UpdatePlan, lastMs: Long, nowMs: Long, zone: TimeZone): Long? = when (plan.mode) {
        1 -> if (lastMs <= 0L) nowMs else lastMs + plan.intervalH.coerceAtLeast(1) * 3_600_000L
        2, 3 -> {
            val c = Calendar.getInstance(zone)
            c.timeInMillis = lastScheduled(plan, nowMs, zone)
            c.add(Calendar.DAY_OF_MONTH, if (plan.mode == 3) 7 else 1)
            c.timeInMillis
        }
        else -> null
    }

    fun fmtBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> String.format(java.util.Locale.GERMANY, "%.0f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.GERMANY, "%.1f MB", b / 1048576.0)
        else -> String.format(java.util.Locale.GERMANY, "%.2f GB", b / 1073741824.0)
    }

    // ---- Zeit ----
    class Zone(val id: String, val label: String)

    /** Auswahl: erster Eintrag = Systemzeit des Geräts. */
    val ZONES = listOf(
        Zone("", ""),
        Zone("Europe/Berlin", "Berlin · Wien · Zürich · Zagreb · Belgrad"),
        Zone("Europe/London", "London · Dublin"),
        Zone("Europe/Lisbon", "Lissabon"),
        Zone("Europe/Athens", "Athen · Helsinki · Bukarest"),
        Zone("Europe/Istanbul", "Istanbul · Ankara"),
        Zone("Europe/Moscow", "Moskau"),
        Zone("Asia/Dubai", "Dubai"),
        Zone("Asia/Kolkata", "Indien"),
        Zone("Asia/Tokyo", "Tokio · Seoul"),
        Zone("Australia/Sydney", "Sydney"),
        Zone("America/Sao_Paulo", "Brasília"),
        Zone("America/New_York", "New York · Toronto"),
        Zone("America/Chicago", "Chicago · Mexiko-Stadt"),
        Zone("America/Los_Angeles", "Los Angeles · Vancouver"),
        Zone("UTC", "UTC")
    )

    fun zoneOf(id: String): TimeZone =
        if (id.isBlank()) TimeZone.getDefault()
        else TimeZone.getTimeZone(id).takeIf { it.id == id } ?: TimeZone.getDefault()

    /** Uhrzeit-Muster: mode 0 = System, 1 = 24 Stunden, 2 = 12 Stunden. */
    fun timePattern(mode: Int, system24: Boolean): String =
        if (mode == 1 || (mode == 0 && system24)) "HH:mm" else "h:mm a"

    fun isMidnight(sec: Long, zone: TimeZone): Boolean {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = sec * 1000
        return c.get(Calendar.HOUR_OF_DAY) == 0 && c.get(Calendar.MINUTE) == 0
    }
}
