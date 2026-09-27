/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks to a real OpenAI-compatible transcription server (for example FunASR's
 * `funasr-server --model sensevoice --device cpu`). Skipped unless OPENAI_SERVER_URL and
 * OPENAI_TEST_WAV are set.
 */
class OpenAiTranscriptionInteropTest {
    @Test
    fun transcribesAWav() {
        val url = System.getenv("OPENAI_SERVER_URL")
        val wav = System.getenv("OPENAI_TEST_WAV")?.let(::File)
        assumeTrue(url != null && wav != null && wav.isFile)
        val bytes = wav!!.readBytes()
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray((bytes.size - 44) / 2) { data.getShort() }
        val done = CountDownLatch(1)
        var final: String? = null
        var failure: String? = null
        val client = OpenAiTranscriptionClient(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build(),
            url!!, System.getenv("OPENAI_MODEL") ?: "", null, object : NetworkAsrClient.Listener {
                override fun onEstablished() {}
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) { final = text; done.countDown() }
                override fun onFailure(detail: String) { failure = detail; done.countDown() }
            })
        client.connect()
        samples.toList().chunked(320).forEach { client.send(it.toShortArray(), it.size) }
        val stopAt = System.nanoTime()
        client.finishInput()
        assertTrue(done.await(120, TimeUnit.SECONDS))
        println("stopToFinal=${(System.nanoTime() - stopAt) / 1_000_000}ms final=$final failure=$failure")
        assertTrue(failure == null && !final.isNullOrBlank())
    }
}
