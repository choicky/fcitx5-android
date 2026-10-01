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
    fun localAsrPreferenceValues() {
        assertNull(localAsrModel("Off"))
        assertNull(localAsrModel(""))
        assertNull(localAsrModel("ZipformerZh"))
        assertEquals(LocalAsrModel.FunAsrNano, localAsrModel("FunAsrNano"))
        assertEquals(1, localAsrThreads("1"))
        assertEquals(4, localAsrThreads("4"))
        assertEquals(4, localAsrThreads("8"))
        assertEquals(DEFAULT_LOCAL_ASR_THREADS, localAsrThreads("x"))
    }
}
