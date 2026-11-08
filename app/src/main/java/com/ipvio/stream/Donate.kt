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
    SectionTitle("Spende")
    Hint("Hallo, ich bin der nigerianische Prinz und brauche dringend deine Hilfe! Spaß beiseite: Die App kostet dich nichts, mich aber ein paar Nächte Schlaf und sehr viel Kaffee. Wenn du mir ein paar Euro schicken möchtest, tanzt mein Kontostand vor Freude. Kein Zwang: Die App bleibt kostenlos, werbefrei und GPL-lizenziert.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QrImage(Donate.URL, 160.dp)
        Text("Dieser QR-Code ist der PayPal-Link für meine Spende. Mit der Handy-Kamera scannen, dann öffnet sich PayPal.", color = Muted, fontSize = 15.sp)
    }
    Text("PayPal: " + Donate.SHORT, color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.Bold, raw = true)
}
