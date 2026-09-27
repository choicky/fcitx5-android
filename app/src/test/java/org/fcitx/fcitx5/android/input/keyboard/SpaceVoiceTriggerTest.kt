/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.voice.VoiceInputSession.State
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

    @Test
    fun cancelArmsAndDisarmsWhileHeld() {
        val tracker = SpaceLongPressTracker(threshold)
        tracker.onDown(100f)
        assertNull(tracker.onMove(90f)) // still below the threshold: no change
        assertEquals(true, tracker.onMove(100f - threshold))
        assertNull(tracker.onMove(0f)) // already armed
        assertEquals(false, tracker.onMove(95f)) // moved back down
        assertEquals(false, tracker.onUp(95f, longPressTriggered = true))
        // the next press starts disarmed
        tracker.onDown(50f)
        assertNull(tracker.onMove(50f))
    }

    @Test
    fun panelFollowsSessionAndGesture() {
        assertEquals(VoicePanelState.Hidden, voicePanelState(State.Idle, spaceHeld = true, cancelArmed = true))
        assertEquals(VoicePanelState.ReleaseToFinish, voicePanelState(State.Starting, spaceHeld = true, cancelArmed = false))
        assertEquals(VoicePanelState.ReleaseToFinish, voicePanelState(State.Listening, spaceHeld = true, cancelArmed = false))
        assertEquals(VoicePanelState.ReleaseToCancel, voicePanelState(State.Listening, spaceHeld = true, cancelArmed = true))
        // mic sessions share the state but have no held Space
        assertEquals(VoicePanelState.Listening, voicePanelState(State.Listening, spaceHeld = false, cancelArmed = false))
        assertEquals(VoicePanelState.Recognizing, voicePanelState(State.Stopping, spaceHeld = false, cancelArmed = false))
    }

    @Test
    fun spaceHoldArmsAndDisarmsCancel() {
        // Listening -> CancelArmed -> back to Listening while the Space key is held
        val tracker = SpaceLongPressTracker(threshold)
        tracker.onDown(100f)
        fun panel(armed: Boolean) = voicePanelState(State.Listening, spaceHeld = true, cancelArmed = armed)
        assertEquals(VoicePanelState.ReleaseToCancel, panel(tracker.onMove(100f - threshold)!!))
        assertEquals(VoicePanelState.ReleaseToFinish, panel(tracker.onMove(90f)!!))
    }

    @Test
    fun onlyMicSessionsGetButtons() {
        assertTrue(VoicePanelState.Listening.showsCancel && VoicePanelState.Listening.showsFinish)
        // stopped: Done no longer applies, Cancel still discards
        assertTrue(VoicePanelState.Recognizing.showsCancel)
        assertFalse(VoicePanelState.Recognizing.showsFinish)
        // a held Space ends by release, not by buttons
        listOf(VoicePanelState.ReleaseToFinish, VoicePanelState.ReleaseToCancel, VoicePanelState.Hidden).forEach {
            assertFalse(it.showsCancel || it.showsFinish)
        }
    }

    @Test
    fun panelHidesWhenSessionEnds() {
        // final, error and cancel all return the shared session to Idle
        assertEquals(VoicePanelState.Hidden, voicePanelState(State.Idle, spaceHeld = false, cancelArmed = false))
    }
}
