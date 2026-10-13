/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import timber.log.Timber

/**
 * Backend-independent voice input orchestration: session state, token checks and routing of
 * backend events to the editor. Every trigger goes through the same instance.
 *
 * When a [TextPostProcessor] is configured, an accepted final transcript is held for one
 * processing window before it reaches the editor, and this flow alone owns that pending result:
 * a cancellation, an input-target change, a close or a newer session drops it without committing
 * anything, while a failed attempt still delivers the user's own transcript (D017).
 *
 * The watchdog bounds each session: no backend may keep the microphone open forever without
 * a usable session, and a microphone trigger may end itself when its continuous activity
 * stream goes silent. Providers without a level stream (System ASR) are never stopped on a
 * silence guess; Space sessions keep their gesture-driven termination: silence never
 * finalizes them.
 */
internal class VoiceInputFlow(
    private val output: Output,
    private val scheduler: VoiceSessionScheduler,
    private val postProcessor: TextPostProcessor? = null
) : VoiceBackend.Events {

    interface Output {
        fun updateComposing(text: String)
        fun clearComposing()
        fun commit(text: String)
        fun stateChanged(state: VoiceInputSession.State)
        fun reportError(error: VoiceError)
        fun audioLevel(level: Float) {}

        /** The session moved to the fallback backend after [error]; speech may need repeating. */
        fun fellBack(error: VoiceError) {}

        /** The watchdog ended the microphone session without user action. */
        fun autoTerminated(reason: VoiceAutoTermination) {}
    }

    private enum class Stage {
        None,

        /** Waiting for [VoiceBackend.Events.onSessionEstablished]; every trigger. */
        Establish,

        /** Established microphone session in which nothing has been said yet. */
        NoSpeech,

        /**
         * Microphone session where discrete speech proof arrived (a provider speech-start or a
         * non-blank partial) but no continuous activity has been seen since. Untimed: providers
         * without a level stream (System ASR) are ended by their own endpoint events, never by
         * a silence guess.
         */
        Active,

        /** Microphone session with recent continuous activity, waiting out the trailing silence. */
        Silence
    }

    private val session = VoiceInputSession()
    private var backend: VoiceBackend? = null
    private var languageTag = ""
    private var established = false

    /** At most one D035 fallback per session, consumed when used. */
    private var fallback: (() -> VoiceBackend)? = null

    // watchdog state, per backend attempt; a D035 fallback re-arms a fresh Establish window,
    // because the fallback toast tells the user their speech may need repeating
    private var trigger = VoiceTrigger.Microphone
    private var stage = Stage.None
    private var cancelTimer: (() -> Unit)? = null

    /**
     * The accepted final whose post-processing answer has not arrived yet. [VoiceInputSession]
     * cannot witness this window: [VoiceInputSession.complete] has already moved the session to
     * Idle, so a later cancel, close or input-target change leaves its generation untouched.
     * Ownership of the pending result therefore lives here, armed for exactly one token.
     */
    private var processingToken: Long? = null
    private var cancelProcessing: (() -> Unit)? = null

    val state: VoiceInputSession.State
        get() = if (processingToken != null) VoiceInputSession.State.Stopping else session.state

    /** Reserve a new session; the backend is attached later by [launch]. */
    fun begin(): Long? {
        // a newer session supersedes any answer still on its way for the previous final
        discardProcessing()
        val token = session.start() ?: return null
        notifyState()
        return token
    }

    /** Returns false if the session was stopped or cancelled while it was being prepared. */
    /**
     * [fallback] creates the backend used if this one fails before its session is established
     * (D035); the caller decides whether one is allowed at all. [trigger] decides the session's
     * termination policy, independently of the provider.
     */
    fun launch(
        token: Long,
        languageTag: String,
        backend: VoiceBackend,
        trigger: VoiceTrigger,
        fallback: (() -> VoiceBackend)? = null
    ): Boolean {
        if (!session.accepts(token) || session.state != VoiceInputSession.State.Starting) {
            return false
        }
        output.clearComposing()
        this.languageTag = languageTag
        this.trigger = trigger
        this.fallback = fallback
        start(token, backend)
        return true
    }

    private fun start(token: Long, backend: VoiceBackend) {
        established = false
        this.backend = backend
        armStage(Stage.Establish, token)
        backend.start(token, languageTag, this)
    }

    fun stop() {
        disarm()
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
        val wasProcessing = discardProcessing()
        if (!session.cancel() && !wasProcessing) return false
        disarm()
        releaseBackend()
        output.clearComposing()
        notifyState()
        return true
    }

    fun close() {
        disarm()
        val wasProcessing = discardProcessing()
        val wasActive = session.cancel()
        releaseBackend()
        if (wasActive || wasProcessing) output.clearComposing()
        notifyState()
    }

    override fun onStarted(token: Long) {
        if (session.onBackendStarted(token)) notifyState()
    }

    override fun onSessionEstablished(token: Long) {
        if (!session.accepts(token)) return
        established = true
        if (stage != Stage.Establish) return
        when (trigger) {
            // held gesture: the release or swipe ends the session, no further timer
            VoiceTrigger.SpaceHold -> disarm()
            // the user's own window starts once results could actually arrive
            VoiceTrigger.Microphone -> armStage(Stage.NoSpeech, token)
        }
    }

    override fun onSpeechStarted(token: Long) {
        if (session.accepts(token)) speechOccurred()
    }

    override fun onEndOfSpeech(token: Long) {
        if (session.onEndOfSpeech(token)) {
            // the service ended the utterance itself: the final result is on its way
            disarm()
            notifyState()
        }
    }

    override fun onPartial(token: Long, text: String) {
        if (!session.accepts(token)) return
        if (text.isNotBlank()) speechOccurred()
        output.updateComposing(text)
    }

    override fun onFinal(token: Long, text: String?) {
        if (!session.complete(token)) return
        disarm()
        releaseBackend()
        if (text.isNullOrBlank()) {
            output.clearComposing()
            notifyState()
            return
        }
        val processor = postProcessor
        if (processor == null) {
            output.commit(text)
            notifyState()
            return
        }
        // Only a FINAL transcript reaches the processor, and only after the backend is released:
        // the commit and the Idle notification now wait for the processing window.
        processingToken = token
        cancelProcessing = scheduler.schedule(TextPostPolicy.TIMEOUT_MS) {
            deliverProcessed(token, text, null)
        }
        try {
            processor.process(text, languageTag) { deliverProcessed(token, text, it) }
        } catch (error: Exception) {
            // a processor that cannot even start must not hang the window or lose the transcript
            Timber.d(error, "Text post-processing failed for the accepted final")
            deliverProcessed(token, text, null)
        }
    }

    /**
     * A processor answer for [token], or null when the attempt failed or timed out.
     *
     * Two outcomes must never be merged: a failed attempt while the result is still ours keeps the
     * user's own [raw] transcript (nothing is lost because optional processing did not work), while
     * a result we no longer own commits nothing at all — its editor target may be gone, and a late
     * commit would be worse than no commit.
     */
    private fun deliverProcessed(token: Long, raw: String, processed: String?) {
        if (processingToken != token) return
        processingToken = null
        cancelProcessing?.invoke()
        cancelProcessing = null
        output.commit(processed?.takeIf { it.isNotBlank() } ?: raw)
        notifyState()
    }

    /** Forgets a pending result: neither its processed text nor its raw transcript may be committed. */
    private fun discardProcessing(): Boolean {
        val had = processingToken != null
        processingToken = null
        cancelProcessing?.invoke()
        cancelProcessing = null
        return had
    }

    override fun onError(token: Long, error: VoiceError) {
        if (tryFallback(token, error)) return
        if (!session.complete(token)) return
        disarm()
        releaseBackend()
        output.clearComposing()
        output.reportError(error)
        notifyState()
    }

    override fun onAudioLevel(token: Long, level: Float) {
        if (!session.accepts(token)) return
        if (level >= VoiceEndpointPolicy.SPEECH_LEVEL) speechActivity(token)
        output.audioLevel(level)
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

    /** Discrete proof that speaking began; it ends NoSpeech but says nothing about silence. */
    private fun speechOccurred() {
        if (trigger != VoiceTrigger.Microphone) return
        // Stage.Establish is left only by onSessionEstablished or a terminal path (D035);
        // an untimed Active stage lets endpoint-less providers keep their own semantics
        if (stage == Stage.NoSpeech) disarmToActive()
    }

    /**
     * Continuous activity (a loud chunk from the Fcitx-owned capture). Only this may arm or
     * refresh the trailing-silence timer, and only after the session was established.
     */
    private fun speechActivity(token: Long) {
        if (trigger != VoiceTrigger.Microphone || !established) return
        when (stage) {
            Stage.None, Stage.Establish -> Unit
            Stage.NoSpeech, Stage.Active, Stage.Silence -> armStage(Stage.Silence, token)
        }
    }

    private fun disarmToActive() {
        stage = Stage.Active
        cancelTimer?.invoke()
        cancelTimer = null
    }

    private fun armStage(stage: Stage, token: Long) {
        this.stage = stage
        cancelTimer?.invoke()
        val delay = when (stage) {
            Stage.None, Stage.Active -> return // Active is deliberately untimed
            Stage.Establish -> VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS
            Stage.NoSpeech -> VoiceEndpointPolicy.NO_SPEECH_TIMEOUT_MS
            Stage.Silence -> VoiceEndpointPolicy.TRAILING_SILENCE_MS
        }
        cancelTimer = scheduler.schedule(delay) { onStageExpired(token, stage) }
    }

    private fun onStageExpired(token: Long, expired: Stage) {
        if (expired != stage || !session.accepts(token)) return
        when (expired) {
            Stage.None, Stage.Active -> Unit // untimed: nothing can expire here
            Stage.Establish -> {
                disarm()
                // an early timeout is a startup service failure, so it may fall back (D035)
                onError(
                    token,
                    VoiceError.Service(
                        "no usable session established within " +
                                "${VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS}ms"
                    )
                )
            }
            Stage.NoSpeech -> {
                // nothing was said: cancel the idle session — no transcript is produced and
                // future capture/transmission stops per backend cancellation; audio already
                // streamed to a remote provider cannot be retracted
                disarm()
                if (cancel()) output.autoTerminated(VoiceAutoTermination.NoSpeech)
            }
            Stage.Silence -> {
                // spoken utterance followed by sustained silence: finish like a second click
                disarm()
                stop()
                output.autoTerminated(VoiceAutoTermination.TrailingSilence)
            }
        }
    }

    private fun disarm() {
        stage = Stage.None
        cancelTimer?.invoke()
        cancelTimer = null
    }

    private fun releaseBackend() {
        backend?.cancel()
        backend = null
    }

    private fun notifyState() {
        output.stateChanged(session.state)
    }
}
