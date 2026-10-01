/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

/** [ModelSources.download] against a local HTTP server, with the real OkHttp client. */
class ModelSourcesTest {

    private val model = LocalAsrModel.FunAsrNano
    private val contents = model.requiredFiles.associateWith { path -> ByteArray(40_000) { (it * 31 + path.length).toByte() } }
    private val entry = ModelCatalogEntry(
        model, "test", contents.map { (path, bytes) ->
            ModelFile(path, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }, "https://unused.invalid"
    )

    /** Requests seen by the server: path and Range header. */
    private val requests = mutableListOf<Pair<String, String?>>()
    private var dropFirst: String? = null
    private var supportRange = true
    private var rejectRange = false
    private var corrupt: String? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/m/")
            val range = ex.requestHeaders.getFirst("Range")
            synchronized(requests) { requests += path to range }
            val bytes = contents[path]?.let { if (path == corrupt) it.copyOf().also { b -> b[0] = (b[0] + 1).toByte() } else it }
            if (bytes == null) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return@createContext
            }
            if (range != null && rejectRange) {
                ex.sendResponseHeaders(416, -1)
                ex.close()
                return@createContext
            }
            val from = range?.takeIf { supportRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            if (from > 0) {
                ex.responseHeaders.add("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                ex.sendResponseHeaders(206, (bytes.size - from).toLong())
            } else ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { out ->
                if (path == dropFirst) {
                    dropFirst = null
                    // half the file, then the connection goes away
                    out.write(bytes, from, (bytes.size - from) / 2)
                    out.flush()
                    ex.close()
                    return@createContext
                }
                out.write(bytes, from, bytes.size - from)
            }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}/m"
    private val root = Files.createTempDirectory("models").toFile()
    private val installer = LocalModelInstaller(root)
    private val http = OkHttpClient()

    @After
    fun stop() = server.stop(0)

    private fun download() = ModelSources.download(http, entry, base, allowCleartext = true)

    @Test
    fun interruptedFileResumesWithARangeRequest() {
        dropFirst = "encoder_adaptor.int8.onnx"
        installer.install(entry, download(), attempts = 2)
        assertTrue(installer.isInstalled(model))
        val encoder = requests.filter { it.first == "encoder_adaptor.int8.onnx" }
        assertEquals(2, encoder.size)
        assertNull(encoder[0].second)
        assertTrue(encoder[1].second!!.matches(Regex("bytes=[1-9][0-9]*-")))
    }

    @Test
    fun serverWithoutRangeSupportStillInstalls() {
        supportRange = false
        dropFirst = "encoder_adaptor.int8.onnx"
        installer.install(entry, download(), attempts = 2)
        assertTrue(installer.isInstalled(model))
    }

    @Test
    fun aRejectedRangeFallsBackToTheWholeFile() {
        rejectRange = true
        dropFirst = "encoder_adaptor.int8.onnx"
        installer.install(entry, download(), attempts = 2)
        assertTrue(installer.isInstalled(model))
    }

    @Test
    fun aWrongFileFromAnyAddressIsRejected() {
        corrupt = "Qwen3-0.6B/vocab.json"
        val failure = runCatching { installer.install(entry, download(), attempts = 1) }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Mismatch)
        assertFalse(installer.isInstalled(model))
        assertFalse(installer.modelDir(model).exists())
    }

    @Test
    fun aMissingFileOnTheServerIsAnError() {
        val gone = ModelCatalogEntry(model, "test", entry.files.map { if (it.path == "Qwen3-0.6B/vocab.json") it.copy(path = "nope.txt") else it }, base)
        val failure = runCatching { installer.install(gone, ModelSources.download(http, gone, base, true)) }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Io)
    }

    @Test
    fun sourceAddressPolicy() {
        assertNull(modelSourceProblem("https://hf-mirror.com/csukuangfj/x/resolve/0123", allowCleartext = false))
        assertEquals(EndpointProblem.Cleartext, modelSourceProblem("http://192.168.1.2:8000/m", allowCleartext = false))
        assertNull(modelSourceProblem("http://192.168.1.2:8000/m", allowCleartext = true))
        assertEquals(EndpointProblem.Invalid, modelSourceProblem("ftp://host/m", allowCleartext = true))
        assertEquals(EndpointProblem.Invalid, modelSourceProblem("https://host/m?token=x", allowCleartext = false))
        assertEquals(EndpointProblem.Invalid, modelSourceProblem("not a url", allowCleartext = false))
        assertTrue(runCatching { ModelSources.download(http, entry, "http://h/m", allowCleartext = false) }.isFailure)
    }
}
