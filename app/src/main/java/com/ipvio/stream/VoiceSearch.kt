package com.ipvio.stream

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Sprachsuche.
 * - Geräte mit Spracherkennung (Google TV, Android TV, Handys): Mikrofon-Knopf → System-Spracherkennung.
 * - Fire TV: Amazon lässt Apps keine eigene Spracherkennung nutzen (die Mikrofontaste gehört Alexa);
 *   dort gibt es keinen Mikrofon-Knopf.
 */
object VoiceSearch {
    /** Gibt es eine Spracherkennung, die Apps nutzen dürfen? */
    fun available(c: Context): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(c) &&
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(c.packageManager) != null
    }.getOrDefault(false)

    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.locale().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PROMPT, Lang.tr("Film, Serie oder Sender sagen …"))
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
}

