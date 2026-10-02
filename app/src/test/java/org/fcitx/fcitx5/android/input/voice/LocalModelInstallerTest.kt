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
    private val model = LocalAsrModel.FunAsrNano

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
        val bad = source(mapOf("Qwen3-0.6B/vocab.json" to "tampered".toByteArray().copyOf(contents["Qwen3-0.6B/vocab.json"]!!.size)))
        val failure = runCatching { installer.install(entry, bad) }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Mismatch)
        assertFalse(installer.isInstalled(model))
        assertFalse(installer.modelDir(model).exists())
    }

    @Test
    fun aMissingFileIsReported() {
        val failure = runCatching { installer.install(entry, source(mapOf("embedding.int8.onnx" to null))) }.exceptionOrNull()
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
            if (f.path == "llm.int8.onnx" && failures-- > 0) object : InputStream() {
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
            assertFalse(e.model.production)
            // Retained models still use their pinned downloads; new X-ASR is import-only.
            if (e.model == LocalAsrModel.FunAsrNano || e.model == LocalAsrModel.ZipformerBilingual) {
                assertTrue(e.downloadBase!!.matches(Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}")))
                // Test builds still offer both retained models.
                assertTrue(e.downloadOffered(testBuild = true))
            } else {
                assertNull(e.downloadBase)
                assertFalse(e.downloadOffered(testBuild = true))
                assertFalse(e.downloadOffered(testBuild = false))
            }
            assertTrue(e.sourceName!!.isNotBlank())
            assertTrue(e.sourceUrl!!.startsWith("https://"))
            assertTrue(e.license!!.isNotBlank())
            assertTrue(e.licenseUrl!!.startsWith("https://"))
            assertTrue(e.attribution!!.isNotBlank())
            assertFalse(e.distributionApproved)
        }
        assertTrue(ModelCatalogEntry.FunAsrNano.downloadOffered(testBuild = false))
        assertTrue(ModelCatalogEntry.FunAsrNano.downloadBase!!.contains("/resolve/6f16bd378457e13f36ccf3910df9017f96c346fb"))
    }

    /** Serves the test content from any offset, like a server with Range support. */
    private val resumable: ModelFileSource = { f, from ->
        val bytes = contents[f.path]!!
        ModelStream(ByteArrayInputStream(bytes, from.toInt(), bytes.size - from.toInt()), from)
    }

    /** Fails once with a dropped connection after [cut] bytes of [path], then serves normally. */
    private fun dropsOnce(path: String, cut: Int): ModelFileSource {
        var dropped = false
        return { f, from ->
            val bytes = contents[f.path]!!
            if (f.path == path && !dropped) {
                dropped = true
                ModelStream(object : InputStream() {
                    var i = 0
                    override fun read(): Int = if (i < cut) bytes[i++].toInt() and 0xff else throw IOException("connection reset")
                })
            } else ModelStream(ByteArrayInputStream(bytes, from.toInt(), bytes.size - from.toInt()), from)
        }
    }

    @Test
    fun aDroppedConnectionIsResumedWhereItStopped() {
        val offsets = mutableListOf<Long>()
        val source = dropsOnce("encoder_adaptor.int8.onnx", cut = 5)
        installer.install(entry, { f: ModelFile, from: Long -> offsets += from; source(f, from) }, attempts = 2)
        assertTrue(installer.isInstalled(model))
        // the retry asked for the rest only
        assertEquals(listOf(0L, 5L), offsets.take(2))
    }

    @Test
    fun anUnfinishedDownloadStaysStagedAndIsNeverInstalled() {
        val source = dropsOnce("embedding.int8.onnx", cut = 3)
        val failure = runCatching { installer.install(entry, source, attempts = 1) }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Io)
        assertFalse(installer.isInstalled(model))
        assertTrue(installer.stagedBytes(model) > 0)
        // a later retry (e.g. after the process died) completes it
        val offsets = mutableListOf<Long>()
        installer.install(entry, { f: ModelFile, from: Long -> offsets += from; source(f, from) })
        assertTrue(installer.isInstalled(model))
        assertTrue(offsets.contains(3L))
        assertEquals(0L, installer.stagedBytes(model))
    }

    @Test
    fun aSourceThatCannotResumeStartsTheFileAgain() {
        runCatching { installer.install(entry, dropsOnce("llm.int8.onnx", cut = 4), attempts = 1) }
        // the source ignores the offset (like a server without Range support)
        installer.install(entry, { f: ModelFile, _: Long -> ModelStream(ByteArrayInputStream(contents[f.path]!!)) })
        assertTrue(installer.isInstalled(model))
    }

    @Test
    fun cancellingDiscardsStagedFiles() {
        runCatching { installer.install(entry, dropsOnce("embedding.int8.onnx", cut = 3), attempts = 1) }
        assertTrue(installer.stagedBytes(model) > 0)
        var reads = 0
        val failure = runCatching { installer.install(entry, source(), cancelled = { ++reads > 3 }) }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Cancelled)
        assertEquals(0L, installer.stagedBytes(model))
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".") })
    }

    @Test
    fun stagedFilesOfAnotherCatalogVersionAreDiscarded() {
        runCatching { installer.install(entry, dropsOnce("Qwen3-0.6B/vocab.json", cut = 2), attempts = 1) }
        val offsets = mutableListOf<Long>()
        installer.install(entry.copy(version = "next"), { f: ModelFile, from: Long -> offsets += from; resumable(f, from) })
        assertTrue(offsets.all { it == 0L })
        assertTrue(installer.isInstalled(model))
    }

    @Test
    fun aCorruptedStagedFileIsNotReused() {
        runCatching { installer.install(entry, dropsOnce("Qwen3-0.6B/vocab.json", cut = 2), attempts = 1) }
        // a complete-looking but corrupted staged file must be fetched again
        val staged = root.resolve(".tmp-${model.dirName}/encoder_adaptor.int8.onnx")
        assertTrue(staged.isFile)
        staged.writeBytes(ByteArray(contents["encoder_adaptor.int8.onnx"]!!.size))
        val offsets = mutableMapOf<String, Long>()
        installer.install(entry, { f: ModelFile, from: Long -> offsets[f.path] = from; resumable(f, from) })
        assertEquals(0L, offsets["encoder_adaptor.int8.onnx"])
        assertTrue(installer.isInstalled(model))
    }

    @Test
    fun removalAlsoRemovesTheAdbCopyAndStagedFiles() {
        val legacy = Files.createTempDirectory("legacy").toFile().resolve(model.dirName)
        legacy.mkdirs()
        contents.forEach { (path, bytes) ->
            legacy.resolve(path).apply {
                parentFile.mkdirs()
                writeBytes(bytes)
            }
        }
        // an adb-pushed model is used while the Model Manager has none
        assertEquals(legacy, installer.activeDir(model, legacy))
        installer.install(entry, source())
        assertEquals(installer.modelDir(model), installer.activeDir(model, legacy))
        runCatching { installer.install(entry.copy(version = "next"), dropsOnce("Qwen3-0.6B/vocab.json", cut = 2), attempts = 1) }

        installer.remove(model, legacy)
        assertFalse(legacy.exists())
        assertFalse(installer.isInstalled(model))
        assertEquals(0L, installer.stagedBytes(model))
        assertFalse(model.missingFiles(installer.activeDir(model, legacy)).isEmpty())
    }

    @Test
    fun spaceCheckCountsOnlyWhatIsStillMissing() {
        val huge = entry.copy(files = entry.files + ModelFile("extra", Long.MAX_VALUE / 4, "0".repeat(64)))
        val failure = runCatching { installer.install(huge, source()) }.exceptionOrNull()
        assertTrue(failure is InstallFailure.NotEnoughSpace)
        assertFalse(installer.modelDir(model).exists())
    }
}
