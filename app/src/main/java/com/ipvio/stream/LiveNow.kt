package com.ipvio.stream

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.aspectRatio

/*
 * Runde 17b – Live TV im „Cinematic“-Stil (Entwurf Bild 3): Hintergrund in der Farbe des markierten Senders mit großem,
 * blassem Logo; rechts groß, was gerade läuft (LIVE, Titel, Beschreibung, Zeitleiste) und was danach kommt.
 */

/** Hintergrund der Live-Seite: Farbe und Logo des markierten Senders, weich überblendet. */
@Composable
fun LiveBackdrop(ch: Item?, modifier: Modifier = Modifier) {
    Box(modifier.background(Bg)) {
        Crossfade(ch, animationSpec = tween(if (Look.motion == 0) 0 else 700), label = "liveBg") { c ->
            if (c != null) {
                val col = colorFor(c)
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(col.copy(alpha = 0.55f), Color.Transparent), radius = 1400f, center = androidx.compose.ui.geometry.Offset(1500f, 380f))))
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(col, Color.White, 0.2f).copy(alpha = 0.22f), Color.Transparent), radius = 900f, center = androidx.compose.ui.geometry.Offset(1900f, 900f))))
                if (c.logo != null) AsyncImage(
                    c.logo, null,
                    Modifier.align(Alignment.TopEnd).padding(top = dpx(60), end = dpx(80)).size(dpx(360)).graphicsLayer { alpha = 0.12f },
                    contentScale = ContentScale.Fit
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.3f to Bg.copy(alpha = .9f), 0.55f to Bg.copy(alpha = .35f), 1f to Bg.copy(alpha = .15f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg.copy(alpha = .9f))))
    }
}

/** Was auf dem markierten Sender läuft: groß, mit Zeitleiste und den nächsten Sendungen. */
@Composable
fun LiveNowPanel(ch: Item?, modifier: Modifier = Modifier) {
    if (ch == null) return
    Crossfade(ch, animationSpec = tween(if (Look.motion == 0) 0 else 400), label = "liveNow", modifier = modifier) { c ->
        val epg by produceState(emptyList<EpgEntry>(), c.id) { value = Epg.now(c) }
        val nowE = Epg.current(epg)
        val next = if (nowE != null) epg.filter { it.start >= nowE.end }.take(2) else epg.take(2)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dpx(18))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                LiveBadge()
                Text(raw = true, text = c.title + (nowE?.let { "  ·  " + hhmm(it.start) + " – " + hhmm(it.end) } ?: ""),
                    color = Tok.TextMid, fontSize = spx(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(raw = true, text = nowE?.title ?: c.title, color = Tok.TextHi, fontSize = spx(68), lineHeight = spx(70),
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            nowE?.desc?.takeIf { it.isNotBlank() }?.let {
                Text(raw = true, text = it, color = Tok.TextMid, fontSize = spx(21), lineHeight = spx(30), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (nowE != null) {
                val frac = ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
                    Text(raw = true, text = hhmm(nowE.start), color = Muted, fontSize = spx(17))
                    Box(Modifier.weight(1f).height(dpx(6)).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50)).background(Accent1))
                    }
                    Text(raw = true, text = hhmm(nowE.end), color = Muted, fontSize = spx(17))
                }
            }
            if (next.isNotEmpty()) Row(Modifier.padding(top = dpx(12)), horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
                next.forEachIndexed { i, e ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(dpx(18))).background(Color(0x12FFFFFF))
                            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(dpx(18))).padding(dpx(18)),
                        verticalArrangement = Arrangement.spacedBy(dpx(6))
                    ) {
                        Text((if (i == 0) Lang.tr("Danach") + " · " else "") + hhmm(e.start), raw = true,
                            color = if (i == 0) Accent2 else Muted, fontSize = spx(15), fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
                        Text(raw = true, text = e.title, color = Tok.TextHi, fontSize = spx(21), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (nowE == null && epg.isEmpty()) Text("Für diesen Sender gibt es kein Programm.", color = Muted, fontSize = spx(18))
        }
    }
}

/** Muted, lightweight live preview for the channel currently highlighted in the Live TV list. */
@Composable
fun LivePreviewWindow(ch: Item?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var previewStatus by remember(ch?.id, ch?.url) { mutableStateOf("Connecting to stream…") }
    val player = remember(ch?.id, ch?.url) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(LiveTaps.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        ExoPlayer.Builder(ctx, SoftAudio.factory(ctx))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5_000, 15_000, 1_000, 3_000).build())
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(TapAwareDataSource.Factory(DefaultDataSource.Factory(ctx, http))))
            .build().apply {
                volume = 0f
                videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            }
    }
    LaunchedEffect(player, ch?.url) {
        val url = ch?.url
        if (!url.isNullOrBlank()) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                previewStatus = when (playbackState) {
                    Player.STATE_BUFFERING -> "Buffering live stream…"
                    Player.STATE_READY -> ""
                    Player.STATE_ENDED -> "Stream ended"
                    else -> "Waiting for stream…"
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                previewStatus = "Preview error: " + error.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val shape = RoundedCornerShape(dpx(18))
    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
            .background(Color(0xFF05070B)).border(1.dp, Accent1.copy(alpha = .65f), shape)
    ) {
        if (ch != null && !ch.url.isNullOrBlank()) {
            val previewPlayer = player
            AndroidView(
                factory = { c -> PlayerView(c).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setKeepContentOnPlayerReset(false)
                    this.player = previewPlayer
                } },
                update = { it.player = previewPlayer },
                modifier = Modifier.fillMaxSize()
            )
            if (previewStatus.isNotBlank()) {
                Text(previewStatus, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC080B12)).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("LIVE PREVIEW", color = Accent1, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                Text(if (ch == null) "Select a channel" else "No stream URL", color = Muted, fontSize = 14.sp)
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LiveBadge()
            Text(ch?.title.orEmpty(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Rotes „LIVE“ mit pulsierendem Punkt (ohne Animationen ruhig). */
@Composable
fun LiveBadge() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "liveA")
    Row(
        Modifier.clip(RoundedCornerShape(dpx(8))).background(Color(0xFFE11D48)).padding(horizontal = dpx(12), vertical = dpx(4)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        Box(Modifier.size(dpx(9)).graphicsLayer { alpha = if (Look.motion == 0) 1f else a }.clip(RoundedCornerShape(50)).background(Color.White))
        Text(raw = true, text = "LIVE", color = Color.White, fontSize = spx(15), fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
    }
}
