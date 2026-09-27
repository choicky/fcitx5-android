/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * The Fun-ASR-Nano streaming server protocol (modelscope/FunASR v1.4.16
 * `funasr/bin/realtime_ws.py`, console script `funasr-realtime-server`): text `START`, which the
 * server confirms with `{"event": "started"}`, then int16 PCM at 16 kHz, then `STOP`. Every
 * result carries the whole state: locked `sentences` plus the current `partial` (which is
 * re-decoded and may change); `STOP` yields a result with `is_final` and `{"event": "stopped"}`.
 * This protocol is not the FunASR 2-pass one; the upstream server needs an NVIDIA GPU (vLLM).
 */
internal object FunAsrNanoProtocol {
    const val START = "START"
    const val STOP = "STOP"

    sealed interface Message {
        data object Started : Message
        data object Stopped : Message
        data class Error(val detail: String) : Message
        data class Result(val sentences: List<String>, val partial: String, val isFinal: Boolean) :
            Message {
            val text get() = joinTranscriptParts(sentences + partial)
        }

        data object Unknown : Message
    }

    fun parse(message: String): Message = runCatching {
        val json = Json.parseToJsonElement(message) as JsonObject
        when (json["event"]?.jsonPrimitive?.contentOrNull) {
            "started" -> return@runCatching Message.Started
            "stopped" -> return@runCatching Message.Stopped
            "error" -> return@runCatching Message.Error(json["error"]?.jsonPrimitive?.contentOrNull.orEmpty())
            null -> Unit
            else -> return@runCatching Message.Unknown
        }
        val sentences = (json["sentences"] as? JsonArray ?: return@runCatching Message.Unknown)
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        Message.Result(
            sentences,
            json["partial"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            json["is_final"]?.jsonPrimitive?.booleanOrNull == true
        )
    }.getOrDefault(Message.Unknown)
}

/** One session against a self-hosted Fun-ASR-Nano streaming server. */
internal class FunAsrNanoServerClient(
    private val client: OkHttpClient,
    private val url: String,
    private val bearerToken: String?,
    private val listener: NetworkAsrClient.Listener
) : NetworkAsrClient {

    // guarded by this: audio waits for the "started" confirmation
    private val pending = mutableListOf<ByteArray>()
    private var started = false
    private var stopRequested = false
    private var done = false
    private var lastText = ""
    private var socket: WebSocket? = null

    override fun connect() {
        val request = Request.Builder().url(url).apply {
            bearerToken?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
        }.build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(FunAsrNanoProtocol.START)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val message = FunAsrNanoProtocol.parse(text)) {
                    FunAsrNanoProtocol.Message.Started -> onStarted(webSocket)
                    is FunAsrNanoProtocol.Message.Result -> {
                        synchronized(this@FunAsrNanoServerClient) { lastText = message.text }
                        if (message.isFinal) finish() else listener.onPartial(message.text)
                    }
                    // STOP without pending audio gives no final result, only "stopped"
                    FunAsrNanoProtocol.Message.Stopped -> finish()
                    is FunAsrNanoProtocol.Message.Error -> fail(message.detail)
                    FunAsrNanoProtocol.Message.Unknown -> Unit
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                fail("server closed the session: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.let { " (HTTP ${it.code})" }.orEmpty()
                fail("${t.javaClass.simpleName}: ${t.message}$status")
            }
        })
    }

    private fun onStarted(webSocket: WebSocket) {
        val (queued, stopNow) = synchronized(this) {
            if (done || started) return
            started = true
            (pending.toList() to stopRequested).also { pending.clear() }
        }
        listener.onEstablished()
        queued.forEach { webSocket.send(it.toByteString()) }
        if (stopNow) webSocket.send(FunAsrNanoProtocol.STOP)
    }

    override fun send(pcm: ShortArray, count: Int) {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = pcm[i].toInt()
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        val sendNow = synchronized(this) {
            if (!started) pending += bytes
            started && !done
        }
        if (sendNow) socket?.send(bytes.toByteString())
    }

    override fun finishInput() {
        val sendNow = synchronized(this) {
            stopRequested = true
            started && !done
        }
        if (sendNow) socket?.send(FunAsrNanoProtocol.STOP)
    }

    override fun cancel() {
        synchronized(this) { done = true }
        socket?.cancel()
        socket = null
    }

    private fun finish() {
        val text = synchronized(this) {
            if (done) return
            done = true
            lastText
        }
        socket?.close(1000, null)
        listener.onFinal(text)
    }

    private fun fail(detail: String) {
        synchronized(this) {
            if (done) return
            done = true
        }
        listener.onFailure(detail)
    }
}
