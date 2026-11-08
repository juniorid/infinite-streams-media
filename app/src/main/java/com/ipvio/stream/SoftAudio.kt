package com.ipvio.stream

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Software-Tondecoder (FFmpeg): spielt Tonformate wie AC3, E-AC3, DTS oder TrueHD auch dann, wenn das Gerät sie nicht selbst
 * decodieren kann – typisch mit Bluetooth-Kopfhörern (AirPods), wo es keinen Durchreich-Ausgang an den Receiver gibt.
 * Bleibt wie bisher, wenn die Bibliothek fehlt oder nicht zur Media3-Version passt: der Test erzeugt einmal probeweise den
 * Decoder, bevor er eingeschaltet wird, und fängt jeden Fehler ab (kein Absturz beim Abspielen).
 */
object SoftAudio {
    private var checked = false
    private var ok = false

    fun available(ctx: Context): Boolean {
        if (checked) return ok
        checked = true
        ok = try {
            val c = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
                .getConstructor(Handler::class.java, AudioRendererEventListener::class.java, AudioSink::class.java)
            c.newInstance(null, null, DefaultAudioSink.Builder(ctx.applicationContext).build())
            true
        } catch (t: Throwable) {
            false
        }
        return ok
    }

    fun factory(ctx: Context): DefaultRenderersFactory =
        DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(if (available(ctx)) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
}
