/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * A streaming network service (Managed Cloud or Self-hosted): Fcitx-owned [AudioCapture] PCM
 * goes to the provider through its own [NetworkAsrClient]. Partial text is only logged (as for
 * Doubao, D028); the final text is the transcript. [label] names the service in logs only.
 */
internal class NetworkAsrBackend(
    private val scope: CoroutineScope,
    private val label: String,
    /** How long to wait for the final result after the end of input. */
    private val finalTimeoutMs: Long = DEFAULT_FINAL_TIMEOUT_MS,
    /** Credential values that must never appear in a logged, shown or stored detail. */
    private val secrets: Collection<String> = emptyList(),
    private val newClient: (NetworkAsrClient.Listener) -> NetworkAsrClient
) : VoiceBackend {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stopRequested = false

    @Volatile
    private var closed = false

    // main thread only
    private var events: VoiceBackend.Events? = null
    private var token = 0L
    private var client: NetworkAsrClient? = null
    private var job: Job? = null
    private var partials = 0

    private val finalTimeout = Runnable {
        fail("no final result within ${finalTimeoutMs}ms")
    }

    override fun start(token: Long, languageTag: String, events: VoiceBackend.Events) {
        this.token = token
        this.events = events
        val session = newClient(object : NetworkAsrClient.Listener {
            // the service accepted the session: failures after this are no longer early
            override fun onEstablished() = post { events.onSessionEstablished(token) }

            override fun onPartial(text: String) = post {
                partials++
                Timber.d("$label partial #$partials (${text.length} chars)")
            }

            override fun onFinal(text: String) = post {
                Timber.i("$label final: ${text.length} chars after $partials partials")
                release()
                events.onFinal(token, text)
            }

            override fun onFailure(detail: String) = post { fail(detail) }
        })
        client = session
        // a malformed endpoint is a failure of this session, not a crash
        runCatching { session.connect() }.onFailure {
            post { fail("${it.javaClass.simpleName}: ${it.message}") }
            return
        }
        job = scope.launch(Dispatchers.IO) { stream(session, token, events) }
    }

    override fun stop() {
        stopRequested = true
    }

    override fun cancel() {
        closed = true
        release()
    }

    private fun CoroutineScope.stream(
        session: NetworkAsrClient,
        token: Long,
        events: VoiceBackend.Events
    ) {
        val stats = CaptureStats(AudioCapture.SAMPLE_RATE)
        val startedAt = SystemClock.elapsedRealtime()
        var failure: String? = null
        var capture: AudioCapture? = null
        try {
            val opened = AudioCapture.open()
            capture = opened
            opened.start()
            post { events.onStarted(token) }
            failure = opened.pump(stats, keepGoing = {
                isActive && !closed && !stopRequested &&
                        SystemClock.elapsedRealtime() - startedAt <= MAX_SESSION_MS
            }, onLevel = { level -> post { events.onAudioLevel(token, level) } }) { buffer, count ->
                session.send(buffer, count)
            }
        } catch (e: Exception) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { capture?.release() }.onFailure {
                failure = failure ?: "release: ${it.javaClass.simpleName}: ${it.message}"
            }
        }
        Timber.i("$label capture released: ${stats.summary()}")
        if (closed) return
        failure?.let {
            // the microphone failed, not the service: no D035 fallback for this
            post { fail(it, capture = true) }
            return
        }
        session.finishInput()
        post { if (!closed) mainHandler.postDelayed(finalTimeout, finalTimeoutMs) }
    }

    private fun fail(detail: String, capture: Boolean = false) {
        val events = events ?: return
        val safe = ErrorRedaction.redact(detail, secrets)
        Timber.w("$label ${if (capture) "capture failed" else "failed"}: $safe")
        release()
        events.onError(token, if (capture) VoiceError.Capture(safe) else VoiceError.Service(safe))
    }

    private fun release() {
        closed = true
        mainHandler.removeCallbacks(finalTimeout)
        job?.cancel()
        job = null
        client?.cancel()
        client = null
    }

    private fun post(block: () -> Unit) {
        mainHandler.post { if (!closed) block() }
    }

    companion object {
        const val DEFAULT_FINAL_TIMEOUT_MS = 8_000L

        /** A self-hosted CPU server may need longer for its offline pass on long speech. */
        const val SELF_HOSTED_FINAL_TIMEOUT_MS = 20_000L
        private const val MAX_SESSION_MS = 60_000L

        /** Shared by the network clients. */
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        }
    }
}
