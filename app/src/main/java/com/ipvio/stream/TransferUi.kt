package com.ipvio.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Einstellungen > Speicher: auf ein anderes Gerät übertragen (Runde 20).
 * WLAN: kleine Seite für das Handy (Sicherung herunterladen / einspielen, Absturzbericht). Google Drive: optional.
 */
@Composable
internal fun TransferSection(ctx: Context) {
    SectionTitle("Auf ein anderes Gerät übertragen")
    Hint("Verlauf, Weiterschauen, Favoriten, Listen und Einstellungen mitnehmen – z. B. vom Fire TV auf das Handy. Auf Wunsch auch die Playlists mit Zugangsdaten.")
    var serving by remember { mutableStateOf(false) }
    val ip = remember { PhoneImport.localIp() }
    val server = remember {
        PhoneImport { }.apply {
            backup = { pl -> Backup.exportText(ctx, pl) }
            restore = { t ->
                val ok = Backup.restoreText(ctx, t) >= 0
                // Neu starten, damit alle Einstellungen gelten (nach dem Antworten an das Handy)
                if (ok) Handler(Looper.getMainLooper()).postDelayed({ Profiles.restart(ctx) }, 1500)
                ok
            }
            crash = { ImportFiles.crashText(ctx) }
        }
    }
    DisposableEffect(Unit) { onDispose { server.stop() } }
    if (!serving) PillButton("Übertragung im WLAN starten", icon = AIcon.Phone) { serving = server.start() }
    else {
        Column(
            Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x14FFFFFF)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (ip == null) Text("Das Gerät ist nicht mit einem WLAN verbunden.", color = Color(0xFFFFB86B), fontSize = 16.sp)
            else {
                Text("Am Handy (im selben WLAN) öffnen:", color = Muted, fontSize = 15.sp)
                val addr = "http://$ip:${server.port}/transfer"
                Text(addr, color = Accent2, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                QrWithLabel(addr)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Code", color = Muted, fontSize = 16.sp)
                    Text(server.code, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                }
                Text("Altes Gerät: Sicherung herunterladen. Neues Gerät: hier ebenfalls starten und die Datei einspielen. Danach startet die App neu.", color = Muted, fontSize = 14.sp)
            }
            PillButton("Übertragung beenden") { server.stop(); serving = false }
        }
    }
    if (DriveSync.available()) DriveSection(ctx)
}

@Composable
private fun DriveSection(ctx: Context) {
    val scope = rememberCoroutineScope()
    remember { DriveSync.init(ctx); 0 }
    var status by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<DriveSync.DeviceCode?>(null) }
    var withPl by remember { mutableStateOf(false) }
    SectionTitle("Google Drive")
    if (!DriveSync.connected) {
        val c = code
        if (c == null) PillButton("Mit Google verbinden") {
            status = "Bitte warten …"
            scope.launch {
                DriveSync.start().onSuccess { d ->
                    code = d; status = ""
                    val ok = DriveSync.poll(ctx, d)
                    code = null
                    status = if (ok) "Verbunden." else "Nicht verbunden (abgelehnt oder Zeit abgelaufen)."
                }.onFailure { status = it.message ?: "Fehler" }
            }
        } else {
            Text("Am Handy öffnen und den Code eingeben:", color = Muted, fontSize = 15.sp)
            Text(c.url.removePrefix("https://"), color = Accent2, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, raw = true)
            QrWithLabel(c.url)
            Text(c.user, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, raw = true)
        }
        Hint("Die App speichert nur in ihrem eigenen, versteckten Ordner in deinem Google Drive und sieht sonst nichts.")
    } else {
        if (DriveSync.lastBackup > 0) Text("Letzte Sicherung: " + dayLabel(DriveSync.lastBackup / 1000) + " " + hhmm(DriveSync.lastBackup / 1000), color = Muted, fontSize = 15.sp)
        ToggleRow("Playlists mit Zugangsdaten mitsichern", "Praktisch für ein neues Gerät. Die Zugangsdaten liegen dann in deinem Google Drive.", withPl) { withPl = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Jetzt in Drive sichern") {
                status = "Sichern …"
                scope.launch {
                    val t = withContext(Dispatchers.IO) { Backup.exportText(ctx, withPl) }
                    status = if (t == null) "Sicherung fehlgeschlagen." else DriveSync.upload(ctx, t).fold({ "Gesichert." }, { it.message ?: "Fehler" })
                }
            }
            ConfirmPill("Aus Drive wiederherstellen") {
                status = "Laden …"
                scope.launch {
                    DriveSync.download(ctx).onSuccess { t ->
                        if (t == null) status = "In Drive gibt es noch keine Sicherung."
                        else {
                            val n = withContext(Dispatchers.IO) { Backup.restoreText(ctx, t) }
                            if (n >= 0) Profiles.restart(ctx) else status = "Die Sicherung in Drive ist ungültig."
                        }
                    }.onFailure { status = it.message ?: "Fehler" }
                }
            }
            ConfirmPill("Trennen") { DriveSync.disconnect(ctx); status = "" }
        }
    }
    if (status.isNotBlank()) Text(status, color = Accent2, fontSize = 15.sp)
}

/** QR-Code (schwarz auf weiß, mit Rand – so lesen ihn Handy-Kameras auch auf dunklem Bildschirm). */
@Composable
internal fun QrImage(text: String, size: Dp = 160.dp) {
    val m = remember(text) { QrCode.encode(text) } ?: return
    Canvas(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val quiet = 2
        val cell = this.size.minDimension / (m.size + quiet * 2)
        for (y in m.indices) for (x in m[y].indices) if (m[y][x])
            drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell * 1.02f, cell * 1.02f))
    }
}

/** QR-Code mit kurzem Hinweis daneben. */
@Composable
internal fun QrWithLabel(text: String, size: Dp = 160.dp, scanLabel: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QrImage(text, size)
        if (scanLabel) Text("Oder mit der Handy-Kamera scannen", color = Muted, fontSize = 15.sp)
        else Text("Mit der Handy-Kamera scannen", color = Muted, fontSize = 15.sp)
    }
}
