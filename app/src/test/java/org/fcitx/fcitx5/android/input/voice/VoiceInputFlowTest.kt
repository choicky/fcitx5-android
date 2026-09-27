/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputFlowTest {

    /** [onStart] lets a test emit events synchronously from [start], as SystemAsrBackend can. */
    private class FakeBackend(
        private val onStart: ((Long, VoiceBackend.Events) -> Unit)? = null
    ) : VoiceBackend {
        var startedToken: Long? = null
        var startedLanguage: String? = null
        var stops = 0
        var cancels = 0

        override fun start(token: Long, languageTag: String, events: VoiceBackend.Events) {
            startedToken = token
            startedLanguage = languageTag
            onStart?.invoke(token, events)
        }

        override fun stop() {
            stops++
        }

        override fun cancel() {
            cancels++
        }
    }

    private class FakeOutput : VoiceInputFlow.Output {
        val composing = mutableListOf<String>()
        val commits = mutableListOf<String>()
        val errors = mutableListOf<VoiceError>()
        val states = mutableListOf<VoiceInputSession.State>()
        var clears = 0

        override fun updateComposing(text: String) {
            composing += text
        }

        override fun clearComposing() {
            clears++
        }

        override fun commit(text: String) {
            commits += text
        }

        override fun stateChanged(state: VoiceInputSession.State) {
            states += state
        }

        override fun reportError(error: VoiceError) {
            errors += error
        }
    }

    private val output = FakeOutput()
    private val flow = VoiceInputFlow(output)

    private fun startListening(backend: FakeBackend = FakeBackend()): Pair<Long, FakeBackend> {
        val token = flow.begin()
        assertNotNull(token)
        assertTrue(flow.launch(token!!, "zh-CN", backend))
        flow.onStarted(token)
        return token to backend
    }

    @Test
    fun startStopAndFinalCommitsOnce() {
        val (token, backend) = startListening()
        assertEquals(token, backend.startedToken)
        assertEquals("zh-CN", backend.startedLanguage)
        assertEquals(VoiceInputSession.State.Listening, flow.state)

        flow.onPartial(token, "你")
        flow.stop()
        assertEquals(1, backend.stops)
        assertEquals(VoiceInputSession.State.Stopping, flow.state)

        flow.onFinal(token, "你好")
        assertEquals(listOf("你"), output.composing)
        assertEquals(listOf("你好"), output.commits)
        assertEquals(1, backend.cancels) // released after the terminal event
        assertEquals(VoiceInputSession.State.Idle, flow.state)

        flow.onFinal(token, "你好")
        assertEquals(listOf("你好"), output.commits)
    }

    @Test
    fun cancelReleasesBackendAndDropsLateEvents() {
        val (token, backend) = startListening()
        assertTrue(flow.cancel())
        assertEquals(1, backend.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)

        flow.onPartial(token, "late")
        flow.onFinal(token, "late")
        flow.onError(token, VoiceError.System(5))
        assertTrue(output.composing.isEmpty())
        assertTrue(output.commits.isEmpty())
        assertTrue(output.errors.isEmpty())
        assertFalse(flow.cancel())
    }

    @Test
    fun stopBeforeLaunchNeverStartsBackend() {
        val token = flow.begin()!!
        flow.stop()
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        val backend = FakeBackend()
        assertFalse(flow.launch(token, "", backend))
        assertNull(backend.startedToken)
    }

    @Test
    fun stopWhileBackendIsStartingReleasesIt() {
        val token = flow.begin()!!
        val backend = FakeBackend()
        assertTrue(flow.launch(token, "", backend))
        // backend has not reported onStarted yet (asynchronous capture start)
        flow.stop()
        assertEquals(1, backend.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        flow.onStarted(token)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun nullOrBlankFinalCommitsNothing() {
        val (token, _) = startListening()
        flow.stop()
        flow.onFinal(token, null)
        assertTrue(output.commits.isEmpty())
        assertEquals(VoiceInputSession.State.Idle, flow.state)

        val (second, _) = startListening()
        flow.onFinal(second, "   ")
        assertTrue(output.commits.isEmpty())
    }

    @Test
    fun errorClearsAndReportsOnce() {
        val (token, backend) = startListening()
        flow.onPartial(token, "partial")
        val clearsBefore = output.clears
        flow.onError(token, VoiceError.Capture("AudioRecord.read=-3"))
        assertEquals(clearsBefore + 1, output.clears)
        assertEquals(listOf<VoiceError>(VoiceError.Capture("AudioRecord.read=-3")), output.errors)
        assertEquals(1, backend.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        assertTrue(output.commits.isEmpty())
    }

    @Test
    fun previousSessionEventsCannotAffectNewSession() {
        val (first, _) = startListening()
        flow.cancel()
        val (second, _) = startListening()
        flow.onFinal(first, "stale")
        assertTrue(output.commits.isEmpty())
        assertEquals(VoiceInputSession.State.Listening, flow.state)
        flow.onFinal(second, "fresh")
        assertEquals(listOf("fresh"), output.commits)
    }

    @Test
    fun synchronousStartErrorReturnsToIdle() {
        val token = flow.begin()!!
        val backend = FakeBackend { t, events -> events.onError(t, VoiceError.Silent) }
        assertTrue(flow.launch(token, "", backend))
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        assertEquals(1, backend.cancels)
        // Silent reaches the output as-is; VoiceInputComponent presents nothing for it
        assertEquals(listOf<VoiceError>(VoiceError.Silent), output.errors)
        assertTrue(output.commits.isEmpty())

        val next = flow.begin()
        assertNotNull(next)
        assertEquals(VoiceInputSession.State.Starting, flow.state)
    }

    @Test
    fun cancelWhileBackendIsStartingIgnoresLateEvents() {
        val token = flow.begin()!!
        val backend = FakeBackend()
        assertTrue(flow.launch(token, "", backend))
        // backend has not reported onStarted yet (asynchronous capture start)
        assertTrue(flow.cancel())
        assertEquals(1, backend.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)

        flow.onStarted(token)
        flow.onPartial(token, "late")
        flow.onFinal(token, "late")
        flow.onError(token, VoiceError.System(3))
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        assertEquals(1, backend.cancels)
        assertTrue(output.composing.isEmpty())
        assertTrue(output.commits.isEmpty())
        assertTrue(output.errors.isEmpty())
    }

    @Test
    fun duplicateErrorIsReportedOnce() {
        val (token, backend) = startListening()
        flow.onError(token, VoiceError.System(7))
        flow.onError(token, VoiceError.System(7))
        assertEquals(listOf<VoiceError>(VoiceError.System(7)), output.errors)
        assertEquals(1, backend.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun stopAfterCancelIsNoOp() {
        // Space swipe-up cancels; even a stray stop afterwards must not reach the backend
        val (token, backend) = startListening()
        flow.onPartial(token, "partial")
        assertTrue(flow.cancel())
        flow.stop()
        assertEquals(0, backend.stops)
        assertEquals(1, backend.cancels)
        flow.onFinal(token, "late")
        assertTrue(output.commits.isEmpty())
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun endOfSpeechMovesToStopping() {
        val (token, _) = startListening()
        flow.onEndOfSpeech(token)
        assertEquals(VoiceInputSession.State.Stopping, flow.state)
    }

    @Test
    fun closeReleasesEvenWhenIdle() {
        val (_, backend) = startListening()
        flow.close()
        assertEquals(1, backend.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        flow.close()
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }
}
