/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class XAsrArchiveInteropTest {
    @Test
    fun fixedArchivesInstallTheirFourRecognitionFiles() {
        val root = System.getenv("X_ASR_FIXTURES") ?: ""
        assumeTrue(root.isNotBlank())
        val fixture = java.io.File(root)
        val temp = Files.createTempDirectory("x-asr-archive-interop").toFile()
        val installer = LocalModelInstaller(temp)
        listOf(ModelCatalogEntry.XAsrOffline, ModelCatalogEntry.XAsrStreaming960).forEach { entry ->
            val archiveName = entry.archiveUrl!!.substringAfterLast('/')
            val source = fixture.resolve(archiveName)
            assumeTrue(source.isFile && source.length() == entry.archiveSize)
            val target = installer.archiveFile(entry.model)
            source.copyTo(target)
            installer.installArchive(entry, target, entry.archiveSize!!, entry.archiveSha256!!)
            assertTrue(installer.isInstalled(entry.model))
        }
    }
}
