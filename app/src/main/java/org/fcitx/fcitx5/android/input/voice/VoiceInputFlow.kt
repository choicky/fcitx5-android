/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

/**
 * Backend-independent voice input orchestration: session state, token checks and routing of
 * backend events to the editor. Every trigger goes through the same instance.
 */
internal class VoiceInputFlow(private val output: Output) : VoiceBackend.Events {

    interface Output {
        fun updateComposing(text: String)
        fun clearComposing()
        fun commit(text: String)
        fun stateChanged(state: VoiceInputSession.State)
        fun reportError(error: VoiceError)
    }

    private val session = VoiceInputSession()
    private var backend: VoiceBackend? = null

    val state: VoiceInputSession.State
        get() = session.state

    /** Reserve a new session; the backend is attached later by [launch]. */
    fun begin(): Long? {
        val token = session.start() ?: return null
        notifyState()
        return token
    }

    /** Returns false if the session was stopped or cancelled while it was being prepared. */
    fun launch(token: Long, languageTag: String, backend: VoiceBackend): Boolean {
        if (!session.accepts(token) || session.state != VoiceInputSession.State.Starting) {
            return false
        }
        output.clearComposing()
        this.backend = backend
        backend.start(token, languageTag, this)
        return true
    }

    fun stop() {
        when (session.stop()) {
            VoiceInputSession.StopAction.CancelPendingStart -> {
                releaseBackend()
                output.clearComposing()
                notifyState()
            }
            VoiceInputSession.StopAction.StopBackend -> {
                backend?.stop()
                notifyState()
            }
            VoiceInputSession.StopAction.None -> Unit
        }
    }

    fun cancel(): Boolean {
        if (!session.cancel()) return false
        releaseBackend()
        output.clearComposing()
        notifyState()
        return true
    }

    fun close() {
        val wasActive = session.cancel()
        releaseBackend()
        if (wasActive) output.clearComposing()
        notifyState()
    }

    override fun onStarted(token: Long) {
        if (session.onBackendStarted(token)) notifyState()
    }

    override fun onEndOfSpeech(token: Long) {
        if (session.onEndOfSpeech(token)) notifyState()
    }

    override fun onPartial(token: Long, text: String) {
        if (session.accepts(token)) output.updateComposing(text)
    }

    override fun onFinal(token: Long, text: String?) {
        if (!session.complete(token)) return
        releaseBackend()
        if (text.isNullOrBlank()) {
            output.clearComposing()
        } else {
            output.commit(text)
        }
        notifyState()
    }

    override fun onError(token: Long, error: VoiceError) {
        if (!session.complete(token)) return
        releaseBackend()
        output.clearComposing()
        output.reportError(error)
        notifyState()
    }

    private fun releaseBackend() {
        backend?.cancel()
        backend = null
    }

    private fun notifyState() {
        output.stateChanged(session.state)
    }
}
