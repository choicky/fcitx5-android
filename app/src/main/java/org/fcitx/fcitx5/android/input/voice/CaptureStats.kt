/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Aggregate statistics of 16-bit PCM captured in one session. Samples are folded into the
 * counters and never retained, so the probe can tell a working microphone from successful
 * reads of silence without keeping any audio.
 */
internal class CaptureStats(val sampleRate: Int) {

    var samples = 0L
        private set
    var reads = 0
        private set
    var emptyReads = 0
        private set
    var nonZeroSamples = 0L
        private set
    var peak = 0
        private set
    private var sumSquares = 0.0

    var silencedChecks = 0
        private set
    var silencedObserved = 0
        private set

    fun accept(buffer: ShortArray, count: Int) {
        if (count <= 0) {
            emptyReads++
            return
        }
        reads++
        for (i in 0 until count) {
            val v = buffer[i].toInt()
            val abs = if (v < 0) -v else v
            if (abs > peak) peak = abs
            if (v != 0) nonZeroSamples++
            sumSquares += (v * v).toDouble()
        }
        samples += count
    }

    /** `null` means the platform gave no answer (API < 29 or no active configuration). */
    fun recordSilenced(silenced: Boolean?) {
        if (silenced == null) return
        silencedChecks++
        if (silenced) silencedObserved++
    }

    val audioMillis: Long
        get() = samples * 1000 / sampleRate

    val rms: Double
        get() = if (samples == 0L) 0.0 else sqrt(sumSquares / samples)

    val peakDbfs: Double
        get() = dbfs(peak.toDouble())

    val rmsDbfs: Double
        get() = dbfs(rms)

    val clientSilenced: String
        get() = when {
            silencedChecks == 0 -> "unknown"
            silencedObserved == 0 -> "false"
            else -> "true($silencedObserved/$silencedChecks)"
        }

    fun summary() = "audio=${audioMillis}ms reads=$reads emptyReads=$emptyReads " +
            "samples=$samples nonZero=$nonZeroSamples peak=$peak (%.1f dBFS) rms=%.1f (%.1f dBFS) "
                .format(Locale.ROOT, peakDbfs, rms, rmsDbfs) +
            "clientSilenced=$clientSilenced"

    private fun dbfs(amplitude: Double) =
        if (amplitude <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(amplitude / FULL_SCALE)

    companion object {
        private const val FULL_SCALE = 32768.0
    }
}
