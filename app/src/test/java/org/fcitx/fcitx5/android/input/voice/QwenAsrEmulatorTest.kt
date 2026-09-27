/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs QwenAsrClient against a local emulator of the documented event flow (not the real
 * service). Skipped unless QWEN_EMULATOR_URL is set.
 */
class QwenAsrEmulatorTest {

    private class Recorder : NetworkAsrClient.Listener {
        val done = CountDownLatch(1)
        var established = false
        var final: String? = null
        var failure: String? = null
        override fun onEstablished() { established = true }
        override fun onPartial(text: String) {}
        override fun onFinal(text: String) { final = text; done.countDown() }
        override fun onFailure(detail: String) { failure = detail; done.countDown() }
    }

    private val url = System.getenv("QWEN_EMULATOR_URL")
    private fun config(key: String) = QwenAsrConfig(key, "ws", QwenAsrConfig.Region.Beijing, QwenAsrConfig.DEFAULT_MODEL)

    @Test
    fun audioWaitsForTaskStartedAndFinishes() {
        assumeTrue(url != null)
        val r = Recorder()
        val client = QwenAsrClient(OkHttpClient(), config("good-key"), r, url = url!!)
        client.connect()
        // audio arrives immediately, before task-started
        repeat(100) { client.send(ShortArray(320) { (it * 37).toShort() }, 320) }
        client.finishInput()
        assertTrue(r.done.await(10, TimeUnit.SECONDS))
        assertTrue(r.established)
        assertEquals("你好世界", r.final)
    }

    @Test
    fun anInvalidKeyFailsBeforeTheSessionIsEstablished() {
        assumeTrue(url != null)
        val r = Recorder()
        QwenAsrClient(OkHttpClient(), config("bad-key"), r, url = url!!).connect()
        assertTrue(r.done.await(10, TimeUnit.SECONDS))
        assertFalse(r.established)
        assertTrue(r.failure!!.startsWith("InvalidApiKey"))
    }
}
