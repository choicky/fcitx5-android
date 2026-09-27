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
        None, CancelPendingStart, StopBackend
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

    fun onBackendStarted(token: Long): Boolean {
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
            StopAction.StopBackend
        }
        State.Idle, State.Stopping -> StopAction.None
    }

    fun cancel(): Boolean {
        if (state == State.Idle) return false
        invalidate()
        return true
    }

    fun accepts(token: Long): Boolean = token == generation && state != State.Idle

    /**
     * A replacement backend takes over the same gesture: the state is kept, and a new token
     * makes every late event of the failed backend stale. Not while stopping.
     */
    fun rebind(token: Long): Long? {
        if (!accepts(token) || state == State.Stopping) return null
        generation += 1
        return generation
    }

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
