/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceBackendSelectionTest {

    @Test
    fun releaseAlwaysUsesSystemAsr() {
        assertEquals(
            VoiceBackendKind.System,
            configuredBackendKind(debug = false, LocalAsrModel.FunAsrNano, doubaoAsr = true, captureProbe = true)
        )
    }

    @Test
    fun debugPrecedenceLocalThenDoubaoThenProbe() {
        assertEquals(
            VoiceBackendKind.LocalAsr(LocalAsrModel.ZipformerZh),
            configuredBackendKind(debug = true, LocalAsrModel.ZipformerZh, doubaoAsr = true, captureProbe = true)
        )
        assertEquals(
            VoiceBackendKind.DoubaoAsr,
            configuredBackendKind(debug = true, null, doubaoAsr = true, captureProbe = true)
        )
        assertEquals(
            VoiceBackendKind.CaptureProbe,
            configuredBackendKind(debug = true, null, doubaoAsr = false, captureProbe = true)
        )
        assertEquals(
            VoiceBackendKind.System,
            configuredBackendKind(debug = true, null, doubaoAsr = false, captureProbe = false)
        )
    }

    @Test
    fun localAsrPreferenceValues() {
        assertNull(localAsrModel("Off"))
        assertNull(localAsrModel(""))
        assertEquals(LocalAsrModel.ZipformerZh, localAsrModel("ZipformerZh"))
        assertEquals(LocalAsrModel.FunAsrNano, localAsrModel("FunAsrNano"))
        assertEquals(1, localAsrThreads("1"))
        assertEquals(4, localAsrThreads("4"))
        assertEquals(4, localAsrThreads("8"))
        assertEquals(DEFAULT_LOCAL_ASR_THREADS, localAsrThreads("x"))
    }
}
