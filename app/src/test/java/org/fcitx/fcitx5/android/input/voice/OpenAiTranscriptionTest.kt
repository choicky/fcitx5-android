/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenAiTranscriptionTest {

    @Test
    fun wavHeaderIs16kMonoPcm16() {
        val wav = OpenAiTranscriptionClient.wav(ByteArray(3200))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 3200, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1.toShort(), b.getShort(20)) // PCM
        assertEquals(1.toShort(), b.getShort(22)) // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(16.toShort(), b.getShort(34))
        assertEquals(3200, b.getInt(40))
        assertEquals(44 + 3200, wav.size)
    }

    @Test
    fun requestIsMultipartWithFileModelAndBearer() {
        val req = OpenAiTranscriptionClient.buildRequest("https://h/v1/audio/transcriptions", "", "t0k", ByteArray(44))
        assertEquals("POST", req.method)
        assertEquals("Bearer t0k", req.header("Authorization"))
        val body = req.body as MultipartBody
        val text = Buffer().also { body.writeTo(it) }.readUtf8()
        assertTrue(text.contains("name=\"file\"; filename=\"audio.wav\""))
        assertTrue(text.contains("name=\"model\"") && text.contains(OpenAiTranscriptionClient.DEFAULT_MODEL))
        assertNull(OpenAiTranscriptionClient.buildRequest("https://h/x", "m", null, ByteArray(44)).header("Authorization"))
    }

    @Test
    fun parsesTheTextField() {
        assertEquals("你好", OpenAiTranscriptionClient.parseText("""{"text":"你好"}"""))
        assertNull(OpenAiTranscriptionClient.parseText("""{"error":{"message":"bad"}}"""))
        assertNull(OpenAiTranscriptionClient.parseText("not json"))
    }

    @Test
    fun endpointPolicyUsesHttpsForTheAdapter() {
        val p = SelfHostedProtocol.OpenAiCompatible
        assertNull(endpointProblem("https://h/v1/audio/transcriptions", allowCleartext = false, protocol = p))
        assertEquals(EndpointProblem.Cleartext, endpointProblem("http://h/v1", allowCleartext = false, protocol = p))
        assertNull(endpointProblem("http://h/v1", allowCleartext = true, protocol = p))
        assertEquals(EndpointProblem.Invalid, endpointProblem("wss://h/v1", allowCleartext = true, protocol = p))
        // streaming protocols still require WebSocket schemes
        assertEquals(EndpointProblem.Invalid, endpointProblem("https://h", allowCleartext = true))
    }

    /** Against a local emulator of the endpoint (not a real server); skipped unless set. */
    @Test
    fun uploadsTheUtteranceAfterStop() {
        val url = System.getenv("OPENAI_EMULATOR_URL")
        assumeTrue(url != null)
        val done = CountDownLatch(1)
        var final: String? = null
        var failure: String? = null
        val client = OpenAiTranscriptionClient(OkHttpClient(), url!!, "whisper-1", "good", object : NetworkAsrClient.Listener {
            override fun onEstablished() {}
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) { final = text; done.countDown() }
            override fun onFailure(detail: String) { failure = detail; done.countDown() }
        })
        client.connect()
        repeat(50) { client.send(ShortArray(320), 320) }
        client.finishInput()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(null, failure)
        assertEquals("整段转写 32000", final)
    }
}
