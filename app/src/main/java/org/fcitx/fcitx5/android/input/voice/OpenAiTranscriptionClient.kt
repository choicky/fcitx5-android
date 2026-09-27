/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenAI-compatible whole-file transcription (`POST …/audio/transcriptions`, multipart `file`
 * and `model`, JSON `{"text": …}`). The utterance is uploaded once after stop, so there are no
 * partial results and no streaming session: this adapter is an optional alternative, not a
 * substitute for the streaming protocols. It never reports [NetworkAsrClient.Listener.onEstablished];
 * its failures happen after the user stopped, where D035 does not fall back anyway.
 */
internal class OpenAiTranscriptionClient(
    private val client: OkHttpClient,
    private val url: String,
    private val model: String,
    private val bearerToken: String?,
    private val listener: NetworkAsrClient.Listener
) : NetworkAsrClient {

    private val pcm = ByteArrayOutputStream()

    // guarded by this
    private var call: Call? = null
    private var done = false

    override fun connect() = Unit

    override fun send(pcm: ShortArray, count: Int) {
        synchronized(this) {
            if (done) return
            for (i in 0 until count) {
                val v = pcm[i].toInt()
                this.pcm.write(v)
                this.pcm.write(v shr 8)
            }
        }
    }

    override fun finishInput() {
        val audio = synchronized(this) {
            if (done) return
            pcm.toByteArray()
        }
        val request = buildRequest(url, model, bearerToken, wav(audio))
        val pending = client.newCall(request)
        synchronized(this) {
            if (done) return
            call = pending
        }
        pending.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) =
                fail("${e.javaClass.simpleName}: ${e.message}")

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        fail("HTTP ${it.code}: ${body.take(120)}")
                        return
                    }
                    val text = parseText(body)
                    if (text == null) fail("unexpected response: ${body.take(120)}") else finish(text)
                }
            }
        })
    }

    override fun cancel() {
        val pending = synchronized(this) {
            done = true
            call
        }
        pending?.cancel()
    }

    private fun finish(text: String) {
        synchronized(this) {
            if (done) return
            done = true
        }
        listener.onFinal(text)
    }

    private fun fail(detail: String) {
        synchronized(this) {
            if (done) return
            done = true
        }
        listener.onFailure(detail)
    }

    companion object {
        /** A canonical 44-byte header: 16 kHz, mono, 16-bit PCM. */
        fun wav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size)
            }
            return header.array() + pcm
        }

        fun buildRequest(url: String, model: String, bearerToken: String?, wav: ByteArray): Request {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", model.ifEmpty { DEFAULT_MODEL })
                .addFormDataPart("response_format", "json")
                .build()
            return Request.Builder().url(url).post(body).apply {
                bearerToken?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
            }.build()
        }

        fun parseText(body: String): String? = runCatching {
            (Json.parseToJsonElement(body) as JsonObject)["text"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        /** Many servers ignore the value but the API requires the field. */
        const val DEFAULT_MODEL = "whisper-1"
    }
}
