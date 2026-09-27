/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatsTest {

    @Test
    fun zeroedAudioHasNoEnergy() {
        val stats = CaptureStats(16000)
        stats.accept(ShortArray(16000), 16000)
        assertEquals(16000L, stats.samples)
        assertEquals(1000L, stats.audioMillis)
        assertEquals(0, stats.peak)
        assertEquals(0L, stats.nonZeroSamples)
        assertEquals(0.0, stats.rms, 0.0)
        assertTrue(stats.peakDbfs.isInfinite())
    }

    @Test
    fun peakAndRmsFollowSamples() {
        val stats = CaptureStats(16000)
        stats.accept(shortArrayOf(1000, -1000, 1000, -1000, 0, 0), 4) // only `count` samples used
        assertEquals(4L, stats.samples)
        assertEquals(1, stats.reads)
        assertEquals(1000, stats.peak)
        assertEquals(1000.0, stats.rms, 1e-9)
        assertEquals(4L, stats.nonZeroSamples)
    }

    @Test
    fun fullScaleNegativePeak() {
        val stats = CaptureStats(16000)
        stats.accept(shortArrayOf(Short.MIN_VALUE), 1)
        assertEquals(32768, stats.peak)
        assertEquals(0.0, stats.peakDbfs, 1e-9)
    }

    @Test
    fun emptyReadsAreCountedSeparately() {
        val stats = CaptureStats(16000)
        stats.accept(ShortArray(4), 0)
        assertEquals(0, stats.reads)
        assertEquals(1, stats.emptyReads)
        assertEquals(0L, stats.samples)
    }

    @Test
    fun clientSilencedReporting() {
        val stats = CaptureStats(16000)
        assertEquals("unknown", stats.clientSilenced)
        stats.recordSilenced(null)
        assertEquals("unknown", stats.clientSilenced)
        stats.recordSilenced(false)
        assertEquals("false", stats.clientSilenced)
        stats.recordSilenced(true)
        assertEquals("true(1/2)", stats.clientSilenced)
    }
}
