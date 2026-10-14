/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.os.Handler
import android.os.Looper

/**
 * How a voice session was opened; decides how the session may terminate by itself.
 * The trigger is session policy only: it never selects or changes a provider.
 */
internal enum class VoiceTrigger {
    /** Toolbar microphone: a second click finishes, cancel cancels, observed silence may end it. */
    Microphone,

    /** Long-press Space: only the gesture ends the session; silence never finalizes it. */
    SpaceHold
}

/**
 * Endpointing policy for [VoiceTrigger.Microphone] sessions, plus the D035 early-session
 * bound. Values are centralized here so device acceptance can revise them in one place.
 */
internal object VoiceEndpointPolicy {

    /**
     * D035 "early timeout before a usable recognition session is established": a backend
     * that has not reported [VoiceBackend.Events.onSessionEstablished] within this window
     * failed to start, which routes into the normal fallback/error path instead of leaving
     * the microphone open until the backends' [VoiceCapturePolicy] safety ceiling.
     */
    const val ESTABLISH_TIMEOUT_MS = 30_000L

    /**
     * A microphone session in which nothing was ever said is cancelled, not finalized: no
     * transcript is produced and future capture/transmission stops, while audio already
     * streamed to a remote provider cannot be retracted.
     */
    const val NO_SPEECH_TIMEOUT_MS = 10_000L

    /**
     * Continuous capture activity followed by silence for this long finalizes the microphone
     * session. Only the [AudioLevel] stream may arm or refresh this window, so providers
     * without one (System ASR) keep endpointing themselves.
     */
    const val TRAILING_SILENCE_MS = 1_500L

    /**
     * [AudioLevel] at or above which one 20 ms chunk counts as speech. Phase 4B.1
     * dual-device data: speech RMS ≈ 0.51, room silence ≈ 0.0..0.18 on this scale
     * (−65..−20 dBFS), so 0.3 leaves margin on both sides.
     */
    const val SPEECH_LEVEL = 0.30f
}

/**
 * The hard safety ceiling on Fcitx-owned Direct PCM capture (D081), deliberately separate
 * from endpointing: [VoiceEndpointPolicy] decides when a session ends normally, while this
 * only bounds sessions that never auto-stop (a held Space gesture, continuous speech).
 * Reaching a ceiling finalizes the session like a user stop and releases the microphone;
 * it is not a cancel, not an error, and never triggers D035 fallback. A ceiling is a safety
 * bound, not a claim that recognition quality at that length is device-verified. Where a
 * current model, provider engine or protocol has a documented lower limit, that lower limit
 * applies: effective capture bound = min(common ceiling, applicable lower limit).
 */
internal object VoiceCapturePolicy {

    /** Default ceiling for streaming Direct capture paths with no lower applicable limit. */
    const val MAX_CAPTURE_MS = 180_000L

    /**
     * OpenAI-compatible whole-file self-hosted upload: its entire utterance is uploaded and
     * transcribed only after stop, within a window (OPENAI_FINAL_TIMEOUT_MS) explicitly
     * premised on at most 60 s of audio, so capture stays at that premise.
     */
    const val WHOLE_FILE_MAX_CAPTURE_MS = 60_000L
}

/** A microphone session ended without user action; for observability only. */
internal enum class VoiceAutoTermination { NoSpeech, TrailingSilence }

/** Relative-delay timing boundary for the [VoiceInputFlow] watchdog, injectable for tests. */
internal interface VoiceSessionScheduler {
    /** Runs [task] after at least [delayMs]; the returned handle cancels it. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** Main-thread implementation; [VoiceInputFlow] events already arrive on the main thread. */
internal class HandlerVoiceSessionScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : VoiceSessionScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}
