/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Local ASR (Phase 4B.3b-1, debug-only PoC). Records from the Fcitx-owned [AudioCapture] and
 * feeds a sherpa-onnx recognizer from [LocalAsrRecognizerCache]: a streaming model decodes while
 * audio arrives, a non-streaming one decodes the buffered utterance on stop. Partial text is only
 * logged, never shown in the editor. The final text goes through [VoiceBackend.Events.onFinal].
 */
internal class LocalAsrBackend(
    private val scope: CoroutineScope,
    private val model: LocalAsrModel,
    private val threads: Int,
    private val modelDir: File?,
    private val cache: LocalAsrRecognizerCache
) : VoiceBackend {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stopRequested = false

    @Volatile
    private var stopRequestedAt = 0L

    @Volatile
    private var cancelled = false

    private var job: Job? = null

    override fun start(token: Long, languageTag: String, events: VoiceBackend.Events) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) { recognize(token, events) }
    }

    override fun stop() {
        stopRequestedAt = SystemClock.elapsedRealtime()
        stopRequested = true
    }

    override fun cancel() {
        cancelled = true
        job?.cancel()
        job = null
    }

    /** Hands the loaded recognizer to the capture loop, or back to the cache if abandoned. */
    private class LeaseHolder {
        private var lease: LocalAsrRecognizerCache.Lease? = null
        private var failure: Throwable? = null
        private var abandoned = false

        fun load(block: () -> LocalAsrRecognizerCache.Lease) {
            val result = runCatching(block)
            synchronized(this) {
                result.onSuccess { if (abandoned) it.close() else lease = it }
                result.onFailure { failure = it }
            }
        }

        @Synchronized
        fun ready() = lease

        @Synchronized
        fun failure() = failure

        @Synchronized
        fun abandon() {
            abandoned = true
            lease?.close()
            lease = null
        }
    }

    private suspend fun recognize(token: Long, events: VoiceBackend.Events) {
        val dir = modelDir
        val missing = dir?.let { model.missingFiles(it) } ?: listOf("<external storage unavailable>")
        if (missing.isNotEmpty()) {
            fail(token, events, "${model.name} model files missing in $dir: ${missing.joinToString()}")
            return
        }
        val holder = LeaseHolder()
        val current = SessionRef()
        try {
            coroutineScope {
                // capture starts right away, so nothing said while the model loads is lost
                val loader = launch(Dispatchers.IO) {
                    holder.load { cache.acquire(model, threads, SystemClock::elapsedRealtime) }
                }
                captureAndRecognize(token, events, holder, loader, current)
            }
        } finally {
            // the stream goes before its recognizer lease
            current.session?.release()
            holder.abandon()
        }
    }

    private class SessionRef {
        var session: LocalAsrSession? = null
    }

    private suspend fun CoroutineScope.captureAndRecognize(
        token: Long,
        events: VoiceBackend.Events,
        holder: LeaseHolder,
        loader: Job,
        current: SessionRef
    ) {
        val stats = CaptureStats(AudioCapture.SAMPLE_RATE)
        val startedAt = SystemClock.elapsedRealtime()
        val pending = mutableListOf<FloatArray>()
        var firstAudioAt = 0L
        var firstPartialMillis: Long? = null
        var partials = 0
        var lastPartial = ""
        var decodeNanos = 0L

        fun feed(target: LocalAsrSession, samples: FloatArray) {
            val t0 = System.nanoTime()
            val text = target.accept(samples)
            decodeNanos += System.nanoTime() - t0
            if (text.isNullOrEmpty() || text == lastPartial) return
            lastPartial = text
            partials++
            if (firstPartialMillis == null) firstPartialMillis = SystemClock.elapsedRealtime() - firstAudioAt
            // observe only: local partial text never reaches preedit
            Timber.d("Local ASR partial #$partials: $text")
        }

        fun openSession(): LocalAsrSession? {
            current.session?.let { return it }
            val lease = holder.ready() ?: return null
            return lease.recognizer.newSession().also { created ->
                current.session = created
                // the model is loaded: failures from here on are no longer "early" (D035)
                post { events.onSessionEstablished(token) }
                pending.forEach { feed(created, it) }
                pending.clear()
            }
        }

        var failure: String? = null
        var capture: AudioCapture? = null
        try {
            val opened = AudioCapture.open()
            capture = opened
            opened.start()
            post { events.onStarted(token) }
            failure = opened.pump(stats, keepGoing = {
                // a failed model load ends capture right away; it is reported below
                isActive && !cancelled && !stopRequested && holder.failure() == null &&
                        SystemClock.elapsedRealtime() - startedAt <= MAX_SESSION_MS
            }, onLevel = { level -> post { events.onAudioLevel(token, level) } }) { buffer, count ->
                val samples = pcm16ToFloat(buffer, count)
                if (firstAudioAt == 0L) firstAudioAt = SystemClock.elapsedRealtime()
                val target = openSession()
                if (target == null) pending += samples else feed(target, samples)
            }
        } catch (e: Exception) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { capture?.release() }.onFailure {
                failure = failure ?: "release: ${it.javaClass.simpleName}: ${it.message}"
            }
        }
        if (cancelled || !isActive) {
            Timber.i("Local ASR ${model.name} cancelled: ${stats.summary()}")
            return
        }
        failure?.let {
            fail(token, events, "capture: $it")
            return
        }
        // end of input: wait for the model if it is still loading, then finalize
        loader.join()
        holder.failure()?.let {
            fail(token, events, "${model.name} load failed: ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        val text = try {
            val target = openSession() ?: error("recognizer unavailable")
            val t0 = System.nanoTime()
            target.finish().also { decodeNanos += System.nanoTime() - t0 }
        } catch (e: Exception) {
            fail(token, events, "${model.name} decode failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val finalAt = SystemClock.elapsedRealtime()
        val metrics = LocalAsrMetrics(
            model = model,
            threads = threads,
            loadMillis = holder.ready()?.loadMillis,
            audioMillis = stats.audioMillis,
            firstPartialMillis = firstPartialMillis,
            partials = partials,
            decodeMillis = decodeNanos / 1_000_000,
            stopToFinalMillis = stopRequestedAt.takeIf { it > 0 }?.let { finalAt - it },
            finalChars = text.length,
            // measured after stop-to-final, so it does not skew the latency above
            processPssMb = runCatching { Debug.getPss() / 1024 }.getOrNull()
        )
        Timber.i("Local ASR result: ${metrics.summary()} ${stats.summary()}")
        post { events.onFinal(token, text) }
    }

    private fun fail(token: Long, events: VoiceBackend.Events, detail: String) {
        Timber.w("Local ASR failed: $detail")
        post { events.onError(token, VoiceError.Service(detail)) }
    }

    /** Events for a cancelled session are dropped. */
    private fun post(block: () -> Unit) {
        mainHandler.post { if (!cancelled) block() }
    }

    companion object {
        private const val MAX_SESSION_MS = 60_000L

        fun modelDir(externalFilesDir: File?, model: LocalAsrModel): File? =
            externalFilesDir?.resolve(LocalAsrModel.ROOT_DIR)?.resolve(model.dirName)
    }
}
