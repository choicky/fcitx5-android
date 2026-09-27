/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Binary WebSocket framing of Volcengine's Doubao streaming ASR (大模型流式语音识别 API, v3 sauc):
 * a 4-byte header, a big-endian sequence number, a big-endian payload size and a gzip payload.
 */
internal object DoubaoAsrProtocol {

    /** Optimized bidirectional streaming; the only endpoint that supports `enable_nonstream`. */
    const val URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"

    // version 1, header size 1 x 4 bytes
    private const val VERSION_AND_HEADER_SIZE = 0x11

    private const val TYPE_FULL_CLIENT_REQUEST = 0b0001
    private const val TYPE_AUDIO_ONLY_REQUEST = 0b0010
    private const val TYPE_FULL_SERVER_RESPONSE = 0b1001
    private const val TYPE_SERVER_ERROR = 0b1111

    private const val FLAG_SEQUENCE = 0b0001
    private const val FLAG_LAST = 0b0010

    private const val SERIALIZATION_NONE = 0b0000
    private const val SERIALIZATION_JSON = 0b0001
    private const val COMPRESSION_GZIP = 0b0001

    sealed interface Response {
        /** [last] marks the final response, sent after the client's last audio packet. */
        data class Result(
            val sequence: Int?,
            val last: Boolean,
            val text: String?,
            val definiteUtterances: Int
        ) : Response

        data class Error(val code: Int, val message: String) : Response
    }

    fun fullClientRequest(sequence: Int): ByteArray = frame(
        TYPE_FULL_CLIENT_REQUEST, FLAG_SEQUENCE, SERIALIZATION_JSON, sequence,
        requestJson().encodeToByteArray()
    )

    /** The last packet carries a negated sequence number. */
    fun audioRequest(sequence: Int, pcm: ByteArray, last: Boolean): ByteArray = frame(
        TYPE_AUDIO_ONLY_REQUEST,
        if (last) FLAG_SEQUENCE or FLAG_LAST else FLAG_SEQUENCE,
        SERIALIZATION_NONE,
        if (last) -sequence else sequence,
        pcm
    )

    fun requestJson(): String = buildJsonObject {
        // a fixed, non-identifying uid: the API only uses it to filter server logs
        putJsonObject("user") { put("uid", "fcitx5-android") }
        putJsonObject("audio") {
            put("format", "pcm")
            put("codec", "raw")
            put("rate", AudioCapture.SAMPLE_RATE)
            put("bits", 16)
            put("channel", 1)
        }
        putJsonObject("request") {
            put("model_name", "bigmodel")
            // two-pass: realtime results, then a non-streaming re-recognition marked definite
            put("enable_nonstream", true)
            put("enable_itn", true)
            put("enable_punc", true)
            put("show_utterances", true)
            put("result_type", "full")
        }
    }.toString()

    fun parse(frame: ByteArray): Response {
        require(frame.size >= 4) { "frame too short: ${frame.size} bytes" }
        val type = (frame[1].toInt() shr 4) and 0x0f
        val flags = frame[1].toInt() and 0x0f
        val serialization = (frame[2].toInt() shr 4) and 0x0f
        val compression = frame[2].toInt() and 0x0f
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        buffer.position((frame[0].toInt() and 0x0f) * 4)
        return when (type) {
            TYPE_FULL_SERVER_RESPONSE -> {
                val sequence = if (flags and FLAG_SEQUENCE != 0) buffer.int else null
                val payload = payload(buffer, compression)
                val json = if (serialization == SERIALIZATION_JSON && payload.isNotEmpty()) {
                    Json.parseToJsonElement(payload.decodeToString()) as? JsonObject
                } else null
                val result = when (val r = json?.get("result")) {
                    is JsonObject -> r
                    is JsonArray -> r.firstOrNull() as? JsonObject
                    else -> null
                }
                val definite = (result?.get("utterances") as? JsonArray)?.count {
                    ((it as? JsonObject)?.get("definite"))?.jsonPrimitive?.booleanOrNull == true
                } ?: 0
                Response.Result(
                    sequence = sequence,
                    last = flags and FLAG_LAST != 0,
                    text = result?.get("text")?.jsonPrimitive?.contentOrNull,
                    definiteUtterances = definite
                )
            }
            TYPE_SERVER_ERROR -> {
                val code = buffer.int
                Response.Error(code, payload(buffer, compression).decodeToString())
            }
            else -> error("unexpected message type $type")
        }
    }

    private fun frame(
        type: Int,
        flags: Int,
        serialization: Int,
        sequence: Int,
        payload: ByteArray
    ): ByteArray {
        val body = gzip(payload)
        return ByteBuffer.allocate(12 + body.size).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION_AND_HEADER_SIZE.toByte())
            .put(((type shl 4) or flags).toByte())
            .put(((serialization shl 4) or COMPRESSION_GZIP).toByte())
            .put(0.toByte())
            .putInt(sequence)
            .putInt(body.size)
            .put(body)
            .array()
    }

    /** Reads a size-prefixed payload at the buffer position. */
    private fun payload(buffer: ByteBuffer, compression: Int): ByteArray {
        val size = buffer.int
        require(size in 0..buffer.remaining()) { "payload size $size exceeds ${buffer.remaining()}" }
        val bytes = ByteArray(size).also { buffer.get(it) }
        return if (compression == COMPRESSION_GZIP && size > 0) gunzip(bytes) else bytes
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }
            .toByteArray()

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }
}

/**
 * Decides what each Doubao response means for the session. Only the last response is final;
 * everything before it is provisional and must never reach the editor.
 */
internal class DoubaoResultTracker {

    sealed interface Outcome {
        data class Provisional(val text: String, val definiteUtterances: Int) : Outcome
        data class Final(val text: String?) : Outcome
        data class Failed(val detail: String) : Outcome
        data object Ignored : Outcome
    }

    var finished = false
        private set

    var provisionalCount = 0
        private set

    fun accept(response: DoubaoAsrProtocol.Response): Outcome {
        if (finished) return Outcome.Ignored
        return when (response) {
            is DoubaoAsrProtocol.Response.Error -> {
                finished = true
                Outcome.Failed("server error ${response.code}: ${response.message}")
            }
            is DoubaoAsrProtocol.Response.Result -> if (response.last) {
                finished = true
                Outcome.Final(response.text)
            } else {
                provisionalCount++
                Outcome.Provisional(response.text.orEmpty(), response.definiteUtterances)
            }
        }
    }

    fun fail(detail: String): Outcome {
        if (finished) return Outcome.Ignored
        finished = true
        return Outcome.Failed(detail)
    }

    fun cancel() {
        finished = true
    }
}
