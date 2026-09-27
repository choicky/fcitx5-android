/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.fcitx.fcitx5.android.BuildConfig
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

class VoiceInputComponent : UniqueComponent<VoiceInputComponent>(), Dependent,
    ManagedHandler by managedHandler(), InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val showVoiceInputButton by AppPrefs.getInstance().keyboard.showVoiceInputButton
    private val voiceCaptureProbe by AppPrefs.getInstance().internal.voiceCaptureProbe
    private val voiceDoubaoAsr by AppPrefs.getInstance().internal.voiceDoubaoAsr

    private val inputFlow = VoiceInputFlow(object : VoiceInputFlow.Output {
        override fun updateComposing(text: String) = service.updateVoiceComposingText(text)

        override fun clearComposing() = service.clearVoiceComposingText()

        override fun commit(text: String) = service.commitText(text)

        override fun stateChanged(state: VoiceInputSession.State) {
            stateListeners.forEach { it(state) }
        }

        override fun reportError(error: VoiceError) {
            when (error) {
                VoiceError.Silent -> Unit
                VoiceError.PermissionDenied -> requestRecordAudioPermission()
                is VoiceError.System ->
                    service.toast(service.getString(R.string.voice_input_error, error.code))
                is VoiceError.Capture ->
                    service.toast(service.getString(R.string.voice_capture_error, error.detail))
                is VoiceError.Service ->
                    service.toast(service.getString(R.string.voice_asr_error, error.detail))
            }
        }
    })

    private var languageCode = ""
    private var passwordField = false
    private val stateListeners = mutableListOf<(VoiceInputSession.State) -> Unit>()

    val toggleCallback = View.OnClickListener { toggle() }

    private enum class Backend { System, CaptureProbe, DoubaoAsr }

    /**
     * The backend a session would use. Its availability, not System ASR's, decides whether the
     * trigger is offered; release builds currently only have System ASR. Phase 4B debug switches
     * select a Direct backend.
     */
    private val configuredBackend: Backend
        get() = when {
            BuildConfig.DEBUG && voiceDoubaoAsr -> Backend.DoubaoAsr
            BuildConfig.DEBUG && voiceCaptureProbe -> Backend.CaptureProbe
            else -> Backend.System
        }

    private fun Backend.isAvailable() = when (this) {
        Backend.System -> SpeechRecognizer.isRecognitionAvailable(service)
        Backend.CaptureProbe, Backend.DoubaoAsr -> true
    }

    private fun Backend.create(): VoiceBackend = when (this) {
        Backend.System -> SystemAsrBackend(service)
        Backend.CaptureProbe -> CaptureProbeBackend(service, service.lifecycleScope)
        Backend.DoubaoAsr ->
            DoubaoAsrBackend(service.lifecycleScope, DoubaoCredentials.fromBuildConfig())
    }

    internal val state: VoiceInputSession.State
        get() = inputFlow.state

    /** Every voice UI (toolbar button, Space key) follows the one shared session. */
    internal fun addStateListener(listener: (VoiceInputSession.State) -> Unit) {
        stateListeners += listener
        listener(inputFlow.state)
    }

    fun shouldShowVoiceInput(capFlags: CapabilityFlags): Boolean {
        passwordField = capFlags.has(CapabilityFlag.Password)
        return showVoiceInputButton && !passwordField && configuredBackend.isAvailable()
    }

    override fun onStartInput(info: android.view.inputmethod.EditorInfo, capFlags: CapabilityFlags) {
        cancel()
        passwordField = capFlags.has(CapabilityFlag.Password)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        languageCode = ime.languageCode.replace("_", "-")
    }

    fun toggle() {
        when (inputFlow.state) {
            VoiceInputSession.State.Idle -> start()
            VoiceInputSession.State.Starting,
            VoiceInputSession.State.Listening -> inputFlow.stop()
            VoiceInputSession.State.Stopping -> Unit
        }
    }

    fun cancel() {
        inputFlow.cancel()
    }

    /** Long-press Space: start a session unless one is already running. */
    fun startVoiceInput() {
        if (inputFlow.state == VoiceInputSession.State.Idle) start()
    }

    /** Space released: stop normally, letting the backend deliver its final result. */
    fun stopVoiceInput() {
        inputFlow.stop()
    }

    fun close() {
        inputFlow.close()
        stateListeners.clear()
    }

    private fun start() {
        if (passwordField) return
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestRecordAudioPermission()
            return
        }
        val backend = configuredBackend
        if (!backend.isAvailable()) {
            service.toast(R.string.voice_input_unavailable)
            return
        }
        val token = inputFlow.begin() ?: return
        service.lifecycleScope.launch {
            // Fcitx InputContext::reset dispatches the engine ResetEvent. The pinned Pinyin
            // implementation clears its context and updates preedit without committing it.
            service.prepareForVoiceInput().join()
            yield()
            inputFlow.launch(token, languageCode, backend.create())
        }
    }

    private fun requestRecordAudioPermission() {
        service.startActivity(Intent(service, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_RECORD_AUDIO_PERMISSION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
