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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Debug-only test credentials, injected at build time from the local environment. */
internal class DoubaoCredentials(
    val apiKey: String,
    val appKey: String,
    val accessKey: String,
    val resourceId: String
) {
    val isComplete: Boolean
        get() = apiKey.isNotEmpty() || (appKey.isNotEmpty() && accessKey.isNotEmpty())

    override fun toString() = "DoubaoCredentials(redacted)"

    companion object {
        // Doubao streaming ASR model 2.0, hourly billing
        private const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"

        fun fromBuildConfig() = DoubaoCredentials(
            BuildConfig.DOUBAO_ASR_API_KEY,
            BuildConfig.DOUBAO_ASR_APP_KEY,
            BuildConfig.DOUBAO_ASR_ACCESS_KEY,
            BuildConfig.DOUBAO_ASR_RESOURCE_ID.ifEmpty { DEFAULT_RESOURCE_ID }
        )
    }
}

/**
 * Direct cloud ASR (Phase 4B.3a): streams Fcitx-owned [AudioCapture] PCM to Doubao Seed-ASR
 * over a WebSocket. Provisional results are only logged; the final result is the transcript.
 */
internal class DoubaoAsrBackend(
    private val scope: CoroutineScope,
    private val credentials: DoubaoCredentials
) : VoiceBackend {

    private val mainHandler = Handler(Looper.getMainLooper())

    // main thread only
    private val tracker = DoubaoResultTracker()
    private var events: VoiceBackend.Events? = null
    private var token = 0L
    private var socket: WebSocket? = null
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    /** Set once the session is finished or cancelled: capture stops and events are dropped. */
    @Volatile
    private var closed = false

    private val finalTimeout = Runnable {
        finish(tracker.fail("no final result within ${FINAL_TIMEOUT_MS}ms"))
    }

    override fun start(token: Long, languageTag: String, events: VoiceBackend.Events) {
        this.token = token
        this.events = events
        if (!credentials.isComplete) {
            finish(tracker.fail("Doubao credentials are not configured in this build"))
            return
        }
        // requests are queued by OkHttp until the handshake completes, in order
        val ws = client.newWebSocket(request(), listener)
        socket = ws
        ws.send(DoubaoAsrProtocol.fullClientRequest(1).toByteString())
        job = scope.launch(Dispatchers.IO) { stream(ws, events) }
    }

    override fun stop() {
        stopRequested = true
    }

    override fun cancel() {
        tracker.cancel()
        closed = true
        mainHandler.removeCallbacks(finalTimeout)
        job?.cancel()
        job = null
        socket?.cancel()
        socket = null
    }

    private fun CoroutineScope.stream(ws: WebSocket, events: VoiceBackend.Events) {
        val stats = CaptureStats(AudioCapture.SAMPLE_RATE)
        val startedAt = SystemClock.elapsedRealtime()
        val packet = ByteArrayOutputStream(PACKET_BYTES)
        // one full packet is held back, so the last (negative sequence) packet always carries
        // real audio as in the official demos, instead of an empty payload
        var pending: ByteArray? = null
        var sequence = 2
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
                // pcm_s16le
                for (i in 0 until count) {
                    val v = buffer[i].toInt()
                    packet.write(v)
                    packet.write(v shr 8)
                }
                if (packet.size() >= PACKET_BYTES) {
                    pending?.let {
                        ws.send(DoubaoAsrProtocol.audioRequest(sequence++, it, false).toByteString())
                    }
                    pending = packet.toByteArray()
                    packet.reset()
                }
            }
        } catch (e: Exception) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { capture?.release() }.onFailure {
                failure = failure ?: "release: ${it.javaClass.simpleName}: ${it.message}"
            }
        }
        Timber.i("Doubao ASR capture released: ${stats.summary()}")
        if (closed) return
        if (failure != null) {
            post { finish(tracker.fail("capture: $failure")) }
            return
        }
        // end of input only: tail results keep arriving until the server's last package
        val tail = packet.toByteArray()
        val last = if (tail.isNotEmpty()) {
            pending?.let {
                ws.send(DoubaoAsrProtocol.audioRequest(sequence++, it, false).toByteString())
            }
            tail
        } else {
            pending ?: tail
        }
        ws.send(DoubaoAsrProtocol.audioRequest(sequence, last, true).toByteString())
        post { if (!tracker.finished) mainHandler.postDelayed(finalTimeout, FINAL_TIMEOUT_MS) }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.i("Doubao ASR connected, X-Tt-Logid=${response.header("X-Tt-Logid")}")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val parsed = runCatching { DoubaoAsrProtocol.parse(bytes.toByteArray()) }
            post {
                parsed.fold(
                    onSuccess = { handle(it) },
                    onFailure = { finish(tracker.fail("bad response: ${it.message}")) }
                )
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            post { finish(tracker.fail("connection closed before final result: $code $reason")) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.let { " (HTTP ${it.code}, X-Tt-Logid=${it.header("X-Tt-Logid")})" }
            post { finish(tracker.fail("${t.javaClass.simpleName}: ${t.message}${status.orEmpty()}")) }
        }
    }

    private fun handle(response: DoubaoAsrProtocol.Response) {
        when (val outcome = tracker.accept(response)) {
            // observe only: neither provisional nor stable text reaches preedit or the editor
            is DoubaoResultTracker.Outcome.Provisional -> Timber.d(
                "Doubao ASR provisional #${tracker.provisionalCount}: ${outcome.text}"
            )
            is DoubaoResultTracker.Outcome.Stable -> Timber.d(
                "Doubao ASR stable (${outcome.definiteUtterances} definite): ${outcome.stableText}"
            )
            else -> finish(outcome)
        }
    }

    private fun finish(outcome: DoubaoResultTracker.Outcome) {
        val events = events ?: return
        when (outcome) {
            is DoubaoResultTracker.Outcome.Final -> {
                Timber.i(
                    "Doubao ASR final: ${outcome.text?.length ?: 0} chars " +
                            "after ${tracker.provisionalCount} provisional results"
                )
                release(graceful = true)
                events.onFinal(token, outcome.text)
            }
            is DoubaoResultTracker.Outcome.Failed -> {
                Timber.w("Doubao ASR failed: ${outcome.detail}")
                release(graceful = false)
                events.onError(token, VoiceError.Service(outcome.detail))
            }
            else -> Unit
        }
    }

    private fun release(graceful: Boolean) {
        closed = true
        mainHandler.removeCallbacks(finalTimeout)
        job?.cancel()
        job = null
        socket?.let { if (graceful) it.close(NORMAL_CLOSURE, null) else it.cancel() }
        socket = null
    }

    private fun post(block: () -> Unit) {
        mainHandler.post { if (!closed) block() }
    }

    private fun request(): Request {
        val requestId = UUID.randomUUID().toString()
        return Request.Builder()
            .url(DoubaoAsrProtocol.URL)
            .apply {
                if (credentials.apiKey.isNotEmpty()) {
                    header("X-Api-Key", credentials.apiKey)
                } else {
                    header("X-Api-App-Key", credentials.appKey)
                    header("X-Api-Access-Key", credentials.accessKey)
                }
            }
            .header("X-Api-Resource-Id", credentials.resourceId)
            .header("X-Api-Request-Id", requestId)
            .header("X-Api-Connect-Id", requestId)
            .build()
    }

    companion object {
        // 200 ms of 16 kHz mono pcm_s16le, the packet size the API recommends
        private const val PACKET_BYTES = AudioCapture.SAMPLE_RATE * 2 / 5
        private const val FINAL_TIMEOUT_MS = 8_000L
        private const val MAX_SESSION_MS = 60_000L
        private const val NORMAL_CLOSURE = 1000

        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
