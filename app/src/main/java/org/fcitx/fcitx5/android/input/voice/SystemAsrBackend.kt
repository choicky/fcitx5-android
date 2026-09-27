/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import timber.log.Timber

/** Android SpeechRecognizer bound to the system default RecognitionService. */
internal class SystemAsrBackend(private val context: Context) : VoiceBackend {

    private var recognizer: SpeechRecognizer? = null

    override fun start(token: Long, languageTag: String, events: VoiceBackend.Events) {
        runCatching {
            val activeRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listenerFor(token, events))
            }.also { recognizer = it }
            activeRecognizer.startListening(recognizerIntent(languageTag))
            events.onStarted(token)
        }.onFailure {
            Timber.w(it, "Unable to start voice input")
            release()
            events.onError(token, VoiceError.Silent)
        }
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun listenerFor(token: Long, events: VoiceBackend.Events) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            events.onEndOfSpeech(token)
        }

        override fun onError(error: Int) {
            Timber.w("Voice input failed with SpeechRecognizer error $error")
            val mapped = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT -> VoiceError.Silent
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.PermissionDenied
                else -> VoiceError.System(error)
            }
            release()
            events.onError(token, mapped)
        }

        override fun onResults(results: Bundle?) {
            val transcript = results?.transcripts()?.firstOrNull()
            release()
            events.onFinal(token, transcript)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.transcripts()?.firstOrNull()?.let { events.onPartial(token, it) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun recognizerIntent(languageTag: String) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (languageTag.isNotBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun Bundle.transcripts(): List<String>? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
}
