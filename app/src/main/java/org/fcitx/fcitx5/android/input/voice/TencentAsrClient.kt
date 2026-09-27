/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** User configuration for Tencent Cloud real-time ASR (Direct BYOK). */
internal data class TencentAsrConfig(
    val appId: String,
    val secretId: String,
    val secretKey: String,
    val engine: String
) {
    val isComplete get() = appId.matches(Regex("[0-9]{1,20}")) && secretId.isNotEmpty() && secretKey.isNotEmpty()

    override fun toString() = "TencentAsrConfig($engine, secrets redacted)"

    companion object {
        const val PROVIDER = "tencent"
        const val APP_ID = "app_id"
        const val SECRET_ID = "secret_id"
        const val SECRET_KEY = "secret_key"
        const val ENGINE = "engine"

        /**
         * 16k_zh_en is the large model 1.0 engine for Chinese with English; Hy-ASR-3.0-preview
         * is still an internal trial offering (1093/135476) and not a default.
         */
        const val DEFAULT_ENGINE = "16k_zh_en"
        val ENGINES = listOf(DEFAULT_ENGINE, "16k_zh", "Hy-ASR-3.0-preview")

        fun fromStore(fields: Map<String, String>?) = TencentAsrConfig(
            appId = fields?.get(APP_ID).orEmpty(),
            secretId = fields?.get(SECRET_ID).orEmpty(),
            secretKey = fields?.get(SECRET_KEY).orEmpty(),
            engine = fields?.get(ENGINE)?.takeIf { it in ENGINES } ?: DEFAULT_ENGINE
        )
    }
}

/**
 * Tencent Cloud real-time ASR over WebSocket (cloud.tencent.com/document/product/1093/48982):
 * the request URL is signed with HMAC-SHA1(SecretKey) over the host, path and the raw query
 * parameters sorted by name; the server confirms the handshake with `code` 0, then returns
 * `result.slice_type` 0/1/2 per sentence `index` (1 = unstable and may change, 2 = stable),
 * and `final` 1 after the client's `{"type":"end"}`. Audio is PCM16 at no more than real time,
 * 200 ms per packet as recommended.
 */
internal object TencentAsrProtocol {
    const val HOST = "asr.cloud.tencent.com"
    const val PACKET_BYTES = 6400
    const val END = """{"type":"end"}"""

    /** The signed wss URL; [now] is in seconds. */
    fun signedUrl(
        config: TencentAsrConfig,
        now: Long,
        nonce: Long,
        voiceId: String,
        validSeconds: Long = 3600
    ): String {
        val params = sortedMapOf(
            "engine_model_type" to config.engine,
            "expired" to (now + validSeconds).toString(),
            "nonce" to nonce.toString(),
            "secretid" to config.secretId,
            "timestamp" to now.toString(),
            "voice_format" to "1",
            "voice_id" to voiceId
        )
        val path = "$HOST/asr/v2/${config.appId}"
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val signature = sign("$path?$query", config.secretKey)
        val encoded = params.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        return "wss://$path?$encoded&signature=${encode(signature)}"
    }

    fun sign(original: String, secretKey: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA1"))
        // okio, not java.util.Base64, which needs API 26 (minSdk is 23)
        return mac.doFinal(original.toByteArray()).toByteString().base64()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    sealed interface Message {
        data object Handshake : Message
        data class Result(val index: Int, val sliceType: Int, val text: String) : Message
        data object Final : Message
        data class Error(val code: Int, val message: String) : Message
        data object Unknown : Message
    }

    fun parse(message: String): Message = runCatching {
        val json = Json.parseToJsonElement(message) as JsonObject
        val code = json["code"]?.jsonPrimitive?.intOrNull ?: return@runCatching Message.Unknown
        if (code != 0) {
            return@runCatching Message.Error(code, json["message"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        if (json["final"]?.jsonPrimitive?.intOrNull == 1) return@runCatching Message.Final
        val result = json["result"] as? JsonObject ?: return@runCatching Message.Handshake
        Message.Result(
            result["index"]?.jsonPrimitive?.intOrNull ?: 0,
            result["slice_type"]?.jsonPrimitive?.intOrNull ?: 0,
            result["voice_text_str"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }.getOrDefault(Message.Unknown)

    /** Sentences by index; an unstable result is replaced by later ones for the same index. */
    class Transcript {
        private val sentences = sortedMapOf<Int, String>()

        fun accept(result: Message.Result): String {
            sentences[result.index] = result.text
            return text
        }

        val text get() = joinTranscriptParts(sentences.values)
    }
}

/** One Tencent Cloud real-time ASR session. */
internal class TencentAsrClient(
    private val client: OkHttpClient,
    private val config: TencentAsrConfig,
    private val listener: NetworkAsrClient.Listener,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    /** The documented endpoint; replaced only to run against a protocol emulator. */
    private val urlOverride: ((String) -> String)? = null
) : NetworkAsrClient {

    private val transcript = TencentAsrProtocol.Transcript()
    private val chunker = Pcm16Chunker(TencentAsrProtocol.PACKET_BYTES)

    // guarded by this: audio waits for the handshake confirmation
    private val pending = mutableListOf<ByteArray>()
    private var confirmed = false
    private var endRequested = false
    private var done = false
    private var socket: WebSocket? = null

    override fun connect() {
        val nonce = (SecureRandom().nextInt(Int.MAX_VALUE).toLong() % 1_000_000_000L) + 1
        val url = TencentAsrProtocol.signedUrl(config, clock(), nonce, UUID.randomUUID().toString())
        socket = client.newWebSocket(
            Request.Builder().url(urlOverride?.invoke(url) ?: url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val message = TencentAsrProtocol.parse(text)) {
                        TencentAsrProtocol.Message.Handshake -> onConfirmed(webSocket)
                        is TencentAsrProtocol.Message.Result ->
                            listener.onPartial(transcript.accept(message))
                        TencentAsrProtocol.Message.Final -> finish()
                        is TencentAsrProtocol.Message.Error -> fail("${message.code}: ${message.message}")
                        TencentAsrProtocol.Message.Unknown -> Unit
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
            }
        )
    }

    private fun onConfirmed(webSocket: WebSocket) {
        val (queued, endNow) = synchronized(this) {
            if (done || confirmed) return
            confirmed = true
            (pending.toList() to endRequested).also { pending.clear() }
        }
        listener.onEstablished()
        queued.forEach { webSocket.send(it.toByteString()) }
        if (endNow) webSocket.send(TencentAsrProtocol.END)
    }

    override fun send(pcm: ShortArray, count: Int) = deliver(chunker.add(pcm, count))

    override fun finishInput() {
        deliver(listOf(chunker.drain()).filter { it.isNotEmpty() })
        val sendNow = synchronized(this) {
            endRequested = true
            confirmed && !done
        }
        if (sendNow) socket?.send(TencentAsrProtocol.END)
    }

    private fun deliver(packets: List<ByteArray>) {
        if (packets.isEmpty()) return
        val sendNow = synchronized(this) {
            if (!confirmed) pending += packets
            confirmed && !done
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
