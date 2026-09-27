/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceVoiceTriggerTest {

    private val threshold = 36f

    private fun release(downY: Float, upY: Float, longPressTriggered: Boolean): Boolean? =
        SpaceLongPressTracker(threshold).run {
            onDown(downY)
            onUp(upY, longPressTriggered)
        }

    @Test
    fun tapIsNotALongPressRelease() {
        // the click path sends the normal Space key; no release action is emitted
        assertNull(release(downY = 20f, upY = 20f, longPressTriggered = false))
        assertNull(release(downY = 20f, upY = -100f, longPressTriggered = false))
    }

    @Test
    fun releaseInPlaceStops() {
        assertEquals(false, release(downY = 20f, upY = 20f, longPressTriggered = true))
    }

    @Test
    fun downwardOrSmallUpwardMovementStops() {
        assertEquals(false, release(downY = 20f, upY = 80f, longPressTriggered = true))
        assertEquals(false, release(downY = 20f, upY = 20f - threshold + 1f, longPressTriggered = true))
    }

    @Test
    fun swipeUpBeyondThresholdCancels() {
        assertEquals(true, release(downY = 20f, upY = 20f - threshold, longPressTriggered = true))
        assertEquals(true, release(downY = 20f, upY = -200f, longPressTriggered = true))
    }

    @Test
    fun trackerIsReusedAcrossPresses() {
        val tracker = SpaceLongPressTracker(threshold)
        tracker.onDown(100f)
        assertEquals(true, tracker.onUp(0f, longPressTriggered = true))
        // a new press measures from its own touch down
        tracker.onDown(0f)
        assertEquals(false, tracker.onUp(0f, longPressTriggered = true))
    }

    @Test
    fun voiceCommandsOnlyForVoiceInputBehavior() {
        val voice = SpaceLongPressBehavior.VoiceInput
        assertEquals(SpaceVoiceCommand.Start, spaceVoiceCommand(voice, KeyAction.SpaceLongPressAction))
        assertEquals(
            SpaceVoiceCommand.Stop,
            spaceVoiceCommand(voice, KeyAction.SpaceLongPressReleaseAction(swipedUp = false))
        )
        assertEquals(
            SpaceVoiceCommand.Cancel,
            spaceVoiceCommand(voice, KeyAction.SpaceLongPressReleaseAction(swipedUp = true))
        )
        assertEquals(SpaceVoiceCommand.None, spaceVoiceCommand(voice, KeyAction.QuickPhraseAction))

        SpaceLongPressBehavior.entries.filter { it != voice }.forEach { other ->
            assertEquals(SpaceVoiceCommand.None, spaceVoiceCommand(other, KeyAction.SpaceLongPressAction))
            assertEquals(
                SpaceVoiceCommand.None,
                spaceVoiceCommand(other, KeyAction.SpaceLongPressReleaseAction(swipedUp = false))
            )
        }
    }

    @Test
    fun oneGestureYieldsExactlyOneEndCommand() {
        val voice = SpaceLongPressBehavior.VoiceInput
        fun gesture(swipedUp: Boolean) = listOf(
            spaceVoiceCommand(voice, KeyAction.SpaceLongPressAction),
            spaceVoiceCommand(voice, KeyAction.SpaceLongPressReleaseAction(swipedUp))
        )
        assertEquals(listOf(SpaceVoiceCommand.Start, SpaceVoiceCommand.Stop), gesture(false))
        val cancelled = gesture(true)
        assertEquals(listOf(SpaceVoiceCommand.Start, SpaceVoiceCommand.Cancel), cancelled)
        assertFalse(SpaceVoiceCommand.Stop in cancelled)
        assertTrue(cancelled.count { it == SpaceVoiceCommand.Cancel } == 1)
    }
}
