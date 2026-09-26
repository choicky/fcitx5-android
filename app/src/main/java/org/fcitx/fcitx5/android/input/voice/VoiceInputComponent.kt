/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.toast
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import timber.log.Timber

class VoiceInputComponent : UniqueComponent<VoiceInputComponent>(), Dependent,
    ManagedHandler by managedHandler(), InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val showVoiceInputButton by AppPrefs.getInstance().keyboard.showVoiceInputButton
    private val session = VoiceInputSession()

    private var recognizer: SpeechRecognizer? = null
    private var languageCode = ""
    private var passwordField = false
    private var stateListener: ((VoiceInputSession.State) -> Unit)? = null

    val toggleCallback = View.OnClickListener { toggle() }

    internal fun setStateListener(listener: (VoiceInputSession.State) -> Unit) {
        stateListener = listener
        listener(session.state)
    }

    fun shouldShowVoiceInput(capFlags: CapabilityFlags): Boolean {
        passwordField = capFlags.has(CapabilityFlag.Password)
        return showVoiceInputButton && !passwordField &&
            SpeechRecognizer.isRecognitionAvailable(service)
    }

    override fun onStartInput(info: android.view.inputmethod.EditorInfo, capFlags: CapabilityFlags) {
        cancel()
        passwordField = capFlags.has(CapabilityFlag.Password)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        languageCode = ime.languageCode.replace("_", "-")
    }

    fun toggle() {
        when (session.state) {
            VoiceInputSession.State.Idle -> start()
            VoiceInputSession.State.Starting,
            VoiceInputSession.State.Listening -> stop()
            VoiceInputSession.State.Stopping -> Unit
        }
    }

    fun cancel() {
        if (!session.cancel()) return
        recognizer?.cancel()
        releaseRecognizer()
        service.clearVoiceComposingText()
        notifyState()
    }

    fun close() {
        val wasActive = session.cancel()
        if (wasActive) recognizer?.cancel()
        releaseRecognizer()
        if (wasActive) service.clearVoiceComposingText()
        notifyState()
        stateListener = null
    }

    private fun start() {
        if (passwordField) return
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestRecordAudioPermission()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(service)) {
            service.toast(R.string.voice_input_unavailable)
            return
        }
        val token = session.start() ?: return
        notifyState()
        service.lifecycleScope.launch {
            // Fcitx InputContext::reset dispatches the engine ResetEvent. The pinned Pinyin
            // implementation clears its context and updates preedit without committing it.
            service.prepareForVoiceInput().join()
            yield()
            if (!session.accepts(token) || session.state != VoiceInputSession.State.Starting) {
                return@launch
            }
            service.clearVoiceComposingText()
            runCatching {
                val activeRecognizer = SpeechRecognizer.createSpeechRecognizer(service).apply {
                    setRecognitionListener(listenerFor(token))
                }.also { recognizer = it }
                activeRecognizer.startListening(recognizerIntent())
                if (session.onRecognizerStarted(token)) notifyState()
            }.onFailure {
                Timber.w(it, "Unable to start voice input")
                finishWithError(token, null)
            }
        }
    }

    private fun stop() {
        when (session.stop()) {
            VoiceInputSession.StopAction.CancelPendingStart -> {
                service.clearVoiceComposingText()
                notifyState()
            }
            VoiceInputSession.StopAction.StopRecognizer -> {
                recognizer?.stopListening()
                notifyState()
            }
            VoiceInputSession.StopAction.None -> Unit
        }
    }

    private fun listenerFor(token: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (session.onEndOfSpeech(token)) notifyState()
        }

        override fun onError(error: Int) {
            if (!session.accepts(token)) return
            Timber.w("Voice input failed with SpeechRecognizer error $error")
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT -> null
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    requestRecordAudioPermission()
                    null
                }
                else -> service.getString(R.string.voice_input_error, error)
            }
            finishWithError(token, message)
        }

        override fun onResults(results: Bundle?) {
            if (!session.accepts(token)) return
            val transcript = results?.transcripts()?.firstOrNull()
            if (!session.complete(token)) return
            releaseRecognizer()
            if (transcript.isNullOrBlank()) {
                service.clearVoiceComposingText()
            } else {
                service.commitText(transcript)
            }
            notifyState()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!session.accepts(token)) return
            partialResults?.transcripts()?.firstOrNull()?.let(service::updateVoiceComposingText)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun finishWithError(token: Long, message: String?) {
        if (!session.complete(token)) return
        releaseRecognizer()
        service.clearVoiceComposingText()
        message?.let { service.toast(it) }
        notifyState()
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        if (languageCode.isNotBlank()) {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
        }
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun Bundle.transcripts(): List<String>? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

    private fun requestRecordAudioPermission() {
        service.startActivity(Intent(service, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_RECORD_AUDIO_PERMISSION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun releaseRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun notifyState() {
        stateListener?.invoke(session.state)
    }
}
