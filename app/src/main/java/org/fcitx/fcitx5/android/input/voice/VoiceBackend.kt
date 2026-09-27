/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

/**
 * One recognition session, driven by [VoiceInputFlow]. A backend instance serves a single
 * session: it owns whatever resources it opens in [start] and releases them on a terminal
 * event ([Events.onFinal] / [Events.onError]) or on [cancel].
 *
 * This is an internal boundary, not a plugin API, and it does not assume the backend
 * consumes audio from Fcitx: the system backend lets the RecognitionService capture audio.
 */
internal interface VoiceBackend {

    fun start(token: Long, languageTag: String, events: Events)

    /** End input; a final result or an error is still expected. */
    fun stop()

    /** Abandon the session and release its resources. No further events. Idempotent. */
    fun cancel()

    /** Delivered on the main thread, tagged with the session token. */
    interface Events {
        fun onStarted(token: Long)
        fun onEndOfSpeech(token: Long)
        fun onPartial(token: Long, text: String)

        /** `null` or blank means nothing is committed. */
        fun onFinal(token: Long, text: String?)
        fun onError(token: Long, error: VoiceError)

        /**
         * Optional microphone loudness (0..1) for the voice panel. Only backends that own the
         * PCM (Fcitx AudioRecord) report it; recognition never depends on it.
         */
        fun onAudioLevel(token: Long, level: Float) {}
    }
}

internal sealed interface VoiceError {
    /** Ends the session without a message, e.g. no match or nothing said. */
    data object Silent : VoiceError

    /** The service reported a missing RECORD_AUDIO permission. */
    data object PermissionDenied : VoiceError

    data class System(val code: Int) : VoiceError

    data class Capture(val detail: String) : VoiceError

    /** A Direct ASR service failed (connection, protocol, server error or timeout). */
    data class Service(val detail: String) : VoiceError
}
