/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunAsr2PassClientTest {

    /** Records what the client queues, in order; the handshake completes only when asked. */
    private class FakeSocket(val request: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<Any>()
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = sent.add(text)
        override fun send(bytes: ByteString) = sent.add(bytes)
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit

        fun open() = listener.onOpen(
            this,
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("").build()
        )
    }

    private class Events : NetworkAsrClient.Listener {
        var established = 0
        override fun onEstablished() {
            established++
        }

        override fun onPartial(text: String) = Unit
        override fun onFinal(text: String) = Unit
        override fun onFailure(detail: String) = Unit
    }

    private fun connect(events: Events = Events()): Pair<FunAsr2PassClient, FakeSocket> {
        lateinit var socket: FakeSocket
        val client = FunAsr2PassClient(
            { request, listener -> FakeSocket(request, listener).also { socket = it } },
            "ws://127.0.0.1:10095", null, events
        )
        client.connect()
        return client to socket
    }

    @Test
    fun configurationIsQueuedBeforeAudioSentDuringTheHandshake() {
        val events = Events()
        val (client, socket) = connect(events)
        // capture starts right away; the handshake has not completed yet
        client.send(ShortArray(FunAsr2PassProtocol.PACKET_BYTES), FunAsr2PassProtocol.PACKET_BYTES)
        socket.open()
        client.finishInput()

        assertEquals(FunAsr2PassProtocol.start(), socket.sent.first())
        assertEquals(1, socket.sent.count { it == FunAsr2PassProtocol.start() })
        assertTrue(socket.sent[1] is ByteString)
        assertEquals(FunAsr2PassProtocol.end(), socket.sent.last())
        assertEquals(1, events.established)
    }

    @Test
    fun handshakeOffersTheBinarySubprotocol() {
        val (_, socket) = connect()
        assertEquals(FunAsr2PassProtocol.SUBPROTOCOL, socket.request.header("Sec-WebSocket-Protocol"))
    }
}
