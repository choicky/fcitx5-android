/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * The FunASR real-time WebSocket protocol in "2pass" mode (modelscope/FunASR v1.4.16
 * runtime/docs/websocket_protocol.md, runtime/python/websocket/funasr_wss_server.py):
 * a JSON configuration first, then PCM16 audio in 60 ms packets (the online model decodes
 * every `chunk_interval` packets), and `{"is_speaking": false, "is_end": true}` at the end.
 * `2pass-online` messages carry the newest online text of the current sentence (appended);
 * a `2pass-offline` message is the corrected sentence that replaces it. The Python server
 * ends with an acknowledgement carrying `is_end`.
 */
internal object FunAsr2PassProtocol {
    /** 60 ms of 16 kHz PCM16, as the upstream client sends it. */
    const val PACKET_BYTES = 1920

    /** Offered by the upstream client and required by the upstream Python server. */
    const val SUBPROTOCOL = "binary"

    sealed interface Message {
        data class Online(val text: String) : Message
        data class Offline(val text: String) : Message
        data class End(val error: String?) : Message
        data object Unknown : Message
    }

    fun start(): String = buildJsonObject {
        put("mode", "2pass")
        put("chunk_size", buildJsonArray { add(5); add(10); add(5) })
        put("chunk_interval", 10)
        put("wav_name", "fcitx5")
        put("wav_format", "pcm")
        put("audio_fs", 16000)
        put("is_speaking", true)
        put("itn", true)
    }.toString()

    fun end(): String = buildJsonObject {
        put("is_speaking", false)
        put("is_end", true)
    }.toString()

    fun parse(message: String): Message = runCatching {
        val json = Json.parseToJsonElement(message) as JsonObject
        fun field(name: String) = json[name]?.jsonPrimitive
        if (field("is_end")?.booleanOrNull == true) {
            val error = field("error")?.contentOrNull
                ?: if (field("is_final")?.booleanOrNull == false) "server reported an error" else null
            return@runCatching Message.End(error)
        }
        val text = field("text")?.contentOrNull.orEmpty()
        when (field("mode")?.contentOrNull) {
            "2pass-online", "online" -> Message.Online(text)
            "2pass-offline", "offline" -> Message.Offline(text)
            else -> Message.Unknown
        }
    }.getOrDefault(Message.Unknown)

    /** Corrected sentences followed by the online text of the sentence in progress. */
    class Transcript {
        private val confirmed = mutableListOf<String>()
        private var online = ""

        fun accept(message: Message): String {
            when (message) {
                is Message.Online -> online += message.text
                is Message.Offline -> {
                    online = ""
                    confirmed += message.text
                }
                else -> Unit
            }
            return text
        }

        val text get() = joinTranscriptParts(confirmed + online)
    }
}

/** One session against a FunASR 2-pass WebSocket server (self-hosted). */
internal class FunAsr2PassClient(
    private val client: OkHttpClient,
    private val url: String,
    private val bearerToken: String?,
    private val listener: NetworkAsrClient.Listener
) : NetworkAsrClient {

    private val transcript = FunAsr2PassProtocol.Transcript()
    private val chunker = Pcm16Chunker(FunAsr2PassProtocol.PACKET_BYTES)
    private var socket: WebSocket? = null

    @Volatile
    private var finishing = false

    // guarded by this
    private var done = false

    override fun connect() {
        val request = Request.Builder().url(url).apply {
            // the upstream Python server rejects connections without this subprotocol (HTTP 400);
            // its own client always offers it (funasr_wss_client.py)
            header("Sec-WebSocket-Protocol", FunAsr2PassProtocol.SUBPROTOCOL)
            bearerToken?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
        }.build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // the protocol has no start acknowledgement: an accepted WebSocket is ready
                webSocket.send(FunAsr2PassProtocol.start())
                listener.onEstablished()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val message = FunAsr2PassProtocol.parse(text)) {
                    is FunAsr2PassProtocol.Message.End ->
                        if (message.error != null) fail(message.error) else finish()
                    FunAsr2PassProtocol.Message.Unknown -> Unit
                    else -> listener.onPartial(transcript.accept(message))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (finishing) finish() else fail("server closed the session: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.let { " (HTTP ${it.code})" }.orEmpty()
                fail("${t.javaClass.simpleName}: ${t.message}$status")
            }
        })
    }

    override fun send(pcm: ShortArray, count: Int) {
        chunker.add(pcm, count).forEach { socket?.send(it.toByteString()) }
    }

    override fun finishInput() {
        chunker.drain().takeIf { it.isNotEmpty() }?.let { socket?.send(it.toByteString()) }
        finishing = true
        socket?.send(FunAsr2PassProtocol.end())
    }

    override fun cancel() {
        synchronized(this) { done = true }
        socket?.cancel()
        socket = null
    }

    private fun finish() {
        synchronized(this) {
            if (done) return
            done = true
        }
        socket?.close(1000, null)
        listener.onFinal(transcript.text)
    }

    private fun fail(detail: String) {
        synchronized(this) {
            if (done) return
            done = true
        }
        listener.onFailure(detail)
    }
}
