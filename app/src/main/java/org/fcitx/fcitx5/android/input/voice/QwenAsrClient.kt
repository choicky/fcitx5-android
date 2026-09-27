/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.UUID

/** User configuration for Alibaba Cloud Model Studio real-time ASR (Direct BYOK). */
internal data class QwenAsrConfig(
    val apiKey: String,
    val workspaceId: String,
    val region: Region,
    val model: String
) {
    enum class Region(val host: String) {
        Beijing("cn-beijing"), Singapore("ap-southeast-1")
    }

    val isComplete get() = apiKey.isNotEmpty() && workspaceId.matches(Regex("[A-Za-z0-9-]{1,64}"))

    /** The workspace-specific endpoint the current documentation uses. */
    val url get() = "wss://$workspaceId.${region.host}.maas.aliyuncs.com/api-ws/v1/inference"

    override fun toString() = "QwenAsrConfig(${region.name}, $model, key redacted)"

    companion object {
        const val PROVIDER = "qwen"
        const val API_KEY = "api_key"
        const val WORKSPACE_ID = "workspace_id"
        const val REGION = "region"
        const val MODEL = "model"

        /** Recommended by Model Studio for voice input; fun-asr-realtime shares the protocol. */
        const val DEFAULT_MODEL = "qwen-audio-3.1-asr-flash-streaming"
        val MODELS = listOf(DEFAULT_MODEL, "fun-asr-realtime")

        fun fromStore(fields: Map<String, String>?) = QwenAsrConfig(
            apiKey = fields?.get(API_KEY).orEmpty(),
            workspaceId = fields?.get(WORKSPACE_ID).orEmpty(),
            region = Region.entries.firstOrNull { it.name == fields?.get(REGION) } ?: Region.Beijing,
            model = fields?.get(MODEL)?.takeIf { it in MODELS } ?: DEFAULT_MODEL
        )
    }
}

/**
 * The Model Studio WebSocket protocol for Qwen-Audio-3.x-ASR-Flash-Streaming and
 * Fun-ASR-Realtime (help.aliyun.com model-studio fun-asr-client-events / fun-asr-server-events):
 * a `run-task` text event, PCM16 binary audio after `task-started`, `finish-task` at the end;
 * `result-generated` carries a sentence with `sentence_id` and `sentence_end`, and heartbeats
 * have `sentence_id` 0.
 */
internal object QwenAsrProtocol {

    sealed interface Event {
        data object Started : Event
        data class Result(val sentenceId: Int, val text: String, val sentenceEnd: Boolean) : Event
        data object Heartbeat : Event
        data object Finished : Event
        data class Failed(val code: String, val message: String) : Event
        data object Unknown : Event
    }

    private fun header(action: String, taskId: String) = buildJsonObject {
        put("action", action)
        put("task_id", taskId)
        put("streaming", "duplex")
    }

    fun runTask(taskId: String, model: String, sampleRate: Int = 16000): String = buildJsonObject {
        put("header", header("run-task", taskId))
        putJsonObject("payload") {
            put("task_group", "audio")
            put("task", "asr")
            put("function", "recognition")
            put("model", model)
            putJsonObject("parameters") {
                put("format", "pcm")
                put("sample_rate", sampleRate)
            }
            putJsonObject("input") {}
        }
    }.toString()

    fun finishTask(taskId: String): String = buildJsonObject {
        put("header", header("finish-task", taskId))
        putJsonObject("payload") { putJsonObject("input") {} }
    }.toString()

    fun parse(message: String): Event = runCatching {
        val json = Json.parseToJsonElement(message).jsonObject
        val header = json["header"]!!.jsonObject
        when (header["event"]?.jsonPrimitive?.contentOrNull) {
            "task-started" -> Event.Started
            "task-finished" -> Event.Finished
            "task-failed" -> Event.Failed(
                header["error_code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                header["error_message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
            "result-generated" -> {
                val sentence = json["payload"]?.jsonObject?.get("output")?.jsonObject
                    ?.get("sentence") as? JsonObject ?: return@runCatching Event.Unknown
                val heartbeat = sentence["heartbeat"]?.jsonPrimitive?.booleanOrNull == true
                if (heartbeat) Event.Heartbeat
                else Event.Result(
                    sentence["sentence_id"]?.jsonPrimitive?.intOrNull ?: 0,
                    sentence["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    sentence["sentence_end"]?.jsonPrimitive?.booleanOrNull == true
                )
            }
            else -> Event.Unknown
        }
    }.getOrDefault(Event.Unknown)

    /** Sentences in id order; a later result for a sentence replaces its text. */
    class Transcript {
        private val sentences = sortedMapOf<Int, String>()

        fun accept(result: Event.Result): String {
            sentences[result.sentenceId] = result.text
            return text
        }

        val text get() = joinTranscriptParts(sentences.values)
    }
}

/** One Model Studio real-time ASR task over its own WebSocket. */
internal class QwenAsrClient(
    private val client: OkHttpClient,
    private val config: QwenAsrConfig,
    private val listener: NetworkAsrClient.Listener,
    private val taskId: String = UUID.randomUUID().toString(),
    /** The documented endpoint; replaced only to run against a protocol emulator. */
    private val url: String = config.url
) : NetworkAsrClient {

    private val transcript = QwenAsrProtocol.Transcript()

    // 100 ms of 16 kHz PCM16, as the API recommends
    private val chunker = Pcm16Chunker(3200)

    // guarded by this: audio waits for task-started
    private val pending = mutableListOf<ByteArray>()
    private var started = false
    private var finishRequested = false
    private var done = false
    private var socket: WebSocket? = null

    override fun connect() {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(QwenAsrProtocol.runTask(taskId, config.model))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val event = QwenAsrProtocol.parse(text)) {
                    QwenAsrProtocol.Event.Started -> onStarted(webSocket)
                    is QwenAsrProtocol.Event.Result -> listener.onPartial(transcript.accept(event))
                    QwenAsrProtocol.Event.Finished -> finish()
                    is QwenAsrProtocol.Event.Failed -> fail("${event.code}: ${event.message}")
                    else -> Unit
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
        val (queued, finishNow) = synchronized(this) {
            if (done || started) return
            started = true
            (pending.toList() to finishRequested).also { pending.clear() }
        }
        listener.onEstablished()
        queued.forEach { webSocket.send(it.toByteString()) }
        if (finishNow) webSocket.send(QwenAsrProtocol.finishTask(taskId))
    }

    override fun send(pcm: ShortArray, count: Int) = deliver(chunker.add(pcm, count))

    override fun finishInput() {
        deliver(listOf(chunker.drain()).filter { it.isNotEmpty() })
        val sendNow = synchronized(this) {
            finishRequested = true
            started && !done
        }
        if (sendNow) socket?.send(QwenAsrProtocol.finishTask(taskId))
    }

    private fun deliver(packets: List<ByteArray>) {
        if (packets.isEmpty()) return
        val sendNow = synchronized(this) {
            if (!started) pending += packets
            started && !done
        }
        if (sendNow) packets.forEach { socket?.send(it.toByteString()) }
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
