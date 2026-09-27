/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import java.io.File
import java.util.Locale

/**
 * Phase 4B.3b-1 Local ASR candidates. Models are never bundled or downloaded: the tester pushes
 * them to `<external files dir>/local-asr/<dirName>/`. Neither is a selected default.
 */
internal enum class LocalAsrModel(
    val dirName: String,
    val streaming: Boolean,
    val requiredFiles: List<String>
) {
    /** A: sherpa-onnx OnlineRecognizer, true streaming. Research only: weights license unresolved. */
    ZipformerZh(
        "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
        streaming = true,
        listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
    ),

    /**
     * B: sherpa-onnx OfflineRecognizer with FunASR Nano, whole-utterance decode on stop. The
     * tokenizer files are the ones sherpa-onnx 1.13.8 OfflineFunASRNanoModelConfig::Validate()
     * requires in the tokenizer directory.
     */
    FunAsrNano(
        "sherpa-onnx-funasr-nano-int8-2025-12-30",
        streaming = false,
        listOf(
            "encoder_adaptor.int8.onnx",
            "llm.int8.onnx",
            "embedding.int8.onnx",
            "Qwen3-0.6B/vocab.json",
            "Qwen3-0.6B/merges.txt",
            "Qwen3-0.6B/tokenizer.json"
        )
    );

    fun missingFiles(modelDir: File): List<String> =
        requiredFiles.filterNot { modelDir.resolve(it).isFile }

    companion object {
        const val ROOT_DIR = "local-asr"
    }
}

/** One recognition over one voice session; used from a single background thread at a time. */
internal interface LocalAsrSession {
    /** Feed 16 kHz mono samples in [-1, 1]. Returns the current text if the engine streams. */
    fun accept(samples: FloatArray): String?

    /** End of input: finalize (may decode for a while) and return the final text. */
    fun finish(): String

    /** Drop the session without finishing. Idempotent. */
    fun release()
}

internal interface LocalAsrRecognizer {
    fun newSession(): LocalAsrSession
    fun release()
}

/** The native streaming stream calls a streaming session needs (sherpa OnlineStream). */
internal interface StreamingDecoder {
    fun acceptWaveform(samples: FloatArray)
    /** Decode everything that is ready; returns the current text. */
    fun decodeReady(): String
    fun inputFinished()
    fun release()
}

/** Streaming: decodes while audio arrives; stop flushes the tail. */
internal class StreamingLocalAsrSession(private val decoder: StreamingDecoder) : LocalAsrSession {
    private var released = false

    override fun accept(samples: FloatArray): String {
        decoder.acceptWaveform(samples)
        return decoder.decodeReady()
    }

    override fun finish(): String {
        decoder.inputFinished()
        return decoder.decodeReady()
    }

    override fun release() {
        if (released) return
        released = true
        decoder.release()
    }
}

/** Non-streaming: buffers the utterance and decodes once on stop; never decodes if dropped. */
internal class BufferingLocalAsrSession(
    private val decode: (FloatArray) -> String
) : LocalAsrSession {
    private val chunks = mutableListOf<FloatArray>()
    private var size = 0

    val bufferedSamples: Int
        get() = size

    override fun accept(samples: FloatArray): String? {
        chunks += samples
        size += samples.size
        return null
    }

    override fun finish(): String {
        val all = FloatArray(size)
        var offset = 0
        chunks.forEach {
            it.copyInto(all, offset)
            offset += it.size
        }
        chunks.clear()
        return decode(all)
    }

    override fun release() {
        chunks.clear()
        size = 0
    }
}

internal fun pcm16ToFloat(buffer: ShortArray, count: Int) =
    FloatArray(count) { buffer[it] / 32768f }

/**
 * Keeps one loaded recognizer (model + numThreads) for later sessions; loading FunASR Nano means
 * about 1 GB of weights. Leases are reference counted, so native resources are released only
 * after the last session using an evicted recognizer is done. Thread-safe.
 */
internal class LocalAsrRecognizerCache(
    private val loader: (LocalAsrModel, Int) -> LocalAsrRecognizer
) {
    inner class Entry internal constructor(
        val model: LocalAsrModel,
        val threads: Int,
        val recognizer: LocalAsrRecognizer
    ) {
        var leases = 0
        var evicted = false
    }

    inner class Lease internal constructor(private val entry: Entry, val loadMillis: Long?) {
        val recognizer: LocalAsrRecognizer
            get() = entry.recognizer
        private var closed = false

        fun close() = synchronized(this@LocalAsrRecognizerCache) {
            if (closed) return
            closed = true
            entry.leases--
            releaseIfUnused(entry)
        }
    }

    private var current: Entry? = null

    /** Blocks while a model loads; call it off the main thread. */
    @Synchronized
    fun acquire(model: LocalAsrModel, threads: Int, clock: () -> Long): Lease {
        current?.let {
            if (it.model == model && it.threads == threads) {
                it.leases++
                return Lease(it, null)
            }
            evict(it)
        }
        val startedAt = clock()
        val entry = Entry(model, threads, loader(model, threads))
        entry.leases++
        current = entry
        return Lease(entry, clock() - startedAt)
    }

    @Synchronized
    fun clear() {
        current?.let { evict(it) }
    }

    private fun evict(entry: Entry) {
        entry.evicted = true
        if (current === entry) current = null
        releaseIfUnused(entry)
    }

    private fun releaseIfUnused(entry: Entry) {
        if (entry.evicted && entry.leases == 0) entry.recognizer.release()
    }
}

/** Per-session numbers for comparing A and B on devices; logged once per session. */
internal data class LocalAsrMetrics(
    val model: LocalAsrModel,
    val threads: Int,
    val loadMillis: Long?,
    val audioMillis: Long,
    val firstPartialMillis: Long?,
    val partials: Int,
    val decodeMillis: Long,
    val stopToFinalMillis: Long?,
    val finalChars: Int,
    /** Process PSS right after the final result, i.e. with the model loaded; null if unknown. */
    val processPssMb: Long? = null
) {
    val rtf: Double
        get() = if (audioMillis > 0) decodeMillis.toDouble() / audioMillis else 0.0

    fun summary() = "model=${model.name} threads=$threads " +
            "load=${loadMillis?.let { "${it}ms" } ?: "cached"} audio=${audioMillis}ms " +
            "firstPartial=${firstPartialMillis?.let { "${it}ms" } ?: "-"} partials=$partials " +
            "decode=${decodeMillis}ms rtf=${"%.3f".format(Locale.ROOT, rtf)} " +
            "stopToFinal=${stopToFinalMillis?.let { "${it}ms" } ?: "-"} finalChars=$finalChars " +
            "pss=${processPssMb?.let { "${it}MB" } ?: "-"}"
}
