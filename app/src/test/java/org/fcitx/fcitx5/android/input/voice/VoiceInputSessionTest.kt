/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputSessionTest {

    @Test
    fun startStopAndFinalResult() {
        val session = VoiceInputSession()
        val token = session.start()
        assertNotNull(token)
        assertEquals(VoiceInputSession.State.Starting, session.state)
        assertTrue(session.onRecognizerStarted(token!!))
        assertEquals(VoiceInputSession.StopAction.StopRecognizer, session.stop())
        assertEquals(VoiceInputSession.State.Stopping, session.state)
        assertTrue(session.accepts(token))
        assertTrue(session.complete(token))
        assertEquals(VoiceInputSession.State.Idle, session.state)
    }

    @Test
    fun cancelRejectsLateCallbacks() {
        val session = VoiceInputSession()
        val token = session.start()!!
        assertTrue(session.cancel())
        assertFalse(session.accepts(token))
        assertFalse(session.onRecognizerStarted(token))
        assertFalse(session.complete(token))
    }

    @Test
    fun stopBeforeRecognizerStartsCancelsPendingStart() {
        val session = VoiceInputSession()
        val token = session.start()!!
        assertEquals(VoiceInputSession.StopAction.CancelPendingStart, session.stop())
        assertEquals(VoiceInputSession.State.Idle, session.state)
        assertFalse(session.accepts(token))
    }

    @Test
    fun previousSessionCannotCompleteNewSession() {
        val session = VoiceInputSession()
        val first = session.start()!!
        session.cancel()
        val second = session.start()!!
        assertFalse(session.complete(first))
        assertTrue(session.accepts(second))
    }
}
