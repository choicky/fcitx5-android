/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

/**
 * Capture-only probe for the Phase 4B gate: records from Fcitx-owned [AudioCapture] and reports
 * aggregate [CaptureStats]. It performs no recognition and never produces text; audio is neither
 * stored nor logged.
 */
internal class CaptureProbeBackend(
    private val context: Context,
    private val scope: CoroutineScope
) : VoiceBackend {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stopRequested = false

    @Volatile
    private var cancelled = false

    private var job: Job? = null

    override fun start(token: Long, languageTag: String, events: VoiceBackend.Events) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) { capture(token, events) }
    }

    override fun stop() {
        stopRequested = true
    }

    override fun cancel() {
        cancelled = true
        job?.cancel()
        job = null
    }

    private fun CoroutineScope.capture(token: Long, events: VoiceBackend.Events) {
        val stats = CaptureStats(AudioCapture.SAMPLE_RATE)
        val startedAt = SystemClock.elapsedRealtime()
        var failure: String? = null
        var capture: AudioCapture? = null
        try {
            capture = AudioCapture.open().apply { start() }
            post { events.onStarted(token) }
            val buffer = ShortArray(AudioCapture.SAMPLE_RATE / READS_PER_SECOND)
            var nextSilenceCheck = 0L
            while (isActive && !stopRequested) {
                val count = capture.read(buffer)
                if (count < 0) {
                    failure = "AudioRecord.read=$count"
                    break
                }
                stats.accept(buffer, count)
                if (stats.samples >= nextSilenceCheck) {
                    stats.recordSilenced(capture.isClientSilenced())
                    nextSilenceCheck = stats.samples + AudioCapture.SAMPLE_RATE / 4
                }
                if (SystemClock.elapsedRealtime() - startedAt > MAX_SESSION_MS) break
            }
        } catch (e: Exception) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { capture?.release() }.onFailure {
                failure = failure ?: "release: ${it.javaClass.simpleName}: ${it.message}"
            }
        }
        val outcome = when {
            cancelled -> "cancel"
            failure != null -> "error"
            else -> "stop"
        }
        Timber.i(
            "Voice capture probe released: outcome=$outcome " +
                    "wall=${SystemClock.elapsedRealtime() - startedAt}ms " +
                    "sampleRate=${capture?.sampleRate} api=${Build.VERSION.SDK_INT} " +
                    stats.summary() + (failure?.let { " failure=$it" } ?: "")
        )
        when (outcome) {
            "stop" -> post {
                context.toast("Capture probe: " + stats.summary(), Toast.LENGTH_LONG)
                events.onFinal(token, null)
            }
            "error" -> post { events.onError(token, VoiceError.Capture(failure!!)) }
        }
    }

    /** Events for a cancelled session are dropped. */
    private fun post(block: () -> Unit) {
        mainHandler.post { if (!cancelled) block() }
    }

    companion object {
        private const val READS_PER_SECOND = 50
        private const val MAX_SESSION_MS = 60_000L
    }
}
