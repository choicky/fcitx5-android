/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.input.voice.FunAsr2PassProtocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Message shapes from FunASR v1.4.16 funasr_wss_server.py and websocket_protocol.md. */
class FunAsr2PassProtocolTest {

    @Test
    fun startAndEndMessages() {
        val start = Json.parseToJsonElement(FunAsr2PassProtocol.start()).jsonObject
        assertEquals("2pass", start["mode"]!!.jsonPrimitive.content)
        assertEquals("[5,10,5]", start["chunk_size"]!!.jsonArray.toString())
        val end = Json.parseToJsonElement(FunAsr2PassProtocol.end()).jsonObject
        assertFalse(end["is_speaking"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun parsesServerMessages() {
        assertEquals(Message.Online("今天"), FunAsr2PassProtocol.parse("""{"mode": "2pass-online", "text": "今天", "wav_name": "fcitx5", "is_final": false}"""))
        assertEquals(Message.Offline("今天天气不错。"), FunAsr2PassProtocol.parse("""{"mode": "2pass-offline", "text": "今天天气不错。", "wav_name": "fcitx5", "is_final": true, "timestamp": "[[100,200]]"}"""))
        assertEquals(Message.End(null), FunAsr2PassProtocol.parse("""{"mode": "2pass", "wav_name": "fcitx5", "is_final": true, "is_end": true}"""))
        assertEquals(Message.End("offline inference failed: x"), FunAsr2PassProtocol.parse("""{"mode": "2pass", "is_final": false, "is_end": true, "error": "offline inference failed: x"}"""))
    }

    @Test
    fun onlineTextIsAppendedAndReplacedByTheCorrectedSentence() {
        // as runtime/python/websocket/funasr_wss_client.py assembles it
        val t = FunAsr2PassProtocol.Transcript()
        t.accept(Message.Online("今天"))
        assertEquals("今天天汽", t.accept(Message.Online("天汽")))
        assertEquals("今天天气不错。", t.accept(Message.Offline("今天天气不错。")))
        assertEquals("今天天气不错。我们", t.accept(Message.Online("我们")))
        assertEquals("今天天气不错。我们出去吧。", t.accept(Message.Offline("我们出去吧。")))
    }
}
