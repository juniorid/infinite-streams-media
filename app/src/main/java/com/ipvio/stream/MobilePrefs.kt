package com.ipvio.stream

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Handy und Tablet (Runde 20). Die Oberfläche ist für den Fernseher gebaut (Querformat). Auf dem Handy steht sie
 * deshalb standardmäßig quer (dreht mit dem Gerät zwischen beiden Querlagen); abschaltbar unter Einstellungen > Allgemein.
 */
object MobilePrefs {
    private const val PREFS = "ipvio_mobile"
    var landscape by mutableStateOf(true)
        private set

    /** Handy/Tablet statt Fernseher (kein Leanback, Touch vorhanden). */
    fun isMobile(c: Context): Boolean {
        val pm = c.packageManager
        val tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("android.software.leanback_only") ||
            pm.hasSystemFeature("amazon.hardware.fire_tv")
        return !tv && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun apply(a: Activity) {
        landscape = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("landscape", true)
        if (!isMobile(a)) return
        a.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun set(a: Activity, v: Boolean) {
        landscape = v
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("landscape", v).apply()
        apply(a)
    }
}
