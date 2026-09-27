/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.DoubaoAsrProtocol.Response
import org.fcitx.fcitx5.android.input.voice.DoubaoResultTracker.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoResultTrackerTest {

    private fun provisional(text: String) = Response.Result(2, last = false, text = text, definiteUtterances = 0)
    private fun finalResult(text: String?) = Response.Result(-3, last = true, text = text, definiteUtterances = 1)

    @Test
    fun provisionalIsNeverFinal() {
        val tracker = DoubaoResultTracker()
        assertEquals(Outcome.Provisional("你", 0), tracker.accept(provisional("你")))
        assertEquals(Outcome.Provisional("你好", 0), tracker.accept(provisional("你好")))
        assertFalse(tracker.finished)
        assertEquals(2, tracker.provisionalCount)
    }

    @Test
    fun finalIsDeliveredOnce() {
        val tracker = DoubaoResultTracker()
        tracker.accept(provisional("你"))
        assertEquals(Outcome.Final("你好。"), tracker.accept(finalResult("你好。")))
        assertTrue(tracker.finished)
        assertEquals(Outcome.Ignored, tracker.accept(finalResult("again")))
        assertEquals(Outcome.Ignored, tracker.accept(provisional("late")))
    }

    @Test
    fun finalWithoutTextCommitsNothing() {
        // a provisional transcript is not promoted when the final result is empty
        val tracker = DoubaoResultTracker()
        tracker.accept(provisional("你好"))
        assertEquals(Outcome.Final(null), tracker.accept(finalResult(null)))
    }

    @Test
    fun cancelRejectsLateFinal() {
        val tracker = DoubaoResultTracker()
        tracker.accept(provisional("你好"))
        tracker.cancel()
        assertEquals(Outcome.Ignored, tracker.accept(finalResult("你好。")))
        assertEquals(Outcome.Ignored, tracker.fail("late failure"))
    }

    @Test
    fun serverErrorFailsOnce() {
        val tracker = DoubaoResultTracker()
        assertEquals(
            Outcome.Failed("server error 55000031: busy"),
            tracker.accept(Response.Error(55000031, "busy"))
        )
        assertEquals(Outcome.Ignored, tracker.accept(finalResult("x")))
    }

    @Test
    fun timeoutOrNetworkFailure() {
        val tracker = DoubaoResultTracker()
        assertEquals(Outcome.Failed("timeout"), tracker.fail("timeout"))
        assertEquals(Outcome.Ignored, tracker.fail("second"))
        assertEquals(Outcome.Ignored, tracker.accept(finalResult("x")))
    }
}
