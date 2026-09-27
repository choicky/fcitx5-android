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
        fun audioLevel(level: Float) {}

        /** The session moved to the fallback backend after [error]; speech may need repeating. */
        fun fellBack(error: VoiceError) {}
    }

    private val session = VoiceInputSession()
    private var backend: VoiceBackend? = null
    private var languageTag = ""
    private var established = false

    /** At most one D035 fallback per session, consumed when used. */
    private var fallback: (() -> VoiceBackend)? = null

    val state: VoiceInputSession.State
        get() = session.state

    /** Reserve a new session; the backend is attached later by [launch]. */
    fun begin(): Long? {
        val token = session.start() ?: return null
        notifyState()
        return token
    }

    /** Returns false if the session was stopped or cancelled while it was being prepared. */
    /**
     * [fallback] creates the backend used if this one fails before its session is established
     * (D035); the caller decides whether one is allowed at all.
     */
    fun launch(
        token: Long,
        languageTag: String,
        backend: VoiceBackend,
        fallback: (() -> VoiceBackend)? = null
    ): Boolean {
        if (!session.accepts(token) || session.state != VoiceInputSession.State.Starting) {
            return false
        }
        output.clearComposing()
        this.languageTag = languageTag
        this.fallback = fallback
        start(token, backend)
        return true
    }

    private fun start(token: Long, backend: VoiceBackend) {
        established = false
        this.backend = backend
        backend.start(token, languageTag, this)
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

    override fun onSessionEstablished(token: Long) {
        if (session.accepts(token)) established = true
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
        if (tryFallback(token, error)) return
        if (!session.complete(token)) return
        releaseBackend()
        output.clearComposing()
        output.reportError(error)
        notifyState()
    }

    override fun onAudioLevel(token: Long, level: Float) {
        if (session.accepts(token)) output.audioLevel(level)
    }

    /**
     * D035: only a technical service failure before the session was established, while the
     * gesture is still active, moves to the fallback backend. Nothing captured is replayed.
     */
    private fun tryFallback(token: Long, error: VoiceError): Boolean {
        val next = fallback ?: return false
        if (established || error !is VoiceError.Service) return false
        val newToken = session.rebind(token) ?: return false
        fallback = null
        releaseBackend()
        output.fellBack(error)
        start(newToken, next())
        return true
    }

    private fun releaseBackend() {
        backend?.cancel()
        backend = null
    }

    private fun notifyState() {
        output.stateChanged(session.state)
    }
}
