package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Einstellungen > Live TV: Zurückspulen (Puffer auf dem Gerät) und Aufnahmen. */
@Composable
internal fun LiveBufferSection(ctx: Context) {
    LiveSettings.init(ctx)
    SectionTitle("DVR / Timeshift")
    ToggleRow(
        "Enable DVR / Timeshift",
        "Automatically buffers Live TV and Multi-View streams on this device so you can pause and rewind without provider catch-up. Works with supported continuous streams; HLS playlists are not supported.",
        LiveSettings.timeshift
    ) { LiveSettings.setTimeshift(ctx, it) }
    val mbs = LiveSettings.BUFFER_MB
    val mbLabels = mbs.map { if (it >= 1024) "${it / 1024} GB" else "$it MB" }
    SelectRow("Buffer size", mbLabels[mbs.indexOf(LiveSettings.bufferMb).coerceAtLeast(0)], mbLabels, mbs.indexOf(LiveSettings.bufferMb).coerceAtLeast(0)) {
        LiveSettings.setBufferMb(ctx, mbs[it])
    }
    val steps = LiveSettings.SEEK_STEPS
    SelectRow("Timeshift seek interval", "${LiveSettings.seekStepSeconds} seconds", steps.map { "$it seconds" }, steps.indexOf(LiveSettings.seekStepSeconds).coerceAtLeast(0)) {
        LiveSettings.setSeekStep(ctx, steps[it])
    }
    Hint("Approximate: 512 MB stores 10–20 minutes of HD video. The buffer is cleared when you leave the player.")
    val dirs = remember { Recorder.dirs(ctx) }
    val labels = dirs.mapIndexed { i, f -> Recorder.dirLabel(ctx, i, f) }
    SelectRow("Recording location", labels.getOrElse(LiveSettings.recDir) { labels.first() }, labels, LiveSettings.recDir.coerceIn(0, labels.lastIndex)) {
        LiveSettings.setRecDir(ctx, it)
    }
    val pads = listOf(0, 2, 3, 5, 10, 15)
    SelectRow("Recording padding after program ends", "${LiveSettings.recPad} min", pads.map { "$it min" }, pads.indexOf(LiveSettings.recPad).coerceAtLeast(0)) {
        LiveSettings.setRecPad(ctx, pads[it])
    }
    Hint("To record, press the red button while watching a channel. The program records until its scheduled end in the TV guide; if it is already in the buffer, recording can include the beginning. Watching another channel at the same time requires a second provider connection. Recordings are stored in the app folder and are deleted if you uninstall the app.")
    RecordingsList(ctx)
}

@Composable
private fun RecordingsList(ctx: Context) {
    var tick by remember { mutableIntStateOf(0) }
    val running = Recorder.active.size
    val list = remember(tick, running) { Recorder.list(ctx) }
    SectionTitle("Aufnahmen (${list.size})")
    if (list.isEmpty()) { Hint("No recordings yet."); return }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in list.take(40)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x12FFFFFF)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text((if (f.running) "● " else "") + f.title, color = if (f.running) Color(0xFFFF6B6B) else Color.White, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
                    Text(
                        listOf(f.channel, dayLabel(f.startMs / 1000) + " " + hhmm(f.startMs / 1000), (f.bytes / (1024 * 1024)).toString() + " MB").filter { it.isNotBlank() }.joinToString("  ·  "),
                        color = Muted, fontSize = 13.sp, maxLines = 1, raw = true
                    )
                }
                if (f.running) SmallPill("Stop") { Recorder.active.firstOrNull { it.file == f.file }?.let { Recorder.stop(ctx, it) }; tick++ }
                SmallPill("Delete") { Recorder.delete(ctx, f); tick++ }
            }
        }
    }
    Hint("Playback: Live TV > “Recordings”.")
}
