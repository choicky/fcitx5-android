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
import org.fcitx.fcitx5.android.input.bar.ToolbarAction
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.toast
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import kotlin.concurrent.thread

class VoiceInputComponent : UniqueComponent<VoiceInputComponent>(), Dependent,
    ManagedHandler by managedHandler(), InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val selectionStore = VoiceSelectionStore(AppPrefs.getInstance()) {
        VoiceSelectionStore.lastErrorFile(service)
    }
    private val credentials by lazy { KeystoreSecretCipher.store(service) }
    private val voiceCaptureProbe by AppPrefs.getInstance().internal.voiceCaptureProbe
    private val voiceDoubaoAsr by AppPrefs.getInstance().internal.voiceDoubaoAsr
    private val voiceLocalAsrThreads by AppPrefs.getInstance().internal.voiceLocalAsrThreads
    private val preferredVoiceInput by AppPrefs.getInstance().keyboard.preferredVoiceInput

    // Observe-only record of control / data-flow decisions (F1). Nothing here is read back to
    // influence a session; a failed write is silently ignored.
    private val voiceAudit = VoiceAudit(file = { VoiceAudit.auditFile(service) })

    /** Stable per in-IME session id for correlating audit events; distinct from the backend generation. */
    private var auditSessionId = 0L

    // loaded Local ASR models outlive single sessions; released when this component closes
    private val localAsrCache = LocalAsrRecognizerCache { model, threads ->
        LocalAsrEngines.load(model, localAsrModelDir(model), threads)
    }

    private val inputFlow = VoiceInputFlow(object : VoiceInputFlow.Output {
        override fun updateComposing(text: String) = service.updateVoiceComposingText(text)

        override fun clearComposing() = service.clearVoiceComposingText()

        // audit records only that a non-blank result was committed; the text is never referenced
        override fun commit(text: String) {
            voiceAudit.record(CommittedEvent(auditSessionId))
            service.commitText(text)
        }

        override fun stateChanged(state: VoiceInputSession.State) {
            voiceAudit.record(StateEvent(auditSessionId, state))
            stateListeners.forEach { it(state) }
        }

        override fun audioLevel(level: Float) {
            levelListeners.forEach { it(level) }
        }

        override fun fellBack(error: VoiceError) {
            // the change of recipient is shown and recorded as the service actually used
            fallbackService?.let {
                selectionStore.lastUsedService = it.key
                voiceAudit.record(FallbackEvent(auditSessionId, (it as? AsrServiceId.Local)?.model?.name ?: it.key))
            }
            service.toast(R.string.voice_fell_back_to_local)
        }

        override fun reportError(error: VoiceError) {
            val (errorClass, systemCode) = error.auditClass()
            voiceAudit.record(ErrorEvent(auditSessionId, errorClass, systemCode))
            when (error) {
                VoiceError.Silent -> Unit
                VoiceError.PermissionDenied -> requestRecordAudioPermission()
                is VoiceError.System ->
                    service.toast(service.getString(R.string.voice_input_error, error.code))
                is VoiceError.Capture ->
                    service.toast(service.getString(R.string.voice_capture_error, error.detail))
                is VoiceError.Service -> {
                    selectionStore.lastUsedService.takeIf { it.isNotEmpty() }?.let {
                        selectionStore.lastError = it to error.detail
                    }
                    service.toast(service.getString(R.string.voice_asr_error, error.detail))
                }
            }
        }
    })

    private var languageCode = ""
    private var passwordField = false
    private val stateListeners = mutableListOf<(VoiceInputSession.State) -> Unit>()
    private val levelListeners = mutableListOf<(Float) -> Unit>()

    val toggleCallback = View.OnClickListener { toggle() }

    /** What a session would use now. Debug PoC switches override the selection. */
    private val resolution: AsrResolution
        get() = resolveVoiceBackend(
            debugOverride = debugBackendOverride(
                BuildConfig.DEBUG, voiceDoubaoAsr, voiceCaptureProbe
            ),
            selection = selectionStore.load(),
            local = localStatus(),
            systemAuthorization = selectionStore.systemAuthorization,
            systemAvailable = { SpeechRecognizer.isRecognitionAvailable(service) },
            // plain ws:// self-hosted endpoints are only allowed in debug builds
            external = selectionStore.externalServices(credentials, allowCleartext = BuildConfig.DEBUG)
        )

    /** Which on-device models are completely installed; nothing is loaded. */
    private fun localStatus() = LocalStatus(
        LocalAsrEngines.AVAILABLE,
        LocalAsrModel.userVisibleEntries.filterTo(mutableSetOf()) { it.missingFiles(localAsrModelDir(it)).isEmpty() }
    )

    /** The Local service of the current session's fallback backend, if it has one. */
    private var fallbackService: AsrServiceId? = null

    private fun VoiceBackendKind.create(): VoiceBackend = when (this) {
        VoiceBackendKind.System -> SystemAsrBackend(service)
        VoiceBackendKind.CaptureProbe -> CaptureProbeBackend(service, service.lifecycleScope)
        VoiceBackendKind.DoubaoAsr ->
            DoubaoAsrBackend(service.lifecycleScope, DoubaoCredentials.fromBuildConfig())
        // an unreadable store yields incomplete credentials: the session fails early
        VoiceBackendKind.Doubao -> DoubaoAsrBackend(
            service.lifecycleScope,
            DoubaoCredentials.fromStore(credentials.read(DoubaoCredentials.PROVIDER))
        )
        VoiceBackendKind.Qwen -> {
            val config = QwenAsrConfig.fromStore(credentials.read(QwenAsrConfig.PROVIDER))
            NetworkAsrBackend(service.lifecycleScope, "Qwen ${config.model}", secrets = listOf(config.apiKey)) { listener ->
                QwenAsrClient(NetworkAsrBackend.http, config, listener)
            }
        }
        VoiceBackendKind.Tencent -> {
            val config = TencentAsrConfig.fromStore(credentials.read(TencentAsrConfig.PROVIDER))
            NetworkAsrBackend(
                service.lifecycleScope, "Tencent ${config.engine}",
                secrets = listOf(config.secretId, config.secretKey)
            ) { listener ->
                TencentAsrClient(NetworkAsrBackend.http, config, listener)
            }
        }
        is VoiceBackendKind.SelfHosted -> when (instance.protocol) {
            SelfHostedProtocol.SherpaOnnx -> {
                val token = credentials.read(instance.credentialProvider)?.get(SelfHostedInstance.TOKEN)
                NetworkAsrBackend(
                    service.lifecycleScope, "Self-hosted sherpa-onnx", NetworkAsrBackend.SELF_HOSTED_FINAL_TIMEOUT_MS,
                    listOfNotNull(token)
                ) { listener ->
                    SherpaOnnxServerClient(NetworkAsrBackend.http, instance.url, token, listener)
                }
            }
            SelfHostedProtocol.FunAsr2Pass -> {
                val token = credentials.read(instance.credentialProvider)?.get(SelfHostedInstance.TOKEN)
                NetworkAsrBackend(
                    service.lifecycleScope, "Self-hosted FunASR 2pass", NetworkAsrBackend.SELF_HOSTED_FINAL_TIMEOUT_MS,
                    listOfNotNull(token)
                ) { listener ->
                    FunAsr2PassClient(NetworkAsrBackend.http, instance.url, token, listener)
                }
            }
            SelfHostedProtocol.OpenAiCompatible -> {
                val token = credentials.read(instance.credentialProvider)?.get(SelfHostedInstance.TOKEN)
                // upload and transcription of up to 60 s of audio happen after stop
                NetworkAsrBackend(
                    service.lifecycleScope, "Self-hosted OpenAI-compatible", OPENAI_FINAL_TIMEOUT_MS,
                    listOfNotNull(token)
                ) { listener ->
                    OpenAiTranscriptionClient(NetworkAsrBackend.http, instance.url, instance.model, token, listener)
                }
            }
            SelfHostedProtocol.FunAsrNano -> {
                val token = credentials.read(instance.credentialProvider)?.get(SelfHostedInstance.TOKEN)
                NetworkAsrBackend(
                    service.lifecycleScope, "Self-hosted Fun-ASR-Nano", NetworkAsrBackend.SELF_HOSTED_FINAL_TIMEOUT_MS,
                    listOfNotNull(token)
                ) { listener ->
                    FunAsrNanoServerClient(NetworkAsrBackend.http, instance.url, token, listener)
                }
            }
        }
        is VoiceBackendKind.LocalAsr -> LocalAsrBackend(
            service.lifecycleScope,
            model,
            localAsrThreads(voiceLocalAsrThreads),
            localAsrModelDir(model),
            localAsrCache
        )
    }

    private fun localAsrModelDir(model: LocalAsrModel): java.io.File = LocalModels.dir(service, model)

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
        return !passwordField && ToolbarAction.Voice in
            ToolbarAction.decode(AppPrefs.getInstance().internal.toolbarActions.getValue())
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
            VoiceInputSession.State.Listening -> requestStop()
            VoiceInputSession.State.Stopping -> Unit
        }
    }

    fun cancel() {
        if (inputFlow.state != VoiceInputSession.State.Idle) voiceAudit.record(CancelEvent(auditSessionId))
        inputFlow.cancel()
    }

    /** Long-press Space: start a session unless one is already running. */
    fun startVoiceInput() {
        if (inputFlow.state == VoiceInputSession.State.Idle) start()
    }

    /** Space released: stop normally, letting the backend deliver its final result. */
    fun stopVoiceInput() {
        requestStop()
    }

    /** Records a user stop intent when a session is active, then asks the flow to stop. */
    private fun requestStop() {
        if (inputFlow.state != VoiceInputSession.State.Idle) voiceAudit.record(StopEvent(auditSessionId))
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
        val resolved = resolution
        // a cached Local ASR model (about 1 GB for FunASR Nano) is not kept once Local is off
        if ((resolved as? AsrResolution.Ready)?.backend !is VoiceBackendKind.LocalAsr) {
            releaseLocalAsr()
        }
        val recordAudioGranted = service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val step = voiceStartStep(resolved, recordAudioGranted)
        voiceAudit.record(step.toAuditStart(token = 0L))
        val backend = when (step) {
            is VoiceStartStep.Start -> step.backend
            VoiceStartStep.StartExternal -> {
                startExternalVoiceInput()
                return
            }
            VoiceStartStep.RequestSystemAuthorization -> {
                requestSystemAsrAuthorization()
                return
            }
            VoiceStartStep.RequestRecordAudio -> {
                requestRecordAudioPermission()
                return
            }
            is VoiceStartStep.ApplyRecommendation -> {
                selectionStore.save(selectionStore.load().applyRecommendation(step.recommendation))
                // resolves to the recommended service, or to no service with its message
                if (step.recommendation == Recommendation.Nothing) {
                    showUnavailable(AsrResolution.NoService)
                } else {
                    start()
                }
                return
            }
            is VoiceStartStep.Unavailable -> {
                showUnavailable(step.resolution)
                return
            }
        }
        val selected = (resolved as? AsrResolution.Ready)?.service
        selectionStore.lastUsedService = selected?.key ?: ""
        selectionStore.lastError = null
        // D035: a selected external service may fall back to a production Local model only
        val fallbackKind = fallbackTarget(selected, selectionStore.load(), localStatus())
        fallbackService = (fallbackKind as? VoiceBackendKind.LocalAsr)?.let { AsrServiceId.Local(it.model) }
        // bump before begin(): begin() emits the Starting state synchronously, attributed to this
        // session id; if begin() returns null the id is bumped but no event ever used it
        auditSessionId += 1L
        val token = inputFlow.begin() ?: return
        val (dest, kind) = backend.auditDescriptor()
        voiceAudit.record(
            RoutedEvent(
                token = auditSessionId,
                dest = dest,
                kind = kind,
                external = dest != VoiceAuditDest.OnDevice,
                fallbackArmed = fallbackKind != null,
                language = languageCode
            )
        )
        service.lifecycleScope.launch {
            // Fcitx InputContext::reset dispatches the engine ResetEvent. The pinned Pinyin
            // implementation clears its context and updates preedit without committing it.
            service.prepareForVoiceInput().join()
            yield()
            inputFlow.launch(
                token, languageCode, backend.create(),
                fallbackKind?.let { kind -> { kind.create() } }
            )
        }
    }

    /**
     * Model A: hand input to the configured external voice IME. The saved top-level provider is
     * never changed here (D055); if the concrete IME has disappeared the trigger reports it
     * explicitly and opens Voice settings, rather than silently switching to another provider.
     * The "System default" empty id lets Android pick the first enabled voice IME.
     */
    private fun startExternalVoiceInput() {
        val (id, subtype) = InputMethodUtil.findVoiceSubtype(preferredVoiceInput)
            ?: run {
                voiceAudit.record(HandoffEvent(token = 0L, outcome = VoiceAuditHandoff.NoIme))
                showUnavailable(
                    AsrResolution.CurrentUnavailable(AsrServiceId.External, UnavailableReason.NoVoiceIme)
                )
                return
            }
        voiceAudit.record(
            HandoffEvent(token = 0L, outcome = VoiceAuditHandoff.Switched, to = id.substringBefore('/'))
        )
        InputMethodUtil.switchInputMethod(service, id, subtype)
    }

    /** Explain why nothing can start and open the Voice settings to choose a service. */
    private fun showUnavailable(resolution: AsrResolution) {
        val message = when (resolution) {
            is AsrResolution.CurrentUnavailable -> when (resolution.reason) {
                UnavailableReason.Disabled -> R.string.voice_current_disabled
                UnavailableReason.NoSystemRecognizer -> R.string.voice_input_unavailable
                UnavailableReason.NoLocalRuntime -> R.string.voice_local_no_runtime
                UnavailableReason.LocalModelFilesMissing -> R.string.voice_local_unavailable
                UnavailableReason.MissingCredentials -> R.string.voice_missing_credentials
                UnavailableReason.InstanceMissing -> R.string.voice_instance_missing
                UnavailableReason.InvalidEndpoint -> R.string.voice_endpoint_invalid
                UnavailableReason.CleartextEndpoint -> R.string.voice_endpoint_cleartext
                UnavailableReason.NoVoiceIme -> R.string.voice_external_no_ime
            }
            else -> R.string.voice_no_provider
        }
        service.toast(message)
        AppUtil.launchMainToVoice(service)
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

private const val OPENAI_FINAL_TIMEOUT_MS = 60_000L
