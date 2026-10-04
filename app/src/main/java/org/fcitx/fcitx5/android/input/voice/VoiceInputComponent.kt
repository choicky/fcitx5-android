/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

class VoiceInputComponent : UniqueComponent<VoiceInputComponent>(), Dependent,
    ManagedHandler by managedHandler(), InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val showVoiceInputButton by AppPrefs.getInstance().keyboard.showVoiceInputButton
    private val prefs = AppPrefs.getInstance()
    private val providerRegistry = systemVoiceProviderRegistry()

    private val inputFlow = VoiceInputFlow(object : VoiceInputFlow.Output {
        override fun updateComposing(text: String) = service.updateVoiceComposingText(text)

        override fun clearComposing() = service.clearVoiceComposingText()

        override fun commit(text: String) = service.commitText(text)

        override fun stateChanged(state: VoiceInputSession.State) {
            stateListener?.invoke(state)
        }

        override fun reportError(error: VoiceError) {
            when (error) {
                VoiceError.Silent -> Unit
                VoiceError.PermissionDenied -> requestRecordAudioPermission()
                is VoiceError.System ->
                    service.toast(service.getString(R.string.voice_input_error, error.code))
                is VoiceError.Capture ->
                    service.toast(service.getString(R.string.voice_capture_error, error.detail))
            }
        }
    })

    private var languageCode = ""
    private var passwordField = false
    private var stateListener: ((VoiceInputSession.State) -> Unit)? = null

    val toggleCallback = View.OnClickListener { toggle() }

    internal fun setStateListener(listener: (VoiceInputSession.State) -> Unit) {
        stateListener = listener
        listener(inputFlow.state)
    }

    fun shouldShowVoiceInput(capFlags: CapabilityFlags): Boolean {
        passwordField = capFlags.has(CapabilityFlag.Password)
        return showVoiceInputButton && !passwordField &&
            providerRegistry.firstAvailable(service) != null
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
        stateListener = null
    }

    private fun start() {
        if (passwordField) return
        if (!prefs.internal.voiceSystemAsrAnswered.getValue()) {
            requestSystemAsrAuthorization()
            return
        }
        if (!prefs.internal.voiceSystemAsrAllowed.getValue()) return
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestRecordAudioPermission()
            return
        }
        val provider = providerRegistry.find(SystemVoiceProvider.ID)
        if (provider == null || !provider.isAvailable(service)) {
            service.toast(R.string.voice_input_unavailable)
            return
        }
        val token = inputFlow.begin() ?: return
        service.lifecycleScope.launch {
            // Fcitx InputContext::reset dispatches the engine ResetEvent. The pinned Pinyin
            // implementation clears its context and updates preedit without committing it.
            service.prepareForVoiceInput().join()
            yield()
            inputFlow.launch(token, languageCode, provider.createBackend(service))
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
