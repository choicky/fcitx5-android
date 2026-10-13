/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * The frozen prompt is part of the PoC's measurement setup: host evidence and device runs
 * are comparable only if the bytes sent are identical. This pins the constant to the
 * recorded identity of `prompt-v1.txt` (982 bytes, trailing newline included).
 */
class LlamaPostProcessorPromptTest {

    @Test
    fun promptV1IsTheFrozen982ByteSequence() {
        val bytes = POSTPROC_PROMPT_V1.toByteArray(Charsets.UTF_8)
        assertEquals(982, bytes.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals("9517338661dd4ac3ddc74b4e59a5549476a40f1ec51927ef635554b79534c79c", sha)
    }
}
