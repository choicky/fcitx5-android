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
 * Runs TencentAsrClient against a local emulator of the documented flow that re-checks the
 * signature independently (not the real service). Skipped unless TENCENT_EMULATOR_URL is set.
 */
class TencentAsrEmulatorTest {

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

    private val base = System.getenv("TENCENT_EMULATOR_URL")
    private fun client(secret: String, r: Recorder) = TencentAsrClient(
        OkHttpClient(), TencentAsrConfig("1259228442", "AKIDexample", secret, TencentAsrConfig.DEFAULT_ENGINE), r,
        urlOverride = { it.replace("wss://asr.cloud.tencent.com", base!!) }
    )

    @Test
    fun signedHandshakeThenAudioThenFinal() {
        assumeTrue(base != null)
        val r = Recorder()
        val c = client("secretKeyExample", r)
        c.connect()
        repeat(50) { c.send(ShortArray(320), 320) }
        c.finishInput()
        assertTrue(r.done.await(10, TimeUnit.SECONDS))
        assertTrue(r.established)
        assertEquals("实时语音识别", r.final)
    }

    @Test
    fun aWrongSecretFailsBeforeTheHandshakeIsConfirmed() {
        assumeTrue(base != null)
        val r = Recorder()
        client("wrong", r).connect()
        assertTrue(r.done.await(10, TimeUnit.SECONDS))
        assertFalse(r.established)
        assertTrue(r.failure!!.startsWith("4002"))
    }
}
