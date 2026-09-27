/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.max

/**
 * Microphone capture owned by the IME process: 16 kHz mono 16-bit PCM from the
 * VOICE_RECOGNITION source. Not thread-safe; one thread opens, reads and releases it.
 */
internal class AudioCapture private constructor(private val record: AudioRecord) {

    private var released = false

    val sampleRate: Int
        get() = record.sampleRate

    fun start() {
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "AudioRecord recordingState=${record.recordingState} after startRecording"
        }
    }

    /** Blocking read; returns the sample count, or a negative AudioRecord error code. */
    fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

    /** Whether the framework currently feeds this client silence; `null` if unknown. */
    fun isClientSilenced(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            record.activeRecordingConfiguration?.isClientSilenced
        } else null

    /**
     * Reads 20 ms chunks while [keepGoing] holds, folding them into [stats], reporting each
     * chunk's [AudioLevel] to [onLevel] and handing it to [onChunk].
     * Returns `null` when stopped, or a failure detail.
     */
    fun pump(
        stats: CaptureStats,
        keepGoing: () -> Boolean,
        onLevel: ((Float) -> Unit)? = null,
        onChunk: (buffer: ShortArray, count: Int) -> Unit = { _, _ -> }
    ): String? {
        val buffer = ShortArray(SAMPLE_RATE / READS_PER_SECOND)
        var nextSilenceCheck = 0L
        while (keepGoing()) {
            val count = read(buffer)
            if (count < 0) return "AudioRecord.read=$count"
            stats.accept(buffer, count)
            if (stats.samples >= nextSilenceCheck) {
                stats.recordSilenced(isClientSilenced())
                nextSilenceCheck = stats.samples + SAMPLE_RATE / 4
            }
            if (count > 0) {
                onLevel?.invoke(AudioLevel.of(buffer, count))
                onChunk(buffer, count)
            }
        }
        return null
    }

    /** Idempotent; always releases the native recorder, even if stopping fails. */
    fun release() {
        if (released) return
        released = true
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.stop() }
        }
        record.release()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val READS_PER_SECOND = 50

        // at least 200 ms of audio, so short scheduling delays don't overrun the buffer
        private const val MIN_BUFFER_BYTES = SAMPLE_RATE * 2 / 5

        // RECORD_AUDIO is checked by VoiceInputComponent before a session starts
        @SuppressLint("MissingPermission")
        fun open(): AudioCapture {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            check(minBuffer > 0) { "AudioRecord.getMinBufferSize=$minBuffer" }
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                max(minBuffer, MIN_BUFFER_BYTES)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                error("AudioRecord state=${record.state} (not initialized)")
            }
            return AudioCapture(record)
        }
    }
}
