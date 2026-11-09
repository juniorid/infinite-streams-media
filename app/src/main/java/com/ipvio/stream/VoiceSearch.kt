package com.ipvio.stream

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** System speech recognition for Android phones and Android TV / Google TV devices. */
object VoiceSearch {
    fun available(c: android.content.Context): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(c) ||
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(c.packageManager) != null
    }.getOrDefault(false)

    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.locale().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PROMPT, Lang.tr("Speak a movie, series or channel name"))
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

    /** Some Android TV builds expose voice search via WEB_SEARCH rather than RECOGNIZE_SPEECH. */
    fun fallbackIntent(): Intent = Intent(RecognizerIntent.ACTION_WEB_SEARCH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.locale().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PROMPT, Lang.tr("Speak a movie, series or channel name"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
