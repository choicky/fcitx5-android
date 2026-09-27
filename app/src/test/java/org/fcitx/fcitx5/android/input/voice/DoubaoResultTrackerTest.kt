/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.DoubaoAsrProtocol.Response
import org.fcitx.fcitx5.android.input.voice.DoubaoAsrProtocol.Utterance
import org.fcitx.fcitx5.android.input.voice.DoubaoResultTracker.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoResultTrackerTest {

    private fun result(lastPackage: Boolean, text: String?, vararg utterances: Pair<String, Boolean>) =
        Response.Result(
            sequence = 2,
            lastPackage = lastPackage,
            text = text,
            utterances = utterances.map { (t, definite) -> Utterance(t, definite) }
        )

    @Test
    fun provisionalIsNeverFinal() {
        val tracker = DoubaoResultTracker()
        assertEquals(Outcome.Provisional("你"), tracker.accept(result(false, "你", "你" to false)))
        assertEquals(Outcome.Provisional("你好"), tracker.accept(result(false, "你好", "你好" to false)))
        assertFalse(tracker.finished)
        assertEquals(2, tracker.provisionalCount)
    }

    @Test
    fun definiteUtteranceIsStableButDoesNotEndRequest() {
        val tracker = DoubaoResultTracker()
        assertEquals(
            Outcome.Stable("你好。", 1),
            tracker.accept(result(false, "你好。世", "你好。" to true, "世" to false))
        )
        assertFalse(tracker.finished)
        // more provisional text after a definite utterance is still provisional
        assertEquals(
            Outcome.Provisional("你好。世界"),
            tracker.accept(result(false, "你好。世界", "你好。" to true, "世界" to false))
        )
        assertEquals(
            Outcome.Stable("你好。世界。", 2),
            tracker.accept(result(false, "你好。世界。", "你好。" to true, "世界。" to true))
        )
        assertFalse(tracker.finished)
    }

    @Test
    fun onlyLastPackageIsFinalAndOnce() {
        val tracker = DoubaoResultTracker()
        tracker.accept(result(false, "你好。", "你好。" to true))
        assertEquals(
            Outcome.Final("你好。世界。"),
            tracker.accept(result(true, "你好。世界。", "你好。" to true, "世界。" to true))
        )
        assertTrue(tracker.finished)
        assertEquals(Outcome.Ignored, tracker.accept(result(true, "again", "again" to true)))
        assertEquals(Outcome.Ignored, tracker.accept(result(false, "late", "late" to false)))
    }

    @Test
    fun lastPackageWithoutTextFallsBackToStableTextOnly() {
        val tracker = DoubaoResultTracker()
        tracker.accept(result(false, "你好。世", "你好。" to true, "世" to false))
        assertEquals(Outcome.Final("你好。"), tracker.accept(result(true, null)))
    }

    @Test
    fun provisionalIsNeverPromotedToFinal() {
        val tracker = DoubaoResultTracker()
        tracker.accept(result(false, "你好", "你好" to false))
        assertEquals(Outcome.Final(null), tracker.accept(result(true, null)))
    }

    @Test
    fun cancelRejectsLateFinal() {
        val tracker = DoubaoResultTracker()
        tracker.accept(result(false, "你好。", "你好。" to true))
        tracker.cancel()
        assertEquals(Outcome.Ignored, tracker.accept(result(true, "你好。", "你好。" to true)))
        assertEquals(Outcome.Ignored, tracker.fail("late failure"))
    }

    @Test
    fun serverErrorFailsOnce() {
        val tracker = DoubaoResultTracker()
        assertEquals(
            Outcome.Failed("server error 55000031: busy"),
            tracker.accept(Response.Error(55000031, "busy"))
        )
        assertEquals(Outcome.Ignored, tracker.accept(result(true, "x", "x" to true)))
    }

    @Test
    fun timeoutOrNetworkFailure() {
        val tracker = DoubaoResultTracker()
        assertEquals(Outcome.Failed("timeout"), tracker.fail("timeout"))
        assertEquals(Outcome.Ignored, tracker.fail("second"))
        assertEquals(Outcome.Ignored, tracker.accept(result(true, "x", "x" to true)))
    }
}
