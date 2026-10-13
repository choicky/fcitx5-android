/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.speech.SpeechRecognizer
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
        val levels = mutableListOf<Float>()
        val autoTerminations = mutableListOf<VoiceAutoTermination>()
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

        override fun audioLevel(level: Float) {
            levels += level
        }

        val fallbacks = mutableListOf<VoiceError>()

        override fun fellBack(error: VoiceError) {
            fallbacks += error
        }

        override fun autoTerminated(reason: VoiceAutoTermination) {
            autoTerminations += reason
        }
    }

    /** One pending task; tests advance time with [expire]. */
    private class FakeScheduler : VoiceSessionScheduler {
        private var pending: (() -> Unit)? = null
        var pendingDelay = -1L
            private set
        var cancels = 0
            private set

        /** Every delay passed to [schedule], in order. */
        val delays = mutableListOf<Long>()

        val hasPending: Boolean
            get() = pending != null

        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            pendingDelay = delayMs
            delays += delayMs
            var active = true
            pending = task
            return {
                if (active) {
                    active = false
                    cancels++
                    if (pending === task) pending = null
                }
            }
        }

        /** Runs the pending task, as the main thread would after its delay. */
        fun expire(): Unit {
            val task = pending ?: error("no pending timer")
            pending = null
            task()
        }
    }

    private val output = FakeOutput()
    private val scheduler = FakeScheduler()
    private val flow = VoiceInputFlow(output, scheduler)

    private fun startListening(
        backend: FakeBackend = FakeBackend(),
        trigger: VoiceTrigger = VoiceTrigger.Microphone
    ): Pair<Long, FakeBackend> {
        val token = flow.begin()
        assertNotNull(token)
        assertTrue(flow.launch(token!!, "zh-CN", backend, trigger))
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
        assertFalse(flow.launch(token, "", backend, VoiceTrigger.Microphone))
        assertNull(backend.startedToken)
    }

    @Test
    fun stopWhileBackendIsStartingReleasesIt() {
        val token = flow.begin()!!
        val backend = FakeBackend()
        assertTrue(flow.launch(token, "", backend, VoiceTrigger.Microphone))
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
        assertTrue(flow.launch(token, "", backend, VoiceTrigger.Microphone))
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
        assertTrue(flow.launch(token, "", backend, VoiceTrigger.Microphone))
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
    fun panelCancelDiscards() {
        // mic panel [Cancel] -> VoiceInputComponent.cancel()
        val (token, backend) = startListening()
        flow.onPartial(token, "partial")
        assertTrue(flow.cancel())
        assertEquals(1, backend.cancels)
        assertEquals(0, backend.stops)
        flow.onFinal(token, "late")
        assertTrue(output.commits.isEmpty())
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun panelFinishStopsThenCommitsFinal() {
        // mic panel [Done] and Space release -> VoiceInputComponent.stopVoiceInput()
        val (token, backend) = startListening()
        flow.stop()
        assertEquals(1, backend.stops)
        assertEquals(VoiceInputSession.State.Stopping, flow.state)
        flow.onFinal(token, "完成")
        assertEquals(listOf("完成"), output.commits)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun audioLevelIsForwardedWithoutAffectingSession() {
        val (token, backend) = startListening()
        flow.onAudioLevel(token, 0.4f)
        flow.onAudioLevel(token, 0.9f)
        assertEquals(listOf(0.4f, 0.9f), output.levels)
        assertEquals(VoiceInputSession.State.Listening, flow.state)
        assertEquals(0, backend.stops + backend.cancels)
        flow.stop()
        flow.onFinal(token, "text")
        assertEquals(listOf("text"), output.commits)
    }

    @Test
    fun staleAudioLevelIsDropped() {
        val (token, _) = startListening()
        flow.cancel()
        flow.onAudioLevel(token, 0.5f)
        val (second, _) = startListening()
        flow.onAudioLevel(token, 0.6f)
        flow.onAudioLevel(second, 0.7f)
        assertEquals(listOf(0.7f), output.levels)
    }

    @Test
    fun backendWithoutAudioLevelStillWorks() {
        // e.g. System ASR: never reports a level
        val (token, _) = startListening()
        flow.stop()
        flow.onFinal(token, "ok")
        assertEquals(listOf("ok"), output.commits)
        assertTrue(output.levels.isEmpty())
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

    // ---- D035 fallback: selected external service -> eligible Local -> failure ----

    private val early = VoiceError.Service("connect failed")

    /** Starts a session whose backend may fall back to [local]; returns the primary's token. */
    private fun startWithFallback(primary: FakeBackend, local: FakeBackend): Long {
        val token = flow.begin()!!
        assertTrue(flow.launch(token, "zh-CN", primary, VoiceTrigger.Microphone) { local })
        flow.onStarted(token)
        return token
    }

    @Test
    fun earlyServiceFailureFallsBackOnceWithANewToken() {
        val primary = FakeBackend()
        val local = FakeBackend()
        val token = startWithFallback(primary, local)
        flow.onError(token, early)

        assertEquals(listOf<VoiceError>(early), output.fallbacks)
        assertEquals(1, primary.cancels)
        val localToken = local.startedToken!!
        assertTrue(localToken != token)
        assertEquals("zh-CN", local.startedLanguage)
        assertEquals(VoiceInputSession.State.Listening, flow.state)
        assertTrue(output.errors.isEmpty())

        // late events of the failed backend are stale
        flow.onFinal(token, "late")
        flow.onError(token, early)
        assertTrue(output.commits.isEmpty())
        assertEquals(1, output.fallbacks.size)

        flow.onSessionEstablished(localToken)
        flow.stop()
        assertEquals(1, local.stops)
        flow.onFinal(localToken, "本地")
        assertEquals(listOf("本地"), output.commits)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun establishedSessionFailureIsReportedNotMigrated() {
        val local = FakeBackend()
        val token = startWithFallback(FakeBackend(), local)
        flow.onSessionEstablished(token)
        flow.onError(token, early)
        assertNull(local.startedToken)
        assertEquals(listOf<VoiceError>(early), output.errors)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun stopBeforeTheEarlyFailureDoesNotStartAnotherBackend() {
        val local = FakeBackend()
        val token = startWithFallback(FakeBackend(), local)
        flow.stop()
        flow.onError(token, early)
        assertNull(local.startedToken)
        assertEquals(listOf<VoiceError>(early), output.errors)
    }

    @Test
    fun cancelBeforeTheEarlyFailureDoesNotStartAnotherBackend() {
        val local = FakeBackend()
        val token = startWithFallback(FakeBackend(), local)
        flow.cancel()
        flow.onError(token, early)
        assertNull(local.startedToken)
        assertTrue(output.errors.isEmpty())
    }

    @Test
    fun onlyTechnicalServiceFailuresFallBack() {
        listOf(VoiceError.Silent, VoiceError.PermissionDenied, VoiceError.System(9), VoiceError.Capture("busy")).forEach { error ->
            val local = FakeBackend()
            val token = startWithFallback(FakeBackend(), local)
            flow.onError(token, error)
            assertNull(local.startedToken)
            assertEquals(VoiceInputSession.State.Idle, flow.state)
        }
    }

    @Test
    fun systemAsrClientErrorIsReportedNotSwallowedAndNeverFallsBack() {
        val local = FakeBackend()
        val token = startWithFallback(FakeBackend(), local)
        flow.onError(token, systemAsrVoiceError(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(listOf<VoiceError>(VoiceError.System(SpeechRecognizer.ERROR_CLIENT)), output.errors)
        assertTrue(output.fallbacks.isEmpty())
        assertNull(local.startedToken)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun fallbackIsExhaustedAfterOneUse() {
        val local = FakeBackend()
        val token = startWithFallback(FakeBackend(), local)
        flow.onError(token, early)
        val localToken = local.startedToken!!
        val second = VoiceError.Service("model load failed")
        flow.onError(localToken, second)
        assertEquals(listOf<VoiceError>(second), output.errors)
        assertEquals(1, local.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun noFallbackWithoutAnEligibleLocal() {
        val (token, _) = startListening()
        flow.onError(token, early)
        assertEquals(listOf<VoiceError>(early), output.errors)
        assertTrue(output.fallbacks.isEmpty())
    }

    @Test
    fun cancelAfterFallbackReachesTheNewBackend() {
        val local = FakeBackend()
        val token = startWithFallback(FakeBackend(), local)
        flow.onError(token, early)
        assertTrue(flow.cancel())
        assertEquals(1, local.cancels)
        flow.onFinal(local.startedToken!!, "late")
        assertTrue(output.commits.isEmpty())
    }

    @Test
    fun fallbackIsNotCarriedIntoTheNextSession() {
        val token = startWithFallback(FakeBackend(), FakeBackend())
        flow.onFinal(token, "done")
        val next = FakeBackend()
        val nextToken = flow.begin()!!
        assertTrue(flow.launch(nextToken, "", next, VoiceTrigger.Microphone))
        flow.onError(nextToken, early)
        assertEquals(listOf<VoiceError>(early), output.errors)
        assertTrue(output.fallbacks.isEmpty())
    }

    // ---- D035 early bound: a session that never becomes usable fails like startup did ----

    @Test
    fun establishTimeoutWithoutFallbackIsAServiceFailure() {
        val (token, backend) = startListening()
        // the backend never delivers onSessionEstablished
        scheduler.expire()
        val error = output.errors.single()
        assertTrue(error is VoiceError.Service)
        assertTrue((error as VoiceError.Service).detail.contains("established"))
        assertEquals(1, backend.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        assertTrue(output.commits.isEmpty())
    }

    @Test
    fun establishTimeoutArmsTheOneD035Fallback() {
        val primary = FakeBackend()
        val local = FakeBackend()
        val token = startWithFallback(primary, local)
        scheduler.expire() // establish window of the external backend
        assertEquals(1, output.fallbacks.size)
        assertTrue(output.fallbacks.single() is VoiceError.Service)
        assertEquals(1, primary.cancels)
        assertTrue(output.errors.isEmpty())
        val localToken = local.startedToken!!
        assertTrue(localToken != token)
        // the fallback backend gets its own fresh window, then delivers normally
        flow.onSessionEstablished(localToken)
        flow.stop()
        assertEquals(1, local.stops)
        flow.onFinal(localToken, "本地")
        assertEquals(listOf("本地"), output.commits)
        assertEquals(1, output.fallbacks.size) // still exactly one fallback for the gesture
    }

    @Test
    fun preEstablishmentEvidenceKeepsFallbackOnAFreshEstablishWindow() {
        // the fallback toast tells the user to repeat speech: pre-establishment speech proof
        // neither replaces the Establish bound nor carries into the fallback backend
        val primary = FakeBackend { token, events ->
            events.onSpeechStarted(token)
            events.onPartial(token, "喂")
        }
        val local = FakeBackend()
        val token = startWithFallback(primary, local)
        assertEquals(VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS, scheduler.pendingDelay)
        scheduler.expire() // establish timeout -> the one D035 fallback
        assertEquals(1, output.fallbacks.size)
        val localToken = local.startedToken!!
        assertEquals(
            listOf(
                VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS, // primary
                VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS  // re-armed fresh for the fallback
            ),
            scheduler.delays
        )
        // the fallback session behaves from scratch: establish -> NoSpeech -> level-armed silence
        flow.onSessionEstablished(localToken)
        assertEquals(VoiceEndpointPolicy.NO_SPEECH_TIMEOUT_MS, scheduler.pendingDelay)
        flow.onAudioLevel(localToken, 0.9f)
        assertEquals(VoiceEndpointPolicy.TRAILING_SILENCE_MS, scheduler.pendingDelay)
        scheduler.expire()
        assertEquals(1, local.stops)
        flow.onFinal(localToken, "本地")
        assertEquals(listOf("本地"), output.commits)
        assertEquals(1, output.fallbacks.size) // still exactly one fallback for the gesture
    }

    @Test
    fun establishTimeoutAppliesToSpaceSessionsToo() {
        // provider health is trigger-independent: even a held session may not hang forever
        val (token, backend) = startListening(trigger = VoiceTrigger.SpaceHold)
        scheduler.expire()
        assertTrue(output.errors.single() is VoiceError.Service)
        assertEquals(1, backend.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    // ---- Stage.Establish is left only by establishment, a terminal path or its own timeout ----

    @Test
    fun preEstablishmentAudioLevelKeepsTheBound() {
        val (token, backend) = startListening()
        flow.onAudioLevel(token, 0.9f)
        assertEquals(VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS, scheduler.pendingDelay)
        scheduler.expire() // still the Establish bound, not a silence window
        assertTrue(output.errors.single() is VoiceError.Service)
        assertEquals(1, backend.cancels)
        assertTrue(output.autoTerminations.isEmpty())
    }

    @Test
    fun preEstablishmentSpeechStartedKeepsTheBound() {
        val (token, _) = startListening()
        flow.onSpeechStarted(token)
        assertEquals(VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS, scheduler.pendingDelay)
        scheduler.expire()
        assertTrue(output.errors.single() is VoiceError.Service)
    }

    @Test
    fun preEstablishmentPartialKeepsTheBound() {
        val (token, _) = startListening()
        flow.onPartial(token, "提前")
        assertEquals(VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS, scheduler.pendingDelay)
        scheduler.expire()
        assertTrue(output.errors.single() is VoiceError.Service)
    }

    @Test
    fun stalePrimaryEventsCannotTouchTheFallbackSession() {
        val primary = FakeBackend()
        val local = FakeBackend()
        val token = startWithFallback(primary, local)
        scheduler.expire() // establish timeout -> the one D035 fallback
        val localToken = local.startedToken!!
        // late events of the released primary backend must not re-arm or terminate anything
        flow.onAudioLevel(token, 0.9f)
        flow.onSpeechStarted(token)
        flow.onPartial(token, "迟到")
        assertEquals(VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS, scheduler.pendingDelay)
        flow.onSessionEstablished(localToken)
        assertEquals(VoiceEndpointPolicy.NO_SPEECH_TIMEOUT_MS, scheduler.pendingDelay)
        flow.onAudioLevel(localToken, 0.9f)
        assertEquals(VoiceEndpointPolicy.TRAILING_SILENCE_MS, scheduler.pendingDelay)
        scheduler.expire()
        assertEquals(1, local.stops)
        flow.onFinal(localToken, "本地")
        assertEquals(listOf("本地"), output.commits)
        assertEquals(1, output.fallbacks.size)
    }

    @Test
    fun silenceEstimationComesOnlyFromTheContinuousLevelStream() {
        // discrete proofs alone leave NoSpeech -> Active untimed; one loud chunk arms the
        // trailing-silence window and its expiry finalizes like a second click
        val (token, backend) = startListening()
        flow.onSessionEstablished(token)
        flow.onSpeechStarted(token)
        flow.onPartial(token, "嗯")
        assertFalse(scheduler.hasPending)
        flow.onAudioLevel(token, 0.8f)
        assertEquals(VoiceEndpointPolicy.TRAILING_SILENCE_MS, scheduler.pendingDelay)
        scheduler.expire()
        assertEquals(1, backend.stops)
        flow.onFinal(token, "正常")
        assertEquals(listOf("正常"), output.commits)
        assertEquals(listOf(VoiceAutoTermination.TrailingSilence), output.autoTerminations)
    }

    // ---- Microphone endpointing: idle no-speech drop and trailing-silence finalize ----

    @Test
    fun micNoSpeechTimeoutCancelsTheIdleSessionWithoutCommitting() {
        val (token, backend) = startListening()
        flow.onSessionEstablished(token)
        // below-threshold chunks are silence: they must not be treated as speech
        flow.onAudioLevel(token, 0.05f)
        flow.onAudioLevel(token, VoiceEndpointPolicy.SPEECH_LEVEL - 0.01f)
        assertEquals(VoiceInputSession.State.Listening, flow.state)
        scheduler.expire() // the no-speech window armed at establishment
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        assertEquals(1, backend.cancels)
        assertEquals(0, backend.stops)
        assertTrue(output.commits.isEmpty())
        assertTrue(output.errors.isEmpty())
        assertEquals(listOf(VoiceAutoTermination.NoSpeech), output.autoTerminations)
        // late events of the dropped session stay stale
        flow.onFinal(token, "迟到")
        assertTrue(output.commits.isEmpty())
    }

    @Test
    fun micNoSpeechWindowMeasuresFromEstablishment() {
        val (token, _) = startListening()
        assertEquals(VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS, scheduler.pendingDelay)
        flow.onSessionEstablished(token)
        assertEquals(VoiceEndpointPolicy.NO_SPEECH_TIMEOUT_MS, scheduler.pendingDelay)
    }

    @Test
    fun micSpeechThenSilenceAutoFinalizesAndCommits() {
        val (token, backend) = startListening()
        flow.onSessionEstablished(token)
        flow.onAudioLevel(token, 0.9f) // speech evidence
        assertEquals(VoiceEndpointPolicy.TRAILING_SILENCE_MS, scheduler.pendingDelay)
        flow.onAudioLevel(token, 0.02f) // silence before the window ends: no early stop
        assertEquals(0, backend.stops)
        scheduler.expire()
        assertEquals(1, backend.stops)
        assertEquals(VoiceInputSession.State.Stopping, flow.state)
        assertEquals(listOf(VoiceAutoTermination.TrailingSilence), output.autoTerminations)
        flow.onFinal(token, "你好")
        assertEquals(listOf("你好"), output.commits)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun providerSpeechSignalsPreventNoSpeechButNeverForceFinalization() {
        // System-style backend: discrete onSpeechStarted, no continuous AudioLevel stream —
        // the watchdog must never stop it on a silence guess
        val (token, backend) = startListening()
        flow.onSessionEstablished(token)
        flow.onSpeechStarted(token)
        assertFalse(scheduler.hasPending)
        flow.onSpeechStarted(token) // repeated one-shot events keep it untimed too
        assertFalse(scheduler.hasPending)
        assertEquals(VoiceInputSession.State.Listening, flow.state)
        assertEquals(0, backend.stops + backend.cancels)
        assertTrue(output.autoTerminations.isEmpty())
        // a long utterance beyond any silence window stays alive until the service ends it
        flow.onEndOfSpeech(token)
        assertFalse(scheduler.hasPending)
        flow.onFinal(token, "系统")
        assertEquals(listOf("系统"), output.commits)
        assertTrue(output.autoTerminations.isEmpty())
    }

    @Test
    fun nonBlankPartialEndsNoSpeechWithoutArmingSilence() {
        val (token, backend) = startListening()
        flow.onSessionEstablished(token)
        flow.onPartial(token, "话")
        assertFalse(scheduler.hasPending)
        assertEquals(VoiceInputSession.State.Listening, flow.state)
        assertEquals(0, backend.stops + backend.cancels)
        flow.onFinal(token, "说话")
        assertEquals(listOf("说话"), output.commits)
    }

    @Test
    fun blankPartialKeepsTheNoSpeechWindow() {
        val (token, _) = startListening()
        flow.onSessionEstablished(token)
        flow.onPartial(token, " ")
        assertEquals(VoiceEndpointPolicy.NO_SPEECH_TIMEOUT_MS, scheduler.pendingDelay)
        scheduler.expire()
        assertEquals(listOf(VoiceAutoTermination.NoSpeech), output.autoTerminations)
    }

    @Test
    fun noAutoTerminationAfterExplicitCancelOrStop() {
        val (token, backend) = startListening()
        flow.onSessionEstablished(token)
        flow.onAudioLevel(token, 0.9f)
        // user swipes up / cancels first: nothing may auto-commit afterwards
        assertTrue(flow.cancel())
        assertFalse(scheduler.hasPending)
        val (second, secondBackend) = startListening()
        assertEquals(0, backend.stops)
        assertEquals(1, backend.cancels)
        assertEquals(0, secondBackend.stops + secondBackend.cancels)
        flow.onFinal(second, null) // end it before the next gesture

        // explicit second-click finish before the silence window ends
        val (third, thirdBackend) = startListening()
        flow.onSessionEstablished(third)
        flow.onAudioLevel(third, 0.9f)
        flow.stop()
        assertEquals(1, thirdBackend.stops)
        assertFalse(scheduler.hasPending)
        flow.onFinal(third, "手动")
        assertEquals(listOf("手动"), output.commits)
        assertTrue(output.autoTerminations.isEmpty())
    }

    @Test
    fun systemEndOfSpeechDisarmsTheWatchdog() {
        val (token, backend) = startListening()
        flow.onSessionEstablished(token)
        flow.onSpeechStarted(token)
        flow.onEndOfSpeech(token)
        assertFalse(scheduler.hasPending)
        assertEquals(0, backend.stops) // the service ended the utterance itself
        flow.onFinal(token, "句子")
        assertEquals(listOf("句子"), output.commits)
    }

    @Test
    fun spaceHoldNeverAutoFinalizesOnSilence() {
        val (token, backend) = startListening(trigger = VoiceTrigger.SpaceHold)
        flow.onSessionEstablished(token)
        // the speech-state timers are microphone policy only
        assertFalse(scheduler.hasPending)
        flow.onAudioLevel(token, 0.9f)
        flow.onAudioLevel(token, 0.02f)
        flow.onSpeechStarted(token)
        flow.onPartial(token, "说到一半")
        assertFalse(scheduler.hasPending)
        assertEquals(VoiceInputSession.State.Listening, flow.state)
        assertEquals(0, backend.stops + backend.cancels)
        assertEquals(0, output.autoTerminations.size)
        // release still finishes normally
        flow.stop()
        assertEquals(1, backend.stops)
        flow.onFinal(token, "说完")
        assertEquals(listOf("说完"), output.commits)
    }

    @Test
    fun newSessionAfterAutoTerminationStartsFresh() {
        val (token, first) = startListening()
        flow.onSessionEstablished(token)
        scheduler.expire() // NoSpeech cancel
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        assertEquals(1, first.cancels)

        val (second, secondBackend) = startListening()
        assertEquals(VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS, scheduler.pendingDelay)
        flow.onSessionEstablished(second)
        flow.onAudioLevel(second, 0.8f)
        scheduler.expire()
        assertEquals(1, secondBackend.stops)
        flow.onFinal(second, "第二次")
        assertEquals(listOf("第二次"), output.commits)
    }

    // ---- optional text post-processing (D017): who owns the pending final ----

    /** An answer is delivered only when the test chooses, as off-main-thread work would be. */
    private class FakeProcessor : TextPostProcessor {
        var calls = 0
            private set
        var rawSeen: String? = null
            private set
        var languageSeen: String? = null
            private set

        /** Every callback handed to the processor, oldest first: a test may answer a stale one. */
        private val replies = mutableListOf<(String?) -> Unit>()

        override fun process(raw: String, languageTag: String, callback: (String?) -> Unit) {
            calls++
            rawSeen = raw
            languageSeen = languageTag
            replies += callback
        }

        fun answer(processed: String?, index: Int = replies.lastIndex) {
            replies.getOrNull(index)?.invoke(processed) ?: error("the flow never asked")
        }
    }

    /** A processing window needs its own output and timer state, so [flow] is not the shared one. */
    private class PostHarness(val processor: FakeProcessor = FakeProcessor()) {
        val output = FakeOutput()
        val scheduler = FakeScheduler()
        val flow = VoiceInputFlow(output, scheduler, processor)

        fun startListening(): Long {
            val token = flow.begin() ?: error("no token")
            assertTrue(flow.launch(token, "zh-CN", FakeBackend(), VoiceTrigger.Microphone))
            flow.onStarted(token)
            return token
        }

        /** The gesture ended and the final was accepted, but nothing is committed yet. */
        fun finalAccepted(text: String): Long {
            val token = startListening()
            flow.stop()
            flow.onFinal(token, text)
            return token
        }
    }

    @Test
    fun onlyTheFinalReachesTheProcessorAndItsAnswerIsCommittedOnce() {
        val h = PostHarness()
        val token = h.startListening()
        h.flow.onPartial(token, "你")
        assertEquals(0, h.processor.calls) // partials are never post-processed
        h.flow.stop()
        h.flow.onFinal(token, "你好世界")
        assertTrue(h.output.commits.isEmpty())
        assertEquals(VoiceInputSession.State.Stopping, h.flow.state)
        assertTrue(h.scheduler.hasPending) // the deferred commit is a bounded window
        assertEquals("你好世界", h.processor.rawSeen)
        assertEquals("zh-CN", h.processor.languageSeen)
        h.processor.answer("你好，世界。")
        assertEquals(listOf("你好，世界。"), h.output.commits)
        assertEquals(VoiceInputSession.State.Idle, h.flow.state)
        assertFalse(h.scheduler.hasPending)
    }

    @Test
    fun processorFailureKeepsTheRawTranscript() {
        val h = PostHarness()
        h.finalAccepted("明天开会")
        h.processor.answer(null) // the attempt failed on its own side
        assertEquals(listOf("明天开会"), h.output.commits)
        assertEquals(VoiceInputSession.State.Idle, h.flow.state)
    }

    @Test
    fun throwingProcessorKeepsTheRawTranscript() {
        val output = FakeOutput()
        val flow = VoiceInputFlow(output, FakeScheduler(), TextPostProcessor { _, _, _ ->
            error("runtime unavailable")
        })
        val token = flow.begin() ?: error("no token")
        assertTrue(flow.launch(token, "zh-CN", FakeBackend(), VoiceTrigger.Microphone))
        flow.onStarted(token)
        flow.stop()
        flow.onFinal(token, "一句话")
        assertEquals(listOf("一句话"), output.commits)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
    }

    @Test
    fun timeoutCommitsTheRawTranscriptAndDropsTheLateAnswer() {
        val h = PostHarness()
        h.finalAccepted("较长的一段话")
        assertEquals(TextPostPolicy.TIMEOUT_MS, h.scheduler.pendingDelay)
        h.scheduler.expire()
        assertEquals(listOf("较长的一段话"), h.output.commits)
        assertEquals(VoiceInputSession.State.Idle, h.flow.state)
        h.processor.answer("迟到") // the runtime finally answered
        assertEquals(listOf("较长的一段话"), h.output.commits) // still exactly one commit
    }

    @Test
    fun blankProcessedOutputKeepsTheRawTranscript() {
        val h = PostHarness()
        h.finalAccepted("空格")
        h.processor.answer("   ")
        assertEquals(listOf("空格"), h.output.commits)
    }

    @Test
    fun cancelWhileProcessingCommitsNothingAndLeavesTheNextGestureUsable() {
        val h = PostHarness()
        val first = h.finalAccepted("别提交")
        assertTrue(h.flow.cancel()) // panel cancel -> VoiceInputComponent.cancel()
        assertEquals(VoiceInputSession.State.Idle, h.flow.state)
        h.processor.answer("处理完了")
        assertTrue(h.output.commits.isEmpty()) // neither the processed text nor the raw transcript
        assertFalse(h.scheduler.hasPending)
        assertTrue(h.output.clears > 0) // the shown partial is gone, as a cancel always cleared it

        val second = h.startListening()
        assertTrue(second != first)
        h.flow.stop()
        h.flow.onFinal(second, "第二次")
        h.processor.answer("第二次。")
        assertEquals(listOf("第二次。"), h.output.commits)
    }

    @Test
    fun inputTargetChangeWhileProcessingCommitsNothing() {
        // VoiceInputComponent.onStartInput -> cancel(): the session is already Idle here, so its
        // generation cannot be what invalidates this result
        val h = PostHarness()
        h.finalAccepted("目标变了")
        h.flow.cancel()
        h.processor.answer("处理完了")
        assertTrue(h.output.commits.isEmpty())
    }

    @Test
    fun closeWhileProcessingCommitsNothing() {
        val h = PostHarness()
        h.finalAccepted("组件关闭")
        h.flow.close()
        h.processor.answer("处理完了")
        assertTrue(h.output.commits.isEmpty())
        assertEquals(VoiceInputSession.State.Idle, h.flow.state)
        assertFalse(h.scheduler.hasPending)
    }

    @Test
    fun newerSessionSupersedesThePendingResultAndStaysValid() {
        val h = PostHarness()
        h.finalAccepted("第一次")
        val second = h.startListening() // begin() invalidates the answer still on its way
        h.processor.answer("迟到的第一次", index = 0)
        assertTrue(h.output.commits.isEmpty())
        assertEquals(VoiceInputSession.State.Listening, h.flow.state) // the new session is untouched
        h.flow.stop()
        h.flow.onFinal(second, "第二次")
        assertEquals("第二次", h.processor.rawSeen)
        h.processor.answer("第二次，好。")
        assertEquals(listOf("第二次，好。"), h.output.commits)
    }

    @Test
    fun aViolatedExactlyOnceContractCannotCommitTwice() {
        val h = PostHarness()
        h.finalAccepted("一句话")
        h.processor.answer("一句话。")
        h.processor.answer("又一句")
        assertEquals(listOf("一句话。"), h.output.commits)
    }

    @Test
    fun strayStopDuringProcessingNeitherDropsNorDuplicates() {
        val h = PostHarness()
        h.finalAccepted("已经收尾")
        h.flow.stop() // a second click after the final was already accepted
        assertTrue(h.output.commits.isEmpty())
        h.processor.answer("已经收尾。")
        assertEquals(listOf("已经收尾。"), h.output.commits)
    }

    @Test
    fun lateErrorForAnAcceptedFinalCommitsNothingAndReportsNothing() {
        val h = PostHarness()
        val token = h.finalAccepted("最终")
        h.flow.onError(token, VoiceError.System(2)) // complete() already consumed this token
        assertTrue(h.output.errors.isEmpty())
        assertTrue(h.output.commits.isEmpty())
        h.processor.answer("最终。")
        assertEquals(listOf("最终。"), h.output.commits)
    }

    @Test
    fun blankFinalNeverReachesTheProcessor() {
        val h = PostHarness()
        val first = h.startListening()
        h.flow.stop()
        h.flow.onFinal(first, null)
        val second = h.startListening()
        h.flow.stop()
        h.flow.onFinal(second, "  ")
        assertEquals(0, h.processor.calls)
        assertTrue(h.output.commits.isEmpty())
        assertEquals(VoiceInputSession.State.Idle, h.flow.state)
        assertFalse(h.scheduler.hasPending)
    }

    @Test
    fun processorlessFlowKeepsTheExistingCommitPath() {
        val (token, backend) = startListening()
        flow.stop()
        flow.onFinal(token, "直接提交")
        assertEquals(listOf("直接提交"), output.commits)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        assertFalse(scheduler.hasPending) // no processing window was armed at all
        assertEquals(1, backend.cancels)
    }

    @Test
    fun d035FallbackIsUntouchedAndItsFinalIsProcessedOnce() {
        val processor = FakeProcessor()
        val output = FakeOutput()
        val scheduler = FakeScheduler()
        val flow = VoiceInputFlow(output, scheduler, processor)
        val primary = FakeBackend()
        val local = FakeBackend()
        val token = flow.begin() ?: error("no token")
        assertTrue(flow.launch(token, "zh-CN", primary, VoiceTrigger.Microphone) { local })
        flow.onStarted(token)
        flow.onError(token, VoiceError.Service("connect failed"))
        assertEquals(listOf<VoiceError>(VoiceError.Service("connect failed")), output.fallbacks)
        assertNull(processor.rawSeen) // nothing was recognized yet, so nothing is processed
        val localToken = local.startedToken!!
        assertTrue(localToken != token)
        flow.stop()
        flow.onFinal(localToken, "本地识别")
        assertEquals("本地识别", processor.rawSeen)
        processor.answer("本地识别。")
        assertEquals(listOf("本地识别。"), output.commits)
        assertEquals(1, primary.cancels)
        assertEquals(VoiceInputSession.State.Idle, flow.state)
        assertEquals(1, output.fallbacks.size) // still exactly one fallback for the gesture
    }
}
