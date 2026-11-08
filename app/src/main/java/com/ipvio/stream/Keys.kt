package com.ipvio.stream

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

@Composable
fun Modifier.favKeys(onFav: () -> Unit): Modifier {
    var longDone by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { e ->
        when {
            e.key == Key.Menu && e.type == KeyEventType.KeyDown -> { onFav(); true }
            // Langes OK: erst beim Loslassen auslösen. Öffnet die Aktion ein Menü, bekäme dieses sonst das Loslassen
            // als Klick auf den ersten Eintrag.
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyDown && (longDone || e.nativeKeyEvent.isLongPress) -> { longDone = true; true }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyUp && longDone -> { longDone = false; onFav(); true }
            else -> false
        }
    }
}

/**
 * Menü-Taste öffnet ein Menü ([onMenu]), OK lange gedrückt macht [onLong] (beim Loslassen).
 * Viele Fernbedienungen (Fire TV) haben keine Menü-Taste: dann öffnet langes OK dasselbe Menü.
 */
@Composable
fun Modifier.menuKeys(onMenu: () -> Unit, onLong: () -> Unit): Modifier {
    var longDone by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { e ->
        when {
            e.key == Key.Menu && e.type == KeyEventType.KeyDown -> { onMenu(); true }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyDown && (longDone || e.nativeKeyEvent.isLongPress) -> { longDone = true; true }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyUp && longDone -> { longDone = false; onLong(); true }
            else -> false
        }
    }
}
