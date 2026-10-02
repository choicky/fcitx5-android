/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorRedactionTest {

    // made-up values in the providers' formats, assembled at run time so that no key-shaped
    // literal is in the source (secret scanners match on format)
    private val tencentId = listOf("AK", "ID", "test0fake1value2not3real4xyz5abc").joinToString("")
    private val tencentKey = listOf("test0Fake1Secret2", "Key3not4Real5xy").joinToString("")
    private val qwenKey = listOf("sk", "-", "test0fake1key2not3real4abc5def6").joinToString("")

    @Test
    fun signedUrlLosesItsQuery() {
        val detail = "ProtocolException: Expected HTTP 101 from wss://asr.cloud.tencent.com/asr/v2/1250000000" +
                "?secretid=$tencentId&timestamp=1700000000&signature=abc%2Bdef%3D (HTTP 401)"
        val out = ErrorRedaction.redact(detail)
        assertEquals(
            "ProtocolException: Expected HTTP 101 from wss://asr.cloud.tencent.com/asr/v2/1250000000?*** (HTTP 401)",
            out
        )
    }

    @Test
    fun userInfoInUrlIsDropped() {
        assertEquals(
            "failed https://example.org/v1/audio/transcriptions",
            ErrorRedaction.redact("failed https://user:pa55w0rd@example.org/v1/audio/transcriptions")
        )
    }

    @Test
    fun knownSecretsAreMaskedWhereverTheyAppear() {
        val out = ErrorRedaction.redact("server echoed key $tencentKey in body", listOf(tencentKey))
        assertFalse(out.contains(tencentKey))
        assertEquals("server echoed key *** in body", out)
    }

    @Test
    fun headerAndFieldStylesAreMasked() {
        val out = ErrorRedaction.redact(
            """Authorization: Bearer $qwenKey; {"api_key":"abc123secret","token"=tok} x-api-key: $qwenKey"""
        )
        assertFalse(out, out.contains(qwenKey))
        assertFalse(out, out.contains("abc123secret"))
        assertFalse(out, out.contains("tok}"))
        assertTrue(out, out.startsWith("Authorization: ***"))
    }

    @Test
    fun randomLookingRunsAreMaskedButNamesAndLogIdsStay() {
        val out = ErrorRedaction.redact(
            "InvalidApiKey $qwenKey model sherpa-onnx-x-asr-960ms-streaming-2026-06-05 " +
                    "(HTTP 403, X-Tt-Logid=20260928011500AB12CD34EF56GH78)"
        )
        assertFalse(out, out.contains(qwenKey.drop(3)))
        assertTrue(out, out.contains("sherpa-onnx-x-asr-960ms-streaming-2026-06-05"))
        assertTrue(out, out.contains("X-Tt-Logid=20260928011500AB12CD34EF56GH78"))
    }

    @Test
    fun ordinaryDetailsAreUnchanged() {
        listOf(
            "capture: AudioRecord read failed: -3",
            "no final result within 8000ms",
            "server closed the session: 1011 internal error",
            "UnknownHostException: Unable to resolve host \"dashscope.aliyuncs.com\"",
            "server error 45000001: invalid request"
        ).forEach { assertEquals(it, ErrorRedaction.redact(it)) }
    }

    @Test
    fun longDetailsAreBounded() {
        val out = ErrorRedaction.redact("x ".repeat(200))
        assertEquals(ErrorRedaction.MAX_LENGTH, out.length)
        assertTrue(out.endsWith("…"))
    }
}
