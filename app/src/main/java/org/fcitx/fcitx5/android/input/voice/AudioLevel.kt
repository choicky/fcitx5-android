/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlin.math.log10
import kotlin.math.sqrt

/** Microphone loudness for the voice panel meter; only this number leaves the audio thread. */
internal object AudioLevel {

    // Phase 4B.1 device data: speech RMS about -42 dBFS, room silence -57 to -66 dBFS
    private const val FLOOR_DBFS = -65.0
    private const val CEILING_DBFS = -20.0

    /** RMS of one PCM16 chunk mapped to 0 (silence) .. 1 (loud speech). */
    fun of(buffer: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble()
            sumSquares += v * v
        }
        val rms = sqrt(sumSquares / count) / FULL_SCALE
        if (rms <= 0.0) return 0f
        val dbfs = 20 * log10(rms)
        return ((dbfs - FLOOR_DBFS) / (CEILING_DBFS - FLOOR_DBFS)).coerceIn(0.0, 1.0).toFloat()
    }

    private const val FULL_SCALE = 32768.0
}

/** Fast attack, slower release: the meter follows speech onsets without flickering. */
internal class LevelSmoother(
    private val attack: Float = 0.6f,
    private val release: Float = 0.15f
) {
    var value = 0f
        private set

    fun update(level: Float): Float {
        value += (level - value) * if (level > value) attack else release
        return value
    }

    fun reset() {
        value = 0f
    }
}
