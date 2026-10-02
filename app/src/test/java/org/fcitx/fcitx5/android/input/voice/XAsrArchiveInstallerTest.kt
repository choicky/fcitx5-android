/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class XAsrArchiveInstallerTest {
    private val root = Files.createTempDirectory("x-asr-archive").toFile()
    private val installer = LocalModelInstaller(root)
    private val paths = LocalAsrModel.XAsrOffline.requiredFiles

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun entry(contents: Map<String, ByteArray>) = ModelCatalogEntry(
        LocalAsrModel.XAsrOffline,
        "test-archive",
        paths.map { ModelFile(it, contents[it]!!.size.toLong(), hashBytes(contents[it]!!)) },
        null
    )

    private fun hashBytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun archive(contents: Map<String, ByteArray>, badPath: String? = null): File {
        val file = installer.archiveFile(LocalAsrModel.XAsrOffline)
        BZip2CompressorOutputStream(file.outputStream()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                contents.forEach { (path, bytes) ->
                    tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                badPath?.let {
                    tar.putArchiveEntry(TarArchiveEntry(it).apply { size = 1 })
                    tar.write(1)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return file
    }

    @Test
    fun fixedArchiveExtractsAndUsesNormalAtomicInstall() {
        val contents = paths.associateWith { "x-$it".toByteArray() }
        val archive = archive(contents)
        installer.installArchive(entry(contents), archive,
            archive.length(), hash(archive))
        assertTrue(installer.isInstalled(LocalAsrModel.XAsrOffline))
        assertFalse(installer.archiveFile(LocalAsrModel.XAsrOffline).exists())
    }

    @Test
    fun traversalEntryIsRejectedAndDoesNotEscapeExtraction() {
        val contents = paths.associateWith { "x-$it".toByteArray() }
        val archive = archive(contents, "../outside")
        val failure = runCatching {
            installer.installArchive(entry(contents), archive,
                archive.length(), hash(archive))
        }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Mismatch)
        assertFalse(root.resolve("outside").exists())
        assertFalse(installer.isInstalled(LocalAsrModel.XAsrOffline))
    }

    @Test
    fun archiveMismatchLeavesExistingInstallUntouched() {
        val contents = paths.associateWith { "old-$it".toByteArray() }
        val archive = archive(contents)
        val entry = entry(contents)
        installer.installArchive(entry, archive, archive.length(), hash(archive))
        val before = installer.modelDir(LocalAsrModel.XAsrOffline).resolve(paths.first()).readBytes()
        val changed = paths.associateWith { "new-$it".toByteArray() }
        val bad = archive(changed)
        val failure = runCatching {
            installer.installArchive(entry, bad, bad.length(), "0".repeat(64))
        }.exceptionOrNull()
        assertTrue(failure is InstallFailure.Mismatch)
        assertTrue(installer.isInstalled(LocalAsrModel.XAsrOffline))
        assertTrue(before.contentEquals(installer.modelDir(LocalAsrModel.XAsrOffline).resolve(paths.first()).readBytes()))
    }
}
