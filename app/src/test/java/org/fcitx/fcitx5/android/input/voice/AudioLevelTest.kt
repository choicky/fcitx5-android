/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {

    private fun tone(amplitude: Int, count: Int = 320) =
        ShortArray(count) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }

    @Test
    fun silenceIsZero() {
        assertEquals(0f, AudioLevel.of(ShortArray(320), 320))
        assertEquals(0f, AudioLevel.of(ShortArray(0), 0))
    }

    @Test
    fun louderIsHigher() {
        val quiet = AudioLevel.of(tone(50), 320)      // about -56 dBFS, room noise
        val speech = AudioLevel.of(tone(260), 320)    // about -42 dBFS, measured speech RMS
        val loud = AudioLevel.of(tone(8000), 320)     // about -12 dBFS
        assertTrue(quiet < speech)
        assertTrue(speech < loud)
        assertTrue(quiet in 0f..0.3f)
        assertEquals(1f, loud)
    }

    @Test
    fun onlyCountSamplesAreUsed() {
        val buffer = tone(8000, 320)
        buffer.fill(0, 160, 320)
        assertEquals(AudioLevel.of(tone(8000, 160), 160), AudioLevel.of(buffer, 160))
    }

    @Test
    fun smootherAttacksFasterThanItReleases() {
        val smoother = LevelSmoother()
        val rise = smoother.update(1f)
        assertTrue(rise > 0.5f)
        val afterRelease = smoother.update(0f)
        // falls by the smaller release step
        assertTrue(rise - afterRelease < rise * 0.5f)
        smoother.reset()
        assertEquals(0f, smoother.value)
    }
}
