/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.LocalModelInstaller.Companion.sha256
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** Optional real pinned-file import check: set X_ASR_FIXTURES to the extracted archive parent. */
class XAsrImportInteropTest {
    @Test
    fun fixedArchivesInstallAndAMismatchedReplacementKeepsTheGoodCopy() {
        val fixtureRoot = System.getenv("X_ASR_FIXTURES")
        assumeNotNull(fixtureRoot)
        val root = Files.createTempDirectory("x-asr-import").toFile()
        try {
            val installer = LocalModelInstaller(root)
            listOf(ModelCatalogEntry.XAsrOffline, ModelCatalogEntry.XAsrStreaming960).forEach { entry ->
                val source = File(fixtureRoot!!, entry.model.dirName)
                installer.install(entry, ModelSources.directory(source))
                assertTrue(installer.isInstalled(entry.model))
                entry.files.forEach { file ->
                    val installed = installer.modelDir(entry.model).resolve(file.path)
                    assertEquals(file.size, installed.length())
                    assertEquals(file.sha256, sha256(installed))
                }
                try {
                    installer.install(entry, { file: ModelFile ->
                        if (file.path == "tokens.txt") {
                            val bytes = source.resolve(file.path).readBytes()
                            bytes[0] = (bytes[0].toInt() xor 1).toByte()
                            ByteArrayInputStream(bytes)
                        } else source.resolve(file.path).inputStream()
                    })
                    fail("changed tokens must not replace a verified model")
                } catch (_: InstallFailure.Mismatch) {
                    assertTrue(installer.isInstalled(entry.model))
                    assertEquals(entry.files.last().sha256,
                        sha256(installer.modelDir(entry.model).resolve("tokens.txt")))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
