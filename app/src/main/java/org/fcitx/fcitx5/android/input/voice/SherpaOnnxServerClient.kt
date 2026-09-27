/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sherpa-onnx streaming WebSocket server protocol, as implemented by upstream v1.13.8
 * `python-api-examples/streaming_server.py` and `sherpa-onnx-online-websocket-server`:
 * the client sends little-endian float32 samples at the server's sample rate (16 kHz) and the
 * text `Done` at the end; the server replies with JSON `{"text", "segment"}` where `text` is the
 * whole current hypothesis of that segment (it may be revised). The C++ server adds
 * `is_final`/`is_eof`; the Python server sends its last result and then closes.
 */
internal object SherpaOnnxServerProtocol {
    const val DONE = "Done"

    data class Result(val text: String, val segment: Int, val isEof: Boolean)

    fun audioFrame(pcm: ShortArray, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putFloat(pcm[i] / 32768f)
        return buffer.array()
    }

    fun parse(message: String): Result? = runCatching {
        val json = Json.parseToJsonElement(message) as JsonObject
        Result(
            text = json["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            segment = json["segment"]?.jsonPrimitive?.intOrNull ?: 0,
            isEof = json["is_eof"]?.jsonPrimitive?.booleanOrNull == true
        )
    }.getOrNull()

    /** Joins segments in order; a later message for a segment replaces its earlier text. */
    class Transcript {
        private val segments = sortedMapOf<Int, String>()

        fun accept(result: Result): String {
            segments[result.segment] = result.text
            return text
        }

        val text: String
            get() = joinSegments(segments.values)
    }

    /** Chinese needs no separator; Latin words split across segments keep one space. */
    fun joinSegments(parts: Collection<String>): String = buildString {
        parts.map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            if (isNotEmpty() && last().isLatinOrDigit() && part.first().isLatinOrDigit()) append(' ')
            append(part)
        }
    }

    private fun Char.isLatinOrDigit() =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}

/**
 * One session against a sherpa-onnx streaming server. Callbacks run on OkHttp threads.
 * [onOpen] means the server accepted the WebSocket, which is the only session acknowledgement
 * the protocol has; reverse-proxy authentication failures arrive before it.
 */
internal class SherpaOnnxServerClient(
    private val client: OkHttpClient,
    private val url: String,
    private val bearerToken: String?,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onFailure(detail: String)
    }

    private val transcript = SherpaOnnxServerProtocol.Transcript()
    private var socket: WebSocket? = null

    @Volatile
    private var finishing = false

    @Volatile
    private var done = false

    fun connect() {
        val request = Request.Builder().url(url).apply {
            bearerToken?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
        }.build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                val result = SherpaOnnxServerProtocol.parse(text) ?: return
                val current = transcript.accept(result)
                if (result.isEof) finish(current) else listener.onPartial(current)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                // the Python server closes after its last result
                if (finishing) finish(transcript.text)
                else fail("server closed the session: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.let { " (HTTP ${it.code})" }.orEmpty()
                fail("${t.javaClass.simpleName}: ${t.message}$status")
            }
        })
    }

    fun send(pcm: ShortArray, count: Int) {
        socket?.send(SherpaOnnxServerProtocol.audioFrame(pcm, count).toByteString())
    }

    /** End of input; the final result follows. */
    fun finishInput() {
        finishing = true
        socket?.send(SherpaOnnxServerProtocol.DONE)
    }

    fun cancel() {
        done = true
        socket?.cancel()
        socket = null
    }

    @Synchronized
    private fun finish(text: String) {
        if (done) return
        done = true
        socket?.close(1000, null)
        listener.onFinal(text)
    }

    @Synchronized
    private fun fail(detail: String) {
        if (done) return
        done = true
        listener.onFailure(detail)
    }
}
