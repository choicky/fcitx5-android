/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.voice.VoiceInputSession

/**
 * Tracks one Space press for [KeyAction.SpaceLongPressReleaseAction]: releasing after the
 * long press fired either ends the gesture normally, or, if the finger moved up by at least
 * [cancelThreshold] since touch down, as a swipe-up.
 */
internal class SpaceLongPressTracker(private val cancelThreshold: Float) {

    private var downY = 0f
    private var cancelArmed = false

    fun onDown(y: Float) {
        downY = y
        cancelArmed = false
    }

    /** While held after the long press: the new cancel-armed state, or `null` if unchanged. */
    fun onMove(y: Float): Boolean? {
        val armed = downY - y >= cancelThreshold
        if (armed == cancelArmed) return null
        cancelArmed = armed
        return armed
    }

    /** `null` when the long press never fired, so the press stays a normal Space key. */
    fun onUp(y: Float, longPressTriggered: Boolean): Boolean? =
        if (longPressTriggered) downY - y >= cancelThreshold else null
}

internal enum class SpaceVoiceCommand { None, Start, Stop, Cancel }

/** Voice input via Space: long press starts, release stops, release after swiping up cancels. */
internal fun spaceVoiceCommand(behavior: SpaceLongPressBehavior, action: KeyAction) =
    if (behavior != SpaceLongPressBehavior.VoiceInput) {
        SpaceVoiceCommand.None
    } else when (action) {
        KeyAction.SpaceLongPressAction -> SpaceVoiceCommand.Start
        is KeyAction.SpaceLongPressReleaseAction ->
            if (action.swipedUp) SpaceVoiceCommand.Cancel else SpaceVoiceCommand.Stop
        else -> SpaceVoiceCommand.None
    }

internal enum class SpaceVoiceHint { None, Listening, ReleaseToFinish, ReleaseToCancel, Processing }

/** What the Space key shows for the shared voice session and the held Space gesture. */
internal fun spaceVoiceHint(
    state: VoiceInputSession.State,
    spaceHeld: Boolean,
    cancelArmed: Boolean
) = when (state) {
    VoiceInputSession.State.Idle -> SpaceVoiceHint.None
    VoiceInputSession.State.Starting,
    VoiceInputSession.State.Listening -> when {
        !spaceHeld -> SpaceVoiceHint.Listening
        cancelArmed -> SpaceVoiceHint.ReleaseToCancel
        else -> SpaceVoiceHint.ReleaseToFinish
    }
    VoiceInputSession.State.Stopping -> SpaceVoiceHint.Processing
}
