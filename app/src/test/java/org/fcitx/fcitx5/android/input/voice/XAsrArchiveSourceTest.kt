/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class XAsrArchiveSourceTest {
    private val bytes = ByteArray(180_000) { (it * 17).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private val requests = mutableListOf<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/archive") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requests += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            if (from > 0) exchange.responseHeaders.add("Content-Range", "bytes $from-${bytes.lastIndex}/${bytes.size}")
            exchange.sendResponseHeaders(if (from > 0) 206 else 200, (bytes.size - from).toLong())
            exchange.responseBody.use { it.write(bytes, from, bytes.size - from) }
        }
        start()
    }
    private val target = Files.createTempDirectory("x-asr-archive-source").resolve("archive.part").toFile()

    @After fun stop() = server.stop(0)

    @Test
    fun pauseKeepsArchiveAndResumeUsesRange() {
        var paused = false
        val first = runCatching {
            ModelSources.downloadArchive(
                OkHttpClient(), "http://127.0.0.1:${server.address.port}/archive", target,
                bytes.size.toLong(), hash, allowCleartext = true,
                paused = { paused }, onProgress = { done, _ -> if (done > 0) paused = true }
            )
        }.exceptionOrNull()
        assertTrue(first is InstallFailure.Paused)
        val retained = target.length()
        assertTrue(retained in 1 until bytes.size.toLong())
        paused = false
        ModelSources.downloadArchive(
            OkHttpClient(), "http://127.0.0.1:${server.address.port}/archive", target,
            bytes.size.toLong(), hash, allowCleartext = true
        )
        assertEquals(bytes.toList(), target.readBytes().toList())
        assertTrue(requests[1] == "bytes=$retained-")
    }
}
