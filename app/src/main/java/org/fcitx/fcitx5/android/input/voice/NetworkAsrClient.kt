/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import java.io.ByteArrayOutputStream

/**
 * One streaming recognition session with a network service (Managed Cloud or Self-hosted).
 * Each provider has its own client for its own protocol; only this small lifecycle is shared,
 * so the capture and event handling of [NetworkAsrBackend] are written once. Callbacks may run
 * on any thread.
 */
internal interface NetworkAsrClient {
    interface Listener {
        /** The service accepted the session and can recognize speech (D035 readiness). */
        fun onEstablished()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onFailure(detail: String)
    }

    fun connect()

    /** 16 kHz mono PCM16 as captured; the client frames it for its protocol. */
    fun send(pcm: ShortArray, count: Int)

    /** End of input; the final result follows. */
    fun finishInput()

    /** Drop the session; no more callbacks. */
    fun cancel()
}

/** Chinese needs no separator; Latin words split across segments keep one space. */
internal fun joinTranscriptParts(parts: Collection<String>): String = buildString {
    parts.map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
        if (isNotEmpty() && last().isLatinOrDigit() && part.first().isLatinOrDigit()) append(' ')
        append(part)
    }
}

private fun Char.isLatinOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** Collects PCM16 little-endian bytes into fixed-size packets, as some APIs recommend. */
internal class Pcm16Chunker(private val packetBytes: Int) {
    private val buffer = ByteArrayOutputStream(packetBytes)

    /** Returns the complete packets that are ready. */
    fun add(pcm: ShortArray, count: Int): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        for (i in 0 until count) {
            val v = pcm[i].toInt()
            buffer.write(v)
            buffer.write(v shr 8)
            if (buffer.size() >= packetBytes) {
                packets += buffer.toByteArray()
                buffer.reset()
            }
        }
        return packets
    }

    /** Whatever is left at the end of input (possibly empty). */
    fun drain(): ByteArray = buffer.toByteArray().also { buffer.reset() }
}
