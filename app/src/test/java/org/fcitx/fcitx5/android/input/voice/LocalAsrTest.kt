/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalAsrTest {

    @Test
    fun missingModelFilesAreListed() {
        val dir = Files.createTempDirectory("local-asr").toFile()
        try {
            val model = LocalAsrModel.FunAsrNano
            assertEquals(model.requiredFiles, model.missingFiles(dir))
            model.requiredFiles.forEach { dir.resolve(it).apply { parentFile!!.mkdirs(); writeText("x") } }
            assertTrue(model.missingFiles(dir).isEmpty())
            dir.resolve("llm.int8.onnx").delete()
            assertEquals(listOf("llm.int8.onnx"), model.missingFiles(dir))
            // a nonexistent directory reports everything missing instead of throwing
            assertEquals(LocalAsrModel.FunAsrNano.requiredFiles, LocalAsrModel.FunAsrNano.missingFiles(dir.resolve("absent")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun funAsrNanoChecksTheFilesSherpaValidates() {
        // sherpa-onnx 1.13.8 OfflineFunASRNanoModelConfig::Validate() requires all three
        // tokenizer files; a partial tokenizer dir must be caught before the native load
        val dir = Files.createTempDirectory("local-asr").toFile()
        try {
            val model = LocalAsrModel.FunAsrNano
            listOf("vocab.json", "merges.txt", "tokenizer.json").forEach {
                assertTrue("Qwen3-0.6B/$it" in model.requiredFiles)
            }
            model.requiredFiles.forEach { dir.resolve(it).apply { parentFile!!.mkdirs(); writeText("x") } }
            dir.resolve("Qwen3-0.6B/merges.txt").delete()
            assertEquals(listOf("Qwen3-0.6B/merges.txt"), model.missingFiles(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun streamingDecodesWhileAudioArrivesAndFlushesOnFinish() {
        val calls = mutableListOf<String>()
        var text = ""
        val session = StreamingLocalAsrSession(object : StreamingDecoder {
            override fun acceptWaveform(samples: FloatArray) {
                calls += "accept"
                text += "字"
            }
            override fun decodeReady(): String = text.also { calls += "decode" }
            override fun inputFinished() {
                calls += "finished"
                text += "。"
            }
            override fun release() {
                calls += "release"
            }
        })
        assertEquals("字", session.accept(FloatArray(4)))
        assertEquals("字字", session.accept(FloatArray(4)))
        assertEquals("字字。", session.finish())
        assertEquals(listOf("accept", "decode", "accept", "decode", "finished", "decode"), calls)
        session.release()
        session.release()
        assertEquals(1, calls.count { it == "release" })
    }

    @Test
    fun bufferingDecodesWholeUtteranceOnceOnFinish() {
        val decoded = mutableListOf<FloatArray>()
        val session = BufferingLocalAsrSession { decoded += it; "你好" }
        assertNull(session.accept(floatArrayOf(0.1f, 0.2f)))
        assertNull(session.accept(floatArrayOf(0.3f)))
        assertEquals(3, session.bufferedSamples)
        assertTrue(decoded.isEmpty()) // nothing decodes while recording
        assertEquals("你好", session.finish())
        assertEquals(1, decoded.size)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), decoded[0], 0f)
    }

    @Test
    fun bufferingCancelNeverDecodes() {
        var decodes = 0
        val session = BufferingLocalAsrSession { decodes++; "x" }
        session.accept(FloatArray(1600))
        session.release()
        assertEquals(0, decodes)
        assertEquals(0, session.bufferedSamples)
    }

    @Test
    fun pcmConversion() {
        assertArrayEquals(
            floatArrayOf(0f, 0.5f, -1f),
            pcm16ToFloat(shortArrayOf(0, 16384, Short.MIN_VALUE, 99), 3),
            1e-6f
        )
    }

    private class FakeRecognizer : LocalAsrRecognizer {
        var released = 0
        override fun newSession(): LocalAsrSession = BufferingLocalAsrSession { "" }
        override fun release() {
            released++
        }
    }

    @Test
    fun cacheLoadsOnceAndReusesForSameModelAndThreads() {
        val loaded = mutableListOf<Pair<LocalAsrModel, Int>>()
        val cache = LocalAsrRecognizerCache { m, t -> loaded += m to t; FakeRecognizer() }
        var now = 100L
        val first = cache.acquire(LocalAsrModel.FunAsrNano, 2) { now.also { now += 1500 } }
        assertEquals(1500L, first.loadMillis)
        first.close()
        val second = cache.acquire(LocalAsrModel.FunAsrNano, 2) { now }
        assertNull(second.loadMillis) // cached
        assertEquals(listOf(LocalAsrModel.FunAsrNano to 2), loaded)
        second.close()
    }

    @Test
    fun evictedRecognizerIsReleasedAfterItsLastLease() {
        val recognizers = mutableListOf<FakeRecognizer>()
        val cache = LocalAsrRecognizerCache { _, _ -> FakeRecognizer().also { recognizers += it } }
        val inUse = cache.acquire(LocalAsrModel.FunAsrNano, 2) { 0 }
        // switching threads loads a new recognizer while the old one is still decoding
        val other = cache.acquire(LocalAsrModel.FunAsrNano, 4) { 0 }
        assertEquals(0, recognizers[0].released)
        inUse.close()
        assertEquals(1, recognizers[0].released)
        inUse.close() // idempotent
        assertEquals(1, recognizers[0].released)
        other.close()
        assertEquals(0, recognizers[1].released) // still cached
        cache.clear()
        assertEquals(1, recognizers[1].released)
    }

    @Test
    fun failedLoadLeavesCacheUsable() {
        var fail = true
        val cache = LocalAsrRecognizerCache { _, _ ->
            if (fail) throw IllegalArgumentException("bad model") else FakeRecognizer()
        }
        val error = runCatching { cache.acquire(LocalAsrModel.FunAsrNano, 1) { 0 } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        fail = false
        cache.acquire(LocalAsrModel.FunAsrNano, 1) { 0 }.close()
    }

    @Test
    fun metricsRtf() {
        val m = LocalAsrMetrics(
            LocalAsrModel.FunAsrNano, 3, null, audioMillis = 4000, firstPartialMillis = null,
            partials = 0, decodeMillis = 1000, stopToFinalMillis = 1100, finalChars = 12
        )
        assertEquals(0.25, m.rtf, 1e-9)
        assertTrue(m.summary().contains("load=cached"))
        assertTrue(m.summary().contains("rtf=0.250"))
        assertTrue(m.summary().contains("pss=-"))
        assertTrue(m.copy(processPssMb = 1320).summary().contains("pss=1320MB"))
        assertEquals(0.0, m.copy(audioMillis = 0).rtf, 0.0)
    }
}
