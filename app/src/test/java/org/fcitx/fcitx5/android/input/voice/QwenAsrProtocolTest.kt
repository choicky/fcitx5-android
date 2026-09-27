/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.input.voice.QwenAsrProtocol.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event shapes are the official examples (help.aliyun.com, fun-asr-*-events). */
class QwenAsrProtocolTest {

    @Test
    fun runAndFinishTaskMatchTheDocumentedShape() {
        val run = Json.parseToJsonElement(QwenAsrProtocol.runTask("t1", "qwen-audio-3.1-asr-flash-streaming")).jsonObject
        val header = run["header"]!!.jsonObject
        assertEquals("run-task", header["action"]!!.jsonPrimitive.content)
        assertEquals("duplex", header["streaming"]!!.jsonPrimitive.content)
        val payload = run["payload"]!!.jsonObject
        assertEquals("audio", payload["task_group"]!!.jsonPrimitive.content)
        assertEquals("recognition", payload["function"]!!.jsonPrimitive.content)
        assertEquals("pcm", payload["parameters"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals(16000, payload["parameters"]!!.jsonObject["sample_rate"]!!.jsonPrimitive.int)
        val finish = Json.parseToJsonElement(QwenAsrProtocol.finishTask("t1")).jsonObject
        assertEquals("finish-task", finish["header"]!!.jsonObject["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesTheDocumentedServerEvents() {
        assertEquals(Event.Started, QwenAsrProtocol.parse("""{"header":{"task_id":"x","event":"task-started","attributes":{}},"payload":{}}"""))
        assertEquals(
            Event.Result(1, "", false),
            QwenAsrProtocol.parse("""{"header":{"task_id":"x","event":"result-generated","attributes":{}},"payload":{"output":{"sentence":{"begin_time":0,"end_time":null,"text":"","sentence_begin":true,"sentence_end":false,"sentence_id":1,"words":[]}},"usage":null}}""")
        )
        assertEquals(
            Event.Result(1, "OK, got it.", true),
            QwenAsrProtocol.parse("""{"header":{"task_id":"x","event":"result-generated","attributes":{}},"payload":{"output":{"sentence":{"begin_time":170,"end_time":920,"text":"OK, got it.","heartbeat":false,"sentence_end":true,"sentence_id":1,"words":[]}},"usage":{"duration":3}}}""")
        )
        assertEquals(Event.Heartbeat, QwenAsrProtocol.parse("""{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"heartbeat":true,"sentence_id":0,"text":""}}}}"""))
        assertEquals(Event.Finished, QwenAsrProtocol.parse("""{"header":{"task_id":"x","event":"task-finished","attributes":{}},"payload":{"output":{},"usage":null}}"""))
        assertEquals(
            Event.Failed("CLIENT_ERROR", "request timeout after 23 seconds."),
            QwenAsrProtocol.parse("""{"header":{"task_id":"x","event":"task-failed","error_code":"CLIENT_ERROR","error_message":"request timeout after 23 seconds.","attributes":{}},"payload":{}}""")
        )
        assertEquals(Event.Unknown, QwenAsrProtocol.parse("not json"))
    }

    @Test
    fun sentencesAreRevisedByIdAndJoined() {
        val t = QwenAsrProtocol.Transcript()
        t.accept(Event.Result(1, "今天", false))
        t.accept(Event.Result(1, "今天天气", true))
        assertEquals("今天天气不错", t.accept(Event.Result(2, "不错", false)))
    }

    @Test
    fun configNeedsKeyAndWorkspaceAndBuildsTheWorkspaceEndpoint() {
        val cfg = QwenAsrConfig.fromStore(mapOf("api_key" to "k", "workspace_id" to "ws123", "region" to "Singapore"))
        assertTrue(cfg.isComplete)
        assertEquals("wss://ws123.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/inference", cfg.url)
        assertEquals(QwenAsrConfig.DEFAULT_MODEL, cfg.model)
        assertFalse(QwenAsrConfig.fromStore(mapOf("api_key" to "k")).isComplete)
        assertFalse(QwenAsrConfig.fromStore(mapOf("api_key" to "k", "workspace_id" to "bad/host")).isComplete)
        assertFalse(cfg.toString().contains("k,"))
    }

    @Test
    fun chunkerMakes100msPackets() {
        val c = Pcm16Chunker(3200)
        assertTrue(c.add(ShortArray(320), 320).isEmpty())
        repeat(3) { c.add(ShortArray(320), 320) }
        val packets = c.add(ShortArray(320), 320)
        assertEquals(1, packets.size)
        assertEquals(3200, packets[0].size)
        assertEquals(0, c.drain().size)
    }
}
