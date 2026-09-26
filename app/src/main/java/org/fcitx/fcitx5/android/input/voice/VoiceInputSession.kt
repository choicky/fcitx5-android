/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

internal class VoiceInputSession {

    enum class State {
        Idle, Starting, Listening, Stopping
    }

    enum class StopAction {
        None, CancelPendingStart, StopRecognizer
    }

    var state: State = State.Idle
        private set

    private var generation = 0L

    fun start(): Long? {
        if (state != State.Idle) return null
        generation += 1
        state = State.Starting
        return generation
    }

    fun onRecognizerStarted(token: Long): Boolean {
        if (token != generation || state != State.Starting) return false
        state = State.Listening
        return true
    }

    fun onEndOfSpeech(token: Long): Boolean {
        if (token != generation || state == State.Idle) return false
        state = State.Stopping
        return true
    }

    fun stop(): StopAction = when (state) {
        State.Starting -> {
            invalidate()
            StopAction.CancelPendingStart
        }
        State.Listening -> {
            state = State.Stopping
            StopAction.StopRecognizer
        }
        State.Idle, State.Stopping -> StopAction.None
    }

    fun cancel(): Boolean {
        if (state == State.Idle) return false
        invalidate()
        return true
    }

    fun accepts(token: Long): Boolean = token == generation && state != State.Idle

    fun complete(token: Long): Boolean {
        if (!accepts(token)) return false
        invalidate()
        return true
    }

    private fun invalidate() {
        generation += 1
        state = State.Idle
    }
}
