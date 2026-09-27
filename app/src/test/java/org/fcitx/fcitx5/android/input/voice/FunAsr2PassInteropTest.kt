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
 * Talks to a real FunASR 2-pass WebSocket server. Skipped unless FUNASR_SERVER_URL and
 * FUNASR_TEST_WAV (16 kHz mono PCM16 WAV) are set.
 */
class FunAsr2PassInteropTest {
    @Test
    fun streamsAWavAndReceivesACorrectedFinal() {
        val url = System.getenv("FUNASR_SERVER_URL")
        val wav = System.getenv("FUNASR_TEST_WAV")?.let(::File)
        assumeTrue(url != null && wav != null && wav.isFile)
        val bytes = wav!!.readBytes()
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray((bytes.size - 44) / 2) { data.getShort() }
        val opened = CountDownLatch(1)
        val done = CountDownLatch(1)
        var partials = 0
        var final: String? = null
        var failure: String? = null
        val client = FunAsr2PassClient(OkHttpClient(), url!!, null, object : NetworkAsrClient.Listener {
            override fun onEstablished() = opened.countDown()
            override fun onPartial(text: String) { partials++ }
            override fun onFinal(text: String) { final = text; done.countDown() }
            override fun onFailure(detail: String) { failure = detail; done.countDown() }
        })
        client.connect()
        assertTrue(opened.await(10, TimeUnit.SECONDS))
        samples.toList().chunked(320).forEach { client.send(it.toShortArray(), it.size); Thread.sleep(20) }
        client.finishInput()
        assertTrue("no final", done.await(120, TimeUnit.SECONDS))
        println("partials=$partials final=$final failure=$failure")
        assertTrue(failure == null && !final.isNullOrBlank())
    }
}
