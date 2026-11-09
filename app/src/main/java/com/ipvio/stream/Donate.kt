package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Spendenlink des Entwicklers (PayPal). Die App bleibt kostenlos – das ist nur ein freiwilliges Dankeschön. */
object Donate {
    const val URL = "https://www.paypal.com/ncp/payment/EUSU4WAGRJNXY"
    /** Kurzform zum Anzeigen (ohne https://). */
    val SHORT: String get() = URL.removePrefix("https://www.")
}

/** Einstellungen > Über: kleiner Spendenbereich mit Text, QR-Code und Link (ohne Knopf: auf dem Fernseher gibt es oft keinen Browser). */
@Composable
internal fun DonateSection(@Suppress("UNUSED_PARAMETER") ctx: Context) {
    SectionTitle("Donate")
    Hint("This app is free to use, but it takes time, effort and plenty of coffee to maintain. If you would like to support development, you can make a voluntary donation. No pressure: the app remains free, ad-free and GPL-licensed.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QrImage(Donate.URL, 160.dp)
        Text("Scan this QR code with your phone camera to open the PayPal donation page.", color = Muted, fontSize = 15.sp)
    }
    Text("PayPal: " + Donate.SHORT, color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.Bold, raw = true)
}
