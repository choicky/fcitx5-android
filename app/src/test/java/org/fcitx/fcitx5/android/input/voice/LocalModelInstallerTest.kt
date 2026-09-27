/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class LocalModelInstallerTest {

    private val root = Files.createTempDirectory("models").toFile()
    private val installer = LocalModelInstaller(root)
    private val model = LocalAsrModel.ZipformerZh

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A small stand-in for the model with real hashes over test content. */
    private val contents = model.requiredFiles.associateWith { "content of $it".toByteArray() }
    private val entry = ModelCatalogEntry(
        model, "test", contents.map { (path, bytes) -> ModelFile(path, bytes.size.toLong(), sha(bytes)) }, null
    )
    private fun source(overrides: Map<String, ByteArray?> = emptyMap()): (ModelFile) -> InputStream? = { f ->
        val bytes = if (f.path in overrides) overrides[f.path] else contents[f.path]
        bytes?.let(::ByteArrayInputStream)
    }

    @Test
    fun verifiedInstallIsComplete() {
        val progress = mutableListOf<Long>()
        installer.install(entry, source(), onProgress = { done, _ -> progress += done })
        assertTrue(installer.isInstalled(model))
        assertEquals(entry.totalBytes, progress.last())
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".") })
    }

    @Test
    fun aHashMismatchLeavesNothingInstalled() {
        val bad = source(mapOf("tokens.txt" to "tampered".toByteArray().copyOf(contents["tokens.txt"]!!.size)))
        val failure = runCatching { installer.install(entry, bad) }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Mismatch)
        assertFalse(installer.isInstalled(model))
        assertFalse(installer.modelDir(model).exists())
    }

    @Test
    fun aMissingFileIsReported() {
        val failure = runCatching { installer.install(entry, source(mapOf("joiner.int8.onnx" to null))) }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Missing)
        assertFalse(installer.modelDir(model).exists())
    }

    @Test
    fun cancellationKeepsTheOldInstall() {
        installer.install(entry, source())
        var reads = 0
        val failure = runCatching { installer.install(entry, source(), cancelled = { ++reads > 2 }) }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Cancelled)
        // the earlier verified copy is untouched
        assertTrue(installer.isInstalled(model))
    }

    @Test
    fun ioErrorsAreRetried() {
        var failures = 1
        val flaky: (ModelFile) -> InputStream? = { f ->
            if (f.path == "decoder.onnx" && failures-- > 0) object : InputStream() {
                override fun read(): Int = throw IOException("connection reset")
            } else ByteArrayInputStream(contents[f.path]!!)
        }
        installer.install(entry, flaky, attempts = 2)
        assertTrue(installer.isInstalled(model))
        failures = 1
        assertTrue(runCatching { installer.install(entry, flaky, attempts = 1) }.exceptionOrNull() is InstallFailure.Io)
    }

    @Test
    fun removalIsAtomicAndAnInterruptedSwapIsRecovered() {
        installer.install(entry, source())
        installer.remove(model)
        assertFalse(installer.isInstalled(model))
        // a crash between "dest -> .old" and ".tmp -> dest" leaves only .old: it is put back
        installer.install(entry, source())
        val dir = installer.modelDir(model)
        assertTrue(dir.renameTo(root.resolve(".old-${model.dirName}")))
        installer.cleanStale(model)
        assertTrue(installer.isInstalled(model))
    }

    @Test
    fun catalogMatchesTheRuntimeFileListsAndLicensingGates() {
        ModelCatalogEntry.entries.forEach { e ->
            assertEquals(e.model.requiredFiles.toSet(), e.files.map { it.path }.toSet())
            e.files.forEach { assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))) }
            // research models only (D037)
            assertFalse(e.model.production)
        }
        // A: no licence for the weights, so no project-offered download
        assertNull(ModelCatalogEntry.ZipformerZh.downloadBase)
        // B: pinned upstream revision, HTTPS
        assertTrue(ModelCatalogEntry.FunAsrNano.downloadBase!!.startsWith("https://huggingface.co/"))
        assertTrue(ModelCatalogEntry.FunAsrNano.downloadBase!!.contains("/resolve/6f16bd378457e13f36ccf3910df9017f96c346fb"))
    }
}
