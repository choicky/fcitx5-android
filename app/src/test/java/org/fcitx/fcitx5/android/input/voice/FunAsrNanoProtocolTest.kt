/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.FunAsrNanoProtocol.Message
import org.junit.Assert.assertEquals
import org.junit.Test

/** Message shapes from FunASR v1.4.16 funasr/bin/realtime_ws.py (_build_response, events). */
class FunAsrNanoProtocolTest {

    @Test
    fun parsesEventsAndResults() {
        assertEquals(Message.Started, FunAsrNanoProtocol.parse("""{"event": "started"}"""))
        assertEquals(Message.Stopped, FunAsrNanoProtocol.parse("""{"event": "stopped"}"""))
        assertEquals(Message.Error("COMMIT requires --endpoint-mode client"), FunAsrNanoProtocol.parse("""{"event": "error", "error": "COMMIT requires --endpoint-mode client"}"""))
        assertEquals(Message.Unknown, FunAsrNanoProtocol.parse("""{"event": "hotwords_set", "hotwords": []}"""))
        val partial = FunAsrNanoProtocol.parse("""{"sentences": [{"start": 0, "end": 1200, "text": "今天天气不错。"}], "partial": "我们出", "partial_start_ms": 1300, "duration_ms": 2000, "is_final": false}""")
        assertEquals(Message.Result(listOf("今天天气不错。"), "我们出", false), partial)
        assertEquals("今天天气不错。我们出", (partial as Message.Result).text)
        val final = FunAsrNanoProtocol.parse("""{"sentences": [{"text": "今天天气不错。"}, {"text": "我们出去吧。"}], "partial": "", "partial_start_ms": 0, "duration_ms": 3000, "is_final": true}""")
        assertEquals("今天天气不错。我们出去吧。", (final as Message.Result).text)
    }
}
