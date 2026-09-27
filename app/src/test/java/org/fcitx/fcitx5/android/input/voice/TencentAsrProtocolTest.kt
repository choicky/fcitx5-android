/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.TencentAsrProtocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message shapes from cloud.tencent.com/document/product/1093/48982. */
class TencentAsrProtocolTest {

    private val config = TencentAsrConfig("1259228442", "AKIDexample", "secretKeyExample", "16k_zh_en")

    @Test
    fun signatureMatchesAnIndependentHmacSha1() {
        // expected value computed with Python hmac/hashlib/base64 over the documented format
        val original = "asr.cloud.tencent.com/asr/v2/1259228442?engine_model_type=16k_zh&expired=1673494772" +
            "&needvad=1&nonce=1673408372&secretid=AKIDexample&timestamp=1673408372&voice_format=1" +
            "&voice_id=c64385ee-3e5c-4fc5-bbfd-7c71addb35b0"
        assertEquals("yQynnMp1H9bBKjXqfPFGBeKKT24=", TencentAsrProtocol.sign(original, "secretKeyExample"))
    }

    @Test
    fun signedUrlSortsRawParametersAndEncodesTheSignature() {
        val url = TencentAsrProtocol.signedUrl(config, now = 1700000000, nonce = 42, voiceId = "v-1")
        assertTrue(url.startsWith("wss://asr.cloud.tencent.com/asr/v2/1259228442?engine_model_type=16k_zh_en&expired=1700003600&nonce=42"))
        assertTrue(url.endsWith("&signature=SSzCsZ%2Fx89xqHuxf3y%2BXAw0A%2BdU%3D"))
        assertFalse(url.contains("secretKeyExample"))
    }

    @Test
    fun parsesHandshakeResultsFinalAndErrors() {
        assertEquals(Message.Handshake, TencentAsrProtocol.parse("""{"code":0,"message":"success","voice_id":"RnKu9FODFHK5FPpsrN"}"""))
        assertEquals(
            Message.Result(0, 0, "实时"),
            TencentAsrProtocol.parse("""{"code":0,"message":"success","voice_id":"RnKu9FODFHK5FPpsrN","message_id":"RnKu9FODFHK5FPpsrN_11_0","result":{"slice_type":0,"index":0,"start_time":0,"end_time":1240,"voice_text_str":"实时","word_size":0,"word_list":[]}}""")
        )
        assertEquals(
            Message.Result(0, 2, "实时语音识别"),
            TencentAsrProtocol.parse("""{"code":0,"message":"success","voice_id":"RnKu9FODFHK5FPpsrN","message_id":"RnKu9FODFHK5FPpsrN_33_0","result":{"slice_type":2,"index":0,"start_time":0,"end_time":2840,"voice_text_str":"实时语音识别","word_size":0,"word_list":[]}}""")
        )
        assertEquals(Message.Final, TencentAsrProtocol.parse("""{"code":0,"message":"success","voice_id":"CzhjnqBkv8lk5pRUxhpX","message_id":"CzhjnqBkv8lk5pRUxhpX_241","final":1}"""))
        assertEquals(Message.Error(4002, "鉴权失败"), TencentAsrProtocol.parse("""{"code":4002,"message":"鉴权失败","voice_id":"x"}"""))
    }

    @Test
    fun unstableResultsAreReplacedPerIndex() {
        val t = TencentAsrProtocol.Transcript()
        t.accept(Message.Result(0, 1, "实时"))
        t.accept(Message.Result(0, 2, "实时语音识别"))
        assertEquals("实时语音识别，很好", t.accept(Message.Result(1, 1, "，很好")))
    }

    @Test
    fun configValidationAndDefaults() {
        assertTrue(config.isComplete)
        assertFalse(config.copy(appId = "abc").isComplete)
        assertFalse(config.copy(secretKey = "").isComplete)
        assertEquals(TencentAsrConfig.DEFAULT_ENGINE, TencentAsrConfig.fromStore(mapOf("engine" to "bogus")).engine)
        assertFalse(config.toString().contains("secretKeyExample"))
    }
}
