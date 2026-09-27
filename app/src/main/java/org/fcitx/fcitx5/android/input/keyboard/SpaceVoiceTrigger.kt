/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Tracks one Space press for [KeyAction.SpaceLongPressReleaseAction]: releasing after the
 * long press fired either ends the gesture normally, or, if the finger moved up by at least
 * [cancelThreshold] since touch down, as a swipe-up.
 */
internal class SpaceLongPressTracker(private val cancelThreshold: Float) {

    private var downY = 0f

    fun onDown(y: Float) {
        downY = y
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
