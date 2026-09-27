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
    private val showVoiceInputButton by AppPrefs.getInstance().voice.showVoiceInputButton
    private val asrProvider by AppPrefs.getInstance().voice.asrProvider
    private val systemAsrAllowed by AppPrefs.getInstance().voice.systemAsrAllowed
    private val systemAsrAnswered by AppPrefs.getInstance().internal.voiceSystemAsrAnswered
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
     * What a session would use now, for both the trigger and [start] (D030: it follows this
     * resolution, not System ASR availability). Debug PoC switches override the formal provider.
     */
    private val resolution: AsrResolution
        get() = resolveVoiceBackend(
            debugOverride = debugBackendOverride(
                BuildConfig.DEBUG, voiceDoubaoAsr, voiceCaptureProbe
            ),
            configured = asrProvider,
            usableLocal = usableLocalModel(),
            systemAuthorization = SystemAsrAuthorization.of(systemAsrAllowed, systemAsrAnswered),
            systemAvailable = { SpeechRecognizer.isRecognitionAvailable(service) }
        )

    /** The configured Local model if its files are in place; no model is loaded here. */
    private fun usableLocalModel(): LocalAsrModel? =
        configuredLocalModel(LocalAsrEngines.AVAILABLE, voiceLocalAsr)?.takeIf { model ->
            localAsrModelDir(model)?.let { model.missingFiles(it).isEmpty() } == true
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
        return showVoiceInputButton && !passwordField && resolution.offersTrigger
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
        releaseLocalAsr()
    }

    /** May wait for a decode still running on a cancelled session; kept off the main thread. */
    private fun releaseLocalAsr() {
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
        val resolved = resolution
        // a cached Local ASR model (about 1 GB for FunASR Nano) is not kept once Local is off
        if ((resolved as? AsrResolution.Ready)?.backend !is VoiceBackendKind.LocalAsr) {
            releaseLocalAsr()
        }
        val backend = when (resolved) {
            is AsrResolution.Ready -> resolved.backend
            AsrResolution.NeedsSystemAuthorization -> {
                requestSystemAsrAuthorization()
                return
            }
            AsrResolution.LocalUnavailable -> {
                service.toast(R.string.voice_local_unavailable)
                return
            }
            AsrResolution.SystemUnavailable -> {
                service.toast(R.string.voice_input_unavailable)
                return
            }
            AsrResolution.NoProvider -> {
                service.toast(R.string.voice_no_provider)
                return
            }
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

    private fun requestSystemAsrAuthorization() {
        service.startActivity(Intent(service, MainActivity::class.java).apply {
            action = MainActivity.ACTION_AUTHORIZE_SYSTEM_ASR
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun requestRecordAudioPermission() {
        service.startActivity(Intent(service, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_RECORD_AUDIO_PERMISSION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

/** The backends a session can run on; chosen by [resolveVoiceBackend]. */
internal sealed interface VoiceBackendKind {
    data object System : VoiceBackendKind
    data object CaptureProbe : VoiceBackendKind
    data object DoubaoAsr : VoiceBackendKind
    data class LocalAsr(val model: LocalAsrModel) : VoiceBackendKind
}

/** The debug preference value is a [LocalAsrModel] name, or anything else for off. */
internal fun localAsrModel(value: String): LocalAsrModel? =
    LocalAsrModel.entries.firstOrNull { it.name == value }

internal fun localAsrThreads(value: String): Int =
    value.toIntOrNull()?.coerceIn(1, 4) ?: DEFAULT_LOCAL_ASR_THREADS

internal const val DEFAULT_LOCAL_ASR_THREADS = 2
