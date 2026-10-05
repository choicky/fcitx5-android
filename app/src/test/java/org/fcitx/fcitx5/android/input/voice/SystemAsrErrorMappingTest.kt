/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The verified vivo A/B (2026-10-04) requires ERROR_CLIENT to surface, code included. */
class SystemAsrErrorMappingTest {

    @Test
    fun errorClientIsAVisibleSystemFailureWithItsNumericCode() {
        assertEquals(
            VoiceError.System(SpeechRecognizer.ERROR_CLIENT),
            systemAsrVoiceError(SpeechRecognizer.ERROR_CLIENT)
        )
    }

    @Test
    fun legitimateSilentOutcomesRemainSilent() {
        assertEquals(VoiceError.Silent, systemAsrVoiceError(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(VoiceError.Silent, systemAsrVoiceError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun insufficientPermissionsKeepsThePermissionFlow() {
        assertEquals(VoiceError.PermissionDenied, systemAsrVoiceError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun otherRecognizerCodesKeepTheGenericSystemBucket() {
        assertEquals(VoiceError.System(SpeechRecognizer.ERROR_SERVER), systemAsrVoiceError(SpeechRecognizer.ERROR_SERVER))
        assertEquals(
            VoiceError.System(SpeechRecognizer.ERROR_RECOGNIZER_BUSY),
            systemAsrVoiceError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        )
    }

    @Test
    fun auditClassKeepsThePrivacySafeNumericCode() {
        assertEquals(
            "system" to SpeechRecognizer.ERROR_CLIENT,
            systemAsrVoiceError(SpeechRecognizer.ERROR_CLIENT).auditClass()
        )
    }
}
