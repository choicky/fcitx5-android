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
import kotlin.concurrent.thread

class VoiceInputComponent : UniqueComponent<VoiceInputComponent>(), Dependent,
    ManagedHandler by managedHandler(), InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val showVoiceInputButton by AppPrefs.getInstance().keyboard.showVoiceInputButton
    private val voiceCaptureProbe by AppPrefs.getInstance().internal.voiceCaptureProbe
    private val voiceDoubaoAsr by AppPrefs.getInstance().internal.voiceDoubaoAsr
    private val voiceLocalAsr by AppPrefs.getInstance().internal.voiceLocalAsr
    private val voiceLocalAsrThreads by AppPrefs.getInstance().internal.voiceLocalAsrThreads

    // loaded Local ASR models outlive single sessions; released when this component closes
    private val localAsrCache = LocalAsrRecognizerCache { model, threads ->
        LocalAsrEngines.load(model, localAsrModelDir(model)!!, threads)
    }

    private val inputFlow = VoiceInputFlow(object : VoiceInputFlow.Output {
        override fun updateComposing(text: String) = service.updateVoiceComposingText(text)

        override fun clearComposing() = service.clearVoiceComposingText()

        override fun commit(text: String) = service.commitText(text)

        override fun stateChanged(state: VoiceInputSession.State) {
            stateListeners.forEach { it(state) }
        }

        override fun audioLevel(level: Float) {
            levelListeners.forEach { it(level) }
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
    private val levelListeners = mutableListOf<(Float) -> Unit>()

    val toggleCallback = View.OnClickListener { toggle() }

    /**
     * The backend a session would use. Its availability, not System ASR's, decides whether the
     * trigger is offered; release builds currently only have System ASR. Phase 4B debug switches
     * select a Direct backend.
     */
    private val configuredBackend: VoiceBackendKind
        get() = configuredBackendKind(
            debug = BuildConfig.DEBUG,
            localAsr = localAsrModel(voiceLocalAsr),
            doubaoAsr = voiceDoubaoAsr,
            captureProbe = voiceCaptureProbe
        )

    private fun VoiceBackendKind.isAvailable() = when (this) {
        VoiceBackendKind.System -> SpeechRecognizer.isRecognitionAvailable(service)
        // missing Local ASR model files are reported when a session starts
        VoiceBackendKind.CaptureProbe, VoiceBackendKind.DoubaoAsr, is VoiceBackendKind.LocalAsr -> true
    }

    private fun VoiceBackendKind.create(): VoiceBackend = when (this) {
        VoiceBackendKind.System -> SystemAsrBackend(service)
        VoiceBackendKind.CaptureProbe -> CaptureProbeBackend(service, service.lifecycleScope)
        VoiceBackendKind.DoubaoAsr ->
            DoubaoAsrBackend(service.lifecycleScope, DoubaoCredentials.fromBuildConfig())
        is VoiceBackendKind.LocalAsr -> LocalAsrBackend(
            service.lifecycleScope,
            model,
            localAsrThreads(voiceLocalAsrThreads),
            localAsrModelDir(model),
            localAsrCache
        )
    }

    private fun localAsrModelDir(model: LocalAsrModel) =
        LocalAsrBackend.modelDir(service.getExternalFilesDir(null), model)

    internal val state: VoiceInputSession.State
        get() = inputFlow.state

    /** Every voice UI (toolbar button, Space key) follows the one shared session. */
    internal fun addStateListener(listener: (VoiceInputSession.State) -> Unit) {
        stateListeners += listener
        listener(inputFlow.state)
    }

    /** Microphone level, only while a backend that owns the PCM is capturing. */
    internal fun addAudioLevelListener(listener: (Float) -> Unit) {
        levelListeners += listener
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
        levelListeners.clear()
        // may wait for a decode still running on the cancelled session; keep it off the main thread
        thread(name = "local-asr-release") { localAsrCache.clear() }
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

/** Phase 4B debug backend choice; release builds always use System ASR. */
internal sealed interface VoiceBackendKind {
    data object System : VoiceBackendKind
    data object CaptureProbe : VoiceBackendKind
    data object DoubaoAsr : VoiceBackendKind
    data class LocalAsr(val model: LocalAsrModel) : VoiceBackendKind
}

internal fun configuredBackendKind(
    debug: Boolean,
    localAsr: LocalAsrModel?,
    doubaoAsr: Boolean,
    captureProbe: Boolean
): VoiceBackendKind = when {
    !debug -> VoiceBackendKind.System
    localAsr != null -> VoiceBackendKind.LocalAsr(localAsr)
    doubaoAsr -> VoiceBackendKind.DoubaoAsr
    captureProbe -> VoiceBackendKind.CaptureProbe
    else -> VoiceBackendKind.System
}

/** The debug preference value is a [LocalAsrModel] name, or anything else for off. */
internal fun localAsrModel(value: String): LocalAsrModel? =
    LocalAsrModel.entries.firstOrNull { it.name == value }

internal fun localAsrThreads(value: String): Int =
    value.toIntOrNull()?.coerceIn(1, 4) ?: DEFAULT_LOCAL_ASR_THREADS

internal const val DEFAULT_LOCAL_ASR_THREADS = 2
