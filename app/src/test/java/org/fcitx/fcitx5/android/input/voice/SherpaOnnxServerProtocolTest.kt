/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SherpaOnnxServerProtocolTest {

    @Test
    fun audioIsLittleEndianFloat32() {
        val frame = SherpaOnnxServerProtocol.audioFrame(shortArrayOf(0, 16384, -32768, 99), 3)
        assertEquals(12, frame.size)
        val floats = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals(0f, floats.get(0))
        assertEquals(0.5f, floats.get(1))
        assertEquals(-1f, floats.get(2))
    }

    @Test
    fun parsesPythonAndCppServerMessages() {
        // python-api-examples/streaming_server.py
        assertEquals(
            SherpaOnnxServerProtocol.Result("你好", 0, false),
            SherpaOnnxServerProtocol.parse("""{"text": "你好", "segment": 0}""")
        )
        // sherpa-onnx-online-websocket-server (OnlineRecognizerResult::AsJsonString)
        val cpp = SherpaOnnxServerProtocol.parse(
            """{ "text": "HELLO", "tokens": [], "timestamps": [], "segment": 2, "start_time": 1.0, "is_final": true, "is_eof": true }"""
        )!!
        assertEquals(2, cpp.segment)
        assertTrue(cpp.isEof)
        assertNull(SherpaOnnxServerProtocol.parse("Done!"))
    }

    @Test
    fun segmentsAreRevisedInPlaceAndJoinedInOrder() {
        val t = SherpaOnnxServerProtocol.Transcript()
        t.accept(SherpaOnnxServerProtocol.Result("今", 0, false))
        t.accept(SherpaOnnxServerProtocol.Result("今天", 0, false))
        t.accept(SherpaOnnxServerProtocol.Result("天气", 1, false))
        assertEquals("今天天气", t.text)
        // a later message revises segment 0 in place
        assertEquals("今天好天气", t.accept(SherpaOnnxServerProtocol.Result("今天好", 0, false)))
    }

    @Test
    fun latinWordsAcrossSegmentsKeepASpace() {
        // observed against the upstream server: "MONDAY" | "TODAY IS" | "THE DAY AFTER"
        assertEquals(
            "昨天是 MONDAY TODAY IS THE DAY AFTER TOMORROW是星期三",
            SherpaOnnxServerProtocol.joinSegments(listOf("昨天是 MONDAY", "TODAY IS", "THE DAY AFTER TOMORROW是星期三"))
        )
        assertEquals("你好世界", SherpaOnnxServerProtocol.joinSegments(listOf("你好", " ", "世界")))
    }
}
