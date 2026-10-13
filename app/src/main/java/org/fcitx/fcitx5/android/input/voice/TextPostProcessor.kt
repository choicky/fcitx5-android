/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

/**
 * Optional post-processing of an already accepted final transcript (D017): ASR stays
 * Audio → Raw Transcript, and this stage is a separate one that can be switched off completely.
 *
 * [process] is called on the main thread with the Raw Transcript of a session whose backend has
 * already been released. The implementation owns its own threading — coroutines, a network client
 * or a native runtime all stay inside it — and must invoke [callback] exactly once on the main
 * thread, with the corrected text or with null when the attempt failed. Whether the answer may
 * still reach the editor is not this interface's decision: [VoiceInputFlow] owns the pending
 * result and drops it once the gesture was cancelled, the target changed, the component closed or
 * a newer session took over.
 */
internal fun interface TextPostProcessor {

    fun process(raw: String, languageTag: String, callback: (processed: String?) -> Unit)
}

/**
 * Provisional bound for the whole processing window, armed when a final is accepted. A processor
 * that has not answered by then counts as a failed attempt, so the user's own transcript is
 * delivered instead of the editor waiting indefinitely.
 *
 * This is a PoC decision band, not a product requirement: it exists to make the deferred commit
 * safe to test, and is deliberately not part of [VoiceEndpointPolicy], which is microphone
 * endpointing policy.
 */
internal object TextPostPolicy {

    const val TIMEOUT_MS = 4_000L
}
