/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs FunAsrNanoServerClient against a local emulator of realtime_ws.py's message flow (the
 * real server needs an NVIDIA GPU). Skipped unless NANO_EMULATOR_URL is set.
 */
class FunAsrNanoEmulatorTest {
    @Test
    fun startThenAudioThenStopGivesTheFinalSentences() {
        val url = System.getenv("NANO_EMULATOR_URL")
        assumeTrue(url != null)
        val done = CountDownLatch(1)
        var established = false
        var final: String? = null
        val client = FunAsrNanoServerClient(OkHttpClient(), url!!, null, object : NetworkAsrClient.Listener {
            override fun onEstablished() { established = true }
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) { final = text; done.countDown() }
            override fun onFailure(detail: String) { done.countDown() }
        })
        client.connect()
        repeat(100) { client.send(ShortArray(320), 320) }
        client.finishInput()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(established)
        assertEquals("今天天气不错。我们出去吧。", final)
    }
}
