/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ceiling resolution for the bounded capture-duration policy (D081). */
class VoiceCapturePolicyTest {

    @Test
    fun commonCeilingIs180Seconds() {
        assertEquals(180_000L, VoiceCapturePolicy.MAX_CAPTURE_MS)
    }

    @Test
    fun wholeFileProtocolKeepsIts60SecondPremise() {
        // the OpenAI-compatible post-stop window is premised on ≤ 60 s of audio
        assertEquals(60_000L, VoiceCapturePolicy.WHOLE_FILE_MAX_CAPTURE_MS)
        assertEquals(60_000L, minOf(VoiceCapturePolicy.MAX_CAPTURE_MS,
            VoiceCapturePolicy.WHOLE_FILE_MAX_CAPTURE_MS))
    }

    @Test
    fun funAsrNanoCurrentPinIsProtectedBelowItsObservedFailurePoint() {
        // dual-device evidence: empty finals from about 34 s with the pinned export
        assertEquals(30_000L, LocalAsrModel.FunAsrNano.maxCaptureMillis)
        assertEquals(30_000L,
            minOf(VoiceCapturePolicy.MAX_CAPTURE_MS, LocalAsrModel.FunAsrNano.maxCaptureMillis))
    }

    @Test
    fun xAsrModelsInheritTheCommonCeiling() {
        for (model in listOf(LocalAsrModel.XAsrOffline, LocalAsrModel.XAsrStreaming960)) {
            assertEquals(VoiceCapturePolicy.MAX_CAPTURE_MS, model.maxCaptureMillis)
            assertEquals(VoiceCapturePolicy.MAX_CAPTURE_MS,
                minOf(VoiceCapturePolicy.MAX_CAPTURE_MS, model.maxCaptureMillis))
        }
    }

    @Test
    fun everyProductModelHasAFiniteBoundForHeldSessions() {
        // SpaceHold has no flow timer: every selectable model must stay finitely bounded
        for (model in LocalAsrModel.userVisibleEntries) {
            val bound = minOf(VoiceCapturePolicy.MAX_CAPTURE_MS, model.maxCaptureMillis)
            assertTrue("$model", bound > 0 && bound <= VoiceCapturePolicy.MAX_CAPTURE_MS)
        }
    }

    @Test
    fun tencentHonorsTheSelectableEnginesLowerHardLimit() {
        assertEquals(60_000L, TencentAsrConfig.maxCaptureMs(TencentAsrConfig.HY_ASR_ENGINE))
        for (engine in TencentAsrConfig.ENGINES) {
            val bound = TencentAsrConfig.maxCaptureMs(engine)
            assertTrue(engine, bound > 0 && bound <= VoiceCapturePolicy.MAX_CAPTURE_MS)
        }
        // engines without a documented lower limit, and the default, inherit the ceiling
        assertEquals(VoiceCapturePolicy.MAX_CAPTURE_MS,
            TencentAsrConfig.maxCaptureMs(TencentAsrConfig.DEFAULT_ENGINE))
        assertEquals(VoiceCapturePolicy.MAX_CAPTURE_MS, TencentAsrConfig.maxCaptureMs("16k_zh"))
        assertEquals(TencentAsrConfig.DEFAULT_ENGINE,
            TencentAsrConfig.fromStore(null).engine)
        assertEquals(60_000L, TencentAsrConfig.maxCaptureMs(
            TencentAsrConfig.fromStore(mapOf(TencentAsrConfig.ENGINE to TencentAsrConfig.HY_ASR_ENGINE)).engine))
    }

    @Test
    fun endpointingPolicyStaysSeparateFromTheCeiling() {
        // auto-stop values are unchanged by this policy (regression pin)
        assertEquals(30_000L, VoiceEndpointPolicy.ESTABLISH_TIMEOUT_MS)
        assertEquals(10_000L, VoiceEndpointPolicy.NO_SPEECH_TIMEOUT_MS)
        assertEquals(1_500L, VoiceEndpointPolicy.TRAILING_SILENCE_MS)
        assertEquals(0.30f, VoiceEndpointPolicy.SPEECH_LEVEL, 0f)
    }
}
