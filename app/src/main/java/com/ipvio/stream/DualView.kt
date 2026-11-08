package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Zweiter Stream im Live-Player (Runde 22): ein eigener, schlanker Player neben dem Hauptbild.
 * Bild-in-Bild (kleines Fenster) oder geteilter Bildschirm (halbe Breite). Der Ton kommt immer nur von einem der beiden Streams.
 * Der zweite Stream lädt weniger voraus und decodiert höchstens 720p (PiP: SD), damit schwache Geräte nicht überfordert werden.
 * Hinweis: Viele Anbieter erlauben nur eine Verbindung je Zugang – dann bricht der zweite Stream ab und die App meldet es.
 */
@Composable
internal fun SecondStream(item: Item, split: Boolean, audioOn: Boolean, cropToFill: Boolean = false, selected: Boolean = false, onSelect: () -> Unit = {}, modifier: Modifier) {
    val ctx = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val p = remember {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 15_000, 1_000, 2_000).build()
        ExoPlayer.Builder(ctx, SoftAudio.factory(ctx))
            .setLoadControl(lc)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(TapAwareDataSource.Factory(DefaultDataSource.Factory(ctx, http))))
            .build()
            .also {
                it.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failed = true }
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) failed = false }
                })
            }
    }
    LiveSettings.init(ctx)
    val tap = remember(item.url, LiveSettings.timeshift) {
        if (LiveSettings.timeshift && TapLogic.tappable(item.url)) LiveTaps.open(ctx, item.url) else null
    }
    var tapBaseMs by remember(item.url) { mutableStateOf(0L) }
    DisposableEffect(tap) { onDispose { tap?.release() } }
    DisposableEffect(Unit) { onDispose { runCatching { p.release() } } }
    // Auflösung begrenzen: im kleinen Fenster reicht SD, geteilt höchstens 720p
    LaunchedEffect(split) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(if (split) 1280 else 854, if (split) 720 else 480)
            .build()
    }
    LaunchedEffect(item.url, tap) {
        failed = false
        val source = if (tap != null) {
            repeat(40) {
                if (tap.index.size() > 0 && tap.nowMs() >= 2500) return@repeat
                kotlinx.coroutines.delay(250)
            }
            val target = (tap.nowMs() - 2000).coerceAtLeast(tap.oldestMs())
            val byte = if (tap.index.size() == 0) tap.start else tap.index.byteAt(target)
            tapBaseMs = tap.index.msAt(byte)
            "tap://${tap.id}/$byte.ts"
        } else item.url
        if (!openStream(p, source, if (tap != null) 0L else C.TIME_UNSET)) failed = true
    }
    LaunchedEffect(audioOn) { p.volume = if (audioOn) 1f else 0f }
    var isPlaying by remember { mutableStateOf(true) }
    LaunchedEffect(p) { isPlaying = p.isPlaying }
    val shape = RoundedCornerShape(if (split) 0.dp else 10.dp)
    Box(modifier.clip(shape).background(Color.Black).border(if (selected) 3.dp else if (split) 0.dp else 2.dp, if (selected) Accent2 else Color(0x66FFFFFF), shape).clickable { onSelect() }) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = if (cropToFill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = p
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(raw = true, text = (if (audioOn) "♪ " else "") + item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Row(Modifier.align(Alignment.BottomCenter).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isPlaying) "Ⅱ PAUSE" else "▶ PLAY", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { isPlaying = !isPlaying; p.playWhenReady = isPlaying }.padding(horizontal = 8.dp, vertical = 5.dp))
                val stepMs = LiveSettings.seekStepSeconds * 1000L
                val canBufferSeek = tap != null && tap.index.size() > 1 && !tap.closed
                val canProviderSeek = tap == null && p.isCurrentMediaItemSeekable && p.duration > 0
                val canTimeshift = canBufferSeek || canProviderSeek
                Text("−${LiveSettings.seekStepSeconds}s", color = if (canTimeshift) Color.White else Color.Gray, fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled = canTimeshift) {
                        if (canBufferSeek) {
                            val target = (tapBaseMs + p.currentPosition - stepMs).coerceAtLeast(tap!!.oldestMs())
                            val byte = tap.index.byteAt(target)
                            tapBaseMs = tap.index.msAt(byte)
                            openStream(p, "tap://${tap.id}/$byte.ts", 0L)
                        } else p.seekTo((p.currentPosition - stepMs).coerceAtLeast(0L))
                    }.padding(horizontal = 8.dp, vertical = 5.dp))
                Text("+${LiveSettings.seekStepSeconds}s", color = if (canTimeshift) Color.White else Color.Gray, fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled = canTimeshift) {
                        if (canBufferSeek) {
                            val target = (tapBaseMs + p.currentPosition + stepMs).coerceAtMost(tap!!.nowMs() - 1500)
                            val byte = tap.index.byteAt(target)
                            tapBaseMs = tap.index.msAt(byte)
                            openStream(p, "tap://${tap.id}/$byte.ts", 0L)
                        } else p.seekTo((p.currentPosition + stepMs).coerceAtMost(p.duration))
                    }.padding(horizontal = 8.dp, vertical = 5.dp))
                if (!canTimeshift) Text(if (LiveSettings.timeshift) "Buffering DVR…" else "Enable DVR in Settings", color = Color.LightGray, fontSize = 10.sp)
            }
        }
        if (failed) Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
            Text("Zweiter Stream nicht abspielbar", color = Color.White, fontSize = 14.sp)
        }
    }
}

/** Gespeicherte Einstellungen für den zweiten Stream: Anordnung, Fenstergröße und Ecke (Bild-in-Bild), Ton. */
object DualPrefs {
    val SIZES = listOf(220, 300, 400, 520)
    val SIZE_NAMES = listOf("Klein", "Mittel", "Groß", "Sehr groß")
    val CORNER_NAMES = listOf("Unten rechts", "Unten links", "Oben rechts", "Oben links")
    val ARRANGE_NAMES = listOf("Bild-in-Bild", "Geteilt")

    var split by mutableIntStateOf(0)
        private set
    var size by mutableIntStateOf(1)
        private set
    var corner by mutableIntStateOf(0)
        private set
    var audioSecond by mutableStateOf(false)
        private set
    private var inited = false

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = Profiles.sp(c, "ipvio_dual")
        split = p.getInt("split", 0).coerceIn(0, 1)
        size = p.getInt("size", 1).coerceIn(0, SIZES.lastIndex)
        corner = p.getInt("corner", 0).coerceIn(0, CORNER_NAMES.lastIndex)
        audioSecond = p.getBoolean("audio", false)
    }

    fun set(c: Context, split: Int = this.split, size: Int = this.size, corner: Int = this.corner, audio: Boolean = this.audioSecond) {
        this.split = split.coerceIn(0, 1)
        this.size = size.coerceIn(0, SIZES.lastIndex)
        this.corner = corner.coerceIn(0, CORNER_NAMES.lastIndex)
        this.audioSecond = audio
        Profiles.sp(c, "ipvio_dual").edit().putInt("split", this.split).putInt("size", this.size).putInt("corner", this.corner).putBoolean("audio", audio).apply()
    }
}

/** Position und Größe des kleinen Fensters (Bild-in-Bild). Unten bleibt Platz für die Steuerleiste. */
internal fun BoxScope.pipModifier(): Modifier {
    val align = when (DualPrefs.corner) {
        1 -> Alignment.BottomStart
        2 -> Alignment.TopEnd
        3 -> Alignment.TopStart
        else -> Alignment.BottomEnd
    }
    val bottom = DualPrefs.corner < 2
    val side = 28.dp
    return Modifier.align(align)
        .padding(start = side, end = side, top = if (bottom) 0.dp else 60.dp, bottom = if (bottom) 140.dp else 0.dp)
        .width(DualPrefs.SIZES[DualPrefs.size].dp).aspectRatio(16f / 9f)
}
