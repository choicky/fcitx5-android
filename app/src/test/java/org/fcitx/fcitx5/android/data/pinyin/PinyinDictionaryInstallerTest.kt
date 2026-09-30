/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class PinyinDictionaryInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installsOnlyAfterSizeAndHashVerification() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)

        val installed = installer.install(entry, ByteArrayInputStream(content))

        assertEquals(content.toList(), installed.readBytes().toList())
        assertTrue(installer.isInstalled(entry))
        assertFalse(temporaryFolder.root.resolve(".${entry.fileName}.download").exists())
    }

    @Test
    fun rejectsMismatchWithoutReplacingExistingDictionary() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)
        installer.install(entry, ByteArrayInputStream(content))

        val failure = assertThrows(DictionaryInstallFailure.Mismatch::class.java) {
            installer.install(entry, ByteArrayInputStream("different".toByteArray()))
        }

        assertEquals(entry.sha256, failure.expected)
        assertEquals(content.toList(), installer.target(entry).readBytes().toList())
        assertTrue(installer.isInstalled(entry))
    }

    @Test
    fun cancellationDoesNotReplaceExistingDictionary() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)
        installer.install(entry, ByteArrayInputStream(content))

        assertThrows(DictionaryInstallFailure.Cancelled::class.java) {
            installer.install(entry, ByteArrayInputStream("replacement".toByteArray()), { true })
        }

        assertEquals(content.toList(), installer.target(entry).readBytes().toList())
        assertTrue(installer.isInstalled(entry))
    }

    @Test
    fun rejectsWhenThereIsNotEnoughSpace() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)

        // The test cannot control filesystem free space; a negative declared size is invalid
        // input and exercises the same guard without allocating a large fixture.
        val impossible = entry.copy(size = Long.MAX_VALUE)
        assertThrows(DictionaryInstallFailure.NotEnoughSpace::class.java) {
            installer.install(impossible, ByteArrayInputStream(content))
        }
    }

    @Test
    fun removesOnlyTheCatalogArtifact() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)
        installer.install(entry, ByteArrayInputStream(content))

        installer.remove(entry)

        assertFalse(installer.target(entry).exists())
    }

    @Test
    fun resumesACompletePartialDownloadBeforeInstalling() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)
        temporaryFolder.root.resolve(".${entry.fileName}.download").writeBytes(content.copyOf(4))

        val installed = installer.install(entry, { offset ->
            assertEquals(4, offset)
            DictionaryStream(ByteArrayInputStream(content.copyOfRange(4, content.size)), offset)
        })

        assertEquals(content.toList(), installed.readBytes().toList())
        assertTrue(installer.isInstalled(entry))
    }

    @Test
    fun replacesPartialFileWhenServerFallsBackToFullResponse() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)
        temporaryFolder.root.resolve(".${entry.fileName}.download").writeBytes(content.copyOf(4))

        val installed = installer.install(entry, { offset ->
            assertEquals(4, offset)
            // A 200 response is represented by offset zero, so the installer
            // must truncate the partial file instead of appending duplicate bytes.
            DictionaryStream(ByteArrayInputStream(content), 0)
        })

        assertEquals(content.toList(), installed.readBytes().toList())
        assertTrue(installer.isInstalled(entry))
    }

    @Test
    fun reportsMonotonicProgressAndPreservesPartialOnNetworkFailure() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)
        val progress = mutableListOf<Pair<Long, Long>>()
        val source = object : InputStream() {
            private var sent = false

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (sent) throw IOException("connection lost")
                sent = true
                content.copyInto(buffer, offset, 0, 4)
                return 4
            }

            override fun read(): Int = error("buffered read expected")
        }

        assertThrows(DictionaryInstallFailure.Io::class.java) {
            installer.install(
                entry,
                { DictionaryStream(source, 0, content.size.toLong()) },
                progress = { done, total -> progress += done to total }
            )
        }

        assertTrue(progress.zipWithNext().all { it.first.first <= it.second.first })
        assertEquals(content.size.toLong(), progress.last().second)
        assertEquals(4L, installer.stagedBytes(entry))
        assertFalse(installer.isInstalled(entry))
        installer.discardDownload(entry)
        assertEquals(0, installer.stagedBytes(entry))
    }

    private fun entry(content: ByteArray) = PinyinDictionaryCatalogEntry(
        id = "test-dictionary",
        displayName = "Test dictionary",
        version = "test",
        url = "https://example.test/test.dict",
        size = content.size.toLong(),
        sha256 = PinyinDictionaryInstaller.sha256(
            temporaryFolder.newFile("hash-input").also { it.writeBytes(content) }
        ),
        license = "test",
        sourceRepository = "https://example.test/source",
        sourceRevision = "test",
        sourceInputSha256 = "test",
        limitations = "test"
    )
}
