/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class DoubaoAsrProtocolTest {

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }
            .toByteArray()

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }

    /** Splits a client frame into header bytes, sequence and decompressed payload. */
    private fun decodeClientFrame(frame: ByteArray): Triple<ByteArray, Int, ByteArray> {
        val buffer = ByteBuffer.wrap(frame)
        val header = ByteArray(4).also { buffer.get(it) }
        val sequence = buffer.int
        val size = buffer.int
        assertEquals(buffer.remaining(), size)
        val payload = ByteArray(size).also { buffer.get(it) }
        return Triple(header, sequence, gunzip(payload))
    }

    private fun serverResponse(flags: Int, sequence: Int, json: String): ByteArray {
        val body = gzip(json.encodeToByteArray())
        return ByteBuffer.allocate(12 + body.size)
            .put(0x11.toByte()).put((0x90 or flags).toByte()).put(0x11.toByte()).put(0.toByte())
            .putInt(sequence).putInt(body.size).put(body).array()
    }

    @Test
    fun fullClientRequestFraming() {
        val (header, sequence, payload) = decodeClientFrame(DoubaoAsrProtocol.fullClientRequest(1))
        // version 1 / header size 1, full client request + positive sequence, JSON + gzip
        assertArrayEquals(byteArrayOf(0x11, 0x11, 0x11, 0x00), header)
        assertEquals(1, sequence)
        val json = Json.parseToJsonElement(payload.decodeToString()).jsonObject
        val request = json["request"]!!.jsonObject
        assertEquals("bigmodel", request["model_name"]!!.jsonPrimitive.content)
        assertTrue(request["enable_nonstream"]!!.jsonPrimitive.boolean)
        val audio = json["audio"]!!.jsonObject
        assertEquals("pcm", audio["format"]!!.jsonPrimitive.content)
        assertEquals(16000, audio["rate"]!!.jsonPrimitive.int)
        assertEquals(1, audio["channel"]!!.jsonPrimitive.int)
    }

    @Test
    fun audioPacketsAndLastPacket() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val (header, sequence, payload) = decodeClientFrame(DoubaoAsrProtocol.audioRequest(2, pcm, false))
        // audio only + positive sequence, raw bytes + gzip
        assertArrayEquals(byteArrayOf(0x11, 0x21, 0x01, 0x00), header)
        assertEquals(2, sequence)
        assertArrayEquals(pcm, payload)

        val (lastHeader, lastSequence, lastPayload) =
            decodeClientFrame(DoubaoAsrProtocol.audioRequest(7, ByteArray(0), true))
        // last packet: flags 0b0011 and a negated sequence
        assertArrayEquals(byteArrayOf(0x11, 0x23, 0x01, 0x00), lastHeader)
        assertEquals(-7, lastSequence)
        assertEquals(0, lastPayload.size)
    }

    @Test
    fun provisionalResponse() {
        val json = """{"result":{"text":"你好","utterances":[{"text":"你好","definite":false}]}}"""
        val response = DoubaoAsrProtocol.parse(serverResponse(0b0001, 3, json))
                as DoubaoAsrProtocol.Response.Result
        assertEquals(3, response.sequence)
        assertFalse(response.last)
        assertEquals("你好", response.text)
        assertEquals(0, response.definiteUtterances)
    }

    @Test
    fun finalResponse() {
        val json = """{"audio_info":{"duration":1700},"result":{"text":"你好。世界。","utterances":[""" +
                """{"text":"你好。","definite":true},{"text":"世界。","definite":true}]}}"""
        val response = DoubaoAsrProtocol.parse(serverResponse(0b0011, -4, json))
                as DoubaoAsrProtocol.Response.Result
        assertTrue(response.last)
        assertEquals(-4, response.sequence)
        assertEquals("你好。世界。", response.text)
        assertEquals(2, response.definiteUtterances)
    }

    @Test
    fun responseWithoutResult() {
        val response = DoubaoAsrProtocol.parse(serverResponse(0b0001, 2, """{"audio_info":{}}"""))
                as DoubaoAsrProtocol.Response.Result
        assertNull(response.text)
    }

    @Test
    fun errorResponse() {
        val message = "invalid request".encodeToByteArray()
        val frame = ByteBuffer.allocate(12 + message.size)
            .put(0x11.toByte()).put(0xF0.toByte()).put(0x10.toByte()).put(0.toByte())
            .putInt(45000001).putInt(message.size).put(message).array()
        assertEquals(
            DoubaoAsrProtocol.Response.Error(45000001, "invalid request"),
            DoubaoAsrProtocol.parse(frame)
        )
    }
}
