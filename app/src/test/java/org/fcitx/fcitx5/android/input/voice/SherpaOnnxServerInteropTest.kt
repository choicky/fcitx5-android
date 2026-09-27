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
 * Talks to a real sherpa-onnx streaming server. Skipped unless SHERPA_ONNX_SERVER_URL and
 * SHERPA_ONNX_TEST_WAV (16 kHz mono PCM16 WAV) are set, so CI runs without a server.
 */
class SherpaOnnxServerInteropTest {

    private fun pcm16(wav: File): ShortArray {
        val bytes = wav.readBytes()
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((bytes.size - 44) / 2) { data.getShort() }
    }

    @Test
    fun streamsAWavAndReceivesAFinalTranscript() {
        val url = System.getenv("SHERPA_ONNX_SERVER_URL")
        val wav = System.getenv("SHERPA_ONNX_TEST_WAV")?.let(::File)
        assumeTrue(url != null && wav != null && wav.isFile)
        val samples = pcm16(wav!!)
        val opened = CountDownLatch(1)
        val done = CountDownLatch(1)
        val partials = mutableListOf<String>()
        var final: String? = null
        var failure: String? = null
        val client = SherpaOnnxServerClient(OkHttpClient(), url!!, null, object : SherpaOnnxServerClient.Listener {
            override fun onOpen() = opened.countDown()
            override fun onPartial(text: String) { synchronized(partials) { partials += text } }
            override fun onFinal(text: String) { final = text; done.countDown() }
            override fun onFailure(detail: String) { failure = detail; done.countDown() }
        })
        client.connect()
        assertTrue("no session", opened.await(10, TimeUnit.SECONDS))
        // 20 ms chunks at real time / 4, like AudioCapture reads
        samples.toList().chunked(320).forEach { chunk ->
            client.send(chunk.toShortArray(), chunk.size)
            Thread.sleep(5)
        }
        client.finishInput()
        assertTrue("no final", done.await(30, TimeUnit.SECONDS))
        println("partials=${partials.size} final=$final failure=$failure")
        assertTrue(failure == null && !final.isNullOrBlank())
    }
}
