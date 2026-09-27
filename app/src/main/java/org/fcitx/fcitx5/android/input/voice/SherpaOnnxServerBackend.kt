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
 * Self-hosted sherpa-onnx streaming server: Fcitx-owned [AudioCapture] PCM goes to the user's
 * server through [SherpaOnnxServerClient]. Partial text is only logged (as for Doubao); the
 * final text is the transcript.
 */
internal class SherpaOnnxServerBackend(
    private val scope: CoroutineScope,
    private val instance: SelfHostedInstance,
    private val bearerToken: String?
) : VoiceBackend {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stopRequested = false

    @Volatile
    private var closed = false

    // main thread only
    private var events: VoiceBackend.Events? = null
    private var token = 0L
    private var client: SherpaOnnxServerClient? = null
    private var job: Job? = null
    private var partials = 0

    private val finalTimeout = Runnable {
        fail("no final result within ${FINAL_TIMEOUT_MS}ms")
    }

    override fun start(token: Long, languageTag: String, events: VoiceBackend.Events) {
        this.token = token
        this.events = events
        val session = SherpaOnnxServerClient(http, instance.url, bearerToken, object :
            SherpaOnnxServerClient.Listener {
            // the server accepted the WebSocket: failures after this are no longer early
            override fun onOpen() = post { events.onSessionEstablished(token) }

            override fun onPartial(text: String) = post {
                partials++
                Timber.d("Self-hosted ASR partial #$partials (${text.length} chars)")
            }

            override fun onFinal(text: String) = post {
                Timber.i("Self-hosted ASR final: ${text.length} chars after $partials partials")
                release()
                events.onFinal(token, text)
            }

            override fun onFailure(detail: String) = post { fail(detail) }
        })
        client = session
        session.connect()
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
        session: SherpaOnnxServerClient,
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
        Timber.i("Self-hosted ASR capture released: ${stats.summary()}")
        if (closed) return
        failure?.let {
            post { fail("capture: $it") }
            return
        }
        session.finishInput()
        post { if (!closed) mainHandler.postDelayed(finalTimeout, FINAL_TIMEOUT_MS) }
    }

    private fun fail(detail: String) {
        val events = events ?: return
        Timber.w("Self-hosted ASR failed: $detail")
        release()
        events.onError(token, VoiceError.Service(detail))
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
        private const val FINAL_TIMEOUT_MS = 8_000L
        private const val MAX_SESSION_MS = 60_000L

        private val http by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        }
    }
}
